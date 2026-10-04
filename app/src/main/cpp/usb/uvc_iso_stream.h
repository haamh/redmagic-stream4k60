#pragma once
#include <jni.h>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <thread>
#include <vector>
#include <deque>
#include <condition_variable>
#include <mutex>
#include <string>
#include "usbfs_compat.h"

namespace stream4k60 {

class UvcIsoStream {
public:
    /** errno of the last failed URB submit (why a start failed), 0 if none. */
    int lastErrno() const { return lastErrno_; }
    UvcIsoStream(int fd, uint8_t endpoint, size_t packetBytes, int packetsPerUrb,
                 int urbCount, jobject callback, jmethodID frameMethod, JavaVM* vm);
    ~UvcIsoStream();

    bool start();
    void stop();
    uint64_t frames() const { return frames_.load(); }
    uint64_t packetErrors() const { return packetErrors_.load(); }
    uint64_t droppedFrames() const { return droppedFrames_.load(); }
    /** Packet errors split by cause since start: what the USB bus reported, and what the camera flagged itself. */
    std::string errorBreakdown() const;
    /** True once the stream gave up: 1.5 s of nothing but failed packets (a camera that is gone but was never reported unplugged). */
    bool dead() const { return dead_.load(); }

private:
    struct Slot {
        usbfs::Urb* urb = nullptr;
        std::vector<uint8_t> storage;
        uint8_t* urbMemory = nullptr;
        size_t urbMemorySize = 0;
    };

    void reapLoop();
    void processUrb(Slot* slot);
    void processPacket(const uint8_t* data, size_t size, uint32_t status);
    struct QueuedFrame { std::vector<uint8_t> data; uint64_t ptsUs=0; bool hadError=false; };
    void emitFrame();
    void callbackLoop();
    static uint64_t monotonicUs();
    bool submit(Slot& slot);
    void discard(Slot& slot);

    int fd_;
    uint8_t endpoint_;
    size_t packetBytes_;
    int packetsPerUrb_;
    int urbCount_;
    jobject callback_;
    jmethodID frameMethod_;
    JavaVM* vm_;

    std::vector<std::unique_ptr<Slot>> slots_;
    std::thread thread_;
    std::thread callbackThread_;
    std::atomic<bool> running_{false};
    std::atomic<int> inFlight_{0}; // URBs submitted to the kernel and not yet reaped
    std::mutex frameQueueMutex_;
    std::condition_variable frameQueueCv_;
    std::deque<QueuedFrame> frameQueue_;
    std::atomic<uint64_t> frames_{0};
    std::atomic<uint64_t> packetErrors_{0};
    std::atomic<uint64_t> droppedFrames_{0};
    // Bus status per packet: missed service interval (EXDEV), transmission (EPROTO/EILSEQ/ECOMM/ENOSR), overflow
    // (EOVERFLOW), other; plus packets whose UVC header has the camera's own error bit.
    std::atomic<uint64_t> busMissed_{0}, busTransmission_{0}, busOverflow_{0}, busOther_{0}, cameraFlagged_{0};
    std::atomic<int> lastOtherStatus_{0};
    std::atomic<bool> dead_{false};
    uint64_t lastGoodUs_ = 0;
    uint64_t failedSinceGood_ = 0;

    std::vector<uint8_t> frame_;
    int currentFid_ = -1;
    bool sawError_ = false;
    uint64_t framePtsUs_ = 0;
    int lastErrno_ = 0;
};

} // namespace stream4k60
