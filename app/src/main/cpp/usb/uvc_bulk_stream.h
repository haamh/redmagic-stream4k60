#pragma once
#include <ctime>
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
#include "usbfs_compat.h"

namespace stream4k60 {
class UvcBulkStream {
public:
    /** True once the stream gave up after 1.5 s of nothing but failed transfers (device gone but never reported unplugged). */
    bool dead() const { return dead_.load(); }
    /** errno of the last failed URB submit (why a start failed), 0 if none. */
    int lastErrno() const { return lastErrno_; }
    /** [maxPayloadBytes] is the camera's committed dwMaxPayloadTransferSize (0 = unknown: a payload ends at a short packet). */
    UvcBulkStream(int fd, uint8_t endpoint, size_t packetBytes, size_t transferBytes, size_t maxPayloadBytes, int urbCount,
                  jobject callback, jmethodID frameMethod, JavaVM* vm);
    ~UvcBulkStream();
    bool start();
    void stop();
    uint64_t frames() const { return frames_.load(); }
    uint64_t transferErrors() const { return transferErrors_.load(); }
    uint64_t droppedFrames() const { return droppedFrames_.load(); }
private:
    struct Slot {
        usbfs::Urb* urb=nullptr;
        std::vector<uint8_t> storage;
        uint8_t* urbMemory=nullptr;
        size_t urbMemorySize=0;
    };
    void reapLoop();
    bool submit(Slot& slot);
    void discard(Slot& slot);
    void processUrb(Slot* slot);
    void processBytes(const uint8_t* data,size_t size,bool shortTransfer);
    void endPayload();
    struct QueuedFrame { std::vector<uint8_t> data; uint64_t ptsUs=0; bool hadError=false; };
    void emitFrame();
    void callbackLoop();
    static uint64_t monotonicUs();
    int fd_; uint8_t endpoint_; size_t packetBytes_; size_t transferBytes_; size_t maxPayload_; int urbCount_;
    jobject callback_; jmethodID frameMethod_; JavaVM* vm_;
    std::vector<std::unique_ptr<Slot>> slots_;
    std::thread thread_; std::thread callbackThread_; std::atomic<bool> running_{false};
    std::atomic<bool> dead_{false}; uint64_t lastGoodUs_=0; uint64_t failedSinceGood_=0; static uint64_t nowUs();
    std::atomic<int> inFlight_{0}; // URBs submitted to the kernel and not yet reaped
    std::mutex frameQueueMutex_; std::condition_variable frameQueueCv_; std::deque<QueuedFrame> frameQueue_;
    std::atomic<uint64_t> frames_{0},transferErrors_{0},droppedFrames_{0};
    std::vector<uint8_t> frame_; int currentFid_=-1; bool sawError_=false;
    // A bulk payload is one UVC header followed by data spanning any number of USB packets and URBs.
    bool inPayload_=false; bool payloadEof_=false; size_t payloadBytes_=0;
    bool skipPayload_=false; // after a transfer error: ignore data until the payload ends
    int lastErrno_ = 0;
};
}
