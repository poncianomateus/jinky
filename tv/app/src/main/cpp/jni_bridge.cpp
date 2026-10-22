// JNI surface for com.tclstream.tv.NativeReceiver.
#include <jni.h>

#include <cstring>
#include <vector>

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

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeSetCodecSupport(JNIEnv*, jclass, jlong h,
                                                                                    jint mask) {
    self(h)->set_codec_support(uint32_t(mask));
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeStop(JNIEnv*, jclass, jlong h) {
    self(h)->stop();
}

// meta: [frameId, flags (bit0 keyframe, bit1 HEVC), captureTimeUs]
JNIEXPORT jint JNICALL Java_com_tclstream_tv_NativeReceiver_nativeReadFrame(JNIEnv* env, jclass, jlong h,
                                                                            jobject buffer, jint timeout_ms,
                                                                            jlongArray meta) {
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong cap = env->GetDirectBufferCapacity(buffer);
    if (!dst || cap <= 0) return 0;
    Receiver::FrameMeta m;
    const int n = self(h)->read_frame(dst, size_t(cap), timeout_ms, m);
    if (n > 0) {
        jlong out[3] = {jlong(m.frame_id), (m.keyframe ? 1 : 0) | (m.hevc ? 2 : 0), jlong(m.capture_time_us)};
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

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeSendInput(
    JNIEnv*, jclass, jlong h, jint pad, jboolean connected, jint seq, jint buttons, jint lt, jint rt, jint lx,
    jint ly, jint rx, jint ry) {
    tclproto::InputState s;
    s.pad_index = uint8_t(pad);
    s.connected = connected;
    s.seq = uint32_t(seq);
    s.buttons = uint16_t(buttons);
    s.left_trigger = uint8_t(lt);
    s.right_trigger = uint8_t(rt);
    s.lx = int16_t(lx);
    s.ly = int16_t(ly);
    s.rx = int16_t(rx);
    s.ry = int16_t(ry);
    self(h)->send_input(s);
}

// Returns pad | large << 8 | small << 16, or -1 if nothing is pending.
JNIEXPORT jint JNICALL Java_com_tclstream_tv_NativeReceiver_nativePollRumble(JNIEnv*, jclass, jlong h) {
    auto r = self(h)->poll_rumble();
    if (!r) return -1;
    return jint(r->pad_index | (r->large_motor << 8) | (r->small_motor << 16));
}

JNIEXPORT jstring JNICALL Java_com_tclstream_tv_NativeReceiver_nativeHostAddress(JNIEnv* env, jclass, jlong h) {
    const std::string a = self(h)->host_address();
    return a.empty() ? nullptr : env->NewStringUTF(a.c_str());
}

JNIEXPORT jint JNICALL Java_com_tclstream_tv_NativeReceiver_nativeState(JNIEnv*, jclass, jlong h) {
    return self(h)->state();
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeTakeover(JNIEnv*, jclass, jlong h) {
    self(h)->request_takeover();
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeSetDeviceName(JNIEnv* env, jclass, jlong h,
                                                                                jstring name) {
    const char* s = env->GetStringUTFChars(name, nullptr);
    self(h)->set_device_name(s ? s : "");
    if (s) env->ReleaseStringUTFChars(name, s);
}

// --- home screen ---
JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeConnect(JNIEnv* env, jclass, jlong h, jstring ip,
                                                                         jboolean desktop, jboolean game) {
    const char* s = env->GetStringUTFChars(ip, nullptr);
    if (auto e = tclproto::Endpoint::parse(s ? s : "", 0)) self(h)->connect(e->ip, desktop, game);
    if (s) env->ReleaseStringUTFChars(ip, s);
}

JNIEXPORT jint JNICALL Java_com_tclstream_tv_NativeReceiver_nativeVideoKbps(JNIEnv*, jclass, jlong h) {
    return jint(self(h)->video_kbps());
}

JNIEXPORT jint JNICALL Java_com_tclstream_tv_NativeReceiver_nativeGameReady(JNIEnv*, jclass, jlong h) {
    return self(h)->game_ready();
}

JNIEXPORT jint JNICALL Java_com_tclstream_tv_NativeReceiver_nativeEndReason(JNIEnv*, jclass, jlong h) {
    return self(h)->end_reason();
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeCloseGame(JNIEnv*, jclass, jlong h) {
    self(h)->close_game();
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeDisconnect(JNIEnv*, jclass, jlong h) {
    self(h)->disconnect();
}

JNIEXPORT jboolean JNICALL Java_com_tclstream_tv_NativeReceiver_nativeInSession(JNIEnv*, jclass, jlong h) {
    return self(h)->in_session();
}

JNIEXPORT jstring JNICALL Java_com_tclstream_tv_NativeReceiver_nativeHosts(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(self(h)->hosts().c_str());
}

// --- pairing ---
JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeSetIdentity(JNIEnv*, jclass, jlong h, jlong id) {
    self(h)->set_identity(uint64_t(id));
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeAddPairedHost(JNIEnv* env, jclass, jlong h,
                                                                               jlong host, jbyteArray key) {
    tclproto::PairKey k{};
    if (env->GetArrayLength(key) != jsize(k.size())) return;
    env->GetByteArrayRegion(key, 0, jsize(k.size()), reinterpret_cast<jbyte*>(k.data()));
    self(h)->add_paired_host(uint64_t(host), k);
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeAddKnownAddress(JNIEnv* env, jclass, jlong h,
                                                                                 jstring ip) {
    const char* s = env->GetStringUTFChars(ip, nullptr);
    self(h)->add_known_address(s);
    env->ReleaseStringUTFChars(ip, s);
}

static bool to_public(JNIEnv* env, jbyteArray a, tclproto::PublicKey& out) {
    if (!a || env->GetArrayLength(a) != jsize(out.size())) return false;
    env->GetByteArrayRegion(a, 0, jsize(out.size()), reinterpret_cast<jbyte*>(out.data()));
    return true;
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeStartPairing(JNIEnv* env, jclass, jlong h,
                                                                              jbyteArray tv_public) {
    tclproto::PublicKey pub{};
    if (to_public(env, tv_public, pub)) self(h)->start_pairing(pub);
}

JNIEXPORT jbyteArray JNICALL Java_com_tclstream_tv_NativeReceiver_nativePairingChallenge(JNIEnv* env, jclass, jlong h) {
    auto c = self(h)->pairing_challenge();
    if (!c) return nullptr;
    jbyteArray out = env->NewByteArray(jsize(c->size()));
    env->SetByteArrayRegion(out, 0, jsize(c->size()), reinterpret_cast<const jbyte*>(c->data()));
    return out;
}

JNIEXPORT jstring JNICALL Java_com_tclstream_tv_NativeReceiver_nativeSetPairSecret(JNIEnv* env, jclass, jlong h,
                                                                                  jbyteArray secret,
                                                                                  jbyteArray tv_public,
                                                                                  jbyteArray pc_public) {
    tclproto::PublicKey tv{}, pc{};
    if (!to_public(env, tv_public, tv) || !to_public(env, pc_public, pc)) return nullptr;
    std::vector<uint8_t> s(size_t(env->GetArrayLength(secret)));
    env->GetByteArrayRegion(secret, 0, jsize(s.size()), reinterpret_cast<jbyte*>(s.data()));
    return env->NewStringUTF(self(h)->set_pair_secret(s, tv, pc).c_str());
}

// Returns host id (8 bytes, little-endian) + key (32 bytes), or null.
JNIEXPORT jbyteArray JNICALL Java_com_tclstream_tv_NativeReceiver_nativeTakeNewPairing(JNIEnv* env, jclass, jlong h) {
    auto p = self(h)->take_new_pairing();
    if (!p) return nullptr;
    uint8_t buf[40];
    for (int i = 0; i < 8; ++i) buf[i] = uint8_t(p->first >> (8 * i));
    std::memcpy(buf + 8, p->second.data(), 32);
    jbyteArray out = env->NewByteArray(40);
    env->SetByteArrayRegion(out, 0, 40, reinterpret_cast<const jbyte*>(buf));
    return out;
}

JNIEXPORT jlong JNICALL Java_com_tclstream_tv_NativeReceiver_nativeTakeForgottenHost(JNIEnv*, jclass, jlong h) {
    return jlong(self(h)->take_forgotten_host());
}

JNIEXPORT void JNICALL Java_com_tclstream_tv_NativeReceiver_nativeRetryPairing(JNIEnv*, jclass, jlong h) {
    self(h)->retry_pairing();
}

JNIEXPORT jstring JNICALL Java_com_tclstream_tv_NativeReceiver_nativeStatus(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(self(h)->status().c_str());
}

}  // extern "C"
