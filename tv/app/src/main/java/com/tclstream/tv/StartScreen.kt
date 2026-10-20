package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Jinky screen shown whenever there is no picture yet: brand, what is happening in plain words,
 * and the connection steps (PC found -> video arriving -> picture on screen), so the user never
 * stares at a black screen without knowing why. Hidden as soon as the TV confirms a frame.
 */
class StartScreen(context: Context) : FrameLayout(context) {
    enum class Step { DONE, ACTIVE, PENDING }

    data class Model(
        val title: String,
        val subtitle: String,
        val steps: List<Pair<String, Step>>,
        val warning: Boolean = false,
    )

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val title = text(34f, Color.WHITE, bold = true)
    private val subtitle = text(18f, MUTED).apply {
        gravity = Gravity.CENTER
        maxWidth = dp(760)
        setLineSpacing(0f, 1.3f)
    }
    private val stepsBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(22), dp(28), dp(22))
        background = GradientDrawable().apply {
            setColor(SURFACE)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), LINE)
        }
    }

    init {
        setBackgroundColor(GROUND)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_jinky) }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark) },
                LinearLayout.LayoutParams(dp(97), dp(50)).apply { marginStart = dp(16) })
        }
        column.addView(brand, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(44) })
        column.addView(title, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
        column.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(32) })
        column.addView(stepsBox, LinearLayout.LayoutParams(dp(520), -2))
        addView(column, LayoutParams(-2, -2, Gravity.CENTER))

        addView(text(15f, HINT).apply { text = "Voltar no controle remoto abre o menu" },
            LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(32) })
    }

    private var last: Model? = null

    fun show(model: Model) {
        if (model == last) return
        last = model
        title.text = model.title
        subtitle.text = model.subtitle
        subtitle.setTextColor(if (model.warning) WARN else MUTED)
        stepsBox.removeAllViews()
        stepsBox.visibility = if (model.steps.isEmpty()) View.GONE else View.VISIBLE
        model.steps.forEachIndexed { i, (label, step) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val mark = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    when (step) {
                        Step.DONE -> setColor(DONE_BG)
                        Step.ACTIVE -> { setColor(Color.TRANSPARENT); setStroke(dp(3), ACCENT) }
                        Step.PENDING -> { setColor(Color.TRANSPARENT); setStroke(dp(2), LINE) }
                    }
                }
                text = if (step == Step.DONE) "✓" else ""
                setTextColor(DONE)
            }
            row.addView(mark, LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(text(19f, if (step == Step.PENDING) HINT else Color.WHITE, bold = step == Step.ACTIVE).apply { text = label },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
            stepsBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(16) })
        }
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun setShown(shown: Boolean) {
        if (shown == (visibility == View.VISIBLE)) return
        animate().cancel()
        if (shown) {
            alpha = 1f
            visibility = View.VISIBLE
        } else {
            animate().alpha(0f).setDuration(250).withEndAction { visibility = View.GONE }.start()
        }
    }

    companion object {
        // Jinky palette (same as the PC app and brand assets)
        const val GROUND = 0xFF0A0E14.toInt()
        const val SURFACE = 0xFF10161F.toInt()
        const val LINE = 0xFF263041.toInt()
        const val ACCENT = 0xFF4C93FF.toInt()
        const val MUTED = 0xFF9FAABB.toInt()
        const val HINT = 0xFF7D899C.toInt()
        const val WARN = 0xFFFFC979.toInt()
        const val DONE = 0xFF3DDC97.toInt()
        const val DONE_BG = 0xFF133A2A.toInt()
    }
}
