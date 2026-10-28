package com.tclstream.tv

import android.content.Context
import android.graphics.Color
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

/**
 * The opening when Jinky starts, like a console's boot (about 5 s): specks of light circle in the
 * dark and spiral into the centre, meeting in a flash with a shock ring (the signature sound); the
 * Jinky tile is born from it, glides aside as the wordmark comes in, a light sweeps across the
 * tile while sparks fade away, and everything pushes towards you into the home screen. The retro theme types "JINKY" on a CRT
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
        // Like a console handing over to its home: the logo pushes towards you as it dissolves.
        content.animate().scaleX(1.12f).scaleY(1.12f).setDuration(700).setInterpolator(exit).withLayer().start()
        animate().alpha(0f).setDuration(700).setInterpolator(standard).withEndAction {
            visibility = View.GONE
            onDone?.invoke()
            onDone = null
        }.start()
    }

    /** Returns how long the opening holds before dissolving (ms). */
    private fun jinky(fast: Boolean): Long {
        val k = if (fast) 0.45f else 1f
        fun t(ms: Int) = (ms * k).toLong()

        // Specks of light spiral into the centre and meet in a flash (IntroField); the tile is
        // born from that flash, the wordmark joins it, a light sweeps across, sparks fade away.
        content.addView(IntroField(context, k), LayoutParams(-1, -1))

        // A soft blue glow behind the tile, swelling as it is born, then breathing.
        val glow = View(context).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf((0x70 shl 24) or (Jinky.BLUE and 0xFFFFFF), (0x18 shl 24) or (Jinky.BLUE and 0xFFFFFF), 0)
                gradientRadius = 280 * d
            }
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
        }
        content.addView(glow, LayoutParams(dp(600), dp(600), Gravity.CENTER))

        // The tile (with the light sweep clipped inside it) and the wordmark, side by side.
        val tileSize = dp(96)
        val tileBox = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(23).toFloat())
            }
        }
        val tile = ImageView(context).apply { setImageResource(R.drawable.jinky_tile) }
        val shine = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00FFFFFF, 0x77FFFFFF, 0x00FFFFFF))
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
            addView(word, LinearLayout.LayoutParams(dp(172), dp(57)).apply { marginStart = dp(22) })
        }
        content.addView(row, LayoutParams(-2, -2, Gravity.CENTER))
        content.clipChildren = false

        // The brand's pixel: it lights up in the flash and the tile grows out of it.
        val pixel = View(context).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
        }
        content.addView(pixel, LayoutParams(dp(14), dp(14), Gravity.CENTER))

        tileBox.alpha = 0f
        word.alpha = 0f
        row.post {
            // Centre the tile alone first (the wordmark is still hidden), then glide it aside.
            val shift = (word.width + dp(22)) / 2f
            tileBox.translationX = shift
            tileBox.scaleX = 0.2f
            tileBox.scaleY = 0.2f
            word.translationX = -dp(30).toFloat()

            val flash = IntroField.FLASH_MS
            pixel.animate().alpha(1f).scaleX(1.4f).scaleY(1.4f).setStartDelay(t(flash - 180)).setDuration(t(220))
                .setInterpolator(emphasized).withEndAction {
                    pixel.animate().scaleX(6f).scaleY(6f).alpha(0f).setStartDelay(0).setDuration(t(420)).setInterpolator(standard).start()
                }.start()
            postDelayed({ UiSounds.play(UiSounds.Sound.BOOT) }, t(flash))
            tileBox.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(t(flash + 60)).setDuration(t(780))
                .setInterpolator(android.view.animation.OvershootInterpolator(1.15f)).withLayer().start()
            glow.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setStartDelay(t(flash)).setDuration(t(800))
                .setInterpolator(emphasized).withEndAction {
                    glow.animate().alpha(0.65f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(t(1200)).setInterpolator(standard).start()
                }.start()
            // The tile glides aside, the wordmark comes in next to it.
            tileBox.animate().translationX(0f).setStartDelay(t(flash + 1000)).setDuration(t(720)).setInterpolator(emphasized).start()
            word.animate().alpha(1f).translationX(0f).setStartDelay(t(flash + 1150)).setDuration(t(720))
                .setInterpolator(emphasized).withLayer().start()
            // A light sweeps across the tile.
            shine.translationX = -tileSize.toFloat()
            shine.animate().alpha(1f).translationX(tileSize.toFloat()).setStartDelay(t(flash + 1750)).setDuration(t(760))
                .setInterpolator(standard).withEndAction { shine.alpha = 0f }.start()
        }
        return t(IntroField.FLASH_MS + 3000)
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
