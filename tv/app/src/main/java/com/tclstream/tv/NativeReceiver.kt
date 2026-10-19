package com.tclstream.tv

import java.nio.ByteBuffer

/** Network receiver implemented in C++ (shared protocol library with the Windows host). */
class NativeReceiver : AutoCloseable {
    private var handle = nativeCreate()

    /** Returns null on success, or an error message. */
    fun start(port: Int): String? = nativeStart(handle, port)

    fun stop() = nativeStop(handle)

    /**
     * Copies the next decodable frame into [dst] (must be a direct buffer).
     * Returns its size, 0 on timeout, or a negative size if it did not fit (frame dropped).
     * [meta] receives [frameId, isKeyframe (0/1), captureTimeUs].
     */
    fun readFrame(dst: ByteBuffer, timeoutMs: Int, meta: LongArray): Int =
        nativeReadFrame(handle, dst, timeoutMs, meta)

    fun requestKeyframe() = nativeRequestKeyframe(handle)

    fun reportPresented(count: Long) = nativeReportPresented(handle, count)

    fun reportLatency(us: Int) = nativeReportLatency(handle, us)

    /** Fills [out] with [synced (0/1), hostMinusLocalUs, rttUs]. */
    fun clock(out: LongArray) = nativeClock(handle, out)

    /** Fills [dst] with [frames] stereo frames (silence if the buffer ran dry). */
    fun readAudio(dst: ShortArray, frames: Int) = nativeReadAudio(handle, dst, frames)

    fun sendInput(pad: Int, connected: Boolean, seq: Int, buttons: Int, lt: Int, rt: Int, lx: Int, ly: Int, rx: Int, ry: Int) =
        nativeSendInput(handle, pad, connected, seq, buttons, lt, rt, lx, ly, rx, ry)

    /** pad | large << 8 | small << 16, or -1 when no vibration request is pending. */
    fun pollRumble(): Int = nativePollRumble(handle)

    fun status(): String = nativeStatus(handle)

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private companion object {
        init {
            System.loadLibrary("tclreceiver")
        }

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeStart(handle: Long, port: Int): String?
        @JvmStatic external fun nativeStop(handle: Long)
        @JvmStatic external fun nativeReadFrame(handle: Long, dst: ByteBuffer, timeoutMs: Int, meta: LongArray): Int
        @JvmStatic external fun nativeRequestKeyframe(handle: Long)
        @JvmStatic external fun nativeReportPresented(handle: Long, count: Long)
        @JvmStatic external fun nativeReportLatency(handle: Long, us: Int)
        @JvmStatic external fun nativeClock(handle: Long, out: LongArray)
        @JvmStatic external fun nativeReadAudio(handle: Long, dst: ShortArray, frames: Int)
        @JvmStatic external fun nativeSendInput(
            handle: Long, pad: Int, connected: Boolean, seq: Int, buttons: Int, lt: Int, rt: Int,
            lx: Int, ly: Int, rx: Int, ry: Int,
        )
        @JvmStatic external fun nativePollRumble(handle: Long): Int
        @JvmStatic external fun nativeStatus(handle: Long): String
    }
}
