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
    val updateVersion: Int = 0,  // TV app version the PC offers (0 = none)
    val updatePort: Int = 0,
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
    private val onUpdate: (HomeHost) -> Unit,
    private val onSettings: () -> Unit,
    private val onLibrary: (HomeHost) -> Unit,
) : TvPage(context) {

    private val statusDot = View(context)
    private val statusText = text(17f, StartScreen.MUTED)
    private val title = text(46f, Color.WHITE, bold = true)
    private val subtitle = text(19f, StartScreen.MUTED)
    private val play = bigButton()
    private val desktop = smallButton("Área de trabalho")
    private val library = smallButton("Biblioteca")
    // Biblioteca and Área de trabalho side by side under Jogar.
    private val secondary = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(library, LinearLayout.LayoutParams(0, -2, 1f))
        addView(desktop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
    }
    private val wake = smallButton("Ligar o PC")
    private val settings = smallButton("Configurações").apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }
    private val update = smallButton("Atualizar o Jinky").apply {
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }
    private val updateNote = text(16f, StartScreen.ACCENT)
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private var host: HomeHost? = null

    init {
        val root = pageRoot()
        // Brand on the left; "update available" and Configurações on the right (D-pad up from the
        // main buttons), the way TV apps keep their settings.
        root.addView(brandHeader().apply {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(update, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
                    addView(settings, LinearLayout.LayoutParams(-2, -2))
                })
                addView(updateNote, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            })
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
            text = "A ou OK: escolher   ·   Voltar: sair   ·   ↑ Configurações   ·   No jogo, View + Menu abre o menu" +
                "   ·   versão ${BuildConfig.VERSION_NAME}"
        })
        addView(root, LayoutParams(-1, -1))

        play.setOnClickListener { host?.let { onPlay(it, false) } }
        desktop.setOnClickListener { host?.let { onPlay(it, true) } }
        wake.setOnClickListener { host?.let(onWake) }
        update.setOnClickListener { host?.let(onUpdate) }
        settings.setOnClickListener { onSettings() }
        library.setOnClickListener { host?.let(onLibrary) }
    }

    /** Updates the screen; `searching` = no PC known yet. Keeps focus where the user put it. */
    /** [updateStatus]: progress/result of an update in progress, else null. */
    fun show(h: HomeHost?, searching: Boolean, waking: Boolean, updateStatus: String? = null) {
        updateNote.text = updateStatus ?: ""
        updateNote.visibility = if (updateStatus == null) View.GONE else View.VISIBLE
        showUpdate(h)
        val previous = host
        host = h
        if (h == null) {
            setStatus(StartScreen.ACCENT, if (searching) "Procurando" else "Nenhum PC")
            title.text = "Procurando seu PC"
            subtitle.text = "Abra o Jinky no computador. A TV e o PC precisam estar na mesma rede de casa."
            setActions(emptyList())
            settings.requestFocus()
            return
        }
        title.text = h.name
        when (h.status) {
            HomeHost.Status.READY -> {
                setStatus(StartScreen.DONE, "Pronto para jogar")
                subtitle.text = h.ip
                setPlay("Jogar", "Modo console · abre o Steam Big Picture")
                setActions(listOf(play, secondary))
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
        if (previous?.id != h.id || previous.status != h.status) (actions.getChildAt(0) ?: settings).requestFocus()
    }

    /** "Atualizar o Jinky" when the PC carries a newer TV app than this one. */
    private fun showUpdate(h: HomeHost?) {
        val v = h?.updateVersion ?: 0
        if (h == null || h.status == HomeHost.Status.OFFLINE || v <= BuildConfig.VERSION_CODE || h.updatePort == 0) {
            update.visibility = View.GONE
            return
        }
        update.text = "Atualizar o Jinky · ${v / 10000}.${v / 100 % 100}.${v % 100}"
        update.visibility = View.VISIBLE
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
        play.text = twoLines(label, detail)
    }

    override fun focusFirst() {
        (actions.getChildAt(0) ?: settings).requestFocus()
    }
}
