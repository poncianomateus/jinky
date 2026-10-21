package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
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
) {
    enum class State { INSTALLED, DOWNLOADING, NOT_INSTALLED }
}

/**
 * Biblioteca: the PC's Steam games as a grid of covers — installed ones first (most recently played
 * first), then, when the PC has the account's list, the games to install. Downloads show their
 * progress on the cover and update by themselves.
 */
class LibraryScreen(
    context: Context,
    private val onPlay: (LibraryGame) -> Unit,
    private val onInstall: (LibraryGame) -> Unit,
    private val onBack: () -> Unit,
) : TvPage(context) {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val caption = text(24f, Color.WHITE, bold = true)
    private val detail = text(16f, StartScreen.MUTED)
    private val message = text(19f, StartScreen.MUTED)
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(8), dp(12), dp(8), dp(24))  // room for the focused cover to grow
        addView(grid, LayoutParams(-1, -2))
        viewTreeObserver.addOnScrollChangedListener { updateCovers() }
    }
    private val back = smallButton("Voltar").apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }
    private var generation = 0  // ignores answers from an older load
    private var tiles = emptyList<View>()
    private var pc: Pair<String, Int>? = null
    private val bars = HashMap<Int, View>()  // game id -> progress bar fill (downloads)
    private var games = HashMap<Int, LibraryGame>()
    private var focusedId = -1
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

    init {
        val root = pageRoot()
        root.addView(brandHeader("Biblioteca").apply {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(back, LinearLayout.LayoutParams(-2, -2))
        })
        root.addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        root.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        root.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        addView(root, LayoutParams(-1, -1))
        back.setOnClickListener { onBack() }
    }

    /** Loads (or reloads) the library of the PC at [pcIp]. */
    fun load(pcIp: String, port: Int) {
        val gen = ++generation
        pc = pcIp to port
        grid.removeAllViews()
        tiles = emptyList()
        bars.clear()
        tileGames.clear()
        coverLoaded.clear()
        caption.text = ""
        detail.text = ""
        showMessage("Carregando seus jogos…")
        back.requestFocus()
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
                        showMessage(null)
                        build(list, pcIp, port)
                    }
                }
            }
        }
    }

    private fun showMessage(text: String?) {
        message.text = text ?: ""
        message.gone(text == null)
    }

    private fun build(list: List<LibraryGame>, pcIp: String, port: Int) {
        games = HashMap(list.associateBy { it.id })
        val all = mutableListOf<View>()
        val here = list.filter { it.state != LibraryGame.State.NOT_INSTALLED }
        val toInstall = list.filter { it.state == LibraryGame.State.NOT_INSTALLED && !it.family }
        val family = list.filter { it.state == LibraryGame.State.NOT_INSTALLED && it.family }
        fun section(title: String, part: List<LibraryGame>) {
            if (part.isEmpty()) return
            grid.addView(text(18f, 0xFFAAB6C8.toInt(), bold = true).apply { text = title },
                LinearLayout.LayoutParams(-2, -2).apply { if (grid.childCount > 0) topMargin = dp(28); bottomMargin = dp(12) })
            part.chunked(COLUMNS).forEachIndexed { r, rowGames ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                rowGames.forEachIndexed { c, game ->
                    val tile = tile(game)
                    all += tile
                    row.addView(tile, LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply { if (c > 0) marginStart = dp(GAP) })
                    if (game.hasCover) tileGames += tile to game
                }
                grid.addView(row, LinearLayout.LayoutParams(-2, -2).apply { if (r > 0) topMargin = dp(GAP) })
            }
        }
        section("Instalados", here)
        section("Para instalar  ·  ${toInstall.size} jogos da sua conta", toInstall)
        section("Da família  ·  ${family.size} jogos compartilhados com você", family)
        tiles = all
        scroll.post { updateCovers() }
        all.firstOrNull()?.requestFocus()
        main.removeCallbacks(poll)
        if (bars.isNotEmpty()) main.postDelayed(poll, 4000)
    }

    private fun tile(game: LibraryGame): FrameLayout = FrameLayout(context).apply {
        isFocusable = true
        isClickable = true
        tag = game.id
        background = GradientDrawable().apply { setColor(0xFF121821.toInt()); cornerRadius = dp(12).toFloat() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
        }
        // Until (or unless) the cover arrives: the name on a plain card.
        addView(TextView(context).apply {
            text = game.name
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LayoutParams(-1, -1))
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setTag(R.id.cover, true) }, LayoutParams(-1, -1))
        when (game.state) {
            LibraryGame.State.NOT_INSTALLED -> {
                // Not on the PC yet: a little dimmer, with a download badge.
                addView(View(context).apply { setBackgroundColor(0x66070A0F) }, LayoutParams(-1, -1))
                addView(badge("Instalar"), LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
            }
            LibraryGame.State.DOWNLOADING -> addProgress(this, game)
            LibraryGame.State.INSTALLED -> {}
        }
        foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat(); setStroke(dp(3), Color.TRANSPARENT)
        }
        setOnFocusChangeListener { v, focused ->
            if (focused) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(240).setInterpolator(OvershootInterpolator(1.4f)).start()
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
            v.elevation = if (focused) dp(8).toFloat() else 0f
            (v.foreground as GradientDrawable).setStroke(dp(3), if (focused) Color.WHITE else Color.TRANSPARENT)
            if (focused) {
                focusedId = game.id
                describe(games[game.id] ?: game)
            }
        }
        setOnClickListener {
            val g = games[game.id] ?: game
            when (g.state) {
                LibraryGame.State.INSTALLED -> onPlay(g)
                LibraryGame.State.NOT_INSTALLED -> onInstall(g)
                LibraryGame.State.DOWNLOADING -> {}  // nothing to do until it finishes
            }
        }
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
        val track = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(0x66000000); cornerRadius = dp(3).toFloat() }
        }
        val fill = View(context).apply {
            background = GradientDrawable().apply { setColor(StartScreen.ACCENT); cornerRadius = dp(3).toFloat() }
            pivotX = 0f
            scaleX = game.progress.toFloat().coerceIn(0.02f, 1f)
        }
        track.addView(fill, LayoutParams(-1, -1))
        tile.addView(View(context).apply { setBackgroundColor(0x55070A0F) }, LayoutParams(-1, -1))
        tile.addView(track, LayoutParams(-1, dp(6), Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(12)) })
        bars[game.id] = fill
    }

    private fun badge(label: String) = TextView(context).apply {
        text = label
        setTextColor(0xFF06101F.toInt())
        textSize = 13f
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(99).toFloat() }
    }

    private fun describe(g: LibraryGame) {
        caption.text = g.name
        detail.text = when (g.state) {
            LibraryGame.State.INSTALLED -> lastPlayedText(g.lastPlayed) + "   ·   A: jogar"
            LibraryGame.State.DOWNLOADING -> "Baixando no PC: ${(g.progress * 100).toInt()}%"
            LibraryGame.State.NOT_INSTALLED ->
                (if (g.family) "Compartilhado pela família" else "Na sua conta") + ", não instalado   ·   A: instalar (a Steam pede confirmação)"
        }
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
            if (g.id == focusedId && (old.state != g.state || old.progress != g.progress)) describe(g)
        }
    }

    private fun showCover(tile: FrameLayout, bmp: android.graphics.Bitmap) {
        val image = coverImage(tile) ?: return
        image.setImageBitmap(bmp)
        // A landscape banner (games without a portrait cover) is shown whole on the dark card.
        if (bmp.width > bmp.height) {
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            (tile.getChildAt(0) as TextView).gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
        if (games[tile.tag as Int]?.state == LibraryGame.State.NOT_INSTALLED) {
            image.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.35f) })
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

    override fun focusFirst() {
        (tiles.firstOrNull() ?: back).requestFocus()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        main.removeCallbacks(poll)
        if (visibility == View.VISIBLE && bars.isNotEmpty()) main.postDelayed(poll, 1000)
    }

    companion object {
        private const val TAG = "Jinky"
        private const val COLUMNS = 6
        private const val TILE_W = 122
        private const val TILE_H = 183
        private const val GAP = 18

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
                    g.optBoolean("family"))
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

        /** Asks the PC to install [game] (Steam shows its confirmation in the session). */
        fun install(pcIp: String, port: Int, game: LibraryGame): String? = post(pcIp, port, "/library/install?id=${game.id}")
    }
}
