package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared look of the Jinky TV screens (home, settings, help, library): ink ground, the Jinky logo,
 * big focusable buttons that turn white when focused, like a console. Navigable with the remote
 * and with a controller (D-pad/stick to move, A/OK to choose).
 */
abstract class TvPage(context: Context) : FrameLayout(context) {

    protected val density = resources.displayMetrics.density
    protected fun dp(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(StartScreen.GROUND)
    }

    /** Page column with the safe-area padding every screen uses. */
    protected fun pageRoot() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(56), dp(40), dp(56), dp(32))
    }

    /** Logo + wordmark, optionally followed by the page name ("jinky · Configurações"). */
    protected fun brandHeader(page: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply { setImageResource(R.drawable.jinky_tile) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark_white) },
            LinearLayout.LayoutParams(dp(57), dp(19)).apply { marginStart = dp(9) })
        if (page != null) {
            addView(text(14f, Jinky.MUTED, bold = true).apply { text = page },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18) })
        }
    }

    /** "Label" on the first line, a smaller, lighter description on the second. */
    protected fun twoLines(label: String, detail: String): CharSequence {
        if (detail.isEmpty()) return label
        val s = android.text.SpannableString("$label\n$detail")
        val start = label.length + 1
        s.setSpan(android.text.style.RelativeSizeSpan(0.72f), start, s.length, 0)
        s.setSpan(android.text.style.StyleSpan(Typeface.NORMAL), start, s.length, 0)
        return s
    }

    protected fun bigButton() = Button(context).apply {
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Jinky.font(context, 700)
        setTextColor(focusColors(Jinky.INK, Jinky.TEXT))
        setLineSpacing(0f, 1.2f)
        background = states(Jinky.TEXT, Jinky.SURFACE, 12)
        stateListAnimator = null
    }

    protected fun smallButton(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Jinky.font(context, 700)
        setLineSpacing(0f, 1.15f)
        setTextColor(focusColors(Jinky.INK, Jinky.TEXT))
        background = states(Jinky.TEXT, Jinky.SURFACE, 99)
        stateListAnimator = null
    }

    protected fun states(focused: Int, normal: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(focused); cornerRadius = dp(radius).toFloat() })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(normal); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Jinky.LINE)
        })
    }

    protected fun focusColors(focused: Int, normal: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(focused, normal))

    protected fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        // The retro theme's pixel face runs wide: a little smaller keeps words whole.
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (bold && Jinky.theme.pixelFont) sp * 0.74f else sp)
        setTextColor(color)
        typeface = Jinky.font(context, if (bold) 800 else 500)
    }

    /** Numbers (clock, hours, percentages) in the monospaced face. */
    protected fun mono(sp: Float, color: Int, weight: Int = 500) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Jinky.mono(context, weight)
    }

    /** Puts focus on the first button when the page is opened. */
    open fun focusFirst() {}

    protected fun View.gone(gone: Boolean) { visibility = if (gone) View.GONE else View.VISIBLE }
}
