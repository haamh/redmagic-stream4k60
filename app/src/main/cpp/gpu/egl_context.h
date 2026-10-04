#pragma once
#include <string>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <mutex>
class EglContext{public:EglContext()=default;~EglContext();bool initialize();bool createOffscreenContext();void release();void shutdown(){release();}bool isInitialized()const{return initialized_;}EGLDisplay display()const{return display_;}EGLContext context()const{return context_;}EGLSurface surface()const{return surface_;}EGLSurface createWindowSurface(ANativeWindow*window,int transfer=0,bool tenBit=false,std::string*info=nullptr);bool hasExtension(const char*name)const;void setHdrMetadata(EGLSurface s,float peakNits);EGLImageKHR createEglImageFromBuffer(AHardwareBuffer*b);void destroyEglImage(EGLImageKHR image);private:EGLDisplay display_=EGL_NO_DISPLAY;EGLConfig config_=nullptr;EGLConfig config10_=nullptr;bool noConfig_=false;EGLContext context_=EGL_NO_CONTEXT;EGLSurface surface_=EGL_NO_SURFACE;bool initialized_=false;std::mutex mutex_;PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR_=nullptr;PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR_=nullptr;PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID_=nullptr;bool loadExtensions();};
