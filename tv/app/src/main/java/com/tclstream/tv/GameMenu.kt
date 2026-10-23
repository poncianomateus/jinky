package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The quick menu over a game (design v2, like the PS5's control centre): the game keeps showing
 * above, a dark band rises from the bottom with what is playing, live stream numbers, the
 * controller's battery, the achievements being followed, and a row of big actions.
 * "Fechar jogo" asks for a second press so a game is never closed by accident.
 */
class GameMenu(
    context: Context,
    private val onResume: () -> Unit,
    private val onCloseGame: () -> Unit,
    private val onToggleStats: () -> Unit,
    private val onHome: () -> Unit,
    private val onExit: () -> Unit,
    private val onAchievements: () -> Unit,
) : TvPage(context) {

    private val shade = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x0007090D, 0x6607090D, 0xE607090D.toInt(), 0xFA07090D.toInt()))
    }
    private val band = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = text(15f, Jinky.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val stats = mono(9f, Jinky.SOFT)
    private val trackCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(11), dp(9), dp(11), dp(9))
        background = Jinky.rounded(0xF010141F.toInt(), dp(11).toFloat())
    }
    private val trackRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tiles = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val resume = tile(R.drawable.ic_play, "Continuar")
    private val achievements = tile(R.drawable.ic_trophy, "Conquistas")
    private val statsTile = tile(R.drawable.ic_gauge, "Desempenho")
    private val home = tile(R.drawable.ic_home, "Início")
    private val closeGame = tile(R.drawable.ic_power, "Fechar jogo", danger = true)
    private val exit = tile(R.drawable.ic_exit, "Sair do Jinky")
    private var armedClose = false
    private var line = ""

    init {
        visibility = View.GONE
        setBackgroundColor(Color.TRANSPARENT)
        addView(shade, LayoutParams(-1, -1))

        cover.clipToOutline = true
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
        }
        trackCard.addView(mono(6.5f, Jinky.MUTED, 700).apply { text = "ACOMPANHANDO"; letterSpacing = 0.2f })
        trackCard.addView(trackRows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

        band.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(cover, LinearLayout.LayoutParams(dp(38), dp(57)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(stats, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(11), 0, dp(11), dp(2)) })
            addView(trackCard, LinearLayout.LayoutParams(dp(260), -2))
        })
        for (t in listOf(resume, achievements, statsTile, home, closeGame, exit))
            tiles.addView(t, LinearLayout.LayoutParams(0, dp(80), 1f).apply { if (tiles.childCount > 0) marginStart = dp(8) })
        band.addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(band, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(48), 0, dp(48), dp(32)) })

        resume.setOnClickListener { onResume() }
        achievements.setOnClickListener { onAchievements() }
        statsTile.setOnClickListener { onToggleStats() }
        home.setOnClickListener { onHome() }
        exit.setOnClickListener { onExit() }
        closeGame.setOnClickListener {
            if (!armedClose) {
                armedClose = true
                setLabel(closeGame, "Aperte de novo")
                closeGame.postDelayed(disarm, 3000)
            } else {
                disarm.run()
                onCloseGame()
            }
        }
        closeGame.setOnFocusChangeListener { v, has -> focusLook(v, has); if (!has) disarm.run() }
    }

    private val disarm = Runnable {
        armedClose = false
        setLabel(closeGame, "Fechar jogo")
    }

    /** Open as far as input is concerned (the band may still be sliding out). */
    var isOpen = false
        private set

    /**
     * [game]: what is playing (null for Big Picture / desktop); [art]: PC address for its cover;
     * [canClose]: a game can be closed from here; [statsShown]: the performance panel is on.
     */
    fun open(name: String, streamLine: String, canClose: Boolean, statsShown: Boolean, game: LibraryGame?, art: Pair<String, Int>?) {
        title.text = name
        line = streamLine
        updateStats()
        closeGame.gone(!canClose)
        achievements.gone(game == null || game.nonSteam)
        setLabel(statsTile, if (statsShown) "Desempenho: ligado" else "Desempenho")
        cover.setImageDrawable(Jinky.rounded(Jinky.RAISED, dp(6).toFloat()))
        if (game != null && art != null) ArtLoader.load(art.first, art.second, game.id, "cover", 2) { bmp -> if (bmp != null) cover.setImageBitmap(bmp) }
        trackCard.gone(true)
        disarm.run()
        if (isOpen) return
        isOpen = true
        visibility = View.VISIBLE
        shade.alpha = 0f
        shade.animate().alpha(1f).setDuration(220).start()
        band.translationY = dp(60).toFloat()
        band.alpha = 0f
        band.animate().translationY(0f).alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        resume.requestFocusFromTouch()
    }

    /** The achievements followed in this game (filled in once the PC answers). */
    fun showTracked(items: List<Achievement>) {
        trackRows.removeAllViews()
        trackCard.gone(items.isEmpty())
        for (a in items) {
            trackRows.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply { setBackgroundColor(a.rarity.color) }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(7) })
                addView(text(9.5f, if (a.achieved) Jinky.MUTED else Jinky.TEXT, bold = true).apply {
                    text = a.name
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(mono(8f, a.rarity.soft).apply {
                    text = if (a.achieved) "✓" else if (a.percent >= 0) String.format(java.util.Locale("pt", "BR"), "%.1f%%", a.percent) else ""
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { if (trackRows.childCount > 0) topMargin = dp(5) })
        }
    }

    fun updateStream(streamLine: String) {
        line = streamLine
        updateStats()
    }

    private fun updateStats() {
        val battery = Jinky.padBattery()
        stats.text = if (battery != null) "$line   |   Controle $battery%" else line
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        disarm.run()
        shade.animate().alpha(0f).setDuration(180).start()
        band.animate().translationY(dp(40).toFloat()).alpha(0f).setDuration(200).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!isOpen) visibility = View.GONE }.start()
    }

    // --- tiles ---

    private fun tile(icon: Int, label: String, danger: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        tag = danger
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, dp(12).toFloat()))
            addState(intArrayOf(), Jinky.rounded(0xF010141F.toInt(), dp(12).toFloat()))
        }
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(if (danger) Jinky.RED else Jinky.TEXT, PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(dp(21), dp(21)))
        addView(text(10f, Jinky.TEXT, bold = true).apply { text = label; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(7) })
        setOnFocusChangeListener { v, has -> focusLook(v, has) }
    }

    private fun focusLook(v: View, has: Boolean) {
        val t = v as LinearLayout
        (t.getChildAt(0) as ImageView).setColorFilter(
            if (has) Jinky.INK else if (t.tag == true) Jinky.RED else Jinky.TEXT, PorterDuff.Mode.SRC_IN)
        (t.getChildAt(1) as TextView).setTextColor(if (has) Jinky.INK else Jinky.TEXT)
        v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(160).start()
    }

    private fun setLabel(t: LinearLayout, label: String) {
        (t.getChildAt(1) as TextView).text = label
    }
}
