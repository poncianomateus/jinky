package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.Executors

/** A game in the PC's Steam library. */
data class LibraryGame(
    val id: Int,
    val name: String,
    val lastPlayed: Long,
    val hasCover: Boolean,
    val state: State = State.INSTALLED,
    val progress: Double = 1.0,  // download progress while DOWNLOADING
    val family: Boolean = false,  // shared by another member of the Steam family
    val minutes: Int = 0,  // time played
    val running: Boolean = false,  // running on the PC right now
    val paused: Boolean = false,  // frozen on the PC, continues where it stopped ("Continuar")
    val pausedAt: Long = 0,  // when it was paused (Unix ms)
    val snapshot: Boolean = false,  // the PC has its last picture (/library/snapshot)
) {
    enum class State { INSTALLED, DOWNLOADING, NOT_INSTALLED }

    /** A "non-Steam game" added to the Steam library (negative ids). */
    val nonSteam get() = id < 0
}

/**
 * Biblioteca (design v2): search, filters (Instalados / Todos / Família / Fora da Steam), sorting,
 * a grid of covers and a panel about the focused game. Downloads show their progress on the cover
 * and update by themselves. A opens the game's page, Y searches, X pins to the home row, L1 goes
 * back to Início.
 */
class LibraryScreen(
    context: Context,
    private val pins: TvSettings,
    private val onOpen: (LibraryGame) -> Unit,
    private val onPlay: (LibraryGame) -> Unit,
    private val onBack: () -> Unit,
) : TvPage(context) {

    private enum class Filter(val label: String) {
        INSTALLED("Instalados"), ALL("Todos"), FAMILY("Família"), NON_STEAM("Fora da Steam")
    }

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val clock = mono(15f, Jinky.TEXT)
    private val homeTab = tab("Início")
    private val searchBox = EditText(context).apply {
        id = View.generateViewId()
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Jinky.font(context, 600)
        setTextColor(Jinky.TEXT)
        setHintTextColor(Jinky.MUTED)
        setPadding(dp(14), 0, dp(12), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.SURFACE, dp(17).toFloat(), dp(2), Jinky.TEXT))
            addState(intArrayOf(), Jinky.rounded(Jinky.SURFACE, dp(17).toFloat(), dp(1), Jinky.LINE))
        }
        val icon = context.getDrawable(R.drawable.ic_search)!!.mutate().apply {
            setBounds(0, 0, dp(12), dp(12))
            setColorFilter(Jinky.MUTED, PorterDuff.Mode.SRC_IN)
        }
        setCompoundDrawables(icon, null, null, null)
        compoundDrawablePadding = dp(8)
    }
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val sortChip = chip("")
    private val message = text(12f, Jinky.MUTED)
    private val grid = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false  // the focused cover grows past the grid's edge
        clipToPadding = false
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        clipChildren = false
        setPadding(dp(6), dp(8), dp(6), dp(40))  // room for the focus ring
        addView(grid, LayoutParams(-1, -2))
        viewTreeObserver.addOnScrollChangedListener { updateCovers() }
    }

    // Panel about the focused game.
    private val panelHero = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val panelLogo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; maxHeight = dp(55); maxWidth = dp(150) }
    private val panelName = text(13f, Jinky.TEXT, bold = true).apply { maxLines = 2 }
    private val statHours = mono(14f, Jinky.TEXT, 700)
    private val statHoursLabel = text(7.5f, Jinky.MUTED)
    private val statState = mono(14f, Jinky.GREEN, 700)
    private val statStateLabel = text(7.5f, Jinky.MUTED)
    private val panelText = text(9f, Jinky.SOFT).apply { setLineSpacing(0f, 1.35f) }
    private val panelPrimary = panelButton(R.drawable.ic_play, primary = true)
    private val panelPin = panelButton(R.drawable.ic_pin)
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val hints = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private var generation = 0  // ignores answers from an older load
    private var pc: Pair<String, Int>? = null
    private var all = emptyList<LibraryGame>()
    private var games = HashMap<Int, LibraryGame>()
    private var filter = Filter.INSTALLED
    private var alphabetical = false
    private var tiles = emptyList<View>()
    private val bars = HashMap<Int, View>()  // game id -> progress bar fill (downloads)
    private var focused: LibraryGame? = null
    // Covers load only near the visible part of the grid (hundreds of family games would not fit
    // in a Fire TV's memory) and are let go again far from it.
    private val tileGames = mutableListOf<Pair<FrameLayout, LibraryGame>>()
    private val coverLoaded = HashSet<FrameLayout>()

    // While something downloads, refresh the progress every few seconds.
    private val poll = object : Runnable {
        override fun run() {
            val (ip, port) = pc ?: return
            if (!isShown || bars.isEmpty()) return
            val gen = generation
            loader.execute {
                val fresh = try { fetchGames(ip, port) } catch (e: Exception) { null }
                main.post { if (gen == generation && fresh != null) updateProgress(fresh) }
            }
            main.postDelayed(this, 4000)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            if (isShown) main.postDelayed(this, 10_000)
        }
    }
    private val applySearch = Runnable { rebuild(keepFocus = false) }

    init {
        addView(View(context).apply { background = Jinky.scanlines(context, 4) }, LayoutParams(-1, -1))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.jinky_tile) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark_white) },
                LinearLayout.LayoutParams(dp(57), dp(19)).apply { marginStart = dp(8) })
            addView(homeTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(20) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(text(12.5f, Jinky.TEXT, bold = true).apply { text = "Biblioteca" })
                addView(View(context).apply { setBackgroundColor(Jinky.BLUE) },
                    LinearLayout.LayoutParams(dp(4) + dp(1) / 2, dp(4) + dp(1) / 2).apply { marginStart = dp(2); bottomMargin = dp(3) })
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clock)
        }
        addView(top, LayoutParams(-1, dp(36)).apply { setMargins(dp(48), dp(22), dp(48), 0) })

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(searchBox, LinearLayout.LayoutParams(dp(230), dp(34)))
            addView(chips, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(sortChip, LinearLayout.LayoutParams(-2, dp(28)))
        }
        addView(controls, LayoutParams(-1, dp(34)).apply { setMargins(dp(48), dp(75), dp(48), 0) })

        addView(message, LayoutParams(-2, -2).apply { setMargins(dp(54), dp(140), 0, 0) })
        addView(scroll, LayoutParams(dp(624), -1).apply { setMargins(dp(42), dp(123), 0, dp(40)) })

        // --- panel ---
        val heroBox = FrameLayout(context).apply {
            addView(panelHero, LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Jinky.surface(0x00), Jinky.SURFACE))
            }, LayoutParams(-1, -1))
            addView(panelLogo, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, dp(12), dp(4)) })
            addView(panelName, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(6)) })
        }
        panel.addView(heroBox, LinearLayout.LayoutParams(-1, dp(115)))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(9), dp(13), dp(14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(statBox(statHours, statHoursLabel), LinearLayout.LayoutParams(0, -2, 1f))
                addView(statBox(statState, statStateLabel), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            })
            addView(panelText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(panelPrimary, LinearLayout.LayoutParams(0, dp(34), 1f))
                addView(panelPin, LinearLayout.LayoutParams(-2, dp(34)).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })
        }
        panel.addView(body)
        panel.background = Jinky.rounded(Jinky.SURFACE, dp(13).toFloat())
        panel.clipToOutline = true
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(13).toFloat())
        }
        addView(panel, LayoutParams(dp(235), -2, Gravity.END).apply { setMargins(0, dp(131), dp(48), 0) })

        addView(hints, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(20)) })

        homeTab.setOnClickListener { onBack() }
        sortChip.setOnClickListener {
            alphabetical = !alphabetical
            rebuild(keepFocus = false)
            sortChip.requestFocus()
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(applySearch)
                main.postDelayed(applySearch, 250)
            }
        })
        searchBox.setOnEditorActionListener { _, _, _ ->
            main.removeCallbacks(applySearch)
            rebuild(keepFocus = false)
            tiles.firstOrNull()?.requestFocus()
            true
        }
        panelPrimary.setOnClickListener { focused?.let { g -> if (g.state == LibraryGame.State.INSTALLED) onPlay(g) else onOpen(g) } }
        panelPin.setOnClickListener { togglePin() }
        panel.visibility = View.INVISIBLE
        refreshHints()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !searchBox.hasFocus()) when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_Y -> { focusSearch(); return true }
            KeyEvent.KEYCODE_BUTTON_X -> { togglePin(); return true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { onBack(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Loads (or reloads) the library of the PC at [pcIp]; [search] then puts the cursor in the search box. */
    fun load(pcIp: String, port: Int, search: Boolean = false) {
        val gen = ++generation
        pc = pcIp to port
        grid.removeAllViews()
        tiles = emptyList()
        bars.clear()
        tileGames.clear()
        coverLoaded.clear()
        panel.visibility = View.INVISIBLE
        showMessage("Carregando seus jogos…")
        homeTab.requestFocus()
        main.removeCallbacks(tick)
        main.post(tick)
        loader.execute {
            val list = try {
                fetchGames(pcIp, port)
            } catch (e: Exception) {
                Log.w(TAG, "library: $e")
                null
            }
            main.post {
                if (gen != generation) return@post
                when {
                    list == null -> showMessage("Não foi possível ler a biblioteca do PC. Confira se o Jinky do PC está atualizado.")
                    list.isEmpty() -> showMessage("Nenhum jogo da Steam neste PC.")
                    else -> {
                        all = list
                        games = HashMap(list.associateBy { it.id })
                        buildChips()
                        rebuild(keepFocus = false)
                        if (search) focusSearch() else tiles.firstOrNull()?.requestFocusFromTouch()
                    }
                }
            }
        }
    }

    /** Puts the cursor in the search box (Y on the controller, or the search button at home). */
    fun focusSearch() {
        searchBox.requestFocus()
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.showSoftInput(searchBox, 0)
    }

    private fun showMessage(text: String?) {
        message.text = text ?: ""
        message.gone(text == null)
    }

    // --- filters, search, sorting ---

    private fun count(f: Filter) = all.count { matches(it, f) }

    private fun matches(g: LibraryGame, f: Filter) = when (f) {
        Filter.INSTALLED -> g.state != LibraryGame.State.NOT_INSTALLED
        Filter.ALL -> true
        Filter.FAMILY -> g.family
        Filter.NON_STEAM -> g.nonSteam
    }

    private fun buildChips() {
        chips.removeAllViews()
        for (f in Filter.values()) {
            val n = count(f)
            if (n == 0 && f != Filter.INSTALLED && f != Filter.ALL) continue
            val c = chip("${f.label}  $n").apply {
                tag = f
                setOnClickListener {
                    filter = f
                    refreshChips()
                    rebuild(keepFocus = false)
                    requestFocus()
                }
            }
            chips.addView(c, LinearLayout.LayoutParams(-2, dp(28)).apply { if (chips.childCount > 0) marginStart = dp(6) })
        }
        refreshChips()
    }

    private fun refreshChips() {
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i) as Button
            val on = c.tag == filter
            c.background = chipBackground(on)
            c.setTextColor(if (on) focusColors(Jinky.INK, Jinky.INK) else focusColors(Jinky.INK, Jinky.TEXT))
        }
        sortChip.text = if (alphabetical) "Ordem: A–Z" else "Ordem: jogados recentemente"
    }

    private fun normalized(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    private fun visible(): List<LibraryGame> {
        val query = normalized(searchBox.text.toString().trim())
        var list = all.filter { matches(it, filter) && (query.isEmpty() || normalized(it.name).contains(query)) }
        list = if (alphabetical) list.sortedBy { normalized(it.name) }
        // Installed (most recent first) before the rest, as the PC lists them.
        else list
        return list
    }

    private fun rebuild(keepFocus: Boolean) {
        val keepId = if (keepFocus) focused?.id else null
        grid.removeAllViews()
        bars.clear()
        tileGames.clear()
        coverLoaded.clear()
        val list = visible()
        searchBox.hint = "Buscar entre ${all.size} jogos"
        showMessage(if (list.isEmpty()) {
            if (searchBox.text.isNullOrBlank()) "Nada aqui por enquanto." else "Nenhum jogo com “${searchBox.text}”."
        } else null)
        val made = mutableListOf<View>()
        list.chunked(COLUMNS).forEachIndexed { r, rowGames ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            rowGames.forEachIndexed { c, game ->
                val tile = tile(game)
                made += tile
                row.addView(tile, LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply { if (c > 0) marginStart = dp(GAP) })
                if (game.hasCover) tileGames += tile to game
            }
            grid.addView(row, LinearLayout.LayoutParams(-2, -2).apply { if (r > 0) topMargin = dp(GAP_V) })
        }
        tiles = made
        // Up from the first row goes to the filters.
        made.take(COLUMNS).forEach { it.nextFocusUpId = searchBox.id }
        scroll.scrollTo(0, 0)
        scroll.post { updateCovers() }
        if (keepId != null) made.firstOrNull { it.tag == keepId }?.requestFocus()
        if (list.isEmpty()) panel.visibility = View.INVISIBLE
        main.removeCallbacks(poll)
        if (bars.isNotEmpty()) main.postDelayed(poll, 4000)
    }

    // --- tiles ---

    private fun tile(game: LibraryGame): FrameLayout = FrameLayout(context).apply {
        isFocusable = true
        isClickable = true
        tag = game.id
        background = Jinky.rounded(Jinky.RAISED, dp(9).toFloat())
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(9).toFloat())
        }
        // Until (or unless) the cover arrives: the name on a plain card, with where it comes from.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(text(9.5f, Jinky.TEXT, bold = true).apply { text = game.name }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(text(6.5f, if (game.nonSteam) Jinky.BLUE_SOFT else Jinky.MUTED, bold = true).apply {
                text = when {
                    game.nonSteam -> "FORA DA STEAM"
                    game.family -> "FAMÍLIA"
                    else -> "STEAM"
                }
                letterSpacing = 0.06f
            })
        }, LayoutParams(-1, -1))
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setTag(R.id.cover, true) }, LayoutParams(-1, -1))
        when (game.state) {
            LibraryGame.State.NOT_INSTALLED -> {
                // Not on the PC yet: a little dimmer, with a download badge.
                addView(View(context).apply { setBackgroundColor(Jinky.ink(0x66)) }, LayoutParams(-1, -1))
                addView(badge("Instalar"), LayoutParams(-2, dp(16), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(7) })
            }
            LibraryGame.State.DOWNLOADING -> addProgress(this, game)
            LibraryGame.State.INSTALLED -> {}
        }
        if (game.running) addView(badge("● NO PC", Jinky.GREEN), LayoutParams(-2, dp(16), Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
        if (game.id in pins.pinned) addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_pin)
            setColorFilter(Jinky.TEXT)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.ink(0xCC)) }
        }, LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(9).toFloat(); setStroke(dp(2) + dp(1) / 2, Color.TRANSPARENT)
        }
        setOnFocusChangeListener { v, has ->
            if (has) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(220).setInterpolator(OvershootInterpolator(1.3f)).start()
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(DecelerateInterpolator()).start()
            }
            v.elevation = if (has) dp(8).toFloat() else 0f
            (v.foreground as GradientDrawable).setStroke(dp(2) + dp(1) / 2, if (has) Color.WHITE else Color.TRANSPARENT)
            if (has) describe(games[game.id] ?: game)
        }
        setOnClickListener { onOpen(games[game.id] ?: game) }
    }

    /** Loads covers within a screen of the visible area; frees those far away. */
    private fun updateCovers() {
        val (ip, port) = pc ?: return
        val h = scroll.height.takeIf { it > 0 } ?: return
        val top = scroll.scrollY - h
        val bottom = scroll.scrollY + 2 * h
        for ((tile, game) in tileGames) {
            val row = tile.parent as? View ?: continue
            val y = row.top
            val near = y + row.height >= top && y <= bottom
            if (near && tile !in coverLoaded) {
                coverLoaded += tile
                ArtLoader.load(ip, port, game.id, "cover", 2) { bmp ->
                    if (bmp != null && tile in coverLoaded) showCover(tile, bmp)
                }
            } else if (!near && tile in coverLoaded && (y > bottom + 2 * h || y + row.height < top - 2 * h)) {
                coverLoaded -= tile
                coverImage(tile)?.setImageDrawable(null)
            }
        }
    }

    private fun coverImage(tile: FrameLayout) = (0 until tile.childCount).map { tile.getChildAt(it) }
        .firstOrNull { it.getTag(R.id.cover) == true } as? ImageView

    private fun addProgress(tile: FrameLayout, game: LibraryGame) {
        val track = FrameLayout(context).apply { background = Jinky.rounded(0x66000000, dp(2).toFloat()) }
        val fill = View(context).apply {
            background = Jinky.rounded(Jinky.BLUE, dp(2).toFloat())
            pivotX = 0f
            scaleX = game.progress.toFloat().coerceIn(0.02f, 1f)
        }
        track.addView(fill, LayoutParams(-1, -1))
        tile.addView(View(context).apply { setBackgroundColor(Jinky.ink(0x55)) }, LayoutParams(-1, -1))
        tile.addView(track, LayoutParams(-1, dp(4), Gravity.BOTTOM).apply { setMargins(dp(7), 0, dp(7), dp(8)) })
        bars[game.id] = fill
    }

    private fun badge(label: String, color: Int = Jinky.INK) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(if (color == Jinky.INK) Jinky.INK else color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 6.5f)
        typeface = Jinky.font(context, 800)
        setPadding(dp(7), 0, dp(7), 0)
        background = Jinky.rounded(if (color == Jinky.INK) Jinky.TEXT else Jinky.ink(0xDB), dp(8).toFloat())
    }

    private fun showCover(tile: FrameLayout, bmp: android.graphics.Bitmap) {
        val image = coverImage(tile) ?: return
        image.setImageBitmap(bmp)
        // A landscape banner (games without a portrait cover) is shown whole on the dark card.
        if (bmp.width > bmp.height) image.scaleType = ImageView.ScaleType.FIT_CENTER
        if (games[tile.tag as Int]?.state == LibraryGame.State.NOT_INSTALLED) {
            image.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.35f) })
        }
    }

    // --- the panel ---

    private fun describe(g: LibraryGame) {
        val changed = focused?.id != g.id
        focused = g
        panel.visibility = View.VISIBLE
        panelName.text = g.name
        statHours.text = Jinky.hours(g.minutes).ifEmpty { "—" }
        statHoursLabel.text = "jogadas"
        when (g.state) {
            LibraryGame.State.INSTALLED -> {
                statState.text = if (g.running) "no PC" else "pronto"
                statState.setTextColor(Jinky.GREEN)
                statStateLabel.text = if (g.running) "rodando agora" else "instalado"
                panelPrimary.text = if (g.running) "Continuar" else "Jogar"
                panelPrimary.isEnabled = true
            }
            LibraryGame.State.DOWNLOADING -> {
                statState.text = "${(g.progress * 100).toInt()}%"
                statState.setTextColor(Jinky.BLUE_SOFT)
                statStateLabel.text = "baixando no PC"
                panelPrimary.text = "Baixando…"
                panelPrimary.isEnabled = false
            }
            LibraryGame.State.NOT_INSTALLED -> {
                statState.text = "—"
                statState.setTextColor(Jinky.MUTED)
                statStateLabel.text = if (g.family) "da família" else "não instalado"
                panelPrimary.text = "Instalar"
                panelPrimary.isEnabled = true
            }
        }
        panelText.text = buildString {
            append(lastPlayedText(g.lastPlayed))
            append(when {
                g.nonSteam -> "\nAdicionado à Steam (fora da loja)"
                g.family -> "\nCompartilhado pela família"
                else -> ""
            })
        }
        panelPin.text = if (g.id in pins.pinned) "Fixado" else "Fixar"
        panelPin.gone(g.state == LibraryGame.State.NOT_INSTALLED)
        if (changed) {
            panelHero.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF102246.toInt(), Jinky.SURFACE)))
            panelLogo.visibility = View.GONE
            panelName.visibility = View.VISIBLE
            main.removeCallbacks(loadPanelArt)
            main.postDelayed(loadPanelArt, 180)
        }
    }

    private val loadPanelArt = Runnable {
        val g = focused ?: return@Runnable
        val (ip, port) = pc ?: return@Runnable
        ArtLoader.load(ip, port, g.id, "hero", 2) { bmp ->
            if (focused?.id == g.id && bmp != null) {
                panelHero.setImageBitmap(bmp)
                panelHero.alpha = 0f
                panelHero.animate().alpha(1f).setDuration(250).start()
            }
        }
        ArtLoader.load(ip, port, g.id, "logo", 2) { bmp ->
            if (focused?.id != g.id || bmp == null) return@load
            panelLogo.setImageBitmap(bmp)
            panelLogo.visibility = View.VISIBLE
            panelName.visibility = View.GONE
        }
    }

    private fun togglePin() {
        val g = focused ?: return
        if (g.state == LibraryGame.State.NOT_INSTALLED) return
        pins.togglePin(g.id)
        val pinFocused = panelPin.hasFocus()
        rebuild(keepFocus = true)
        describe(g)
        if (pinFocused) panelPin.requestFocus()
    }

    /** Fresh numbers from the PC: move the bars; a finished download becomes playable. */
    private fun updateProgress(fresh: List<LibraryGame>) {
        for (g in fresh) {
            val old = games[g.id] ?: continue
            games[g.id] = g
            bars[g.id]?.let { bar ->
                bar.animate().scaleX(g.progress.toFloat().coerceIn(0.02f, 1f)).setDuration(600).start()
                if (g.state == LibraryGame.State.INSTALLED) {
                    // Done: drop the dimming and the bar.
                    val tile = tiles.firstOrNull { it.tag == g.id } as? FrameLayout
                    tile?.let { t -> while (t.childCount > 2) t.removeViewAt(2) }
                    bars.remove(g.id)
                }
            }
            if (g.id == focused?.id && (old.state != g.state || old.progress != g.progress)) describe(g)
        }
        all = all.map { games[it.id] ?: it }
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

    private fun refreshHints() {
        hints.removeAllViews()
        fun add(v: View) = hints.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (hints.childCount > 0) marginStart = dp(16) })
        add(Jinky.hint(context, Jinky.Button.OK, "Abrir"))
        add(Jinky.hint(context, Jinky.Button.SEARCH, "Buscar"))
        add(Jinky.hint(context, Jinky.Button.PIN, "Fixar no início"))
        add(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mono(7.5f, Jinky.TEXT, 700).apply {
                text = "L1"
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = Jinky.rounded(Jinky.LINE, dp(4).toFloat())
            })
            addView(text(9f, Jinky.SOFT).apply { text = "Início" }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
        })
    }

    // --- views ---

    private fun statBox(value: TextView, label: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(9), dp(7), dp(9), dp(7))
        background = Jinky.rounded(Jinky.RAISED, dp(8).toFloat())
        addView(value)
        addView(label)
    }

    private fun chipBackground(on: Boolean) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, dp(14).toFloat(), dp(2), Jinky.BLUE))
        addState(intArrayOf(), if (on) Jinky.rounded(Jinky.TEXT, dp(14).toFloat()) else Jinky.rounded(Jinky.SURFACE, dp(14).toFloat()))
    }

    private fun chip(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        typeface = Jinky.font(context, 700)
        setPadding(dp(11), 0, dp(11), 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextColor(focusColors(Jinky.INK, Jinky.TEXT))
        background = chipBackground(false)
        stateListAnimator = null
    }

    private fun tab(label: String) = TextView(context).apply {
        text = label
        isFocusable = true
        isClickable = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Jinky.font(context, 600)
        setTextColor(focusColors(Jinky.INK, Jinky.MUTED))
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, dp(99).toFloat()))
            addState(intArrayOf(), Jinky.rounded(Color.TRANSPARENT, dp(99).toFloat()))
        }
    }

    private fun panelButton(icon: Int, primary: Boolean = false) = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 12f else 9.5f)
        typeface = Jinky.font(context, 800)
        setPadding(dp(12), 0, dp(14), 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextColor(focusColors(Jinky.INK, Jinky.TEXT))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, dp(17).toFloat()))
            addState(intArrayOf(), Jinky.rounded(Jinky.RAISED, dp(17).toFloat()))
        }
        stateListAnimator = null
        val d = context.getDrawable(icon)!!.mutate()
        val size = dp(if (primary) 11 else 10)
        d.setBounds(0, 0, size, size)
        d.setColorFilter(Jinky.TEXT, PorterDuff.Mode.SRC_IN)
        setCompoundDrawables(d, null, null, null)
        compoundDrawablePadding = dp(6)
        setOnFocusChangeListener { _, has -> d.setColorFilter(if (has) Jinky.INK else Jinky.TEXT, PorterDuff.Mode.SRC_IN) }
    }

    override fun focusFirst() {
        (tiles.firstOrNull() ?: homeTab).requestFocusFromTouch()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(poll)
        main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        main.removeCallbacks(poll)
        main.removeCallbacks(tick)
        if (visibility == View.VISIBLE) {
            main.post(tick)
            refreshHints()
            if (bars.isNotEmpty()) main.postDelayed(poll, 1000)
        }
    }

    companion object {
        private const val TAG = "Jinky"
        private const val COLUMNS = 7
        private const val TILE_W = 76
        private const val TILE_H = 114
        private const val GAP = 12
        private const val GAP_V = 14

        fun fetchGames(pcIp: String, port: Int): List<LibraryGame> {
            val c = URL("http://$pcIp:$port/library").openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 20000
            val json = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            c.disconnect()
            val list = org.json.JSONObject(json).getJSONArray("games")
            return (0 until list.length()).map {
                val g = list.getJSONObject(it)
                LibraryGame(g.getInt("id"), g.getString("name"), g.optLong("lastPlayed"), g.optBoolean("cover"),
                    when (g.optString("state")) {
                        "downloading" -> LibraryGame.State.DOWNLOADING
                        "notInstalled" -> LibraryGame.State.NOT_INSTALLED
                        else -> LibraryGame.State.INSTALLED
                    },
                    g.optDouble("progress", 1.0),
                    g.optBoolean("family"),
                    g.optInt("minutes"),
                    g.optBoolean("running"),
                    g.optBoolean("paused"),
                    g.optLong("pausedAt"),
                    g.optBoolean("snapshot"))
            }
        }

        private fun post(pcIp: String, port: Int, path: String): String? = try {
            val c = URL("http://$pcIp:$port$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 8000
            val code = c.responseCode
            c.disconnect()
            if (code == 200) null else "o PC respondeu $code"
        } catch (e: Exception) {
            e.message ?: e.toString()
        }

        /** Asks the PC to start [game] (now, or when the session that is opening begins). */
        fun launch(pcIp: String, port: Int, game: LibraryGame): String? = post(pcIp, port, "/library/launch?id=${game.id}")

        /** A paused game's last picture ([LibraryGame.pausedAt] keeps an older one out of the cache). */
        fun snapshotUrl(pcIp: String, port: Int, game: LibraryGame) =
            "http://$pcIp:$port/library/snapshot?id=${game.id}&t=${game.pausedAt}"

        /** Closes a game that is paused on the PC (to make room for another). */
        fun closePaused(pcIp: String, port: Int, gameId: Int): String? = post(pcIp, port, "/library/close?id=$gameId")

        /** Asks the PC to install [game] (Steam shows its confirmation in the session). */
        fun install(pcIp: String, port: Int, game: LibraryGame): String? = post(pcIp, port, "/library/install?id=${game.id}")
    }
}
