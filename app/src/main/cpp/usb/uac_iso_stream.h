#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include "usbfs_compat.h"

namespace stream4k60 {

/**
 * USB Audio Class capture over usbfs: isochronous IN URBs on the app's own device fd, Type I PCM converted to float and
 * handed to a sink (the audio mixer) stamped with the CLOCK_MONOTONIC time of its first frame. Used instead of AAudio
 * because Android's audio policy on the Astra opens only one USB input at a time.
 */
class UacIsoStream {
public:
    enum class Push { Pushed, NoMixer, Refused };
    /** Receives interleaved float PCM (1 or 2 channels, more are cut to the first two). */
    using Sink = std::function<Push(const float* pcm, size_t frames, int channels, int sampleRate, uint64_t ptsUs)>;
    struct Format {
        int channels = 2;
        int subslotBytes = 2;   // 1-4 bytes per sample in the USB stream, little-endian signed
        int bitResolution = 16; // valid bits; MSB-justified in the subslot, so it only matters for the log
        int sampleRate = 48000; // 0 = unknown: measured from the stream before anything is pushed
    };

    UacIsoStream(int fd, uint8_t endpoint, size_t packetBytes, int packetsPerUrb, int urbCount, Format format, Sink sink);
    ~UacIsoStream();
    bool start();
    void stop();
    /** errno of the last failed URB submit (why a start failed), 0 if none. */
    int lastErrno() const { return lastErrno_.load(); }
    int sampleRate() const { return rate_.load(); }
    std::string stats() const;
    /** Saves the next [bytes] of raw packet payload (exactly as the device sent it) to [path], for offline checks. */
    void requestDump(const std::string& path, size_t bytes);

private:
    // Devices that put only 8 significant bits in the low byte of a 16-bit sample (the Elgato 4K S with an iPhone over
    // HDMI: sign-extension high bytes only, so -48 dB). Detected from the data, then shifted back up 8 bits (x256).
    std::atomic<bool> lowByteAudio_{false};
    std::atomic<int> restoredBits_{0};
    int lowByteLoudSeconds_ = 0;
    int lowBytePeak_ = 0;
    size_t lowByteWindowFrames_ = 0;
    void watchLowByte(const uint8_t* data, size_t frames);
    std::mutex dumpMutex_;
    std::string dumpPath_;
    std::vector<uint8_t> dump_;
    size_t dumpWanted_ = 0;
    struct Slot {
        usbfs::Urb* urb = nullptr;
        uint8_t* urbMemory = nullptr;
        std::vector<uint8_t> storage;
    };

    void reapLoop();
    bool submit(Slot& slot);
    void processUrb(Slot& slot, uint64_t nowUs);
    float sample(const uint8_t* p) const;
    void checkRate(size_t realFrames, uint64_t nowUs);
    void deliver(size_t frames, uint64_t nowUs);
    void resetClock();
    static usbfs::IsoPacketDesc* descriptors(Slot& slot) { return reinterpret_cast<usbfs::IsoPacketDesc*>(slot.urbMemory + sizeof(usbfs::Urb)); }

    const int fd_;
    const uint8_t endpoint_;
    const size_t packetBytes_;
    const int packetsPerUrb_;
    const int urbCount_;
    const int channels_;
    const int outChannels_;
    const int subslot_;
    const int bits_;
    const size_t frameBytes_;
    const int requestedRate_;
    Sink sink_;

    std::vector<std::unique_ptr<Slot>> slots_;
    std::vector<float> pcm_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<int> inFlight_{0}; // URBs submitted to the kernel and not yet reaped
    std::atomic<int> lastErrno_{0};
    std::atomic<int> exitErrno_{0}; // why the reap loop ended by itself (device unplugged…)
    std::atomic<int> rate_{0};
    std::atomic<int> rateCorrectedFrom_{0};

    // Reap thread only.
    double avgPacketFrames_ = 0;    // frames in a typical packet, stands in for packets lost on the bus
    uint64_t firstDataUs_ = 0, measureStartUs_ = 0, measureFrames_ = 0, lastDataUs_ = 0;
    bool rateChecked_ = false;
    bool anchored_ = false;
    uint64_t anchorUs_ = 0;
    double nextUs_ = 0;             // DLL: filtered time of the next frame
    double usPerFrame_ = 0, nominalUsPerFrame_ = 0;

    // Stats.
    std::atomic<uint64_t> packets_{0}, failedPackets_{0}, bytes_{0}, framesPushed_{0}, noMixer_{0}, refused_{0}, reanchors_{0};
    std::atomic<double> measuredRate_{0};
};

} // namespace stream4k60
