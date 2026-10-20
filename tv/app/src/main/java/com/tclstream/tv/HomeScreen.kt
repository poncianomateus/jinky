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

/** A PC as the home screen shows it. */
data class HomeHost(
    val id: Long,
    val name: String,
    val ip: String,
    val status: Status,
    val busyWith: String,
    val mac: ByteArray?,
) {
    enum class Status { READY, BUSY, OFFLINE }
}

/**
 * The console-style home screen: the PC in focus, its status, and what to do — play (console
 * mode, Steam Big Picture), show its desktop, or wake it up. Navigable with the remote and with a
 * controller (D-pad/stick to move, A/OK to choose).
 */
class HomeScreen(
    context: Context,
    private val onPlay: (HomeHost, desktop: Boolean) -> Unit,
    private val onWake: (HomeHost) -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val statusDot = View(context)
    private val statusText = text(17f, StartScreen.MUTED)
    private val title = text(46f, Color.WHITE, bold = true)
    private val subtitle = text(19f, StartScreen.MUTED)
    private val play = bigButton()
    private val desktop = smallButton("Área de trabalho")
    private val wake = smallButton("Ligar o PC")
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var host: HomeHost? = null

    init {
        setBackgroundColor(StartScreen.GROUND)
        val pad = dp(56)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(40), pad, dp(32))
        }
        // Brand
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_jinky) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark) },
                LinearLayout.LayoutParams(dp(62), dp(32)).apply { marginStart = dp(12) })
        })
        // PC + status
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        info.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
            addView(statusText)
        })
        info.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        info.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); bottomMargin = dp(32) })
        info.addView(actions, LinearLayout.LayoutParams(dp(560), -2))
        root.addView(info, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(36) })
        // Footer hints
        root.addView(text(15f, StartScreen.HINT).apply {
            text = "A ou OK: escolher   ·   Voltar: sair   ·   No jogo, segure View + Menu para abrir o menu"
        })
        addView(root, LayoutParams(-1, -1))

        play.setOnClickListener { host?.let { onPlay(it, false) } }
        desktop.setOnClickListener { host?.let { onPlay(it, true) } }
        wake.setOnClickListener { host?.let(onWake) }
    }

    /** Updates the screen; `searching` = no PC known yet. Keeps focus where the user put it. */
    fun show(h: HomeHost?, searching: Boolean, waking: Boolean) {
        val previous = host
        host = h
        if (h == null) {
            setStatus(StartScreen.ACCENT, if (searching) "Procurando" else "Nenhum PC")
            title.text = "Procurando seu PC"
            subtitle.text = "Abra o Jinky no computador. A TV e o PC precisam estar na mesma rede de casa."
            setActions(emptyList())
            return
        }
        title.text = h.name
        when (h.status) {
            HomeHost.Status.READY -> {
                setStatus(StartScreen.DONE, "Pronto para jogar")
                subtitle.text = h.ip
                setPlay("Jogar", "Modo console · abre o Steam Big Picture")
                setActions(listOf(play, desktop))
            }
            HomeHost.Status.BUSY -> {
                setStatus(StartScreen.WARN, "Jogando em ${h.busyWith.ifBlank { "outra TV" }}")
                subtitle.text = "O jogo continua de onde está."
                setPlay("Jogar aqui", "Traz o jogo de ${h.busyWith.ifBlank { "outra TV" }} para esta TV")
                setActions(listOf(play))
            }
            HomeHost.Status.OFFLINE -> {
                setStatus(StartScreen.HINT, if (waking) "Ligando…" else "Desligado ou dormindo")
                subtitle.text = when {
                    waking -> "Pode levar até um minuto. A TV continua procurando sozinha."
                    h.mac != null -> "Aperte Ligar o PC para acordá-lo pela rede."
                    else -> "Ligue o computador para jogar."
                }
                setActions(if (h.mac != null && !waking) listOf(wake) else emptyList())
            }
        }
        // First time this PC shows up (or its buttons changed): put focus on the main action.
        if (previous?.id != h.id || previous.status != h.status) actions.getChildAt(0)?.requestFocus()
    }

    private fun setStatus(color: Int, label: String) {
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statusText.text = label
    }

    private var current: List<View> = emptyList()

    private fun setActions(views: List<View>) {
        if (views == current) return
        current = views
        actions.removeAllViews()
        views.forEachIndexed { i, v ->
            actions.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(14) })
        }
    }

    // --- views ---

    private fun setPlay(label: String, detail: String) {
        // Two lines: the action, then a smaller, lighter description.
        val s = android.text.SpannableString("$label\n$detail")
        val start = label.length + 1
        s.setSpan(android.text.style.RelativeSizeSpan(0.72f), start, s.length, 0)
        s.setSpan(android.text.style.StyleSpan(Typeface.NORMAL), start, s.length, 0)
        play.text = s
    }

    private fun bigButton() = Button(context).apply {
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(28), dp(20), dp(28), dp(20))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(focusColors(0xFF06101F.toInt(), Color.WHITE))
        setLineSpacing(0f, 1.15f)
        background = states(StartScreen.ACCENT, 0xFF121821.toInt(), 18)
    }

    private fun smallButton(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(28), dp(16), dp(28), dp(16))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTextColor(focusColors(0xFF06101F.toInt(), Color.WHITE))
        background = states(StartScreen.ACCENT, 0xFF121821.toInt(), 16)
    }

    private fun states(focused: Int, normal: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(focused); cornerRadius = dp(radius).toFloat() })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(normal); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), 0xFF222C3B.toInt())
        })
    }

    private fun focusColors(focused: Int, normal: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(focused, normal))

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}
