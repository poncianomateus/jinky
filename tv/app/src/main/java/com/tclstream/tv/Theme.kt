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
/** A look for the whole TV app; chosen in Configurações › Temas e sons (the app restarts its screens). */
enum class UiTheme(
    val key: String, val label: String,
    val ink: Int, val surface: Int, val raised: Int, val line: Int,
    val text: Int, val soft: Int, val muted: Int, val dim: Int,
    val accent: Int, val accentSoft: Int, val accentDeep: Int,
    val corners: Float,      // multiplies every corner radius (retro: almost square)
    val scanlines: Int,      // strength of the old-TV lines (0 = none)
    val pixelFont: Boolean,  // headings in a pixel face
    val art: Boolean,        // game artwork behind the screens
) {
    JINKY("jinky", "Jinky", 0xFF07090D.toInt(), 0xFF10141F.toInt(), 0xFF171C2A.toInt(), 0xFF23283A.toInt(),
        0xFFF5F7FC.toInt(), 0xFFC8CFDF.toInt(), 0xFFA6AEC2.toInt(), 0xFF7F889E.toInt(),
        0xFF4F8BFF.toInt(), 0xFF8DB3FF.toInt(), 0xFF2856D9.toInt(), 1f, 6, false, true),
    MIDNIGHT("midnight", "Meia-noite", 0xFF000000.toInt(), 0xFF0B0B0D.toInt(), 0xFF151518.toInt(), 0xFF26262B.toInt(),
        0xFFF5F5F7.toInt(), 0xFFC9C9CF.toInt(), 0xFFA0A0A8.toInt(), 0xFF7A7A82.toInt(),
        0xFF4F8BFF.toInt(), 0xFF8DB3FF.toInt(), 0xFF2856D9.toInt(), 1f, 0, false, false),
    RETRO("retro", "Retrô 32-bit", 0xFF1A1430.toInt(), 0xFF241B45.toInt(), 0xFF2E2357.toInt(), 0xFF3A2E66.toInt(),
        0xFFF2EEFF.toInt(), 0xFFD3CCF2.toInt(), 0xFFA99FD6.toInt(), 0xFF857BB5.toInt(),
        0xFF7CF2C8.toInt(), 0xFFB8FFE6.toInt(), 0xFF2FA98A.toInt(), 0.15f, 26, true, true);

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: JINKY
    }
}

object Jinky {
    /** The theme in use; set before the screens are built. */
    var theme = UiTheme.JINKY
    /** Game artwork behind the home screen and pages (Configurações; Meia-noite never shows it). */
    var artBackgrounds = true
    /** Old-TV lines: 0 off, 1 subtle, 2 strong. */
    var scanlineLevel = 1

    val INK get() = theme.ink
    val SURFACE get() = theme.surface
    val RAISED get() = theme.raised
    val LINE get() = theme.line
    val TEXT get() = theme.text
    val SOFT get() = theme.soft
    val MUTED get() = theme.muted
    val DIM get() = theme.dim
    val BLUE get() = theme.accent
    val BLUE_SOFT get() = theme.accentSoft
    val BLUE_DEEP get() = theme.accentDeep
    const val AMBER = 0xFFFFB547.toInt()
    const val VIOLET = 0xFF9B7BFF.toInt()
    const val GREEN = 0xFF3DDC97.toInt()
    const val RED = 0xFFFF8A8A.toInt()
    const val STEEL = 0xFFB7C0D4.toInt()
    const val MINT = 0xFF7CF2C8.toInt()
    const val PINK = 0xFFFF8AD8.toInt()
    /** Card over artwork. */
    val GLASS get() = (0xE0 shl 24) or (theme.surface and 0xFFFFFF)

    /** The ground colour at [alpha] (0-255), for scrims over artwork. */
    fun ink(alpha: Int) = (alpha shl 24) or (theme.ink and 0xFFFFFF)

    /** The card colour at [alpha] (0-255). */
    fun surface(alpha: Int) = (alpha shl 24) or (theme.surface and 0xFFFFFF)

    /** Whether game artwork shows behind the screens. */
    val showArt get() = theme.art && artBackgrounds

    private val fonts = HashMap<String, Typeface>()

    /** Montserrat at [weight] (500 medium … 800 extra bold); the retro theme's headings are pixels. */
    fun font(context: Context, weight: Int = 600): Typeface =
        if (theme.pixelFont && weight >= 800) pixel(context) else variable(context, "fonts/Montserrat.ttf", weight)

    private fun pixel(context: Context): Typeface = synchronized(fonts) {
        fonts.getOrPut("pixel") {
            try { Typeface.createFromAsset(context.assets, "fonts/Silkscreen-Bold.ttf") } catch (e: Exception) { Typeface.DEFAULT_BOLD }
        }
    }

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
        cornerRadius = radius * theme.corners
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    /** Thin lines of an old TV over artwork (the retro touch), as a repeating texture. */
    fun scanlines(context: Context, alpha: Int = 6): Drawable {
        val step = (if (theme.pixelFont) 3 else 2) * context.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap(1, step.toInt().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        // The theme sets the strength; the user's setting scales it (off, subtle, strong).
        val base = theme.scanlines * alpha / 6
        val a = when (scanlineLevel) { 0 -> 0; 2 -> base * 2; else -> base }.coerceIn(0, 90)
        // Retro: dark lines like a CRT; otherwise faint light ones.
        val dark = theme.pixelFont
        Canvas(bmp).drawRect(0f, 0f, 1f, if (dark) 1.5f * context.resources.displayMetrics.density else 1f,
            Paint().apply { color = if (dark) Color.argb(a, 0, 0, 0) else Color.argb(a, 255, 255, 255) })
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

    /** "esta semana" once the PC has a week of record, else how far back it goes. */
    fun weekLabel(days: Int): String = when {
        days >= 7 -> "esta semana"
        days == 1 -> "desde ontem"
        else -> "nos últimos $days dias"
    }

    /** Time left for a download: "faltam 12 min", "faltam 1 h 20 min" ("" when unknown). */
    fun eta(seconds: Int): String = when {
        seconds < 0 -> ""
        seconds < 60 -> "falta menos de 1 min"
        seconds < 3600 -> "faltam ${(seconds + 59) / 60} min"
        else -> {
            val m = (seconds + 59) / 60
            if (m % 60 == 0) "faltam ${m / 60} h" else "faltam ${m / 60} h ${m % 60} min"
        }
    }

    /** "Baixando no PC · 45% · faltam 12 min" / "Atualizando no PC · …" for a game being downloaded. */
    fun downloadText(g: LibraryGame, prefix: Boolean = true): String {
        val what = if (g.updating) "Atualizando" else "Baixando"
        val pct = "${(g.progress * 100).toInt()}%"
        val eta = eta(g.eta)
        return listOf(if (prefix) "$what no PC" else what, pct, eta).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
