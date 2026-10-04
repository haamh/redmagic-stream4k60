#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <cerrno>
#include <cstring>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include "../usb/uac_iso_stream.h"
#include "../audio/native_audio_mixer.h"

// jni_bridge.cpp owns the mixers; this finds one by its Kotlin handle (null when it is gone).
std::shared_ptr<stream4k60::NativeAudioMixer> stream4k60FindAudioMixer(jlong handle);

namespace {
std::mutex streamsMutex;
std::unordered_map<jlong, std::shared_ptr<stream4k60::UacIsoStream>> streams;
std::atomic<jlong> nextHandle{1};
// The studio mixer every USB microphone pushes into, set from Kotlin whenever NativeAudioGraph (re)starts it; 0 for none.
std::atomic<jlong> mixerHandle{0};
std::mutex errorMutex;
std::string startError;

std::string jstr(JNIEnv* e, jstring s) { if (!s) return {}; const char* c = e->GetStringUTFChars(s, nullptr); std::string r = c ? c : ""; if (c) e->ReleaseStringUTFChars(s, c); return r; }
jlong fail(std::string why) { std::lock_guard<std::mutex> l(errorMutex); startError = std::move(why); return 0; }
std::shared_ptr<stream4k60::UacIsoStream> find(jlong handle) { std::lock_guard<std::mutex> l(streamsMutex); auto it = streams.find(handle); return it == streams.end() ? nullptr : it->second; }
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbAudio_start(JNIEnv* env, jclass, jint fd, jint endpoint, jint packetBytes, jint packetsPerUrb, jint urbCount, jint channels, jint subslotBytes, jint bitResolution, jint sampleRate, jstring mixerInputId) {
    if (fd < 0 || (endpoint & 0x80) == 0 || packetBytes <= 0 || channels < 1 || subslotBytes < 1 || subslotBytes > 4 || !mixerInputId)
        return fail("invalid USB audio stream parameters (endpoint " + std::to_string(endpoint) + ", " + std::to_string(packetBytes) + " bytes, " + std::to_string(channels) + " ch, " + std::to_string(subslotBytes) + " B)");
    const std::string inputId = jstr(env, mixerInputId);
    auto sink = [inputId](const float* pcm, size_t frames, int ch, int rate, uint64_t ptsUs) {
        // Looked up on every push: the audio graph replaces its mixer when it restarts, and holding the old one would keep it alive.
        auto mixer = stream4k60FindAudioMixer(mixerHandle.load());
        if (!mixer) return stream4k60::UacIsoStream::Push::NoMixer;
        return mixer->pushExternalPcm(inputId, pcm, frames, ch, rate, ptsUs) ? stream4k60::UacIsoStream::Push::Pushed : stream4k60::UacIsoStream::Push::Refused;
    };
    stream4k60::UacIsoStream::Format format{channels, subslotBytes, bitResolution, sampleRate};
    auto stream = std::make_shared<stream4k60::UacIsoStream>(fd, static_cast<uint8_t>(endpoint), static_cast<size_t>(packetBytes), packetsPerUrb, urbCount, format, std::move(sink));
    if (!stream->start()) {
        const int err = stream->lastErrno();
        return fail(std::string("the kernel refused the isochronous audio transfers") + (err ? std::string(": ") + strerror(err) + " (errno " + std::to_string(err) + ")" : std::string()) +
                    ", " + std::to_string(urbCount) + " x " + std::to_string(packetsPerUrb) + " packets x " + std::to_string(packetBytes) + " bytes");
    }
    const jlong handle = nextHandle.fetch_add(1);
    { std::lock_guard<std::mutex> l(streamsMutex); streams.emplace(handle, std::move(stream)); }
    return handle;
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeUsbAudio_stop(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<stream4k60::UacIsoStream> stream;
    { std::lock_guard<std::mutex> l(streamsMutex); auto it = streams.find(handle); if (it == streams.end()) return; stream = it->second; streams.erase(it); }
    stream->stop();
}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeUsbAudio_stats(JNIEnv* env, jclass, jlong handle) { auto s = find(handle); return env->NewStringUTF(s ? s->stats().c_str() : ""); }
extern "C" JNIEXPORT jint JNICALL Java_com_stream4k60_app_engine_NativeUsbAudio_sampleRate(JNIEnv*, jclass, jlong handle) { auto s = find(handle); return s ? s->sampleRate() : 0; }
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeUsbAudio_lastStartError(JNIEnv* env, jclass) { std::lock_guard<std::mutex> l(errorMutex); return env->NewStringUTF(startError.c_str()); }
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeUsbAudio_dumpRaw(JNIEnv* env, jclass, jlong handle, jstring path, jint bytes) {
    auto s = find(handle); if (!s || !path) return;
    const char* c = env->GetStringUTFChars(path, nullptr); s->requestDump(c, static_cast<size_t>(std::max(0, static_cast<int>(bytes)))); env->ReleaseStringUTFChars(path, c);
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeUsbAudio_setMixer(JNIEnv*, jclass, jlong handle) { mixerHandle = handle; }
// Like libusbhost's usb_device_connect_kernel_driver. snd-usb-audio binds the AudioControl interface and claims the streaming
// ones from there, so for a streaming interface the kernel usually finds no driver (result 0); the ALSA card keeps the
// stream regardless and uses it again once the app has released the interface.
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeUsbAudio_reattachKernelDriver(JNIEnv*, jclass, jint fd, jint interfaceNumber) {
    if (fd < 0 || interfaceNumber < 0) return JNI_FALSE;
    usbdevfs_ioctl command{};
    command.ifno = interfaceNumber;
    command.ioctl_code = USBDEVFS_CONNECT;
    command.data = nullptr;
    return ioctl(fd, USBDEVFS_IOCTL, &command) >= 0 || errno == EBUSY ? JNI_TRUE : JNI_FALSE;
}
