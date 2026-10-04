#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif
#include <memory>
#include <cstring>
#include <vector>
#include <atomic>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include "../gpu/gl_compositor.h"
#include "../usb/uvc_iso_stream.h"
#include "../usb/uvc_bulk_stream.h"
#include "../audio/native_audio_mixer.h"
static stream4k60::GlCompositor g;
static std::mutex gm;

static std::mutex um;
static std::unordered_map<jlong, std::shared_ptr<stream4k60::UvcIsoStream>> isoStreams;
static std::atomic<jlong> nextIsoHandle{1};
static std::unordered_map<jlong, std::shared_ptr<stream4k60::UvcBulkStream>> bulkStreams;
static std::atomic<jlong> nextBulkHandle{1};
static std::unordered_map<jlong, std::shared_ptr<stream4k60::NativeAudioMixer>> audioMixers;
static std::atomic<jlong> nextAudioHandle{1};

static std::string jstr(JNIEnv*e,jstring s){const char*c=e->GetStringUTFChars(s,nullptr);std::string r=c?c:"";if(c)e->ReleaseStringUTFChars(s,c);return r;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_initializeRenderer(JNIEnv*e,jclass,jint w,jint h,jint fps){std::lock_guard<std::mutex>l(gm);return g.initialize(w,h,fps,e);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_shutdownRenderer(JNIEnv*e,jclass){std::lock_guard<std::mutex>l(gm);g.shutdown();}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_startRenderer(JNIEnv*,jclass){return g.start();}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_stopRenderer(JNIEnv*,jclass){g.stop();}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setCanvasSize(JNIEnv*,jclass,jint w,jint h){g.setCanvas(w,h);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setVideoSettings(JNIEnv*,jclass,jint w,jint h,jint fps){g.setVideoSettings(static_cast<uint32_t>(w),static_cast<uint32_t>(h),fps);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_setPreviewSurface(JNIEnv*e,jclass,jobject s){return g.setPreviewSurface(e,s);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_grabCanvas(JNIEnv*e,jclass,jstring path){g.grabCanvas(jstr(e,path));}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setGpuBench(JNIEnv*,jclass,jboolean on,jint flags){g.setGpuBench(on==JNI_TRUE,flags);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setPreviewHdr(JNIEnv*,jclass,jboolean on){g.setPreviewHdr(on==JNI_TRUE);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSoloPreview(JNIEnv*e,jclass,jstring id,jobject s){std::string key;if(id){const char*c=e->GetStringUTFChars(id,nullptr);key=c;e->ReleaseStringUTFChars(id,c);}return g.setSoloPreview(e,key,s);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_setEncoderSurface(JNIEnv*e,jclass,jobject s){return g.setEncoderSurface(e,s);}
extern "C" JNIEXPORT jobject JNICALL Java_com_stream4k60_app_engine_NativeEngine_createSourceSurface(JNIEnv*e,jclass,jstring id){jobject out=nullptr;return g.createSource(jstr(e,id),e,out)?out:nullptr;}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceBufferSize(JNIEnv*e,jclass,jstring id,jint w,jint h){g.setSourceBufferSize(jstr(e,id),w,h,e);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_releaseSourceSurface(JNIEnv*e,jclass,jstring id){g.releaseSource(jstr(e,id),e);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_updateSourceRgba(JNIEnv*e,jclass,jstring id,jbyteArray data,jint w,jint h){if(!data)return JNI_FALSE;jsize n=e->GetArrayLength(data);std::vector<uint8_t>buf((size_t)n);e->GetByteArrayRegion(data,0,n,reinterpret_cast<jbyte*>(buf.data()));return g.updateRgba(jstr(e,id),buf.data(),buf.size(),w,h);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeEngine_updateSourceYuvDirect(JNIEnv*e,jclass,jstring id,jobject frame,jint w,jint h,jint fmt){if(!frame)return JNI_FALSE;void*p=e->GetDirectBufferAddress(frame);jlong n=e->GetDirectBufferCapacity(frame);if(!p||n<=0)return JNI_FALSE;if(fmt==4){
    // I420 (Y plane, then U, then V) is re-packed into NV12's interleaved UV plane and drawn by the NV12 path.
    const size_t ySize=(size_t)w*h,cSize=(size_t)((w+1)/2)*((h+1)/2);if((size_t)n<ySize+2*cSize)return JNI_FALSE;
    thread_local std::vector<uint8_t> nv12;nv12.resize(ySize+2*cSize);const uint8_t*src=reinterpret_cast<const uint8_t*>(p);
    std::memcpy(nv12.data(),src,ySize);const uint8_t*u=src+ySize;const uint8_t*v=u+cSize;uint8_t*uv=nv12.data()+ySize;
    for(size_t i=0;i<cSize;++i){uv[2*i]=u[i];uv[2*i+1]=v[i];}
    return g.updateRaw(jstr(e,id),nv12.data(),nv12.size(),w,h,stream4k60::RawPixelFormat::NV12);}
 auto f=static_cast<stream4k60::RawPixelFormat>(fmt);return g.updateRaw(jstr(e,id),reinterpret_cast<const uint8_t*>(p),(size_t)n,w,h,f);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceTextureParameters(JNIEnv*e,jclass,jstring id,jfloat x,jfloat y,jfloat w,jfloat h,jfloat px,jfloat py,jfloat r,jfloat sx,jfloat sy,jfloat op,jfloat cl,jfloat ct,jfloat cr,jfloat cb,jboolean v,jint z,jboolean fh,jboolean fv){g.updateLayer(jstr(e,id),x,y,w,h,px,py,r,sx,sy,op,cl,ct,cr,cb,v,z,fh,fv);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceFilterChain(JNIEnv*e,jclass,jstring id,jintArray types,jfloatArray params){
    const jsize count=types?e->GetArrayLength(types):0;
    const jsize paramCount=params?e->GetArrayLength(params):0;
    std::vector<jint> t((size_t)count);
    std::vector<jfloat> p((size_t)paramCount);
    if(count>0)e->GetIntArrayRegion(types,0,count,t.data());
    if(paramCount>0)e->GetFloatArrayRegion(params,0,paramCount,p.data());
    static_assert(sizeof(jint)==sizeof(int)&&sizeof(jfloat)==sizeof(float));
    g.updateFilterChain(jstr(e,id),reinterpret_cast<const int*>(t.data()),(int)count,p.data(),(int)paramCount);
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceLut(JNIEnv*e,jclass,jstring id,jint slot,jstring key,jint size,jbyteArray rgb,jfloatArray domainMin,jfloatArray domainMax){
    if(!rgb||size<2)return;
    const jsize n=e->GetArrayLength(rgb);
    const size_t expected=static_cast<size_t>(size)*size*size*3;
    if(static_cast<size_t>(n)<expected)return;
    std::vector<uint8_t> data(expected);
    e->GetByteArrayRegion(rgb,0,static_cast<jsize>(expected),reinterpret_cast<jbyte*>(data.data()));
    float dmin[3]={0,0,0},dmax[3]={1,1,1};
    if(domainMin&&e->GetArrayLength(domainMin)>=3)e->GetFloatArrayRegion(domainMin,0,3,dmin);
    if(domainMax&&e->GetArrayLength(domainMax)>=3)e->GetFloatArrayRegion(domainMax,0,3,dmax);
    g.setLut(jstr(e,id),slot,jstr(e,key),size,data.data(),dmin,dmax);
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_clearSourceLut(JNIEnv*e,jclass,jstring id,jint slot){g.clearLut(jstr(e,id),slot);}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeEngine_getSourceLutKey(JNIEnv*e,jclass,jstring id,jint slot){return e->NewStringUTF(g.lutKey(jstr(e,id),slot).c_str());}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSceneTarget(JNIEnv*e,jclass,jstring key,jint w,jint h){g.setSceneTarget(jstr(e,key),w,h);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_retainSceneTargets(JNIEnv*e,jclass,jobjectArray keys){
    std::vector<std::string> list;
    const jsize n=keys?e->GetArrayLength(keys):0;
    for(jsize i=0;i<n;++i){auto k=static_cast<jstring>(e->GetObjectArrayElement(keys,i));if(k){list.push_back(jstr(e,k));e->DeleteLocalRef(k);}}
    g.retainSceneTargets(list);
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceOwner(JNIEnv*e,jclass,jstring id,jstring owner){g.setSourceOwner(jstr(e,id),jstr(e,owner));}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceSceneRef(JNIEnv*e,jclass,jstring id,jstring key){g.setSourceSceneRef(jstr(e,id),jstr(e,key));}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeEngine_getLastError(JNIEnv*e,jclass){return e->NewStringUTF(stream4k60::GlCompositor::lastError().c_str());}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_removeSourceLayer(JNIEnv*e,jclass,jstring id){g.releaseSource(jstr(e,id),e);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setTransition(JNIEnv*,jclass,jint type,jint duration){g.setTransition(type,duration);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setTransitionProgress(JNIEnv*,jclass,jfloat p){g.setTransitionProgress(p);}
extern "C" JNIEXPORT jfloat JNICALL Java_com_stream4k60_app_engine_NativeEngine_getRenderTimeMs(JNIEnv*,jclass){return g.renderMs();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeEngine_getDroppedFrames(JNIEnv*,jclass){return (jlong)g.dropped();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeEngine_getTotalFrames(JNIEnv*,jclass){return (jlong)g.frames();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeEngine_getEncoderFrames(JNIEnv*,jclass){return (jlong)g.encoderFrames();}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceScaleFilter(JNIEnv*e,jclass,jstring id,jint mode){g.setSourceScaleFilter(jstr(e,id),mode);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceTransfer(JNIEnv*e,jclass,jstring id,jint transfer){g.setSourceTransfer(jstr(e,id),transfer);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setOutputColor(JNIEnv*,jclass,jint transfer,jboolean tenBit,jfloat sdrWhite,jfloat hdrPeak){g.setOutputColor(transfer,tenBit==JNI_TRUE,sdrWhite,hdrPeak);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceYuvColor(JNIEnv*e,jclass,jstring id,jint matrix,jint range){g.setSourceYuvColor(jstr(e,id),matrix,range);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceDecodedColor(JNIEnv*e,jclass,jstring id,jint kind,jint matrix,jint range){g.setSourceDecodedColor(jstr(e,id),kind,matrix,range);}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeEngine_describeRender(JNIEnv*e,jclass){return e->NewStringUTF(g.describeRender().c_str());}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceHlgLook(JNIEnv*e,jclass,jstring id,jfloat strength,jfloat colour,jfloat peak){g.setSourceHlgLook(jstr(e,id),strength,colour,peak);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceBlank(JNIEnv*e,jclass,jstring id,jboolean blank){g.setSourceBlank(jstr(e,id),blank==JNI_TRUE);}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeEngine_describeSource(JNIEnv*e,jclass,jstring id){return e->NewStringUTF(g.describeSource(jstr(e,id)).c_str());}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_ensureRawSource(JNIEnv*e,jclass,jstring id){g.ensureRawSource(jstr(e,id));}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceYuvReported(JNIEnv*e,jclass,jstring id,jint matrix){g.setSourceYuvReported(jstr(e,id),matrix);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_setSourceAlias(JNIEnv*e,jclass,jstring id,jstring target){g.setSourceAlias(jstr(e,id),jstr(e,target));}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeEngine_encoderSurfaceInfo(JNIEnv*e,jclass){return e->NewStringUTF(g.encoderSurfaceInfo().c_str());}
static std::mutex usbErrorMutex;static std::string usbStartError;
static void setUsbStartError(const char*kind,int err,int a,int b){std::lock_guard<std::mutex>l(usbErrorMutex);usbStartError=std::string("the kernel refused the ")+kind+" USB transfers"+(err?std::string(": ")+strerror(err)+" (errno "+std::to_string(err)+")":std::string())+", "+std::to_string(b)+" x "+std::to_string(a)+" bytes";}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_lastStartError(JNIEnv*e,jclass){std::lock_guard<std::mutex>l(usbErrorMutex);return e->NewStringUTF(usbStartError.c_str());}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_start(JNIEnv* env,jclass,jint fd,jint endpoint,jint packetBytes,jint packetsPerUrb,jint urbCount,jobject callback){
    if(fd<0||endpoint<0||packetBytes<=0||!callback)return 0;
    jclass cls=env->GetObjectClass(callback);
    if(!cls)return 0;
    jmethodID method=env->GetMethodID(cls,"onNativeFrame","(Ljava/nio/ByteBuffer;JI)V");
    if(!method)return 0;
    jobject global=env->NewGlobalRef(callback);
    if(!global)return 0;
    JavaVM* vm=nullptr;env->GetJavaVM(&vm);
    auto stream=std::make_shared<stream4k60::UvcIsoStream>(fd,(uint8_t)endpoint,(size_t)packetBytes,packetsPerUrb,urbCount,global,method,vm);
    // On failure the stream's destructor releases the global ref; releasing it here as well was a double delete,
    // which Android aborts the app for (adding an MX Brio crashed the studio when its stream could not start).
    if(!stream->start()){setUsbStartError("isochronous",stream->lastErrno(),packetBytes,packetsPerUrb);return 0;}
    const jlong handle=nextIsoHandle.fetch_add(1);
    {std::lock_guard<std::mutex> lock(um);isoStreams.emplace(handle,std::move(stream));}
    return handle;
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_stop(JNIEnv* env,jclass,jlong handle){
    std::shared_ptr<stream4k60::UvcIsoStream> stream;
    {std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);if(it==isoStreams.end())return;stream=it->second;isoStreams.erase(it);}
    stream->stop();
}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_frames(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);return it==isoStreams.end()?0:(jlong)it->second->frames();}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_isDead(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);return it!=isoStreams.end()&&it->second->dead()?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_isDead(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);return it!=bulkStreams.end()&&it->second->dead()?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_errorBreakdown(JNIEnv*e,jclass,jlong handle){std::string s;{std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);if(it!=isoStreams.end())s=it->second->errorBreakdown();}return e->NewStringUTF(s.c_str());}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_packetErrors(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);return it==isoStreams.end()?0:(jlong)it->second->packetErrors();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbIso_droppedFrames(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=isoStreams.find(handle);return it==isoStreams.end()?0:(jlong)it->second->droppedFrames();}


// The speed the device's link actually negotiated (enum usb_device_speed: 3 high = USB 2.0, 5 super = 5 Gbps,
// 6 super-plus = 10 Gbps+), not just what it supports; -1 when the kernel can't tell.
extern "C" JNIEXPORT jint JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_linkSpeed(JNIEnv*,jclass,jint fd){return fd<0?-1:ioctl(fd,USBDEVFS_GET_SPEED);}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_start(JNIEnv* env,jclass,jint fd,jint endpoint,jint packetBytes,jint transferBytes,jint maxPayloadBytes,jint urbCount,jobject callback){
    if(fd<0||endpoint<0||packetBytes<=0||transferBytes<=0||!callback)return 0;
    jclass cls=env->GetObjectClass(callback); if(!cls)return 0;
    jmethodID method=env->GetMethodID(cls,"onNativeFrame","(Ljava/nio/ByteBuffer;JI)V"); if(!method)return 0;
    jobject global=env->NewGlobalRef(callback); if(!global)return 0;
    JavaVM* vm=nullptr; env->GetJavaVM(&vm);
    auto stream=std::make_shared<stream4k60::UvcBulkStream>(fd,(uint8_t)endpoint,(size_t)packetBytes,(size_t)transferBytes,(size_t)(maxPayloadBytes>0?maxPayloadBytes:0),urbCount,global,method,vm);
    if(!stream->start()){setUsbStartError("bulk",stream->lastErrno(),packetBytes,transferBytes);return 0;}
    jlong handle=nextBulkHandle.fetch_add(1); {std::lock_guard<std::mutex> lock(um); bulkStreams.emplace(handle,std::move(stream));} return handle;
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_stop(JNIEnv*,jclass,jlong handle){std::shared_ptr<stream4k60::UvcBulkStream> s;{std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);if(it==bulkStreams.end())return;s=it->second;bulkStreams.erase(it);}s->stop();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_frames(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);return it==bulkStreams.end()?0:(jlong)it->second->frames();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_errors(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);return it==bulkStreams.end()?0:(jlong)it->second->transferErrors();}
extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeUsbBulk_droppedFrames(JNIEnv*,jclass,jlong handle){std::lock_guard<std::mutex> lock(um);auto it=bulkStreams.find(handle);return it==bulkStreams.end()?0:(jlong)it->second->droppedFrames();}


extern "C" JNIEXPORT jlong JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_start(JNIEnv* env,jclass,jobject callback,jint sampleRate,jint channels,jint blockFrames,jint monitorDeviceId,jboolean monitorEnabled){
    if(!callback||sampleRate<=0||blockFrames<=0)return 0;
    JavaVM* vm=nullptr;if(env->GetJavaVM(&vm)!=JNI_OK)return 0;
    auto mixer=std::make_shared<stream4k60::NativeAudioMixer>(vm,callback,sampleRate,channels,blockFrames,monitorDeviceId,monitorEnabled);
    if(!mixer->start())return 0;
    jlong h=nextAudioHandle.fetch_add(1);{std::lock_guard<std::mutex> l(um);audioMixers.emplace(h,mixer);}return h;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_addInput(JNIEnv* env,jclass,jlong handle,jstring sourceId,jint deviceId,jfloat volume,jfloat balance,jboolean muted,jint monitoring,jint syncOffsetMs){
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}return m->addInput(jstr(env,sourceId),deviceId,volume,balance,muted,monitoring,syncOffsetMs)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_addExternalInput(JNIEnv* env,jclass,jlong handle,jstring sourceId,jfloat volume,jfloat balance,jboolean muted,jint monitoring,jint syncOffsetMs,jboolean solo){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}return m->addExternalInput(jstr(env,sourceId),volume,balance,muted,monitoring,syncOffsetMs,solo)?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_pushExternalPcm(JNIEnv* env,jclass,jlong handle,jstring sourceId,jobject pcm,jint frames,jint channels,jint sampleRate,jlong ptsUs){
    if(!pcm||frames<=0)return JNI_FALSE;void*ptr=env->GetDirectBufferAddress(pcm);jlong bytes=env->GetDirectBufferCapacity(pcm);if(!ptr||bytes<=0)return JNI_FALSE;
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}
    const size_t required=static_cast<size_t>(frames)*static_cast<size_t>(std::max(1,channels))*sizeof(float);if(static_cast<size_t>(bytes)<required)return JNI_FALSE;
    return m->pushExternalPcm(jstr(env,sourceId),static_cast<const float*>(ptr),frames,channels,sampleRate,static_cast<uint64_t>(ptsUs))?JNI_TRUE:JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_removeInput(JNIEnv* env,jclass,jlong handle,jstring sourceId){
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}return m->removeInput(jstr(env,sourceId))?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setInputConfig(JNIEnv* env,jclass,jlong handle,jstring sourceId,jfloat volume,jfloat balance,jboolean muted,jint monitoring,jint syncOffsetMs,jboolean solo){
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}return m->setInputConfig(jstr(env,sourceId),volume,balance,muted,monitoring,syncOffsetMs,solo)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setInputGate(JNIEnv* env,jclass,jlong handle,jstring sourceId,jboolean enabled,jfloat openDb,jfloat closeDb,jfloat attackMs,jfloat holdMs,jfloat releaseMs){
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}
    return m->setInputGate(jstr(env,sourceId),enabled,openDb,closeDb,attackMs,holdMs,releaseMs)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setInputGain(JNIEnv* env,jclass,jlong handle,jstring sourceId,jfloat gainDb){
    std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return JNI_FALSE;m=it->second;}
    return m->setInputGain(jstr(env,sourceId),gainDb)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setMonitorVolume(JNIEnv*,jclass,jlong handle,jfloat volume){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return;m=it->second;}m->setMonitorVolume(volume);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setMonitorMuted(JNIEnv*,jclass,jlong handle,jboolean muted){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return;m=it->second;}m->setMonitorMuted(muted);}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_setMonitorOutput(JNIEnv*,jclass,jlong handle,jint format,jboolean bitPerfect){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return;m=it->second;}m->setMonitorOutput(format,bitPerfect==JNI_TRUE);}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_takeDebugStats(JNIEnv* env,jclass,jlong handle){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it!=audioMixers.end())m=it->second;}return env->NewStringUTF(m?m->takeDebugStats().c_str():"");}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_lastInputError(JNIEnv* env,jclass,jlong handle){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it!=audioMixers.end())m=it->second;}return env->NewStringUTF(m?m->lastInputError().c_str():"");}
extern "C" JNIEXPORT jstring JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_monitorInfo(JNIEnv* env,jclass,jlong handle){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it!=audioMixers.end())m=it->second;}return env->NewStringUTF(m?m->monitorInfo().c_str():"");}
extern "C" JNIEXPORT jfloat JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_getPeak(JNIEnv* env,jclass,jlong handle,jstring sourceId){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return 0.0f;m=it->second;}return m->getPeak(jstr(env,sourceId));}
extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeAudioMixer_stop(JNIEnv*,jclass,jlong handle){std::shared_ptr<stream4k60::NativeAudioMixer> m;{std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);if(it==audioMixers.end())return;m=it->second;audioMixers.erase(it);}m->stop();}
// For the direct USB microphone capture (usb_audio_jni.cpp): the mixer behind a Kotlin handle, null once it is stopped.
std::shared_ptr<stream4k60::NativeAudioMixer> stream4k60FindAudioMixer(jlong handle){if(handle==0)return nullptr;std::lock_guard<std::mutex> l(um);auto it=audioMixers.find(handle);return it==audioMixers.end()?nullptr:it->second;}

JNIEXPORT jint JNI_OnLoad(JavaVM*,void*){return JNI_VERSION_1_6;}
