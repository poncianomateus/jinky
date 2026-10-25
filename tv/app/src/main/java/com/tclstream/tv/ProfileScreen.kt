package com.tclstream.tv

import android.content.Context
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** The account as the PC reports it (GET /profile). */
data class SteamProfileData(
    val name: String, val avatar: String, val level: Int, val games: Int, val minutes: Long,
    val unlocked: Int, val total: Int, val legendary: Int, val epic: Int, val perfect: Int,
    val recent: List<Recent>, val played: List<Played>, val friends: List<Friend>,
) {
    data class Recent(val gameId: Int, val game: String, val id: String, val name: String, val icon: String, val percent: Double, val time: Long)
    data class Played(val id: Int, val name: String, val minutes: Int, val unlocked: Int, val total: Int)
    data class Friend(val name: String, val avatar: String, val game: String, val state: Int)

    companion object {
        fun fetch(pcIp: String, port: Int): Pair<SteamProfileData?, String?> {
            val c = URL("http://$pcIp:$port/profile").openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 90000
            val o = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            c.disconnect()
            if (o.has("error")) return null to o.optString("error")
            fun arr(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            return SteamProfileData(
                o.optString("name"), o.optString("avatar"), o.optInt("level"), o.optInt("games"), o.optLong("minutes"),
                o.optInt("unlocked"), o.optInt("total"), o.optInt("legendary"), o.optInt("epic"), o.optInt("perfect"),
                arr("recent").map {
                    Recent(it.optInt("gameId"), it.optString("game"), it.optString("id"), it.optString("name"), it.optString("icon"),
                        it.optDouble("percent", -1.0), it.optLong("time"))
                },
                arr("played").map { Played(it.optInt("id"), it.optString("name"), it.optInt("minutes"), it.optInt("unlocked"), it.optInt("total")) },
                arr("friends").map { Friend(it.optString("name"), it.optString("avatar"), it.optString("game"), it.optInt("state")) },
            ) to null
        }
    }
}

/**
 * Perfil (design v2): the Steam account over the art of the last game played, and everything on
 * it leads somewhere: the recently played games open their page, the latest achievements open
 * straight on that achievement (with "Como conseguir"), "Quase 100%" lists the games closest to
 * complete and opens what is left, and "Amigos online" shows who is playing what right now.
 */
class ProfileScreen(
    context: Context,
    private val onBack: () -> Unit,
    private val onOpenGame: (gameId: Int) -> Unit,
    private val onOpenAchievements: (gameId: Int, achievementId: String?, lockedOnly: Boolean) -> Unit,
    private val onChoosePc: () -> Unit,
    private val onSettings: () -> Unit,
) : TvPage(context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val banner = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val clock = mono(15f, Jinky.TEXT)
    private val avatar = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val initial = text(40f, Jinky.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    private val ring = ProgressRing(context, 5f).apply { color = Jinky.AMBER }
    private val level = mono(10f, 0xFF1A1204.toInt(), 700)
    private val name = text(30f, Jinky.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val sub = mono(10f, Jinky.SOFT)
    private val stats = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val played = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val almost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val almostSection = section("MAIS PERTO DE 100%", almost)
    private val recent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val friends = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val friendsSection = section("AMIGOS ONLINE", friends)
    private val message = text(12f, Jinky.MUTED)
    private val choosePc = smallButton("Trocar de PC").apply { setOnClickListener { onChoosePc() } }
    private val settings = smallButton("Configurações").apply { setOnClickListener { onSettings() } }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, dp(6), 0, dp(40))
    }
    private var generation = 0

    init {
        addView(banner, LayoutParams(-1, dp(215)))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Jinky.ink(0x8C), Jinky.ink(0x59), Jinky.INK))
        }, LayoutParams(-1, dp(215)))
        addView(View(context).apply { background = Jinky.scanlines(context) }, LayoutParams(-1, dp(215)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_back); setColorFilter(Jinky.SOFT) },
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
            addView(text(11.5f, Jinky.SOFT, bold = true).apply { text = "Perfil" })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clock)
        }, LayoutParams(-1, dp(36)).apply { setMargins(dp(48), dp(22), dp(48), 0) })

        // Avatar with the achievements ring and the Steam level, the name, and two shortcuts.
        val face = FrameLayout(context).apply {
            addView(ring, LayoutParams(-1, -1))
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.BLUE_DEEP) }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
                }
                addView(initial, LayoutParams(-1, -1))
                addView(avatar, LayoutParams(-1, -1))
            }, LayoutParams(dp(80), dp(80), Gravity.CENTER))
            addView(level.apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = Jinky.rounded(Jinky.AMBER, dp(12).toFloat())
            }, LayoutParams(-2, dp(22), Gravity.BOTTOM or Gravity.END))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(face, LinearLayout.LayoutParams(dp(100), dp(100)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(choosePc)
                    addView(settings, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(dp(600), -2).apply { marginStart = dp(18) })
        }, LayoutParams(-2, -2).apply { setMargins(dp(48), dp(70), 0, 0) })

        // Everything below scrolls: two columns, every item selectable.
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(stats, LinearLayout.LayoutParams(-1, dp(58)))
            addView(section("JOGADOS RECENTEMENTE", played), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(almostSection, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(section("CONQUISTAS RECENTES", recent))
            addView(friendsSection, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        scroll.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), 0, dp(48), 0)
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(right, LinearLayout.LayoutParams(dp(300), -2).apply { marginStart = dp(22) })
        })
        addView(message, LayoutParams(-2, -2).apply { setMargins(dp(48), dp(200), 0, 0) })
        addView(scroll, LayoutParams(-1, -1).apply { topMargin = dp(190) })
    }

    /** Loads the profile from the PC at [pcIp]. */
    fun show(pcIp: String, port: Int) {
        val gen = ++generation
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        stats.removeAllViews()
        played.removeAllViews()
        almost.removeAllViews()
        recent.removeAllViews()
        friends.removeAllViews()
        almostSection.visibility = View.GONE
        friendsSection.visibility = View.GONE
        banner.setImageDrawable(null)
        avatar.setImageDrawable(null)
        name.text = ""
        sub.text = ""
        level.visibility = View.GONE
        ring.progress = 0f
        scroll.visibility = View.INVISIBLE
        message.text = "Carregando seu perfil… (a primeira vez pode levar alguns segundos)"
        message.visibility = View.VISIBLE
        io.execute {
            val (p, error) = try { SteamProfileData.fetch(pcIp, port) } catch (e: Exception) {
                Log.w("Jinky", "profile: $e")
                null to "não foi possível falar com o PC"
            }
            main.post {
                if (gen != generation) return@post
                if (p == null) message.text = "Perfil indisponível: $error" else fill(p, pcIp, port)
            }
        }
    }

    private fun fill(p: SteamProfileData, pcIp: String, port: Int) {
        message.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        name.text = p.name
        initial.text = p.name.take(1).uppercase()
        sub.text = "Nível ${p.level} na Steam  ·  ${p.games} jogos  ·  ${p.unlocked} de ${p.total} conquistas nos jogos instalados"
        level.text = p.level.toString()
        level.visibility = if (p.level > 0) View.VISIBLE else View.GONE
        ring.animateTo(if (p.total > 0) p.unlocked.toFloat() / p.total else 0f)
        if (p.avatar.isNotBlank()) ArtLoader.loadUrl(p.avatar, 1) { bmp -> if (bmp != null) avatar.setImageBitmap(bmp) }
        p.played.firstOrNull { it.minutes > 0 }?.let { g ->
            if (Jinky.showArt) ArtLoader.load(pcIp, port, g.id, "hero", 1) { bmp ->
                if (bmp != null) { banner.setImageBitmap(bmp); banner.alpha = 0f; banner.animate().alpha(1f).setDuration(500).start() }
            }
        }
        fun stat(value: String, label: String, color: Int) = stats.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(13), 0)
            background = Jinky.rounded(Jinky.SURFACE, dp(11).toFloat())
            addView(mono(20f, color, 700).apply { text = value })
            addView(text(9f, Jinky.SOFT).apply { text = label })
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (stats.childCount > 0) marginStart = dp(9) })
        stat("${p.unlocked}", "Conquistas", Jinky.TEXT)
        stat("${p.legendary}", "Lendárias", Jinky.AMBER)
        stat("${p.perfect}", "Jogos 100%", Jinky.BLUE_SOFT)
        stat("${p.minutes / 60} h", "Jogadas", Jinky.TEXT)

        // Recently played: covers that open the game's page.
        for (g in p.played) {
            val pct = if (g.total > 0) "${g.unlocked * 100 / g.total}%" else "—"
            played.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(dp(3), dp(3), dp(3), dp(5))
                background = focusFrame(dp(10).toFloat())
                val cover = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Jinky.rounded(Jinky.RAISED, dp(8).toFloat())
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
                ArtLoader.load(pcIp, port, g.id, "cover", 2) { bmp -> if (bmp != null) cover.setImageBitmap(bmp) }
                addView(cover, LinearLayout.LayoutParams(-1, dp(112)))
                addView(mono(8.5f, if (g.total > 0 && g.unlocked == g.total) Jinky.AMBER else Jinky.SOFT).apply {
                    text = listOf(Jinky.hours(g.minutes).ifEmpty { "0 h" }, pct).joinToString(" · ")
                    setPadding(dp(3), 0, 0, 0)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
                setOnClickListener { onOpenGame(g.id) }
                scrollIntoViewOnFocus()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (played.childCount > 0) marginStart = dp(6) })
        }

        // Closest to 100%: what is left, one click away.
        val close = p.played.filter { it.total > 0 && it.unlocked < it.total && it.unlocked > 0 }
            .sortedByDescending { it.unlocked.toDouble() / it.total }.take(3)
        almostSection.visibility = if (close.isEmpty()) View.GONE else View.VISIBLE
        for (g in close) {
            almost.addView(itemRow().apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(10.5f, Jinky.TEXT, bold = true).apply { text = g.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(text(8.5f, Jinky.MUTED).apply {
                        val left = g.total - g.unlocked
                        text = "Faltam $left ${if (left == 1) "conquista" else "conquistas"} · ${g.unlocked} de ${g.total}"
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ProgressRing(context, 3.5f).apply { progress = g.unlocked.toFloat() / g.total; color = Jinky.BLUE },
                    LinearLayout.LayoutParams(dp(28), dp(28)))
                setOnClickListener { onOpenAchievements(g.id, null, true) }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (almost.childCount > 0) topMargin = dp(7) })
        }

        // Latest achievements: each opens straight on itself (with "Como conseguir").
        if (p.recent.isEmpty()) recent.addView(text(9.5f, Jinky.MUTED).apply { text = "Nenhuma conquista ainda nos jogos instalados." })
        for (r in p.recent.take(6)) {
            val rarity = Rarity.of(r.percent)
            recent.addView(itemRow().apply {
                val icon = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Jinky.rounded(rarity.ground, dp(7).toFloat())
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(7).toFloat())
                    }
                }
                if (r.icon.isNotBlank()) ArtLoader.loadUrl(r.icon, 1) { bmp -> if (bmp != null) icon.setImageBitmap(bmp) }
                addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(10f, Jinky.TEXT, bold = true).apply { text = r.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(text(8f, Jinky.MUTED).apply {
                        text = "${r.game} · ${rarity.label}"
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(9), 0, dp(6), 0) })
                addView(mono(7.5f, Jinky.MUTED).apply { text = ago(r.time) })
                setOnClickListener { onOpenAchievements(r.gameId, r.id.ifBlank { null }, false) }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (recent.childCount > 0) topMargin = dp(7) })
        }

        // Friends online now (Steam), the ones in a game first.
        friendsSection.visibility = if (p.friends.isEmpty()) View.GONE else View.VISIBLE
        for (f in p.friends) {
            friends.addView(itemRow().apply {
                val face = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Jinky.RAISED) }
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
                    }
                }
                if (f.avatar.isNotBlank()) ArtLoader.loadUrl(f.avatar, 1) { bmp -> if (bmp != null) face.setImageBitmap(bmp) }
                addView(face, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(10f, Jinky.TEXT, bold = true).apply { text = f.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(text(8f, if (f.game.isNotEmpty()) Jinky.GREEN else Jinky.MUTED).apply {
                        text = if (f.game.isNotEmpty()) "Jogando ${f.game}" else when (f.state) {
                            3, 4 -> "Ausente"
                            2 -> "Ocupado"
                            else -> "Online"
                        }
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
            }, LinearLayout.LayoutParams(-1, -2).apply { if (friends.childCount > 0) topMargin = dp(7) })
        }
        post { focusFirst() }
    }

    private fun section(title: String, content: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(mono(7.5f, Jinky.MUTED, 700).apply { text = title; letterSpacing = 0.2f })
        addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /** A selectable row: surface card, white ring when focused. */
    private fun itemRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.RAISED, dp(10).toFloat(), dp(2), Jinky.TEXT))
            addState(intArrayOf(), Jinky.rounded(Jinky.SURFACE, dp(10).toFloat()))
        }
        scrollIntoViewOnFocus()
    }

    private fun focusFrame(radius: Float) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(android.graphics.Color.TRANSPARENT, radius, dp(2), Jinky.TEXT))
        addState(intArrayOf(), Jinky.rounded(android.graphics.Color.TRANSPARENT, radius))
    }

    /** Scrolls only when the focused item would be (partly) out of sight, by as little as needed. */
    private fun View.scrollIntoViewOnFocus() = setOnFocusChangeListener { v, has ->
        if (!has) return@setOnFocusChangeListener
        var top = v.top
        var parent = v.parent as? View
        while (parent != null && parent !== scroll) {
            top += parent.top
            parent = parent.parent as? View
        }
        val margin = dp(24)
        val visibleTop = scroll.scrollY
        val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
        when {
            top - margin < visibleTop -> scroll.smoothScrollTo(0, (top - margin).coerceAtLeast(0))
            top + v.height + margin > visibleBottom -> scroll.smoothScrollTo(0, top + v.height + margin - (scroll.height - scroll.paddingBottom))
        }
    }

    private fun ago(unix: Long): String {
        if (unix <= 0) return ""
        val h = (System.currentTimeMillis() / 1000 - unix) / 3600
        return when {
            h < 1 -> "agora"
            h < 24 -> "$h h"
            h < 48 -> "ontem"
            h < 24 * 30 -> "${h / 24} dias"
            else -> java.text.SimpleDateFormat("MMM yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(unix * 1000))
        }
    }

    override fun focusFirst() {
        (played.getChildAt(0) ?: choosePc).requestFocusFromTouch()
    }

    fun close() = onBack()
}
