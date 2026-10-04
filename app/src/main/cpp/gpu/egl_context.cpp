#include "egl_context.h"
#include <android/native_window.h>
#include <string>
EglContext::~EglContext(){release();}
bool EglContext::initialize(){std::lock_guard<std::mutex>l(mutex_);if(initialized_)return true;display_=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(display_==EGL_NO_DISPLAY||eglInitialize(display_,nullptr,nullptr)!=EGL_TRUE)return false;if(!loadExtensions()){eglTerminate(display_);display_=EGL_NO_DISPLAY;return false;}initialized_=true;return true;}
bool EglContext::createOffscreenContext(){if(!initialize())return false;const EGLint a[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_SURFACE_TYPE,EGL_PBUFFER_BIT|EGL_WINDOW_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};EGLint n=0;if(!eglChooseConfig(display_,a,&config_,1,&n)||!n)return false;const EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};// EGL_KHR_no_config_context: one context draws the 8-bit preview and the 10-bit encoder window HDR / P010 needs.
noConfig_=hasExtension("EGL_KHR_no_config_context");context_=eglCreateContext(display_,noConfig_?EGL_NO_CONFIG_KHR:config_,EGL_NO_CONTEXT,ca);if(context_==EGL_NO_CONTEXT&&noConfig_){noConfig_=false;context_=eglCreateContext(display_,config_,EGL_NO_CONTEXT,ca);}if(context_==EGL_NO_CONTEXT)return false;const EGLint pa[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};surface_=eglCreatePbufferSurface(display_,config_,pa);if(surface_==EGL_NO_SURFACE)return false;return eglMakeCurrent(display_,surface_,surface_,context_)==EGL_TRUE;}
bool EglContext::hasExtension(const char*name)const{const char*all=display_!=EGL_NO_DISPLAY?eglQueryString(display_,EGL_EXTENSIONS):nullptr;if(!all)return false;const std::string list=std::string(" ")+all+" ";return list.find(std::string(" ")+name+" ")!=std::string::npos;}
// transfer 0 SDR, 1 PQ, 2 HLG. HDR / 10-bit output (OBS's P010 + Rec. 2100) renders into an RGBA 1010102 window tagged
// BT.2020 PQ / HLG, which the hardware encoder turns into 10-bit HEVC. The shader writes the encoded (PQ / HLG) values.
EGLSurface EglContext::createWindowSurface(ANativeWindow*w,int transfer,bool tenBit,std::string*info){
    if(!w)return EGL_NO_SURFACE;
    EGLConfig cfg=config_;bool used10=false;
    if(tenBit&&noConfig_){
        if(!config10_){
            const EGLint a[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RED_SIZE,10,EGL_GREEN_SIZE,10,EGL_BLUE_SIZE,10,EGL_ALPHA_SIZE,2,EGL_NONE};
            EGLConfig c[16];EGLint n=0;
            if(eglChooseConfig(display_,a,c,16,&n))for(int i=0;i<n;++i){EGLint r=0,al=0;eglGetConfigAttrib(display_,c[i],EGL_RED_SIZE,&r);eglGetConfigAttrib(display_,c[i],EGL_ALPHA_SIZE,&al);if(r==10&&al==2){config10_=c[i];break;}}
        }
        if(config10_){cfg=config10_;used10=true;}
    }
    // EGL_GL_COLORSPACE_KHR = BT2020_PQ (0x3340) / BT2020_HLG (0x3540): tags the buffers, no conversion is applied.
    EGLint attrs[3]={EGL_NONE,EGL_NONE,EGL_NONE};bool tagged=false;
    if(transfer==1&&hasExtension("EGL_EXT_gl_colorspace_bt2020_pq")){attrs[0]=0x309D;attrs[1]=0x3340;tagged=true;}
    else if(transfer==2&&hasExtension("EGL_EXT_gl_colorspace_bt2020_hlg")){attrs[0]=0x309D;attrs[1]=0x3540;tagged=true;}
    EGLSurface s=eglCreateWindowSurface(display_,cfg,w,attrs);
    if(s==EGL_NO_SURFACE&&tagged){const EGLint none[]={EGL_NONE};s=eglCreateWindowSurface(display_,cfg,w,none);tagged=false;}
    if(s==EGL_NO_SURFACE&&used10){const EGLint none[]={EGL_NONE};s=eglCreateWindowSurface(display_,config_,w,none);used10=false;}
    // ADATASPACE_BT2020_PQ / ADATASPACE_BT2020_HLG (BT.2020, PQ / HLG, full-range RGB): how the encoder reads the pixels.
    if(s!=EGL_NO_SURFACE&&transfer!=0)ANativeWindow_setBuffersDataSpace(w,transfer==1?163971072:168165376);
    if(info)*info=std::string(used10?"10-bit RGBA 1010102":(tenBit?"8-bit RGBA (no 10-bit EGL window on this GPU)":"8-bit RGBA"))+
        (transfer==1?", BT.2020 PQ":transfer==2?", BT.2020 HLG":", SDR")+(transfer!=0?(tagged?" (EGL colour space)":" (dataspace)"):"");
    return s;
}
// HDR10 static metadata (as the encoder sends YouTube): BT.2020 mastering primaries, D65, mastered for [peakNits], MaxCLL =
// MaxFALL = peak. The screen's tone mapper then treats the preview exactly as the stream. EGL_EXT_surface_SMPTE2086_metadata /
// EGL_EXT_surface_CTA861_3_metadata; values are scaled by EGL_METADATA_SCALING_EXT (50000).
void EglContext::setHdrMetadata(EGLSurface s,float peakNits){
    if(s==EGL_NO_SURFACE)return;
    const auto v=[](double x){return static_cast<EGLint>(x*50000.0+0.5);};
    if(hasExtension("EGL_EXT_surface_SMPTE2086_metadata")){
        const EGLint a[][2]={{0x3341,v(0.708)},{0x3342,v(0.292)},{0x3343,v(0.170)},{0x3344,v(0.797)},{0x3345,v(0.131)},{0x3346,v(0.046)},
            {0x3347,v(0.3127)},{0x3348,v(0.3290)},{0x3349,v(peakNits)},{0x334A,0}};
        for(const auto& x:a)eglSurfaceAttrib(display_,s,x[0],x[1]);
    }
    if(hasExtension("EGL_EXT_surface_CTA861_3_metadata")){eglSurfaceAttrib(display_,s,0x3360,v(peakNits));eglSurfaceAttrib(display_,s,0x3361,v(peakNits));}
}
void EglContext::release(){std::lock_guard<std::mutex>l(mutex_);if(display_!=EGL_NO_DISPLAY){eglMakeCurrent(display_,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(context_!=EGL_NO_CONTEXT)eglDestroyContext(display_,context_);if(surface_!=EGL_NO_SURFACE)eglDestroySurface(display_,surface_);eglTerminate(display_);}display_=EGL_NO_DISPLAY;context_=EGL_NO_CONTEXT;surface_=EGL_NO_SURFACE;config_=nullptr;initialized_=false;}
EGLImageKHR EglContext::createEglImageFromBuffer(AHardwareBuffer*b){if(!b||!eglCreateImageKHR_||display_==EGL_NO_DISPLAY)return EGL_NO_IMAGE_KHR;auto c=eglGetNativeClientBufferANDROID_(b);if(!c)return EGL_NO_IMAGE_KHR;const EGLint a[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};return eglCreateImageKHR_(display_,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,c,a);}
void EglContext::destroyEglImage(EGLImageKHR i){if(i!=EGL_NO_IMAGE_KHR&&eglDestroyImageKHR_)eglDestroyImageKHR_(display_,i);}
bool EglContext::loadExtensions(){eglCreateImageKHR_=reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));eglDestroyImageKHR_=reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));eglGetNativeClientBufferANDROID_=reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));return eglCreateImageKHR_&&eglDestroyImageKHR_&&eglGetNativeClientBufferANDROID_;}
