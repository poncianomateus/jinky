package com.tclstream.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Jinky look (design v2): ink ground, Montserrat for words, JetBrains Mono for numbers, the
 * blue square pixel of the logo as the recurring mark, white focus like a console.
 */
object Jinky {
    const val INK = 0xFF07090D.toInt()
    const val SURFACE = 0xFF10141F.toInt()
    const val RAISED = 0xFF171C2A.toInt()
    const val LINE = 0xFF23283A.toInt()
    const val TEXT = 0xFFF5F7FC.toInt()
    const val SOFT = 0xFFC8CFDF.toInt()
    const val MUTED = 0xFFA6AEC2.toInt()
    const val DIM = 0xFF7F889E.toInt()
    const val BLUE = 0xFF4F8BFF.toInt()
    const val BLUE_SOFT = 0xFF8DB3FF.toInt()
    const val BLUE_DEEP = 0xFF2856D9.toInt()
    const val AMBER = 0xFFFFB547.toInt()
    const val VIOLET = 0xFF9B7BFF.toInt()
    const val GREEN = 0xFF3DDC97.toInt()
    const val RED = 0xFFFF8A8A.toInt()
    const val STEEL = 0xFFB7C0D4.toInt()
    const val MINT = 0xFF7CF2C8.toInt()
    const val PINK = 0xFFFF8AD8.toInt()
    /** Card over artwork. */
    const val GLASS = 0xE010141F.toInt()

    private val fonts = HashMap<String, Typeface>()

    /** Montserrat at [weight] (500 medium … 800 extra bold). */
    fun font(context: Context, weight: Int = 600): Typeface = variable(context, "fonts/Montserrat.ttf", weight)

    /** JetBrains Mono at [weight]: clock, latency, hours, percentages. */
    fun mono(context: Context, weight: Int = 500): Typeface = variable(context, "fonts/JetBrainsMono.ttf", weight)

    private fun variable(context: Context, path: String, weight: Int): Typeface = synchronized(fonts) {
        fonts.getOrPut("$path#$weight") {
            try {
                if (Build.VERSION.SDK_INT >= 26)
                    Typeface.Builder(context.assets, path).setFontVariationSettings("'wght' $weight").build()
                else Typeface.createFromAsset(context.assets, path)
            } catch (e: Exception) {
                Typeface.create("sans-serif-medium", if (weight >= 700) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    /** Thin lines of an old TV over artwork (the retro touch), as a repeating texture. */
    fun scanlines(context: Context, alpha: Int = 6): Drawable {
        val step = (2 * context.resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(1, step, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.argb(alpha, 255, 255, 255) })
        return BitmapDrawable(context.resources, bmp).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    /** True when a PlayStation controller is connected: hints then show ✕ ○ □ △ instead of A B X Y. */
    fun playStationPad(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        Build.VERSION.SDK_INT >= 19 && d.vendorId == 0x054C &&
            (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }

    enum class Button(val xbox: String, val ps: String, val color: Int) {
        OK("A", "✕", BLUE_SOFT), BACK("B", "○", RED), PIN("X", "□", PINK), SEARCH("Y", "△", MINT)
    }

    /** A controller hint for the bottom bar: the button's glyph in a little circle, then what it does. */
    fun hint(context: Context, button: Button, label: String): LinearLayout {
        val d = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = if (playStationPad()) button.ps else button.xbox
                gravity = Gravity.CENTER
                setTextColor(button.color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
                typeface = font(context, 800)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(LINE) }
            }, LinearLayout.LayoutParams((13 * d).toInt(), (13 * d).toInt()))
            addView(TextView(context).apply {
                text = label
                setTextColor(SOFT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = font(context, 600)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (5 * d).toInt() })
        }
    }

    /** The logo's blue square, used as the "you are here" mark next to the active tab. */
    fun pixel(context: Context, sizeDp: Float = 4.5f, color: Int = BLUE) = View(context).apply {
        setBackgroundColor(color)
        tag = sizeDp
    }

    /** Battery of the first controller that reports one, in % (Android 12+), else null. */
    fun padBattery(): Int? {
        if (Build.VERSION.SDK_INT < 31) return null
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if ((d.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) continue
            val b = d.batteryState
            if (b.isPresent && !b.capacity.isNaN()) return (b.capacity * 100).toInt()
        }
        return null
    }

    fun hours(minutes: Int): String = when {
        minutes <= 0 -> ""
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h"
    }
}
