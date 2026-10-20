package com.tclstream.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** A game in the PC's Steam library. */
data class LibraryGame(val id: Int, val name: String, val lastPlayed: Long, val hasCover: Boolean)

/**
 * Biblioteca: the games installed on the PC, as a grid of covers (most recently played first).
 * Choosing one starts the console session and then the game. Covers come from the PC, which
 * reads them from Steam's local cache.
 */
class LibraryScreen(
    context: Context,
    private val onPlay: (LibraryGame) -> Unit,
    private val onBack: () -> Unit,
) : TvPage(context) {

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(3)
    private val caption = text(24f, Color.WHITE, bold = true)
    private val detail = text(16f, StartScreen.MUTED)
    private val message = text(19f, StartScreen.MUTED)
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(8), dp(12), dp(8), dp(24))  // room for the focused cover to grow
        addView(grid, LayoutParams(-1, -2))
    }
    private val back = smallButton("Voltar").apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }
    private var generation = 0  // ignores answers from an older load
    private var tiles = emptyList<View>()

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
        grid.removeAllViews()
        tiles = emptyList()
        caption.text = ""
        detail.text = ""
        showMessage("Carregando seus jogos…")
        back.requestFocus()
        loader.execute {
            val games = try {
                fetchGames(pcIp, port)
            } catch (e: Exception) {
                Log.w(TAG, "library: $e")
                null
            }
            main.post {
                if (gen != generation) return@post
                when {
                    games == null -> showMessage("Não foi possível ler a biblioteca do PC. Confira se o Jinky do PC está atualizado.")
                    games.isEmpty() -> showMessage("Nenhum jogo da Steam instalado no PC.")
                    else -> {
                        showMessage(null)
                        build(games, pcIp, port, gen)
                    }
                }
            }
        }
    }

    private fun showMessage(text: String?) {
        message.text = text ?: ""
        message.gone(text == null)
    }

    private fun build(games: List<LibraryGame>, pcIp: String, port: Int, gen: Int) {
        val all = mutableListOf<View>()
        games.chunked(COLUMNS).forEachIndexed { r, rowGames ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowGames.forEachIndexed { c, game ->
                val tile = tile(game)
                all += tile
                row.addView(tile, LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply { if (c > 0) marginStart = dp(GAP) })
                if (game.hasCover) loadCover(tile, game, pcIp, port, gen)
            }
            grid.addView(row, LinearLayout.LayoutParams(-2, -2).apply { if (r > 0) topMargin = dp(GAP) })
        }
        tiles = all
        all.firstOrNull()?.requestFocus()
    }

    private fun tile(game: LibraryGame): FrameLayout = FrameLayout(context).apply {
        isFocusable = true
        isClickable = true
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
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LayoutParams(-1, -1))
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "cover" }, LayoutParams(-1, -1))
        // Focus ring drawn above the cover.
        foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat(); setStroke(dp(3), Color.TRANSPARENT)
        }
        setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.07f else 1f).scaleY(if (focused) 1.07f else 1f).setDuration(120).start()
            v.elevation = if (focused) dp(8).toFloat() else 0f
            (v.foreground as GradientDrawable).setStroke(dp(3), if (focused) StartScreen.ACCENT else Color.TRANSPARENT)
            if (focused) {
                caption.text = game.name
                detail.text = lastPlayedText(game.lastPlayed)
            }
        }
        setOnClickListener { onPlay(game) }
    }

    private fun loadCover(tile: FrameLayout, game: LibraryGame, pcIp: String, port: Int, gen: Int) {
        loader.execute {
            val bmp = try {
                val c = URL("http://$pcIp:$port/library/cover?id=${game.id}").openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 8000
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                // Covers are 600x900; half size is plenty for a tile and saves TV memory.
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            } catch (e: Exception) {
                null
            }
            if (bmp != null) main.post { if (gen == generation) showCover(tile, bmp) }
        }
    }

    private fun showCover(tile: FrameLayout, bmp: Bitmap) {
        val image = tile.findViewWithTag<ImageView>("cover")
        image.setImageBitmap(bmp)
        // A landscape banner (games without a portrait cover) is shown whole on the dark card.
        image.scaleType = if (bmp.width > bmp.height) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if (bmp.width > bmp.height) (tile.getChildAt(0) as TextView).gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
    }

    private fun lastPlayedText(unix: Long): String {
        if (unix <= 0) return "Ainda não jogado"
        val days = (System.currentTimeMillis() / 1000 - unix) / 86400
        return when {
            days < 1 -> "Jogado hoje"
            days < 2 -> "Jogado ontem"
            days < 30 -> "Jogado há $days dias"
            else -> "Jogado em " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(unix * 1000))
        } + "   ·   A ou OK: jogar"
    }

    override fun focusFirst() {
        (tiles.firstOrNull() ?: back).requestFocus()
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
            c.readTimeout = 15000
            val json = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            c.disconnect()
            val list = org.json.JSONObject(json).getJSONArray("games")
            return (0 until list.length()).map {
                val g = list.getJSONObject(it)
                LibraryGame(g.getInt("id"), g.getString("name"), g.optLong("lastPlayed"), g.optBoolean("cover"))
            }
        }

        /** Asks the PC to start [game] (a session must be running). Returns null or an error. */
        fun launch(pcIp: String, port: Int, game: LibraryGame): String? = try {
            val c = URL("http://$pcIp:$port/library/launch?id=${game.id}").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 8000
            val code = c.responseCode
            c.disconnect()
            if (code == 200) null else "o PC respondeu $code"
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }
}
