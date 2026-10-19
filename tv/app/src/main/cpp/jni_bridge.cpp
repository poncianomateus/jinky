// JNI surface for com.tclstream.tv.NativeReceiver.
#include <jni.h>

#include "receiver.h"

using tcltv::Receiver;

namespace {
Receiver* self(jlong handle) { return reinterpret_cast<Receiver*>(handle); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_tclstream_tv_NativeReceiver_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Receiver());
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete self(h);
}

JNIEXPORT jstring JNICALL Java_com_tclstream_tv_NativeReceiver_nativeStart(JNIEnv* env, jclass, jlong h,
                                                                           jint port) {
    if (self(h)->start(uint16_t(port))) return nullptr;
    return env->NewStringUTF(self(h)->error().c_str());
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeStop(JNIEnv*, jclass, jlong h) {
    self(h)->stop();
}

// meta: [frameId, isKeyframe(0/1), captureTimeUs]
JNIEXPORT jint JNICALL Java_com_tclstream_tv_NativeReceiver_nativeReadFrame(JNIEnv* env, jclass, jlong h,
                                                                            jobject buffer, jint timeout_ms,
                                                                            jlongArray meta) {
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong cap = env->GetDirectBufferCapacity(buffer);
    if (!dst || cap <= 0) return 0;
    Receiver::FrameMeta m;
    const int n = self(h)->read_frame(dst, size_t(cap), timeout_ms, m);
    if (n > 0) {
        jlong out[3] = {jlong(m.frame_id), m.keyframe ? 1 : 0, jlong(m.capture_time_us)};
        env->SetLongArrayRegion(meta, 0, 3, out);
    }
    return n;
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeRequestKeyframe(JNIEnv*, jclass, jlong h) {
    self(h)->request_keyframe();
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeReportPresented(JNIEnv*, jclass, jlong h,
                                                                                  jlong count) {
    self(h)->report_presented(uint64_t(count));
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeReportLatency(JNIEnv*, jclass, jlong h,
                                                                                jint us) {
    self(h)->report_latency_us(uint32_t(us));
}

// out: [synced(0/1), offsetUs, rttUs]
JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeClock(JNIEnv* env, jclass, jlong h,
                                                                        jlongArray out) {
    jlong v[3] = {self(h)->clock_synced() ? 1 : 0, self(h)->clock_offset_us(), self(h)->rtt_us()};
    env->SetLongArrayRegion(out, 0, 3, v);
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeReadAudio(JNIEnv* env, jclass, jlong h,
                                                                            jshortArray dst, jint frames) {
    jshort* p = env->GetShortArrayElements(dst, nullptr);
    self(h)->read_audio(reinterpret_cast<int16_t*>(p), size_t(frames));
    env->ReleaseShortArrayElements(dst, p, 0);
}

JNIEXPORT jstring JNICALL Java_com_tclstream_tv_NativeReceiver_nativeStatus(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(self(h)->status().c_str());
}

}  // extern "C"
