package com.tclstream.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Feeds frames from [NativeReceiver] into a hardware MediaCodec rendering to [surface], and
 * verifies every step: decoder created, frames queued, frames decoded, frames presented.
 * If frames keep arriving but nothing comes out, the decoder is recreated (bounded retries).
 */
class VideoPipeline(
    private val receiver: NativeReceiver,
    private val surface: Surface,
    private val tuning: Tuning = Tuning(),
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    /** Display-path options, switchable at launch for A/B latency tests on real TVs. */
    data class Tuning(
        val vendorLowLatency: Boolean = true,  // vendor-specific low-latency keys (e.g. Amlogic)
        val explicitRenderTime: Boolean = true,  // releaseOutputBuffer(idx, now) instead of (idx, true)
    )

    enum class Stage { NO_DECODER, WAITING_FOR_VIDEO, DECODING, PRESENTING, STALLED, ERROR }

    @Volatile var stage = Stage.NO_DECODER; private set
    @Volatile var decoderName = "—"; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var recoveries = 0; private set
    val latency = LatencyTracker()

    val framesQueued = AtomicLong()
    val framesDecoded = AtomicLong()
    val framesPresented = AtomicLong()

    // MediaCodec needs monotonic presentation times, so we use the local queue time as PTS and
    // remember which host capture time (0 = repeated content) belongs to it.
    private val captureTimeByPts = ConcurrentHashMap<Long, Long>()
    private val clock = LongArray(3)

    @Volatile private var running = false
    private var thread: Thread? = null
    private val callbackThread = HandlerThread("tcl-render-cb").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    fun start() {
        running = true
        thread = Thread(::loop, "tcl-decoder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        callbackThread.quitSafely()
    }

    private fun createDecoder(): MediaCodec? {
        val best = HardwareInfo.bestDecoder(MIME)
        if (best == null) {
            fail("Esta TV não tem decodificador H.264 por hardware")
            return null
        }
        return try {
            val codec = MediaCodec.createByCodecName(best.name)
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_BYTES)
                if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                if (Build.VERSION.SDK_INT >= 30 && best.lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                if (tuning.vendorLowLatency && best.name.lowercase().contains("amlogic")) {
                    setInteger("vdec-lowlatency", 1)
                }
            }
            codec.configure(format, surface, null, 0)
            codec.setOnFrameRenderedListener({ _, pts, renderNanos ->
                val n = framesPresented.incrementAndGet()
                lastPresentMs = SystemClock.elapsedRealtime()
                receiver.reportPresented(n)
                onRendered(pts, renderNanos / 1000)
            }, callbackHandler)
            codec.start()
            decoderName = best.name + if (best.lowLatency) " (baixa latência)" else ""
            Log.i(TAG, "decoder created: ${best.name} lowLatency=${best.lowLatency}")
            codec
        } catch (e: Exception) {
            fail("Falha ao criar o decodificador ${best.name}: ${e.message}")
            null
        }
    }

    @Volatile private var lastPresentMs = 0L
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
                Log.i(TAG, "latency total avg=%.1f min=%.1f max=%.1f ms (n=%d) local decode+display=%.1f ms | queued=%d decoded=%d presented=%d"
                    .format(s.totalAvgMs, s.totalMinMs, s.totalMaxMs, s.samples, s.localAvgMs,
                        framesQueued.get(), framesDecoded.get(), framesPresented.get()))
            }
        }
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        val meta = LongArray(3)
        var codec: MediaCodec? = null
        var pendingInput = -1
        var decoderStartMs = 0L
        var lastFeedMs = 0L
        var lastProgressMs = 0L
        var lastProgressCount = 0L
        val recoveryTimes = ArrayDeque<Long>()

        fun release() {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
            pendingInput = -1
        }

        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (codec == null) {
                codec = createDecoder()
                if (codec == null) {
                    SystemClock.sleep(2000)
                    continue
                }
                receiver.requestKeyframe()
                decoderStartMs = now
                lastProgressMs = now
                lastProgressCount = framesDecoded.get() + framesPresented.get()
                stage = Stage.WAITING_FOR_VIDEO
            }
            val c = codec!!
            try {
                if (pendingInput < 0) pendingInput = c.dequeueInputBuffer(5_000)
                if (pendingInput >= 0) {
                    val buf = c.getInputBuffer(pendingInput)!!
                    buf.clear()
                    val n = receiver.readFrame(buf, 10, meta)
                    if (n > 0) {
                        // System.nanoTime() is CLOCK_MONOTONIC, the same clock the native side uses.
                        val pts = System.nanoTime() / 1000
                        captureTimeByPts[pts] = meta[2]
                        if (captureTimeByPts.size > 256) captureTimeByPts.clear()  // frames never rendered
                        c.queueInputBuffer(pendingInput, 0, n, pts, 0)
                        pendingInput = -1
                        framesQueued.incrementAndGet()
                        lastFeedMs = now
                    } else if (n < 0) {
                        Log.w(TAG, "frame of ${-n} bytes exceeds decoder input buffer (${buf.capacity()})")
                    }
                }
                while (true) {
                    val out = c.dequeueOutputBuffer(info, 0)
                    if (out >= 0) {
                        // render = true: show immediately, no presentation-time scheduling.
                        if (tuning.explicitRenderTime) c.releaseOutputBuffer(out, System.nanoTime())
                        else c.releaseOutputBuffer(out, true)
                        framesDecoded.incrementAndGet()
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "output format: ${c.outputFormat}")
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "decoder error", e)
                lastError = "Erro no decodificador: ${e.message ?: e.javaClass.simpleName}"
                release()
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
            if (fedRecently && now - lastProgressMs > STALL_MS && now - decoderStartMs > STALL_MS) {
                stage = Stage.STALLED
                lastError = "Vídeo chegando mas o decodificador não produz imagem — recriando decodificador"
                Log.w(TAG, lastError!!)
                while (recoveryTimes.isNotEmpty() && now - recoveryTimes.first() > 60_000) recoveryTimes.removeFirst()
                recoveryTimes.addLast(now)
                recoveries++
                release()
                if (recoveryTimes.size > MAX_RECOVERIES_PER_MINUTE) {
                    fail("O decodificador da TV travou ${recoveryTimes.size} vezes em 1 minuto")
                    SystemClock.sleep(10_000)  // back off instead of looping forever
                    recoveryTimes.clear()
                }
            }
        }
        release()
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        lastError = msg
        stage = Stage.ERROR
    }

    companion object {
        private const val TAG = "TclStream"
        private const val MIME = "video/avc"
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        private const val STALL_MS = 3000L
        private const val MAX_RECOVERIES_PER_MINUTE = 3
    }
}
