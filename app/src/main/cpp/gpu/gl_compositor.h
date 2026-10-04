#pragma once
#include "egl_context.h"
#include <GLES3/gl32.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <atomic>
#include <algorithm>
#include <vector>
#include <array>

namespace stream4k60 {

// NV12_PACKED is how the shader reads an NV12 frame stored in an RGBA8 hardware buffer (zero-copy capture): 4 luma bytes
// per texel, the interleaved chroma rows below the luma rows.
// P010 (a capture card's 10-bit HDR mode: 16-bit little-endian samples, value in the top 10 bits) is stored the same way
// in both paths: an RGBA8 texture / hardware buffer of width/2 texels, 2 luma samples per texel, then one UV pair per texel.
enum class RawPixelFormat : int { NONE = 0, YUYV = 1, UYVY = 2, NV12 = 3, NV12_PACKED = 5, P010 = 6 };

// Ordered per-layer filter chain; must match VideoFilterChain.MAX_STAGES / FLOATS_PER_STAGE in Kotlin.
constexpr int kMaxFilterStages = 8;
constexpr int kFilterStageFloats = 16;
// Stage type ids shared with VideoFilterType.nativeId in Kotlin.
constexpr int kStageLut = 5;
// LUT stages per source; each slot has its own sampler in the layer shader.
constexpr int kMaxLutSlots = 2;

struct SourceLayer {
    std::string id;
    GLuint textureId = 0;
    GLuint auxTextureId = 0;
    float x=0,y=0,w=1920,h=1080,pivotX=0,pivotY=0,rotation=0,scaleX=1,scaleY=1,opacity=1;
    float cropL=0,cropT=0,cropR=0,cropB=0;
    int z=0;
    bool visible=true,flipH=false,flipV=false,external=true;
    int filterCount=0;
    /** OBS's per-source Scale Filtering: 0 auto (area down, bicubic up), 1 point, 2 bilinear, 3 bicubic, 4 lanczos, 5 area. */
    int scaleFilter=0;
    /** The source's own signal: 0 SDR, 1 HDR10 (PQ / ST 2084), 2 HLG; converted to the output's when drawn, as OBS does. */
    int transfer=0;
    /** Raw YUV matrix chosen in Properties (-1 = as the device reports) and the one the device reports (-1 = it didn't):
     *  0 Rec. 601, 1 Rec. 709, 2 BT.2020. With neither, Rec. 709 from 720p up, else 601 (what Windows, macOS and OBS assume). */
    int yuvMatrix=-1,yuvReported=-1;
    bool yuvFull=false;
    /** Colour range chosen in Properties (-1 as reported, 0 limited, 1 full) and the one the decoder / device reports. */
    int yuvRange=-1,rangeReported=-1;
    /** What a decoder (external) texture holds: 0 RGB or unknown (drawn as the GPU converts it), 1 YUV from a video
     *  decoder (Color space / range can be re-applied from the raw YUV), 2 RGB from JPEG (Rec. 601, full range). */
    int decodedKind=0;
    /** SDR to HDR boost (inverse tone mapping) for an SDR source in an HDR output: strength 0..1, highlight peak in nits. */
    /** HLG look (a source read as HLG): strength and colour 0..1 (1 = plain HLG), the display peak HLG is decoded for. */
    float hlgMix=1.f,hlgGamut=1.f,hlgPeak=1000.f;
    /** Raw frames: whose frame this is (the original for a reference) and which one, for converting each frame to RGB once. */
    std::string decodeKey; uint64_t frameStamp=0;
    int filterTypes[kMaxFilterStages]={};
    float filterParams[kMaxFilterStages*kFilterStageFloats]={};
    RawPixelFormat rawFormat = RawPixelFormat::NONE;
    // Render target this layer is drawn into: "" is the program canvas, otherwise a nested scene/group key.
    std::string owner;
    // Non-empty for a scene/group source: the layer samples that scene's offscreen render.
    std::string sceneRef;
    int rawWidth=0,rawHeight=0;
    float texMatrix[16];
    SourceLayer(){for(int i=0;i<16;++i)texMatrix[i]=(i%5==0)?1.f:0.f;}
};

class GlCompositor {
public:
    bool initialize(uint32_t width,uint32_t height,int fps,JNIEnv* env);
    void shutdown();
    bool createSource(const std::string& id,JNIEnv* env,jobject& outSurface);
    void releaseSource(const std::string& id,JNIEnv* env);
    void setSourceBufferSize(const std::string& id,int w,int h,JNIEnv* env);
    void updateLayer(const std::string& id,float x,float y,float w,float h,float pivotX,float pivotY,float rot,float sx,float sy,float opacity,float cl,float ct,float cr,float cb,bool visible,int z,bool fh,bool fv);
    void updateFilterChain(const std::string& id,const int* types,int count,const float* params,int paramCount);
    // 3D LUT for a source's LUT slot. rgb is size^3 RGB8 texels, red fastest. key identifies the loaded file.
    void setLut(const std::string& id,int slot,const std::string& key,int size,const uint8_t* rgb,const float* domainMin,const float* domainMax);
    void clearLut(const std::string& id,int slot);
    std::string lutKey(const std::string& id,int slot);
    // Why initialization failed (EGL or shader compiler message), for crash reports.
    static std::string lastError();
    // Nested scenes and groups: each key renders into its own offscreen canvas of the given size.
    void setSceneTarget(const std::string& key,int width,int height);
    void retainSceneTargets(const std::vector<std::string>& keys);
    void setSourceOwner(const std::string& id,const std::string& owner);
    void setSourceScaleFilter(const std::string& id,int mode);
    void setSourceTransfer(const std::string& id,int transfer);
    /** Output colour (OBS: Settings → Advanced → Video): 0 SDR, 1 Rec. 2100 PQ, 2 Rec. 2100 HLG; 10-bit; white / peak nits. */
    void setOutputColor(int transfer,bool tenBit,float sdrWhite,float hdrPeak);
    /** HDR screen: the preview gets the output's HDR signal itself (10-bit PQ / HLG, the encoder's pixels) instead of an SDR tone map. */
    void setPreviewHdr(bool on){previewHdr_=on;}
    void setGpuBench(bool on,int flags){benchCanvas_=on;debugFlags_=flags;}
    /** Saves the next streamed canvas frame, exactly as the encoder gets it, to [path] (8-bit RGBA, top row first). */
    void grabCanvas(const std::string& path){std::lock_guard<std::mutex>lk(m_);grabPath_=path;}
    /** OBS's Paste (Reference): [id] draws [target]'s current frame with its own transform; "" removes it. */
    void setSourceAlias(const std::string& id,const std::string& target);
    /** Color space / range chosen in Properties: matrix -1 as reported, 0 Rec. 601, 1 Rec. 709, 2 BT.2020; range -1 as reported, 0 limited, 1 full. */
    void setSourceYuvColor(const std::string& id,int matrix,int range);
    void setSourceYuvReported(const std::string& id,int matrix);
    /** What a decoder surface holds (see SourceLayer::decodedKind) and the matrix / range its decoder reports (-1 = not said). */
    void setSourceDecodedColor(const std::string& id,int kind,int matrix,int range);
    /** A source that shows nothing for now (a media source whose playback ended, OBS's "Show nothing when playback ends"). */
    void setSourceBlank(const std::string& id,bool blank){std::lock_guard<std::mutex>lk(m_);if(blank)blanks_[id]=true;else blanks_.erase(id);}
    /** HLG look for a source read as HLG (often an SDR camera, for its look): [strength] blends from its SDR reading, [colour]
     *  from true (Rec. 709) colours to HLG's BT.2020 reading, [peakNits] is the display peak it is decoded for. 1, 1, 1000 = plain HLG. */
    void setSourceHlgLook(const std::string& id,float strength,float colour,float peakNits){std::lock_guard<std::mutex>lk(m_);
        if(strength>=0.999f&&colour>=0.999f&&std::abs(peakNits-1000.f)<1.f)hlgLook_.erase(id);
        else hlgLook_[id]={std::clamp(strength,0.f,1.f),std::clamp(colour,0.f,1.f),std::clamp(peakNits,100.f,4000.f)};}
    /** Where the render time goes (per-stage averages), for the stream log and Stats. */
    std::string describeRender();
    /** A source fed by raw frames (no decoder surface), created before its first frame. */
    void ensureRawSource(const std::string& id);
    /** One line of the compositor's state for a source, for the stream log. */
    std::string describeSource(const std::string& id);
    std::string encoderSurfaceInfo(){std::lock_guard<std::mutex>lk(m_);return encoderSurfaceInfo_;}
    void setSourceSceneRef(const std::string& id,const std::string& key);
    bool updateRgba(const std::string& id,const uint8_t* pixels,size_t bytes,int width,int height);
    bool updateRaw(const std::string& id,const uint8_t* pixels,size_t bytes,int width,int height,RawPixelFormat format);
    bool setPreviewSurface(JNIEnv* env,jobject surface);
    bool setEncoderSurface(JNIEnv* env,jobject surface);
    // A second, small output that shows one source on its own (the Properties preview). Empty id or null surface turns it off.
    bool setSoloPreview(JNIEnv* env,const std::string& id,jobject surface);
    bool start(); void stop();
    void setCanvas(uint32_t w,uint32_t h){canvasW_.store(w);canvasH_.store(h);}
    void setVideoSettings(uint32_t w,uint32_t h,int fps){if(w>0&&h>0){canvasW_.store(w);canvasH_.store(h);}if(fps>0)fps_.store(std::clamp(fps,1,240));}
    void setTransition(int type,int durationMs){transitionType_=type;transitionDurationMs_=std::max(1,durationMs);if(type==0)transitionProgress_=0.0f;}
    void setTransitionProgress(float progress){transitionProgress_=std::clamp(progress,0.0f,1.0f);}
    float renderMs() const{return renderMs_.load();}
    uint64_t frames()const{return frames_.load();}
    uint64_t dropped()const{return dropped_.load();}
    /** Frames rendered into the encoder's surface; minus frames the encoder output = frames skipped for encoding lag. */
    uint64_t encoderFrames()const{return encoderFrames_.load();}
private:
    struct Source {
        SourceLayer layer;
        bool external=true;
        int pixelW=0,pixelH=0;
        std::vector<uint8_t> pendingRgba;
        std::vector<uint8_t> pendingRaw;
        jobject surfaceTexture=nullptr;
        GLuint oesTexture=0; // the decoder (external) texture; never reused for raw YUV uploads
        uint64_t rawFrames=0,rawUploads=0; int lastGlError=0; bool hadContext=false; // diagnostics
        // Set once the editor has sent the source's on-canvas rect; frames must not overwrite it with their own size
        // (a capture whose format changed snapped out of its box and stretched after every drag).
        bool placed=false;
        jobject surface=nullptr;
        jmethodID updateTex=nullptr;
        jmethodID getMatrix=nullptr;
        jobject matrixArray=nullptr;
        // Raw frames are copied into a spare buffer outside the lock and swapped in; the render thread swaps them out
        // and uploads without holding the lock (a 4K NV12 copy under it stalled the USB thread, the UI and the frame).
        std::vector<uint8_t> spareRaw;
        int allocW=0,allocH=0,allocFmt=-1; // storage of the raw textures (glTexStorage2D), re-allocated only on change
        // Zero-copy capture: the USB thread writes each raw frame into a GPU-shareable hardware buffer that the GPU samples
        // in place, so the render thread copies nothing (a 4K NV12 upload took most of a 60 fps frame). Each slot is free,
        // being written, pending (newest, not drawn yet), shown, or retiring until the GPU has finished reading it.
        enum { HB_FREE=0, HB_WRITING=1, HB_PENDING=2, HB_SHOWN=3, HB_RETIRING=4, HB_RETIRE_AFTER_FRAME=5 };
        struct HbSlot { AHardwareBuffer* hb=nullptr; EGLImageKHR image=EGL_NO_IMAGE_KHR; GLuint tex=0; int texW=0,texH=0; uint32_t stride=0; int state=0; EGLSyncKHR sync=EGL_NO_SYNC_KHR; int w=0,h=0; RawPixelFormat fmt=RawPixelFormat::NONE; };
        std::array<HbSlot,4> hb;
        int hbShown=-1,hbPending=-1;
        bool hbFallback=false; // the hardware-buffer path failed: frames go through the texture upload instead
        uint64_t hbFrames=0;
        std::vector<std::pair<EGLImageKHR,GLuint>> hbGarbage; // render-thread objects of replaced buffers
        std::vector<AHardwareBuffer*> hbRelease;
    };
    bool updateRawZeroCopy(const std::string& id,const uint8_t* pixels,int width,int height,RawPixelFormat format);
    void releaseHardwareBuffers(Source& s);
    PFNEGLCREATESYNCKHRPROC createSync_=nullptr; PFNEGLCLIENTWAITSYNCKHRPROC clientWaitSync_=nullptr; PFNEGLDESTROYSYNCKHRPROC destroySync_=nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTargetTexture_=nullptr;
    std::atomic<bool> zeroCopy_{false};
    struct UploadJob { std::string id; RawPixelFormat format=RawPixelFormat::NONE; bool rgba=false; int width=0,height=0; GLuint texture=0,aux=0; int allocW=0,allocH=0,allocFmt=-1; std::vector<uint8_t> data; };
    struct ExternalTex { std::string id; jobject surfaceTexture=nullptr; jmethodID updateTex=nullptr,getMatrix=nullptr; jobject matrixArray=nullptr; bool updated=false; float matrix[16]; };
    void uploadJob(UploadJob& job);
    // The program canvas rendered once per frame while streaming; the encoder and the preview are scaled copies of it.
    GLuint canvasFbo_=0,canvasTex_=0;int canvasAllocW_=0,canvasAllocH_=0;bool canvasAllocHdr_=false;
    void drawTextureFull(GLuint texture,int texW,int texH,int dstW,int dstH,int signal,int output);
    float refWhiteFor(int output)const{return output!=0?203.f:(outputTransfer_.load()!=0?sdrWhite_.load():203.f);}
    float kneeFor(int output)const{return output==0&&outputTransfer_.load()!=0?0.92f:0.75f;}
    int previewTransferWanted()const{return previewHdr_.load()?outputTransfer_.load():0;}
    bool yuvTarget_=false;
    struct Uniforms { GLint ext=-1,raw=-1,canvas=-1,rect=-1,scale=-1,pivot=-1,rot=-1,mat=-1,op=-1,crop=-1,fh=-1,fv=-1,rawSize=-1,extTex=-1,tex2d=-1,rawTex=-1,rawAux=-1,sf=-1,tf=-1,out=-1,sw=-1,hp=-1,ym=-1,yf=-1,
        stageCount=-1,stageType=-1,stageParams=-1,texel=-1,lutMin=-1,lutMax=-1,lutSize=-1,lut0=-1,lut1=-1,clipFlipY=-1,premul=-1,extMode=-1,srcMatrix=-1,srcFull=-1,refWhite=-1,yuvTex=-1,knee=-1,hlgLook=-1,hlgMix=-1,hlgGamut=-1,hlgPeak=-1,localSize=-1,decodePass=-1,scroll=-1; } u_;
    // Render-thread stage times (ms, smoothed) for describeRender().
    std::atomic<float> tPrepare_{0},tScenes_{0},tCanvas_{0},tPreview_{0},tEncoder_{0},tSolo_{0},tWait_{0};
    // Where a streaming frame's time really goes: GPU time of the canvas and encoder passes (EXT_disjoint_timer_query,
    // read a frame later), and how long handing the frame to the encoder blocks (its input queue full = encoder behind).
    std::atomic<float> gpuCanvas_{0},gpuEncoder_{0},tEncSwap_{0},gpuPrep_{0};
    // Measurement (adb): render the 4K canvas every frame without streaming, and switch costs off to see what they cost.
    // debugFlags_: 1 bilinear scaling for every layer, 2 no colour conversion, 4 no filters.
    std::atomic<bool> benchCanvas_{false};std::atomic<int> debugFlags_{0};std::string grabPath_;
    GLuint prepQ_[2]={0,0};int prepSet_=0;bool prepPending_[2]={false,false};
    GLuint timerQ_[2][2]={{0,0},{0,0}};int timerSet_=0;bool timerInit_=false,timerOk_=false,timerPending_[2]={false,false},timerEnc_[2]={false,false};
    void (*getQueryUi64_)(GLuint,GLenum,GLuint64*)=nullptr;
    std::atomic<uint64_t> lateFrames_{0};
    std::atomic<int> uploadsPerSec_{0};
    EglContext egl_;
    GLuint vao_=0,vbo_=0,program_=0,overlayProgram_=0,copyProgram_=0;GLint copyTexLoc_=-1;
    int transitionType_=0; int transitionDurationMs_=300; float transitionProgress_=0.0f; float transitionR_=0.0f,transitionG_=0.0f,transitionB_=0.0f;
    std::atomic<uint32_t> canvasW_{1920},canvasH_{1080};
    std::atomic<int> fps_{60};
    EGLSurface preview_=EGL_NO_SURFACE,encoder_=EGL_NO_SURFACE;
    ANativeWindow* previewWin_=nullptr,*encoderWin_=nullptr;
    EGLSurface solo_=EGL_NO_SURFACE;
    ANativeWindow* soloWin_=nullptr;
    std::string soloId_;
    std::map<std::string,Source> sources_;
    std::map<std::string,SourceLayer> pendingFilters_;
    struct Lut {
        std::string key;
        int size=0;
        float domainMin[3]={0,0,0},domainMax[3]={1,1,1};
        std::vector<uint8_t> pending;
        GLuint texture=0;
    };
    // Keyed by "<sourceId>#<slot>"; survives source surface re-creation.
    std::map<std::string,Lut> luts_;
    std::vector<GLuint> lutTexturesToDelete_;
    struct SceneTarget { int width=0,height=0; GLuint fbo=0,texture=0; int allocW=0,allocH=0; bool allocHdr=false; };
    std::map<std::string,SceneTarget> sceneTargets_;
    std::vector<std::pair<GLuint,GLuint>> sceneTargetsToDelete_; // fbo, texture
    std::map<std::string,std::string> owners_;
    std::map<std::string,int> scaleFilters_;
    std::map<std::string,int> transfers_;
    std::map<std::string,std::string> aliases_;
    std::map<std::string,std::pair<int,int>> yuvColors_;      // matrix, range chosen in Properties
    std::map<std::string,int> yuvReported_;
    std::map<std::string,std::array<int,3>> decodedColors_;  // kind, matrix, range a decoder reports
    std::map<std::string,bool> blanks_;
    // Raw USB frames converted to RGB once per new frame at their own size (render thread only).
    struct DecodeTarget{GLuint fbo=0,tex=0;int w=0,h=0;uint64_t stamp=~0ull;GLuint srcTex=0;int colourSig=-1;};
    std::map<std::string,DecodeTarget> decoded_;
    bool decodePass_=false;
    void decodeRawSources(std::vector<SourceLayer>& layers);
    std::map<std::string,std::array<float,3>> hlgLook_;
    std::atomic<int> outputTransfer_{0};std::atomic<bool> outputTenBit_{false};std::atomic<float> sdrWhite_{300.f},hdrPeak_{1000.f};
    std::string encoderSurfaceInfo_;
    std::atomic<bool> previewHdr_{false};
    // What the preview window was made as (under m_): its signal, the HDR peak in its metadata, and a description.
    int previewSurfTransfer_=0;float previewSurfPeak_=0.f;std::string previewSurfaceInfo_="8-bit RGBA, SDR";
    // Render thread only: the signal the current target is written in, and the one nested scene canvases are kept in.
    int drawOutput_=0,sceneEncoding_=0;
    mutable std::mutex m_;
    // Serialises everything that pauses the render thread and borrows the GL context (surface create/release, start/stop):
    // two Java threads doing it at once left one without a current context and its surface with texture 0.
    std::recursive_mutex ctxMutex_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<float> renderMs_{0};
    std::atomic<uint64_t> frames_{0},dropped_{0},encoderFrames_{0};
    JavaVM* vm_=nullptr;
    bool setupGl();
    void loop();
    std::vector<SourceLayer> prepareFrame();
    void renderSceneTargets(const std::vector<SourceLayer>& layers);
    void drawLayers(const std::vector<SourceLayer>& layers,const std::string& owner,int canvasWidth,int canvasHeight,bool toOffscreen);
    /**
     * An output window held for one rendered frame. The UI thread may replace or release the window at any time
     * (set*Surface); the extra reference keeps the ANativeWindow alive until the frame is done with it.
     */
    struct HeldWindow {
        EGLSurface surface=EGL_NO_SURFACE; ANativeWindow* window=nullptr;
        HeldWindow(EGLSurface s,ANativeWindow* w){if(s!=EGL_NO_SURFACE&&w){surface=s;window=w;ANativeWindow_acquire(w);}}
        ~HeldWindow(){if(window)ANativeWindow_release(window);}
        HeldWindow(const HeldWindow&)=delete; HeldWindow& operator=(const HeldWindow&)=delete;
        int width()const{return window?ANativeWindow_getWidth(window):0;}
        int height()const{return window?ANativeWindow_getHeight(window):0;}
    };
    void renderTo(EGLSurface target,int width,int height,int canvasWidth,int canvasHeight,const std::vector<SourceLayer>& layers,int output=0);
    void renderSolo(const HeldWindow& solo,const std::string& id,const std::vector<SourceLayer>& layers);
    void destroyWindow(EGLSurface& s,ANativeWindow*& w);
    Source* source(const std::string&id);
    void applyPendingFilters(SourceLayer& layer,const std::string&id);
    void bindSurfaceTexture(Source& s,JNIEnv* env);
    void renderTransitionOverlay(int width,int height);
};
}
