package com.tclstream.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
 * The console home screen, PlayStation style: the focused game's artwork fills the screen, a row
 * of recently played games runs along the top, and the game's logo and a big Jogar button sit
 * below. Steam Big Picture, the PC's desktop and the full library are tiles in the same row.
 * Left/right moves along the row, A/OK plays, down reaches the Jogar button.
 */
class HomeScreen(
    context: Context,
    private val onPlay: (HomeHost, desktop: Boolean) -> Unit,
    private val onPlayGame: (HomeHost, LibraryGame) -> Unit,
    private val onWake: (HomeHost) -> Unit,
    private val onUpdate: (HomeHost) -> Unit,
    private val onSettings: () -> Unit,
    private val onLibrary: (HomeHost) -> Unit,
) : TvPage(context) {

    private sealed class Item {
        data class Game(val game: LibraryGame) : Item()
        object BigPicture : Item()
        object Desktop : Item()
        object Library : Item()
    }

    // Background: two layers for cross-fading artwork, under dark scrims that keep text readable.
    private val heroBack = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val heroFront = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }

    private val statusDot = View(context)
    private val statusText = text(16f, Color.WHITE)
    private val clock = text(20f, Color.WHITE)
    private val update = pill("Atualizar o Jinky").apply { visibility = View.GONE }
    private val updateNote = text(15f, StartScreen.ACCENT)
    private val settings = iconButton(R.drawable.ic_settings)

    private val rowItems = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(10), dp(14), dp(40), dp(14))  // room for the focused tile to grow
    }
    private val row = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        addView(rowItems)
    }

    private val logo = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxHeight = dp(120)
        maxWidth = dp(440)
    }
    private val title = text(44f, Color.WHITE, bold = true).apply {
        setShadowLayer(dp(12).toFloat(), 0f, 0f, 0xCC000000.toInt())
        maxLines = 2
    }
    private val meta = text(18f, 0xFFD5DCE6.toInt()).apply { setShadowLayer(dp(8).toFloat(), 0f, 0f, 0xCC000000.toInt()) }
    private val primary = playButton()

    private var host: HomeHost? = null
    private var waking = false
    private var games: List<LibraryGame>? = null
    private var art: Pair<String, Int>? = null  // PC address and port the artwork comes from
    private var items = emptyList<Pair<Item, View>>()
    private var selected: Item? = null
    private var shownHero: String? = null
    private var touched = false  // the user pressed something since the screen was opened

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) touched = true
        return super.dispatchKeyEvent(event)
    }

    init {
        addView(heroBack, LayoutParams(-1, -1))
        addView(heroFront, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF0070A0F.toInt(), 0xB0070A0F.toInt(), 0x30070A0F))
        }, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x90070A0F.toInt(), 0x00070A0F, 0x00070A0F, 0xF0070A0F.toInt()))
        }, LayoutParams(-1, -1))
        showHero(null)

        val root = pageRoot()
        // Top bar: brand, then the PC's status, update, clock and settings on the right.
        root.addView(brandHeader().apply {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(6), dp(16), dp(6))
                background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(99).toFloat() }
                addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
                addView(statusText)
            })
            addView(update, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            addView(clock, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(22) })
            addView(settings, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(16) })
        })
        root.addView(updateNote.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(-1, -2))
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        // The focused item: its logo (or name), a line about it, and the big button.
        root.addView(logo, LinearLayout.LayoutParams(-2, -2))
        root.addView(title, LinearLayout.LayoutParams(dp(640), -2))
        root.addView(meta, LinearLayout.LayoutParams(dp(640), -2).apply { topMargin = dp(10) })
        root.addView(primary, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22); bottomMargin = dp(12) })
        addView(root, LayoutParams(-1, -1))

        primary.id = View.generateViewId()
        settings.setOnClickListener { onSettings() }
        update.setOnClickListener { host?.let(onUpdate) }
        primary.setOnClickListener { activate(selected) }
        rebuildRow()
    }

    // --- state from MainActivity (every 500 ms) ---

    /** [updateStatus]: progress/result of an update in progress, else null. */
    fun show(h: HomeHost?, searching: Boolean, waking: Boolean, updateStatus: String? = null) {
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        updateNote.text = updateStatus ?: ""
        updateNote.gone(updateStatus == null)
        showUpdate(h)
        val previous = host
        host = h
        this.waking = waking
        when {
            h == null -> {
                setStatus(StartScreen.ACCENT, if (searching) "Procurando o PC" else "Nenhum PC")
                showMessage("Procurando seu PC", "Abra o Jinky no computador. A TV e o PC precisam estar na mesma rede de casa.", null)
            }
            h.status == HomeHost.Status.READY -> {
                setStatus(StartScreen.DONE, h.name)
                row.visibility = View.VISIBLE
                if (previous?.status != HomeHost.Status.READY) showItem(selected ?: items.firstOrNull()?.first)
            }
            h.status == HomeHost.Status.BUSY -> {
                val other = h.busyWith.ifBlank { "outra TV" }
                setStatus(StartScreen.WARN, "${h.name} · em uso")
                showMessage("Jogando em $other", "O jogo continua de onde está. Traga para esta TV quando quiser.", "Jogar aqui")
            }
            else -> {
                setStatus(StartScreen.HINT, if (waking) "${h.name} · ligando" else "${h.name} · desligado")
                showMessage(h.name,
                    when {
                        waking -> "Ligando o PC… pode levar até um minuto. A TV continua procurando sozinha."
                        h.mac != null -> "O PC está desligado ou dormindo."
                        else -> "Ligue o computador para jogar."
                    },
                    if (h.mac != null && !waking) "Ligar o PC" else null)
            }
        }
        // The PC came up (or changed): put focus where the user expects it.
        if (previous?.id != h?.id || previous?.status != h?.status) focusFirst()
    }

    /** Recently played games from the PC (null = not loaded / not available). */
    fun setGames(list: List<LibraryGame>?, pcIp: String, port: Int) {
        games = list
        art = pcIp to port
        val keep = selected
        rebuildRow()
        // Like a console: open on the most recently played game, unless the user already moved.
        val again = (if (touched) items.firstOrNull { it.first == keep } else null) ?: items.firstOrNull()
        if (host?.status == HomeHost.Status.READY && again != null) {
            selected = null
            if (isShown && (!touched || rowItems.hasFocus())) again.second.requestFocus() else showItem(again.first)
        }
    }

    /** A PC that isn't ready: no row, just a message and maybe one action. */
    private fun showMessage(heading: String, detail: String, action: String?) {
        row.visibility = View.INVISIBLE
        selected = null
        showHero(null)
        logo.visibility = View.GONE
        title.visibility = View.VISIBLE
        title.text = heading
        meta.text = detail
        primary.gone(action == null)
        if (action != null) primary.text = action
        primary.nextFocusUpId = settings.id
    }

    override fun focusFirst() {
        touched = false
        when {
            host?.status == HomeHost.Status.READY ->
                (items.firstOrNull { it.first == selected }?.second ?: items.firstOrNull()?.second)?.requestFocus()
            primary.visibility == View.VISIBLE -> primary.requestFocus()
            else -> settings.requestFocus()
        }
    }

    // --- the row ---

    private fun rebuildRow() {
        rowItems.removeAllViews()
        val list = mutableListOf<Item>()
        games?.take(RECENT)?.forEach { list += Item.Game(it) }
        if (!games.isNullOrEmpty()) list += Item.Library
        list += Item.BigPicture
        list += Item.Desktop
        items = list.mapIndexed { i, item ->
            val tile = tile(item)
            rowItems.addView(tile, LinearLayout.LayoutParams(dp(if (item is Item.Game) 104 else 156), dp(156)).apply {
                if (i > 0) marginStart = dp(16)
            })
            item to tile
        }
    }

    private fun tile(item: Item): FrameLayout = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        nextFocusDownId = primary.id
        background = GradientDrawable().apply {
            if (item is Item.Game) setColor(0xFF161E2A.toInt())
            else {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF1B2A44.toInt(), 0xFF0E1522.toInt())
            }
            cornerRadius = dp(14).toFloat()
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
        when (item) {
            is Item.Game -> {
                addView(TextView(context).apply {
                    text = item.game.name
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }, LayoutParams(-1, -1))
                val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(cover, LayoutParams(-1, -1))
                art?.let { (ip, port) ->
                    if (item.game.hasCover) ArtLoader.load(ip, port, item.game.id, "cover", 2) { bmp ->
                        if (bmp != null) {
                            cover.setImageBitmap(bmp)
                            if (bmp.width > bmp.height) cover.scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    }
                }
            }
            Item.BigPicture -> addView(tileLabel("Steam", "Big Picture"), LayoutParams(-1, -1))
            Item.Desktop -> addView(tileLabel("Área de\ntrabalho", "a tela do PC"), LayoutParams(-1, -1))
            Item.Library -> addView(tileLabel("Biblioteca", "${games?.size ?: 0} jogos"), LayoutParams(-1, -1))
        }
        foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(14).toFloat(); setStroke(dp(3), Color.TRANSPARENT)
        }
        setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.14f else 1f).scaleY(if (focused) 1.14f else 1f).setDuration(140).start()
            v.translationZ = if (focused) dp(10).toFloat() else 0f
            (v.foreground as GradientDrawable).setStroke(dp(3), if (focused) Color.WHITE else Color.TRANSPARENT)
            if (focused) {
                primary.nextFocusUpId = v.id
                showItem(item)
            }
        }
        setOnClickListener { activate(item) }
    }

    private fun tileLabel(name: String, detail: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(text(19f, Color.WHITE, bold = true).apply { text = name; gravity = Gravity.CENTER })
        addView(text(13f, 0xFFAAB6C8.toInt()).apply { text = detail; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
    }

    // --- the focused item ---

    private fun showItem(item: Item?) {
        if (host?.status != HomeHost.Status.READY || item == null) return
        val changed = item != selected
        selected = item
        primary.visibility = View.VISIBLE
        when (item) {
            is Item.Game -> {
                val g = item.game
                primary.text = "Jogar"
                meta.text = lastPlayedText(g.lastPlayed) + "   ·   Steam"
                if (changed) {
                    title.text = g.name
                    title.visibility = View.VISIBLE
                    logo.visibility = View.GONE
                    // Artwork only once the user pauses on a game, not while scrolling past it.
                    removeCallbacks(loadArt)
                    postDelayed(loadArt, 160)
                }
            }
            Item.BigPicture -> describe("Steam Big Picture", "Modo console com a interface da Steam. Sair do Big Picture encerra a sessão.", "Abrir")
            Item.Desktop -> describe("Área de trabalho", "Mostra a tela do PC como ela está, sem modo console.", "Abrir")
            Item.Library -> describe("Biblioteca", "Todos os ${games?.size ?: 0} jogos instalados no PC.", "Ver todos")
        }
    }

    private fun describe(name: String, detail: String, action: String) {
        removeCallbacks(loadArt)
        title.text = name
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        meta.text = detail
        primary.text = action
        showHero(null)
    }

    private val loadArt = Runnable {
        val g = (selected as? Item.Game)?.game ?: return@Runnable
        val (ip, port) = art ?: return@Runnable
        ArtLoader.load(ip, port, g.id, "hero", 1) { bmp ->
            if ((selected as? Item.Game)?.game?.id == g.id) showHero(bmp, "hero/${g.id}")
        }
        ArtLoader.load(ip, port, g.id, "logo", 1) { bmp ->
            if ((selected as? Item.Game)?.game?.id != g.id || bmp == null) return@load
            logo.setImageBitmap(bmp)
            logo.visibility = View.VISIBLE
            title.visibility = View.GONE
        }
    }

    /** Cross-fades to [bmp]; null shows the Jinky backdrop. */
    private fun showHero(bmp: Bitmap?, key: String? = null) {
        if (key == shownHero && (key != null || heroFront.drawable != null)) return
        shownHero = key
        heroBack.animate().cancel()
        heroBack.alpha = 0f
        if (bmp == null) {
            heroBack.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF13233F.toInt(), 0xFF0A1220.toInt(), 0xFF070A0F.toInt())))
        } else {
            heroBack.setImageBitmap(bmp)
        }
        heroBack.animate().alpha(1f).setDuration(320).withEndAction {
            heroFront.setImageDrawable(heroBack.drawable)
            heroFront.alpha = 1f
            heroBack.alpha = 0f
        }.start()
    }

    private fun activate(item: Item?) {
        val h = host ?: return
        when {
            h.status == HomeHost.Status.BUSY -> onPlay(h, false)
            h.status == HomeHost.Status.OFFLINE -> if (h.mac != null && !waking) onWake(h)
            item is Item.Game -> onPlayGame(h, item.game)
            item == Item.BigPicture -> onPlay(h, false)
            item == Item.Desktop -> onPlay(h, true)
            item == Item.Library -> onLibrary(h)
        }
    }

    private fun lastPlayedText(unix: Long): String {
        if (unix <= 0) return "Ainda não jogado"
        val days = (System.currentTimeMillis() / 1000 - unix) / 86400
        return when {
            days < 1 -> "Jogado hoje"
            days < 2 -> "Jogado ontem"
            days < 30 -> "Jogado há $days dias"
            else -> "Jogado em " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(unix * 1000))
        }
    }

    /** "Atualizar o Jinky" when the PC carries a newer TV app than this one. */
    private fun showUpdate(h: HomeHost?) {
        val v = h?.updateVersion ?: 0
        if (h == null || h.status == HomeHost.Status.OFFLINE || v <= BuildConfig.VERSION_CODE || h.updatePort == 0) {
            update.visibility = View.GONE
            return
        }
        update.text = "Atualizar · ${v / 10000}.${v / 100 % 100}.${v % 100}"
        update.visibility = View.VISIBLE
    }

    private fun setStatus(color: Int, label: String) {
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statusText.text = label
    }

    // --- views ---

    private fun playButton() = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(46), dp(14), dp(46), dp(14))
        minWidth = dp(200)
        setTextColor(focusColors(0xFF06101F.toInt(), Color.WHITE))
        background = states(Color.WHITE, 0x40FFFFFF, 99)
        stateListAnimator = null
    }

    private fun pill(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        minHeight = 0
        minimumHeight = 0
        setTextColor(focusColors(0xFF06101F.toInt(), Color.WHITE))
        background = states(StartScreen.ACCENT, 0x5528406B, 99)
        stateListAnimator = null
    }

    private fun iconButton(icon: Int) = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        background = states(Color.WHITE, 0x22FFFFFF, 99)
        val image = ImageView(context).apply { setImageResource(icon) }
        addView(image, LayoutParams(dp(26), dp(26), Gravity.CENTER))
        setOnFocusChangeListener { _, focused ->
            image.setColorFilter(if (focused) 0xFF06101F.toInt() else Color.WHITE)
        }
    }

    companion object {
        private const val RECENT = 10
    }
}
