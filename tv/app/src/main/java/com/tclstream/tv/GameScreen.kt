package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 * A game's own page, between choosing it and playing it (like a console's game hub): its artwork,
 * logo, what it is (store description, genres, studio, release), this PC's numbers (time played,
 * size), and a strip of screenshots and trailers to look at full screen. Jogar / Instalar from here.
 */
class GameScreen(
    context: Context,
    private val onPlay: (LibraryGame) -> Unit,
    private val onInstall: (LibraryGame) -> Unit,
) : TvPage(context) {

    private data class Details(
        val description: String, val genres: List<String>, val developer: String, val released: String,
        val metacritic: Int, val shots: List<Pair<String, String>>, val trailers: List<Triple<String, String, String>>,
        val minutes: Long, val sizeBytes: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val hero = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val logo = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxHeight = dp(96)
        maxWidth = dp(420)
    }
    private val title = text(42f, Color.WHITE, bold = true).apply { maxLines = 2; shadow() }
    private val meta = text(17f, 0xFFD5DCE6.toInt()).apply { shadow() }
    private val genres = text(15f, 0xFFAAB6C8.toInt()).apply { shadow() }
    private val description = text(17f, 0xFFE4E9F0.toInt()).apply {
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.2f)
        shadow()
    }
    private val primary = Button(context).apply {
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(46), dp(14), dp(46), dp(14))
        minWidth = dp(220)
        setTextColor(focusColors(0xFF06101F.toInt(), Color.WHITE))
        background = states(Color.WHITE, 0x40FFFFFF, 99)
        stateListAnimator = null
        id = View.generateViewId()
    }
    private val mediaRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(6), dp(10), dp(40), dp(10))
    }
    private val media = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        addView(mediaRow)
    }
    private val mediaTitle = text(16f, 0xFFAAB6C8.toInt(), bold = true).apply { text = "Imagens e vídeos" }

    // Full-screen viewer for a screenshot or a trailer.
    private val viewer = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor(Color.BLACK)
        isFocusable = true
    }
    private val viewerImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val viewerVideo = VideoView(context)
    private val viewerSpinner = ProgressBar(context)
    private val viewerHint = text(15f, 0xCCFFFFFF.toInt()).apply {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(99).toFloat() }
    }
    private var shots: List<Pair<String, String>> = emptyList()
    private var shotIndex = -1

    private var game: LibraryGame? = null
    private var pc: Pair<String, Int>? = null

    init {
        addView(hero, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF2070A0F.toInt(), 0xC0070A0F.toInt(), 0x20070A0F))
        }, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00070A0F, 0x40070A0F, 0xF5070A0F.toInt()))
        }, LayoutParams(-1, -1))

        // No header: the game fills the page, like a console's game hub.
        val root = pageRoot().apply { setPadding(dp(56), dp(28), dp(56), dp(14)) }
        root.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(logo, LinearLayout.LayoutParams(-2, -2))
        root.addView(title, LinearLayout.LayoutParams(dp(700), -2))
        root.addView(meta, LinearLayout.LayoutParams(dp(760), -2).apply { topMargin = dp(8) })
        root.addView(genres, LinearLayout.LayoutParams(dp(760), -2).apply { topMargin = dp(4) })
        root.addView(description, LinearLayout.LayoutParams(dp(640), -2).apply { topMargin = dp(10) })
        root.addView(primary, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(mediaTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(media, LinearLayout.LayoutParams(-1, -2))
        addView(root, LayoutParams(-1, -1))

        viewer.addView(viewerImage, LayoutParams(-1, -1))
        viewer.addView(viewerVideo, LayoutParams(-1, -1, Gravity.CENTER))
        viewer.addView(viewerSpinner, LayoutParams(dp(48), dp(48), Gravity.CENTER))
        viewer.addView(viewerHint, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        addView(viewer, LayoutParams(-1, -1))
        viewerVideo.setOnPreparedListener { viewerSpinner.visibility = View.GONE }
        viewerVideo.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "trailer: error $what/$extra")
            viewerSpinner.visibility = View.GONE
            viewerHint.text = "Não foi possível tocar o trailer   ·   Voltar: fechar"
            true
        }
        viewerVideo.setOnCompletionListener { closeViewer() }

        primary.setOnClickListener {
            val g = game ?: return@setOnClickListener
            when (g.state) {
                LibraryGame.State.INSTALLED -> onPlay(g)
                LibraryGame.State.NOT_INSTALLED -> onInstall(g)
                LibraryGame.State.DOWNLOADING -> {}
            }
        }
    }

    /** Opens the page of [g] from the PC at [pcIp]. */
    fun show(g: LibraryGame, pcIp: String, port: Int) {
        game = g
        pc = pcIp to port
        closeViewer()
        hero.setImageDrawable(null)
        logo.visibility = View.GONE
        title.visibility = View.VISIBLE
        title.text = g.name
        meta.text = statusLine(g, null)
        genres.text = ""
        description.text = ""
        mediaRow.removeAllViews()
        mediaTitle.visibility = View.GONE
        primary.text = when (g.state) {
            LibraryGame.State.INSTALLED -> "Jogar"
            LibraryGame.State.NOT_INSTALLED -> "Instalar"
            LibraryGame.State.DOWNLOADING -> "Baixando ${(g.progress * 100).toInt()}%"
        }
        ArtLoader.load(pcIp, port, g.id, "hero", 1) { bmp ->
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
    }

    private fun fill(g: LibraryGame, d: Details) {
        meta.text = statusLine(g, d)
        genres.text = listOf(d.genres.joinToString(" · "), d.developer, d.released.let { if (it.isBlank()) "" else "Lançado em $it" })
            .filter { it.isNotBlank() }.joinToString("    ·    ")
        description.text = d.description
        description.alpha = 0f
        description.animate().alpha(1f).setDuration(300).start()
        // Trailers first (with a play mark), then screenshots.
        shots = d.shots
        d.trailers.forEach { (name, thumb, hls) -> mediaRow.addView(mediaTile(thumb, trailer = true) { playTrailer(hls, name) }, tileParams()) }
        d.shots.forEachIndexed { i, (thumb, _) -> mediaRow.addView(mediaTile(thumb, trailer = false) { showShot(i) }, tileParams()) }
        mediaTitle.visibility = if (mediaRow.childCount > 0) View.VISIBLE else View.GONE
        if (mediaRow.childCount > 0) primary.nextFocusDownId = mediaRow.getChildAt(0).id
    }

    private fun tileParams() = LinearLayout.LayoutParams(dp(192), dp(108)).apply { if (mediaRow.childCount > 0) marginStart = dp(14) }

    private fun mediaTile(url: String, trailer: Boolean, open: () -> Unit) = FrameLayout(context).apply {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        nextFocusUpId = primary.id
        background = GradientDrawable().apply { setColor(0xFF161E2A.toInt()); cornerRadius = dp(10).toFloat() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
        }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(image, LayoutParams(-1, -1))
        if (trailer) addView(TextView(context).apply {
            text = "▶  Trailer"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(10), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = dp(99).toFloat() }
        }, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, 0, dp(8)) })
        foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(10).toFloat(); setStroke(dp(3), Color.TRANSPARENT) }
        ArtLoader.loadUrl(url, 1) { bmp -> if (bmp != null) image.setImageBitmap(bmp) }
        setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(200).start()
            v.translationZ = if (focused) dp(8).toFloat() else 0f
            (v.foreground as GradientDrawable).setStroke(dp(3), if (focused) Color.WHITE else Color.TRANSPARENT)
            if (focused) media.post { media.smoothScrollTo((v.left - dp(40)).coerceAtLeast(0), 0) }
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
        primary.requestFocus()
    }

    private fun statusLine(g: LibraryGame, d: Details?): String {
        val parts = mutableListOf<String>()
        parts += when (g.state) {
            LibraryGame.State.INSTALLED -> lastPlayed(g.lastPlayed)
            LibraryGame.State.DOWNLOADING -> "Baixando no PC"
            LibraryGame.State.NOT_INSTALLED -> if (g.family) "Compartilhado pela família" else "Na sua conta"
        }
        if (d != null && d.minutes > 0) parts += if (d.minutes < 60) "${d.minutes} min jogados" else "%d h %02d min jogadas".format(d.minutes / 60, d.minutes % 60)
        if (d != null && d.sizeBytes > 0) parts += "%.1f GB".format(d.sizeBytes / 1e9)
        if (d != null && d.metacritic > 0) parts += "Metacritic ${d.metacritic}"
        return parts.joinToString("   ·   ")
    }

    private fun lastPlayed(unix: Long): String {
        if (unix <= 0) return "Ainda não jogado"
        val days = (System.currentTimeMillis() / 1000 - unix) / 86400
        return when {
            days < 1 -> "Jogado hoje"
            days < 2 -> "Jogado ontem"
            days < 30 -> "Jogado há $days dias"
            else -> "Jogado em " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(unix * 1000))
        }
    }

    private fun TextView.shadow() = setShadowLayer(dp(10).toFloat(), 0f, 0f, 0xCC000000.toInt())

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
