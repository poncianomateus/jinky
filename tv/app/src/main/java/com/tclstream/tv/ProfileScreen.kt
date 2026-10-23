package com.tclstream.tv

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** The account as the PC reports it (GET /profile). */
data class SteamProfileData(
    val name: String, val avatar: String, val level: Int, val games: Int, val minutes: Long,
    val unlocked: Int, val total: Int, val legendary: Int, val epic: Int, val perfect: Int,
    val recent: List<Recent>, val played: List<Played>,
) {
    data class Recent(val gameId: Int, val game: String, val name: String, val icon: String, val percent: Double, val time: Long)
    data class Played(val id: Int, val name: String, val minutes: Int, val unlocked: Int, val total: Int)

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
                arr("recent").map { Recent(it.optInt("gameId"), it.optString("game"), it.optString("name"), it.optString("icon"), it.optDouble("percent", -1.0), it.optLong("time")) },
                arr("played").map { Played(it.optInt("id"), it.optString("name"), it.optInt("minutes"), it.optInt("unlocked"), it.optInt("total")) },
            ) to null
        }
    }
}

/**
 * Perfil (design v2): the Steam account's name, avatar and level over the art of the last game
 * played; big numbers (achievements, legendary ones, games at 100%, hours); the recently played
 * games with time and completion; and the latest achievements unlocked.
 */
class ProfileScreen(context: Context, private val onBack: () -> Unit) : TvPage(context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val banner = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val clock = mono(15f, Jinky.TEXT)
    private val avatar = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val initial = text(40f, Jinky.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    private val ring = ProgressRing(context, 5f).apply { color = Jinky.AMBER }
    private val level = mono(10f, 0xFF1A1204.toInt(), 700)
    private val name = text(34f, Jinky.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val sub = mono(10f, Jinky.SOFT)
    private val stats = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val played = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val recent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val message = text(12f, Jinky.MUTED)
    private var generation = 0
    private var pc: Pair<String, Int>? = null
    private val back = View(context).apply { isFocusable = true }  // keeps focus on the page

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

        // Avatar with the achievements ring and the Steam level.
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
            }, LayoutParams(dp(88), dp(88), Gravity.CENTER))
            addView(level.apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = Jinky.rounded(Jinky.AMBER, dp(12).toFloat())
            }, LayoutParams(-2, dp(22), Gravity.BOTTOM or Gravity.END))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(face, LinearLayout.LayoutParams(dp(110), dp(110)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(dp(520), -2).apply { setMargins(dp(20), 0, 0, dp(8)) })
        }, LayoutParams(-2, -2).apply { setMargins(dp(48), dp(100), 0, 0) })

        addView(message, LayoutParams(-2, -2).apply { setMargins(dp(48), dp(250), 0, 0) })
        addView(stats, LayoutParams(dp(560), dp(62)).apply { setMargins(dp(48), dp(250), 0, 0) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(mono(7.5f, Jinky.MUTED, 700).apply { text = "JOGADOS RECENTEMENTE"; letterSpacing = 0.2f })
            addView(played, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        }, LayoutParams(dp(560), -2).apply { setMargins(dp(48), dp(338), 0, 0) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = Jinky.rounded(Jinky.SURFACE, dp(13).toFloat())
            addView(mono(7.5f, Jinky.MUTED, 700).apply { text = "CONQUISTAS RECENTES"; letterSpacing = 0.2f })
            addView(recent, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        }, LayoutParams(dp(285), -2, Gravity.END).apply { setMargins(0, dp(250), dp(48), 0) })
        addView(back, LayoutParams(1, 1))
    }

    /** Loads the profile from the PC at [pcIp]. */
    fun show(pcIp: String, port: Int) {
        val gen = ++generation
        pc = pcIp to port
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        stats.removeAllViews()
        played.removeAllViews()
        recent.removeAllViews()
        banner.setImageDrawable(null)
        avatar.setImageDrawable(null)
        name.text = ""
        sub.text = ""
        level.visibility = View.GONE
        ring.progress = 0f
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
            addView(mono(21f, color, 700).apply { text = value })
            addView(text(9f, Jinky.SOFT).apply { text = label })
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (stats.childCount > 0) marginStart = dp(9) })
        stat("${p.unlocked}", "Conquistas", Jinky.TEXT)
        stat("${p.legendary}", "Lendárias", Jinky.AMBER)
        stat("${p.perfect}", "Jogos 100%", Jinky.BLUE_SOFT)
        stat("${p.minutes / 60} h", "Jogadas", Jinky.TEXT)

        for (g in p.played) {
            val pct = if (g.total > 0) "${g.unlocked * 100 / g.total}%" else "—"
            played.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val cover = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Jinky.rounded(Jinky.RAISED, dp(8).toFloat())
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
                ArtLoader.load(pcIp, port, g.id, "cover", 2) { bmp -> if (bmp != null) cover.setImageBitmap(bmp) }
                addView(cover, LinearLayout.LayoutParams(-1, dp(120)))
                addView(mono(8.5f, if (g.total > 0 && g.unlocked == g.total) Jinky.AMBER else Jinky.SOFT).apply {
                    text = listOf(Jinky.hours(g.minutes).ifEmpty { "0 h" }, pct).joinToString(" · ")
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (played.childCount > 0) marginStart = dp(9) })
        }

        if (p.recent.isEmpty()) recent.addView(text(9.5f, Jinky.MUTED).apply { text = "Nenhuma conquista ainda nos jogos instalados." })
        for (r in p.recent.take(5)) {
            val rarity = Rarity.of(r.percent)
            recent.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val icon = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Jinky.rounded(rarity.ground, dp(7).toFloat())
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(7).toFloat())
                    }
                }
                if (r.icon.isNotBlank()) ArtLoader.loadUrl(r.icon, 1) { bmp -> if (bmp != null) icon.setImageBitmap(bmp) }
                addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
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
            }, LinearLayout.LayoutParams(-1, -2).apply { if (recent.childCount > 0) topMargin = dp(9) })
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
        back.requestFocusFromTouch()
    }

    fun close() = onBack()
}
