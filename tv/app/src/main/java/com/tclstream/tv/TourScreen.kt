package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The first-run tour: a few pages that show how Jinky works on the TV (finding games, playing,
 * the menu during a game, Quick Resume, the picture), driven by the controller or the remote.
 * A or OK goes on, B or Back goes back, Y skips. It plays once (then from Ajuda › Ver o tour).
 */
class TourScreen(context: Context, private val onDone: () -> Unit) : FrameLayout(context) {

    private data class Step(val icon: Int, val eyebrow: String, val title: String, val text: String,
                            val keys: List<Pair<Jinky.Button?, String>> = emptyList(), val logo: Boolean = false)

    private val steps = listOf(
        Step(R.drawable.ic_gamepad, "BEM-VINDO", "Os jogos do seu PC, na TV",
            "O Jinky transforma o seu PC num console. Você escolhe o jogo aqui, com o controle, e o PC faz o resto sozinho. Este tour leva um minuto.",
            logo = true),
        Step(R.drawable.ic_grid, "ENCONTRE OS SEUS JOGOS", "Início, Biblioteca e Amigos",
            "No Início ficam os jogos pausados, os que você fixou e os recentes. A Biblioteca tem todos: Steam, PlayStation 2, Epic, GOG e Xbox.",
            listOf(null to "R1  Biblioteca", null to "L1  Amigos", Jinky.Button.SEARCH to "Buscar", Jinky.Button.PIN to "Fixar no Início")),
        Step(R.drawable.ic_play, "ESCOLHA E JOGUE", "Um botão e o jogo abre",
            "Aperte A num jogo. Enquanto ele abre, a TV mostra o que a Steam está fazendo (atualização, saves, shaders). Se a Steam perguntar algo, a pergunta aparece aqui, com os botões do Jinky.",
            listOf(Jinky.Button.OK to "Jogar")),
        Step(R.drawable.ic_settings, "DURANTE O JOGO", "O menu do Jinky",
            "Aperte Voltar (ou segure View + Menu por um segundo) para abrir o menu: capturar a tela, ver conquistas, usar o controle como mouse ou teclado, fechar o jogo ou voltar ao Início.",
            listOf(Jinky.Button.BACK to "Abrir o menu")),
        Step(R.drawable.ic_home, "CONTINUE DE ONDE PAROU", "Quick Resume",
            "Voltou ao Início no meio do jogo? Ele fica pausado no PC. Escolha Continuar e ele volta exatamente onde estava. Nos jogos de PS2, dá até depois de reiniciar o PC."),
        Step(R.drawable.ic_gauge, "IMAGEM PERFEITA", "Teste a sua rede",
            "Em Configurações › Imagem e qualidade você testa a rede e ajusta a qualidade. Wi-Fi de 5 GHz na TV e cabo de rede no PC deixam tudo mais estável."),
        Step(R.drawable.ic_trophy, "PRONTO", "Hora de jogar",
            "Dúvidas a qualquer momento? Configurações › Sistema › Ajuda e informações responde as principais, e este tour fica lá também.",
            listOf(Jinky.Button.OK to "Começar")),
    )

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var index = 0
    private val iconCircle = FrameLayout(context)
    private val icon = ImageView(context)
    private val logo = LinearLayout(context)
    private val eyebrow = TextView(context)
    private val title = TextView(context)
    private val body = TextView(context)
    private val keys = LinearLayout(context)
    private val dots = LinearLayout(context)
    private val content = LinearLayout(context)
    private val next = TextView(context)

    init {
        setBackgroundColor(Jinky.INK)
        isClickable = true
        // A soft blue glow behind, like the home screen.
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x402856D9, 0x00000000, 0x00000000)).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 900 * density
                setGradientCenter(0.3f, 0.35f)
            }
        }, LayoutParams(-1, -1))

        iconCircle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(Jinky.BLUE, Jinky.BLUE_DEEP)
            orientation = GradientDrawable.Orientation.TL_BR
        }
        icon.setColorFilter(Color.WHITE)
        iconCircle.addView(icon, LayoutParams(dp(56), dp(56), Gravity.CENTER))
        logo.orientation = LinearLayout.HORIZONTAL
        logo.gravity = Gravity.CENTER_VERTICAL
        logo.addView(ImageView(context).apply { setImageResource(R.drawable.jinky_tile) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        logo.addView(ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark_white) },
            LinearLayout.LayoutParams(dp(130), dp(43)).apply { marginStart = dp(18) })

        eyebrow.apply {
            setTextColor(Jinky.BLUE_SOFT)
            textSize = 11f
            letterSpacing = 0.18f
            typeface = Jinky.mono(context, 600)
        }
        title.apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Jinky.font(context, 800)
        }
        body.apply {
            setTextColor(Jinky.SOFT)
            textSize = 15f
            typeface = Jinky.font(context, 500)
            setLineSpacing(0f, 1.35f)
        }
        keys.orientation = LinearLayout.HORIZONTAL
        keys.gravity = Gravity.CENTER_VERTICAL

        content.orientation = LinearLayout.VERTICAL
        content.addView(eyebrow)
        content.addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        content.addView(body, LinearLayout.LayoutParams(dp(560), -2).apply { topMargin = dp(14) })
        content.addView(keys, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                addView(iconCircle, LayoutParams(dp(150), dp(150), Gravity.CENTER))
                addView(logo, LayoutParams(-2, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(240), dp(200)))
            addView(content, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(48) })
        }
        addView(row, LayoutParams(-2, -2, Gravity.CENTER))

        // Bottom: page dots on the left, what the buttons do on the right.
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER_VERTICAL
        addView(dots, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(56), 0, 0, dp(40)) })
        next.apply {
            setTextColor(Jinky.INK)
            textSize = 13f
            typeface = Jinky.font(context, 800)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = Jinky.rounded(Jinky.TEXT, 99 * density)
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Jinky.hint(context, Jinky.Button.SEARCH, "Pular"))
            addView(Jinky.hint(context, Jinky.Button.BACK, "Voltar"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(18) })
            addView(next, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(22) })
        }
        addView(bar, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(56), dp(32)) })
        show(0, animate = false)
    }

    private fun show(i: Int, animate: Boolean = true) {
        val forward = i >= index
        index = i
        val s = steps[i]
        val apply = {
            icon.setImageResource(s.icon)
            iconCircle.visibility = if (s.logo) View.GONE else View.VISIBLE
            logo.visibility = if (s.logo) View.VISIBLE else View.GONE
            eyebrow.text = s.eyebrow
            title.text = s.title
            body.text = s.text
            keys.removeAllViews()
            s.keys.forEachIndexed { n, (button, label) ->
                keys.addView(keyChip(button, label), LinearLayout.LayoutParams(-2, -2).apply { if (n > 0) marginStart = dp(26) })
            }
            next.text = if (i == steps.lastIndex) "Começar" else "Próximo"
            dots.removeAllViews()
            steps.indices.forEach { n ->
                dots.addView(View(context).apply {
                    background = Jinky.rounded(if (n == i) Jinky.BLUE else Jinky.LINE, 99 * density)
                }, LinearLayout.LayoutParams(if (n == i) dp(22) else dp(7), dp(7)).apply { if (n > 0) marginStart = dp(6) })
            }
        }
        if (!animate) { apply(); return }
        // The page slides out to the side and the next one comes in from the other.
        val shift = 40 * density * if (forward) 1 else -1
        content.animate().alpha(0f).translationX(-shift).setDuration(140).withEndAction {
            apply()
            content.translationX = shift
            content.animate().alpha(1f).translationX(0f).setDuration(260)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        }.start()
        iconCircle.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(140).withEndAction {
            iconCircle.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }.start()
    }

    /**
     * A button and what it does, big enough to read on the tour: A/B/X/Y in a coloured circle
     * (✕ ○ □ △ with a PlayStation pad), shoulder buttons ("R1 Biblioteca") in a rounded box.
     */
    private fun keyChip(button: Jinky.Button?, label: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val shoulder = button == null
        val glyph = if (shoulder) label.substringBefore("  ") else if (Jinky.playStationPad()) button!!.ps else button!!.xbox
        val words = if (shoulder) label.substringAfter("  ") else label
        addView(TextView(context).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(if (shoulder) Jinky.SOFT else button!!.color)
            textSize = 11f
            typeface = Jinky.font(context, 800)
            background = if (shoulder) Jinky.rounded(Jinky.LINE, 6 * density)
                         else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.LINE) }
            if (shoulder) setPadding(dp(7), 0, dp(7), 0)
        }, LinearLayout.LayoutParams(if (shoulder) -2 else dp(22), dp(22)))
        addView(TextView(context).apply {
            text = words
            setTextColor(Jinky.SOFT)
            textSize = 13f
            typeface = Jinky.font(context, 600)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }

    /** The controller or remote while the tour is up. True: used. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> {
                UiSounds.play(UiSounds.Sound.SELECT)
                if (index == steps.lastIndex) finish() else show(index + 1)
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (index > 0) { UiSounds.play(UiSounds.Sound.MOVE); show(index - 1) }
            }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU -> finish()
        }
        return true
    }

    private fun finish() {
        animate().alpha(0f).setDuration(240).withEndAction { onDone() }.start()
    }

    /** Fades in over the screen. */
    fun appear() {
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
    }
}
