#include "uac_iso_stream.h"
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <poll.h>
#include <sys/ioctl.h>
#include <time.h>
#include <algorithm>

namespace stream4k60 {
namespace {
constexpr int kStandardRates[] = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 176400, 192000, 352800, 384000};
constexpr uint64_t kGapUs = 100000; // a pause this long (device stall, starved thread) re-anchors the timestamps
constexpr double kPi = 3.14159265358979323846;
uint64_t monotonicUs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull + static_cast<uint64_t>(ts.tv_nsec) / 1000ull;
}
} // namespace

UacIsoStream::UacIsoStream(int fd, uint8_t endpoint, size_t packetBytes, int packetsPerUrb, int urbCount, Format format, Sink sink)
    : fd_(fd), endpoint_(endpoint), packetBytes_(packetBytes), packetsPerUrb_(std::clamp(packetsPerUrb, 1, 128)),
      urbCount_(std::clamp(urbCount, 2, 32)), channels_(std::max(1, format.channels)), outChannels_(std::min(2, std::max(1, format.channels))),
      subslot_(std::clamp(format.subslotBytes, 1, 4)), bits_(format.bitResolution), frameBytes_(static_cast<size_t>(channels_) * static_cast<size_t>(subslot_)),
      requestedRate_(std::max(0, format.sampleRate)), sink_(std::move(sink)) {
    rate_ = requestedRate_;
    resetClock();
}

UacIsoStream::~UacIsoStream() { stop(); }

bool UacIsoStream::start() {
    if (running_.exchange(true)) return false;
    if (fd_ < 0 || packetBytes_ < frameBytes_ || !sink_) { running_ = false; return false; }
    const size_t urbBytes = sizeof(usbfs::Urb) + sizeof(usbfs::IsoPacketDesc) * static_cast<size_t>(packetsPerUrb_);
    for (int i = 0; i < urbCount_; ++i) {
        auto slot = std::make_unique<Slot>();
        slot->urbMemory = static_cast<uint8_t*>(calloc(1, urbBytes));
        if (!slot->urbMemory) { stop(); return false; }
        slot->urb = reinterpret_cast<usbfs::Urb*>(slot->urbMemory);
        slot->storage.resize(packetBytes_ * static_cast<size_t>(packetsPerUrb_));
        auto* urb = slot->urb;
        urb->type = usbfs::URB_TYPE_ISO;
        urb->endpoint = endpoint_;
        urb->flags = usbfs::URB_ISO_ASAP;
        urb->buffer = slot->storage.data();
        urb->buffer_length = static_cast<int32_t>(slot->storage.size());
        urb->number_of_packets = packetsPerUrb_;
        urb->usercontext = slot.get();
        slots_.push_back(std::move(slot));
    }
    // Worst case per URB: every packet full, or the silence standing in for a lost packet (never more than a full one).
    pcm_.assign(static_cast<size_t>(packetsPerUrb_) * (packetBytes_ / frameBytes_ + 1) * static_cast<size_t>(outChannels_), 0.f);
    for (auto& s : slots_) {
        if (!submit(*s)) { stop(); return false; }
    }
    thread_ = std::thread(&UacIsoStream::reapLoop, this);
    return true;
}

bool UacIsoStream::submit(Slot& slot) {
    auto* desc = descriptors(slot);
    for (int p = 0; p < packetsPerUrb_; ++p) desc[p] = {static_cast<uint32_t>(packetBytes_), 0, 0};
    slot.urb->status = 0;
    slot.urb->actual_length = 0;
    slot.urb->error_count = 0;
    slot.urb->start_frame = 0;
    if (ioctl(fd_, USBDEVFS_SUBMITURB, slot.urb) < 0) { lastErrno_ = errno; return false; }
    inFlight_++;
    return true;
}

void UacIsoStream::stop() {
    const bool wasRunning = running_.exchange(false);
    if (wasRunning) for (auto& s : slots_) if (s->urb) ioctl(fd_, USBDEVFS_DISCARDURB, s->urb);
    if (thread_.joinable()) thread_.join();
    // Discarded URBs still belong to the kernel until reaped; free them only then, else leak rather than let it write into freed memory.
    if (usbfs::reapOutstanding(fd_, inFlight_)) {
        for (auto& s : slots_) { free(s->urbMemory); s->urbMemory = nullptr; s->urb = nullptr; }
    } else {
        for (auto& s : slots_) (void)s.release();
    }
    inFlight_ = 0;
    slots_.clear();
}

void UacIsoStream::reapLoop() {
    while (running_) {
        void* context = nullptr;
        if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &context) < 0) {
            if (errno == EAGAIN || errno == EINTR) {
                // usbfs signals POLLOUT when a URB has completed; the timeout only bounds the wait if it doesn't.
                pollfd pfd{fd_, POLLOUT, 0};
                poll(&pfd, 1, 4);
                continue;
            }
            exitErrno_ = errno;
            running_ = false;
            break;
        }
        const uint64_t now = monotonicUs();
        inFlight_--;
        // REAPURB returns the address of the reaped URB itself; our Slot is its usercontext.
        auto* urb = static_cast<usbfs::Urb*>(context);
        auto* slot = urb ? static_cast<Slot*>(urb->usercontext) : nullptr;
        if (!slot || slot->urb != urb) continue;
        if (running_) processUrb(*slot, now);
        if (running_ && !submit(*slot)) { exitErrno_ = lastErrno_.load(); running_ = false; break; }
    }
}

// Type I PCM is little-endian two's complement, MSB-justified in its subslot, so 24-bit in 4 bytes reads as 32-bit.
float UacIsoStream::sample(const uint8_t* p) const {
    switch (subslot_) {
        case 1: return static_cast<float>(static_cast<int8_t>(p[0])) * (1.f / 128.f);
        case 2: return static_cast<float>(static_cast<int16_t>(p[0] | (p[1] << 8))) * (lowByteAudio_.load(std::memory_order_relaxed) ? (1.f / 128.f) : (1.f / 32768.f));
        case 3: return static_cast<float>(static_cast<int32_t>((uint32_t(p[0]) << 8) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 24))) * (1.f / 2147483648.f);
        default: return static_cast<float>(static_cast<int32_t>(uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24))) * (1.f / 2147483648.f);
    }
}

// Each second: the loudest sample. Audio that never leaves the low byte while reaching its top (|x| >= 100) for 3 seconds
// is 48 dB below full scale. It is only reported (stats): an iPhone game at a very low in-game volume looks exactly like
// that, and the same Elgato carries YouTube at full 16-bit level, so the samples are left exactly as sent.
void UacIsoStream::watchLowByte(const uint8_t* data, size_t frames) {
    int peak = 0;
    for (size_t i = 0; i < frames * static_cast<size_t>(channels_); ++i) {
        const int v = static_cast<int16_t>(data[i * 2] | (data[i * 2 + 1] << 8));
        peak = std::max(peak, v < 0 ? -v : v);
    }
    if (peak > 128) {
        restoredBits_ = 0;
        lowByteLoudSeconds_ = 0; lowBytePeak_ = 0; lowByteWindowFrames_ = 0;
        return;
    }
    lowBytePeak_ = std::max(lowBytePeak_, peak);
    lowByteWindowFrames_ += frames;
    const size_t second = static_cast<size_t>(std::max(8000, rate_.load() > 0 ? rate_.load() : requestedRate_));
    if (lowByteWindowFrames_ < second) return;
    if (lowBytePeak_ >= 100 && ++lowByteLoudSeconds_ >= 3) restoredBits_ = 8;
    lowBytePeak_ = 0; lowByteWindowFrames_ = 0;
}

void UacIsoStream::requestDump(const std::string& path, size_t bytes) {
    std::lock_guard<std::mutex> l(dumpMutex_);
    dumpPath_ = path; dump_.clear(); dump_.reserve(bytes); dumpWanted_ = bytes;
}

void UacIsoStream::processUrb(Slot& slot, uint64_t nowUs) {
    const auto* desc = descriptors(slot);
    size_t frames = 0, realFrames = 0, base = 0;
    for (int p = 0; p < packetsPerUrb_; ++p, base += packetBytes_) {
        const auto& d = desc[p];
        packets_++;
        if (d.status != 0) {
            // Lost on the bus: silence of a typical packet's length keeps the sample count in step with time.
            failedPackets_++;
            const size_t fill = std::min(static_cast<size_t>(avgPacketFrames_ + 0.5), packetBytes_ / frameBytes_);
            std::fill_n(pcm_.begin() + static_cast<std::ptrdiff_t>(frames * outChannels_), fill * outChannels_, 0.f);
            frames += fill;
            continue;
        }
        const size_t bytes = std::min<size_t>(d.actual_length, packetBytes_);
        bytes_ += bytes;
        const size_t n = bytes / frameBytes_;
        if (n == 0) continue; // asynchronous devices may send empty packets (warming up, or nothing ready yet)
        avgPacketFrames_ = avgPacketFrames_ == 0 ? static_cast<double>(n) : avgPacketFrames_ + (static_cast<double>(n) - avgPacketFrames_) * 0.05;
        const uint8_t* src = slot.storage.data() + base;
        if (dumpWanted_) {
            std::lock_guard<std::mutex> l(dumpMutex_);
            if (dumpWanted_) {
                dump_.insert(dump_.end(), src, src + std::min(bytes, dumpWanted_ - dump_.size()));
                if (dump_.size() >= dumpWanted_) {
                    if (FILE* f = fopen(dumpPath_.c_str(), "wb")) { fwrite(dump_.data(), 1, dump_.size(), f); fclose(f); }
                    dump_.clear(); dumpWanted_ = 0;
                }
            }
        }
        if (subslot_ == 2) watchLowByte(src, n);
        float* out = pcm_.data() + frames * outChannels_;
        for (size_t f = 0; f < n; ++f, src += frameBytes_)
            for (int c = 0; c < outChannels_; ++c) *out++ = sample(src + static_cast<size_t>(c * subslot_));
        frames += n;
        realFrames += n;
    }
    if (realFrames == 0) return; // nothing from the device this round: no timing information either
    if (lastDataUs_ && nowUs - lastDataUs_ > kGapUs) { if (anchored_) reanchors_++; anchored_ = false; if (!rateChecked_) firstDataUs_ = measureStartUs_ = 0; }
    lastDataUs_ = nowUs;
    checkRate(realFrames, nowUs);
    deliver(frames, nowUs);
}

// Checks the rate against the data actually arriving: some devices ignore SET_CUR, and a UAC2 clock owned by Android's
// audio driver can be neither set nor read. An unknown rate (0) is measured before anything is pushed.
void UacIsoStream::checkRate(size_t realFrames, uint64_t nowUs) {
    if (rateChecked_) return;
    const bool unknown = rate_.load() <= 0;
    // Skip the start, where a device may still send short or empty packets, then count over a window.
    const uint64_t skipUs = unknown ? 100000 : 500000, windowUs = unknown ? 500000 : 2000000;
    if (!firstDataUs_) firstDataUs_ = nowUs;
    if (nowUs - firstDataUs_ < skipUs) return;
    // This URB's frames arrived before its reap time, so the window starts at the first reap and counts the ones after it.
    if (!measureStartUs_) { measureStartUs_ = nowUs; measureFrames_ = 0; return; }
    measureFrames_ += realFrames;
    const uint64_t elapsed = nowUs - measureStartUs_;
    if (elapsed < windowUs) return;
    rateChecked_ = true;
    const double measured = static_cast<double>(measureFrames_) * 1e6 / static_cast<double>(elapsed);
    int best = 0; double bestError = 1e9;
    for (int r : kStandardRates) { const double e = std::abs(measured / r - 1.0); if (e < bestError) { bestError = e; best = r; } }
    const int current = rate_.load();
    if (unknown) rate_ = bestError < 0.03 ? best : static_cast<int>(std::lround(measured));
    else if (best != current && bestError < 0.02 && std::abs(measured / current - 1.0) > 0.04) { rateCorrectedFrom_ = current; rate_ = best; }
    else return;
    resetClock();
}

void UacIsoStream::resetClock() {
    const int r = rate_.load();
    nominalUsPerFrame_ = r > 0 ? 1e6 / r : 0.0;
    usPerFrame_ = nominalUsPerFrame_;
    anchored_ = false;
}

void UacIsoStream::deliver(size_t frames, uint64_t nowUs) {
    const int rate = rate_.load();
    if (rate <= 0 || frames == 0) return;
    const double n = static_cast<double>(frames), now = static_cast<double>(nowUs);
    // The timestamps follow the sample count (smooth, as the mixer needs), steered toward the reap times by a
    // second-order DLL (F. Adriaensen, "Using a DLL to filter time") so they neither jitter nor drift from the clock.
    double error = anchored_ ? now - (nextUs_ + n * usPerFrame_) : 0.0;
    if (!anchored_ || std::abs(error) > static_cast<double>(kGapUs)) {
        if (anchored_) reanchors_++;
        nextUs_ = now - n * usPerFrame_; // this URB's last frame arrived just now
        anchorUs_ = nowUs;
        anchored_ = true;
        error = 0.0;
    }
    const uint64_t ptsUs = static_cast<uint64_t>(std::llround(nextUs_));
    // Wide for the first second so it locks quickly, then narrow so reap-time jitter (scheduling, poll) barely reaches the timestamps.
    const double bandwidthHz = nowUs - anchorUs_ < 1000000 ? 1.0 : 0.1;
    const double w = 2.0 * kPi * bandwidthHz * n * usPerFrame_ * 1e-6;
    nextUs_ += n * usPerFrame_ + std::sqrt(2.0) * w * error;
    usPerFrame_ = std::clamp(usPerFrame_ + w * w * error / n, nominalUsPerFrame_ * 0.99, nominalUsPerFrame_ * 1.01);
    measuredRate_ = 1e6 / usPerFrame_;
    // The nominal rate goes to the mixer: it resamples from it, and a jittering estimate would wobble the pitch.
    switch (sink_(pcm_.data(), frames, outChannels_, rate, ptsUs)) {
        case Push::Pushed: framesPushed_ += frames; break;
        case Push::NoMixer: noMixer_++; break;
        case Push::Refused: refused_++; break;
    }
}

std::string UacIsoStream::stats() const {
    const int rate = rate_.load(), corrected = rateCorrectedFrom_.load(), exitErr = exitErrno_.load();
    char line[512];
    snprintf(line, sizeof line, "%s (measured %.1f Hz), %d ch %d-bit in %d B, %llu packets (%llu failed), %.1f KB, %llu frames pushed, %llu pushes with no mixer set, %llu refused by the mixer (no such input), %llu re-anchors",
             rate > 0 ? (std::to_string(rate) + " Hz").c_str() : "rate still being measured", measuredRate_.load(), channels_, bits_, subslot_,
             static_cast<unsigned long long>(packets_.load()), static_cast<unsigned long long>(failedPackets_.load()), static_cast<double>(bytes_.load()) / 1024.0,
             static_cast<unsigned long long>(framesPushed_.load()), static_cast<unsigned long long>(noMixer_.load()), static_cast<unsigned long long>(refused_.load()),
             static_cast<unsigned long long>(reanchors_.load()));
    std::string s(line);
    if (corrected) s += ", corrected from " + std::to_string(corrected) + " Hz (the device did not run at the requested rate)";
    else if (requestedRate_ <= 0 && rate > 0) s += ", measured from the stream";
    if (exitErr) s += std::string(", stopped: ") + strerror(exitErr);
    if (restoredBits_.load()) s += "; the audio stays 48 dB below full scale (only the low 8 bits used): the source is very quiet, e.g. a game's own volume";
    return s;
}

} // namespace stream4k60
