package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The menu over a game (View + Menu held on the controller, or Back on the remote), PS5 style: a
 * panel slides in from the right over the dimmed game, with what is playing, live stream numbers
 * and the actions.
 */
class GameMenu(
    context: Context,
    private val onResume: () -> Unit,
    private val onCloseGame: () -> Unit,
    private val onToggleStats: () -> Unit,
    private val onHome: () -> Unit,
    private val onExit: () -> Unit,
) : TvPage(context) {

    private val dim = View(context).apply { setBackgroundColor(0x99000000.toInt()) }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(32), dp(34), dp(32), dp(24))
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xF20D131D.toInt(), 0xFA0A0E16.toInt()))
    }
    private val playing = text(14f, 0xFF8FA0B8.toInt()).apply { letterSpacing = 0.12f }
    private val title = text(28f, Color.WHITE, bold = true).apply { maxLines = 2 }
    private val pc = text(16f, StartScreen.MUTED)
    private val stats = text(15f, Color.WHITE).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { setColor(0x1FFFFFFF); cornerRadius = dp(10).toFloat() }
    }
    private val resume = row()
    private val closeGame = row()
    private val statsRow = row()
    private val home = row()
    private val exit = row()

    init {
        visibility = View.GONE
        setBackgroundColor(Color.TRANSPARENT)
        addView(dim, LayoutParams(-1, -1))
        panel.addView(playing.apply { text = "JOGANDO AGORA" })
        panel.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(pc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        panel.addView(stats, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        panel.addView(View(context), LinearLayout.LayoutParams(1, dp(18)))
        listOf(resume, closeGame, statsRow, home, exit).forEachIndexed { i, b ->
            panel.addView(b, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) })
        }
        addView(panel, LayoutParams(dp(PANEL_W), -1, Gravity.END))

        resume.setOnClickListener { onResume() }
        closeGame.setOnClickListener { onCloseGame() }
        statsRow.setOnClickListener { onToggleStats() }
        home.setOnClickListener { onHome() }
        exit.setOnClickListener { onExit() }
    }

    /** Open as far as input is concerned (the panel may still be sliding out). */
    var isOpen = false
        private set

    /** [game]: what is playing; [canClose]: a game can be closed from here; [statsShown]: panel on. */
    fun open(game: String, pcName: String, streamLine: String, canClose: Boolean, statsShown: Boolean) {
        title.text = game
        pc.text = pcName
        stats.text = streamLine
        resume.text = twoLines("Continuar jogando", "")
        closeGame.text = twoLines("Fechar jogo", "Encerra o jogo no PC e volta ao início")
        closeGame.gone(!canClose)
        statsRow.text = twoLines("Desempenho na tela", if (statsShown) "Ligado" else "Desligado")
        home.text = twoLines("Voltar ao início", "O jogo continua aberto no PC")
        exit.text = twoLines("Sair do Jinky", "")
        if (isOpen) return
        isOpen = true
        visibility = View.VISIBLE
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(220).start()
        panel.translationX = dp(PANEL_W).toFloat()
        panel.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator(2f)).start()
        resume.requestFocus()
    }

    fun updateStream(streamLine: String) {
        stats.text = streamLine
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        dim.animate().alpha(0f).setDuration(180).start()
        panel.animate().translationX(dp(PANEL_W).toFloat()).setDuration(220).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!isOpen) visibility = View.GONE }.start()
    }

    private fun row() = Button(context).apply {
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(11), dp(22), dp(11))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(0f, 1.1f)
        setTextColor(focusColors(0xFF06101F.toInt(), Color.WHITE))
        background = states(Color.WHITE, 0x14FFFFFF, 14)
        stateListAnimator = null
    }

    companion object {
        private const val PANEL_W = 420
    }
}
