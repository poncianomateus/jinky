package com.tclstream.tv

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Desempenho na tela: Compacto": one small pill in the corner while playing, like the Steam Deck's
 * first performance level: a health dot, frames per second shown, PC-to-TV latency, the video
 * quality arriving and the network round trip. The dot turns amber/red when something suffers.
 */
class PerfHud(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val dot = View(context)
    private val fps = cell()
    private val latency = cell()
    private val quality = cell()
    private val ping = cell()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(5), dp(12), dp(5))
        background = Jinky.rounded(0xB80B0E14.toInt(), dp(12).toFloat())
        addView(dot, LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
        for (v in listOf(fps, latency, quality, ping)) addView(v, LayoutParams(-2, -2).apply { if (childCount > 1) marginStart = dp(12) })
        isFocusable = false
    }

    private fun cell() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(0xFFE8EAF0.toInt())
        typeface = Jinky.mono(context, 600)
    }

    /** NaN / 0 = not known yet. */
    fun update(framesPerSecond: Double, latencyMs: Double, mbps: Double, rttMs: Double) {
        fps.text = if (framesPerSecond.isNaN()) "— fps" else "${framesPerSecond.toInt()} fps"
        latency.text = if (latencyMs.isNaN()) "— ms" else "${latencyMs.toInt()} ms"
        quality.text = if (mbps > 0) String.format(java.util.Locale.US, "%.0f Mbit/s", mbps) else "— Mbit/s"
        ping.text = if (rttMs.isNaN()) "" else String.format(java.util.Locale.US, "ping %.0f", rttMs)
        ping.visibility = if (rttMs.isNaN()) View.GONE else View.VISIBLE
        val color = when {
            framesPerSecond.isNaN() -> Jinky.MUTED
            framesPerSecond < 45 || latencyMs > 120 || rttMs > 30 -> Jinky.RED
            framesPerSecond < 56 || latencyMs > 85 || rttMs > 12 -> Jinky.AMBER
            else -> Jinky.GREEN
        }
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        fps.setTextColor(if (!framesPerSecond.isNaN() && framesPerSecond < 56) Jinky.AMBER else 0xFFE8EAF0.toInt())
    }
}
