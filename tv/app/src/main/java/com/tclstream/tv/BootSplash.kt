package com.tclstream.tv

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The opening when Jinky starts, like a console's boot: the logo pops in, the wordmark slides out of
 * it, the signature sound plays, then everything fades into the home screen. The retro theme types
 * "JINKY" in pixels on a CRT instead. [fast] keeps it under a second.
 */
class BootSplash(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    init {
        setBackgroundColor(Jinky.INK)
        isClickable = true  // swallows input while it plays
    }

    fun play(fast: Boolean, done: () -> Unit) {
        val retro = Jinky.theme == UiTheme.RETRO
        if (retro) retro(fast) else jinky(fast)
        UiSounds.play(UiSounds.Sound.BOOT)
        val hold = if (fast) 650L else 1700L
        postDelayed({
            animate().alpha(0f).setDuration(if (fast) 220 else 380).withEndAction {
                visibility = View.GONE
                done()
            }.start()
        }, hold)
    }

    private fun jinky(fast: Boolean) {
        val speed = if (fast) 0.5f else 1f
        val glow = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf((0x55 shl 24) or (Jinky.BLUE and 0xFFFFFF), 0)
                gradientRadius = 220 * d
            }
            alpha = 0f
        }
        addView(glow, LayoutParams(dp(440), dp(440), Gravity.CENTER))
        val tile = ImageView(context).apply { setImageResource(R.drawable.jinky_tile) }
        val word = ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark_white) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tile, LinearLayout.LayoutParams(dp(84), dp(84)))
            addView(word, LinearLayout.LayoutParams(dp(150), dp(50)).apply { marginStart = dp(18) })
        }
        addView(row, LayoutParams(-2, -2, Gravity.CENTER))
        tile.scaleX = 0.4f; tile.scaleY = 0.4f; tile.alpha = 0f
        tile.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration((520 * speed).toLong())
            .setInterpolator(OvershootInterpolator(2.2f)).start()
        word.alpha = 0f
        word.translationX = -dp(40).toFloat()
        word.animate().alpha(1f).translationX(0f).setStartDelay((300 * speed).toLong()).setDuration((520 * speed).toLong())
            .setInterpolator(DecelerateInterpolator(2f)).start()
        glow.animate().alpha(1f).setStartDelay((200 * speed).toLong()).setDuration((700 * speed).toLong()).start()
    }

    private fun retro(fast: Boolean) {
        addView(View(context).apply { background = Jinky.scanlines(context, 12) }, LayoutParams(-1, -1))
        val text = TextView(context).apply {
            typeface = Jinky.font(context, 800)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            setTextColor(Jinky.TEXT)
            setShadowLayer(0.01f, 5 * d, 5 * d, 0xFF0E0A1C.toInt())
        }
        val pixel = View(context).apply { setBackgroundColor(0xFF4F8BFF.toInt()); alpha = 0f }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(text)
            addView(pixel, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(6); bottomMargin = dp(14) })
        }, LayoutParams(-2, -2, Gravity.CENTER))
        val word = "JINKY"
        val step = if (fast) 60L else 120L
        word.indices.forEach { i -> postDelayed({ text.text = word.substring(0, i + 1) }, i * step) }
        postDelayed({ pixel.alpha = 1f }, word.length * step + 60)
    }
}
