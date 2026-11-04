package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Ajuda e informações: questions and answers about using Jinky (the answer shows next to the
 * question under focus), the tour again, and what this TV / PC are.
 */
class HelpScreen(context: Context, private val onBack: () -> Unit, private val onTour: () -> Unit) : TvPage(context) {

    private data class Faq(val q: String, val a: String)

    private val topics = listOf(
        "Jogar" to listOf(
            Faq("Como abro um jogo?",
                "Escolha o jogo no Início ou na Biblioteca e aperte A. A TV mostra uma tela de carregamento com o que a Steam está fazendo e só mostra a imagem quando o jogo está na tela."),
            Faq("A Steam fez uma pergunta. E agora?",
                "As perguntas da Steam (saves diferentes na nuvem, jeito de abrir o jogo, jogo aberto em outro computador) aparecem na própria TV, com os botões do Jinky. Escolha com o controle; Cancelar volta ao Início."),
            Faq("Como abro o menu durante o jogo?",
                "Aperte Voltar no controle ou no controle remoto, ou segure View + Menu por um segundo. No menu: Continuar, Capturar a tela, Conquistas, Teclado, Mouse, Desempenho, Início e Fechar jogo."),
            Faq("Como continuo um jogo de onde parei?",
                "Escolha Início no menu do jogo (ou aperte o botão Home do controle remoto): o jogo fica pausado no PC e aparece em Pausados, no Início. Escolha Continuar e ele volta na hora. Até 2 jogos ficam pausados; jogos online ou com anti-cheat não são pausados. Liga e desliga em Configurações › Sistema › Pausar jogos ao sair."),
            Faq("Como fecho o jogo?",
                "No menu do jogo, escolha Fechar jogo. O PC fecha o jogo e a TV volta ao Início."),
            Faq("Como uso o controle como mouse ou teclado?",
                "No menu do jogo, escolha Mouse: o analógico esquerdo move o ponteiro, A clica, X é o botão direito, o analógico direito rola a tela, B é Esc e Start é Enter. Teclado abre um teclado na tela para digitar no PC."),
            Faq("Como instalo um jogo pela TV?",
                "Na Biblioteca, escolha Todos para ver os jogos que você tem e não estão instalados. Abra o jogo e escolha Instalar: o PC baixa pela Steam e a TV mostra o progresso em Downloads."),
        ),
        "Controles" to listOf(
            Faq("Quais controles funcionam?",
                "Qualquer controle conectado na TV: Xbox, DualSense, DualShock 4 e outros. Até 4 controles ao mesmo tempo, cada um vira um jogador no PC."),
            Faq("Meu controle PlayStation aparece como Xbox",
                "Ligue Configurações › Sistema › Controle PlayStation no PC. O PC passa a ver um controle de PS4, com os botões △ ○ ✕ □ nos jogos. Touchpad e movimento funcionam em TVs com Android 12 ou mais novo."),
        ),
        "Imagem e rede" to listOf(
            Faq("A imagem trava ou fica borrada",
                "Teste a rede em Configurações › Imagem e qualidade › Testar a rede. O que mais ajuda: cabo de rede no PC, Wi-Fi de 5 GHz na TV e a TV perto do roteador. Se continuar, baixe a Qualidade máxima."),
            Faq("O som chega antes ou depois da imagem",
                "Em Configurações › Imagem e qualidade › Sincronia do áudio, atrase o som até ele bater com a imagem. Vale na hora."),
            Faq("A TV não encontra o PC",
                "O PC precisa estar ligado, com o Jinky aberto (ele fica perto do relógio do Windows), e na mesma rede da TV. Se o PC estiver suspenso, a TV pode acordá-lo: ligue Ligar o PC ao abrir o Jinky e, no PC, Ligar o PC pela TV."),
        ),
        "PC e TV" to listOf(
            Faq("Como suspendo ou desligo o PC pela TV?",
                "Em Configurações › Sistema ficam Suspender o PC e Desligar o PC. No Jinky do PC, a opção A TV pode suspender ou desligar o PC precisa estar ligada."),
            Faq("Como jogo fora de casa?",
                "No Jinky do PC, ligue Jogar fora de casa (usa o Tailscale, grátis). Entre com a mesma conta do Tailscale no PC e na TV e, aqui, em Configurações › Sistema › PC pela internet, digite o endereço que o PC mostra."),
            Faq("Como coloco o Jinky em outra TV?",
                "No PC, em TVs › Instalar numa TV nova, aparece um endereço. Na outra TV, instale o app Downloader, permita a instalação de apps e digite esse endereço. Depois ela se atualiza sozinha pelo PC."),
            Faq("Como o Jinky se atualiza?",
                "O PC se atualiza sozinho e traz junto a versão nova da TV. Quando houver uma, o Início da TV mostra Atualizar o Jinky."),
            Faq("Algo deu errado. Como peço ajuda?",
                "Em Configurações › Sistema › Enviar diagnóstico ao PC, a TV manda um relatório para a pasta de diagnóstico do Jinky do PC. Ele ajuda a entender o problema."),
        ),
    )

    private val device = text(13f, StartScreen.MUTED).apply { setLineSpacing(0f, 1.3f) }
    private val back = smallButton("Voltar")
    private val tour = smallButton("Ver o tour de novo")
    private val answerTitle = text(22f, Color.WHITE, bold = true)
    private val answer = text(16f, Jinky.SOFT).apply { setLineSpacing(0f, 1.4f) }
    private var first: View? = null

    init {
        val root = pageRoot()
        root.addView(brandHeader("Ajuda e informações").apply {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(tour, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            addView(back, LinearLayout.LayoutParams(-2, -2))
        })

        val columns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        topics.forEachIndexed { t, (topic, faqs) ->
            list.addView(mono(10f, Jinky.DIM, 600).apply { text = topic.uppercase(); letterSpacing = 0.18f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = if (t == 0) 0 else dp(18); bottomMargin = dp(8) })
            faqs.forEach { f ->
                val b = bigButton().apply {
                    text = f.q
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(16), dp(11), dp(16), dp(11))
                    setOnFocusChangeListener { _, has -> if (has) showAnswer(f) }
                    setOnClickListener { showAnswer(f) }
                }
                if (first == null) first = b
                list.addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            setPadding(0, dp(4), dp(4), dp(4))
            clipToPadding = false
            addView(list, LayoutParams(-1, -2))
        }
        columns.addView(scroll, LinearLayout.LayoutParams(0, -1, 0.9f))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(28), dp(30), dp(28))
            background = Jinky.rounded(Jinky.SURFACE, 18 * density, dp(1), Jinky.LINE)
            addView(answerTitle)
            addView(answer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        columns.addView(card, LinearLayout.LayoutParams(0, -2, 1.1f).apply { marginStart = dp(32) })
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(26) })
        root.addView(device, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        addView(root, LayoutParams(-1, -1))

        back.setOnClickListener { onBack() }
        tour.setOnClickListener { onTour() }
        showAnswer(topics[0].second[0])
    }

    private fun showAnswer(f: Faq) {
        if (answerTitle.text == f.q) return
        answerTitle.text = f.q
        answer.text = f.a
        answer.alpha = 0f
        answer.translationY = 6 * density
        answer.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    /** [pc]: the PC as the home screen knows it, if any. */
    fun show(pc: HomeHost?, decoder: String) {
        device.text = "Jinky ${BuildConfig.VERSION_NAME}  ·  ${Build.MANUFACTURER} ${Build.MODEL}  ·  Android ${Build.VERSION.RELEASE}  ·  " +
            "vídeo: $decoder  ·  " + (if (pc == null) "PC: nenhum encontrado ainda" else "PC: ${pc.name} (${pc.ip})")
    }

    override fun focusFirst() {
        (first ?: back).requestFocus()
    }
}
