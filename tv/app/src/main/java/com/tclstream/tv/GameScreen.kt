package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A game's own page (design v2), between choosing it and playing it, like a console's game hub:
 * artwork, logo, what it is (genres, studio, year, Metacritic, store description), Jogar / Trailer /
 * Fixar, then this account's achievements (progress ring, rarity, the rarest one), the time played,
 * and a strip of trailers and screenshots to look at full screen.
 */
class GameScreen(
    context: Context,
    private val pins: TvSettings,
    private val onPlay: (LibraryGame) -> Unit,
    private val onInstall: (LibraryGame) -> Unit,
    private val onAchievements: (LibraryGame) -> Unit,
) : TvPage(context) {

    private data class Details(
        val description: String, val genres: List<String>, val developer: String, val released: String,
        val metacritic: Int, val shots: List<Pair<String, String>>, val trailers: List<Triple<String, String, String>>,
        val minutes: Long, val sizeBytes: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val hero = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val crumb = text(11.5f, Jinky.SOFT, bold = true)
    private val clock = mono(15f, Jinky.TEXT)
    private val logo = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxHeight = dp(95)
        maxWidth = dp(270)
    }
    private val title = text(28f, Jinky.TEXT, bold = true).apply { maxLines = 2; shadow() }
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val description = text(11.5f, Jinky.SOFT).apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.4f)
        shadow()
    }
    private val primary = action(R.drawable.ic_play, primary = true)
    private val trailer = action(R.drawable.ic_play).apply { text = "Trailer" }
    private val pin = action(R.drawable.ic_pin)

    // Achievements card
    private val achCard = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        background = cardStates()
    }
    private val achRing = ProgressRing(context, 6f)
    private val achPercent = mono(17f, Jinky.TEXT, 700)
    private val achOf = text(7.5f, Jinky.MUTED)
    private val achBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val achCounts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val achRarest = text(9f, Jinky.SOFT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val achMessage = text(10f, Jinky.MUTED)
    private val achBody = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    // Time card
    private val timeCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = Jinky.rounded(Jinky.GLASS, dp(11).toFloat())
    }
    private val timeValue = mono(26f, Jinky.TEXT, 700)
    private val timeLast = text(9.5f, Jinky.SOFT)
    private val timeSize = mono(8.5f, Jinky.MUTED)

    private val mediaRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(4), dp(4), dp(48), dp(4))
    }
    private val media = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        addView(mediaRow)
    }

    // Full-screen viewer for a screenshot or a trailer.
    private val viewer = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor(Color.BLACK)
        isFocusable = true
    }
    private val viewerImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val viewerVideo = VideoView(context)
    private val viewerSpinner = ProgressBar(context)
    private val viewerHint = text(10f, 0xCCFFFFFF.toInt()).apply {
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = Jinky.rounded(0x99000000.toInt(), dp(99).toFloat())
    }
    private var shots: List<Pair<String, String>> = emptyList()
    private var trailers: List<Triple<String, String, String>> = emptyList()
    private var shotIndex = -1

    private var game: LibraryGame? = null
    private var pc: Pair<String, Int>? = null

    init {
        addView(hero, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Jinky.ink(0xF7), Jinky.ink(0xC7), Jinky.ink(0x1A)))
        }, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Jinky.ink(0xBF), Jinky.ink(0x00), Jinky.ink(0x00), Jinky.ink(0xF7), Jinky.ink(0xFA)))
        }, LayoutParams(-1, -1))
        addView(View(context).apply { background = Jinky.scanlines(context) }, LayoutParams(-1, -1))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_back); setColorFilter(Jinky.SOFT) },
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
            addView(crumb)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clock)
        }, LayoutParams(-1, dp(36)).apply { setMargins(dp(48), dp(22), dp(48), 0) })

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(logo, LinearLayout.LayoutParams(-2, -2))
            addView(title, LinearLayout.LayoutParams(dp(480), -2))
            addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(description, LinearLayout.LayoutParams(dp(420), -2).apply { topMargin = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(primary, LinearLayout.LayoutParams(-2, dp(42)))
                addView(trailer, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(8) })
                addView(pin, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
        addView(column, LayoutParams(dp(560), -2).apply { setMargins(dp(48), dp(70), 0, 0) })

        // --- achievements card ---
        achBody.addView(FrameLayout(context).apply {
            addView(achRing, LayoutParams(-1, -1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(achPercent)
                addView(achOf)
            }, LayoutParams(-2, -2, Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(75), dp(75)))
        achBody.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(mono(7f, Jinky.MUTED, 700).apply { text = "CONQUISTAS · POR RARIDADE"; letterSpacing = 0.16f })
            addView(achBar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(8) })
            addView(achCounts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            addView(achRarest, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
        achCard.addView(achBody, LayoutParams(-1, -1).apply { setMargins(dp(13), dp(11), dp(13), dp(11)) })
        achCard.addView(achMessage, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL).apply { setMargins(dp(14), 0, dp(14), 0) })

        // --- time card ---
        timeCard.addView(mono(7f, Jinky.MUTED, 700).apply { text = "SEU TEMPO"; letterSpacing = 0.16f })
        timeCard.addView(timeValue, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        timeCard.addView(timeLast, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        timeCard.addView(timeSize, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(achCard, LinearLayout.LayoutParams(dp(300), dp(112)))
            addView(timeCard, LinearLayout.LayoutParams(dp(170), dp(112)).apply { marginStart = dp(10) })
            addView(media, LinearLayout.LayoutParams(0, dp(120), 1f).apply { marginStart = dp(6) })
        }, LayoutParams(-1, dp(120), Gravity.BOTTOM).apply { setMargins(dp(48), 0, 0, dp(24)) })

        viewer.addView(viewerImage, LayoutParams(-1, -1))
        viewer.addView(viewerVideo, LayoutParams(-1, -1, Gravity.CENTER))
        viewer.addView(viewerSpinner, LayoutParams(dp(40), dp(40), Gravity.CENTER))
        viewer.addView(viewerHint, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        addView(viewer, LayoutParams(-1, -1))
        viewerVideo.setOnPreparedListener { viewerSpinner.visibility = View.GONE }
        viewerVideo.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "trailer: error $what/$extra")
            viewerSpinner.visibility = View.GONE
            viewerHint.text = "Não foi possível tocar o trailer   ·   Voltar: fechar"
            true
        }
        viewerVideo.setOnCompletionListener { closeViewer() }

        for (b in listOf(primary, trailer, pin)) {
            b.id = View.generateViewId()
            b.nextFocusDownId = achCard.id
        }
        achCard.nextFocusUpId = primary.id
        primary.setOnClickListener {
            val g = game ?: return@setOnClickListener
            when (g.state) {
                LibraryGame.State.INSTALLED -> onPlay(g)
                LibraryGame.State.NOT_INSTALLED -> onInstall(g)
                LibraryGame.State.DOWNLOADING -> {}
            }
        }
        trailer.setOnClickListener { trailers.firstOrNull()?.let { (name, _, hls) -> playTrailer(hls, name) } }
        pin.setOnClickListener {
            val g = game ?: return@setOnClickListener
            pins.togglePin(g.id)
            pin.text = if (g.id in pins.pinned) "Fixado" else "Fixar"
        }
        achCard.setOnClickListener { game?.let(onAchievements) }
    }

    /** Opens the page of [g] from the PC at [pcIp]; [from] names the screen Back returns to. */
    fun show(g: LibraryGame, pcIp: String, port: Int, from: String = "Biblioteca") {
        game = g
        pc = pcIp to port
        closeViewer()
        crumb.text = from
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        hero.setImageDrawable(null)
        logo.visibility = View.GONE
        title.visibility = View.VISIBLE
        title.text = g.name
        chips.removeAllViews()
        addStateChip(g, null)
        description.text = ""
        mediaRow.removeAllViews()
        trailers = emptyList()
        shots = emptyList()
        trailer.gone(true)
        primary.text = when (g.state) {
            LibraryGame.State.INSTALLED -> if (g.running) "Continuar" else "Jogar"
            LibraryGame.State.NOT_INSTALLED -> "Instalar"
            LibraryGame.State.DOWNLOADING -> "Baixando ${(g.progress * 100).toInt()}%"
        }
        pin.gone(g.state == LibraryGame.State.NOT_INSTALLED)
        pin.text = if (g.id in pins.pinned) "Fixado" else "Fixar"
        timeValue.text = Jinky.hours(g.minutes).ifEmpty { "—" }
        timeLast.text = lastPlayed(g.lastPlayed)
        timeSize.text = ""
        showAchievementsLoading(g)
        if (Jinky.showArt) ArtLoader.load(pcIp, port, g.id, "hero", 1) { bmp ->
            if (game?.id != g.id || bmp == null) return@load
            hero.setImageBitmap(bmp)
            hero.alpha = 0f
            hero.scaleX = 1.06f; hero.scaleY = 1.06f
            hero.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(1600).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        ArtLoader.load(pcIp, port, g.id, "logo", 1) { bmp ->
            if (game?.id != g.id || bmp == null) return@load
            logo.setImageBitmap(bmp)
            logo.visibility = View.VISIBLE
            title.visibility = View.GONE
        }
        io.execute {
            val d = try { fetch(pcIp, port, g.id) } catch (e: Exception) { Log.w(TAG, "details: $e"); null }
            main.post { if (game?.id == g.id && d != null) fill(g, d) }
        }
        if (!g.nonSteam) io.execute {
            val a = try { AchievementsApi.fetch(pcIp, port, g.id) } catch (e: Exception) { Log.w(TAG, "achievements: $e"); null }
            main.post { if (game?.id == g.id) showAchievements(a) }
        }
    }

    private fun fill(g: LibraryGame, d: Details) {
        chips.removeAllViews()
        d.genres.take(2).forEach { addChip(it) }
        val year = Regex("\\d{4}").find(d.released)?.value
        listOf(d.developer.substringBefore(",").trim(), year.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
            .takeIf { it.isNotBlank() }?.let { addChip(it) }
        addStateChip(g, d)
        if (d.metacritic > 0) addChip("MC ${d.metacritic}", numeric = true, color = 0xFFFFD58A.toInt(), ground = 0xFF3A2E10.toInt())
        description.text = d.description
        description.alpha = 0f
        description.animate().alpha(1f).setDuration(300).start()
        if (d.minutes > 0) timeValue.text = Jinky.hours(d.minutes.toInt())
        if (d.sizeBytes > 0) timeSize.text = "%.1f GB no disco".format(d.sizeBytes / 1e9)
        // Trailers first (with a play mark), then screenshots.
        shots = d.shots
        trailers = d.trailers
        trailer.gone(d.trailers.isEmpty())
        d.trailers.forEach { (name, thumb, hls) -> mediaRow.addView(mediaTile(thumb, trailer = true) { playTrailer(hls, name) }, tileParams()) }
        d.shots.forEachIndexed { i, (thumb, _) -> mediaRow.addView(mediaTile(thumb, trailer = false) { showShot(i) }, tileParams()) }
    }

    // --- achievements card ---

    private fun showAchievementsLoading(g: LibraryGame) {
        achCard.gone(g.nonSteam)
        achBody.visibility = View.INVISIBLE
        achMessage.visibility = View.VISIBLE
        achMessage.text = "Carregando conquistas…"
        achRing.progress = 0f
    }

    private fun showAchievements(list: AchievementList?) {
        when {
            list == null -> { achMessage.text = "Conquistas indisponíveis agora."; return }
            list.error != null -> { achMessage.text = "Conquistas: ${list.error}"; return }
            list.items.isEmpty() -> { achCard.gone(true); return }
        }
        list!!
        achMessage.visibility = View.GONE
        achBody.visibility = View.VISIBLE
        val fraction = list.unlocked.toFloat() / list.total
        achRing.animateTo(fraction)
        achPercent.text = "${(fraction * 100).toInt()}%"
        achOf.text = "${list.unlocked} de ${list.total}"
        achBar.removeAllViews()
        achCounts.removeAllViews()
        val unlocked = list.items.filter { it.achieved }
        for (r in Rarity.values().reversed()) {
            val n = unlocked.count { it.rarity == r }
            if (n == 0) continue
            achBar.addView(View(context).apply { background = Jinky.rounded(r.color, dp(2).toFloat()) },
                LinearLayout.LayoutParams(0, -1, n.toFloat()).apply { if (achBar.childCount > 0) marginStart = dp(2) })
            achCounts.addView(mono(8f, r.soft).apply { text = "$n ${r.label.lowercase().take(5)}" },
                LinearLayout.LayoutParams(-2, -2).apply { if (achCounts.childCount > 0) marginStart = dp(9) })
        }
        if (unlocked.isEmpty()) {
            achBar.addView(View(context).apply { background = Jinky.rounded(Jinky.LINE, dp(2).toFloat()) }, LinearLayout.LayoutParams(-1, -1))
            achCounts.addView(text(8.5f, Jinky.MUTED).apply { text = "Nenhuma ainda" })
        }
        val rarest = unlocked.filter { it.percent >= 0 }.minByOrNull { it.percent }
        achRarest.text = when {
            list.progressHidden -> "Seu progresso está privado na Steam"
            rarest != null -> "Mais rara: ${rarest.name} · ${pct(rarest.percent)}"
            else -> "Aperte para ver todas"
        }
    }

    private fun pct(p: Double) = (if (p < 10) String.format(java.util.Locale("pt", "BR"), "%.1f", p) else p.toInt().toString()) + "%"

    // --- chips ---

    private fun addChip(label: String, numeric: Boolean = false, color: Int = Jinky.TEXT, ground: Int = Jinky.GLASS, dot: Int? = null) {
        chips.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
            background = Jinky.rounded(ground, dp(10).toFloat())
            if (dot != null) addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) } },
                LinearLayout.LayoutParams(dp(4), dp(4)).apply { marginEnd = dp(5) })
            addView((if (numeric) mono(8.5f, color, 700) else text(8.5f, color, bold = true)).apply { text = label })
        }, LinearLayout.LayoutParams(-2, dp(20)).apply { if (chips.childCount > 0) marginStart = dp(5) })
    }

    private fun addStateChip(g: LibraryGame, d: Details?) {
        when (g.state) {
            LibraryGame.State.INSTALLED -> addChip(if (g.running) "Rodando no PC" else "Instalado", dot = Jinky.GREEN)
            LibraryGame.State.DOWNLOADING -> addChip("Baixando ${(g.progress * 100).toInt()}%", dot = Jinky.BLUE)
            LibraryGame.State.NOT_INSTALLED -> addChip(if (g.family) "Da família" else "Na sua conta", dot = Jinky.DIM)
        }
    }

    // --- media ---

    private fun tileParams() = LinearLayout.LayoutParams(dp(178), dp(100)).apply { if (mediaRow.childCount > 0) marginStart = dp(9) }

    private fun mediaTile(url: String, trailer: Boolean, open: () -> Unit) = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        nextFocusUpId = primary.id
        background = Jinky.rounded(Jinky.RAISED, dp(9).toFloat())
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(9).toFloat())
        }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(image, LayoutParams(-1, -1))
        if (trailer) {
            addView(View(context).apply { setBackgroundColor(Jinky.ink(0x59)) }, LayoutParams(-1, -1))
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.TEXT) }
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_play); setColorFilter(Jinky.INK) },
                    LayoutParams(dp(13), dp(13), Gravity.CENTER).apply { leftMargin = dp(2) })
            }, LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(9).toFloat(); setStroke(dp(2) + dp(1) / 2, Color.TRANSPARENT) }
        ArtLoader.loadUrl(url, 1) { bmp -> if (bmp != null) image.setImageBitmap(bmp) }
        setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(200).start()
            v.translationZ = if (focused) dp(8).toFloat() else 0f
            (v.foreground as GradientDrawable).setStroke(dp(2) + dp(1) / 2, if (focused) Color.WHITE else Color.TRANSPARENT)
            if (focused) media.post { media.smoothScrollTo((v.left - dp(8)).coerceAtLeast(0), 0) }
        }
        setOnClickListener { open() }
    }

    // --- viewer ---

    private fun showShot(index: Int) {
        if (index !in shots.indices) return
        shotIndex = index
        viewerVideo.stopPlayback()
        viewerVideo.visibility = View.GONE
        viewerImage.visibility = View.VISIBLE
        viewerSpinner.visibility = View.VISIBLE
        viewerHint.text = "Imagem ${index + 1} de ${shots.size}   ·   ◀ ▶ trocar   ·   Voltar: fechar"
        openViewer()
        ArtLoader.loadUrl(shots[index].second, 1) { bmp ->
            if (shotIndex != index) return@loadUrl
            viewerSpinner.visibility = View.GONE
            if (bmp != null) viewerImage.setImageBitmap(bmp)
        }
    }

    private fun playTrailer(hls: String, name: String) {
        shotIndex = -1
        viewerImage.visibility = View.GONE
        viewerImage.setImageDrawable(null)
        viewerVideo.visibility = View.VISIBLE
        viewerSpinner.visibility = View.VISIBLE
        viewerHint.text = "$name   ·   Voltar: fechar"
        openViewer()
        viewerVideo.setVideoURI(Uri.parse(hls))
        viewerVideo.start()
    }

    private fun openViewer() {
        if (viewer.visibility != View.VISIBLE) {
            viewer.visibility = View.VISIBLE
            viewer.alpha = 0f
            viewer.animate().alpha(1f).setDuration(200).start()
        }
        viewer.requestFocus()
    }

    /** Closes the image/trailer viewer; false if it wasn't open (Back then leaves the page). */
    fun closeViewer(): Boolean {
        if (viewer.visibility != View.VISIBLE) return false
        viewerVideo.stopPlayback()
        viewer.visibility = View.GONE
        shotIndex = -1
        primary.requestFocus()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Left/right flips through screenshots while one is open.
        if (viewer.visibility == View.VISIBLE && shotIndex >= 0 && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { showShot((shotIndex + 1) % shots.size); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { showShot((shotIndex - 1 + shots.size) % shots.size); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun focusFirst() {
        primary.requestFocusFromTouch()
    }

    private fun lastPlayed(unix: Long): String {
        if (unix <= 0) return "Ainda não jogado"
        val days = (System.currentTimeMillis() / 1000 - unix) / 86400
        return when {
            days < 1 -> "Última vez: hoje"
            days < 2 -> "Última vez: ontem"
            days < 30 -> "Última vez: há $days dias"
            else -> "Última vez: " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(unix * 1000))
        }
    }

    private fun TextView.shadow() = setShadowLayer(dp(8).toFloat(), 0f, 0f, 0xCC000000.toInt())

    private fun cardStates() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.RAISED, dp(11).toFloat(), dp(2) + dp(1) / 2, Jinky.TEXT))
        addState(intArrayOf(), Jinky.rounded(Jinky.GLASS, dp(11).toFloat()))
    }

    private fun action(icon: Int, primary: Boolean = false) = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 14.5f else 11f)
        typeface = Jinky.font(context, 800)
        setPadding(dp(if (primary) 26 else 16), 0, dp(if (primary) 30 else 18), 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextColor(focusColors(Jinky.INK, Jinky.TEXT))
        background = StateListDrawableCompat.pill(context, dp(21).toFloat())
        stateListAnimator = null
        val d = context.getDrawable(icon)!!.mutate()
        val size = dp(if (primary) 13 else 11)
        d.setBounds(0, 0, size, size)
        d.setColorFilter(Jinky.TEXT, PorterDuff.Mode.SRC_IN)
        setCompoundDrawables(d, null, null, null)
        compoundDrawablePadding = dp(8)
        setOnFocusChangeListener { _, has -> d.setColorFilter(if (has) Jinky.INK else Jinky.TEXT, PorterDuff.Mode.SRC_IN) }
    }

    private fun fetch(pcIp: String, port: Int, id: Int): Details {
        val c = URL("http://$pcIp:$port/library/details?id=$id").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 15000
        val o = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        c.disconnect()
        fun list(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.get(it) } } ?: emptyList()
        return Details(
            o.optString("description"),
            list("genres").map { it.toString() },
            o.optString("developer"),
            o.optString("released"),
            o.optInt("metacritic"),
            list("screenshots").map { (it as org.json.JSONObject).let { s -> s.optString("thumb") to s.optString("full") } },
            list("trailers").map { (it as org.json.JSONObject).let { t -> Triple(t.optString("name"), t.optString("thumb"), t.optString("hls")) } },
            o.optLong("minutes"),
            o.optLong("sizeBytes"),
        )
    }

    companion object {
        private const val TAG = "Jinky"
    }
}
