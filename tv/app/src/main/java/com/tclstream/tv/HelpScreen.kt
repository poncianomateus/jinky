package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.LinearLayout

/** Ajuda e informações: controls, network tips and what this TV / PC are. */
class HelpScreen(context: Context, private val onBack: () -> Unit) : TvPage(context) {

    private val device = text(17f, StartScreen.MUTED).apply { setLineSpacing(0f, 1.3f) }
    private val back = smallButton("Voltar").apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }

    init {
        val root = pageRoot()
        root.addView(brandHeader("Ajuda e informações").apply {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(back, LinearLayout.LayoutParams(-2, -2))
        })
        val columns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        columns.addView(section("No controle", listOf(
            "A ou OK" to "escolher",
            "Voltar" to "volta; no jogo, abre o menu",
            "View + Menu (1 s)" to "menu durante o jogo",
            "Sair do Big Picture" to "encerra a sessão",
            "Jogar aqui" to "traz o jogo de outra TV",
        )), LinearLayout.LayoutParams(0, -2, 1f))
        columns.addView(section("Para jogar melhor", listOf(
            "Cabo de rede" to "o mais estável",
            "Wi-Fi 5 GHz" to "melhor que 2,4 GHz",
            "Imagem travando" to "baixe a Qualidade",
            "Ligar o PC" to "da suspensão; desligado, só com cabo",
            "Senha do Windows" to "não aparece na TV",
        )), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(48) })
        root.addView(columns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) })
        root.addView(text(20f, Color.WHITE, bold = true).apply { text = "Este aparelho" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        root.addView(device, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(root, LayoutParams(-1, -1))
        back.setOnClickListener { onBack() }
    }

    private fun section(title: String, rows: List<Pair<String, String>>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(20f, Color.WHITE, bold = true).apply { text = title })
        rows.forEach { (key, value) ->
            addView(text(17f, StartScreen.MUTED).apply { text = twoLinesInline(key, value) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    /** "Key — value" with the key in white. */
    private fun twoLinesInline(key: String, value: String): CharSequence {
        val s = android.text.SpannableString("$key  —  $value")
        s.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, key.length, 0)
        return s
    }

    /** [pc]: the PC as the home screen knows it, if any. */
    fun show(pc: HomeHost?, decoder: String) {
        device.text = buildString {
            appendLine("Jinky ${BuildConfig.VERSION_NAME}  ·  ${Build.MANUFACTURER} ${Build.MODEL}  ·  Android ${Build.VERSION.RELEASE}")
            appendLine("Decodificador de vídeo: $decoder")
            append(if (pc == null) "PC: nenhum encontrado ainda" else "PC: ${pc.name}  ·  ${pc.ip}")
        }
    }

    override fun focusFirst() {
        back.requestFocus()
    }
}
