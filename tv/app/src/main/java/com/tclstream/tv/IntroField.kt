package com.tclstream.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The opening's light show, drawn frame by frame: specks of light drifting in the dark slowly
 * start to circle the centre, spiral in faster and faster with light trails, and meet in a flash
 * with a shock ring; then sparks fly out of it and fade while the Jinky tile is born there
 * (BootSplash). [k] scales the timeline (a short version for a theme change).
 *
 * Timeline (ms, k = 1): 0-900 specks appear, 900-1900 spiral in, 1900 flash, 1900-3600 sparks.
 */
class IntroField(context: Context, private val k: Float = 1f) : View(context) {
    private val d = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis()
    private val rnd = Random(7)

    // One soft dot, drawn scaled for every speck (much cheaper than a gradient per speck).
    private val sprite: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(32f, 32f, 32f, intArrayOf(Color.WHITE, 0x88FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(32f, 32f, 32f, p)
    }

    private class Speck(val angle: Float, val radius: Float, val size: Float, val spin: Float, val delay: Float, val tint: Int)
    private class Spark(val angle: Float, val speed: Float, val size: Float, val life: Float, val tint: Int)

    private val blue get() = Jinky.BLUE
    private val soft get() = Jinky.BLUE_SOFT
    private val specks = List(150) {
        Speck(
            angle = rnd.nextFloat() * 2 * PI.toFloat(),
            radius = 0.25f + rnd.nextFloat() * 0.85f,  // of the screen's half-diagonal
            size = (2f + rnd.nextFloat() * 5f),
            spin = 0.6f + rnd.nextFloat() * 0.8f,
            delay = rnd.nextFloat() * 0.35f,
            tint = when (it % 5) { 0 -> 0; 1, 2 -> 1; else -> 2 },
        )
    }
    private val sparks = List(46) {
        Spark(
            angle = rnd.nextFloat() * 2 * PI.toFloat(),
            speed = 0.25f + rnd.nextFloat() * 0.75f,
            size = 2f + rnd.nextFloat() * 4f,
            life = 0.6f + rnd.nextFloat() * 0.4f,
            tint = when (it % 4) { 0 -> 0; 1 -> 1; else -> 2 },
        )
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val flash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private fun color(tint: Int) = when (tint) { 1 -> blue; 2 -> soft; else -> Color.WHITE }
    // The three tints, made once (not one filter per speck per frame).
    private val filters = Array(3) { android.graphics.PorterDuffColorFilter(color(it), android.graphics.PorterDuff.Mode.MULTIPLY) }

    /** Ease in (slow start, fast end). */
    private fun easeIn(x: Float) = x.coerceIn(0f, 1f).pow(2.4f)
    private fun easeOut(x: Float) = 1f - (1f - x.coerceIn(0f, 1f)).pow(3f)

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / k  // ms on the k = 1 timeline
        val cx = width / 2f
        val cy = height / 2f
        val reach = kotlin.math.hypot(cx, cy)

        // --- specks: appear, drift around, then spiral into the centre ---
        if (t < 2000) {
            val converge = easeIn((t - 900f) / 1000f)  // 0 until 900 ms, 1 at 1900 ms
            for (s in specks) {
                val appear = ((t / 900f) - s.delay).coerceIn(0f, 1f)
                if (appear <= 0f) continue
                val turn = s.spin * (t / 1000f) * (0.35f + 5.5f * converge * converge)
                val r = s.radius * reach * (1f - converge)
                val a = s.angle + turn
                val x = cx + cos(a) * r
                val y = cy + sin(a) * r * 0.62f  // a slight tilt: an ellipse, like a disc seen at an angle
                val size = s.size * d * (1f + 0.6f * converge)
                val alpha = (appear * (0.35f + 0.65f * converge)).coerceIn(0f, 1f)
                val c = color(s.tint)
                if (converge > 0.05f) {
                    // A light trail behind it, longer as it speeds up.
                    val back = a - 0.12f * converge * s.spin * 2.2f
                    val rb = r * (1f + 0.08f * converge)
                    trail.color = (c and 0xFFFFFF) or ((alpha * 120).toInt() shl 24)
                    trail.strokeWidth = size * 0.45f
                    canvas.drawLine(cx + cos(back) * rb, cy + sin(back) * rb * 0.62f, x, y, trail)
                }
                dot.colorFilter = filters[s.tint]
                dot.alpha = (alpha * 255).toInt()
                box.set(x - size, y - size, x + size, y + size)
                canvas.drawBitmap(sprite, null, box, dot)
            }
        }

        // --- the flash and the shock ring where they meet ---
        if (t in 1800f..2700f) {
            val f = ((t - 1800f) / 900f).coerceIn(0f, 1f)
            val bloom = reach * (0.12f + 0.55f * easeOut(f))
            flash.shader = RadialGradient(cx, cy, max(1f, bloom),
                intArrayOf(Color.argb((220 * (1f - f)).toInt(), 255, 255, 255),
                    (soft and 0xFFFFFF) or ((110 * (1f - f)).toInt() shl 24), 0x00000000), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, bloom, flash)
            val rr = reach * 0.9f * easeOut(f)
            ring.color = (soft and 0xFFFFFF) or ((200 * (1f - f)).toInt() shl 24)
            ring.strokeWidth = (6f * (1f - f) + 1f) * d
            canvas.drawOval(cx - rr, cy - rr * 0.62f, cx + rr, cy + rr * 0.62f, ring)
        }

        // --- sparks flying out of the flash, slowing down and fading ---
        if (t in 1900f..3800f) {
            val since = (t - 1900f) / 1900f
            for (s in sparks) {
                val life = (since / s.life).coerceIn(0f, 1f)
                if (life >= 1f) continue
                val dist = reach * 0.55f * s.speed * easeOut(life)
                val x = cx + cos(s.angle) * dist
                val y = cy + sin(s.angle) * dist * 0.62f
                val size = s.size * d * (1f - 0.5f * life)
                dot.colorFilter = filters[s.tint]
                dot.alpha = ((1f - life) * 230).toInt()
                box.set(x - size, y - size, x + size, y + size)
                canvas.drawBitmap(sprite, null, box, dot)
            }
        }
        if (t < 3900) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sprite.recycle()
    }

    companion object {
        /** When the flash happens (ms, k = 1): the tile is born there. */
        const val FLASH_MS = 1900
    }
}
