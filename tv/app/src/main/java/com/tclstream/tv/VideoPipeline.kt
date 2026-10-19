package com.tclstream.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Feeds frames from [NativeReceiver] into a hardware MediaCodec rendering to [surface], and
 * verifies every step: decoder created, frames queued, frames decoded, frames presented.
 * If frames keep arriving but nothing comes out, the decoder is recreated (bounded retries).
 *
 * Two threads: the input thread feeds the decoder as frames arrive from the network; the output
 * thread releases each decoded frame the moment it is ready. (A single loop would only collect
 * frame N when frame N+1 arrives: a full frame of extra latency, and pairs of frames released
 * together, one of which the display then drops.)
 */
class VideoPipeline(
    private val receiver: NativeReceiver,
    private val surface: Surface,
    private val tuning: Tuning = Tuning(),
    private val refreshRate: Float = 60f,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    /** Display-path options, switchable at launch for A/B tests on real TVs. */
    data class Tuning(
        val vendorLowLatency: Boolean = true,  // vendor-specific low-latency keys (e.g. Amlogic)
        // Give each frame its own display refresh: when two frames arrive within one refresh, the
        // second is scheduled for the next one instead of replacing the first on screen.
        val pacing: Boolean = true,
        // Align each frame's display time to the middle of a real vsync interval (Choreographer),
        // so a timestamp never lands on the boundary where two frames can share one refresh.
        val vsyncAlign: Boolean = true,
    )

    enum class Stage { NO_DECODER, WAITING_FOR_VIDEO, DECODING, PRESENTING, STALLED, ERROR }

    @Volatile var stage = Stage.NO_DECODER; private set
    @Volatile var decoderName = "—"; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var recoveries = 0; private set
    val latency = LatencyTracker()

    val framesQueued = AtomicLong()
    val framesDecoded = AtomicLong()
    val framesSkipped = AtomicLong()  // decoded but deliberately not shown (a newer one was ready)
    val framesPresented = AtomicLong()

    // MediaCodec needs monotonic presentation times, so we use the local queue time as PTS and
    // remember which host capture time (0 = repeated content) belongs to it.
    private val captureTimeByPts = ConcurrentHashMap<Long, Long>()
    private val clock = LongArray(3)

    @Volatile private var running = false
    @Volatile private var codec: MediaCodec? = null
    private val codecLock = Object()
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null
    private val callbackThread = HandlerThread("tcl-render-cb").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    // Latest vsync timestamp (System.nanoTime base), kept fresh by a Choreographer on its own thread.
    @Volatile private var lastVsyncNs = 0L
    private val vsyncThread = HandlerThread("tcl-vsync").apply { start() }
    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            lastVsyncNs = frameTimeNanos
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        running = true
        if (tuning.vsyncAlign) {
            Handler(vsyncThread.looper).post { Choreographer.getInstance().postFrameCallback(vsyncCallback) }
        }
        inputThread = Thread(::inputLoop, "tcl-decoder-in").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        outputThread = Thread(::outputLoop, "tcl-decoder-out").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        inputThread?.join(2000)
        outputThread?.join(2000)
        inputThread = null
        outputThread = null
        releaseCodec()
        callbackThread.quitSafely()
        vsyncThread.quitSafely()
    }

    private fun createDecoder(mime: String): MediaCodec? {
        val best = HardwareInfo.bestDecoder(mime)
        val codecName = if (mime == MIME_HEVC) "HEVC" else "H.264"
        if (best == null) {
            fail("Esta TV não tem decodificador $codecName por hardware")
            return null
        }
        return try {
            val c = MediaCodec.createByCodecName(best.name)
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_BYTES)
                if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                if (Build.VERSION.SDK_INT >= 30 && best.lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (tuning.vendorLowLatency && best.name.lowercase().contains("amlogic")) {
                    setInteger("vdec-lowlatency", 1)
                }
            }
            c.configure(format, surface, null, 0)
            c.setOnFrameRenderedListener({ _, pts, renderNanos ->
                val n = framesPresented.incrementAndGet()
                lastPresentMs = SystemClock.elapsedRealtime()
                receiver.reportPresented(n)
                onRendered(pts, renderNanos / 1000)
            }, callbackHandler)
            c.start()
            decoderName = "$codecName · ${best.name}" + if (best.lowLatency) " (baixa latência)" else ""
            Log.i(TAG, "decoder created: ${best.name} ($mime) lowLatency=${best.lowLatency} tuning=$tuning refresh=$refreshRate")
            c
        } catch (e: Exception) {
            fail("Falha ao criar o decodificador ${best.name}: ${e.message}")
            null
        }
    }

    private fun releaseCodec() {
        synchronized(codecLock) {
            val c = codec ?: return
            codec = null
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
    }

    /** SystemClock.elapsedRealtime() of the last frame the TV confirmed on screen (0 = none yet). */
    @Volatile var lastPresentMs = 0L
        private set
    private var lastRollMs = 0L

    private fun onRendered(pts: Long, renderUs: Long) {
        val hostCapture = captureTimeByPts.remove(pts) ?: 0L
        receiver.clock(clock)
        val total = if (hostCapture != 0L && clock[0] == 1L) renderUs - (hostCapture - clock[1]) else null
        latency.add(total, renderUs - pts)
        val now = SystemClock.elapsedRealtime()
        if (now - lastRollMs >= 1000) {
            lastRollMs = now
            latency.roll()?.let { s ->
                if (!s.totalAvgMs.isNaN()) receiver.reportLatency((s.totalAvgMs * 1000).toInt())
                Log.i(TAG, "latency total avg=%.1f min=%.1f max=%.1f ms (n=%d) local decode+display=%.1f ms | queued=%d decoded=%d skipped=%d presented=%d"
                    .format(s.totalAvgMs, s.totalMinMs, s.totalMaxMs, s.samples, s.localAvgMs,
                        framesQueued.get(), framesDecoded.get(), framesSkipped.get(), framesPresented.get()))
            }
        }
    }

    /**
     * Reads frames, (re)creates the decoder for each frame's codec, feeds it, and watches for
     * stalls. Owns decoder lifetime. Frames are read into a staging buffer first so the codec is
     * known before a decoder exists (the PC picks H.264 or HEVC per session).
     */
    private fun inputLoop() {
        val meta = LongArray(3)
        val staging = java.nio.ByteBuffer.allocateDirect(MAX_FRAME_BYTES)
        var pendingSize = 0
        var pendingFlags = 0L
        var pendingCapture = 0L
        var codecMime = ""
        var decoderStartMs = 0L
        var lastFeedMs = 0L
        var lastProgressMs = 0L
        var lastProgressCount = 0L
        val recoveryTimes = ArrayDeque<Long>()

        while (running) {
            val now = SystemClock.elapsedRealtime()
            try {
                if (pendingSize == 0) {
                    staging.clear()
                    val n = receiver.readFrame(staging, 10, meta)
                    if (n < 0) Log.w(TAG, "frame of ${-n} bytes exceeds the input buffer")
                    if (n > 0) {
                        pendingSize = n
                        pendingFlags = meta[1]
                        pendingCapture = meta[2]
                        // Video resumes after a pause (host restarted, Wi-Fi gap): restart the stall
                        // clock, or the pause itself would look like a decoder that stopped producing.
                        if (now - lastFeedMs > STALL_MS) lastProgressMs = now
                    }
                }
                if (pendingSize > 0) {
                    val mime = if (pendingFlags and FLAG_HEVC != 0L) MIME_HEVC else MIME_AVC
                    var c = codec
                    if (c == null || mime != codecMime) {
                        releaseCodec()
                        c = createDecoder(mime)
                        if (c == null) {
                            pendingSize = 0
                            SystemClock.sleep(2000)
                            continue
                        }
                        codec = c
                        codecMime = mime
                        decoderStartMs = now
                        lastProgressMs = now
                        lastProgressCount = framesDecoded.get() + framesPresented.get()
                        stage = Stage.WAITING_FOR_VIDEO
                        if (pendingFlags and FLAG_KEYFRAME == 0L) {
                            // A fresh decoder must start on a keyframe.
                            pendingSize = 0
                            receiver.requestKeyframe()
                            continue
                        }
                    }
                    val idx = c.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)!!
                        buf.clear()
                        staging.position(0)
                        staging.limit(pendingSize)
                        buf.put(staging)
                        // System.nanoTime() is CLOCK_MONOTONIC, the same clock the native side uses.
                        val pts = System.nanoTime() / 1000
                        captureTimeByPts[pts] = pendingCapture
                        if (captureTimeByPts.size > 256) captureTimeByPts.clear()  // frames never rendered
                        c.queueInputBuffer(idx, 0, pendingSize, pts, 0)
                        pendingSize = 0
                        framesQueued.incrementAndGet()
                        lastFeedMs = now
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "decoder input error", e)
                lastError = "Erro no decodificador: ${e.message ?: e.javaClass.simpleName}"
                releaseCodec()
                pendingSize = 0
                recoveries++
                continue
            }

            // Progress = decoder output or confirmed presentation moving forward.
            val progress = framesDecoded.get() + framesPresented.get()
            if (progress != lastProgressCount) {
                lastProgressCount = progress
                lastProgressMs = now
                stage = if (now - lastPresentMs < 1000) Stage.PRESENTING else Stage.DECODING
            } else if (framesQueued.get() == 0L) {
                stage = Stage.WAITING_FOR_VIDEO
            }

            // Stall: video keeps arriving, nothing comes out. Recreate only the decoder.
            val fedRecently = now - lastFeedMs < 1000
            if (codec != null && fedRecently && now - lastProgressMs > STALL_MS && now - decoderStartMs > STALL_MS) {
                stage = Stage.STALLED
                lastError = "Vídeo chegando mas o decodificador não produz imagem — recriando decodificador"
                Log.w(TAG, "$lastError (queued=${framesQueued.get()} decoded=${framesDecoded.get()} presented=${framesPresented.get()})")
                while (recoveryTimes.isNotEmpty() && now - recoveryTimes.first() > 60_000) recoveryTimes.removeFirst()
                recoveryTimes.addLast(now)
                recoveries++
                releaseCodec()
                receiver.requestKeyframe()
                if (recoveryTimes.size > MAX_RECOVERIES_PER_MINUTE) {
                    fail("O decodificador da TV travou ${recoveryTimes.size} vezes em 1 minuto")
                    SystemClock.sleep(10_000)  // back off instead of looping forever
                    recoveryTimes.clear()
                }
            }
        }
    }

    /** Releases decoded frames to the display as soon as they are ready. */
    private fun outputLoop() {
        val info = MediaCodec.BufferInfo()
        val periodNs = (1_000_000_000.0 / refreshRate).toLong()
        var lastTargetNs = 0L
        while (running) {
            synchronized(codecLock) {
                val c = codec ?: return@synchronized
                try {
                    var out = c.dequeueOutputBuffer(info, 4_000)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "output format: ${c.outputFormat}")
                        return@synchronized
                    }
                    if (out < 0) return@synchronized
                    framesDecoded.incrementAndGet()
                    // If newer frames are already decoded, show only the newest: never build a backlog.
                    while (true) {
                        val newer = c.dequeueOutputBuffer(info, 0)
                        if (newer < 0) break
                        framesDecoded.incrementAndGet()
                        framesSkipped.incrementAndGet()
                        c.releaseOutputBuffer(out, false)
                        out = newer
                    }
                    val now = System.nanoTime()
                    var target = now
                    val vsync = lastVsyncNs
                    if (tuning.vsyncAlign && vsync != 0L && now - vsync < 4 * periodNs) {
                        // Earliest refresh we can still make, then one refresh per frame.
                        val ahead = (now - vsync + periodNs - 1) / periodNs
                        var slot = vsync + ahead * periodNs
                        if (slot < lastTargetNs + periodNs / 2) slot = lastTargetNs + periodNs
                        if (slot - now > 2 * periodNs) slot = vsync + ahead * periodNs  // don't build latency
                        lastTargetNs = slot
                        // Ask for "half a refresh before that vsync": unambiguous which refresh it is.
                        target = maxOf(now, slot - periodNs / 2)
                    } else if (tuning.pacing) {
                        // One frame per refresh; if pacing would push us >1 refresh behind, reset to now.
                        target = maxOf(now, lastTargetNs + periodNs)
                        if (target - now > periodNs) target = now
                        lastTargetNs = target
                    }
                    c.releaseOutputBuffer(out, target)
                } catch (e: IllegalStateException) {
                    // Decoder being recreated by the input thread; it handles recovery.
                }
            }
            if (codec == null) SystemClock.sleep(5)
        }
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        lastError = msg
        stage = Stage.ERROR
    }

    companion object {
        private const val TAG = "Jinky"
        const val MIME_AVC = "video/avc"
        const val MIME_HEVC = "video/hevc"
        private const val FLAG_KEYFRAME = 1L
        private const val FLAG_HEVC = 2L
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        private const val STALL_MS = 3000L
        private const val MAX_RECOVERIES_PER_MINUTE = 3
    }
}
