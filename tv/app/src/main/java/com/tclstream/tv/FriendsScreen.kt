package com.tclstream.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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

/** A Steam friend as the PC reports it (GET /friends). */
data class SteamFriend(
    val id: String, val name: String, val avatar: String, val gameId: Int, val game: String,
    val state: Int, val lastOff: Long, val since: Long,
) {
    val playing get() = game.isNotEmpty()
    val online get() = state > 0 || playing

    companion object {
        /** The list and the PC's clock (unix seconds), or null and the reason. */
        fun fetch(pcIp: String, port: Int): Triple<List<SteamFriend>?, Long, String?> {
            val c = URL("http://$pcIp:$port/friends").openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 30000
            val code = c.responseCode
            if (code == 404) { c.disconnect(); return Triple(null, 0, "atualize o Jinky do PC para ver seus amigos") }
            val o = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            c.disconnect()
            if (o.has("error")) return Triple(null, 0, o.optString("error"))
            val a = o.optJSONArray("friends") ?: org.json.JSONArray()
            val list = (0 until a.length()).map { a.getJSONObject(it) }.map {
                SteamFriend(it.optString("id"), it.optString("name"), it.optString("avatar"), it.optInt("gameId"),
                    it.optString("game"), it.optInt("state"), it.optLong("lastOff"), it.optLong("since"))
            }
            return Triple(list, o.optLong("now", System.currentTimeMillis() / 1000), null)
        }

        /** What [id] played in the last two weeks: (game id, name, minutes in 2 weeks, total minutes). */
        fun recentGames(pcIp: String, port: Int, id: String): List<FriendGame> {
            val c = URL("http://$pcIp:$port/friends/games?id=$id").openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 15000
            val o = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            c.disconnect()
            val a = o.optJSONArray("games") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }.map {
                FriendGame(it.optInt("id"), it.optString("name"), it.optInt("recent"), it.optInt("total"))
            }
        }
    }
}

data class FriendGame(val id: Int, val name: String, val recent: Int, val total: Int)

/**
 * Amigos: the Steam friends list on the TV, the thing people miss most in Steam Link/Moonlight.
 * Left, everyone grouped as "Jogando agora", "Online" and "Offline" (with when they were last seen).
 * Right, the friend in focus: their avatar, what they're playing over that game's artwork, what
 * they played in the last two weeks, and "Jogar também" when that game is on the user's PC.
 * Refreshes by itself every 45 s while open.
 */
class FriendsScreen(
    context: Context,
    private val ownedGame: (Int) -> LibraryGame?,
    private val onOpenGame: (LibraryGame) -> Unit,
    private val onPlayGame: (LibraryGame) -> Unit,
) : TvPage(context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)

    private val hero = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val clock = mono(15f, Jinky.TEXT)
    private val summary = mono(9.5f, Jinky.SOFT)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, dp(4), 0, dp(40))
        addView(list)
    }
    private val message = text(12f, Jinky.MUTED).apply { setLineSpacing(0f, 1.25f) }

    // The panel for the friend in focus.
    private val panelAvatar = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val panelInitial = text(30f, Jinky.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    private val panelDot = View(context)
    private val panelName = text(22f, Jinky.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val panelStatus = text(11f, Jinky.SOFT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val panelSince = mono(8.5f, Jinky.MUTED)
    private val playButton = smallButton("").apply { id = View.generateViewId() }
    private val openButton = smallButton("").apply { id = View.generateViewId() }
    private val panelNote = text(9f, Jinky.MUTED)
    private val recentTitle = mono(7.5f, Jinky.MUTED, 700).apply { letterSpacing = 0.2f }
    private val recentList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.INVISIBLE
    }

    private var pc: Pair<String, Int>? = null
    private var friends = emptyList<SteamFriend>()
    private var now = 0L
    private var selected: SteamFriend? = null
    private val rowIds = HashMap<String, Int>()  // friend id -> row view id (focus comes back to it)
    private val recentCache = HashMap<String, List<FriendGame>>()
    private var shownHero: String? = null
    private var generation = 0
    private val refresh = Runnable { reload(quiet = true) }
    private val loadRecent = Runnable { selected?.let(::showRecent) }

    init {
        addView(hero, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Jinky.ink(0xFA), Jinky.ink(0xF0), Jinky.ink(0xA6), Jinky.ink(0x8C)))
        }, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Jinky.ink(0xC0), Jinky.ink(0x10), Jinky.ink(0x40), Jinky.ink(0xF0)))
        }, LayoutParams(-1, -1))
        addView(View(context).apply { background = Jinky.scanlines(context) }, LayoutParams(-1, -1))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_back); setColorFilter(Jinky.SOFT) },
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
            addView(text(11.5f, Jinky.SOFT, bold = true).apply { text = "Amigos" })
            addView(summary, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clock)
        }, LayoutParams(-1, dp(36)).apply { setMargins(dp(48), dp(22), dp(48), 0) })

        addView(scroll, LayoutParams(dp(400), -1).apply { setMargins(dp(48), dp(68), 0, 0) })
        addView(message, LayoutParams(dp(560), -2).apply { setMargins(dp(48), dp(90), 0, 0) })

        // The panel: avatar + name + status, the buttons, then the last two weeks.
        val face = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.BLUE_DEEP) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            addView(panelInitial, LayoutParams(-1, -1))
            addView(panelAvatar, LayoutParams(-1, -1))
        }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                addView(face, LayoutParams(dp(72), dp(72)))
                addView(panelDot, LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END))
            }, LinearLayout.LayoutParams(dp(74), dp(74)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(panelName)
                addView(panelStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                addView(panelSince, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        })
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(playButton)
            addView(openButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        panel.addView(panelNote, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        panel.addView(recentTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        panel.addView(recentList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(panel, LayoutParams(dp(400), -2).apply { setMargins(dp(512), dp(80), dp(48), 0) })
    }

    /** Opens the page for the PC at [pcIp]. */
    fun show(pcIp: String, port: Int) {
        pc = pcIp to port
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        list.removeAllViews()
        rowIds.clear()
        friends = emptyList()
        selected = null
        panel.visibility = View.INVISIBLE
        summary.text = ""
        message.text = "Carregando seus amigos…"
        message.visibility = View.VISIBLE
        reload(quiet = false)
    }

    /** Stops the automatic refresh (the page was left). */
    fun hidden() {
        main.removeCallbacks(refresh)
        main.removeCallbacks(loadRecent)
        generation++
    }

    /** Back on the page (from a game's page): refresh what is shown. */
    fun resumed() {
        if (pc != null) reload(quiet = true)
    }

    private fun reload(quiet: Boolean) {
        val (ip, port) = pc ?: return
        val gen = ++generation
        main.removeCallbacks(refresh)
        io.execute {
            val (list, pcNow, error) = try { SteamFriend.fetch(ip, port) } catch (e: Exception) {
                Log.w("Jinky", "friends: $e")
                Triple(null, 0L, "não foi possível falar com o PC")
            }
            main.post {
                if (gen != generation || visibility != View.VISIBLE) return@post
                if (list == null) {
                    // A refresh that fails keeps what is on screen.
                    if (!quiet) message.text = "Amigos indisponíveis: $error"
                } else {
                    now = pcNow
                    fill(list)
                }
                main.postDelayed(refresh, 45_000)
            }
        }
    }

    private fun fill(all: List<SteamFriend>) {
        // A refresh while the user is in the panel would move the focus: keep the old list then.
        if (friends.isNotEmpty() && panel.findFocus() != null) return
        val focusedId = selected?.id.takeIf { list.findFocus() != null }
        friends = all
        list.removeAllViews()
        rowIds.clear()
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val playing = all.filter { it.playing }
        val online = all.filter { it.online && !it.playing }
        val offline = all.filter { !it.online }
        summary.text = listOfNotNull(
            "${playing.size} jogando".takeIf { playing.isNotEmpty() },
            "${online.size} online".takeIf { online.isNotEmpty() },
            "${all.size} ${if (all.size == 1) "amigo" else "amigos"}",
        ).joinToString("  ·  ")
        if (all.isEmpty()) {
            message.text = "Nenhum amigo na sua conta da Steam ainda."
            message.visibility = View.VISIBLE
            panel.visibility = View.INVISIBLE
            return
        }
        message.visibility = View.GONE
        section("JOGANDO AGORA", playing)
        section("ONLINE", online)
        section("OFFLINE", offline)
        val target = focusedId?.let { rowIds[it] }?.let { list.findViewById<View>(it) }
        if (target != null) target.requestFocus()
        else if (visibility == View.VISIBLE) focusFirst()
    }

    private fun section(title: String, people: List<SteamFriend>) {
        if (people.isEmpty()) return
        list.addView(mono(7.5f, Jinky.MUTED, 700).apply { text = "$title  ${people.size}"; letterSpacing = 0.2f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = if (list.childCount > 0) dp(16) else 0; bottomMargin = dp(8) })
        for (f in people) list.addView(row(f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun row(f: SteamFriend) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        id = View.generateViewId()
        rowIds[f.id] = id
        isFocusable = true
        isClickable = true
        setPadding(dp(10), dp(7), dp(12), dp(7))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.RAISED, dp(12).toFloat(), dp(2), Jinky.TEXT))
            addState(intArrayOf(), Jinky.rounded(Jinky.GLASS, dp(12).toFloat()))
        }
        val face = avatar(f, 34)
        addView(FrameLayout(context).apply {
            addView(face, LayoutParams(dp(34), dp(34)))
            addView(dot(f, 11), LayoutParams(dp(11), dp(11), Gravity.BOTTOM or Gravity.END))
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(11f, if (f.online) Jinky.TEXT else Jinky.SOFT, bold = true).apply {
                text = f.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(text(8.5f, statusColor(f)).apply { text = statusLine(f); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
        // A small tag when the game they're playing is on this PC too.
        if (f.playing && ownedGame(f.gameId) != null) addView(mono(7f, Jinky.INK, 700).apply {
            text = "VOCÊ TEM"
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = Jinky.rounded(Jinky.GREEN, dp(6).toFloat())
        })
        alpha = if (f.online) 1f else 0.8f
        setOnFocusChangeListener { v, has ->
            if (!has) return@setOnFocusChangeListener
            select(f)
            scrollIntoView(v)
        }
        setOnClickListener {
            // OK on a friend: straight to the buttons (or the first recent game).
            listOf<View>(playButton, openButton).firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
                ?: recentList.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
        }
    }

    private fun select(f: SteamFriend) {
        selected = f
        panel.visibility = View.VISIBLE
        panelName.text = f.name
        panelInitial.text = f.name.take(1).uppercase()
        panelAvatar.setImageDrawable(null)
        if (f.avatar.isNotBlank()) ArtLoader.loadUrl(f.avatar, 1) { bmp -> if (selected == f && bmp != null) panelAvatar.setImageBitmap(bmp) }
        panelDot.background = dotDrawable(f, 16)
        panelStatus.text = statusLine(f, long = true)
        panelStatus.setTextColor(statusColor(f))
        panelSince.text = if (f.since > 0) "Amigos desde ${java.text.SimpleDateFormat("MMM 'de' yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(f.since * 1000))}" else ""
        panelSince.gone(f.since <= 0)

        // What they're playing: play it too, or open its page, when it's on this PC.
        val mine = if (f.playing) ownedGame(f.gameId) else null
        playButton.gone(mine?.state != LibraryGame.State.INSTALLED)
        openButton.gone(mine == null)
        if (mine != null) {
            playButton.text = "Jogar também"
            playButton.setOnClickListener { onPlayGame(mine) }
            openButton.text = if (mine.state == LibraryGame.State.INSTALLED) "Ver jogo" else "Instalar ${mine.name}"
            openButton.setOnClickListener { onOpenGame(mine) }
        }
        panelNote.text = when {
            f.playing && mine == null -> "Você não tem ${f.game} na Steam."
            f.playing && mine?.state == LibraryGame.State.NOT_INSTALLED -> "Está na sua biblioteca, mas não instalado."
            else -> ""
        }
        panelNote.gone(panelNote.text.isEmpty())
        for (b in listOf(playButton, openButton)) b.nextFocusLeftId = rowIds[f.id] ?: View.NO_ID
        showHero(f)

        recentTitle.text = "ÚLTIMAS DUAS SEMANAS"
        recentList.removeAllViews()
        val cached = recentCache[f.id]
        if (cached != null) fillRecent(f, cached)
        else {
            recentList.addView(text(9f, Jinky.MUTED).apply { text = "Carregando…" })
            // Waits a moment so scrolling down the list doesn't ask Steam for every friend passed.
            main.removeCallbacks(loadRecent)
            main.postDelayed(loadRecent, 350)
        }
    }

    private fun showRecent(f: SteamFriend) {
        val (ip, port) = pc ?: return
        io.execute {
            val games = try { SteamFriend.recentGames(ip, port, f.id) } catch (e: Exception) { emptyList() }
            main.post {
                recentCache[f.id] = games
                if (selected == f) fillRecent(f, games)
            }
        }
    }

    private fun fillRecent(f: SteamFriend, games: List<FriendGame>) {
        recentList.removeAllViews()
        if (games.isEmpty()) {
            recentList.addView(text(9f, Jinky.MUTED).apply {
                text = "Nada nas últimas duas semanas, ou o perfil é privado."
            })
            if (selected == f && !f.playing) showHero(f, games)
            return
        }
        for (g in games.take(4)) {
            val mine = ownedGame(g.id)
            recentList.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = mine != null
                isClickable = mine != null
                nextFocusLeftId = rowIds[f.id] ?: View.NO_ID
                setPadding(dp(8), dp(6), dp(10), dp(6))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.RAISED, dp(10).toFloat(), dp(2), Jinky.TEXT))
                    addState(intArrayOf(), Jinky.rounded(Jinky.GLASS, dp(10).toFloat()))
                }
                val art = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Jinky.rounded(Jinky.RAISED, dp(6).toFloat())
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
                    }
                }
                ArtLoader.loadUrl("https://cdn.cloudflare.steamstatic.com/steam/apps/${g.id}/capsule_184x69.jpg", 1) { bmp ->
                    if (bmp != null) art.setImageBitmap(bmp)
                }
                addView(art, LinearLayout.LayoutParams(dp(92), dp(35)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(10f, Jinky.TEXT, bold = true).apply { text = g.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(mono(8f, Jinky.MUTED).apply {
                        text = listOf("${Jinky.hours(g.recent).ifEmpty { "${g.recent} min" }} em 2 semanas",
                            Jinky.hours(g.total).takeIf { it.isNotEmpty() }?.let { "$it no total" }).filterNotNull().joinToString(" · ")
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                if (mine != null) addView(mono(7f, Jinky.INK, 700).apply {
                    text = "VOCÊ TEM"
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = Jinky.rounded(Jinky.GREEN, dp(6).toFloat())
                })
                if (mine != null) setOnClickListener { onOpenGame(mine) }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (recentList.childCount > 0) topMargin = dp(6) })
        }
        if (selected == f && !f.playing) showHero(f, games)
    }

    /** The artwork behind the page: the game they play now, else the one they played most lately. */
    private fun showHero(f: SteamFriend, recent: List<FriendGame>? = recentCache[f.id]) {
        val gameId = if (f.playing) f.gameId else recent?.firstOrNull()?.id ?: 0
        val key = gameId.toString()
        if (key == shownHero) return
        shownHero = key
        if (gameId == 0 || !Jinky.showArt) { hero.animate().alpha(0f).setDuration(300).start(); return }
        val done = { bmp: Bitmap? ->
            if (shownHero == key) {
                if (bmp == null) hero.animate().alpha(0f).setDuration(300).start()
                else {
                    hero.setImageBitmap(bmp)
                    hero.alpha = 0f
                    hero.animate().alpha(1f).setDuration(500).start()
                }
            }
        }
        val (ip, port) = pc ?: return
        if (ownedGame(gameId) != null) ArtLoader.load(ip, port, gameId, "hero", 2) { bmp ->
            if (bmp != null) done(bmp) else ArtLoader.loadUrl(steamHero(gameId), 2, done)
        } else ArtLoader.loadUrl(steamHero(gameId), 2, done)
    }

    private fun steamHero(id: Int) = "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_hero.jpg"

    private fun avatar(f: SteamFriend, size: Int) = FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.RAISED) }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        addView(text(size * 0.38f, Jinky.SOFT, bold = true).apply { text = f.name.take(1).uppercase(); gravity = Gravity.CENTER }, LayoutParams(-1, -1))
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(img, LayoutParams(-1, -1))
        if (f.avatar.isNotBlank()) ArtLoader.loadUrl(f.avatar, 4) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
        if (!f.online) img.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
    }

    private fun dot(f: SteamFriend, size: Int) = View(context).apply { background = dotDrawable(f, size) }

    private fun dotDrawable(f: SteamFriend, size: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(statusColor(f))
        setStroke(dp(2), Jinky.INK)
        setSize(dp(size), dp(size))
    }

    private fun statusColor(f: SteamFriend) = when {
        f.playing -> Jinky.GREEN
        f.state == 1 || f.state >= 5 -> Jinky.BLUE_SOFT
        f.state in 2..4 -> Jinky.AMBER
        else -> Jinky.MUTED
    }

    private fun statusLine(f: SteamFriend, long: Boolean = false) = when {
        f.playing -> if (long) "Jogando ${f.game}" else f.game
        f.state == 2 -> "Ocupado"
        f.state == 3 || f.state == 4 -> "Ausente"
        f.state == 6 -> "Procurando partida"
        f.state > 0 -> "Online"
        f.lastOff > 0 -> "Visto ${ago(f.lastOff)}"
        else -> "Offline"
    }

    private fun ago(unix: Long): String {
        val m = ((if (now > 0) now else System.currentTimeMillis() / 1000) - unix) / 60
        return when {
            m < 2 -> "agora há pouco"
            m < 60 -> "há $m min"
            m < 60 * 24 -> "há ${m / 60} h"
            m < 60 * 48 -> "ontem"
            m < 60 * 24 * 30 -> "há ${m / (60 * 24)} dias"
            m < 60 * 24 * 365 -> "há ${m / (60 * 24 * 30)} ${if (m / (60 * 24 * 30) == 1L) "mês" else "meses"}"
            else -> "há mais de um ano"
        }
    }

    /** Scrolls the list only as much as needed to show [v]. */
    private fun scrollIntoView(v: View) {
        val margin = dp(30)
        val top = v.top
        when {
            top - margin < scroll.scrollY -> scroll.smoothScrollTo(0, (top - margin).coerceAtLeast(0))
            top + v.height + margin > scroll.scrollY + scroll.height - scroll.paddingBottom ->
                scroll.smoothScrollTo(0, top + v.height + margin - (scroll.height - scroll.paddingBottom))
        }
    }

    override fun focusFirst() {
        (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
    }

    /** Back from the panel returns to the list first; true when it did. */
    fun backToList(): Boolean {
        if (panel.findFocus() == null) return false
        selected?.id?.let { rowIds[it] }?.let { list.findViewById<View>(it) }?.requestFocus() ?: focusFirst()
        return true
    }
}
