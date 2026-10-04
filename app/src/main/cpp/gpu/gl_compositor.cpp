#include "gl_compositor.h"
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>
#if __ANDROID_API__ >= 31
#include <android/performance_hint.h>
#endif
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <android/native_window_jni.h>
#include <chrono>
#include <memory>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <cstdio>
#include <functional>

namespace stream4k60 {
// Canvas copies (encoder, preview) in the same signal: a tiny shader. They ran through the full source shader, whose
// size (every source type, filter and colour path) keeps the GPU from running many pixels at once, so a plain 4K copy
// took ~10 ms of GPU time per frame.
static const char* COPY_VS=R"GLSL(#version 320 es
layout(location=0)in vec2 aPos;layout(location=1)in vec2 aUv;out vec2 vUv;
void main(){gl_Position=vec4(aPos.x,-aPos.y,0.0,1.0);vUv=vec2(aUv.x,1.0-aUv.y);})GLSL";
static const char* COPY_FS=R"GLSL(#version 320 es
precision highp float;in vec2 vUv;out vec4 frag;uniform sampler2D uTex;
void main(){vec2 dx=dFdx(vUv),dy=dFdy(vUv);vec2 size=vec2(textureSize(uTex,0));
if(max(length(dx*size),length(dy*size))<1.25){frag=vec4(texture(uTex,vUv).rgb,1.0);return;}
vec2 a=0.25*(dx+dy),b=0.25*(dx-dy);
frag=vec4(0.25*(texture(uTex,vUv+a).rgb+texture(uTex,vUv-a).rgb+texture(uTex,vUv+b).rgb+texture(uTex,vUv-b).rgb),1.0);})GLSL";

static const char* VS=R"GLSL(#version 320 es
layout(location=0)in vec2 aPos;layout(location=1)in vec2 aUv;
uniform vec2 uCanvas;uniform vec4 uRect;uniform vec2 uScale;uniform vec2 uPivot;uniform float uRotation;uniform mat4 uTexMatrix;
uniform float uClipFlipY;
out vec2 vUv;
void main(){vec2 p=(aPos*.5+.5)*uRect.zw*uScale;p-=uPivot;float cs=cos(uRotation),sn=sin(uRotation);p=vec2(p.x*cs-p.y*sn,p.x*sn+p.y*cs)+uPivot+uRect.xy;vec2 clip=vec2(p.x/uCanvas.x*2.-1.,(1.-p.y/uCanvas.y*2.)*uClipFlipY);gl_Position=vec4(clip,0,1);vUv=(uTexMatrix*vec4(aUv,0,1)).xy;})GLSL";
static const char* FS=R"GLSL(#version 320 es
#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : enable
precision highp float;
in vec2 vUv;
uniform samplerExternalOES uExtTex;
uniform sampler2D u2DTex;
uniform sampler2D uRawTex;
uniform sampler2D uRawAuxTex;
uniform int uExternal;
uniform int uRawFormat;
uniform vec2 uRawSize;
uniform float uOpacity;
uniform vec4 uCrop;
uniform bool uFlipH;uniform bool uFlipV;
const int MAX_STAGES=8;
uniform int uStageCount;
uniform int uStageType[MAX_STAGES];
uniform vec4 uStageParams[MAX_STAGES*4];
uniform vec2 uTexel;
uniform bool uPremultiplied;
precision highp sampler3D;
uniform sampler3D uLut0;
uniform sampler3D uLut1;
uniform vec3 uLutMin[2];
uniform vec3 uLutMax[2];
uniform float uLutSize[2];
out vec4 frag;
// YUV to RGB with the source's matrix (0 Rec. 601, 1 Rec. 709, 2 BT.2020) and range. Every source used to be read as limited
// Rec. 601 (standard definition), which tints HD captures: greens too strong, reds orange, skin tones off.
uniform int uYuvMatrix;uniform int uYuvFull;
// How a decoder (external) texture is read, so every video source gets the capture card's Color space / range options:
// 0 the GPU's own conversion (what the decoder tagged), 1 the raw YUV (GL_EXT_YUV_target) converted here with the chosen
// matrix / range, 2 the GPU's RGB turned back into YUV with what it assumed (uSrcMatrix / uSrcFull) and re-converted.
uniform int uExtMode;uniform int uSrcMatrix;uniform int uSrcFull;
#ifdef GL_EXT_YUV_target
uniform highp __samplerExternal2DY2YEXT uYuvTex;
#endif
vec2 kRB(int m){return m==1?vec2(0.2126,0.0722):(m==2?vec2(0.2627,0.0593):vec2(0.299,0.114));}
vec3 yuvToRgbWith(vec3 yuv,int m,int full){float y=yuv.x,u=yuv.y-0.5019608,v=yuv.z-0.5019608;
if(full==0){y=1.16438356*(y-0.0627451);u*=1.13839286;v*=1.13839286;}
vec2 k=kRB(m);float r=y+2.0*(1.0-k.x)*v,b=y+2.0*(1.0-k.y)*u;float g=(y-k.x*r-k.y*b)/(1.0-k.x-k.y);
return clamp(vec3(r,g,b),0.0,1.0);}
vec3 rgbToYuvWith(vec3 c,int m,int full){vec2 k=kRB(m);float y=dot(c,vec3(k.x,1.0-k.x-k.y,k.y));float u=(c.b-y)/(2.0*(1.0-k.y)),v=(c.r-y)/(2.0*(1.0-k.x));
if(full==0){y=0.0627451+y*0.85882353;u*=0.87843137;v*=0.87843137;}return vec3(y,u+0.5019608,v+0.5019608);}
vec3 yuvToRgb(float y,float u,float v){return yuvToRgbWith(vec3(y,u,v),uYuvMatrix,uYuvFull);}
vec4 externalColorAt(vec2 st){
#ifdef GL_EXT_YUV_target
if(uExtMode==1)return vec4(yuvToRgbWith(texture(uYuvTex,st).xyz,uYuvMatrix,uYuvFull),1.0);
#endif
vec4 c=texture(uExtTex,st);if(uExtMode==2)c.rgb=yuvToRgbWith(rgbToYuvWith(c.rgb,uSrcMatrix,uSrcFull),uYuvMatrix,uYuvFull);return c;}
// Raw USB frames are sampled at the cropped/flipped coordinate like every other source type.
// NV12 in an RGBA8 hardware buffer (zero-copy capture): luma rows (4 per texel), then chroma rows (2 UV pairs per texel).
// Nearest texels: the scale filter's taps do the filtering.
vec4 nv12PackedAt(vec2 st){vec2 sz=uRawSize;float xi=min(floor(clamp(st.x,0.0,1.0)*sz.x),sz.x-1.0),yi=min(floor(clamp(st.y,0.0,1.0)*sz.y),sz.y-1.0);
float tw=sz.x*0.25,th=sz.y*1.5;vec4 ty=texture(uRawTex,vec2((floor(xi*0.25)+0.5)/tw,(yi+0.5)/th));float c=mod(xi,4.0);
float y=c<0.5?ty.r:(c<1.5?ty.g:(c<2.5?ty.b:ty.a));float pair=floor(xi*0.5);
vec4 tc=texture(uRawTex,vec2((floor(pair*0.5)+0.5)/tw,(sz.y+floor(yi*0.5)+0.5)/th));vec2 uv=mod(pair,2.0)<0.5?tc.rg:tc.ba;
return vec4(yuvToRgb(y,uv.x,uv.y),1.0);}
// P010 in RGBA8 texels (see RawPixelFormat): bytes are recombined into the 16-bit sample, scaled to the 10-bit code / 1023.
float p010Sample(vec2 lohi){return (lohi.x+lohi.y*256.0)*(255.0/65472.0);}
vec4 p010PackedAt(vec2 st){vec2 sz=uRawSize;float xi=min(floor(clamp(st.x,0.0,1.0)*sz.x),sz.x-1.0),yi=min(floor(clamp(st.y,0.0,1.0)*sz.y),sz.y-1.0);
float tw=sz.x*0.5,th=sz.y*1.5;vec4 ty=texture(uRawTex,vec2((floor(xi*0.5)+0.5)/tw,(yi+0.5)/th));float y=p010Sample(mod(xi,2.0)<0.5?ty.rg:ty.ba);
vec4 tc=texture(uRawTex,vec2((floor(xi*0.5)+0.5)/tw,(sz.y+floor(yi*0.5)+0.5)/th));
return vec4(yuvToRgb(y,p010Sample(tc.rg),p010Sample(tc.ba)),1.0);}
vec4 rawColorAt(vec2 st){if(uRawFormat==5)return nv12PackedAt(st);if(uRawFormat==6)return p010PackedAt(st);if(uRawFormat==1||uRawFormat==2){float px=clamp(st.x,0.0,1.0)*uRawSize.x;float pair=floor(px*0.5);float which=mod(floor(px),2.0);vec2 tc=(vec2(pair+0.5,floor(clamp(st.y,0.0,1.0)*uRawSize.y)+0.5))/vec2(max(1.0,ceil(uRawSize.x*0.5)),uRawSize.y);vec4 p=texture(uRawTex,tc);float y,u,v;if(uRawFormat==1){y=(which<0.5)?p.r:p.b;u=p.g;v=p.a;}else{y=(which<0.5)?p.g:p.a;u=p.r;v=p.b;}return vec4(yuvToRgb(y,u,v),1.0);}if(uRawFormat==3){float y=texture(uRawTex,st).r;vec2 uv=texture(uRawAuxTex,st).rg;return vec4(yuvToRgb(y,uv.r,uv.g),1.0);}return vec4(0.0);}
// Ordinary textures (decoded camera frames, images, text, scenes) and decoder (external) textures: one hardware-filtered read.
vec4 tex2(vec2 st){return uExternal==1?externalColorAt(st):texture(u2DTex,st);}
vec4 sampleSource(vec2 st){return uRawFormat!=0?rawColorAt(st):tex2(st);}
// Scaling a source to its size on the canvas, as OBS's Scale Filtering does. Shrinking (a 4K video on a 9:16 canvas,
// the preview): average the source pixels each output pixel covers (area), where one bilinear tap read only 2x2 of
// them and turned detail into grain. Enlarging: a bicubic (Catmull-Rom) or Lanczos kernel keeps edges crisp where
// bilinear blurs them. uScaleFilter: 0 auto (area down, bicubic up), 1 point, 2 bilinear, 3 bicubic, 4 lanczos, 5 area.
uniform int uScaleFilter;
// Colour, as OBS: every source keeps its own signal (uTransfer: 0 SDR, 1 HDR10 PQ, 2 HLG) and is converted into the
// output's (uOutput, same codes). An HDR clip in an HDR stream goes out untouched; SDR sources (camera, SDR video,
// images) are placed in HDR at the SDR white level (nits); HDR sources in an SDR stream or the preview are tone-mapped,
// highlights rolled off up to the HDR nominal peak. Shown as SDR without this, HDR10 looked flat, dark and blue-purple.
uniform int uTransfer;uniform int uOutput;uniform float uSdrWhite;uniform float uHdrPeak;
// The white HDR is tone-mapped to on SDR targets: 203 nits (BT.2408) when the stream is SDR; the SDR white level when it is
// HDR, so the preview shows HDR and SDR sources at the same relative brightness the HDR stream's viewers see.
uniform float uRefWhite;
// Where the highlight roll-off starts (fraction of reference white): 0.75 tone-maps HDR into an SDR stream properly; the
// SDR preview of an HDR stream uses 0.92, or every SDR camera lost the top quarter of its range and looked dull.
uniform float uKnee;
// HLG look (see setSourceHlgLook): uHlgLook 1 when a source's HLG reading is adjusted.
uniform int uHlgLook;uniform float uHlgMix;uniform float uHlgGamut;uniform float uHlgPeak;
// The raw-frame-to-RGB pass: uDecodePass 1, target size uLocalSize.
uniform vec2 uLocalSize;uniform int uDecodePass;
// Scroll filter: offset (fraction of the whole source) the source has rolled by, wrapping around.
uniform vec2 uScroll;
const mat3 kBt709To2020=mat3(0.6274,0.0691,0.0164,0.3293,0.9195,0.0880,0.0433,0.0114,0.8956);
const mat3 kBt2020To709=mat3(1.6605,-0.1246,-0.0182,-0.5876,1.1329,-0.1006,-0.0728,-0.0083,1.1187);
const vec3 kLuma2020=vec3(0.2627,0.6780,0.0593);
vec3 srgbToLinear(vec3 c){c=clamp(c,0.0,1.0);return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}
vec3 linearToSrgb(vec3 c){c=clamp(c,0.0,1.0);return mix(12.92*c,1.055*pow(c,vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));}
const float kM1=0.1593017578125,kM2=78.84375,kC1=0.8359375,kC2=18.8515625,kC3=18.6875;
vec3 pqToNits(vec3 e){vec3 p=pow(clamp(e,0.0,1.0),vec3(1.0/kM2));return 10000.0*pow(max(p-kC1,0.0)/(kC2-kC3*p),vec3(1.0/kM1));}
vec3 nitsToPq(vec3 n){vec3 y=pow(clamp(n/10000.0,0.0,1.0),vec3(kM1));return pow((kC1+kC2*y)/(1.0+kC3*y),vec3(kM2));}
// HLG for a 1000-nit display (BT.2100, system gamma 1.2).
const float kHa=0.17883277,kHb=0.28466892,kHc=0.55991073;
vec3 hlgToNits(vec3 e){e=clamp(e,0.0,1.0);vec3 s=mix(e*e/3.0,(exp((e-kHc)/kHa)+kHb)/12.0,step(vec3(0.5),e));return 1000.0*s*pow(max(dot(s,kLuma2020),1e-6),0.2);}
// HLG decoded for a display of [peak] nits (BT.2100: system gamma 1.2 at 1000 nits, +0.42 per decade).
vec3 hlgToNitsPeak(vec3 e,float peak){e=clamp(e,0.0,1.0);vec3 s=mix(e*e/3.0,(exp((e-kHc)/kHa)+kHb)/12.0,step(vec3(0.5),e));
float g=clamp(1.2+0.42*log(peak/1000.0)/log(10.0),1.0,1.5);return peak*s*pow(max(dot(s,kLuma2020),1e-6),g-1.0);}
// An HLG source's light in BT.2020 nits, with its look applied: colour blends the BT.2020 reading (vivid) toward the same
// values read as Rec. 709 (true colours), strength blends toward the plain SDR reading of the signal.
vec3 hlgSourceNits(vec3 e,float sdrNits){if(uHlgLook==0)return hlgToNits(e);vec3 h=hlgToNitsPeak(e,uHlgPeak);
h=mix(kBt709To2020*h,h,uHlgGamut);return mix(kBt709To2020*srgbToLinear(e)*sdrNits,h,uHlgMix);}
vec3 nitsToHlg(vec3 n){vec3 d=clamp(n/1000.0,0.0,1.0);vec3 s=clamp(d*pow(max(dot(d,kLuma2020),1e-6),-0.2/1.2),0.0,1.0);return mix(sqrt(3.0*s),kHa*log(max(12.0*s-kHb,1e-6))+kHc,step(vec3(1.0/12.0),s));}
vec3 convertSignal(vec3 e){
// HDR to SDR puts HDR's reference white (203 nits, ITU BT.2408) at SDR white; the SDR white level setting is for the
// other direction. Dividing by it (300 by default) made every HDR source about 1.5x too dark on SDR.
if(uOutput==0){float kRefWhite=max(uRefWhite,80.0);vec3 rgb=max(kBt2020To709*((uTransfer==1?pqToNits(e):hlgSourceNits(e,kRefWhite))/kRefWhite),0.0);
float peak=max(uHdrPeak/kRefWhite,1.01);float ks=clamp(uKnee,0.5,0.98);float w=(peak-ks)*(1.0-ks)/(peak-1.0);
float mx=max(max(rgb.r,rgb.g),rgb.b);if(mx>ks){float d=mx-ks;rgb*=min(ks+d/(1.0+d/w),1.0)/mx;}return linearToSrgb(rgb);}
vec3 nits;
if(uTransfer==0)nits=kBt709To2020*srgbToLinear(e)*uSdrWhite;
else nits=uTransfer==1?pqToNits(e):hlgSourceNits(e,uSdrWhite);
return uOutput==1?nitsToPq(nits):nitsToHlg(nits);}
vec2 sourceSize(){return uExternal==1?vec2(textureSize(uExtTex,0)):(uRawFormat!=0?uRawSize:vec2(textureSize(u2DTex,0)));}
// Scaling with the GPU's bilinear filter doing most of the work, as OBS's scale effects: shrinking averages four filtered reads
// spread over the pixel's footprint (each covers 2x2 texels); enlarging is Catmull-Rom bicubic from nine filtered reads.
// These used to call the full sampling function (every source format, colour conversion) up to 16 times per pixel; that
// made the compiled shader so large the GPU could only run a few pixels at once, and scaling cost ~25 ms of a 4K frame.
vec4 catmullRom(vec2 st,vec2 size){vec2 p=st*size;vec2 t1=floor(p-0.5)+0.5;vec2 f=p-t1;
vec2 w0=f*(-0.5+f*(1.0-0.5*f)),w1=1.0+f*f*(-2.5+1.5*f),w2=f*(0.5+f*(2.0-1.5*f)),w3=f*f*(-0.5+0.5*f);
vec2 w12=w1+w2;vec2 t0=(t1-1.0)/size,t3=(t1+2.0)/size,t12=(t1+w2/w12)/size;
vec4 r=tex2(vec2(t0.x,t0.y))*w0.x*w0.y+tex2(vec2(t12.x,t0.y))*w12.x*w0.y+tex2(vec2(t3.x,t0.y))*w3.x*w0.y
+tex2(vec2(t0.x,t12.y))*w0.x*w12.y+tex2(vec2(t12.x,t12.y))*w12.x*w12.y+tex2(vec2(t3.x,t12.y))*w3.x*w12.y
+tex2(vec2(t0.x,t3.y))*w0.x*w3.y+tex2(vec2(t12.x,t3.y))*w12.x*w3.y+tex2(vec2(t3.x,t3.y))*w3.x*w3.y;
return clamp(r,0.0,1.0);}
vec4 sampleScaled(vec2 st){
// A raw frame is converted to RGB first; read raw only if that conversion didn't happen.
if(uRawFormat!=0)return rawColorAt(st);
if(uScaleFilter==2)return tex2(st);
vec2 size=sourceSize();
if(uScaleFilter==1)return tex2((floor(st*size)+0.5)/size);
vec2 dx=dFdx(st),dy=dFdy(st);float texels=max(length(dx*size),length(dy*size));
if(texels>1.05){float k=texels>2.5?0.375:0.25;vec2 a=k*(dx+dy),b=k*(dx-dy);return 0.25*(tex2(st+a)+tex2(st-a)+tex2(st+b)+tex2(st-b));}
if(texels<0.95&&uScaleFilter!=5)return catmullRom(st,size);
return tex2(st);}
const vec3 LUMA=vec3(0.2126,0.7152,0.0722);
// Rotation about the gray axis (Rodrigues), which preserves neutral colors.
vec3 hueRotate(vec3 c,float degrees){float a=radians(degrees);float cs=cos(a),sn=sin(a);vec3 k=vec3(0.57735026);
return c*cs+cross(k,c)*sn+k*dot(k,c)*(1.0-cs);}
// p0=(brightness,contrast,saturation,gammaExponent) p1=(hueDegrees,opacity) p2=multiply.rgb p3=add.rgb
vec4 colorCorrection(vec4 c,vec4 p0,vec4 p1,vec4 p2,vec4 p3){vec3 rgb=pow(max(c.rgb,vec3(0.0)),vec3(p0.w));
rgb=(rgb-0.5)*p0.y+0.5+p0.x;float l=dot(rgb,LUMA);rgb=mix(vec3(l),rgb,p0.z);
if(p1.x!=0.0)rgb=hueRotate(rgb,p1.x);rgb=rgb*p2.rgb+p3.rgb;return vec4(clamp(rgb,0.0,1.0),c.a*p1.y);}
// OBS key filters' own adjustment: p=(opacity,contrast,brightness,gammaExponent)
vec4 keyAdjust(vec4 c,vec4 p){return vec4(clamp(pow(max(c.rgb,vec3(0.0)),vec3(p.w))*p.y+p.z,0.0,1.0),c.a*p.x);}
// p0=(keyCr,keyCb,similarity,smoothness) p1.x=spill; distance measured in CbCr like OBS chroma_key_filter.
vec4 chromaKey(vec4 c,vec4 p0,vec4 p1,vec4 p2){float cb=dot(c.rgb,vec3(-0.100644,-0.338572,0.439216))+0.501961;
float cr=dot(c.rgb,vec3(0.439216,-0.398942,-0.040274))+0.501961;float base=distance(vec2(cr,cb),p0.xy)-p0.z;
c.a*=pow(clamp(base/p0.w,0.0,1.0),1.5);float spill=pow(clamp(base/p1.x,0.0,1.0),1.5);
c.rgb=mix(vec3(dot(c.rgb,LUMA)),c.rgb,spill);return keyAdjust(c,p2);}
// p0=(key.rgb,similarity) p1.x=smoothness; RGB distance like OBS color_key_filter.
vec4 colorKey(vec4 c,vec4 p0,vec4 p1,vec4 p2){float base=distance(c.rgb,p0.rgb)-p0.w;
c.a*=clamp(max(base,0.0)/p1.x,0.0,1.0);return keyAdjust(c,p2);}
// p0=(lumaMin,lumaMax,lumaMinSmooth,lumaMaxSmooth)
vec4 lumaKey(vec4 c,vec4 p0){float l=dot(c.rgb,LUMA);
float lo=p0.z>0.0?smoothstep(p0.x,p0.x+p0.z,l):step(p0.x,l);
float hi=p0.w>0.0?1.0-smoothstep(p0.y-p0.w,p0.y,l):step(l,p0.y);return vec4(c.rgb,c.a*lo*hi);}
// p0=(amount,slot). 3D LUT indexed r,g,b; input remapped from the LUT's domain.
vec4 applyLut(vec4 c,vec4 p0){bool s0=p0.y<0.5;vec3 lo=s0?uLutMin[0]:uLutMin[1];vec3 hi=s0?uLutMax[0]:uLutMax[1];float n=s0?uLutSize[0]:uLutSize[1];
vec3 x=clamp((c.rgb-lo)/max(hi-lo,vec3(1e-5)),0.0,1.0);vec3 coord=x*((n-1.0)/n)+0.5/n;
vec3 graded=s0?texture(uLut0,coord).rgb:texture(uLut1,coord).rgb;return vec4(mix(c.rgb,graded,p0.x),c.a);}
vec4 applyPixelStage(vec4 c,int i){int b=i*4;int t=uStageType[i];
if(t==1)return colorCorrection(c,uStageParams[b],uStageParams[b+1],uStageParams[b+2],uStageParams[b+3]);
if(t==2)return chromaKey(c,uStageParams[b],uStageParams[b+1],uStageParams[b+2]);
if(t==3)return colorKey(c,uStageParams[b],uStageParams[b+1],uStageParams[b+2]);
if(t==4)return lumaKey(c,uStageParams[b]);
if(t==5)return applyLut(c,uStageParams[b]);
return c;}
// Stages before `end`, skipping sharpen (used to filter a sharpen stage's neighbor samples).
vec4 applyPixelStages(vec4 c,int end){for(int i=0;i<MAX_STAGES;++i){if(i>=end)break;c=applyPixelStage(c,i);}return c;}
vec4 applyFilters(vec4 c,vec2 st){for(int i=0;i<MAX_STAGES;++i){if(i>=uStageCount)break;
if(uStageType[i]==6){float s=uStageParams[i*4].x;
vec4 n=applyPixelStages(tex2(st+vec2(0.0,-uTexel.y)),i)+applyPixelStages(tex2(st+vec2(0.0,uTexel.y)),i)
+applyPixelStages(tex2(st+vec2(-uTexel.x,0.0)),i)+applyPixelStages(tex2(st+vec2(uTexel.x,0.0)),i);
c=vec4(clamp(c.rgb+(4.0*c.rgb-n.rgb)*s,0.0,1.0),c.a);}
else c=applyPixelStage(c,i);}return c;}
void main(){
// SDR to HDR's local brightness pass: the source drawn whole into a small target, position from the fragment itself, so it
// lines up with the texture coordinates the real draw samples it at.
// Raw frame to RGB, once per camera frame, at the frame's own size (position from the fragment).
if(uDecodePass==1){frag=vec4(rawColorAt(gl_FragCoord.xy/uLocalSize).rgb,1.0);return;}
vec2 uv=mix(uCrop.xy,uCrop.zw,vUv);if(uFlipH)uv.x=1.-uv.x;if(uFlipV)uv.y=1.-uv.y;
// As OBS: the filter rolls the whole source (wrapping around), and the scene item's crop then shows a window of it.
// Only the axis that moves wraps: wrapping both made a horizontal ticker's top / bottom edge pick up the opposite edge.
if(uScroll.x!=0.0)uv.x=fract(uv.x+uScroll.x);if(uScroll.y!=0.0)uv.y=fract(uv.y+uScroll.y);vec4 c=sampleScaled(uv);if(uPremultiplied&&c.a>0.0)c.rgb/=c.a;c=applyFilters(c,uv);if(uTransfer!=uOutput||(uTransfer==2&&uHlgLook==1))c.rgb=convertSignal(c.rgb);frag=vec4(c.rgb,c.a*uOpacity);})GLSL";
static std::string gLastError;
static GLuint compileShader(GLenum t,const char*s){GLuint x=glCreateShader(t);glShaderSource(x,1,&s,nullptr);glCompileShader(x);GLint ok=0;glGetShaderiv(x,GL_COMPILE_STATUS,&ok);
if(!ok){char log[2048]={0};glGetShaderInfoLog(x,sizeof(log)-1,nullptr,log);gLastError=std::string(t==GL_VERTEX_SHADER?"Vertex":"Fragment")+" shader failed to compile: "+log;glDeleteShader(x);return 0;}return x;}
static GLuint createProgram(){auto v=compileShader(GL_VERTEX_SHADER,VS);auto f=compileShader(GL_FRAGMENT_SHADER,FS);if(!v||!f){if(v)glDeleteShader(v);if(f)glDeleteShader(f);return 0;}GLuint p=glCreateProgram();glAttachShader(p,v);glAttachShader(p,f);glBindAttribLocation(p,0,"aPos");glBindAttribLocation(p,1,"aUv");glLinkProgram(p);GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);glDeleteShader(v);glDeleteShader(f);if(!ok){char log[2048]={0};glGetProgramInfoLog(p,sizeof(log)-1,nullptr,log);gLastError=std::string("Shader program failed to link: ")+log;glDeleteProgram(p);return 0;}return p;}

bool GlCompositor::initialize(uint32_t w,uint32_t h,int fps,JNIEnv* env){canvasW_.store(w);canvasH_.store(h);fps_.store(std::clamp(fps,1,240));env->GetJavaVM(&vm_);if(!egl_.initialize()){gLastError="EGL display could not be initialized (eglInitialize or required Android EGL extensions missing).";return false;}if(!egl_.createOffscreenContext()){gLastError="EGL could not create an OpenGL ES 3 context (error 0x"+[]{char b[16];snprintf(b,sizeof b,"%X",eglGetError());return std::string(b);}()+").";return false;}
    // The context is created current on this (the app's) thread. Release it once the GL setup is done: a context can be
    // current on one thread only, and the render thread could not take it. Creating a video / decoder surface used to
    // release it by accident, so a scene with only an uncompressed USB capture never rendered (black preview).
    const bool ok=setupGl();eglMakeCurrent(egl_.display(),EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);return ok;}
bool GlCompositor::setupGl(){program_=createProgram();if(!program_)return false;
    // Every sampler gets its own texture unit once. Unset samplers default to unit 0, and GLES rejects
    // draws where samplers of different types (external, 2D, 3D) share a unit.
    glUseProgram(program_);
    glUniform1i(glGetUniformLocation(program_,"uExtTex"),0);glUniform1i(glGetUniformLocation(program_,"u2DTex"),1);
    glUniform1i(glGetUniformLocation(program_,"uRawTex"),2);glUniform1i(glGetUniformLocation(program_,"uRawAuxTex"),3);
    glUniform1i(glGetUniformLocation(program_,"uLut0"),4);glUniform1i(glGetUniformLocation(program_,"uLut1"),5);
    // Looked up once: drawLayers ran ~40 glGetUniformLocation calls per target per frame.
    auto L=[&](const char* n){return glGetUniformLocation(program_,n);};
    u_.ext=L("uExternal");u_.raw=L("uRawFormat");u_.canvas=L("uCanvas");u_.rect=L("uRect");u_.scale=L("uScale");u_.pivot=L("uPivot");u_.rot=L("uRotation");u_.mat=L("uTexMatrix");
    u_.op=L("uOpacity");u_.crop=L("uCrop");u_.fh=L("uFlipH");u_.fv=L("uFlipV");u_.rawSize=L("uRawSize");u_.extTex=L("uExtTex");u_.tex2d=L("u2DTex");u_.rawTex=L("uRawTex");u_.rawAux=L("uRawAuxTex");
    u_.sf=L("uScaleFilter");u_.tf=L("uTransfer");u_.out=L("uOutput");u_.sw=L("uSdrWhite");u_.hp=L("uHdrPeak");u_.ym=L("uYuvMatrix");u_.yf=L("uYuvFull");
    u_.stageCount=L("uStageCount");u_.stageType=L("uStageType");u_.stageParams=L("uStageParams");u_.texel=L("uTexel");u_.lutMin=L("uLutMin");u_.lutMax=L("uLutMax");u_.lutSize=L("uLutSize");
    u_.lut0=L("uLut0");u_.lut1=L("uLut1");u_.clipFlipY=L("uClipFlipY");u_.premul=L("uPremultiplied");u_.extMode=L("uExtMode");u_.srcMatrix=L("uSrcMatrix");u_.srcFull=L("uSrcFull");u_.refWhite=L("uRefWhite");u_.yuvTex=L("uYuvTex");u_.knee=L("uKnee");u_.localSize=L("uLocalSize");u_.decodePass=L("uDecodePass");u_.scroll=L("uScroll");u_.hlgLook=L("uHlgLook");u_.hlgMix=L("uHlgMix");u_.hlgGamut=L("uHlgGamut");u_.hlgPeak=L("uHlgPeak");
    // The raw-YUV sampler exists only where the driver has GL_EXT_YUV_target (the Astra's Adreno does).
    yuvTarget_=u_.yuvTex>=0;if(yuvTarget_)glUniform1i(u_.yuvTex,6);
    createSync_=reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    clientWaitSync_=reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
    destroySync_=reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    imageTargetTexture_=reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    zeroCopy_=createSync_&&clientWaitSync_&&destroySync_&&imageTargetTexture_;
    glUseProgram(0);
const char* ovs=R"GLSL(#version 320 es
layout(location=0)in vec2 aPos;out vec2 v;void main(){v=aPos;gl_Position=vec4(aPos,0.,1.);}
)GLSL";const char* ofs=R"GLSL(#version 320 es
precision highp float;uniform vec4 uColor;out vec4 frag;void main(){frag=uColor;}
)GLSL";auto compileLocal=[](GLenum t,const char*src)->GLuint{GLuint x=glCreateShader(t);glShaderSource(x,1,&src,nullptr);glCompileShader(x);GLint ok=0;glGetShaderiv(x,GL_COMPILE_STATUS,&ok);if(!ok){glDeleteShader(x);return 0;}return x;};GLuint ov=compileLocal(GL_VERTEX_SHADER,ovs),of=compileLocal(GL_FRAGMENT_SHADER,ofs);if(!ov||!of){if(ov)glDeleteShader(ov);if(of)glDeleteShader(of);return false;}{GLuint cv=compileLocal(GL_VERTEX_SHADER,COPY_VS),cf=compileLocal(GL_FRAGMENT_SHADER,COPY_FS);if(cv&&cf){copyProgram_=glCreateProgram();glAttachShader(copyProgram_,cv);glAttachShader(copyProgram_,cf);glLinkProgram(copyProgram_);GLint cl=0;glGetProgramiv(copyProgram_,GL_LINK_STATUS,&cl);if(!cl){glDeleteProgram(copyProgram_);copyProgram_=0;}else copyTexLoc_=glGetUniformLocation(copyProgram_,"uTex");}if(cv)glDeleteShader(cv);if(cf)glDeleteShader(cf);}
overlayProgram_=glCreateProgram();glAttachShader(overlayProgram_,ov);glAttachShader(overlayProgram_,of);glLinkProgram(overlayProgram_);GLint ol=0;glGetProgramiv(overlayProgram_,GL_LINK_STATUS,&ol);glDeleteShader(ov);glDeleteShader(of);if(!ol)return false;const float q[]={-1,-1,0,1,1,-1,1,1,-1,1,0,0,1,1,1,0};glGenVertexArrays(1,&vao_);glGenBuffers(1,&vbo_);glBindVertexArray(vao_);glBindBuffer(GL_ARRAY_BUFFER,vbo_);glBufferData(GL_ARRAY_BUFFER,sizeof(q),q,GL_STATIC_DRAW);glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(float),(void*)0);glEnableVertexAttribArray(1);glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(float),(void*)(2*sizeof(float)));glBindVertexArray(0);return true;}
void GlCompositor::destroyWindow(EGLSurface&s,ANativeWindow*&w){if(s!=EGL_NO_SURFACE){eglDestroySurface(egl_.display(),s);s=EGL_NO_SURFACE;}if(w){ANativeWindow_release(w);w=nullptr;}}
bool GlCompositor::setSoloPreview(JNIEnv* env,const std::string& id,jobject js){std::lock_guard<std::mutex>lk(m_);destroyWindow(solo_,soloWin_);soloId_=js?id:std::string();if(!js||id.empty())return true;soloWin_=ANativeWindow_fromSurface(env,js);if(!soloWin_)return false;solo_=egl_.createWindowSurface(soloWin_);return solo_!=EGL_NO_SURFACE;}
bool GlCompositor::setPreviewSurface(JNIEnv* env,jobject js){std::lock_guard<std::mutex>lk(m_);destroyWindow(preview_,previewWin_);if(!js)return true;previewWin_=ANativeWindow_fromSurface(env,js);if(!previewWin_)return false;const int t=previewTransferWanted();preview_=egl_.createWindowSurface(previewWin_,t,t!=0,&previewSurfaceInfo_);previewSurfTransfer_=t;previewSurfPeak_=0.f;return preview_!=EGL_NO_SURFACE;}
bool GlCompositor::setEncoderSurface(JNIEnv* env,jobject js){std::lock_guard<std::mutex>lk(m_);destroyWindow(encoder_,encoderWin_);if(!js)return true;encoderWin_=ANativeWindow_fromSurface(env,js);if(!encoderWin_)return false;encoder_=egl_.createWindowSurface(encoderWin_,outputTransfer_.load(),outputTenBit_.load(),&encoderSurfaceInfo_);return encoder_!=EGL_NO_SURFACE;}
void GlCompositor::bindSurfaceTexture(Source&s,JNIEnv*env){if(!s.surfaceTexture)return;jclass c=env->GetObjectClass(s.surfaceTexture);s.updateTex=env->GetMethodID(c,"updateTexImage","()V");s.getMatrix=env->GetMethodID(c,"getTransformMatrix","([F)V");jfloatArray a=(jfloatArray)env->NewFloatArray(16);s.matrixArray=env->NewGlobalRef(a);env->DeleteLocalRef(a);}
 // Runs on the caller's thread with the render thread stopped; an EGL context can be current on one thread only, so it
// is released again before the render thread restarts.
bool GlCompositor::createSource(const std::string&id,JNIEnv*env,jobject&out){std::lock_guard<std::recursive_mutex>cl(ctxMutex_);bool was=running_.load();if(was)stop();if(!eglMakeCurrent(egl_.display(),egl_.surface(),egl_.surface(),egl_.context())){if(was)start();return false;}std::lock_guard<std::mutex>lk(m_);if(sources_.count(id)){out=env->NewLocalRef(sources_[id].surface);eglMakeCurrent(egl_.display(),EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(was)start();return true;}GLuint tex=0;glGenTextures(1,&tex);glBindTexture(GL_TEXTURE_EXTERNAL_OES,tex);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);jclass stc=env->FindClass("android/graphics/SurfaceTexture");jmethodID ctor=env->GetMethodID(stc,"<init>","(I)V");jobject st=env->NewObject(stc,ctor,(jint)tex);jclass sc=env->FindClass("android/view/Surface");jmethodID sctor=env->GetMethodID(sc,"<init>","(Landroid/graphics/SurfaceTexture;)V");jobject surf=env->NewObject(sc,sctor,st);Source x;x.layer.id=id;x.layer.external=true;applyPendingFilters(x.layer,id);x.external=true;x.layer.textureId=tex;x.oesTexture=tex;x.surfaceTexture=env->NewGlobalRef(st);x.surface=env->NewGlobalRef(surf);bindSurfaceTexture(x,env);sources_[id]=x;out=env->NewLocalRef(surf);env->DeleteLocalRef(st);env->DeleteLocalRef(surf);eglMakeCurrent(egl_.display(),EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(was)start();return true;}
void GlCompositor::setSourceBufferSize(const std::string&id,int w,int h,JNIEnv*env){std::lock_guard<std::mutex>lk(m_);auto it=sources_.find(id);if(it==sources_.end()||!it->second.surfaceTexture)return;jclass c=env->GetObjectClass(it->second.surfaceTexture);jmethodID m=env->GetMethodID(c,"setDefaultBufferSize","(II)V");if(m)env->CallVoidMethod(it->second.surfaceTexture,m,w,h);if(w>0&&h>0){it->second.pixelW=w;it->second.pixelH=h;}}
void GlCompositor::releaseSource(const std::string&id,JNIEnv*env){std::lock_guard<std::recursive_mutex>cl(ctxMutex_);bool was=running_.load();if(was)stop();eglMakeCurrent(egl_.display(),egl_.surface(),egl_.surface(),egl_.context());std::lock_guard<std::mutex>lk(m_);auto it=sources_.find(id);if(it==sources_.end()){eglMakeCurrent(egl_.display(),EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(was)start();return;}auto&s=it->second;for(const auto& x:s.hb)if(x.tex&&x.tex==s.layer.textureId)s.layer.textureId=0;releaseHardwareBuffers(s);if(s.matrixArray)env->DeleteGlobalRef(s.matrixArray);if(s.surfaceTexture)env->DeleteGlobalRef(s.surfaceTexture);if(s.surface)env->DeleteGlobalRef(s.surface);if(s.layer.textureId)glDeleteTextures(1,&s.layer.textureId);if(s.oesTexture&&s.oesTexture!=s.layer.textureId)glDeleteTextures(1,&s.oesTexture);if(s.layer.auxTextureId)glDeleteTextures(1,&s.layer.auxTextureId);sources_.erase(it);eglMakeCurrent(egl_.display(),EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(was)start();}
GlCompositor::Source* GlCompositor::source(const std::string&id){auto it=sources_.find(id);return it==sources_.end()?nullptr:&it->second;}
void GlCompositor::applyPendingFilters(SourceLayer&layer,const std::string&id){auto it=pendingFilters_.find(id);if(it==pendingFilters_.end())return;const auto&p=it->second;layer.filterCount=p.filterCount;std::copy(std::begin(p.filterTypes),std::end(p.filterTypes),layer.filterTypes);std::copy(std::begin(p.filterParams),std::end(p.filterParams),layer.filterParams);}
void GlCompositor::updateLayer(const std::string&id,float x,float y,float w,float h,float pivotX,float pivotY,float r,float sx,float sy,float op,float cl,float ct,float cr,float cb,bool vis,int z,bool fh,bool fv){std::lock_guard<std::mutex>lk(m_);auto*s=source(id);if(!s)return;s->layer.x=x;s->layer.y=y;s->layer.w=w;s->layer.h=h;s->layer.pivotX=pivotX;s->layer.pivotY=pivotY;s->layer.rotation=r;s->layer.scaleX=sx;s->layer.scaleY=sy;s->layer.opacity=op;s->layer.cropL=cl;s->layer.cropT=ct;s->layer.cropR=cr;s->layer.cropB=cb;s->layer.visible=vis;s->layer.z=z;s->layer.flipH=fh;s->layer.flipV=fv;s->placed=true;}
bool GlCompositor::updateRgba(const std::string&id,const uint8_t*pixels,size_t bytes,int width,int height){if(!pixels||bytes==0||width<=0||height<=0)return false;std::lock_guard<std::mutex>lk(m_);auto &s=sources_[id];s.layer.id=id;applyPendingFilters(s.layer,id);s.external=false;s.layer.external=false;s.layer.rawFormat=RawPixelFormat::NONE;s.pixelW=width;s.pixelH=height;s.pendingRgba.assign(pixels,pixels+bytes);s.pendingRaw.clear();s.layer.rawWidth=width;s.layer.rawHeight=height;s.layer.texMatrix[0]=s.layer.texMatrix[5]=s.layer.texMatrix[10]=s.layer.texMatrix[15]=1.f;for(int i:{1,2,3,4,6,7,8,9,11,12,13,14})s.layer.texMatrix[i]=0.f;s.layer.texMatrix[5]=-1.f;s.layer.texMatrix[13]=1.f;if(!s.placed){s.layer.w=width;s.layer.h=height;}return true;}
bool GlCompositor::updateRaw(const std::string&id,const uint8_t*pixels,size_t bytes,int width,int height,RawPixelFormat format){if(!pixels||bytes==0||width<=0||height<=0||format==RawPixelFormat::NONE)return false;
    // The upload reads a whole frame. A frame cut short by lost USB packets would make the GPU driver read past the
    // end of the buffer (a native crash), so incomplete frames are dropped.
    const size_t w=static_cast<size_t>(width),h=static_cast<size_t>(height);
    const size_t required=format==RawPixelFormat::NV12?w*h+((w+1)/2)*((h+1)/2)*2:format==RawPixelFormat::P010?(w*h+w*(h/2))*2:((w+1)/2)*h*4;
    if(format==RawPixelFormat::P010&&(width%2!=0||height%2!=0))return false;
    if(bytes<required)return false;
    // Zero-copy first: straight into a hardware buffer the GPU reads in place.
    if(zeroCopy_.load()&&(format==RawPixelFormat::NV12?(width%4==0&&height%2==0):(width%2==0))&&updateRawZeroCopy(id,pixels,width,height,format))return true;
    // The frame is copied into the source's spare buffer without the lock (12 MB for 4K NV12), then swapped in.
    std::vector<uint8_t> buf;
    {std::lock_guard<std::mutex>lk(m_);buf.swap(sources_[id].spareRaw);}
    buf.assign(pixels,pixels+required);
    std::lock_guard<std::mutex>lk(m_);auto &s=sources_[id];s.layer.id=id;applyPendingFilters(s.layer,id);s.external=false;s.layer.external=false;s.layer.rawFormat=format;s.layer.rawWidth=width;s.layer.rawHeight=height;s.pixelW=width;s.pixelH=height;
    s.pendingRaw.swap(buf); // a frame the renderer hadn't taken yet is replaced: the newest wins
    if(s.spareRaw.capacity()<buf.capacity())s.spareRaw.swap(buf);
    s.rawFrames++;s.pendingRgba.clear();if(!s.placed){s.layer.w=width;s.layer.h=height;}s.layer.texMatrix[0]=s.layer.texMatrix[5]=s.layer.texMatrix[10]=s.layer.texMatrix[15]=1.f;for(int i:{1,2,3,4,6,7,8,9,11,12,13,14})s.layer.texMatrix[i]=0.f;s.layer.texMatrix[5]=-1.f;s.layer.texMatrix[13]=1.f;return true;}

// The USB thread's side of zero-copy capture: one copy of the frame into a free hardware buffer (outside the lock), then it
// becomes the source's pending frame. Returns false to use the texture-upload path instead.
bool GlCompositor::updateRawZeroCopy(const std::string&id,const uint8_t*pixels,int width,int height,RawPixelFormat format){
    const int texW=format==RawPixelFormat::NV12?width/4:width/2,texH=(format==RawPixelFormat::NV12||format==RawPixelFormat::P010)?height+height/2:height;
    int slot=-1;AHardwareBuffer* hb=nullptr;uint32_t stride=0;
    {
        std::lock_guard<std::mutex>lk(m_);
        auto& s=sources_[id];
        if(s.hbFallback||s.surfaceTexture)return false;
        // Buffers the GPU has finished reading are free again.
        for(auto& x:s.hb)if(x.state==Source::HB_RETIRING&&x.sync!=EGL_NO_SYNC_KHR&&clientWaitSync_(egl_.display(),x.sync,0,0)==EGL_CONDITION_SATISFIED_KHR){destroySync_(egl_.display(),x.sync);x.sync=EGL_NO_SYNC_KHR;x.state=Source::HB_FREE;}
        for(int i=0;i<static_cast<int>(s.hb.size());++i)if(s.hb[i].state==Source::HB_FREE){slot=i;break;}
        if(slot<0&&s.hbPending>=0){slot=s.hbPending;s.hbPending=-1;} // the newest frame replaces one not drawn yet
        if(slot<0)return true; // every buffer is still in use by the GPU: this frame is skipped
        auto& x=s.hb[slot];x.state=Source::HB_WRITING;
        if(x.hb&&(x.texW!=texW||x.texH!=texH)){
            if(x.image!=EGL_NO_IMAGE_KHR||x.tex)s.hbGarbage.push_back({x.image,x.tex});
            s.hbRelease.push_back(x.hb);x.hb=nullptr;x.image=EGL_NO_IMAGE_KHR;x.tex=0;
        }
        hb=x.hb;stride=x.stride;
    }
    const bool fresh=!hb;
    auto fail=[&]{std::lock_guard<std::mutex>lk(m_);auto it=sources_.find(id);if(it!=sources_.end()){it->second.hb[slot].state=Source::HB_FREE;it->second.hbFallback=true;}if(fresh&&hb)AHardwareBuffer_release(hb);return false;};
    if(fresh){
        AHardwareBuffer_Desc d{};d.width=static_cast<uint32_t>(texW);d.height=static_cast<uint32_t>(texH);d.layers=1;d.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage=AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        if(AHardwareBuffer_allocate(&d,&hb)!=0||!hb){hb=nullptr;return fail();}
        AHardwareBuffer_Desc got{};AHardwareBuffer_describe(hb,&got);stride=got.stride;
    }
    void* dst=nullptr;
    if(AHardwareBuffer_lock(hb,AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,-1,nullptr,&dst)!=0||!dst)return fail();
    const size_t row=static_cast<size_t>(texW)*4;
    if(stride==static_cast<uint32_t>(texW))std::memcpy(dst,pixels,row*static_cast<size_t>(texH));
    else for(int r=0;r<texH;++r)std::memcpy(static_cast<uint8_t*>(dst)+static_cast<size_t>(r)*stride*4,pixels+static_cast<size_t>(r)*row,row);
    AHardwareBuffer_unlock(hb,nullptr);
    std::lock_guard<std::mutex>lk(m_);
    auto it=sources_.find(id);
    if(it==sources_.end()){if(fresh)AHardwareBuffer_release(hb);return true;} // released meanwhile
    auto& s=it->second;auto& x=s.hb[slot];
    if(fresh){x.hb=hb;x.stride=stride;x.texW=texW;x.texH=texH;}
    x.w=width;x.h=height;x.fmt=format;x.state=Source::HB_PENDING;
    if(s.hbPending>=0&&s.hbPending!=slot)s.hb[s.hbPending].state=Source::HB_FREE;
    s.hbPending=slot;s.hbFrames++;
    s.layer.id=id;applyPendingFilters(s.layer,id);s.external=false;s.layer.external=false;s.pixelW=width;s.pixelH=height;s.rawFrames++;
    s.pendingRaw.clear();s.pendingRgba.clear();
    if(!s.placed){s.layer.w=static_cast<float>(width);s.layer.h=static_cast<float>(height);}
    for(int i=0;i<16;++i)s.layer.texMatrix[i]=(i%5==0)?1.f:0.f;
    s.layer.texMatrix[5]=-1.f;s.layer.texMatrix[13]=1.f;
    return true;
}

void GlCompositor::releaseHardwareBuffers(Source& s){
    for(auto& x:s.hb){
        if(x.sync!=EGL_NO_SYNC_KHR&&destroySync_)destroySync_(egl_.display(),x.sync);
        if(x.tex)glDeleteTextures(1,&x.tex);
        if(x.image!=EGL_NO_IMAGE_KHR)egl_.destroyEglImage(x.image);
        if(x.hb)AHardwareBuffer_release(x.hb);
        x=Source::HbSlot{};
    }
    for(auto& g:s.hbGarbage){if(g.second)glDeleteTextures(1,&g.second);if(g.first!=EGL_NO_IMAGE_KHR)egl_.destroyEglImage(g.first);}
    for(auto* b:s.hbRelease)AHardwareBuffer_release(b);
    s.hbGarbage.clear();s.hbRelease.clear();s.hbShown=s.hbPending=-1;
}

void GlCompositor::updateFilterChain(const std::string&id,const int*types,int count,const float*params,int paramCount){
    std::lock_guard<std::mutex>lk(m_);
    auto apply=[&](SourceLayer&layer){
        const int n=std::clamp(std::min(count,paramCount/kFilterStageFloats),0,kMaxFilterStages);
        layer.filterCount=n;
        std::fill(std::begin(layer.filterTypes),std::end(layer.filterTypes),0);
        std::fill(std::begin(layer.filterParams),std::end(layer.filterParams),0.f);
        for(int i=0;i<n;++i)layer.filterTypes[i]=types[i];
        std::copy(params,params+n*kFilterStageFloats,layer.filterParams);
    };
    if(auto*s=source(id))apply(s->layer);
    // Remembered per id, so a source re-created later (camera restart, media reload) keeps its filters without the UI
    // having to send them again.
    SourceLayer remembered;remembered.id=id;apply(remembered);pendingFilters_[id]=remembered;
}
static std::string lutMapKey(const std::string&id,int slot){return id+"#"+std::to_string(slot);}
void GlCompositor::setLut(const std::string&id,int slot,const std::string&key,int size,const uint8_t*rgb,const float*dmin,const float*dmax){
    if(slot<0||slot>=kMaxLutSlots||size<2||!rgb)return;
    std::lock_guard<std::mutex>lk(m_);
    auto&l=luts_[lutMapKey(id,slot)];
    l.key=key;l.size=size;
    for(int i=0;i<3;++i){l.domainMin[i]=dmin?dmin[i]:0.f;l.domainMax[i]=dmax?dmax[i]:1.f;}
    l.pending.assign(rgb,rgb+static_cast<size_t>(size)*size*size*3);
}
void GlCompositor::clearLut(const std::string&id,int slot){
    std::lock_guard<std::mutex>lk(m_);
    auto it=luts_.find(lutMapKey(id,slot));
    if(it==luts_.end())return;
    if(it->second.texture)lutTexturesToDelete_.push_back(it->second.texture);
    luts_.erase(it);
}
std::string GlCompositor::lastError(){return gLastError;}
std::string GlCompositor::lutKey(const std::string&id,int slot){
    std::lock_guard<std::mutex>lk(m_);
    auto it=luts_.find(lutMapKey(id,slot));
    return it==luts_.end()?std::string():it->second.key;
}
void GlCompositor::setSceneTarget(const std::string&key,int width,int height){
    if(key.empty())return;
    std::lock_guard<std::mutex>lk(m_);
    auto&t=sceneTargets_[key];
    t.width=std::clamp(width,16,8192);t.height=std::clamp(height,16,8192);
}
void GlCompositor::retainSceneTargets(const std::vector<std::string>&keys){
    std::lock_guard<std::mutex>lk(m_);
    for(auto it=sceneTargets_.begin();it!=sceneTargets_.end();){
        if(std::find(keys.begin(),keys.end(),it->first)==keys.end()){
            sceneTargetsToDelete_.push_back({it->second.fbo,it->second.texture});
            it=sceneTargets_.erase(it);
        }else ++it;
    }
}
void GlCompositor::setSourceYuvColor(const std::string&id,int matrix,int range){std::lock_guard<std::mutex>lk(m_);yuvColors_[id]={std::clamp(matrix,-1,2),std::clamp(range,-1,1)};}
void GlCompositor::setSourceDecodedColor(const std::string&id,int kind,int matrix,int range){std::lock_guard<std::mutex>lk(m_);decodedColors_[id]={std::clamp(kind,0,2),std::clamp(matrix,-1,2),std::clamp(range,-1,1)};}
std::string GlCompositor::describeSource(const std::string&id){std::lock_guard<std::mutex>lk(m_);auto it=sources_.find(id);if(it==sources_.end())return "not in the compositor";const auto&s=it->second;
    return "external="+std::to_string(s.external)+" layerExternal="+std::to_string(s.layer.external)+" rawFormat="+std::to_string(static_cast<int>(s.layer.rawFormat))+" texture="+std::to_string(s.layer.textureId)+" aux="+std::to_string(s.layer.auxTextureId)+" oes="+std::to_string(s.oesTexture)+" rawFrames="+std::to_string(s.rawFrames)+" uploads="+std::to_string(s.rawUploads)+" glError="+std::to_string(s.lastGlError)+" context="+std::to_string(s.hadContext)+" pending="+std::to_string(s.pendingRaw.size())+" zeroCopy="+std::to_string(!s.hbFallback&&s.hbFrames>0)+" hbFrames="+std::to_string(s.hbFrames)+" visible="+std::to_string(s.layer.visible)+" size="+std::to_string((int)s.layer.w)+"x"+std::to_string((int)s.layer.h)+" at "+std::to_string((int)s.layer.x)+","+std::to_string((int)s.layer.y);}
void GlCompositor::ensureRawSource(const std::string&id){std::lock_guard<std::mutex>lk(m_);auto&s=sources_[id];s.layer.id=id;if(!s.surfaceTexture){s.external=false;s.layer.external=false;}applyPendingFilters(s.layer,id);}
void GlCompositor::setSourceYuvReported(const std::string&id,int matrix){std::lock_guard<std::mutex>lk(m_);yuvReported_[id]=std::clamp(matrix,-1,2);}
void GlCompositor::setSourceAlias(const std::string&id,const std::string&target){
    std::lock_guard<std::mutex>lk(m_);
    if(target.empty()||target==id){
        if(aliases_.erase(id)){auto it=sources_.find(id);if(it!=sources_.end()&&!it->second.surfaceTexture&&!it->second.layer.textureId)sources_.erase(it);}
        return;
    }
    aliases_[id]=target;
    // A placeholder entry holds the reference's own transform (updateLayer needs it); it never gets a texture.
    auto& s=sources_[id];s.layer.id=id;s.external=false;s.layer.external=false;applyPendingFilters(s.layer,id);
}
void GlCompositor::setOutputColor(int transfer,bool tenBit,float sdrWhite,float hdrPeak){outputTransfer_=std::clamp(transfer,0,2);outputTenBit_=tenBit||transfer!=0;sdrWhite_=std::clamp(sdrWhite,80.f,480.f);hdrPeak_=std::clamp(hdrPeak,400.f,10000.f);}
void GlCompositor::setSourceTransfer(const std::string&id,int transfer){std::lock_guard<std::mutex>lk(m_);transfers_[id]=std::clamp(transfer,0,2);}
void GlCompositor::setSourceScaleFilter(const std::string&id,int mode){std::lock_guard<std::mutex>lk(m_);scaleFilters_[id]=std::clamp(mode,0,5);}
void GlCompositor::setSourceOwner(const std::string&id,const std::string&owner){
    std::lock_guard<std::mutex>lk(m_);
    if(owner.empty())owners_.erase(id);else owners_[id]=owner;
}
void GlCompositor::setSourceSceneRef(const std::string&id,const std::string&key){
    std::lock_guard<std::mutex>lk(m_);
    auto&s=sources_[id];
    s.layer.id=id;
    applyPendingFilters(s.layer,id);
    s.external=false;s.layer.external=false;
    s.layer.rawFormat=RawPixelFormat::NONE;
    s.layer.sceneRef=key;
    // Offscreen canvases are rendered top row first, like CPU uploads.
    for(int i=0;i<16;++i)s.layer.texMatrix[i]=(i%5==0)?1.f:0.f;
    s.layer.texMatrix[5]=-1.f;s.layer.texMatrix[13]=1.f;
}
void GlCompositor::renderTransitionOverlay(int width,int height){if(!overlayProgram_||transitionType_==0||transitionProgress_<=0.001f)return;glUseProgram(overlayProgram_);glBindVertexArray(vao_);glDisable(GL_DEPTH_TEST);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);GLint c=glGetUniformLocation(overlayProgram_,"uColor");float alpha=transitionProgress_;glUniform4f(c,transitionR_,transitionG_,transitionB_,alpha);glDrawArrays(GL_TRIANGLE_STRIP,0,4);}

// Raw USB frames and bitmaps: storage is allocated once per size (glTexStorage2D) and each frame is a sub-image
// upload. glTexImage2D re-allocated a 12 MB texture for every 4K frame.
void GlCompositor::uploadJob(UploadJob& job){
    glPixelStorei(GL_UNPACK_ALIGNMENT,1);
    auto prepare=[](GLuint& tex,GLenum internal,int w,int h,bool realloc,GLint filter){
        if(realloc&&tex){glDeleteTextures(1,&tex);tex=0;}
        const bool fresh=!tex;
        if(fresh)glGenTextures(1,&tex);
        glBindTexture(GL_TEXTURE_2D,tex);
        if(fresh){
            glTexStorage2D(GL_TEXTURE_2D,1,internal,std::max(1,w),std::max(1,h));
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,filter);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,filter);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        }
    };
    const int fmt=job.rgba?100:static_cast<int>(job.format);
    const bool realloc=job.allocW!=job.width||job.allocH!=job.height||job.allocFmt!=fmt;
    if(job.rgba){
        prepare(job.texture,GL_RGBA8,job.width,job.height,realloc,GL_LINEAR);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,job.width,job.height,GL_RGBA,GL_UNSIGNED_BYTE,job.data.data());
    }else if(job.format==RawPixelFormat::YUYV||job.format==RawPixelFormat::UYVY){
        const int tw=(job.width+1)/2;
        prepare(job.texture,GL_RGBA8,tw,job.height,realloc,GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,tw,job.height,GL_RGBA,GL_UNSIGNED_BYTE,job.data.data());
    }else if(job.format==RawPixelFormat::P010){
        const int tw=job.width/2,th=job.height+job.height/2;
        prepare(job.texture,GL_RGBA8,tw,th,realloc,GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,tw,th,GL_RGBA,GL_UNSIGNED_BYTE,job.data.data());
    }else if(job.format==RawPixelFormat::NV12){
        const size_t yBytes=static_cast<size_t>(job.width)*job.height;
        const int cw=(job.width+1)/2,ch=(job.height+1)/2;
        prepare(job.texture,GL_R8,job.width,job.height,realloc,GL_LINEAR);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,job.width,job.height,GL_RED,GL_UNSIGNED_BYTE,job.data.data());
        prepare(job.aux,GL_RG8,cw,ch,realloc,GL_LINEAR);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,cw,ch,GL_RG,GL_UNSIGNED_BYTE,job.data.data()+std::min(yBytes,job.data.size()));
    }
    job.allocW=job.width;job.allocH=job.height;job.allocFmt=fmt;
}

std::vector<SourceLayer> GlCompositor::prepareFrame(){
    std::vector<SourceLayer> layers;
    std::vector<UploadJob> jobs;
    std::vector<ExternalTex> externals;
    struct HbImport { std::string id; int slot; AHardwareBuffer* hb; EGLImageKHR image=EGL_NO_IMAGE_KHR; GLuint tex=0; };
    std::vector<HbImport> imports;
    std::vector<std::pair<EGLImageKHR,GLuint>> hbGarbage;
    std::vector<AHardwareBuffer*> hbRelease;
    // 1. Under the lock: take the waiting frames (buffer swaps, no copies) and the decoder textures to latch.
    {
        std::lock_guard<std::mutex> lock(m_);
        for(auto& entry:sources_){
            auto& source=entry.second;
            if(source.hbPending>=0){
                if(source.hbShown>=0)source.hb[source.hbShown].state=Source::HB_RETIRE_AFTER_FRAME;
                source.hbShown=source.hbPending;source.hbPending=-1;source.hb[source.hbShown].state=Source::HB_SHOWN;
            }
            if(source.hbShown>=0&&source.hb[source.hbShown].image==EGL_NO_IMAGE_KHR)imports.push_back({entry.first,source.hbShown,source.hb[source.hbShown].hb});
            for(auto& g:source.hbGarbage)hbGarbage.push_back(g);
            for(auto* b:source.hbRelease)hbRelease.push_back(b);
            source.hbGarbage.clear();source.hbRelease.clear();
            if(source.external&&source.surfaceTexture&&source.updateTex){
                ExternalTex x;x.id=entry.first;x.surfaceTexture=source.surfaceTexture;x.updateTex=source.updateTex;x.getMatrix=source.getMatrix;x.matrixArray=source.matrixArray;
                externals.push_back(x);
            }
            const bool rgba=!source.external&&source.layer.rawFormat==RawPixelFormat::NONE&&!source.pendingRgba.empty();
            const bool raw=!source.external&&source.layer.rawFormat!=RawPixelFormat::NONE&&!source.pendingRaw.empty();
            if(!rgba&&!raw)continue;
            UploadJob job;job.id=entry.first;job.rgba=rgba;job.format=source.layer.rawFormat;
            job.width=rgba?source.pixelW:source.layer.rawWidth;job.height=rgba?source.pixelH:source.layer.rawHeight;
            job.texture=source.layer.textureId==source.oesTexture?0:source.layer.textureId;job.aux=source.layer.auxTextureId;
            job.allocW=source.allocW;job.allocH=source.allocH;job.allocFmt=source.allocFmt;
            if(rgba)job.data.swap(source.pendingRgba);else job.data.swap(source.pendingRaw);
            jobs.push_back(std::move(job));
        }
    }
    // 2. Without the lock: latch decoder frames (SurfaceTexture.updateTexImage) and upload. Sources can't be released
    // meanwhile: releaseSource stops this thread first.
    if(!externals.empty()){
        JNIEnv* env=nullptr;bool detach=false;
        if(vm_->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){if(vm_->AttachCurrentThread(&env,nullptr)!=JNI_OK)env=nullptr;else detach=true;}
        if(env){
            for(auto& x:externals){
                env->CallVoidMethod(x.surfaceTexture,x.updateTex);
                if(!env->ExceptionCheck()&&x.getMatrix&&x.matrixArray){
                    env->CallVoidMethod(x.surfaceTexture,x.getMatrix,x.matrixArray);
                    env->GetFloatArrayRegion(static_cast<jfloatArray>(x.matrixArray),0,16,x.matrix);
                    x.updated=!env->ExceptionCheck();
                }
                if(env->ExceptionCheck())env->ExceptionClear();
            }
            if(detach)vm_->DetachCurrentThread();
        }
    }
    for(auto& job:jobs){
        if(job.width<=0||job.height<=0||job.data.empty())continue;
        uploadJob(job);
    }
    for(auto& g:hbGarbage){if(g.second)glDeleteTextures(1,&g.second);if(g.first!=EGL_NO_IMAGE_KHR)egl_.destroyEglImage(g.first);}
    for(auto* b:hbRelease)AHardwareBuffer_release(b);
    for(auto& im:imports){
        im.image=egl_.createEglImageFromBuffer(im.hb);
        if(im.image==EGL_NO_IMAGE_KHR)continue;
        glGenTextures(1,&im.tex);glBindTexture(GL_TEXTURE_2D,im.tex);
        imageTargetTexture_(GL_TEXTURE_2D,static_cast<GLeglImageOES>(im.image));
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    }
    // 3. Under the lock again: store the textures, give the buffers back as spares, and snapshot the layers.
    {
        std::lock_guard<std::mutex> lock(m_);
        for(auto& x:externals){if(!x.updated)continue;auto it=sources_.find(x.id);if(it!=sources_.end())for(int i=0;i<16;++i)it->second.layer.texMatrix[i]=x.matrix[i];}
        for(auto& im:imports){
            auto it=sources_.find(im.id);
            if(it==sources_.end()||it->second.hb[im.slot].hb!=im.hb){if(im.tex)glDeleteTextures(1,&im.tex);if(im.image!=EGL_NO_IMAGE_KHR)egl_.destroyEglImage(im.image);continue;}
            if(im.image==EGL_NO_IMAGE_KHR){it->second.hbFallback=true;continue;} // the GPU can't sample it: upload path from now on
            it->second.hb[im.slot].image=im.image;it->second.hb[im.slot].tex=im.tex;
        }
        for(auto& entry:sources_){
            auto& source=entry.second;
            if(source.hbShown<0)continue;
            const auto& x=source.hb[source.hbShown];
            if(!x.tex)continue;
            source.layer.textureId=x.tex;source.layer.auxTextureId=0;
            source.layer.rawFormat=x.fmt==RawPixelFormat::NV12?RawPixelFormat::NV12_PACKED:x.fmt;
            source.layer.rawWidth=x.w;source.layer.rawHeight=x.h;
        }
        for(auto& job:jobs){
            auto it=sources_.find(job.id);
            if(it==sources_.end()){if(job.texture)glDeleteTextures(1,&job.texture);if(job.aux)glDeleteTextures(1,&job.aux);continue;}
            auto& source=it->second;
            source.layer.textureId=job.texture;source.layer.auxTextureId=job.aux;
            source.allocW=job.allocW;source.allocH=job.allocH;source.allocFmt=job.allocFmt;
            if(!job.rgba){
                source.rawUploads++;source.lastGlError=static_cast<int>(glGetError());source.hadContext=eglGetCurrentContext()!=EGL_NO_CONTEXT;
                job.data.clear();if(source.spareRaw.capacity()<job.data.capacity())source.spareRaw.swap(job.data);
            }
        }
        for(auto& entry:sources_){
            auto& source=entry.second;
            SourceLayer layer=source.layer;
            {auto sf=scaleFilters_.find(entry.first);layer.scaleFilter=sf==scaleFilters_.end()?0:sf->second;}
            {auto tf=transfers_.find(entry.first);layer.transfer=tf==transfers_.end()?0:tf->second;}
            auto colours=[&](const std::string& key){
                auto yc=yuvColors_.find(key);layer.yuvMatrix=yc==yuvColors_.end()?-1:yc->second.first;layer.yuvRange=yc==yuvColors_.end()?-1:yc->second.second;
                auto yr=yuvReported_.find(key);layer.yuvReported=yr==yuvReported_.end()?-1:yr->second;
                auto dc=decodedColors_.find(key);layer.decodedKind=dc==decodedColors_.end()?0:dc->second[0];
                layer.rangeReported=-1;
                if(dc!=decodedColors_.end()){if(dc->second[1]>=0)layer.yuvReported=dc->second[1];layer.rangeReported=dc->second[2];}
            };
            colours(entry.first);
            layer.decodeKey=entry.first;layer.frameStamp=source.rawFrames;
            {auto hl=hlgLook_.find(entry.first);if(hl!=hlgLook_.end()){layer.hlgMix=hl->second[0];layer.hlgGamut=hl->second[1];layer.hlgPeak=hl->second[2];}}
            if(blanks_.count(entry.first))layer.visible=false;
            {auto o=owners_.find(entry.first);layer.owner=o==owners_.end()?std::string():o->second;}
            // A reference shows the original's current frame (always in sync, no second decoder) with its own transform.
            if(auto al=aliases_.find(entry.first);al!=aliases_.end()){
                auto t=sources_.find(al->second);
                if(t==sources_.end()||t->first==entry.first)continue;
                const SourceLayer& o=t->second.layer;
                if(!o.textureId&&o.sceneRef.empty())continue;
                layer.textureId=o.textureId;layer.auxTextureId=o.auxTextureId;layer.external=o.external;layer.rawFormat=o.rawFormat;
                layer.rawWidth=o.rawWidth;layer.rawHeight=o.rawHeight;layer.sceneRef=o.sceneRef;
                layer.decodeKey=al->second;layer.frameStamp=t->second.rawFrames;
                for(int i=0;i<16;++i)layer.texMatrix[i]=o.texMatrix[i];
                {auto tf=transfers_.find(al->second);layer.transfer=tf==transfers_.end()?0:tf->second;}
                colours(al->second);
                if(t->second.pixelW>0&&t->second.pixelH>0&&layer.rawFormat==RawPixelFormat::NONE){layer.rawWidth=t->second.pixelW;layer.rawHeight=t->second.pixelH;}
                layers.push_back(layer);
                continue;
            }
            if(!layer.sceneRef.empty()){auto t=sceneTargets_.find(layer.sceneRef);if(t!=sceneTargets_.end()){source.pixelW=t->second.width;source.pixelH=t->second.height;}}
            if(source.pixelW>0&&source.pixelH>0){layer.rawWidth=layer.rawFormat==RawPixelFormat::NONE?source.pixelW:layer.rawWidth;layer.rawHeight=layer.rawFormat==RawPixelFormat::NONE?source.pixelH:layer.rawHeight;}
            layers.push_back(layer);
        }
        for(GLuint t:lutTexturesToDelete_)glDeleteTextures(1,&t);
        lutTexturesToDelete_.clear();
        for(auto&entry:luts_){
            auto&l=entry.second;
            if(l.pending.empty())continue;
            if(!l.texture)glGenTextures(1,&l.texture);
            glBindTexture(GL_TEXTURE_3D,l.texture);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
            glPixelStorei(GL_UNPACK_ALIGNMENT,1);
            glTexImage3D(GL_TEXTURE_3D,0,GL_RGB8,l.size,l.size,l.size,0,GL_RGB,GL_UNSIGNED_BYTE,l.pending.data());
            l.pending.clear();
            l.pending.shrink_to_fit();
        }
    }
    std::sort(layers.begin(),layers.end(),[](const auto& left,const auto& right){return left.z<right.z;});
    return layers;
}

namespace {
// What a layer's YUV is read with: the matrix / range used, how a decoder texture is read (uExtMode) and, for mode 2,
// what the GPU's own conversion assumed.
struct LayerColour{int matrix=0,full=0,extMode=0,srcMatrix=0,srcFull=0;};
LayerColour colourOf(const SourceLayer& l,bool yuvTarget){
    // Rec. 709 from 720p up, else 601: what Windows, macOS, OBS and Android's decoders assume when nothing is said.
    const int bySize=l.rawHeight>=720?1:0;
    LayerColour c;
    if(!l.external){
        c.matrix=l.yuvMatrix>=0?l.yuvMatrix:(l.yuvReported>=0?l.yuvReported:bySize);
        c.full=l.yuvRange>=0?l.yuvRange:(l.rangeReported>=0?l.rangeReported:0);
        return c;
    }
    if(l.decodedKind==1){
        const int rm=l.yuvReported>=0?l.yuvReported:bySize,rf=l.rangeReported>=0?l.rangeReported:0;
        c.matrix=l.yuvMatrix>=0?l.yuvMatrix:rm;c.full=l.yuvRange>=0?l.yuvRange:rf;
        if(c.matrix!=rm||c.full!=rf){c.extMode=yuvTarget?1:2;c.srcMatrix=rm;c.srcFull=rf;}
        return c;
    }
    if(l.decodedKind==2){
        // JPEG is Rec. 601, full range (JFIF); the software decoder hands over RGB made that way.
        c.matrix=l.yuvMatrix>=0?l.yuvMatrix:0;c.full=l.yuvRange>=0?l.yuvRange:1;
        if(c.matrix!=0||c.full!=1){c.extMode=2;c.srcMatrix=0;c.srcFull=1;}
    }
    return c;
}
}

void GlCompositor::drawLayers(const std::vector<SourceLayer>& layers,const std::string& owner,int canvasWidth,int canvasHeight,bool toOffscreen){
    glUseProgram(program_);
    glBindVertexArray(vao_);
    struct LutView{GLuint texture;int size;float domainMin[3],domainMax[3];};
    std::map<std::string,LutView> lutByKey;
    std::map<std::string,GLuint> sceneTextures;
    {
        std::lock_guard<std::mutex> lock(m_);
        for(auto&e:luts_){const auto&l=e.second;if(!l.texture)continue;LutView v{l.texture,l.size,{},{}};for(int c=0;c<3;++c){v.domainMin[c]=l.domainMin[c];v.domainMax[c]=l.domainMax[c];}lutByKey.emplace(e.first,v);}
        for(auto&e:sceneTargets_)if(e.second.texture)sceneTextures[e.first]=e.second.texture;
    }
    glUniform1f(u_.clipFlipY,toOffscreen?-1.f:1.f);
    glUniform1i(u_.decodePass,decodePass_?1:0);glUniform2f(u_.localSize,static_cast<float>(canvasWidth),static_cast<float>(canvasHeight));
    glUniform1i(u_.extTex,0);glUniform1i(u_.tex2d,1);glUniform1i(u_.rawTex,2);glUniform1i(u_.rawAux,3);
    glUniform2f(u_.canvas,static_cast<float>(canvasWidth),static_cast<float>(canvasHeight));
    glUniform1i(u_.out,drawOutput_);glUniform1f(u_.sw,sdrWhite_.load());glUniform1f(u_.hp,hdrPeak_.load());glUniform1f(u_.refWhite,refWhiteFor(drawOutput_));glUniform1f(u_.knee,kneeFor(drawOutput_));
    for(const auto& layer:layers){
        if(layer.owner!=owner||!layer.visible)continue;
        GLuint sceneTexture=0;
        if(!layer.sceneRef.empty()){auto t=sceneTextures.find(layer.sceneRef);if(t==sceneTextures.end())continue;sceneTexture=t->second;}
        else if(!layer.textureId)continue;
        glUniform1i(u_.premul,sceneTexture?1:0);
        glUniform1i(u_.ext,layer.external?1:0);glUniform1i(u_.raw,static_cast<int>(layer.rawFormat));
        glUniform4f(u_.rect,layer.x,layer.y,layer.w,layer.h);glUniform2f(u_.scale,layer.scaleX,layer.scaleY);
        glUniform2f(u_.pivot,layer.pivotX,layer.pivotY);glUniform1f(u_.rot,layer.rotation*0.01745329252f);glUniformMatrix4fv(u_.mat,1,GL_FALSE,layer.texMatrix);
        glUniform1f(u_.op,std::clamp(layer.opacity,0.f,1.f));glUniform4f(u_.crop,layer.cropL,layer.cropT,1-layer.cropR,1-layer.cropB);
        glUniform1i(u_.stageCount,(debugFlags_.load()&4)?0:layer.filterCount);
        {float su=0.f,sv=0.f;
         for(int i=0;i<layer.filterCount;++i)if(layer.filterTypes[i]==7){
             static const auto epoch=std::chrono::steady_clock::now();
             const double t=std::chrono::duration<double>(std::chrono::steady_clock::now()-epoch).count();
             const double tw=std::max(1.0,static_cast<double>(layer.rawWidth>0?layer.rawWidth:layer.w)),th=std::max(1.0,static_cast<double>(layer.rawHeight>0?layer.rawHeight:layer.h));
             su=static_cast<float>(std::fmod(layer.filterParams[i*kFilterStageFloats]*t/tw,1.0));sv=static_cast<float>(std::fmod(layer.filterParams[i*kFilterStageFloats+1]*t/th,1.0));}
         glUniform2f(u_.scroll,su,sv);}
        if(layer.filterCount>0){
            int types[kMaxFilterStages];
            float lutMin[kMaxLutSlots*3]={},lutMax[kMaxLutSlots*3]={},lutSize[kMaxLutSlots]={2.f,2.f};
            for(int i=0;i<layer.filterCount;++i){
                types[i]=layer.filterTypes[i];
                if(types[i]!=kStageLut)continue;
                const int slot=static_cast<int>(layer.filterParams[i*kFilterStageFloats+1]+0.5f);
                auto it=(slot>=0&&slot<kMaxLutSlots)?lutByKey.find(lutMapKey(layer.id,slot)):lutByKey.end();
                if(it==lutByKey.end()){types[i]=0;continue;} // LUT not loaded yet: pass through
                const LutView&l=it->second;
                glActiveTexture(GL_TEXTURE4+slot);glBindTexture(GL_TEXTURE_3D,l.texture);
                for(int c=0;c<3;++c){lutMin[slot*3+c]=l.domainMin[c];lutMax[slot*3+c]=l.domainMax[c];}
                lutSize[slot]=static_cast<float>(l.size);
            }
            glUniform1i(u_.lut0,4);glUniform1i(u_.lut1,5);
            glUniform3fv(u_.lutMin,kMaxLutSlots,lutMin);glUniform3fv(u_.lutMax,kMaxLutSlots,lutMax);glUniform1fv(u_.lutSize,kMaxLutSlots,lutSize);
            glUniform1iv(u_.stageType,layer.filterCount,types);glUniform4fv(u_.stageParams,layer.filterCount*4,layer.filterParams);
        }
        {const float tw=layer.rawWidth>0?static_cast<float>(layer.rawWidth):std::max(1.f,layer.w),th=layer.rawHeight>0?static_cast<float>(layer.rawHeight):std::max(1.f,layer.h);glUniform2f(u_.texel,1.f/tw,1.f/th);}
        const LayerColour colour=colourOf(layer,yuvTarget_);
        const int dbg=debugFlags_.load();glUniform1i(u_.fh,layer.flipH);glUniform1i(u_.sf,(dbg&1)?2:layer.scaleFilter);glUniform1i(u_.tf,(dbg&2)&&!decodePass_?drawOutput_:(layer.sceneRef.empty()?layer.transfer:sceneEncoding_));
        glUniform1i(u_.ym,colour.matrix);glUniform1i(u_.yf,colour.full);glUniform1i(u_.extMode,layer.external?colour.extMode:0);glUniform1i(u_.srcMatrix,colour.srcMatrix);glUniform1i(u_.srcFull,colour.srcFull);
        glUniform1i(u_.fv,layer.flipV);glUniform2f(u_.rawSize,static_cast<float>(layer.rawWidth),static_cast<float>(layer.rawHeight));
        glUniform1i(u_.hlgLook,(layer.hlgMix<0.999f||layer.hlgGamut<0.999f||std::abs(layer.hlgPeak-1000.f)>=1.f)?1:0);glUniform1f(u_.hlgMix,layer.hlgMix);glUniform1f(u_.hlgGamut,layer.hlgGamut);glUniform1f(u_.hlgPeak,layer.hlgPeak);
        if(layer.external){
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_EXTERNAL_OES,layer.textureId);
            if(yuvTarget_&&colour.extMode==1){glActiveTexture(GL_TEXTURE6);glBindTexture(GL_TEXTURE_EXTERNAL_OES,layer.textureId);}
        }
        else if(layer.rawFormat==RawPixelFormat::NONE){glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,sceneTexture?sceneTexture:layer.textureId);}
        else{glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,layer.textureId);if(layer.rawFormat==RawPixelFormat::NV12){glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,layer.auxTextureId);}}
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);
    }
    glBindVertexArray(0);
}

// Draws a whole canvas texture (rendered top row first) scaled into the current target, converting its signal when
// the target's differs: the encoder gets the canvas as is, the SDR preview a tone-mapped copy.
void GlCompositor::drawTextureFull(GLuint texture,int texW,int texH,int dstW,int dstH,int signal,int output){
    if(signal==output&&copyProgram_){
        glUseProgram(copyProgram_);glBindVertexArray(vao_);glDisable(GL_BLEND);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,texture);glUniform1i(copyTexLoc_,1);
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);glBindVertexArray(0);
        return;
    }
    glUseProgram(program_);
    glBindVertexArray(vao_);
    glDisable(GL_BLEND);
    static const float flip[16]={1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,1,0,1};
    glUniform1f(u_.clipFlipY,1.f);glUniform1i(u_.premul,0);glUniform1i(u_.ext,0);glUniform1i(u_.raw,0);glUniform1i(u_.extMode,0);
    glUniform1i(u_.extTex,0);glUniform1i(u_.tex2d,1);glUniform1i(u_.rawTex,2);glUniform1i(u_.rawAux,3);
    glUniform2f(u_.canvas,static_cast<float>(dstW),static_cast<float>(dstH));glUniform4f(u_.rect,0,0,static_cast<float>(dstW),static_cast<float>(dstH));
    glUniform2f(u_.scale,1,1);glUniform2f(u_.pivot,0,0);glUniform1f(u_.rot,0);glUniformMatrix4fv(u_.mat,1,GL_FALSE,flip);
    glUniform1f(u_.op,1);glUniform4f(u_.crop,0,0,1,1);glUniform1i(u_.fh,0);glUniform1i(u_.fv,0);glUniform1i(u_.stageCount,0);
    glUniform2f(u_.texel,1.f/std::max(1,texW),1.f/std::max(1,texH));glUniform2f(u_.rawSize,static_cast<float>(texW),static_cast<float>(texH));
    glUniform1i(u_.sf,0);glUniform1i(u_.tf,signal);glUniform1i(u_.out,output);glUniform1i(u_.decodePass,0);glUniform2f(u_.scroll,0.f,0.f);glUniform1i(u_.hlgLook,0);
    glUniform1f(u_.sw,sdrWhite_.load());glUniform1f(u_.hp,hdrPeak_.load());glUniform1f(u_.refWhite,refWhiteFor(output));glUniform1f(u_.knee,kneeFor(output));
    glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,texture);
    glDrawArrays(GL_TRIANGLE_STRIP,0,4);
    glBindVertexArray(0);
    glEnable(GL_BLEND);
}

// Renders every nested scene/group canvas reachable from the program canvas, dependencies first.
void GlCompositor::renderSceneTargets(const std::vector<SourceLayer>& layers){
    std::map<std::string,std::pair<int,int>> sizes;
    {
        std::lock_guard<std::mutex> lock(m_);
        for(auto& d:sceneTargetsToDelete_){if(d.first)glDeleteFramebuffers(1,&d.first);if(d.second)glDeleteTextures(1,&d.second);}
        sceneTargetsToDelete_.clear();
        for(auto& e:sceneTargets_){
            auto& t=e.second;
            if(t.width<=0||t.height<=0)continue;
            // HDR output keeps nested scenes in its PQ / HLG signal, in half floats (8 bits would band).
            const bool hdr=sceneEncoding_!=0;
            if(!t.fbo||t.allocW!=t.width||t.allocH!=t.height||t.allocHdr!=hdr){
                if(!t.texture)glGenTextures(1,&t.texture);
                glBindTexture(GL_TEXTURE_2D,t.texture);
                glTexImage2D(GL_TEXTURE_2D,0,hdr?GL_RGBA16F:GL_RGBA8,t.width,t.height,0,GL_RGBA,hdr?GL_HALF_FLOAT:GL_UNSIGNED_BYTE,nullptr);
                t.allocHdr=hdr;
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                if(!t.fbo)glGenFramebuffers(1,&t.fbo);
                glBindFramebuffer(GL_FRAMEBUFFER,t.fbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,t.texture,0);
                t.allocW=t.width;t.allocH=t.height;
            }
            sizes[e.first]={t.width,t.height};
        }
    }
    if(sizes.empty())return;
    std::map<std::string,GLuint> fbos;
    {std::lock_guard<std::mutex> lock(m_);for(auto&e:sceneTargets_)if(e.second.fbo)fbos[e.first]=e.second.fbo;}
    std::map<std::string,int> state; // 1 = rendering (cycle guard), 2 = done
    std::function<void(const std::string&,int)> render=[&](const std::string& key,int depth){
        if(depth>8||state[key]!=0||!sizes.count(key)||!fbos.count(key))return;
        state[key]=1;
        for(const auto& l:layers)if(l.owner==key&&l.visible&&!l.sceneRef.empty())render(l.sceneRef,depth+1);
        const auto size=sizes[key];
        glBindFramebuffer(GL_FRAMEBUFFER,fbos[key]);
        glViewport(0,0,size.first,size.second);
        glClearColor(0,0,0,0);
        glClear(GL_COLOR_BUFFER_BIT);
        drawOutput_=sceneEncoding_;
        drawLayers(layers,key,size.first,size.second,true);
        state[key]=2;
    };
    for(const auto& l:layers)if(l.owner.empty()&&l.visible&&!l.sceneRef.empty())render(l.sceneRef,0);
    glBindFramebuffer(GL_FRAMEBUFFER,0);
}

// The preview and the Properties preview never wait for the screen: with swap interval 0 their buffer queue drops a
// frame the UI hasn't shown yet instead of blocking the render thread (and with it the encoder) until it does.
static void noVsyncWait(EGLDisplay display,ANativeWindow* window,ANativeWindow*& lastSet){
    if(window==lastSet)return;
    if(eglSwapInterval(display,0))lastSet=window;
}

void GlCompositor::renderTo(EGLSurface target,int width,int height,int canvasWidth,int canvasHeight,const std::vector<SourceLayer>& layers,int output){
    if(target==EGL_NO_SURFACE||width<=0||height<=0)return;
    // Fails when the UI thread destroyed this surface after the frame started; skip it this frame.
    if(!eglMakeCurrent(egl_.display(),target,target,egl_.context()))return;
    glBindFramebuffer(GL_FRAMEBUFFER,0);
    glViewport(0,0,width,height);
    glClearColor(0,0,0,1);
    glClear(GL_COLOR_BUFFER_BIT);
    drawOutput_=output;
    drawLayers(layers,std::string(),canvasWidth,canvasHeight,false);
    glBindVertexArray(vao_);
    renderTransitionOverlay(width,height);
    glBindVertexArray(0);
    eglSwapBuffers(egl_.display(),target);
}

// Draws one source, filters and crop included, letterboxed into the solo surface; position, rotation and opacity
// on the canvas are ignored so the preview shows the source itself.
void GlCompositor::renderSolo(const HeldWindow& solo,const std::string& id,const std::vector<SourceLayer>& layers){
    if(solo.surface==EGL_NO_SURFACE)return;
    const int sw=solo.width(),sh=solo.height();
    if(sw<=0||sh<=0)return;
    std::vector<SourceLayer> one;
    for(const auto& l:layers)if(l.id==id){one.push_back(l);break;}
    if(!eglMakeCurrent(egl_.display(),solo.surface,solo.surface,egl_.context()))return;
    static ANativeWindow* soloInterval=nullptr;
    noVsyncWait(egl_.display(),solo.window,soloInterval);
    glBindFramebuffer(GL_FRAMEBUFFER,0);
    glViewport(0,0,sw,sh);
    glClearColor(0.08f,0.08f,0.1f,1);
    glClear(GL_COLOR_BUFFER_BIT);
    if(!one.empty()){
        SourceLayer& l=one.front();
        // The layer rect is already the displayed (cropped) size; crop only picks the texture area.
        const float cw=std::max(1.f,std::abs(l.w*l.scaleX)),ch=std::max(1.f,std::abs(l.h*l.scaleY));
        const float fit=std::min(sw/cw,sh/ch);
        const float dw=cw*fit,dh=ch*fit;
        l.w=dw;l.h=dh;
        l.scaleX=1;l.scaleY=1;l.rotation=0;l.pivotX=0;l.pivotY=0;l.opacity=1;l.visible=true;l.owner.clear();
        l.x=(sw-dw)/2.f;l.y=(sh-dh)/2.f;
        drawOutput_=0;
        drawLayers(one,std::string(),sw,sh,false);
    }
    eglSwapBuffers(egl_.display(),solo.surface);
}

std::string GlCompositor::describeRender(){
    char b[320];
    const int fps=std::clamp(fps_.load(),1,240);
    snprintf(b,sizeof b,"render %.1f ms of %.1f (latch+upload %.1f, nested scenes %.1f, canvas %.1f, encoder %.1f, preview %.1f, properties preview %.1f), %llu late frames",
        renderMs_.load(),1000.f/fps,tPrepare_.load(),tScenes_.load(),tCanvas_.load(),tEncoder_.load(),tPreview_.load(),tSolo_.load(),static_cast<unsigned long long>(dropped_.load()));
    char g[200];
    snprintf(g,sizeof g,"; GPU: frame prep %.1f ms, canvas %.1f ms, encoder copy %.1f ms; handing frames to the encoder blocked %.1f ms",gpuPrep_.load(),gpuCanvas_.load(),gpuEncoder_.load(),tEncSwap_.load());
    std::lock_guard<std::mutex> lock(m_);
    return std::string(b)+g+"; preview window "+previewSurfaceInfo_;
}

void GlCompositor::loop(){
    JNIEnv* env=nullptr;
    if(vm_->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
    // Above normal app threads (Android's "urgent display"), so UI work and decoders don't push frames past their slot.
    setpriority(PRIO_PROCESS,static_cast<id_t>(syscall(SYS_gettid)),-8);
    // Encoder frames are stamped with their slot on the frame clock (CLOCK_MONOTONIC, as the audio), not with the time
    // the swap happened, so the stream's frame times stay even when one frame took longer to draw.
    auto presentationTime=reinterpret_cast<EGLBoolean(*)(EGLDisplay,EGLSurface,EGLnsecsANDROID)>(eglGetProcAddress("eglPresentationTimeANDROID"));
    ANativeWindow* previewInterval=nullptr;
    auto next=std::chrono::steady_clock::now();
#if __ANDROID_API__ >= 31
    APerformanceHintSession* performanceSession=nullptr;
    if(APerformanceHintManager* manager=APerformanceHint_getManager()){
        const int32_t threadId=static_cast<int32_t>(syscall(SYS_gettid));
        const int64_t targetDuration=1000000000LL/std::clamp(fps_.load(),1,240);
        performanceSession=APerformanceHint_createSession(manager,&threadId,1,targetDuration);
    }
    int targetFrameRate=std::clamp(fps_.load(),1,240);
#endif
    auto smooth=[](std::atomic<float>& a,float v){a.store(a.load()*0.9f+v*0.1f);};
    auto ms=[](std::chrono::steady_clock::time_point a,std::chrono::steady_clock::time_point b){return std::chrono::duration<float,std::milli>(b-a).count();};
    while(running_){
        const auto frameStart=std::chrono::steady_clock::now();
        const int64_t slotNs=std::chrono::duration_cast<std::chrono::nanoseconds>(next.time_since_epoch()).count();
        const int canvasWidth=static_cast<int>(canvasW_.load());
        const int canvasHeight=static_cast<int>(canvasH_.load());
        {
            // The UI thread replaces these windows under m_ (Properties opening/closing, preview resize): take them
            // once per frame, with a reference, instead of reading the members while drawing.
            std::unique_ptr<HeldWindow> preview,encoder,solo;std::string soloId;int previewOut=0;
            {
                std::lock_guard<std::mutex> lock(m_);
                // HDR preview switched on / off, or the output's signal changed: the preview window is made again in the
                // new format (released from this thread first, or EGL can't connect to the window a second time).
                const int wantPreview=previewTransferWanted();
                if(previewWin_&&previewSurfTransfer_!=wantPreview){
                    eglMakeCurrent(egl_.display(),egl_.surface(),egl_.surface(),egl_.context());
                    if(preview_!=EGL_NO_SURFACE)eglDestroySurface(egl_.display(),preview_);
                    preview_=egl_.createWindowSurface(previewWin_,wantPreview,wantPreview!=0,&previewSurfaceInfo_);
                    previewSurfTransfer_=wantPreview;previewSurfPeak_=0.f;previewInterval=nullptr;
                }
                if(preview_!=EGL_NO_SURFACE&&previewSurfTransfer_!=0&&previewSurfPeak_!=hdrPeak_.load()){previewSurfPeak_=hdrPeak_.load();egl_.setHdrMetadata(preview_,previewSurfPeak_);}
                previewOut=previewSurfTransfer_;
                preview=std::make_unique<HeldWindow>(preview_,previewWin_);
                encoder=std::make_unique<HeldWindow>(encoder_,encoderWin_);
                solo=std::make_unique<HeldWindow>(solo_,soloWin_);
                soloId=soloId_;
            }
            // Latch source frames, upload textures and draw nested scenes once per frame, then present.
            const EGLSurface work=encoder->surface!=EGL_NO_SURFACE?encoder->surface:(preview->surface!=EGL_NO_SURFACE?preview->surface:egl_.surface());
            if(!eglMakeCurrent(egl_.display(),work,work,egl_.context()))eglMakeCurrent(egl_.display(),egl_.surface(),egl_.surface(),egl_.context());
            glDisable(GL_SCISSOR_TEST);
            glEnable(GL_BLEND);
            // Straight-alpha sources; destination alpha accumulates correctly for offscreen scene canvases.
            glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
            if(!timerInit_){
                timerInit_=true;
                const char* ext=reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
                getQueryUi64_=reinterpret_cast<void(*)(GLuint,GLenum,GLuint64*)>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
                timerOk_=ext&&strstr(ext,"GL_EXT_disjoint_timer_query")&&getQueryUi64_;
                if(timerOk_){glGenQueries(4,&timerQ_[0][0]);glGenQueries(2,prepQ_);}
            }
            const bool prepTiming=timerOk_&&!prepPending_[prepSet_];
            if(prepTiming)glBeginQuery(0x88BF,prepQ_[prepSet_]);
            std::vector<SourceLayer> layers=prepareFrame();
            decodeRawSources(layers);
            const auto tPrepared=std::chrono::steady_clock::now();
            smooth(tPrepare_,ms(frameStart,tPrepared));
            // The encoder gets the output's signal. The preview is SDR (HDR tone-mapped, as OBS's preview on an SDR screen),
            // or with HDR preview on an HDR screen the output's signal too, so it shows the stream's own pixels.
            const bool streaming=encoder->surface!=EGL_NO_SURFACE||benchCanvas_.load();
            const int output=streaming?outputTransfer_.load():previewOut;
            sceneEncoding_=output;
            renderSceneTargets(layers);
            if(prepTiming){glEndQuery(0x88BF);prepPending_[prepSet_]=true;}
            {const int o=prepSet_^1;
             if(timerOk_&&prepPending_[o]){GLuint av=0;glGetQueryObjectuiv(prepQ_[o],0x8867,&av);
                 if(av){GLuint64 v=0;getQueryUi64_(prepQ_[o],0x8866,&v);GLint dj=0;glGetIntegerv(0x8FBB,&dj);if(!dj)smooth(gpuPrep_,static_cast<float>(v)/1e6f);prepPending_[o]=false;}}
             prepSet_=o;}
            const auto tScenesDone=std::chrono::steady_clock::now();
            smooth(tScenes_,ms(tPrepared,tScenesDone));
            auto tPreviewStart=tScenesDone;
            if(streaming&&canvasWidth>0&&canvasHeight>0){
                // Streaming: the canvas is drawn once, in the output's signal, and the encoder and the preview are scaled
                // copies of it. Every source used to be drawn (and its 4K frame sampled) twice per frame, once per target.
                const bool deep=output!=0||outputTenBit_.load();
                if(!canvasFbo_||canvasAllocW_!=canvasWidth||canvasAllocH_!=canvasHeight||canvasAllocHdr_!=deep){
                    if(canvasTex_){glDeleteTextures(1,&canvasTex_);canvasTex_=0;}
                    glGenTextures(1,&canvasTex_);glBindTexture(GL_TEXTURE_2D,canvasTex_);
                    // 10-bit for HDR / 10-bit output: PQ and HLG are made for 10 bits and the stream is 10-bit, so half floats
                    // (twice the memory traffic of the frame's biggest pass) bought nothing.
                    glTexStorage2D(GL_TEXTURE_2D,1,deep?GL_RGB10_A2:GL_RGBA8,canvasWidth,canvasHeight);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                    if(!canvasFbo_)glGenFramebuffers(1,&canvasFbo_);
                    glBindFramebuffer(GL_FRAMEBUFFER,canvasFbo_);
                    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,canvasTex_,0);
                    canvasAllocW_=canvasWidth;canvasAllocH_=canvasHeight;canvasAllocHdr_=deep;
                }
                const int ts=timerSet_;const bool timing=timerOk_&&!timerPending_[ts];
                glBindFramebuffer(GL_FRAMEBUFFER,canvasFbo_);
                glViewport(0,0,canvasWidth,canvasHeight);
                if(timing)glBeginQuery(0x88BF/*GL_TIME_ELAPSED_EXT*/,timerQ_[ts][0]);
                glClearColor(0,0,0,1);
                glClear(GL_COLOR_BUFFER_BIT);
                drawOutput_=output;
                drawLayers(layers,std::string(),canvasWidth,canvasHeight,true);
                glBindVertexArray(vao_);
                renderTransitionOverlay(canvasWidth,canvasHeight);
                glBindVertexArray(0);
                if(timing){glEndQuery(0x88BF);timerPending_[ts]=true;timerEnc_[ts]=false;}
                {std::string path;{std::lock_guard<std::mutex> lock(m_);path.swap(grabPath_);}
                 if(!path.empty()){
                     const size_t n=static_cast<size_t>(canvasWidth)*canvasHeight;std::vector<uint8_t> px(n*4);
                     if(canvasAllocHdr_){std::vector<uint32_t> raw(n);glReadPixels(0,0,canvasWidth,canvasHeight,GL_RGBA,GL_UNSIGNED_INT_2_10_10_10_REV,raw.data());
                         for(size_t i=0;i<n;++i){uint32_t v=raw[i];px[i*4]=static_cast<uint8_t>(((v)&1023)>>2);px[i*4+1]=static_cast<uint8_t>(((v>>10)&1023)>>2);px[i*4+2]=static_cast<uint8_t>(((v>>20)&1023)>>2);px[i*4+3]=255;}}
                     else glReadPixels(0,0,canvasWidth,canvasHeight,GL_RGBA,GL_UNSIGNED_BYTE,px.data());
                     std::thread([path,data=std::move(px)]{FILE* f=fopen(path.c_str(),"wb");if(f){fwrite(data.data(),1,data.size(),f);fclose(f);}}).detach();
                 }}
                glBindFramebuffer(GL_FRAMEBUFFER,0);
                const auto tCanvasDone=std::chrono::steady_clock::now();
                smooth(tCanvas_,ms(tScenesDone,tCanvasDone));
                const int ew=encoder->width(),eh=encoder->height();
                if(ew>0&&eh>0&&eglMakeCurrent(egl_.display(),encoder->surface,encoder->surface,egl_.context())){
                    glViewport(0,0,ew,eh);
                    if(timing)glBeginQuery(0x88BF,timerQ_[ts][1]);
                    drawTextureFull(canvasTex_,canvasWidth,canvasHeight,ew,eh,output,output);
                    if(timing){glEndQuery(0x88BF);timerEnc_[ts]=true;}
                    if(presentationTime)presentationTime(egl_.display(),encoder->surface,static_cast<EGLnsecsANDROID>(slotNs));
                    const auto tSwap=std::chrono::steady_clock::now();
                    eglSwapBuffers(egl_.display(),encoder->surface);
                    smooth(tEncSwap_,ms(tSwap,std::chrono::steady_clock::now()));
                    encoderFrames_++;
                }
                // Last frame's GPU timings, if the GPU has finished them (never waits for them).
                {const int other=ts^1;
                 if(timerOk_&&timerPending_[other]){
                     GLuint avail=0;glGetQueryObjectuiv(timerQ_[other][timerEnc_[other]?1:0],0x8867/*GL_QUERY_RESULT_AVAILABLE*/,&avail);
                     if(avail){GLuint64 a=0,b=0;getQueryUi64_(timerQ_[other][0],0x8866/*GL_QUERY_RESULT*/,&a);if(timerEnc_[other])getQueryUi64_(timerQ_[other][1],0x8866,&b);
                         GLint disjoint=0;glGetIntegerv(0x8FBB/*GL_GPU_DISJOINT_EXT*/,&disjoint);
                         if(!disjoint){smooth(gpuCanvas_,static_cast<float>(a)/1e6f);smooth(gpuEncoder_,static_cast<float>(b)/1e6f);}
                         timerPending_[other]=false;}
                 }
                 timerSet_=other;}
                tPreviewStart=std::chrono::steady_clock::now();
                smooth(tEncoder_,ms(tCanvasDone,tPreviewStart));
                const int pw=preview->width(),ph=preview->height();
                if(preview->surface!=EGL_NO_SURFACE&&pw>0&&ph>0&&eglMakeCurrent(egl_.display(),preview->surface,preview->surface,egl_.context())){
                    noVsyncWait(egl_.display(),preview->window,previewInterval);
                    glViewport(0,0,pw,ph);
                    drawTextureFull(canvasTex_,canvasWidth,canvasHeight,pw,ph,output,previewOut);
                    eglSwapBuffers(egl_.display(),preview->surface);
                }
            }else if(preview->surface!=EGL_NO_SURFACE){
                smooth(tCanvas_,0.f);smooth(tEncoder_,0.f);
                if(eglMakeCurrent(egl_.display(),preview->surface,preview->surface,egl_.context()))noVsyncWait(egl_.display(),preview->window,previewInterval);
                renderTo(preview->surface,preview->width(),preview->height(),canvasWidth,canvasHeight,layers,previewOut);
            }
            const auto tPreviewDone=std::chrono::steady_clock::now();
            smooth(tPreview_,ms(tPreviewStart,tPreviewDone));
            renderSolo(*solo,soloId,layers);
            smooth(tSolo_,ms(tPreviewDone,std::chrono::steady_clock::now()));
            if(zeroCopy_.load()){
                std::lock_guard<std::mutex> lock(m_);
                for(auto& entry:sources_)for(auto& x:entry.second.hb)if(x.state==Source::HB_RETIRE_AFTER_FRAME){
                    x.sync=createSync_(egl_.display(),EGL_SYNC_FENCE_KHR,nullptr);
                    x.state=x.sync!=EGL_NO_SYNC_KHR?Source::HB_RETIRING:Source::HB_FREE;
                }
            }
        }
        const auto frameEnd=std::chrono::steady_clock::now();
        renderMs_.store(std::chrono::duration<float,std::milli>(frameEnd-frameStart).count());
        frames_++;
        const int frameRate=std::clamp(fps_.load(),1,240);
#if __ANDROID_API__ >= 31
        if(performanceSession){
            if(frameRate!=targetFrameRate){
                targetFrameRate=frameRate;
                APerformanceHint_updateTargetWorkDuration(performanceSession,1000000000LL/frameRate);
            }
            const auto workNs=std::chrono::duration_cast<std::chrono::nanoseconds>(frameEnd-frameStart).count();
            if(workNs>0)APerformanceHint_reportActualWorkDuration(performanceSession,workNs);
        }
#endif
        next+=std::chrono::nanoseconds(1000000000LL/frameRate);
        if(next<frameEnd){dropped_++;next=frameEnd;}
        std::this_thread::sleep_until(next);
    }
#if __ANDROID_API__ >= 31
    if(performanceSession)APerformanceHint_closeSession(performanceSession);
#endif
    if(canvasFbo_){glDeleteFramebuffers(1,&canvasFbo_);canvasFbo_=0;}
    if(canvasTex_){glDeleteTextures(1,&canvasTex_);canvasTex_=0;}
    canvasAllocW_=canvasAllocH_=0;
    // Leave the context free for the next thread that needs it (source creation, the next render thread).
    eglMakeCurrent(egl_.display(),EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    vm_->DetachCurrentThread();
}
bool GlCompositor::start(){std::lock_guard<std::recursive_mutex>cl(ctxMutex_);if(running_.exchange(true))return true;thread_=std::thread(&GlCompositor::loop,this);return true;}
void GlCompositor::stop(){std::lock_guard<std::recursive_mutex>cl(ctxMutex_);if(!running_.exchange(false))return;if(thread_.joinable())thread_.join();}
// Raw USB frames (NV12, YUYV, P010) are converted to RGB once per new frame, at their own size, into a 10-bit RGB texture;
// the canvas, the preview and the SDR to HDR pass then read an ordinary texture with the GPU's filtering. Read raw, every
// output pixel unpacked and colour-converted the frame for each tap (16 to enlarge with bicubic, 4 more to sharpen), at
// 60 fps: the 1440p camera alone cost more GPU time than the rest of the 4K canvas.
void GlCompositor::decodeRawSources(std::vector<SourceLayer>& layers){
    std::vector<std::string> live;
    bool drew=false;
    for(auto& l:layers){
        if(l.rawFormat==RawPixelFormat::NONE||l.external||!l.textureId||l.rawWidth<=0||l.rawHeight<=0||!l.sceneRef.empty())continue;
        const std::string key=l.decodeKey.empty()?l.id:l.decodeKey;
        if(std::find(live.begin(),live.end(),key)==live.end())live.push_back(key);
        auto& t=decoded_[key];
        if(!t.fbo||t.w!=l.rawWidth||t.h!=l.rawHeight){
            if(t.fbo){glDeleteFramebuffers(1,&t.fbo);glDeleteTextures(1,&t.tex);}
            glGenTextures(1,&t.tex);glBindTexture(GL_TEXTURE_2D,t.tex);
            glTexStorage2D(GL_TEXTURE_2D,1,GL_RGB10_A2,l.rawWidth,l.rawHeight);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glGenFramebuffers(1,&t.fbo);glBindFramebuffer(GL_FRAMEBUFFER,t.fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,t.tex,0);
            t.w=l.rawWidth;t.h=l.rawHeight;t.stamp=~0ull;
        }
        const LayerColour c=colourOf(l,yuvTarget_);
        const int sig=c.matrix*2+c.full;
        if(t.stamp!=l.frameStamp||t.srcTex!=l.textureId||t.colourSig!=sig){
            SourceLayer one=l;
            one.owner.clear();one.visible=true;one.x=0;one.y=0;one.w=static_cast<float>(t.w);one.h=static_cast<float>(t.h);one.scaleX=1;one.scaleY=1;
            one.rotation=0;one.pivotX=0;one.pivotY=0;one.opacity=1;one.cropL=one.cropT=one.cropR=one.cropB=0;one.flipH=false;one.flipV=false;
            one.filterCount=0;
            glBindFramebuffer(GL_FRAMEBUFFER,t.fbo);glViewport(0,0,t.w,t.h);glDisable(GL_BLEND);
            decodePass_=true;drawOutput_=0;
            drawLayers({one},std::string(),t.w,t.h,false);
            decodePass_=false;
            t.stamp=l.frameStamp;t.srcTex=l.textureId;t.colourSig=sig;drew=true;
        }
        l.textureId=t.tex;l.auxTextureId=0;l.rawFormat=RawPixelFormat::NONE;
    }
    for(auto it=decoded_.begin();it!=decoded_.end();){
        if(std::find(live.begin(),live.end(),it->first)==live.end()){glDeleteFramebuffers(1,&it->second.fbo);glDeleteTextures(1,&it->second.tex);it=decoded_.erase(it);}else ++it;
    }
    if(drew){glBindFramebuffer(GL_FRAMEBUFFER,0);glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);}
}

void GlCompositor::shutdown(){std::lock_guard<std::recursive_mutex>cl(ctxMutex_);stop();if(egl_.display()!=EGL_NO_DISPLAY){eglMakeCurrent(egl_.display(),egl_.surface(),egl_.surface(),egl_.context());for(auto&t:decoded_){glDeleteFramebuffers(1,&t.second.fbo);glDeleteTextures(1,&t.second.tex);}decoded_.clear();for(auto&kv:sources_){auto&s=kv.second;for(const auto& x:s.hb)if(x.tex&&x.tex==s.layer.textureId)s.layer.textureId=0;releaseHardwareBuffers(s);if(s.layer.textureId)glDeleteTextures(1,&s.layer.textureId);if(s.layer.auxTextureId)glDeleteTextures(1,&s.layer.auxTextureId);}sources_.clear();for(auto&l:luts_)if(l.second.texture)glDeleteTextures(1,&l.second.texture);luts_.clear();for(auto&t:sceneTargets_){if(t.second.fbo)glDeleteFramebuffers(1,&t.second.fbo);if(t.second.texture)glDeleteTextures(1,&t.second.texture);}sceneTargets_.clear();for(GLuint t:lutTexturesToDelete_)glDeleteTextures(1,&t);lutTexturesToDelete_.clear();if(program_)glDeleteProgram(program_),program_=0;if(overlayProgram_)glDeleteProgram(overlayProgram_),overlayProgram_=0;if(vbo_)glDeleteBuffers(1,&vbo_),vbo_=0;if(vao_)glDeleteVertexArrays(1,&vao_),vao_=0;}destroyWindow(preview_,previewWin_);destroyWindow(encoder_,encoderWin_);destroyWindow(solo_,soloWin_);egl_.shutdown();}
}
