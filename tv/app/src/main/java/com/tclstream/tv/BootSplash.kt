package com.tclstream.tv

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/**
 * The opening when Jinky starts, like a console's boot (about 3 s): the brand's blue pixel lights
 * up, grows into the Jinky tile (the signature sound as it lands, a glow pulsing behind), the tile
 * glides aside as the wordmark comes in, a light sweeps across the tile while small pixels drift
 * out, and everything dissolves into the home screen. The retro theme types "JINKY" on a CRT
 * instead. [fast] (a theme change) keeps it under a second; any button skips it.
 */
class BootSplash(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    // Material motion curves: smooth starts and gentle landings, nothing linear.
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val standard = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val exit = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    private val content = FrameLayout(context)
    private var onDone: (() -> Unit)? = null
    private var finishing = false
    private var startedAt = 0L

    init {
        setBackgroundColor(Jinky.INK)
        isClickable = true  // swallows touches while it plays (keys: see MainActivity.dispatchKeyEvent)
        addView(content, LayoutParams(-1, -1))
    }

    fun play(fast: Boolean, done: () -> Unit) {
        onDone = done
        startedAt = android.os.SystemClock.uptimeMillis()
        val total = if (Jinky.theme == UiTheme.RETRO) retro(fast) else jinky(fast)
        postDelayed({ finish() }, total)
    }

    /** Any button skips the opening (after a moment, so the key that opened Jinky doesn't). */
    fun skip() {
        if (android.os.SystemClock.uptimeMillis() - startedAt > 400) finish()
    }

    private fun finish() {
        if (finishing) return
        finishing = true
        content.animate().scaleX(1.06f).scaleY(1.06f).setDuration(520).setInterpolator(exit).withLayer().start()
        animate().alpha(0f).setDuration(520).setInterpolator(standard).withEndAction {
            visibility = View.GONE
            onDone?.invoke()
            onDone = null
        }.start()
    }

    /** Returns how long the opening holds before dissolving (ms). */
    private fun jinky(fast: Boolean): Long {
        val k = if (fast) 0.45f else 1f
        fun t(ms: Int) = (ms * k).toLong()

        // A soft blue glow behind everything, pulsing once as the tile lands.
        val glow = View(context).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf((0x66 shl 24) or (Jinky.BLUE and 0xFFFFFF), (0x14 shl 24) or (Jinky.BLUE and 0xFFFFFF), 0)
                gradientRadius = 260 * d
            }
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
        }
        content.addView(glow, LayoutParams(dp(560), dp(560), Gravity.CENTER))

        // The tile (with the light sweep clipped inside it) and the wordmark, side by side.
        val tileSize = dp(92)
        val tileBox = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
            }
        }
        val tile = ImageView(context).apply { setImageResource(R.drawable.jinky_tile) }
        val shine = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF))
            rotation = 20f
            alpha = 0f
        }
        tileBox.addView(tile, LayoutParams(-1, -1))
        tileBox.addView(shine, LayoutParams(tileSize / 2, tileSize * 2, Gravity.CENTER))
        val word = ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark_white) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(tileBox, LinearLayout.LayoutParams(tileSize, tileSize))
            addView(word, LinearLayout.LayoutParams(dp(165), dp(55)).apply { marginStart = dp(20) })
        }
        content.addView(row, LayoutParams(-2, -2, Gravity.CENTER))
        content.clipChildren = false

        // The brand's pixel: lights up alone in the middle, then grows into the tile.
        val pixel = View(context).apply {
            setBackgroundColor(Jinky.BLUE)
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
        }
        content.addView(pixel, LayoutParams(dp(14), dp(14), Gravity.CENTER))

        tileBox.alpha = 0f
        word.alpha = 0f
        row.post {
            // Centre the tile alone first (the wordmark is still hidden), then glide it aside.
            val shift = (word.width + dp(20)) / 2f
            tileBox.translationX = shift
            tileBox.scaleX = 0.16f
            tileBox.scaleY = 0.16f
            word.translationX = -dp(26).toFloat()

            // Pixel -> tile: the pixel lights up, then swells and fades as the tile grows out of it.
            // (Chained: a second animate() on the same properties would cancel the first.)
            pixel.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(t(150)).setDuration(t(420))
                .setInterpolator(emphasized).withEndAction {
                    pixel.animate().scaleX(5.5f).scaleY(5.5f).alpha(0f).setStartDelay(t(80)).setDuration(t(420))
                        .setInterpolator(standard).start()
                }.start()
            tileBox.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(t(680)).setDuration(t(620))
                .setInterpolator(emphasized).withLayer().start()
            postDelayed({ UiSounds.play(UiSounds.Sound.BOOT) }, t(900))
            glow.animate().alpha(1f).scaleX(1.08f).scaleY(1.08f).setStartDelay(t(760)).setDuration(t(700))
                .setInterpolator(emphasized).withEndAction {
                    glow.animate().alpha(0.7f).scaleX(1f).scaleY(1f).setDuration(t(900)).setInterpolator(standard).start()
                }.start()
            // The tile glides aside, the wordmark comes in next to it.
            tileBox.animate().translationX(0f).setStartDelay(t(1350)).setDuration(t(650)).setInterpolator(emphasized).start()
            word.animate().alpha(1f).translationX(0f).setStartDelay(t(1480)).setDuration(t(620))
                .setInterpolator(emphasized).withLayer().start()
            // A light sweeps across the tile.
            shine.translationX = -tileSize.toFloat()
            shine.animate().alpha(1f).translationX(tileSize.toFloat()).setStartDelay(t(2050)).setDuration(t(700))
                .setInterpolator(standard).withEndAction { shine.alpha = 0f }.start()
            if (!fast) sparkles(tileBox, row, t(1900))
        }
        return t(3050)
    }

    /** A few small pixels drifting out of the tile and fading, the brand's motif. */
    private fun sparkles(tileBox: View, row: View, delay: Long) {
        val cx = row.left + tileBox.left + tileBox.width / 2f
        val cy = row.top + tileBox.top + tileBox.height / 2f
        val count = 9
        repeat(count) { i ->
            val size = dp(listOf(4, 5, 6, 8)[i % 4])
            val p = View(context).apply {
                setBackgroundColor(if (i % 3 == 0) Jinky.BLUE_SOFT else Jinky.BLUE)
                alpha = 0f
                x = cx - size / 2f
                y = cy - size / 2f
            }
            content.addView(p, LayoutParams(size, size))
            val angle = Math.toRadians(i * 360.0 / count + 15 * (i % 2))
            val distance = dp(70 + (i * 13) % 50)
            p.animate().alpha(0.9f).translationXBy((cos(angle) * distance).toFloat())
                .translationYBy((sin(angle) * distance).toFloat()).setStartDelay(delay + i * 35L).setDuration(560)
                .setInterpolator(emphasized).withEndAction {
                    p.animate().alpha(0f).setDuration(420).setInterpolator(standard).start()
                }.start()
        }
    }

    private fun retro(fast: Boolean): Long {
        UiSounds.play(UiSounds.Sound.BOOT)
        content.addView(View(context).apply { background = Jinky.scanlines(context, 12) }, LayoutParams(-1, -1))
        val text = TextView(context).apply {
            typeface = Jinky.font(context, 800)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            setTextColor(Jinky.TEXT)
            setShadowLayer(0.01f, 5 * d, 5 * d, 0xFF0E0A1C.toInt())
        }
        val pixel = View(context).apply { setBackgroundColor(0xFF4F8BFF.toInt()) }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(text)
            addView(pixel, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(6); bottomMargin = dp(14) })
        }, LayoutParams(-2, -2, Gravity.CENTER))
        // A blinking cursor (the pixel) before and while it types; then it stays lit.
        val word = "JINKY"
        val step = if (fast) 60L else 170L
        val typingStart = if (fast) 0L else 600L
        if (!fast) for (b in 0 until 3) {
            postDelayed({ pixel.alpha = 0f }, b * 200L)
            postDelayed({ pixel.alpha = 1f }, b * 200L + 100)
        }
        word.indices.forEach { i -> postDelayed({ text.text = word.substring(0, i + 1) }, typingStart + i * step) }
        return if (fast) 700L else typingStart + word.length * step + 1100L
    }
}
