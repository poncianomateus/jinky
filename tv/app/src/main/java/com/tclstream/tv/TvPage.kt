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
 * Shared look of the Jinky TV screens (home, settings, help, library): dark ground, brand header,
 * big focusable buttons that light up in the accent color. Navigable with the remote and with a
 * controller (D-pad/stick to move, A/OK to choose).
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
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_jinky) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark) },
            LinearLayout.LayoutParams(dp(62), dp(32)).apply { marginStart = dp(12) })
        if (page != null) {
            addView(text(22f, StartScreen.MUTED).apply { text = "·  $page" },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
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
        setPadding(dp(28), dp(20), dp(28), dp(20))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(focusColors(0xFF06101F.toInt(), Color.WHITE))
        setLineSpacing(0f, 1.15f)
        background = states(StartScreen.ACCENT, 0xFF121821.toInt(), 18)
    }

    protected fun smallButton(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(28), dp(16), dp(28), dp(16))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setLineSpacing(0f, 1.1f)
        setTextColor(focusColors(0xFF06101F.toInt(), Color.WHITE))
        background = states(StartScreen.ACCENT, 0xFF121821.toInt(), 16)
    }

    protected fun states(focused: Int, normal: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(focused); cornerRadius = dp(radius).toFloat() })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(normal); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), 0xFF222C3B.toInt())
        })
    }

    protected fun focusColors(focused: Int, normal: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(focused, normal))

    protected fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Puts focus on the first button when the page is opened. */
    open fun focusFirst() {}

    protected fun View.gone(gone: Boolean) { visibility = if (gone) View.GONE else View.VISIBLE }
}
