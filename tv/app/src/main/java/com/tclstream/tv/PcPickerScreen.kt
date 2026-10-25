package com.tclstream.tv

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * "Escolher PC": every PC this TV can reach or has seen. One PC can show more than once: each
 * Windows user (or ASTER workplace) running Jinky at the same time is its own "PC", with its
 * own name, library and pairing. The choice is remembered (TvSettings.preferredPc).
 */
class PcPickerScreen(
    context: Context,
    private val onChoose: (HomeHost) -> Unit,
    private val onBack: () -> Unit,
) : TvPage(context) {

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val intro = text(11f, Jinky.MUTED).apply { setLineSpacing(0f, 1.25f) }
    private val back = smallButton("Voltar").apply { setOnClickListener { onBack() } }

    init {
        val root = pageRoot()
        root.addView(brandHeader("Escolher PC").apply {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(back, LinearLayout.LayoutParams(-2, -2))
        })
        root.addView(intro, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        root.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        addView(root, LayoutParams(-1, -1))
    }

    /**
     * Lists the PCs paired with this TV (the one in use first) and focuses the one in use. PCs not
     * paired with it never show here; "Parear um PC novo" (or a TV paired with none) switches to
     * pairing: the other PCs on the network, the first connection then asks for the code.
     */
    fun show(hosts: List<HomeHost>, currentId: Long?, paired: Set<Long>, pairing: Boolean = false) {
        list.removeAllViews()
        var focus: View? = null
        val pairingMode = pairing || paired.isEmpty()
        val shown = if (pairingMode) hosts.filter { it.id !in paired } else hosts.filter { it.id in paired }
        intro.text = if (pairingMode)
            "Parear um PC: escolha o PC (cada usuário do Windows com o Jinky aberto aparece como um PC). " +
                "Na primeira conexão ele mostra um código, confirme no Jinky do PC. Só PCs pareados aparecem depois."
        else "Os PCs pareados com esta TV: escolha em qual jogar."
        for (h in shown.sortedWith(compareByDescending<HomeHost> { it.id == currentId }.thenByDescending { it.id in paired })) {
            val status = when (h.status) {
                HomeHost.Status.READY -> "Pronto"
                HomeHost.Status.BUSY -> if (h.busyWith.isNotEmpty()) "Jogando em ${h.busyWith}" else "Em uso"
                HomeHost.Status.OFFLINE -> "Desligado ou sem o Jinky aberto"
            } + (if (h.id !in paired) " · ainda não pareado com esta TV" else "") +
                if (h.id == currentId) " · em uso nesta TV" else ""
            val row = bigButton().apply {
                text = twoLines(h.name, status)
                setOnClickListener { onChoose(h) }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (list.childCount > 0) topMargin = dp(10) })
            if (h.id == currentId) focus = row
        }
        if (hosts.isEmpty() && !pairingMode) {
            list.addView(text(13f, Jinky.MUTED).apply { text = "Nenhum PC com o Jinky foi encontrado na rede." })
        }
        val others = hosts.count { it.id !in paired }
        if (!pairingMode) {
            val add = smallButton(if (others > 0) "Parear um PC novo  ·  $others na rede" else "Parear um PC novo").apply {
                setOnClickListener { show(hosts, currentId, paired, pairing = true) }
            }
            list.addView(add, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            if (focus == null && shown.isEmpty()) focus = add
        } else if (shown.isEmpty()) {
            list.addView(text(13f, Jinky.MUTED).apply {
                text = "Nenhum outro PC com o Jinky aberto na rede. Abra o Jinky no PC e volte aqui."
            })
        }
        (focus ?: list.getChildAt(0) ?: back).let { v -> post { v.requestFocus() } }
    }
}
