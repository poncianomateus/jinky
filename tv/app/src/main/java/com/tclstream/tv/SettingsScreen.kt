package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Configurações (design v2): categories on the left, like a console, and the chosen category's
 * rows on the right. Moving over a category shows it; right enters it. Every row shows its value;
 * OK/A changes it (TV style, no pop-ups). Theme and old-TV lines rebuild the screens.
 */
class SettingsScreen(
    context: Context,
    private val settings: TvSettings,
    private val onChanged: () -> Unit,
    private val onSendDiagnostics: () -> Unit,
    private val onForgetPc: () -> Unit,
    private val onHelp: () -> Unit,
    private val onLookChanged: () -> Unit,
    private val onTestToast: () -> Unit,
    private val pcAddress: () -> Pair<String, Int>?,
    private val currentPc: () -> HomeHost?,
    private val onPower: (off: Boolean) -> Unit,
    private val onAddRemotePc: () -> Unit,
) : TvPage(context) {

    private enum class Section(val label: String, val icon: Int, val subtitle: String) {
        PICTURE("Imagem e qualidade", R.drawable.ic_gauge, "Como o jogo chega à sua TV."),
        LOOK("Temas e sons", R.drawable.ic_settings, "Como o Jinky parece e soa na sua TV."),
        ACHIEVEMENTS("Conquistas e avisos", R.drawable.ic_trophy, "O que aparece por cima do jogo."),
        SYSTEM("Sistema", R.drawable.ic_power, "Esta TV, o PC pareado e a ajuda."),
    }

    private val nav = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val navItems = HashMap<Section, LinearLayout>()
    private val title = text(19f, Jinky.TEXT, bold = true)
    private val subtitle = text(10f, Jinky.MUTED)
    private val clock = mono(15f, Jinky.TEXT)
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(4), dp(6), dp(4), dp(24))
        addView(rows, LayoutParams(-1, -2))
    }
    private val note = text(10f, Jinky.BLUE_SOFT)
    private var section = Section.PICTURE
    private var forgetArmedUntil = 0L
    private var powerArmed: Boolean? = null  // which power action waits for the second press
    private var powerArmedUntil = 0L
    private var lastTest: NetworkTest.Result? = null

    init {
        // --- left: categories ---
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(18), dp(24))
            setBackgroundColor(Jinky.surface(0x99))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(R.drawable.jinky_tile) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(text(17f, Jinky.TEXT, bold = true).apply { text = "Configurações" },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            })
            addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark_white); alpha = 0.5f },
                LinearLayout.LayoutParams(dp(54), dp(18)))
            addView(mono(8f, Jinky.DIM).apply {
                text = "versão ${BuildConfig.VERSION_NAME} · ${android.os.Build.MODEL}"
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        }
        for (s in Section.values()) {
            val item = navItem(s)
            navItems[s] = item
            nav.addView(item, LinearLayout.LayoutParams(-1, dp(32)).apply { if (nav.childCount > 0) topMargin = dp(4) })
        }
        addView(left, LayoutParams(dp(270), -1))

        // --- right: the section ---
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(28), dp(48), 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(clock)
            })
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        }
        addView(right, LayoutParams(-1, -1).apply { leftMargin = dp(270) })
        show(Section.PICTURE)
    }

    fun setNote(text: String?) {
        note.text = text ?: ""
        note.gone(text.isNullOrEmpty())
    }

    /** Opens on the theme section (after the screens were rebuilt for a new look). */
    fun openLook() {
        show(Section.LOOK)
        navItems[Section.LOOK]?.requestFocusFromTouch()
    }

    override fun focusFirst() {
        setNote(null)
        forgetArmedUntil = 0L
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        show(section)
        navItems[section]?.requestFocusFromTouch()
    }

    private fun show(s: Section) {
        val changed = s != section || rows.childCount == 0
        section = s
        title.text = s.label
        subtitle.text = s.subtitle
        for ((k, v) in navItems) v.isSelected = k == s
        if (changed) build()
    }

    // --- sections ---

    private fun build() {
        rows.removeAllViews()
        when (section) {
            Section.PICTURE -> {
                networkTest()
                cycle("Qualidade máxima", {
                    val m = settings.bitrateMbps
                    if (m == 0) "a do PC" else "$m Mbit/s"
                }, { val m = settings.bitrateMbps
                    when {
                        m == 0 -> "O Jinky baixa sozinho se o Wi-Fi oscilar"
                        m <= 20 -> "Mais estável em Wi-Fi fraco"
                        m >= 50 -> "Máxima nitidez; pede rede boa"
                        else -> "Equilíbrio entre nitidez e estabilidade"
                    } }) {
                    val all = TvSettings.BITRATES
                    settings.bitrateMbps = all[(all.indexOf(settings.bitrateMbps) + 1) % all.size]
                    onChanged()
                }
                // Weak sticks (no HEVC, no low-latency decoder: the Fire TV Stick Lite) decode 720p in about half the time.
                val weak = HardwareInfo.bestDecoder(VideoPipeline.MIME_HEVC) == null
                cycle("Resolução", { if (settings.resolution == 1) "720p" else "1080p" },
                    { if (settings.resolution == 1) "Menos atraso e menos travadinhas em aparelhos mais fracos"
                      else if (weak) "Este aparelho é mais simples: 720p deve deixar o jogo mais leve"
                      else "A imagem mais nítida" }) {
                    settings.resolution = if (settings.resolution == 1) 0 else 1
                    onChanged()
                }
                if (HardwareInfo.hdr10(context)) toggle("HDR (experimental)",
                    "Cores e brilho de HDR10. O PC liga o HDR do Windows durante o jogo e volta ao normal depois.",
                    { settings.hdr && !settings.forceH264 }, enabled = !settings.forceH264) {
                    settings.hdr = !settings.hdr
                    onChanged()
                }
                cycle("Formato do vídeo", { if (settings.forceH264) "H.264" else "Automático" },
                    { if (settings.forceH264) "O mais compatível, para defeitos na imagem" else "HEVC quando a TV aceita: mais qualidade" }) {
                    settings.forceH264 = !settings.forceH264
                    onChanged()
                }
                cycle("Desempenho na tela", { when (settings.statsMode) { "compact" -> "Compacto"; "full" -> "Detalhado"; else -> "Desligado" } },
                    { when (settings.statsMode) {
                        "compact" -> "Quadros, latência, qualidade e ping num cantinho"
                        "full" -> "O painel técnico completo, para achar problemas"
                        else -> "Ligue no menu do jogo quando quiser"
                    } }) {
                    settings.statsMode = when (settings.statsMode) { "off" -> "compact"; "compact" -> "full"; else -> "off" }
                    onChanged()
                }
                cycle("Sincronia do áudio", { if (settings.audioDelayMs == 0) "Sem atraso" else "Som +${settings.audioDelayMs} ms" },
                    { if (settings.audioDelayMs == 0) "Se o som vier antes da imagem, atrase-o aqui"
                      else "Atrasa o som para casar com a imagem. Som atrasado (fone Bluetooth)? Use a saída da TV por cabo." }) {
                    val steps = listOf(0, 20, 40, 60, 80, 100, 150, 200)
                    settings.audioDelayMs = steps[(steps.indexOf(settings.audioDelayMs).coerceAtLeast(0) + 1) % steps.size]
                    onChanged()
                }
                info("Qualidade, resolução e formato valem a partir da próxima conexão; a sincronia, na hora.")
            }
            Section.LOOK -> {
                themes()
                toggle("Arte atrás dos jogos", "A arte muda com o jogo em foco", { settings.art && Jinky.theme.art },
                    enabled = Jinky.theme.art) {
                    settings.art = !settings.art
                    onLookChanged()
                }
                cycle("Efeito de TV antiga", { listOf("Desligado", "Sutil", "Forte")[settings.scanlines] },
                    { "Linhas finas sobre as artes" }) {
                    settings.scanlines = (settings.scanlines + 1) % 3
                    onLookChanged()
                }
                cycle("Sons da interface", {
                    when (settings.sounds) { "retro" -> "Retrô 8-bit"; "off" -> "Desligados"; else -> "Do tema" }
                }, { "Navegar, confirmar, voltar e conquistas" }) {
                    settings.sounds = when (settings.sounds) { "theme" -> "retro"; "retro" -> "off"; else -> "theme" }
                    UiSounds.mode = settings.sounds
                    UiSounds.play(UiSounds.Sound.SELECT)
                }
                cycle("Volume dos sons", { "${settings.soundVolume}%" }, { "Só os sons do Jinky, não o do jogo" }) {
                    val steps = listOf(25, 50, 70, 100)
                    settings.soundVolume = steps[(steps.indexOfFirst { it >= settings.soundVolume }.coerceAtLeast(0) + 1) % steps.size]
                    UiSounds.volume = settings.soundVolume
                    UiSounds.play(UiSounds.Sound.SELECT)
                }
                cycle("Abertura", { when (settings.boot) { "fast" -> "Rápida"; "off" -> "Nenhuma"; else -> "Do tema" } },
                    { "A animação quando o Jinky abre" }) {
                    settings.boot = when (settings.boot) { "theme" -> "fast"; "fast" -> "off"; else -> "theme" }
                }
            }
            Section.ACHIEVEMENTS -> {
                toggle("Avisos de conquista", "Aparecem no canto por 5 segundos, com som", { settings.toasts }) {
                    settings.toasts = !settings.toasts
                }
                action("Testar o aviso", "Mostra um aviso de exemplo agora") { onTestToast() }
                info("As conquistas vêm da sua Steam pela chave da API que está no Jinky do PC. " +
                    "Durante o jogo, o PC confere a cada poucos segundos.")
            }
            Section.SYSTEM -> {
                action("Enviar diagnóstico ao PC", "Vai para a pasta de diagnóstico do PC") { onSendDiagnostics() }
                val forget = action("Esquecer este PC", "Desfaz o pareamento nesta TV") {}
                forget.setOnClickListener {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now < forgetArmedUntil) {
                        forgetArmedUntil = 0L
                        onForgetPc()
                    } else {
                        forgetArmedUntil = now + 5000
                        setNote("Aperte de novo para confirmar. A TV vai pedir um novo pareamento na próxima conexão.")
                    }
                }
                toggle("Pausar jogos ao sair (como no Xbox)",
                    "Início, no menu do jogo, congela o jogo no PC; Continuar volta exatamente de onde parou. Até 2 jogos. Jogos com anti-cheat não são pausados.",
                    { settings.pauseGames }) { settings.pauseGames = !settings.pauseGames }
                toggle("Controle PlayStation no PC (beta)",
                    "DualSense/DualShock vira um DualShock 4 no PC: botões PlayStation nos jogos, touchpad e movimento (TVs com Android 12+). Precisa do Steam Input ligado para jogos sem suporte a PlayStation.",
                    { settings.psPad }) {
                    settings.psPad = !settings.psPad
                    onChanged()
                }
                val remote = settings.remotePcs
                action("PC pela internet",
                    if (remote.isEmpty()) "Jogar fora de casa com o Tailscale: digite o endereço que o Jinky do PC mostra"
                    else "Procurando também em ${remote.joinToString()}. Aperte para trocar.") { onAddRemotePc() }
                powerRow(off = false)
                powerRow(off = true)
                action("Ajuda e informações", "Controles, dicas de rede e dados desta TV") { onHelp() }
            }
        }
    }

    /** "Testar a rede": the row, and the last result under it (kept while the TV app is open). */
    private fun networkTest() {
        val row = action("Testar a rede", "Ping, variação, perda e velocidade até o PC") {}
        val detail = (row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(card, LinearLayout.LayoutParams(-1, -2))
        lastTest?.let { fillTest(card, it) }
        row.setOnClickListener {
            val pc = pcAddress()
            if (pc == null) {
                setNote("Nenhum PC disponível agora. Abra o Jinky no PC e tente de novo.")
                return@setOnClickListener
            }
            detail.text = "Medindo… não mexa na rede por uns segundos"
            NetworkTest.run(context, pc.first, pc.second,
                progress = { f, line -> detail.text = "$line  ${(f * 100).toInt()}%" },
                done = { result, error ->
                    if (result == null) detail.text = "Não deu para testar: $error"
                    else {
                        lastTest = result
                        detail.text = "Pronto. Aperte de novo para repetir."
                        if (card.isAttachedToWindow) fillTest(card, result)
                    }
                })
        }
    }

    private fun fillTest(card: LinearLayout, r: NetworkTest.Result) {
        card.removeAllViews()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Jinky.rounded(Jinky.SURFACE, dp(11).toFloat(), dp(1), Jinky.LINE)
        }
        // Verdict first, then the numbers, then what to do about it.
        box.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(r.grade.color) } },
                LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
            addView(text(13f, Jinky.TEXT, bold = true).apply { text = "Rede ${r.grade.label.lowercase()}" })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(mono(8.5f, Jinky.MUTED).apply {
                val l = r.link
                text = when {
                    l == null -> ""
                    l.wired -> "TV no cabo"
                    else -> listOfNotNull("Wi-Fi", l.wifiBand, l.signalDbm?.let { "$it dBm" }, l.linkMbps?.let { "$it Mbit/s" }).joinToString(" · ")
                }
            })
        })
        val stats = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(value: String, label: String, good: Boolean) = stats.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = Jinky.rounded(Jinky.ink(0xFF), dp(9).toFloat())
            addView(mono(15f, if (good) Jinky.TEXT else Jinky.AMBER, 700).apply { text = value })
            addView(text(8.5f, Jinky.MUTED).apply { text = label })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (stats.childCount > 0) marginStart = dp(8) })
        stat(String.format(java.util.Locale.US, "%.1f ms", r.pingMs), "Ping", r.pingMs <= 15)
        stat(String.format(java.util.Locale.US, "%.1f ms", r.jitterMs), "Variação", r.jitterMs <= 6)
        stat(if (r.lossPct == 0.0) "0%" else String.format(java.util.Locale.US, "%.1f%%", r.lossPct), "Perda", r.lossPct < 1)
        stat(r.mbps?.let { if (it >= 100) "${it.toInt()} Mbit/s" else String.format(java.util.Locale.US, "%.0f Mbit/s", it) } ?: "—",
            "Velocidade", r.mbps == null || r.mbps >= 30)
        box.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        box.addView(text(9.5f, Jinky.SOFT).apply { text = r.advice; setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // One click to the quality that fits, when the current one asks for more than the network gives.
        val current = settings.bitrateMbps
        if (r.grade == NetworkTest.Grade.FAIR || r.grade == NetworkTest.Grade.POOR) {
            if (current == 0 || current > r.suggestedMbps) {
                val apply = action("Usar ${r.suggestedMbps} Mbit/s", "A qualidade que cabe nesta rede (dá para mudar abaixo)") {}
                rows.removeView(apply)
                card.addView(apply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                apply.setOnClickListener {
                    settings.bitrateMbps = r.suggestedMbps
                    onChanged()
                    build()
                    setNote("Qualidade máxima: ${r.suggestedMbps} Mbit/s, a partir da próxima conexão.")
                }
            }
        }
    }

    /** "Suspender o PC" / "Desligar o PC": press twice (the second press within 5 s) to confirm. */
    private fun powerRow(off: Boolean) {
        val name = currentPc()?.name?.takeIf { it.isNotBlank() }?.let { "o PC $it" } ?: "o PC"
        val row = action(if (off) "Desligar o PC" else "Suspender o PC",
            if (off) "Desliga $name. Programas abertos podem pedir para salvar."
            else "Coloca $name para dormir. Acorda com Ligar o PC, no Início (PC no cabo), ou pelo mouse/teclado.") {}
        row.setOnClickListener {
            val pc = currentPc()
            if (pc == null || pc.status == HomeHost.Status.OFFLINE) {
                setNote("O PC já está desligado ou fora da rede.")
                return@setOnClickListener
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (powerArmed == off && now < powerArmedUntil) {
                powerArmed = null
                onPower(off)
            } else {
                powerArmed = off
                powerArmedUntil = now + 5000
                val busy = if (pc.status == HomeHost.Status.BUSY) " ${pc.busyWith.ifBlank { "Outra TV" }} está jogando nele agora: o jogo vai fechar." else ""
                setNote("Aperte de novo para ${if (off) "desligar" else "suspender"} o PC ${pc.name}.$busy")
            }
        }
    }

    private fun themes() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in UiTheme.values()) {
            val card = FrameLayout(context).apply {
                isFocusable = true
                isClickable = true
                if (t == UiTheme.values().first()) nextFocusLeftId = navItems[section]?.id ?: View.NO_ID
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(t.ink, dp(11).toFloat(), dp(2) + dp(1) / 2, Jinky.TEXT))
                    addState(intArrayOf(), Jinky.rounded(t.ink, dp(11).toFloat(), dp(1), Jinky.LINE))
                }
                // A tiny picture of the theme: its ground, a highlighted tile and a few cards.
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(View(context).apply { background = GradientDrawable().apply { setColor(t.accent); cornerRadius = dp(5) * t.corners } },
                        LinearLayout.LayoutParams(dp(28), dp(42)))
                    repeat(3) {
                        addView(View(context).apply { background = GradientDrawable().apply { setColor(t.raised); cornerRadius = dp(4) * t.corners } },
                            LinearLayout.LayoutParams(dp(21), dp(32)).apply { marginStart = dp(4) })
                    }
                }, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(10)) })
                if (t.scanlines > 12) addView(View(context).apply { background = Jinky.scanlines(context, 6) }, LayoutParams(-1, -1))
                if (t == Jinky.theme) addView(TextView(context).apply {
                    text = "✓"
                    gravity = Gravity.CENTER
                    setTextColor(t.ink)
                    textSize = 9f
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.text) }
                }, LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
                setOnClickListener {
                    if (t == Jinky.theme) return@setOnClickListener
                    settings.theme = t.key
                    onLookChanged()
                }
                setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(160).start() }
            }
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(card, LinearLayout.LayoutParams(-1, dp(84)))
                addView(text(10.5f, Jinky.TEXT, bold = true).apply { text = t.label }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (row.childCount > 0) marginStart = dp(12) })
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    // --- rows ---

    private fun rowBase(label: String, detail: String, enabled: Boolean = true): Pair<LinearLayout, LinearLayout> {
        val right = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = enabled
            isClickable = enabled
            alpha = if (enabled) 1f else 0.45f
            setPadding(dp(16), dp(11), dp(14), dp(11))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.RAISED, dp(11).toFloat(), dp(2), Jinky.TEXT))
                addState(intArrayOf(), Jinky.rounded(Jinky.SURFACE, dp(11).toFloat()))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(12f, Jinky.TEXT, bold = true).apply { text = label })
                addView(text(9f, Jinky.MUTED).apply { text = detail }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(right)
            nextFocusLeftId = navItems[section]?.id ?: View.NO_ID
            setOnFocusChangeListener { v, has -> if (has) v.post { scroll.smoothScrollTo(0, (v.top - dp(30)).coerceAtLeast(0)) } }
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (rows.childCount > 0) topMargin = dp(8) })
        return row to right
    }

    private fun cycle(label: String, value: () -> String, detail: () -> String, onClick: () -> Unit) {
        val (row, right) = rowBase(label, detail())
        val pill = TextView(context).apply {
            setPadding(dp(11), dp(4), dp(11), dp(4))
            setTextColor(Jinky.TEXT)
            textSize = 9.5f
            typeface = Jinky.font(context, 700)
            background = Jinky.rounded(Jinky.ink(0xFF), dp(14).toFloat(), dp(1), Jinky.LINE)
            text = value()
        }
        right.addView(pill)
        val detailView = ((row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView)
        row.setOnClickListener {
            onClick()
            pill.text = value()
            detailView.text = detail()
        }
    }

    private fun toggle(label: String, detail: String, value: () -> Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        val (row, right) = rowBase(label, detail, enabled)
        val track = FrameLayout(context)
        val knob = View(context)
        track.addView(knob, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER_VERTICAL))
        right.addView(track, LinearLayout.LayoutParams(dp(40), dp(22)))
        fun paint() {
            val on = value()
            track.background = Jinky.rounded(if (on) Jinky.BLUE else Jinky.LINE, dp(11).toFloat())
            knob.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) Jinky.TEXT else Jinky.MUTED) }
            (knob.layoutParams as FrameLayout.LayoutParams).leftMargin = if (on) dp(20) else dp(3)
            knob.requestLayout()
        }
        paint()
        row.setOnClickListener { onClick(); paint() }
    }

    private fun action(label: String, detail: String, onClick: () -> Unit): LinearLayout {
        val (row, right) = rowBase(label, detail)
        right.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            rotation = 180f
            setColorFilter(Jinky.MUTED)
        }, LinearLayout.LayoutParams(dp(13), dp(13)))
        row.setOnClickListener { onClick() }
        return row
    }

    private fun info(message: String) {
        rows.addView(text(9f, Jinky.DIM).apply { text = message; setLineSpacing(0f, 1.3f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); leftMargin = dp(4) })
    }

    private fun navItem(s: Section) = LinearLayout(context).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        // Right always enters the section (its first option).
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && event.action == android.view.KeyEvent.ACTION_DOWN) {
                firstFocusable(rows)?.requestFocus()
                true
            } else false
        }
        isClickable = true
        setPadding(dp(11), 0, dp(11), 0)
        val icon = ImageView(context).apply { setImageResource(s.icon); setColorFilter(Jinky.SOFT) }
        val label = text(10.5f, Jinky.SOFT, bold = false).apply { text = s.label }
        addView(icon, LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(9) })
        addView(label)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, dp(9).toFloat()))
            addState(intArrayOf(android.R.attr.state_selected), Jinky.rounded(Jinky.RAISED, dp(9).toFloat()))
            addState(intArrayOf(), Jinky.rounded(Color.TRANSPARENT, dp(9).toFloat()))
        }
        setOnFocusChangeListener { _, has ->
            icon.setColorFilter(if (has) Jinky.INK else Jinky.SOFT)
            label.setTextColor(if (has) Jinky.INK else if (isSelected) Jinky.TEXT else Jinky.SOFT)
            if (has) { setNote(null); show(s) }
        }
        setOnClickListener {
            show(s)
            (rows.getChildAt(0) as? View)?.let { first ->
                // Into the section: the first focusable row (the theme cards' first card).
                first.focusSearch(View.FOCUS_RIGHT)
                val target = if (first.isFocusable) first else (first as? LinearLayout)?.let { firstFocusable(it) }
                target?.requestFocus()
            }
        }
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }
}
