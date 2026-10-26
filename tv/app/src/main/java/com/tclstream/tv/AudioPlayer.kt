package com.tclstream.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Plays the PC's audio: pulls 5 ms chunks of 48 kHz stereo PCM from the native jitter buffer and
 * writes them to a low-latency AudioTrack. The blocking write paces the loop at the TV's own
 * audio clock; the native buffer absorbs network jitter and clock drift.
 */
class AudioPlayer(private val receiver: NativeReceiver) {
    /** "Sincronia do áudio": hold the sound back this long (0..500 ms); takes effect right away. */
    @Volatile var delayMs = 0

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var outputLatencyMs = Double.NaN; private set
    @Volatile var deviceUnderruns = 0; private set
    @Volatile var error: String? = null; private set

    fun start() {
        running = true
        thread = Thread(::loop, "tcl-audio").apply { start() }
    }

    /** Silent while a game is still starting (Big Picture, launchers); fades back in when cleared. */
    @Volatile var muted = false

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply { if (Build.VERSION.SDK_INT >= 26) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
                .build()
        } catch (e: Exception) {
            error = "Falha ao abrir a saída de áudio: ${e.message}"
            Log.e(TAG, error!!, e)
            return
        }
        // Start with a small device queue (low latency) and grow it only if the TV actually underruns.
        var queueFrames = START_QUEUE_FRAMES
        if (Build.VERSION.SDK_INT >= 24) queueFrames = track.setBufferSizeInFrames(queueFrames)
        Log.i(TAG, "audio track: capacity ${minBytes / 4} frames, using $queueFrames")
        // Pre-fill the device queue with silence so the first reads don't drain the jitter buffer.
        val silence = ShortArray(queueFrames * 2)
        track.write(silence, 0, silence.size)
        track.play()
        var lastUnderruns = 0

        val chunk = ShortArray(CHUNK_FRAMES * 2)
        // "Sincronia do áudio": the sound held back a fixed time (when it comes before the picture,
        // e.g. a TV that processes the image slowly). A queue of 5 ms chunks, primed with silence.
        val delayLine = ArrayDeque<ShortArray>()
        val ts = android.media.AudioTimestamp()
        var written = silence.size / 2L  // the pre-fill counts as written
        var lastReport = 0L
        var lastUnderrunCheck = 0L
        // Right after play() the TV's audio system pulls faster than real time while it fills its own
        // internal buffers (measured ~15% over the first second). Feed it silence during that phase
        // instead of draining the jitter buffer, which would cause clicks at the start.
        val warmupEnd = System.nanoTime() + WARMUP_NS
        var gain = if (muted) 0f else 1f
        try {
            while (running) {
                if (System.nanoTime() < warmupEnd) chunk.fill(0)
                else receiver.readAudio(chunk, CHUNK_FRAMES)
                // Muted: keep draining the stream (no delay builds up), play silence; unmuting
                // ramps the volume up over ~0.4 s.
                val target = if (muted) 0f else 1f
                if (gain != target || gain < 1f) {
                    gain = if (target == 0f) 0f else minOf(1f, gain + CHUNK_FRAMES / (RATE * 0.4f))
                    for (i in chunk.indices) chunk[i] = (chunk[i] * gain).toInt().toShort()
                }
                val delayChunks = (delayMs.coerceIn(0, 500) + 2) / 5
                if (delayChunks == 0 && delayLine.isEmpty()) track.write(chunk, 0, chunk.size)
                else {
                    delayLine.addLast(chunk.copyOf())
                    // Grow: play silence while the queue fills. Shrink: skip the oldest sound.
                    while (delayLine.size > delayChunks + 1) delayLine.removeFirst()
                    val out = if (delayLine.size <= delayChunks) ShortArray(chunk.size) else delayLine.removeFirst()
                    track.write(out, 0, out.size)
                }
                written += CHUNK_FRAMES
                val now = System.nanoTime()
                // Grow the device queue quickly if the TV can't keep up with it this small.
                if (Build.VERSION.SDK_INT >= 24 && now - lastUnderrunCheck > 100_000_000L) {
                    lastUnderrunCheck = now
                    deviceUnderruns = track.underrunCount
                    if (deviceUnderruns > lastUnderruns && queueFrames < minBytes / 4) {
                        queueFrames = track.setBufferSizeInFrames(queueFrames + CHUNK_FRAMES)
                        Log.w(TAG, "audio device underrun; queue raised to $queueFrames frames")
                    }
                    lastUnderruns = deviceUnderruns
                }
                if (now - lastReport > 1_000_000_000L) {
                    lastReport = now
                    // Frames written but not yet heard = output latency of the TV audio path.
                    if (track.getTimestamp(ts)) {
                        val playedNow = ts.framePosition + (now - ts.nanoTime) * RATE / 1_000_000_000L
                        outputLatencyMs = (written - playedNow) * 1000.0 / RATE
                    }
                }
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    companion object {
        private const val TAG = "Jinky"
        private const val RATE = 48_000
        private const val CHUNK_FRAMES = 240  // 5 ms
        private const val WARMUP_NS = 1_500_000_000L
        private const val START_QUEUE_FRAMES = 1920  // 40 ms: measured minimum on the TCL is ~45 ms
    }
}
