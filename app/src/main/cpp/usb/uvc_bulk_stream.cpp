#include "uvc_bulk_stream.h"
#include <cerrno>
#include <cstring>
#include <poll.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>

namespace stream4k60 {
UvcBulkStream::UvcBulkStream(int fd,uint8_t endpoint,size_t packetBytes,size_t transferBytes,size_t maxPayloadBytes,int urbCount,jobject callback,jmethodID frameMethod,JavaVM* vm)
:fd_(fd),endpoint_(endpoint),packetBytes_(std::max<size_t>(packetBytes,64)),transferBytes_(std::max<size_t>(transferBytes,packetBytes_*16)),maxPayload_(maxPayloadBytes),urbCount_(std::clamp(urbCount,4,32)),callback_(callback),frameMethod_(frameMethod),vm_(vm){}
UvcBulkStream::~UvcBulkStream(){stop();if(callback_&&vm_){JNIEnv*e=nullptr;bool a=false;if(vm_->GetEnv(reinterpret_cast<void**>(&e),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&e,nullptr)==JNI_OK)a=true;}if(e)e->DeleteGlobalRef(callback_);if(a)vm_->DetachCurrentThread();callback_=nullptr;}}
bool UvcBulkStream::start(){lastGoodUs_=nowUs();failedSinceGood_=0;dead_=false;if(running_.exchange(true))return false;if(fd_<0||!callback_||!frameMethod_||!vm_){running_=false;return false;}slots_.reserve(urbCount_);const size_t bytes=sizeof(usbfs::Urb);for(int i=0;i<urbCount_;++i){auto s=std::make_unique<Slot>();s->urbMemorySize=bytes;s->urbMemory=(uint8_t*)calloc(1,bytes);if(!s->urbMemory){running_=false;return false;}s->urb=(usbfs::Urb*)s->urbMemory;s->storage.resize(transferBytes_);s->urb->type=usbfs::URB_TYPE_BULK;s->urb->endpoint=endpoint_;s->urb->flags=0;s->urb->buffer=s->storage.data();s->urb->buffer_length=(int)s->storage.size();s->urb->number_of_packets=0;s->urb->usercontext=s.get();slots_.push_back(std::move(s));}for(auto&s:slots_)if(!submit(*s)){stop();return false;}frame_.reserve(4*1024*1024);thread_=std::thread(&UvcBulkStream::reapLoop,this);callbackThread_=std::thread(&UvcBulkStream::callbackLoop,this);return true;}
bool UvcBulkStream::submit(Slot&s){s.urb->status=0;s.urb->actual_length=0;s.urb->error_count=0;if(ioctl(fd_,USBDEVFS_SUBMITURB,s.urb)!=0){lastErrno_=errno;return false;}inFlight_++;return true;}
void UvcBulkStream::discard(Slot&s){if(s.urb)ioctl(fd_,USBDEVFS_DISCARDURB,s.urb);}
void UvcBulkStream::stop(){const bool wasRunning=running_.exchange(false);if(wasRunning)for(auto&s:slots_)discard(*s);frameQueueCv_.notify_all();if(thread_.joinable())thread_.join();if(callbackThread_.joinable())callbackThread_.join();
    // Discarded URBs belong to the kernel until reaped; only then may their memory be freed.
    if(usbfs::reapOutstanding(fd_,inFlight_)){for(auto&s:slots_){free(s->urbMemory);s->urb=nullptr;s->urbMemory=nullptr;}}
    else{for(auto&s:slots_)(void)s.release();}
    inFlight_=0;slots_.clear();frame_.clear();{std::lock_guard<std::mutex> lock(frameQueueMutex_);frameQueue_.clear();}currentFid_=-1;inPayload_=false;payloadEof_=false;payloadBytes_=0;skipPayload_=false;}
uint64_t UvcBulkStream::monotonicUs(){timespec ts{};clock_gettime(CLOCK_MONOTONIC,&ts);return (uint64_t)ts.tv_sec*1000000ull+(uint64_t)ts.tv_nsec/1000ull;}
void UvcBulkStream::reapLoop(){JNIEnv*e=nullptr;bool a=false;if(vm_->GetEnv(reinterpret_cast<void**>(&e),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&e,nullptr)!=JNI_OK){running_=false;return;}a=true;}while(running_){void*ctx=nullptr;int rc=ioctl(fd_,USBDEVFS_REAPURBNDELAY,&ctx);if(rc<0){if(errno==EAGAIN||errno==EINTR){pollfd p{fd_,POLLIN,0};poll(&p,1,2);continue;}running_=false;break;}inFlight_--;
        // REAPURB returns the address of the reaped URB itself; our Slot is its usercontext.
        auto*urb=static_cast<usbfs::Urb*>(ctx);auto*s=urb?static_cast<Slot*>(urb->usercontext):nullptr;if(!s||s->urb!=urb)continue;processUrb(s);if(failedSinceGood_>64&&nowUs()-lastGoodUs_>1500000ull){dead_=true;running_=false;break;}if(running_&&!submit(*s)){running_=false;break;}}if(a)vm_->DetachCurrentThread();}
void UvcBulkStream::processUrb(Slot*s){
    const size_t n=s->urb->actual_length>0?static_cast<size_t>(s->urb->actual_length):0;
    // A transfer shorter than its buffer ended on a short (or zero-length) USB packet: the payload is over.
    const bool shortTransfer=n<transferBytes_;
    if(s->urb->status!=0)failedSinceGood_++;else{failedSinceGood_=0;lastGoodUs_=nowUs();}
    if(s->urb->status!=0){
        // Where the payload stands is unknown now; drop its data and resync at the next payload start.
        transferErrors_++;sawError_=true;inPayload_=false;payloadEof_=false;skipPayload_=!shortTransfer;
        return;
    }
    if(skipPayload_){if(shortTransfer)skipPayload_=false;return;}
    processBytes(s->storage.data(),n,shortTransfer);
}
// UVC bulk framing (as in Linux uvcvideo): each payload starts with a header and runs until a short packet, or until
// dwMaxPayloadTransferSize bytes, header included. Only the first packet of a payload carries the header.
void UvcBulkStream::processBytes(const uint8_t* d,size_t n,bool shortTransfer){
    size_t p=0;
    while(p<n){
        if(!inPayload_){
            const size_t left=n-p;
            const uint8_t h=d[p];
            if(left<2||h<2||h>left){
                // Not a payload header: skip to the end of this payload.
                transferErrors_++;sawError_=true;skipPayload_=!shortTransfer;
                return;
            }
            const uint8_t f=d[p+1];
            const int fid=f&1;
            if(currentFid_<0)currentFid_=fid;
            if(fid!=currentFid_){
                if(!frame_.empty())emitFrame();
                frame_.clear();
                currentFid_=fid;
                sawError_=false;
            }
            if(f&0x40){transferErrors_++;sawError_=true;} // the camera flagged an error in this payload
            payloadEof_=(f&0x02)!=0;
            inPayload_=true;
            payloadBytes_=h;
            p+=h;
        }
        size_t take=n-p;
        if(maxPayload_>payloadBytes_)take=std::min(take,maxPayload_-payloadBytes_);
        if(take){
            constexpr size_t MAX=32ull*1024*1024;
            if(frame_.size()+take>MAX){
                frame_.clear();
                droppedFrames_++;
                sawError_=true;
            } else {
                frame_.insert(frame_.end(),d+p,d+p+take);
            }
            payloadBytes_+=take;
            p+=take;
        }
        // A full-size payload ends without a short packet; the next one may start in this same transfer.
        if(maxPayload_>0&&payloadBytes_>=maxPayload_)endPayload();
    }
    if(shortTransfer&&inPayload_)endPayload();
}
void UvcBulkStream::endPayload(){
    inPayload_=false;
    if(payloadEof_){
        if(!frame_.empty())emitFrame();
        frame_.clear();
    }
    payloadEof_=false;
}
void UvcBulkStream::emitFrame(){
    if(frame_.empty())return;
    QueuedFrame frame;
    frame.data=std::move(frame_);
    frame.ptsUs=monotonicUs();
    frame.hadError=sawError_;
    frame_.clear();
    frame_.reserve(4*1024*1024);
    {
        std::lock_guard<std::mutex> lock(frameQueueMutex_);
        constexpr size_t kMaxQueuedFrames=4;
        if(frameQueue_.size()>=kMaxQueuedFrames){frameQueue_.pop_front();droppedFrames_++;}
        frameQueue_.push_back(std::move(frame));
    }
    frameQueueCv_.notify_one();
    sawError_=false;
}
void UvcBulkStream::callbackLoop(){
    JNIEnv*e=nullptr;bool a=false;
    if(vm_->GetEnv(reinterpret_cast<void**>(&e),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&e,nullptr)!=JNI_OK)return;a=true;}
    while(running_){
        QueuedFrame frame;
        {std::unique_lock<std::mutex> lock(frameQueueMutex_);frameQueueCv_.wait_for(lock,std::chrono::milliseconds(10),[&]{return !frameQueue_.empty()||!running_;});if(frameQueue_.empty()){if(!running_)break;else continue;}frame=std::move(frameQueue_.front());frameQueue_.pop_front();}
        jobject direct=e->NewDirectByteBuffer(frame.data.data(),(jlong)frame.data.size());
        if(direct){e->CallVoidMethod(callback_,frameMethod_,direct,(jlong)frame.ptsUs,(jint)(frame.hadError?1:0));e->DeleteLocalRef(direct);}
        if(e->ExceptionCheck()){e->ExceptionClear();droppedFrames_++;}else frames_++;
    }
    if(a)vm_->DetachCurrentThread();
}

}

uint64_t stream4k60::UvcBulkStream::nowUs(){timespec ts{};clock_gettime(CLOCK_MONOTONIC,&ts);return static_cast<uint64_t>(ts.tv_sec)*1000000ull+static_cast<uint64_t>(ts.tv_nsec)/1000ull;}
