package com.tclstream.tv

import android.content.Context
import android.view.View
import android.widget.LinearLayout

/**
 * Configurações: each row shows its current value; OK/A moves to the next value (TV style, no
 * pop-ups). Actions (send diagnostics, forget the PC, help) are rows too.
 */
class SettingsScreen(
    context: Context,
    private val settings: TvSettings,
    private val onChanged: () -> Unit,
    private val onSendDiagnostics: () -> Unit,
    private val onForgetPc: () -> Unit,
    private val onHelp: () -> Unit,
) : TvPage(context) {

    private val quality = bigButton()
    private val codec = bigButton()
    private val stats = bigButton()
    private val diagnostics = bigButton()
    private val forget = bigButton()
    private val help = bigButton()
    private val note = text(17f, StartScreen.ACCENT)
    private var forgetArmedUntil = 0L

    init {
        val root = pageRoot()
        root.addView(brandHeader("Configurações"))
        // Two columns: preferences on the left, actions on the right.
        fun column(vararg rows: View) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEachIndexed { i, b ->
                (b as android.widget.TextView).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                b.setPadding(dp(24), dp(14), dp(24), dp(14))
                addView(b, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(12) })
            }
        }
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(column(quality, codec, stats), LinearLayout.LayoutParams(0, -2, 1f))
            addView(column(diagnostics, forget, help), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(20) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) })
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        root.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(text(15f, StartScreen.HINT).apply {
            text = "A ou OK: mudar   ·   Voltar: tela inicial   ·   Qualidade e formato valem a partir da próxima conexão"
        })
        addView(root, LayoutParams(-1, -1))

        quality.setOnClickListener {
            val all = TvSettings.BITRATES
            settings.bitrateMbps = all[(all.indexOf(settings.bitrateMbps) + 1) % all.size]
            changed()
        }
        codec.setOnClickListener {
            settings.forceH264 = !settings.forceH264
            changed()
        }
        stats.setOnClickListener {
            settings.showStats = !settings.showStats
            changed()
        }
        diagnostics.setOnClickListener { onSendDiagnostics() }
        forget.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < forgetArmedUntil) {
                forgetArmedUntil = 0L
                onForgetPc()
            } else {
                forgetArmedUntil = now + 5000
                setNote("Aperte de novo para confirmar. A TV vai pedir um novo pareamento na próxima conexão.")
            }
            refresh()
        }
        help.setOnClickListener { onHelp() }
        refresh()
    }

    private fun changed() {
        onChanged()
        refresh()
    }

    fun setNote(text: String?) {
        note.text = text ?: ""
        note.gone(text.isNullOrEmpty())
    }

    fun refresh() {
        val mbps = settings.bitrateMbps
        quality.text = twoLines("Qualidade máxima:  " + if (mbps == 0) "a do PC" else "$mbps Mbit/s",
            when {
                mbps == 0 -> "A do PC; o Jinky baixa sozinho se o Wi-Fi oscilar"
                mbps <= 20 -> "Mais estável em Wi-Fi fraco"
                mbps >= 50 -> "Máxima nitidez; o Jinky baixa sozinho se a rede não aguentar"
                else -> "Equilíbrio; o Jinky baixa sozinho se o Wi-Fi oscilar"
            })
        codec.text = twoLines("Formato do vídeo:  " + if (settings.forceH264) "H.264" else "automático",
            if (settings.forceH264) "O mais compatível, para defeitos na imagem"
            else "HEVC quando a TV aceita: mais qualidade")
        stats.text = twoLines("Desempenho durante o jogo:  " + if (settings.showStats) "visível" else "oculto",
            "Latência, quadros e rede na tela")
        diagnostics.text = twoLines("Enviar diagnóstico ao PC",
            "Vai para a pasta de diagnóstico do PC")
        val armed = android.os.SystemClock.elapsedRealtime() < forgetArmedUntil
        forget.text = twoLines(if (armed) "Confirmar: esquecer este PC" else "Esquecer este PC",
            "Desfaz o pareamento nesta TV")
        help.text = twoLines("Ajuda e informações", "Controles, dicas de rede e dados desta TV")
    }

    override fun focusFirst() {
        setNote(null)
        forgetArmedUntil = 0L
        refresh()
        quality.requestFocus()
    }
}
