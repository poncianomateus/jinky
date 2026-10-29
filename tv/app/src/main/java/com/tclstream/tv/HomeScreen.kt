package com.tclstream.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
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
    val port: Int = 0,  // its streaming port (several Jinkys, one per Windows user, can share a PC)
    val pairingKnown: Boolean = false,  // the PC said whether it has this TV paired (newer PCs)
    val pairedHere: Boolean = false,
) {
    enum class Status { READY, BUSY, OFFLINE }
}

/**
 * The console home screen (design v2). The focused game's artwork fills the screen; along the top,
 * the game running on the PC, then the games the user pinned, then the recently played ones, then
 * Biblioteca, Steam Big Picture and the PC's desktop. The focused tile grows and pushes the others
 * aside, like a console. Below: the game's logo, how long it has been played, and Jogar.
 *
 * Controller: A opens the game's page, Y searches the library, X pins/unpins, R1 goes to the
 * Biblioteca tab, L1 to Amigos. Nothing here is an advert: only the user's own games.
 */
class HomeScreen(
    context: Context,
    private val pins: TvSettings,
    private val onPlay: (HomeHost, desktop: Boolean) -> Unit,
    private val onPlayGame: (HomeHost, LibraryGame) -> Unit,
    private val onOpenGame: (HomeHost, LibraryGame) -> Unit,
    private val onWake: (HomeHost) -> Unit,
    private val onUpdate: (HomeHost) -> Unit,
    private val onSettings: () -> Unit,
    private val onLibrary: (HomeHost, search: Boolean) -> Unit,
    private val onProfile: () -> Unit,
    private val onFriends: () -> Unit,
    private val onChoosePc: () -> Unit,
    private val onDownloads: () -> Unit = {},
) : TvPage(context) {

    private sealed class Item {
        data class Game(val game: LibraryGame) : Item()
        object BigPicture : Item()
        object Desktop : Item()
        object Library : Item()
        object Downloads : Item()
    }

    // Background: two layers for cross-fading artwork, under scrims that keep text readable.
    // (Hardware layers: crossfades and the slow drift move a ready texture instead of redrawing.)
    private val heroBack = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; setLayerType(View.LAYER_TYPE_HARDWARE, null) }
    private val heroFront = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; setLayerType(View.LAYER_TYPE_HARDWARE, null) }
    // The focused game's own colour, glowing softly from the lower left, like the PS5's home.
    private val ambient = View(context)
    private var ambientColor = 0
    private var ambientAnim: ValueAnimator? = null
    private var drift: android.animation.AnimatorSet? = null

    private val chipBars = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    private val statusText = text(9.5f, Jinky.TEXT, bold = true)
    private val clock = mono(15f, Jinky.TEXT)
    private val update = pill("Atualizar o Jinky").apply { visibility = View.GONE }
    private val updateNote = text(8.5f, Jinky.BLUE_SOFT)
    private val search = iconButton(R.drawable.ic_search)
    private val settings = iconButton(R.drawable.ic_settings)
    private val avatarImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val avatarInitial = text(11f, Jinky.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    private val profileButton = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.BLUE_DEEP) }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        addView(avatarInitial, LayoutParams(-1, -1))
        addView(avatarImage, LayoutParams(-1, -1))
        foreground = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Color.TRANSPARENT) }
        setOnFocusChangeListener { v, has ->
            (v.foreground as GradientDrawable).setStroke(dp(2) + dp(1) / 2, if (has) Color.WHITE else Color.TRANSPARENT)
            v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(160).start()
        }
    }
    private val libraryTab = tab("Biblioteca")
    private val friendsTab = tab("Amigos")
    // The controller's battery (Android 12+), lowest of the connected ones; hidden when unknown.
    private val padIcon = ImageView(context).apply { setImageResource(R.drawable.ic_gamepad) }
    private val padText = mono(10f, Jinky.SOFT, 600)
    private val padChip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(padIcon, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(4) })
        addView(padText)
    }
    private val friendsBadge = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
        typeface = Jinky.mono(context, 700)
        setTextColor(Jinky.INK)
        gravity = Gravity.CENTER
        setPadding(dp(5), 0, dp(5), 0)
        background = Jinky.rounded(Jinky.GREEN, dp(8).toFloat())
        visibility = View.GONE
    }

    private val rowItems = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(48), dp(4), dp(48), dp(8))
    }
    private val row = object : HorizontalScrollView(context) {
        // Focus changes don't jump the row; glideRowTo moves it so the focused tile sits near the
        // left edge, like the PS5.
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0
    }.apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        addView(rowItems)
    }

    private val logo = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxHeight = dp(75)
        maxWidth = dp(260)
    }
    private val title = text(28f, Jinky.TEXT, bold = true).apply {
        setShadowLayer(dp(10).toFloat(), 0f, 0f, 0xCC000000.toInt())
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val metaDot = View(context)
    private val metaText = text(10.5f, Jinky.SOFT, bold = false)
    private val metaHours = mono(10.5f, Jinky.SOFT)
    private val meta = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(metaDot, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(6) })
        addView(metaText)
        addView(metaHours, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }
    private val primary = actionButton(R.drawable.ic_play, primary = true)
    private val details = actionButton(null).apply { text = "Detalhes" }
    private val pinButton = actionButton(R.drawable.ic_pin)
    private val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val hints = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    // Live downloads on the PC: the card at the bottom left (instead of the week line) and the
    // "Downloads" tile in the row.
    private val downloadCard = DownloadCard(context)
    private var downloads: DownloadsState? = null
    private var downloadsTileText: View? = null
    private var downloadsTileBar: DownloadBar? = null
    private var week: LibraryWeek? = null

    // "Esta semana": play time over the last days on this PC, and the game played most.
    private val weekLabel = mono(8.5f, Jinky.BLUE_SOFT, 700).apply { letterSpacing = 0.18f }
    private val weekTime = mono(13f, Jinky.TEXT, 700)
    private val weekTop = text(9.5f, Jinky.SOFT, bold = false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val weekLine = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(weekLabel)
        addView(weekTime, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(9) })
        addView(weekTop, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(9) })
    }
    private var hintsForPs: Boolean? = null

    private var rowGlide: ValueAnimator? = null
    private var heroTop: ImageView? = null  // the artwork layer currently in front
    private val sizeAnims = HashMap<View, ValueAnimator>()

    private var host: HomeHost? = null
    private var waking = false
    private var games: List<LibraryGame>? = null
    private var art: Pair<String, Int>? = null  // PC address and port the artwork comes from
    private var items = emptyList<Pair<Item, View>>()
    private var selected: Item? = null
    private var shownHero: String? = null
    private var touched = false  // the user pressed something since the screen was opened

    init {
        addView(heroBack, LayoutParams(-1, -1))
        addView(heroFront, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Jinky.ink(0xF7), Jinky.ink(0xBD), Jinky.ink(0x26), Jinky.ink(0x4D)))
        }, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Jinky.ink(0xE0), Jinky.ink(0x00), Jinky.ink(0x00), Jinky.ink(0xF2)))
        }, LayoutParams(-1, -1))
        addView(View(context).apply { background = Jinky.scanlines(context) }, LayoutParams(-1, -1))
        addView(ambient, LayoutParams(-1, -1))
        setAmbient(Jinky.BLUE_DEEP)
        showHero(null)

        // --- top bar ---
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.jinky_tile) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(ImageView(context).apply { setImageResource(R.drawable.jinky_wordmark_white) },
                LinearLayout.LayoutParams(dp(57), dp(19)).apply { marginStart = dp(8) })
            addView(activeTab("Início"), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(30) })
            addView(libraryTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            addView(friendsTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
            addView(friendsBadge, LinearLayout.LayoutParams(-2, dp(15)).apply { marginStart = dp(1) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(update, LinearLayout.LayoutParams(-2, dp(28)).apply { marginEnd = dp(10) })
            // The PC chip: OK opens "Escolher PC" (a PC can run one Jinky per Windows user).
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), 0, dp(13), 0)
                isFocusable = true
                isClickable = true
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, dp(14).toFloat()))
                    addState(intArrayOf(), Jinky.rounded(Jinky.GLASS, dp(14).toFloat()))
                }
                setOnClickListener { onChoosePc() }
                addView(chipBars, LinearLayout.LayoutParams(-2, dp(9)).apply { marginEnd = dp(7) })
                addView(statusText.apply {
                    isDuplicateParentStateEnabled = true
                    setTextColor(focusColors(Jinky.INK, Jinky.TEXT))
                })
            }, LinearLayout.LayoutParams(-2, dp(28)))
            addView(search, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) })
            addView(profileButton, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) })
            addView(settings, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) })
            addView(padChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            addView(clock, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        addView(top, LayoutParams(-1, dp(36)).apply { setMargins(dp(48), dp(22), dp(48), 0) })
        addView(updateNote.apply { gravity = Gravity.END }, LayoutParams(-1, -2).apply { setMargins(dp(48), dp(60), dp(48), 0) })

        addView(row, LayoutParams(-1, dp(196)).apply { topMargin = dp(66) })

        // --- the focused item ---
        info.addView(logo, LinearLayout.LayoutParams(-2, -2))
        info.addView(title, LinearLayout.LayoutParams(dp(470), -2))
        info.addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        info.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(primary, LinearLayout.LayoutParams(-2, dp(42)))
            addView(details, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(8) })
            addView(pinButton, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        addView(info, LayoutParams(dp(600), -2).apply { setMargins(dp(48), dp(268), 0, 0) })

        addView(hints, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(48), dp(20)) })
        addView(weekLine, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(48), 0, 0, dp(20)) })
        addView(downloadCard, LayoutParams(dp(470), -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(48), 0, 0, dp(18)) })

        for (b in listOf(primary, details, pinButton)) b.id = View.generateViewId()
        libraryTab.id = View.generateViewId()
        friendsTab.id = View.generateViewId()
        friendsTab.setOnClickListener { onFriends() }
        settings.setOnClickListener { onSettings() }
        profileButton.setOnClickListener { onProfile() }
        avatarInitial.text = "?"
        search.setOnClickListener { host?.let { onLibrary(it, true) } }
        libraryTab.setOnClickListener { host?.let { onLibrary(it, false) } }
        update.setOnClickListener { host?.let(onUpdate) }
        primary.setOnClickListener { activate(selected, play = true) }
        details.setOnClickListener { activate(selected, play = false) }
        pinButton.setOnClickListener { togglePin() }
        rebuildRow()
        refreshHints()
    }

    // While the player moves around, the drift waits: navigation gets the whole GPU.
    private val resumeDrift = Runnable { if (isShown) drift?.resume() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            drift?.pause()
            removeCallbacks(resumeDrift)
            postDelayed(resumeDrift, 2500)
            touched = true
            val h = host
            // Amigos works while another TV plays on the PC too.
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 && h != null && h.status != HomeHost.Status.OFFLINE) { onFriends(); return true }
            if (h != null && h.status == HomeHost.Status.READY) when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_Y -> { onLibrary(h, true); return true }
                KeyEvent.KEYCODE_BUTTON_X -> { togglePin(); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { onLibrary(h, false); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // --- state from MainActivity (every 500 ms) ---

    private var padCheckedAt = 0L

    private fun showPadBattery() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - padCheckedAt < 5000) return
        padCheckedAt = now
        val pct = PadBattery.lowest()
        padChip.gone(pct == null)
        if (pct == null) return
        val charging = PadBattery.charging()
        val color = when {
            charging -> Jinky.GREEN
            pct <= 15 -> Jinky.RED
            pct <= 30 -> Jinky.AMBER
            else -> Jinky.SOFT
        }
        padText.text = if (charging) "$pct% ⚡" else "$pct%"
        padText.setTextColor(color)
        padIcon.setColorFilter(color)
    }

    /** Friends playing right now: a small green count next to the Amigos tab (0 hides it). */
    fun setFriendsPlaying(n: Int) {
        friendsBadge.text = n.toString()
        friendsBadge.gone(n <= 0)
    }

    /** [updateStatus]: progress/result of an update in progress, else null. */
    fun show(h: HomeHost?, searching: Boolean, waking: Boolean, updateStatus: String? = null, unpairedAround: Int = 0) {
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        showPadBattery()
        updateNote.text = updateStatus ?: ""
        updateNote.gone(updateStatus == null)
        showUpdate(h)
        refreshHints()
        val previous = host
        host = h
        this.waking = waking
        when {
            h == null && unpairedAround > 0 -> {
                // PCs answer, but none is paired with this TV: they only show once paired.
                setStatus(Jinky.BLUE, 1, "Parear um PC")
                showMessage("Nenhum PC pareado com esta TV",
                    "Há ${if (unpairedAround == 1) "1 PC" else "$unpairedAround PCs"} com o Jinky na rede. " +
                        "Aperte OK em \"Parear um PC\", no topo, e confirme o código no Jinky do PC.", null)
            }
            h == null -> {
                setStatus(Jinky.BLUE, 1, if (searching) "Procurando o PC" else "Nenhum PC")
                showMessage("Procurando seu PC", "Abra o Jinky no computador. A TV e o PC precisam estar na mesma rede de casa.", null)
            }
            h.status == HomeHost.Status.READY -> {
                setStatus(Jinky.GREEN, 4, h.name)
                row.visibility = View.VISIBLE
                if (previous?.status != HomeHost.Status.READY) showItem(selected ?: items.firstOrNull()?.first)
            }
            h.status == HomeHost.Status.BUSY -> {
                val other = h.busyWith.ifBlank { "outra TV" }
                setStatus(Jinky.AMBER, 3, "${h.name} · em uso")
                showMessage("Jogando em $other", "O jogo continua de onde está. Traga para esta TV quando quiser.", "Jogar aqui")
            }
            else -> {
                setStatus(Jinky.DIM, 0, if (waking) "${h.name} · ligando" else "${h.name} · desligado")
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

    /** The PC's games (null = not loaded / not available). */
    /** The PC's downloads, every second or so while something downloads. */
    fun setDownloads(state: DownloadsState?) {
        val had = downloads?.any == true
        downloads = state
        val (ip, port) = art ?: ("" to 0)
        downloadCard.show(state, ip, port)
        setWeek(week)
        if (had != (state?.any == true) && games != null) {
            // The Downloads tile comes or goes: rebuild the row, keeping the focus where it was.
            val keep = selected
            val focusedRow = rowItems.hasFocus()
            rebuildRow()
            val keepId = (keep as? Item.Game)?.game?.id
            val again = items.firstOrNull { (k, _) -> k == keep || (keepId != null && (k as? Item.Game)?.game?.id == keepId) }
                ?: items.firstOrNull()
            if (again != null) {
                selected = null
                if (focusedRow) again.second.requestFocus() else showItem(again.first)
            }
            return
        }
        val pct = state?.active?.percent
        (downloadsTileText as? LinearLayout)?.let { l ->
            (l.getChildAt(l.childCount - 1) as? android.widget.TextView)?.text = if (pct != null) "$pct%" else "na fila"
        }
        downloadsTileBar?.paused = state?.active?.paused == true
        downloadsTileBar?.setProgress((pct ?: 0) / 100f)
    }

    /** The week's play time on this PC (hidden until the PC has a record, or nothing was played). */
    fun setWeek(w: LibraryWeek?) {
        week = w
        val time = Jinky.hours(w?.minutes ?: 0)
        weekLine.gone(w == null || w.days <= 0 || time.isEmpty() || downloads?.active != null)
        if (w == null || time.isEmpty()) return
        weekLabel.text = Jinky.weekLabel(w.days).uppercase()
        weekTime.text = time
        weekTop.text = if (w.topName.isNotEmpty()) "· mais jogado: ${w.topName}" else ""
    }

    fun setGames(list: List<LibraryGame>?, pcIp: String, port: Int) {
        setWeek(if (list == null) null else LibraryScreen.lastWeek)
        games = list?.filter { it.state != LibraryGame.State.NOT_INSTALLED }
        art = pcIp to port
        val keep = selected
        rebuildRow()
        row.scrollX = 0
        if (isShown && !touched) animateIn()
        // Like a console: open on the game running on the PC, else the most recently played one,
        // unless the user already moved.
        val again = (if (touched) items.firstOrNull { it.first == keep } else null) ?: items.firstOrNull()
        if (host?.status == HomeHost.Status.READY && again != null) {
            selected = null
            if (isShown && (!touched || rowItems.hasFocus())) again.second.requestFocusFromTouch() else showItem(again.first)
        }
    }

    /** The Steam account's name and picture, for the profile button. */
    fun setProfile(name: String, avatarUrl: String) {
        avatarInitial.text = name.take(1).uppercase().ifEmpty { "?" }
        if (avatarUrl.isNotBlank()) ArtLoader.loadUrl(avatarUrl, 2) { bmp -> if (bmp != null) avatarImage.setImageBitmap(bmp) }
    }

    /** Rebuilds the row (pins changed elsewhere), keeping the game the user was on. */
    fun refreshRow() {
        if (games == null) return
        val keep = (selected as? Item.Game)?.game?.id
        rebuildRow()
        val again = items.firstOrNull { (it.first as? Item.Game)?.game?.id == keep } ?: items.firstOrNull()
        selected = null
        if (again != null) showItem(again.first)
    }

    /** A PC that isn't ready: no row, just a message and maybe one action. */
    private fun showMessage(heading: String, detail: String, action: String?) {
        row.visibility = View.INVISIBLE
        selected = null
        showHero(null)
        logo.visibility = View.GONE
        title.visibility = View.VISIBLE
        title.text = heading
        setMeta(null, detail, "")
        primary.gone(action == null)
        details.gone(true)
        pinButton.gone(true)
        if (action != null) primary.text = action
        primary.nextFocusUpId = settings.id
    }

    override fun focusFirst() {
        touched = false
        when {
            host?.status == HomeHost.Status.READY ->
                (items.firstOrNull { it.first == selected }?.second ?: items.firstOrNull()?.second)?.requestFocusFromTouch()
            primary.visibility == View.VISIBLE -> primary.requestFocusFromTouch()
            else -> settings.requestFocusFromTouch()
        }
    }

    // --- the row ---

    private fun isDescendant(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === rowItems) return true
            p = p.parent
        }
        return false
    }

    private fun rebuildRow() {
        rowGlide?.cancel()
        rowItems.removeAllViews()
        sizeAnims.values.forEach { it.cancel() }
        sizeAnims.clear()
        val installed = games.orEmpty()
        val paused = installed.filter { it.paused }
        val running = installed.firstOrNull { it.running && !it.paused }
        // Pinned on this TV, then the PC app's favourites.
        val pinnedIds = pins.pinned + installed.filter { it.favorite && it.id !in pins.pinned }.map { it.id }
        val pinned = pinnedIds.mapNotNull { id -> installed.firstOrNull { it.id == id } }.filter { it != running && !it.paused }
        val recent = installed.filter { it != running && it !in pinned && !it.paused }.take(RECENT)
        val list = mutableListOf<Pair<Item, View>>()

        fun section(label: String?, part: List<Item>, divider: Boolean) {
            if (part.isEmpty()) return
            if (divider && rowItems.childCount > 0) {
                rowItems.addView(View(context).apply { setBackgroundColor(0x24FFFFFF) },
                    LinearLayout.LayoutParams(dp(1), dp(H)).apply { setMargins(dp(14), dp(LABEL), dp(14), 0) })
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            column.addView(mono(8f, Jinky.MUTED, 700).apply {
                text = label ?: ""
                letterSpacing = 0.18f
                gravity = Gravity.BOTTOM
            }, LinearLayout.LayoutParams(-2, dp(LABEL)).apply { bottomMargin = dp(4) })
            val tiles = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            part.forEachIndexed { i, item ->
                val tile = tile(item)
                tiles.addView(tile, LinearLayout.LayoutParams(dp(W), dp(H)).apply { if (i > 0) marginStart = dp(GAP) })
                list += item to tile
            }
            column.addView(tiles, LinearLayout.LayoutParams(-2, -2))
            rowItems.addView(column, LinearLayout.LayoutParams(-2, -2).apply { if (!divider && rowItems.childCount > 0) marginStart = dp(GAP) })
        }

        section("PAUSADOS", paused.map { Item.Game(it) }, divider = false)
        if (running != null) section(null, listOf(Item.Game(running)), divider = paused.isNotEmpty())
        section("FIXADOS", pinned.map { Item.Game(it) }, divider = paused.isNotEmpty() && running == null)
        section("RECENTES", recent.map { Item.Game(it) }, divider = pinned.isNotEmpty() || running != null || paused.isNotEmpty())
        section(null, buildList {
            if (installed.isNotEmpty()) add(Item.Library)
            if (downloads?.any == true) add(Item.Downloads)
            if (LibraryScreen.steamOnPc) add(Item.BigPicture)
            add(Item.Desktop)
        }, divider = installed.isNotEmpty())
        items = list
    }

    private fun tile(item: Item): FrameLayout = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        nextFocusDownId = primary.id
        nextFocusUpId = libraryTab.id
        background = Jinky.rounded(if (item is Item.Game) Jinky.RAISED else Jinky.GLASS, dp(10).toFloat())
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
        }
        when (item) {
            is Item.Game -> {
                addView(text(10f, Jinky.TEXT, bold = true).apply {
                    text = item.game.name
                    gravity = Gravity.START or Gravity.TOP
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
                if (item.game.paused) addView(pausedBadge(), LayoutParams(-2, dp(17), Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(6), 0, 0, dp(6))
                })
                else if (item.game.running) addView(runningBadge(), LayoutParams(-2, dp(17), Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(6), 0, 0, dp(6))
                })
            }
            Item.BigPicture -> addView(tileLabel(null, "Steam", "Big Picture"), LayoutParams(-1, -1))
            Item.Desktop -> addView(tileLabel(null, "Área de trabalho", "a tela do PC"), LayoutParams(-1, -1))
            Item.Library -> addView(tileLabel(R.drawable.ic_grid, "Biblioteca", "${games?.size ?: 0}"), LayoutParams(-1, -1))
            Item.Downloads -> {
                // The live download right on the tile: its percent and a thin bar along the bottom.
                val pct = downloads?.active?.percent
                addView(tileLabel(null, "Downloads", if (pct != null) "$pct%" else "na fila").also { downloadsTileText = it },
                    LayoutParams(-1, -1))
                addView(DownloadBar(context, 2f).also { b ->
                    downloadsTileBar = b
                    b.setProgress((pct ?: 0) / 100f, animate = false)
                }, LayoutParams(-1, dp(4), Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(12)) })
            }
        }
        foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(10).toFloat(); setStroke(dp(2) + dp(1) / 2, Color.TRANSPARENT)
        }
        setOnFocusChangeListener { v, focused ->
            resize(v, focused)
            v.translationZ = if (focused) dp(10).toFloat() else 0f
            (v.foreground as GradientDrawable).setStroke(dp(2) + dp(1) / 2, if (focused) Color.WHITE else Color.TRANSPARENT)
            if (focused) {
                primary.nextFocusUpId = v.id
                details.nextFocusUpId = v.id
                pinButton.nextFocusUpId = v.id
                v.post { glideRowTo(v) }
                showItem(item)
            }
        }
        setOnClickListener { activate(item, play = false) }
    }

    /** The focused tile grows and pushes its neighbours aside (a size change, not a scale). */
    private fun resize(tile: View, focused: Boolean) {
        val lp = tile.layoutParams ?: return
        sizeAnims.remove(tile)?.cancel()
        val fromW = lp.width
        val fromH = lp.height
        val toW = dp(if (focused) BIG_W else W)
        val toH = dp(if (focused) BIG_H else H)
        if (fromW == toW && fromH == toH) return
        sizeAnims[tile] = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (focused) 240 else 200
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                lp.width = (fromW + (toW - fromW) * f).toInt()
                lp.height = (fromH + (toH - fromH) * f).toInt()
                tile.layoutParams = lp
            }
            start()
        }
    }

    /**
     * Smoothly scrolls the row so [tile] sits at the left margin (as far as the row allows). The
     * target is re-measured every frame: the tiles are still growing and shrinking meanwhile.
     */
    private fun glideRowTo(tile: View) {
        rowGlide?.cancel()
        val start = row.scrollX
        rowGlide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val f = it.animatedValue as Float
                row.scrollX = (start + (rowTarget(tile) - start) * f).toInt()
            }
            start()
        }
    }

    private fun rowTarget(tile: View): Int {
        // The row may have been rebuilt meanwhile (games arrived, a pin changed): stay put.
        if (!isDescendant(tile)) return row.scrollX
        val r = Rect()
        tile.getDrawingRect(r)
        rowItems.offsetDescendantRectToMyCoords(tile, r)
        val max = (rowItems.width - row.width).coerceAtLeast(0)
        return (r.left - dp(48)).coerceIn(0, max)
    }

    /** Tiles slide in one after another (when the home screen opens or the games arrive). */
    fun animateIn() {
        if (row.visibility != View.VISIBLE) return
        // Own animators (not the view's animate()), so the focus grow can't cut them short and
        // leave a tile half-faded.
        items.forEachIndexed { i, (_, tile) ->
            android.animation.AnimatorSet().apply {
                playTogether(
                    android.animation.ObjectAnimator.ofFloat(tile, View.ALPHA, 0f, 1f),
                    android.animation.ObjectAnimator.ofFloat(tile, View.TRANSLATION_X, dp(48).toFloat(), 0f))
                startDelay = i * 40L
                duration = 380
                interpolator = DecelerateInterpolator(2f)
                tile.alpha = 0f
                start()
            }
        }
        info.alpha = 0f
        info.animate().alpha(1f).setStartDelay(120).setDuration(400).start()
    }

    private fun tileLabel(icon: Int?, name: String, detail: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        if (icon != null) addView(ImageView(context).apply { setImageResource(icon) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(7) })
        addView(text(10f, Jinky.TEXT, bold = true).apply { text = name; gravity = Gravity.CENTER })
        addView(mono(8f, Jinky.MUTED).apply { text = detail; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
    }

    private fun pausedBadge() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(7), 0)
        background = Jinky.rounded(Jinky.ink(0xDB), dp(9).toFloat())
        // Two small bars: the pause sign.
        repeat(2) { i ->
            addView(View(context).apply { setBackgroundColor(Jinky.AMBER) },
                LinearLayout.LayoutParams(dp(2), dp(7)).apply { marginEnd = if (i == 0) dp(2) else dp(4) })
        }
        addView(text(6.5f, Jinky.TEXT, bold = true).apply { text = "PAUSADO" })
    }

    private fun runningBadge() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(7), 0)
        background = Jinky.rounded(Jinky.ink(0xDB), dp(9).toFloat())
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.GREEN) } },
            LinearLayout.LayoutParams(dp(4), dp(4)).apply { marginEnd = dp(4) })
        addView(text(6.5f, Jinky.TEXT, bold = true).apply { text = "NO PC" })
    }

    // --- the focused item ---

    /** The backgrounds of the games next to the focused one, so moving there shows them at once. */
    private fun prefetchNeighbours(item: Item.Game) {
        val (ip, port) = art ?: return
        if (!Jinky.showArt) return
        val i = items.indexOfFirst { it.first == item }
        for (j in listOf(i + 1, i - 1)) {
            val g = (items.getOrNull(j)?.first as? Item.Game)?.game ?: continue
            ArtLoader.prefetch(ip, port, g.id, "hero", 1)
        }
    }

    private fun showItem(item: Item?) {
        if (host?.status != HomeHost.Status.READY || item == null) return
        val changed = item != selected
        selected = item
        primary.visibility = View.VISIBLE
        if (changed && item is Item.Game) prefetchNeighbours(item)
        if (changed) {
            info.animate().cancel()
            info.alpha = 0f
            info.translationY = dp(14).toFloat()
            info.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(280)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        when (item) {
            is Item.Game -> {
                val g = item.game
                primary.text = if (g.running || g.paused) "Continuar" else "Jogar"
                details.gone(false)
                pinButton.gone(false)
                showPinState(g)
                val week = (LibraryScreen.lastWeek?.days ?: 0).let { d ->
                    Jinky.hours(g.weekMinutes).let { if (d <= 0 || it.isEmpty()) "" else "$it ${Jinky.weekLabel(d)}" }
                }
                val hours = listOf(week, Jinky.hours(g.minutes).let { if (it.isEmpty()) "" else "$it no total" })
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                val downloading = g.state == LibraryGame.State.DOWNLOADING || g.updating
                if (g.paused && g.saved) setMeta(Jinky.AMBER, "Salvo ${pausedAgo(g.pausedAt)}· continua de onde parou, mesmo com o PC desligado", hours)
                else if (g.paused) setMeta(Jinky.AMBER, "Pausado ${pausedAgo(g.pausedAt)}· continua de onde parou", hours)
                else if (g.running) setMeta(Jinky.GREEN, "Rodando no PC", hours)
                else setMeta(if (downloading) Jinky.BLUE else null,
                    if (downloading) Jinky.downloadText(g) else g.platformName.let { if (it.isEmpty()) "" else "$it · " } + lastPlayedText(g.lastPlayed), hours)
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
            Item.Library -> describe("Biblioteca", "Todos os seus jogos, com busca e filtros.", "Ver todos")
            Item.Downloads -> describe("Downloads", downloads?.active?.let { "${it.stateText} ${it.name} no PC." } ?: "Jogos na fila de download do PC.", "Ver downloads")
        }
    }

    private fun describe(name: String, detail: String, action: String) {
        removeCallbacks(loadArt)
        title.text = name
        title.visibility = View.VISIBLE
        logo.visibility = View.GONE
        setMeta(null, detail, "")
        primary.text = action
        details.gone(true)
        pinButton.gone(true)
        showHero(null)
    }

    /** "há 12 min ", "há 3 h "... ("" if unknown). */
    private fun pausedAgo(at: Long): String {
        if (at <= 0) return ""
        val min = (System.currentTimeMillis() - at) / 60_000
        return when {
            min < 2 -> "agora há pouco "
            min < 60 -> "há $min min "
            min < 60 * 24 -> "há ${min / 60} h "
            else -> "há ${min / (60 * 24)} dia${if (min >= 2 * 60 * 24) "s" else ""} "
        }
    }

    private fun setMeta(dot: Int?, text: String, hours: String) {
        metaDot.gone(dot == null)
        if (dot != null) metaDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
        metaText.text = text
        metaText.setTextColor(if (dot != null) Jinky.TEXT else Jinky.SOFT)
        metaHours.text = if (hours.isEmpty()) "" else "·  $hours"
        metaHours.gone(hours.isEmpty())
    }

    private fun showPinState(g: LibraryGame) {
        val pinnedNow = g.id in pins.pinned
        pinButton.text = if (pinnedNow) "Fixado" else "Fixar"
    }

    private fun togglePin() {
        val g = (selected as? Item.Game)?.game ?: return
        pins.togglePin(g.id)
        rebuildRow()
        items.firstOrNull { (it.first as? Item.Game)?.game?.id == g.id }?.second?.let { tile ->
            val pinFocused = pinButton.hasFocus()
            selected = null
            showItem(Item.Game(g))
            if (pinFocused) pinButton.requestFocus() else tile.requestFocus()
            tile.post { glideRowTo(tile) }
        }
    }

    private val loadArt = Runnable {
        val g = (selected as? Item.Game)?.game ?: return@Runnable
        val (ip, port) = art ?: return@Runnable
        // A paused game shows the moment it was paused (like a console's Quick Resume).
        if (Jinky.showArt && g.paused && g.snapshot) ArtLoader.loadUrl(LibraryScreen.snapshotUrl(ip, port, g), 1) { bmp ->
            if ((selected as? Item.Game)?.game?.id != g.id) return@loadUrl
            if (bmp != null) showHero(bmp, "snap/${g.id}/${g.pausedAt}")
            else ArtLoader.load(ip, port, g.id, "hero", 1) { h -> if ((selected as? Item.Game)?.game?.id == g.id) showHero(h, "hero/${g.id}") }
        }
        else if (Jinky.showArt) ArtLoader.load(ip, port, g.id, "hero", 1) { bmp ->
            if ((selected as? Item.Game)?.game?.id == g.id) showHero(bmp, "hero/${g.id}")
        }
        ArtLoader.load(ip, port, g.id, "logo", 1) { bmp ->
            if ((selected as? Item.Game)?.game?.id != g.id || bmp == null) return@load
            logo.setImageBitmap(bmp)
            logo.alpha = 0f
            logo.visibility = View.VISIBLE
            logo.animate().alpha(1f).setDuration(260).start()
            title.visibility = View.GONE
        }
    }

    /** Cross-fades to [bmp]; null shows the Jinky backdrop. */
    private fun showHero(bmp: Bitmap?, key: String? = null) {
        if (key == shownHero && (key != null || heroTop?.drawable != null)) return
        shownHero = key
        // The new artwork goes on the layer behind, which is moved in front of the old one (still
        // under the scrims) and fades in while settling from a slight zoom.
        val incoming = if (heroTop === heroFront) heroBack else heroFront
        incoming.animate().cancel()
        if (bmp == null) {
            incoming.setImageDrawable(if (!Jinky.theme.art) android.graphics.drawable.ColorDrawable(Jinky.INK)
                else GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Jinky.theme.accentDeep and 0x00FFFFFF or 0x66000000, Jinky.SURFACE, Jinky.INK)))
        } else {
            incoming.setImageBitmap(bmp)
        }
        removeView(incoming)
        addView(incoming, 1, LayoutParams(-1, -1))
        heroTop = incoming
        incoming.alpha = 0f
        incoming.scaleX = 1.06f
        incoming.scaleY = 1.06f
        incoming.translationX = 0f
        drift?.cancel()
        incoming.animate().scaleX(1f).scaleY(1f).setDuration(1400).setInterpolator(DecelerateInterpolator(2f))
            .withEndAction { if (heroTop === incoming && bmp != null) startDrift(incoming) }.start()
        android.animation.ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).apply { duration = 420 }.start()
        setAmbient(if (bmp != null) accentOf(bmp) else Jinky.BLUE_DEEP)
    }

    // The slow drift only while the home screen is on show (not under a game or another page).
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isShown) drift?.pause()
        else {
            removeCallbacks(resumeDrift)
            postDelayed(resumeDrift, 1500)
        }
    }

    /** The artwork keeps moving, very slowly, as if a camera drifted over it (a living screen). */
    private fun startDrift(v: ImageView) {
        drift?.cancel()
        drift = android.animation.AnimatorSet().apply {
            val dx = -dp(22).toFloat()
            playTogether(
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.07f).apply { repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE },
                android.animation.ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.07f).apply { repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE },
                android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, dx).apply { repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE })
            duration = 22_000
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** The most colourful bright tone of the artwork (its "colour"), for the glow. */
    private fun accentOf(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 24, 8, true)
        var best = Jinky.BLUE_DEEP
        var bestScore = -1f
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            // Saturated and reasonably bright; neither near-black nor washed-out.
            val score = hsv[1] * (if (hsv[2] in 0.25f..0.95f) hsv[2] else hsv[2] * 0.2f)
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (small !== bmp) small.recycle()
        Color.colorToHSV(best, hsv)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(1f)
        hsv[2] = 0.55f
        return Color.HSVToColor(hsv)
    }

    private fun setAmbient(color: Int) {
        if (!Jinky.showArt) {
            ambient.background = null
            return
        }
        val from = if (ambientColor == 0) color else ambientColor
        ambientColor = color
        ambientAnim?.cancel()
        val glow = (ambient.background as? GradientDrawable) ?: GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.12f, 0.95f)
            gradientRadius = dp(760).toFloat()
            ambient.background = this
        }
        val stops = IntArray(3)
        fun paint(c: Int) {
            stops[0] = (c and 0xFFFFFF) or 0x55000000
            stops[1] = (c and 0xFFFFFF) or 0x18000000
            stops[2] = 0
            glow.colors = stops
        }
        ambientAnim = ValueAnimator.ofArgb(from, color).apply {
            duration = 700
            addUpdateListener { paint(it.animatedValue as Int) }
            start()
        }
    }

    private fun activate(item: Item?, play: Boolean) {
        val h = host ?: return
        when {
            h.status == HomeHost.Status.BUSY -> onPlay(h, false)
            h.status == HomeHost.Status.OFFLINE -> if (h.mac != null && !waking) onWake(h)
            item is Item.Game -> if (play) onPlayGame(h, item.game) else onOpenGame(h, item.game)
            item == Item.BigPicture -> onPlay(h, false)
            item == Item.Desktop -> onPlay(h, true)
            item == Item.Library -> onLibrary(h, false)
            item == Item.Downloads -> onDownloads()
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

    /** "Atualizar" when the PC carries a newer TV app than this one. */
    private fun showUpdate(h: HomeHost?) {
        val v = h?.updateVersion ?: 0
        if (h == null || h.status == HomeHost.Status.OFFLINE || v <= BuildConfig.VERSION_CODE || h.updatePort == 0) {
            update.visibility = View.GONE
            return
        }
        update.text = "Atualizar · ${v / 10000}.${v / 100 % 100}.${v % 100}"
        update.visibility = View.VISIBLE
    }

    /** The PC chip: [bars] of 4 lit in [color] (signal-meter style), then its name. */
    private fun setStatus(color: Int, bars: Int, label: String) {
        if (chipBars.childCount == 0) for (i in 0 until 4) chipBars.addView(View(context),
            LinearLayout.LayoutParams(dp(2) + dp(1) / 2, dp(3 + i * 2)).apply { if (i > 0) marginStart = dp(1) + dp(1) / 2 })
        for (i in 0 until 4) chipBars.getChildAt(i).setBackgroundColor(if (i < bars.coerceAtLeast(1)) color else 0x40FFFFFF)
        statusText.text = label
    }

    private fun refreshHints() {
        val ps = Jinky.playStationPad()
        if (ps == hintsForPs) return
        hintsForPs = ps
        hints.removeAllViews()
        fun add(v: View) = hints.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (hints.childCount > 0) marginStart = dp(16) })
        add(Jinky.hint(context, Jinky.Button.OK, "Abrir"))
        add(Jinky.hint(context, Jinky.Button.SEARCH, "Buscar"))
        add(Jinky.hint(context, Jinky.Button.PIN, "Fixar"))
        fun bumper(key: String, label: String) = add(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mono(7.5f, Jinky.TEXT, 700).apply {
                text = key
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = Jinky.rounded(Jinky.LINE, dp(4).toFloat())
            })
            addView(text(9f, Jinky.SOFT).apply { text = label }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
        })
        bumper("L1", "Amigos")
        bumper("R1", "Biblioteca")
    }

    // --- views ---

    private fun activeTab(label: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(text(12.5f, Jinky.TEXT, bold = true).apply { text = label })
        addView(View(context).apply { setBackgroundColor(Jinky.BLUE) },
            LinearLayout.LayoutParams(dp(4) + dp(1) / 2, dp(4) + dp(1) / 2).apply { marginStart = dp(2); bottomMargin = dp(3) })
    }

    private fun tab(label: String) = TextView(context).apply {
        text = label
        isFocusable = true
        isClickable = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Jinky.font(context, 600)
        setTextColor(focusColors(Jinky.INK, Jinky.MUTED))
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, dp(99).toFloat()))
            addState(intArrayOf(), Jinky.rounded(Color.TRANSPARENT, dp(99).toFloat()))
        }
    }

    private fun actionButton(icon: Int?, primary: Boolean = false) = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 14.5f else 11f)
        typeface = Jinky.font(context, 800)
        setPadding(dp(if (primary) 24 else 16), 0, dp(if (primary) 28 else 18), 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        val colors = focusColors(Jinky.INK, Jinky.TEXT)
        setTextColor(colors)
        background = StateListDrawableCompat.pill(context, dp(21).toFloat())
        stateListAnimator = null
        if (icon != null) {
            val d = context.getDrawable(icon)!!.mutate()
            val size = dp(if (primary) 13 else 11)
            d.setBounds(0, 0, size, size)
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(8)
            setOnFocusChangeListener { _, focused ->
                d.setColorFilter(if (focused) Jinky.INK else Jinky.TEXT, PorterDuff.Mode.SRC_IN)
            }
            d.setColorFilter(Jinky.TEXT, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun pill(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        typeface = Jinky.font(context, 700)
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = 0
        minimumHeight = 0
        setTextColor(focusColors(Jinky.INK, Jinky.TEXT))
        background = states(Jinky.TEXT, Jinky.BLUE_DEEP, 99)
        stateListAnimator = null
    }

    private fun iconButton(icon: Int) = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        background = StateListDrawableCompat.pill(context, dp(14).toFloat())
        val image = ImageView(context).apply { setImageResource(icon); setColorFilter(Jinky.TEXT) }
        addView(image, LayoutParams(dp(14), dp(14), Gravity.CENTER))
        setOnFocusChangeListener { _, focused -> image.setColorFilter(if (focused) Jinky.INK else Jinky.TEXT) }
    }

    companion object {
        private const val RECENT = 8
        private const val W = 76
        private const val H = 114
        private const val BIG_W = 110
        private const val BIG_H = 165
        private const val GAP = 10
        private const val LABEL = 12
    }
}

/** Console-style pill background: glass when idle, white when focused. */
object StateListDrawableCompat {
    fun pill(context: Context, radius: Float) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, radius))
        addState(intArrayOf(), Jinky.rounded(Jinky.GLASS, radius))
    }
}
