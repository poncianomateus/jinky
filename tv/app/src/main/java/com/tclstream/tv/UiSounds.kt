package com.tclstream.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The interface's own little sounds, synthesised here (nothing borrowed from any console): a soft
 * tick to move, two rising notes to confirm, one falling note to go back, a short arpeggio for an
 * achievement and the Jinky signature when the app opens. Two voices: Jinky (round, soft) and
 * Retrô (8-bit square waves).
 */
object UiSounds {
    enum class Sound { MOVE, SELECT, BACK, ERROR, ACHIEVEMENT, BOOT }

    private const val RATE = 44_100
    private var pool: SoundPool? = null
    private val ids = HashMap<Pair<Boolean, Sound>, Int>()  // (retro, sound) -> SoundPool id
    private var lastMove = 0L

    /** "theme", "retro" or "off" (Configurações), and 0..100. */
    @Volatile var mode = "theme"
    @Volatile var volume = 70

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        val dir = File(context.cacheDir, "sounds").apply { mkdirs() }
        for (retro in listOf(false, true)) for (s in Sound.values()) {
            val file = File(dir, "${if (retro) "retro" else "jinky"}_${s.name.lowercase()}_v1.wav")
            if (!file.exists()) writeWav(file, synth(s, retro))
            ids[retro to s] = p.load(file.path, 1)
        }
    }

    fun play(s: Sound) {
        val p = pool ?: return
        if (mode == "off" || volume <= 0) return
        if (s == Sound.MOVE) {
            // Holding a direction repeats fast: don't machine-gun the tick.
            val now = System.currentTimeMillis()
            if (now - lastMove < 45) return
            lastMove = now
        }
        val retro = mode == "retro" || (mode == "theme" && Jinky.theme == UiTheme.RETRO)
        val id = ids[retro to s] ?: return
        val v = volume / 100f * when (s) { Sound.MOVE -> 0.45f; Sound.BOOT, Sound.ACHIEVEMENT -> 0.8f; else -> 0.6f }
        p.play(id, v, v, 1, 0, 1f)
    }

    // --- synthesis ---

    private class Note(val hz: Double, val start: Double, val length: Double, val gain: Double = 1.0)

    private fun synth(s: Sound, retro: Boolean): ShortArray {
        val notes = when (s) {
            Sound.MOVE -> listOf(Note(if (retro) 1320.0 else 1760.0, 0.0, 0.03, 0.55))
            Sound.SELECT -> listOf(Note(660.0, 0.0, 0.05), Note(990.0, 0.045, 0.08))
            Sound.BACK -> listOf(Note(880.0, 0.0, 0.045), Note(587.3, 0.04, 0.07))
            Sound.ERROR -> listOf(Note(220.0, 0.0, 0.13), Note(207.7, 0.0, 0.13, 0.6))
            Sound.ACHIEVEMENT -> listOf(1046.5, 1318.5, 1568.0, 2093.0).mapIndexed { i, hz ->
                Note(hz, i * 0.085, if (i == 3) 0.45 else 0.12, if (i == 3) 1.0 else 0.8)
            } + Note(1568.0, 0.255, 0.45, 0.35)
            Sound.BOOT -> if (retro) {
                listOf(523.3, 659.3, 784.0, 1046.5, 1318.5).mapIndexed { i, hz -> Note(hz, i * 0.06, 0.12, 0.7) } +
                    listOf(523.3, 784.0, 1046.5).map { Note(it, 0.32, 0.9, 0.5) }
            } else {
                // A blip like the logo's pixel, then a warm chord that swells and settles.
                listOf(Note(2093.0, 0.0, 0.07, 0.5)) +
                    listOf(261.6, 392.0, 523.3, 659.3, 784.0).map { Note(it, 0.08, 1.1, 0.42) }
            }
        }
        val total = notes.maxOf { it.start + it.length } + 0.02
        val out = DoubleArray((total * RATE).toInt())
        for (n in notes) {
            val from = (n.start * RATE).toInt()
            val count = (n.length * RATE).toInt()
            val swell = s == Sound.BOOT && !retro && n.length > 0.5
            for (i in 0 until count) {
                val t = i.toDouble() / RATE
                val phase = 2 * PI * n.hz * t
                val wave = if (retro) (if (sin(phase) >= 0) 0.5 else -0.5)
                           else sin(phase) + 0.18 * sin(2 * phase) + 0.06 * sin(3 * phase)
                // Quick attack, smooth decay; the boot chord swells in instead.
                val attack = min(1.0, t / (if (swell) 0.25 else 0.004))
                val decay = if (swell) exp(-2.2 * maxOf(0.0, t - 0.25)) else exp(-t / (n.length * 0.35))
                val idx = from + i
                if (idx < out.size) out[idx] += wave * attack * decay * n.gain
            }
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6)
        // Short fade at the very end so nothing clicks.
        val fade = (0.01 * RATE).toInt()
        for (i in 0 until fade) out[out.size - 1 - i] *= i.toDouble() / fade
        return ShortArray(out.size) { (out[it] / peak * 0.85 * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size * 2)
        }
        FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
    }
}
