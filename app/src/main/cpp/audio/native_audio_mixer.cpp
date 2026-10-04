#include "native_audio_mixer.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <time.h>

namespace stream4k60 {
namespace {
static uint64_t monoUs() { timespec ts{}; clock_gettime(CLOCK_MONOTONIC, &ts); return static_cast<uint64_t>(ts.tv_sec)*1000000ull + static_cast<uint64_t>(ts.tv_nsec)/1000ull; }
static float clamp1(float v) { return std::max(-1.0f, std::min(1.0f, v)); }
}

NativeAudioMixer::Ring::Ring(size_t cap, int sr) : data(cap * 2, 0.0f), capacityFrames(std::max<size_t>(2, cap)), sampleRate(std::max(8000, sr)) {}

bool NativeAudioMixer::Ring::push(const float* src, size_t frames, int ch, uint64_t ptsUs) {
    if (!src || !frames) return true;
    channels = ch;
    const size_t h=head.load(std::memory_order_relaxed), t=tail.load(std::memory_order_acquire);
    const size_t used = h>=t ? h-t : capacityFrames-(t-h);
    const size_t free = capacityFrames-1-used;
    if (frames>free) return false;
    if (used==0) startPts.store(ptsUs,std::memory_order_release);
    for(size_t f=0;f<frames;++f){size_t idx=(h+f)%capacityFrames;if(ch==1){data[idx*2]=src[f];data[idx*2+1]=src[f];}else{data[idx*2]=src[f*ch];data[idx*2+1]=src[f*ch+1];}}
    head.store((h+frames)%capacityFrames,std::memory_order_release);return true;
}
size_t NativeAudioMixer::Ring::availableFrames()const{size_t h=head.load(std::memory_order_acquire),t=tail.load(std::memory_order_acquire);return h>=t?h-t:capacityFrames-(t-h);}
// The consumer advances the start time before releasing the frames: once the ring reads empty the producer stamps
// the next push itself, and until then a push continues exactly where the consumed audio ends.
size_t NativeAudioMixer::Ring::discard(size_t frames){
    const size_t n=std::min(frames,availableFrames());
    if(n==0)return 0;
    startPts.fetch_add(static_cast<uint64_t>(n)*1000000ull/static_cast<uint64_t>(sampleRate),std::memory_order_acq_rel);
    tail.store((tail.load(std::memory_order_relaxed)+n)%capacityFrames,std::memory_order_release);
    return n;
}
size_t NativeAudioMixer::Ring::popUpTo(float*dst,size_t frames){
    if(!dst)return 0;
    const size_t n=std::min(frames,availableFrames());
    if(n==0)return 0;
    const size_t t=tail.load(std::memory_order_relaxed);
    for(size_t f=0;f<n;++f){const size_t idx=(t+f)%capacityFrames;dst[f*2]=data[idx*2];dst[f*2+1]=data[idx*2+1];}
    startPts.fetch_add(static_cast<uint64_t>(n)*1000000ull/static_cast<uint64_t>(sampleRate),std::memory_order_acq_rel);
    tail.store((t+n)%capacityFrames,std::memory_order_release);
    return n;
}
bool NativeAudioMixer::Ring::pop(float*dst,size_t frames){if(!dst||availableFrames()<frames)return false;return popUpTo(dst,frames)==frames;}
uint64_t NativeAudioMixer::Ring::startPtsUs()const{return startPts.load(std::memory_order_acquire);}void NativeAudioMixer::Ring::clear(){head.store(0);tail.store(0);startPts.store(0);}

NativeAudioMixer::NativeAudioMixer(JavaVM* vm,jobject callback,int sr,int ch,int block,int monitorDevice,bool monitorEnabled,int monitorFormat,bool monitorBitPerfect)
:vm_(vm),sampleRate_(std::max(8000,sr)),channels_(ch==1?1:2),blockFrames_(std::max(48,block)),monitorDeviceId_(monitorDevice),monitorEnabled_(monitorEnabled){
    if(monitorFormat!=AAUDIO_FORMAT_PCM_I16&&monitorFormat!=AAUDIO_FORMAT_PCM_I24_PACKED&&monitorFormat!=AAUDIO_FORMAT_PCM_I32)monitorFormat=AAUDIO_FORMAT_PCM_FLOAT;
    monitorFormat_=monitorFormat;
    monitorBitPerfect_=monitorBitPerfect;
    JNIEnv* env=nullptr;if(vm_&&vm_->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_OK&&callback){callback_=env->NewGlobalRef(callback);jclass cls=env->GetObjectClass(callback_);if(cls)callbackMethod_=env->GetMethodID(cls,"onMixed","(Ljava/nio/ByteBuffer;JIII)V");}
}
NativeAudioMixer::~NativeAudioMixer(){stop();if(vm_&&callback_){JNIEnv* env=nullptr;bool a=false;if(vm_->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&env,nullptr)==JNI_OK)a=true;}if(env)env->DeleteGlobalRef(callback_);if(a)vm_->DetachCurrentThread();callback_=nullptr;callbackMethod_=nullptr;}}

bool NativeAudioMixer::start(){if(running_.exchange(true))return false;{std::lock_guard<std::mutex>l(inputsMutex_);for(auto&i:inputs_){if(!openInput(*i)){running_=false;for(auto&j:inputs_)closeInput(*j);return false;}}}if(monitorEnabled_.load()){monitorRing_=std::make_unique<Ring>(static_cast<size_t>(sampleRate_), sampleRate_);if(!openMonitor()){running_=false;for(auto&i:inputs_)closeInput(*i);return false;}}mixThread_=std::thread(&NativeAudioMixer::mixLoop,this);return true;}

std::string NativeAudioMixer::takeDebugStats(){
    std::lock_guard<std::mutex>l(inputsMutex_);std::string out;
    if(const uint64_t lim=limitedFrames_.exchange(0))out="limiter turned the mix down over 3 dB for "+std::to_string(lim*1000/static_cast<uint64_t>(sampleRate_))+" ms (sources too loud together)";
    for(auto&i:inputs_){
        const uint64_t late=i->lateFrames.exchange(0),shortW=i->shortWindows.exchange(0),over=i->overflows.exchange(0);
        if(!late&&!shortW&&!over)continue;
        if(!out.empty())out+="; ";
        std::string sid=i->id;
        for(const std::string pre:{std::string("media_audio_"),std::string("usb_audio_")})if(sid.rfind(pre,0)==0)sid=sid.substr(pre.size());
        out+=sid.substr(0,8)+": "+std::to_string(late*1000/static_cast<uint64_t>(sampleRate_))+" ms late, "+std::to_string(shortW)+" short windows, "+std::to_string(over)+" overflows";
    }
    return out;
}
std::string NativeAudioMixer::lastInputError() const{std::lock_guard<std::mutex>l(inputsMutex_);return lastInputError_;}
bool NativeAudioMixer::addInput(const std::string& id,int32_t deviceId,float volume,float balance,bool muted,int monitoring,int syncOffsetMs){std::lock_guard<std::mutex>l(inputsMutex_);if(std::any_of(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;}))return true;auto in=std::make_unique<Input>();in->id=id;in->deviceId=deviceId;in->volume=std::max(0.f,volume);in->balance=clamp1(balance);in->muted=muted;in->monitoring=std::clamp(monitoring,0,3);in->syncOffsetMs=syncOffsetMs;in->owner=this;in->ring=std::make_unique<Ring>(static_cast<size_t>(sampleRate_)*2, sampleRate_);if(running_.load()&&!openInput(*in))return false;inputs_.push_back(std::move(in));return true;}

bool NativeAudioMixer::addExternalInput(const std::string& id,float volume,float balance,bool muted,int monitoring,int syncOffsetMs,bool solo){
    std::lock_guard<std::mutex>l(inputsMutex_);
    if(std::any_of(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;}))return true;
    auto in=std::make_unique<Input>();in->id=id;in->deviceId=-1;in->external=true;in->volume=std::max(0.f,volume);in->balance=clamp1(balance);in->muted=muted;in->monitoring=std::clamp(monitoring,0,3);in->syncOffsetMs=syncOffsetMs;in->solo=solo;in->owner=this;in->ring=std::make_unique<Ring>(static_cast<size_t>(sampleRate_)*2,sampleRate_);in->sampleRate=sampleRate_;in->active=true;inputs_.push_back(std::move(in));return true;
}

bool NativeAudioMixer::pushExternalPcm(const std::string& id,const float*samples,size_t frames,int channels,int sampleRate,uint64_t ptsUs){
    if(!samples||frames==0||channels<1||sampleRate<8000)return false;
    std::lock_guard<std::mutex>l(inputsMutex_);
    auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});
    if(it==inputs_.end()||!(*it)->external||!(*it)->ring)return false;
    auto&in=*(*it);in.sampleRate=sampleRate;in.channels=std::clamp(channels,1,2);
    float peak=0.f;for(size_t n=0;n<frames*static_cast<size_t>(channels);++n)peak=std::max(peak,std::abs(samples[n]));
    in.peak.store(std::max(peak,in.peak.load(std::memory_order_relaxed)*0.85f),std::memory_order_relaxed);
    // Room for the whole block after rate conversion (a short buffer cut blocks and threw the resampler's position off).
    const size_t need=static_cast<size_t>(static_cast<double>(frames)*sampleRate_/std::max(8000,sampleRate))+64;
    if(in.resampleScratch.size()<std::max<size_t>(8192,need)*2)in.resampleScratch.resize(std::max<size_t>(8192,need)*2);
    const size_t cap=in.resampleScratch.size()/2;
    const size_t produced=resampleToProgramBus(in,samples,static_cast<int>(std::min<size_t>(frames,static_cast<size_t>(INT32_MAX))),in.resampleScratch.data(),cap);
    if(produced==0)return false;
    if(!in.ring->push(in.resampleScratch.data(),produced,2,ptsUs)){in.overflows++;in.ring->clear();return in.ring->push(in.resampleScratch.data(),produced,2,ptsUs);}return true;
}

bool NativeAudioMixer::removeInput(const std::string&id){std::lock_guard<std::mutex>l(inputsMutex_);auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});if(it==inputs_.end())return false;closeInput(*(*it));inputs_.erase(it);return true;}
bool NativeAudioMixer::setInputConfig(const std::string&id,float volume,float balance,bool muted,int monitoring,int syncOffsetMs,bool solo){std::lock_guard<std::mutex>l(inputsMutex_);auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});if(it==inputs_.end())return false;auto&i=*(*it);i.volume=std::max(0.f,volume);i.balance=clamp1(balance);i.muted=muted;i.monitoring=std::clamp(monitoring,0,3);i.syncOffsetMs=syncOffsetMs;i.solo=solo;return true;}
bool NativeAudioMixer::setInputGain(const std::string&id,float gainDb){
    std::lock_guard<std::mutex>l(inputsMutex_);
    auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});
    if(it==inputs_.end())return false;
    const float db=std::clamp(gainDb,-30.f,30.f);
    (*it)->gain.multiplier=std::pow(10.f,db/20.f);
    return true;
}
bool NativeAudioMixer::setInputGate(const std::string&id,bool enabled,float openDb,float closeDb,float attackMs,float holdMs,float releaseMs){
    std::lock_guard<std::mutex>l(inputsMutex_);
    auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});
    if(it==inputs_.end())return false;
    auto&g=(*it)->gate;
    const float sr=static_cast<float>(sampleRate_);
    const float open=std::clamp(openDb,-96.f,0.f);
    const float close=std::min(std::clamp(closeDb,-96.f,0.f),open);
    if(enabled&&!g.enabled){g.level=0.f;g.attenuation=0.f;g.heldSamples=0.f;g.open=false;}
    g.enabled=enabled;
    g.openThreshold=std::pow(10.f,open/20.f);
    g.closeThreshold=std::pow(10.f,close/20.f);
    g.attackRate=1.f/std::max(1.f,std::max(0.f,attackMs)*0.001f*sr);
    g.releaseRate=1.f/std::max(1.f,std::max(0.f,releaseMs)*0.001f*sr);
    g.holdSamples=std::max(0.f,holdMs)*0.001f*sr;
    return true;
}
// Same structure as OBS's noise gate: open above the open threshold, close once the decaying
// peak level drops below the close threshold, then hold before releasing.
void NativeAudioMixer::applyGate(Input::Gate&g,float*stereo,size_t frames,float levelDecay){
    for(size_t f=0;f<frames;++f){
        const float cur=std::max(std::abs(stereo[f*2]),std::abs(stereo[f*2+1]));
        if(cur>g.openThreshold&&!g.open)g.open=true;
        if(g.level<g.closeThreshold&&g.open){g.heldSamples=0.f;g.open=false;}
        g.level=std::max(g.level*levelDecay,cur);
        if(g.open)g.attenuation=std::min(1.f,g.attenuation+g.attackRate);
        else{g.heldSamples+=1.f;if(g.heldSamples>g.holdSamples)g.attenuation=std::max(0.f,g.attenuation-g.releaseRate);}
        stereo[f*2]*=g.attenuation;stereo[f*2+1]*=g.attenuation;
    }
}
void NativeAudioMixer::applyGain(const Input::Gain&g,float*stereo,size_t frames){
    if(!stereo||g.multiplier==1.f)return;
    for(size_t n=0;n<frames*2;++n)stereo[n]*=g.multiplier;
}
void NativeAudioMixer::setMonitorVolume(float v){monitorVolume_=std::max(0.f,v);}void NativeAudioMixer::setMonitorMuted(bool m){monitorMuted_=m;}
float NativeAudioMixer::getPeak(const std::string& id) const{std::lock_guard<std::mutex>l(inputsMutex_);auto it=std::find_if(inputs_.begin(),inputs_.end(),[&](const auto&i){return i->id==id;});return it==inputs_.end()?0.f:(*it)->peak.load();}

bool NativeAudioMixer::openInput(Input&input){
    if(input.stream)return true;
    // Low latency first. Android routes it to the device's single fast/MMAP capture path, which on the Astra is one
    // stream for all USB microphones together: a second USB mic is refused there while the first is open. Normal
    // latency uses a different (hifi) input path, so the second mic can still open. Some vendor USB paths also need
    // an explicit channel count, so stereo and mono are tried too; the sample rate is always left to the device.
    aaudio_result_t r=AAUDIO_ERROR_INTERNAL;
    AAudioStream*s=nullptr;
    for(aaudio_performance_mode_t mode:{AAUDIO_PERFORMANCE_MODE_LOW_LATENCY,AAUDIO_PERFORMANCE_MODE_NONE}){
        for(int ch:{0,2,1}){
            AAudioStreamBuilder*b=nullptr;
            if(AAudio_createStreamBuilder(&b)!=AAUDIO_OK)break;
            AAudioStreamBuilder_setDirection(b,AAUDIO_DIRECTION_INPUT);
            AAudioStreamBuilder_setPerformanceMode(b,mode);
            AAudioStreamBuilder_setSharingMode(b,AAUDIO_SHARING_MODE_SHARED);
            AAudioStreamBuilder_setSampleRate(b,0);
            AAudioStreamBuilder_setChannelCount(b,ch);
            AAudioStreamBuilder_setFormat(b,AAUDIO_FORMAT_PCM_FLOAT);
            if(input.deviceId>=0)AAudioStreamBuilder_setDeviceId(b,input.deviceId);
#if __ANDROID_API__ >= 29
            AAudioStreamBuilder_setInputPreset(b,AAUDIO_INPUT_PRESET_UNPROCESSED);
#endif
            AAudioStreamBuilder_setDataCallback(b,&NativeAudioMixer::dataCallback,&input);
            AAudioStreamBuilder_setErrorCallback(b,&NativeAudioMixer::errorCallback,&input);
            r=AAudioStreamBuilder_openStream(b,&s);
            AAudioStreamBuilder_delete(b);
            if(r==AAUDIO_OK&&s){
                // Android may hand back a stream routed to another device when the requested one is busy.
                const int32_t routed=AAudioStream_getDeviceId(s);
                if(input.deviceId>=0&&routed>0&&routed!=input.deviceId){
                    AAudioStream_close(s);s=nullptr;r=AAUDIO_ERROR_UNAVAILABLE;continue;
                }
                break;
            }
            if(s){AAudioStream_close(s);s=nullptr;}
        }
        if(r==AAUDIO_OK&&s)break;
    }
    if(r!=AAUDIO_OK||!s){lastInputError_=AAudio_convertResultToText(r);return false;}
    input.stream=s;
    input.channels=std::clamp(AAudioStream_getChannelCount(s),1,2);
    input.sampleRate=std::max(8000,AAudioStream_getSampleRate(s));
    // The callback normally stays well below this; reserve enough for large USB bursts so no
    // allocator work is required during steady-state resampling.
    input.resampleScratch.resize(static_cast<size_t>(8192)*2);
    input.resamplePhase=0.0;
    input.haveResampleHistory=false;
    input.lastSampleL=input.lastSampleR=0.0f;
    input.active=true;
    r=AAudioStream_requestStart(s);
    if(r!=AAUDIO_OK){lastInputError_=AAudio_convertResultToText(r);closeInput(input);return false;}
    return true;
}void NativeAudioMixer::closeInput(Input&input){input.active=false;if(input.stream){AAudioStream_requestStop(input.stream);AAudioStream_close(input.stream);input.stream=nullptr;}if(input.ring)input.ring->clear();}

// The monitor is the "hear it" path: what a source sends is played as is. 16- and 24-bit samples are exact in float
// and come back out bit for bit. Lossless mode opens the output exclusively on the direct (MMAP no-IRQ) port, 48 kHz
// 24-bit: the samples go into the audio DSP's buffer without Android's mixer, software volume, effects or resampling.
bool NativeAudioMixer::openMonitor(){
    if(monitorStream_)return true;
    AAudioStreamBuilder*b=nullptr;if(AAudio_createStreamBuilder(&b)!=AAUDIO_OK)return false;
    const bool bitPerfect=monitorBitPerfect_.load();const int format=monitorFormat_.load();
    AAudioStreamBuilder_setDirection(b,AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b,AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    // Exclusive = the direct port, unmixed; Android falls back to shared (mixed) when another app holds it.
    AAudioStreamBuilder_setSharingMode(b,bitPerfect?AAUDIO_SHARING_MODE_EXCLUSIVE:AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setUsage(b,AAUDIO_USAGE_MEDIA);AAudioStreamBuilder_setContentType(b,AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setSampleRate(b,sampleRate_);AAudioStreamBuilder_setChannelCount(b,2);AAudioStreamBuilder_setFormat(b,format);
    if(monitorDeviceId_>=0)AAudioStreamBuilder_setDeviceId(b,monitorDeviceId_);
    AAudioStreamBuilder_setDataCallback(b,&NativeAudioMixer::monitorCallback,this);AAudioStreamBuilder_setErrorCallback(b,&NativeAudioMixer::monitorErrorCallback,this);
    aaudio_result_t r=AAudioStreamBuilder_openStream(b,&monitorStream_);AAudioStreamBuilder_delete(b);
    if((r!=AAUDIO_OK||!monitorStream_)&&format!=AAUDIO_FORMAT_PCM_FLOAT){
        // The direct port takes 16/24/32-bit integer only; anything else retried as float (shared).
        monitorStream_=nullptr;monitorFormat_=AAUDIO_FORMAT_PCM_FLOAT;return openMonitor();
    }
    if(r!=AAUDIO_OK||!monitorStream_){monitorStream_=nullptr;std::lock_guard<std::mutex>l(monitorInfoMutex_);monitorInfo_=std::string("monitor output could not open: ")+AAudio_convertResultToText(r);return false;}
    monitorActualFormat_=AAudioStream_getFormat(monitorStream_);
    if(monitorScratch_.size()<static_cast<size_t>(8192)*2)monitorScratch_.resize(static_cast<size_t>(8192)*2);
    {
        const int rate=AAudioStream_getSampleRate(monitorStream_);const int f=monitorActualFormat_;
        const char* fname=f==AAUDIO_FORMAT_PCM_I16?"16-bit":f==AAUDIO_FORMAT_PCM_I24_PACKED?"24-bit":f==AAUDIO_FORMAT_PCM_I32?"32-bit":"32-bit float";
        std::lock_guard<std::mutex>l(monitorInfoMutex_);
        const bool exclusive=AAudioStream_getSharingMode(monitorStream_)==AAUDIO_SHARING_MODE_EXCLUSIVE;
        monitorInfo_=std::to_string(rate)+" Hz, "+fname+", "+(exclusive?"direct (exclusive): no Android mixing, volume or effects":
            (bitPerfect?"shared (another app holds the direct output, so Android mixes this one)":"shared through Android's mixer"))+
            (rate!=sampleRate_?"; Android resamples it, not lossless":"");
    }
    if(AAudioStream_requestStart(monitorStream_)!=AAUDIO_OK){closeMonitor();return false;}
    monitorLost_=false;return true;}
void NativeAudioMixer::setMonitorOutput(int format,bool bitPerfect){
    if(format!=AAUDIO_FORMAT_PCM_I16&&format!=AAUDIO_FORMAT_PCM_I24_PACKED&&format!=AAUDIO_FORMAT_PCM_I32)format=AAUDIO_FORMAT_PCM_FLOAT;
    if(monitorFormat_.load()==format&&monitorBitPerfect_.load()==bitPerfect)return;
    monitorFormat_=format;monitorBitPerfect_=bitPerfect;
    nextMonitorReopenUs_=0;monitorLost_=true; // the mix thread reopens it in the new format
}
std::string NativeAudioMixer::monitorInfo()const{
    std::string s;{std::lock_guard<std::mutex>l(monitorInfoMutex_);s=monitorInfo_;}
    // The level actually sent out (0 dBFS = full scale), so a quiet monitor can be told apart from a quiet source.
    const float peak=const_cast<std::atomic<float>&>(monitorPeak_).exchange(0.f);
    char b[96];snprintf(b,sizeof b,"; output peak %.1f dBFS, %llu underruns",peak>1e-6f?20.f*std::log10(peak):-120.f,static_cast<unsigned long long>(monitorUnderruns_.load()));
    return s.empty()?s:s+b;}
void NativeAudioMixer::closeMonitor(){if(!monitorStream_)return;AAudioStream_requestStop(monitorStream_);AAudioStream_close(monitorStream_);monitorStream_=nullptr;}
void NativeAudioMixer::stop(){running_=false;if(mixThread_.joinable())mixThread_.join();{std::lock_guard<std::mutex>l(inputsMutex_);for(auto&i:inputs_)closeInput(*i);}closeMonitor();}
uint64_t NativeAudioMixer::timestampFor(AAudioStream*s,int frames,int sourceRate)const{int64_t pos=0,timeNs=0;if(s&&AAudioStream_getTimestamp(s,CLOCK_MONOTONIC,&pos,&timeNs)==AAUDIO_OK){const int rate=std::max(8000,sourceRate);return static_cast<uint64_t>(std::max<int64_t>(0,timeNs/1000-static_cast<int64_t>(frames)*1000000LL/rate));}return monoUs();}
size_t NativeAudioMixer::resampleToProgramBus(Input& input,const float*src,int frames,float*dst,size_t dstCapacity){
    if(!src||!dst||frames<=0||dstCapacity==0)return 0;
    if(input.sampleRate==sampleRate_){
        const size_t out=std::min<size_t>(static_cast<size_t>(frames),dstCapacity);
        for(size_t i=0;i<out;++i){
            const float l=src[i*input.channels];
            const float r=input.channels>1?src[i*input.channels+1]:l;
            dst[i*2]=l;dst[i*2+1]=r;
        }
        return out;
    }
    // Band-limited (windowed-sinc, 32 taps, Blackman) instead of linear interpolation, which dulled the highs and
    // folded aliases back into 44.1 kHz media. Sources already at 48 kHz pass through untouched (above).
    constexpr int kHalf=16,kPhases=256;
    const double ratio=static_cast<double>(input.sampleRate)/static_cast<double>(sampleRate_);
    if(input.rsRatio!=ratio||input.rsKernel.empty()){
        input.rsRatio=ratio;
        const double cutoff=std::min(1.0,1.0/ratio)*0.97; // of the input's Nyquist frequency
        input.rsKernel.assign(static_cast<size_t>(kPhases+1)*2*kHalf,0.f);
        for(int p=0;p<=kPhases;++p){
            const double frac=static_cast<double>(p)/kPhases;double sum=0;
            float* row=input.rsKernel.data()+static_cast<size_t>(p)*2*kHalf;
            for(int k=0;k<2*kHalf;++k){
                const double t=static_cast<double>(k-kHalf+1)-frac;
                const double x=M_PI*cutoff*t;
                const double sinc=std::abs(x)<1e-9?1.0:std::sin(x)/x;
                const double w=std::abs(t)>=kHalf?0.0:0.42+0.5*std::cos(M_PI*t/kHalf)+0.08*std::cos(2*M_PI*t/kHalf);
                row[k]=static_cast<float>(sinc*w);sum+=row[k];
            }
            for(int k=0;k<2*kHalf;++k)row[k]=static_cast<float>(row[k]/sum); // unity gain at DC
        }
        input.rsHistory.assign(static_cast<size_t>(2*kHalf)*2,0.f);
        input.rsPos=2.0*kHalf;
    }
    // History (2*kHalf frames) followed by this block, as stereo.
    const size_t histFrames=input.rsHistory.size()/2;
    input.rsBuffer.resize((histFrames+static_cast<size_t>(frames))*2);
    std::copy(input.rsHistory.begin(),input.rsHistory.end(),input.rsBuffer.begin());
    for(int i=0;i<frames;++i){
        const float l=src[i*input.channels];const float r=input.channels>1?src[i*input.channels+1]:l;
        input.rsBuffer[(histFrames+i)*2]=l;input.rsBuffer[(histFrames+i)*2+1]=r;
    }
    const size_t total=histFrames+static_cast<size_t>(frames);
    const float* buf=input.rsBuffer.data();
    size_t out=0;double pos=input.rsPos;
    while(pos+kHalf<static_cast<double>(total)&&out<dstCapacity){
        const int i0=static_cast<int>(pos);
        const double phase=(pos-i0)*kPhases;const int p0=static_cast<int>(phase);const float f=static_cast<float>(phase-p0);
        const float* k0=input.rsKernel.data()+static_cast<size_t>(p0)*2*kHalf;const float* k1=k0+2*kHalf;
        float l=0.f,r=0.f;
        const float* x=buf+static_cast<size_t>(i0-kHalf+1)*2;
        for(int k=0;k<2*kHalf;++k){const float w=k0[k]+(k1[k]-k0[k])*f;l+=x[k*2]*w;r+=x[k*2+1]*w;}
        dst[out*2]=l;dst[out*2+1]=r;++out;pos+=ratio;
    }
    // Keep the last 2*kHalf frames for the next block.
    const size_t keep=histFrames;
    std::copy(input.rsBuffer.end()-static_cast<std::ptrdiff_t>(keep*2),input.rsBuffer.end(),input.rsHistory.begin());
    input.rsPos=pos-static_cast<double>(total-keep);
    input.haveResampleHistory=true;
    return out;
}

void NativeAudioMixer::capture(Input&input,void*audioData,int32_t frames){
    if(!input.active.load()||!input.ring||frames<=0||!audioData)return;
    const uint64_t pts=timestampFor(input.stream,frames,input.sampleRate);
    const float*f=static_cast<const float*>(audioData);
    float pk=0.f;for(int32_t n=0;n<frames*input.channels;++n)pk=std::max(pk,std::abs(f[n]));
    input.peak.store(std::max(pk,input.peak.load()*0.85f),std::memory_order_relaxed);
    const size_t maxFrames=input.resampleScratch.size()/2;
    const size_t outFrames=resampleToProgramBus(input,f,frames,input.resampleScratch.data(),maxFrames);
    if(outFrames==0)return;
    // Timestamp the first output sample in the program clock domain. Linear resampling only
    // changes sample spacing; it does not change the source clock origin.
    const uint64_t outPts=pts;
    if(!input.ring->push(input.resampleScratch.data(),outFrames,2,outPts)){
        // Keep latency bounded: discard the stale ring contents and publish the newest callback.
        input.overflows++;
        input.ring->clear();
        input.ring->push(input.resampleScratch.data(),outFrames,2,outPts);
    }
}aaudio_data_callback_result_t NativeAudioMixer::dataCallback(AAudioStream*,void*userData,void*audioData,int32_t numFrames){auto*in=static_cast<Input*>(userData);if(!in||!in->owner)return AAUDIO_CALLBACK_RESULT_STOP;in->owner->capture(*in,audioData,numFrames);return AAUDIO_CALLBACK_RESULT_CONTINUE;}
void NativeAudioMixer::errorCallback(AAudioStream*,void*userData,aaudio_result_t){if(auto*in=static_cast<Input*>(userData))in->active=false;}
void NativeAudioMixer::monitorErrorCallback(AAudioStream*,void*userData,aaudio_result_t){if(auto*self=static_cast<NativeAudioMixer*>(userData))self->monitorLost_=true;}
// Android disconnects a stream when its device goes away or the default device changes (a dock unplugged, Bluetooth
// headphones connected). Such streams used to stay dead (silent monitor/mic until restart); reopen them on the
// current device, at most once a second. Streams can't be closed from their own callbacks, hence this mix-thread step.
void NativeAudioMixer::recoverStreams(){
    const uint64_t now=monoUs();
    if(now-lastRecoverUs_<1000000)return;
    lastRecoverUs_=now;
    // A device that stays away is retried less and less often (1 s doubling to 30 s): each attempt can take a moment.
    if(monitorEnabled_.load()&&(monitorLost_.load()||!monitorStream_)&&now>=nextMonitorReopenUs_){
        closeMonitor();
        if(openMonitor())monitorReopenDelayUs_=1000000;
        else{nextMonitorReopenUs_=now+monitorReopenDelayUs_;monitorReopenDelayUs_=std::min<uint64_t>(monitorReopenDelayUs_*2,30000000);}
    }
    std::lock_guard<std::mutex> l(inputsMutex_);
    for(auto&i:inputs_){
        if(i->external||(i->stream&&i->active.load())||now<i->nextReopenUs)continue;
        closeInput(*i);
        if(openInput(*i))i->reopenDelayUs=1000000;
        else{i->nextReopenUs=now+i->reopenDelayUs;i->reopenDelayUs=std::min<uint64_t>(i->reopenDelayUs*2,30000000);}
    }
}
aaudio_data_callback_result_t NativeAudioMixer::monitorCallback(AAudioStream*,void*userData,void*audioData,int32_t numFrames){
    auto*self=static_cast<NativeAudioMixer*>(userData);
    if(!self||!audioData||numFrames<=0)return AAUDIO_CALLBACK_RESULT_STOP;
    const int format=self->monitorActualFormat_;
    const size_t bytesPerSample=format==AAUDIO_FORMAT_PCM_I16?2:format==AAUDIO_FORMAT_PCM_I24_PACKED?3:4;
    const bool silent=self->monitorMuted_.load()||!self->monitorRing_;
    const float gain=self->monitorVolume_.load();
    size_t done=0;const size_t total=static_cast<size_t>(numFrames);
    while(done<total){
        const size_t chunk=std::min(total-done,self->monitorScratch_.size()/2);
        float*f=format==AAUDIO_FORMAT_PCM_FLOAT?static_cast<float*>(audioData)+done*2:self->monitorScratch_.data();
        if(silent||!self->monitorRing_->pop(f,chunk)){std::fill_n(f,chunk*2,0.f);if(!silent)self->monitorUnderruns_++;}
        // At 100 % volume nothing is multiplied, so the samples stay exactly as captured.
        if(gain!=1.f)for(size_t n=0;n<chunk*2;++n)f[n]*=gain;
        uint8_t*out=static_cast<uint8_t*>(audioData)+done*2*bytesPerSample;
        float peak=0.f;for(size_t n=0;n<chunk*2;++n)peak=std::max(peak,std::abs(f[n]));
        if(peak>self->monitorPeak_.load(std::memory_order_relaxed))self->monitorPeak_.store(peak,std::memory_order_relaxed);
        for(size_t n=0;n<chunk*2;++n){
            const float v=clamp1(f[n]);
            if(format==AAUDIO_FORMAT_PCM_FLOAT)f[n]=v;
            else if(format==AAUDIO_FORMAT_PCM_I16){const int32_t q=static_cast<int32_t>(std::lrint(v*32768.f));const int16_t x=static_cast<int16_t>(std::clamp<int32_t>(q,-32768,32767));std::memcpy(out+n*2,&x,2);}
            else if(format==AAUDIO_FORMAT_PCM_I24_PACKED){const int32_t x=std::clamp<int32_t>(static_cast<int32_t>(std::lrint(static_cast<double>(v)*8388608.0)),-8388608,8388607);out[n*3]=static_cast<uint8_t>(x);out[n*3+1]=static_cast<uint8_t>(x>>8);out[n*3+2]=static_cast<uint8_t>(x>>16);}
            else{const int64_t q=std::llrint(static_cast<double>(v)*2147483648.0);const int32_t x=static_cast<int32_t>(std::clamp<int64_t>(q,INT32_MIN,INT32_MAX));std::memcpy(out+n*4,&x,4);}
        }
        done+=chunk;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;}

// Mixed on a clock, as OBS's audio thread: one block per block duration (10 ms), each source contributing the samples
// stamped for that window, with kMixLatencyUs of slack for sources that deliver late or in bursts. It used to mix
// whenever any one source had data, so two sources arriving at different moments became extra half-mixed blocks
// (faster than real time: the encoder queue overflowed and dropped audio) and each source sounded chopped.
void NativeAudioMixer::mixLoop(){
    constexpr uint64_t kMixLatencyUs=50000;
    std::vector<float>program(static_cast<size_t>(blockFrames_)*2),monitor(static_cast<size_t>(blockFrames_)*2),tmp(static_cast<size_t>(blockFrames_)*2);
    // Peak-envelope decay for the gate's close detection (~50 ms time constant).
    const float gateLevelDecay=std::exp(-1.f/(0.05f*static_cast<float>(sampleRate_)));
    const uint64_t blockUs=static_cast<uint64_t>(blockFrames_)*1000000ull/static_cast<uint64_t>(sampleRate_);
    const int64_t halfBlockUs=static_cast<int64_t>(blockUs/2);
    // Limiter on the stream mix: full-scale ceiling, instant attack, ~80 ms release, stereo-linked. Two loud sources
    // (music near 0 dB each) summed past full scale were hard-clipped, heard as harsh distortion.
    // The ceiling is full scale: it only acts where the mix would otherwise clip, and leaves every other sample untouched.
    const float limitCeiling=0.999f,limitRelease=std::exp(-1.f/(0.08f*static_cast<float>(sampleRate_)));
    float limitEnv=0.f;
    uint64_t window=monoUs()-kMixLatencyUs;
    // Attached to Java once for the thread's life. Attaching and detaching around every 10 ms callback (100 times a
    // second) churned the runtime's thread list, which garbage-collection checkpoints walk.
    JNIEnv*env=nullptr;bool attached=false;
    if(vm_&&vm_->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&env,nullptr)==JNI_OK)attached=true;else env=nullptr;}
    while(running_){
        recoverStreams();
        const uint64_t now=monoUs();
        // More than half a second behind (the thread was starved): continue from now instead of racing to catch up.
        if(now>window+kMixLatencyUs+500000ull)window=now-kMixLatencyUs;
        const uint64_t due=window+blockUs+kMixLatencyUs;
        if(now<due){std::this_thread::sleep_for(std::chrono::microseconds(std::min<uint64_t>(due-now,5000)));continue;}
        std::fill(program.begin(),program.end(),0.f); std::fill(monitor.begin(),monitor.end(),0.f);
        bool routedProgram=false,routedMonitor=false,anySolo=false;
        {
            std::lock_guard<std::mutex> l(inputsMutex_);
            for(auto &i:inputs_) anySolo=anySolo || (i->solo.load() && i->monitoring.load()!=0 && !i->muted.load());
            for(auto &i:inputs_){
                const int route=i->monitoring.load();
                if(!i->ring) continue;
                // This window in the source's own time: a positive sync offset plays the source later.
                const int64_t want=static_cast<int64_t>(window)-static_cast<int64_t>(i->syncOffsetMs.load())*1000LL;
                if(route==0 || i->muted.load() || (anySolo && !i->solo.load())){
                    // Not heard: keep its buffer trimmed to the present, so it neither overflows (it did every 2 s) nor
                    // plays stale audio when unmuted.
                    const size_t held=i->ring->availableFrames();
                    const int64_t end=want+static_cast<int64_t>(blockUs);
                    const int64_t from=static_cast<int64_t>(i->ring->startPtsUs());
                    if(held&&from<end)i->ring->discard(static_cast<size_t>(std::min<int64_t>(static_cast<int64_t>(held),(end-from)*sampleRate_/1000000LL)));
                    continue;
                }
                const bool toProgram=route==1||route==3,toMonitor=route==2||route==3;
                routedProgram=routedProgram||toProgram;routedMonitor=routedMonitor||toMonitor;
                size_t avail=i->ring->availableFrames();
                if(avail==0) continue;
                int64_t start=static_cast<int64_t>(i->ring->startPtsUs());
                // Stamped before this window (it arrived too late): drop what has already passed.
                if(start+halfBlockUs<want){
                    const size_t late=static_cast<size_t>(std::min<int64_t>(static_cast<int64_t>(avail),(want-start)*sampleRate_/1000000LL));
                    i->ring->discard(late);i->lateFrames+=late;
                    avail=i->ring->availableFrames();
                    if(avail==0) continue;
                    start=static_cast<int64_t>(i->ring->startPtsUs());
                }
                // Stamped after this window: not due yet.
                if(start>want+halfBlockUs) continue;
                const size_t got=i->ring->popUpTo(tmp.data(),static_cast<size_t>(blockFrames_));
                if(got<static_cast<size_t>(blockFrames_)){std::fill(tmp.begin()+static_cast<std::ptrdiff_t>(got*2),tmp.end(),0.f);i->shortWindows++;}
                if(i->gate.enabled)applyGate(i->gate,tmp.data(),static_cast<size_t>(blockFrames_),gateLevelDecay);
                applyGain(i->gain,tmp.data(),static_cast<size_t>(blockFrames_));
                i->peak.store(i->peak.load(std::memory_order_relaxed)*0.96f,std::memory_order_relaxed);
                const float v=i->volume.load(),pan=clamp1(i->balance.load()),L=v*(pan>0?1.f-pan:1.f),R=v*(pan<0?1.f+pan:1.f);
                for(size_t n=0;n<program.size();n+=2){const float lv=tmp[n]*L,rv=tmp[n+1]*R;if(toProgram){program[n]+=lv;program[n+1]+=rv;}if(toMonitor){monitor[n]+=lv;monitor[n+1]+=rv;}}
            }
        }
        const uint64_t pts=window;
        window+=blockUs;
        if(routedMonitor && monitorRing_){for(float&v:monitor)v=clamp1(v);if(!monitorRing_->push(monitor.data(),static_cast<size_t>(blockFrames_),2,pts)){monitorRing_->clear();monitorRing_->push(monitor.data(),static_cast<size_t>(blockFrames_),2,pts);}}
        // Every window goes out while any source is routed to the stream, silent or not, so the timeline stays even.
        if(!routedProgram) continue;
        uint64_t limited=0;
        for(size_t n=0;n<program.size();n+=2){
            limitEnv=std::max(std::max(std::abs(program[n]),std::abs(program[n+1])),limitEnv*limitRelease);
            if(limitEnv>limitCeiling){const float g=limitCeiling/limitEnv;program[n]*=g;program[n+1]*=g;if(g<0.708f)++limited;}
        }
        if(limited)limitedFrames_+=limited;
        for(float&v:program)v=clamp1(v);
        if(env&&callback_&&callbackMethod_){jobject buf=env->NewDirectByteBuffer(program.data(),static_cast<jlong>(program.size()*sizeof(float)));if(buf){env->CallVoidMethod(callback_,callbackMethod_,buf,static_cast<jlong>(pts),blockFrames_,2,sampleRate_);env->DeleteLocalRef(buf);if(env->ExceptionCheck())env->ExceptionClear();}}
    }
    if(attached)vm_->DetachCurrentThread();
}

} // namespace stream4k60
