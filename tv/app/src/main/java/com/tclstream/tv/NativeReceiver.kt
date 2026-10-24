package com.tclstream.tv

import java.nio.ByteBuffer

/** Network receiver implemented in C++ (shared protocol library with the Windows host). */
class NativeReceiver : AutoCloseable {
    private var handle = nativeCreate()

    /** Returns null on success, or an error message. */
    fun start(port: Int): String? = nativeStart(handle, port)

    fun stop() = nativeStop(handle)

    // --- home screen ---
    /** Starts a session with the PC at [ip]: console mode, or just its screen when [desktop]. */
    /** [port]: the PC's streaming port from discovery (several Jinkys can share one PC); 0 = the usual one. */
    fun connect(ip: String, port: Int, desktop: Boolean, game: Boolean = false) = nativeConnect(handle, ip, port, desktop, game)
    /** Game session: -1 still starting, 0 the game is on screen, 1 a launcher/prompt waits for the player. */
    fun gameReady(): Int = nativeGameReady(handle)
    /** Video arriving right now (last second), kbit/s; the PC adjusts it to the network. */
    fun videoKbps(): Int = nativeVideoKbps(handle)
    /** Why the PC ended the session: 0 Big Picture closed, 1 game closed. */
    fun endReason(): Int = nativeEndReason(handle)
    fun closeGame() = nativeCloseGame(handle)
    /** Ends the session and goes back to discovering PCs. */
    fun disconnect() = nativeDisconnect(handle)
    fun inSession(): Boolean = nativeInSession(handle)
    /** PCs seen recently: lines "hostId|ip|state|mac|ageMs|name|busyWith". */
    fun hosts(): String = nativeHosts(handle)

    /** Codecs decodable in hardware: bit0 H.264, bit1 HEVC. Sent to the PC in every Hello. */
    fun setCodecSupport(mask: Int) = nativeSetCodecSupport(handle, mask)

    /**
     * Copies the next decodable frame into [dst] (must be a direct buffer).
     * Returns its size, 0 on timeout, or a negative size if it did not fit (frame dropped).
     * [meta] receives [frameId, flags (bit0 keyframe, bit1 HEVC), captureTimeUs].
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

    /** Address of the PC streaming to this TV, or null while still searching. */
    fun hostAddress(): String? = nativeHostAddress(handle)

    /** 0 = this TV has the session (or is looking for the PC), 1 = another TV took it, 2 = the PC ended it. */
    fun state(): Int = nativeState(handle)

    /** "Play here": take the session over from another TV. */
    fun takeover() = nativeTakeover(handle)

    fun setDeviceName(name: String) = nativeSetDeviceName(handle, name)

    // --- pairing ---
    fun setIdentity(deviceId: Long) = nativeSetIdentity(handle, deviceId)
    fun addPairedHost(hostId: Long, key: ByteArray) = nativeAddPairedHost(handle, hostId, key)
    fun addKnownAddress(ip: String) = nativeAddKnownAddress(handle, ip)
    fun startPairing(tvPublic: ByteArray) = nativeStartPairing(handle, tvPublic)
    /** The PC's public key (65 bytes) once its challenge arrived, else null. */
    fun pairingChallenge(): ByteArray? = nativePairingChallenge(handle)
    /** Gives the ECDH secret; returns the 6-digit code to show. */
    fun setPairSecret(secret: ByteArray, tvPublic: ByteArray, pcPublic: ByteArray): String? =
        nativeSetPairSecret(handle, secret, tvPublic, pcPublic)
    /** After approval on the PC: host id (8 bytes LE) + key (32 bytes), once; else null. */
    fun takeNewPairing(): ByteArray? = nativeTakeNewPairing(handle)
    /** A PC that forgot this TV (0 = none), once. */
    fun takeForgottenHost(): Long = nativeTakeForgottenHost(handle)
    fun retryPairing() = nativeRetryPairing(handle)

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
        @JvmStatic external fun nativeConnect(handle: Long, ip: String, port: Int, desktop: Boolean, game: Boolean)
        @JvmStatic external fun nativeGameReady(handle: Long): Int
        @JvmStatic external fun nativeVideoKbps(handle: Long): Int
        @JvmStatic external fun nativeEndReason(handle: Long): Int
        @JvmStatic external fun nativeCloseGame(handle: Long)
        @JvmStatic external fun nativeDisconnect(handle: Long)
        @JvmStatic external fun nativeInSession(handle: Long): Boolean
        @JvmStatic external fun nativeHosts(handle: Long): String
        @JvmStatic external fun nativeSetCodecSupport(handle: Long, mask: Int)
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
        @JvmStatic external fun nativeHostAddress(handle: Long): String?
        @JvmStatic external fun nativeState(handle: Long): Int
        @JvmStatic external fun nativeTakeover(handle: Long)
        @JvmStatic external fun nativeSetDeviceName(handle: Long, name: String)
        @JvmStatic external fun nativeSetIdentity(handle: Long, deviceId: Long)
        @JvmStatic external fun nativeAddPairedHost(handle: Long, hostId: Long, key: ByteArray)
        @JvmStatic external fun nativeAddKnownAddress(handle: Long, ip: String)
        @JvmStatic external fun nativeStartPairing(handle: Long, tvPublic: ByteArray)
        @JvmStatic external fun nativePairingChallenge(handle: Long): ByteArray?
        @JvmStatic external fun nativeSetPairSecret(handle: Long, secret: ByteArray, tvPublic: ByteArray, pcPublic: ByteArray): String?
        @JvmStatic external fun nativeTakeNewPairing(handle: Long): ByteArray?
        @JvmStatic external fun nativeTakeForgottenHost(handle: Long): Long
        @JvmStatic external fun nativeRetryPairing(handle: Long)
        @JvmStatic external fun nativeStatus(handle: Long): String
    }
}
