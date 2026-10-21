package com.tclstream.tv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Shown while a game chosen on the TV is starting on the PC: the game's artwork and logo with a
 * quiet loading pulse, instead of the PC's desktop, launchers or Steam windows. The picture is
 * revealed when the PC says the game is on screen.
 */
class LaunchScreen(context: Context) : TvPage(context) {

    private val hero = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val logo = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        maxHeight = dp(150)
        maxWidth = dp(520)
    }
    private val title = text(46f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
    private val status = text(19f, 0xFFD5DCE6.toInt()).apply { gravity = Gravity.CENTER }
    private val dots = List(3) { View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        alpha = 0.25f
    } }
    private val pulses = mutableListOf<ValueAnimator>()
    private var gameId = -1

    init {
        addView(hero, LayoutParams(-1, -1))
        addView(View(context).apply { setBackgroundColor(0xB8070A0F.toInt()) }, LayoutParams(-1, -1))
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(logo, LinearLayout.LayoutParams(-2, -2))
            addView(title, LinearLayout.LayoutParams(dp(760), -2))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                dots.forEachIndexed { i, d ->
                    addView(d, LinearLayout.LayoutParams(dp(10), dp(10)).apply { if (i > 0) marginStart = dp(12) })
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(40) })
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        }
        addView(center, LayoutParams(-2, -2, Gravity.CENTER))
    }

    /** Starts showing [game] from the PC at [pcIp]. */
    fun show(game: LibraryGame, pcIp: String, port: Int) {
        gameId = game.id
        title.text = game.name
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        hero.setImageDrawable(null)
        status.text = "Iniciando no PC…"
        ArtLoader.load(pcIp, port, game.id, "hero", 1) { bmp ->
            if (gameId != game.id || bmp == null) return@load
            hero.setImageBitmap(bmp)
            hero.alpha = 0f
            hero.scaleX = 1.08f; hero.scaleY = 1.08f
            hero.animate().alpha(1f).setDuration(500).start()
            // A very slow drift keeps the screen alive while the game loads.
            hero.animate().scaleX(1f).scaleY(1f).setDuration(9000).setInterpolator(DecelerateInterpolator()).start()
        }
        ArtLoader.load(pcIp, port, game.id, "logo", 1) { bmp ->
            if (gameId != game.id || bmp == null) return@load
            logo.setImageBitmap(bmp)
            logo.alpha = 0f
            logo.visibility = View.VISIBLE
            title.visibility = View.GONE
            logo.animate().alpha(1f).setDuration(400).start()
        }
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(250).start()
        startPulse()
    }

    fun setStatus(text: String) {
        status.text = text
    }

    /** Fades out to reveal the game. */
    fun hide() {
        if (visibility != View.VISIBLE) return
        gameId = -1
        animate().alpha(0f).setDuration(350).withEndAction {
            visibility = View.GONE
            stopPulse()
        }.start()
    }

    val showing: Boolean get() = visibility == View.VISIBLE && gameId != -1

    private fun startPulse() {
        stopPulse()
        dots.forEachIndexed { i, d ->
            pulses += ObjectAnimator.ofFloat(d, View.ALPHA, 0.25f, 1f, 0.25f).apply {
                duration = 1200
                startDelay = i * 200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private fun stopPulse() {
        pulses.forEach { it.cancel() }
        pulses.clear()
        dots.forEach { it.alpha = 0.25f }
    }
}
