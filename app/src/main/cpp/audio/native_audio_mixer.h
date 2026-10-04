#pragma once

#include <aaudio/AAudio.h>
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace stream4k60 {

class NativeAudioMixer {
public:
    NativeAudioMixer(JavaVM* vm, jobject callback, int sampleRate, int channels, int blockFrames, int monitorDeviceId, bool monitorEnabled);
    ~NativeAudioMixer();
    bool start();
    bool addInput(const std::string& sourceId, int32_t deviceId, float volume, float balance, bool muted, int monitoring, int syncOffsetMs);
    bool addExternalInput(const std::string& sourceId, float volume, float balance, bool muted, int monitoring, int syncOffsetMs, bool solo);
    bool pushExternalPcm(const std::string& sourceId, const float* samples, size_t frames, int channels, int sampleRate, uint64_t ptsUs);
    bool removeInput(const std::string& sourceId);
    bool setInputConfig(const std::string& sourceId, float volume, float balance, bool muted, int monitoring, int syncOffsetMs, bool solo);
    // Per-input noise gate on the 48 kHz program bus. Thresholds in dBFS; times in milliseconds.
    bool setInputGate(const std::string& sourceId, bool enabled, float openDb, float closeDb, float attackMs, float holdMs, float releaseMs);
    void stop();
    void setMonitorVolume(float volume);
    void setMonitorMuted(bool muted);
    /**
     * Monitor output format: an AAudio format (PCM_FLOAT, I16, I24_PACKED, I32) and whether Android was asked for
     * bit-perfect playback on that device, in which case the stream must match its format exactly and must not use the
     * low-latency (mixed) path. Reopens the monitor stream on the mix thread.
     */
    void setMonitorOutput(int format, bool bitPerfect);
    /** What the monitor stream really is (rate, format, path), for Settings. */
    std::string monitorInfo() const;
    float getPeak(const std::string& sourceId) const;
    /** Why the last input failed to open (an AAudio result name), for the user-facing error. */
    std::string lastInputError() const;
    /** Per-input audio problems since the last call (late / short / overflow), for the stream log. */
    std::string takeDebugStats();

private:
    struct Ring {
        explicit Ring(size_t capacityFrames, int sampleRate);
        bool push(const float* data, size_t frames, int channels, uint64_t ptsUs);
        size_t availableFrames() const;
        bool pop(float* dst, size_t frames);
        /** Pops up to `frames`; returns how many. */
        size_t popUpTo(float* dst, size_t frames);
        size_t discard(size_t frames);
        uint64_t startPtsUs() const;
        void clear();
        std::vector<float> data;
        std::atomic<size_t> head{0}, tail{0};
        std::atomic<uint64_t> startPts{0};
        size_t capacityFrames;
        int channels = 2;
        int sampleRate = 48000;
    };

    struct Input {
        std::string id;
        int32_t deviceId = -1;
        bool external = false;
        int channels = 2;
        int sampleRate = 48000;
        double resamplePhase = 0.0;
        bool haveResampleHistory = false;
        float lastSampleL = 0.0f, lastSampleR = 0.0f;
        std::vector<float> resampleScratch;
        // Windowed-sinc resampler state (sources not at 48 kHz): the last input frames (stereo) and the read position,
        // plus the kernel table for this input's rate.
        std::vector<float> rsHistory, rsBuffer, rsKernel;
        double rsPos = 0.0, rsRatio = 0.0;
        AAudioStream* stream = nullptr;
        std::unique_ptr<Ring> ring;
        std::atomic<float> volume{1.0f};
        std::atomic<float> balance{0.0f};
        std::atomic<bool> muted{false};
        std::atomic<int> monitoring{0};
        std::atomic<int> syncOffsetMs{0};
        std::atomic<bool> solo{false};
        std::atomic<bool> active{true};
        uint64_t nextReopenUs = 0, reopenDelayUs = 1000000; // backoff while its device stays unavailable
        std::atomic<float> peak{0.0f};
        // Diagnostics for the stream log, reset each time they are read: frames dropped for arriving after their
        // window, windows with too little audio, and buffer overflows (the source ran far ahead).
        std::atomic<uint64_t> lateFrames{0}, shortWindows{0}, overflows{0};
        NativeAudioMixer* owner = nullptr;
        struct Gate {
            bool enabled = false;
            float openThreshold = 0.05f, closeThreshold = 0.025f; // linear amplitude
            float attackRate = 0.f, releaseRate = 0.f, holdSamples = 0.f;
            // Runtime state, touched only by the mix thread.
            float level = 0.f, attenuation = 1.f, heldSamples = 0.f;
            bool open = false;
        } gate;
    };

    static aaudio_data_callback_result_t dataCallback(AAudioStream*, void*, void*, int32_t);
    static void errorCallback(AAudioStream*, void*, aaudio_result_t);
    static aaudio_data_callback_result_t monitorCallback(AAudioStream*, void*, void*, int32_t);
    void capture(Input& input, void* audioData, int32_t frames);
    void mixLoop();
    static void applyGate(Input::Gate& gate, float* stereo, size_t frames, float levelDecay);
    bool openInput(Input& input);
    std::string lastInputError_; // guarded by inputsMutex_ (openInput runs under it)
    void closeInput(Input& input);
    bool openMonitor();
    static void monitorErrorCallback(AAudioStream* stream, void* userData, aaudio_result_t error);
    void recoverStreams();
    void closeMonitor();
    uint64_t timestampFor(AAudioStream* stream, int frames, int sourceRate) const;
    size_t resampleToProgramBus(Input& input, const float* src, int frames, float* dst, size_t dstCapacity);
    JNIEnv* envForThread(bool& attached) const;

    JavaVM* vm_ = nullptr;
    jobject callback_ = nullptr;
    jmethodID callbackMethod_ = nullptr;
    int sampleRate_ = 48000;
    int channels_ = 2;
    int blockFrames_ = 480;
    std::atomic<uint64_t> limitedFrames_{0}; // stream-mix frames the limiter turned down by more than 3 dB
    int monitorDeviceId_ = -1;
    std::atomic<bool> running_{false};
    std::atomic<bool> monitorEnabled_{false};
    AAudioStream* monitorStream_ = nullptr;
    std::atomic<bool> monitorLost_{false}; // set by the monitor's error callback (device gone or default changed)
    uint64_t lastRecoverUs_ = 0, nextMonitorReopenUs_ = 0, monitorReopenDelayUs_ = 1000000;
    std::unique_ptr<Ring> monitorRing_;
    std::atomic<float> monitorVolume_{1.0f};
    std::atomic<bool> monitorMuted_{false};
    std::atomic<int> monitorFormat_{AAUDIO_FORMAT_PCM_FLOAT};
    std::atomic<bool> monitorBitPerfect_{false};
    int monitorActualFormat_ = AAUDIO_FORMAT_PCM_FLOAT; // set when the stream opens, read by its callback
    std::vector<float> monitorScratch_;
    std::atomic<float> monitorPeak_{0.f};   // loudest output sample since monitorInfo() last read it
    std::atomic<uint64_t> monitorUnderruns_{0};
    mutable std::mutex monitorInfoMutex_;
    std::string monitorInfo_;
    mutable std::mutex inputsMutex_;
    std::vector<std::unique_ptr<Input>> inputs_;
    std::thread mixThread_;
};

} // namespace stream4k60
