package com.tclstream.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.net.HttpURLConnection
import java.net.URL

/** One Steam achievement of a game, as the PC reports it. */
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val iconGray: String,
    val hidden: Boolean,
    val achieved: Boolean,
    val unlockTime: Long,
    val percent: Double,  // players worldwide who have it (-1 = unknown)
) {
    val rarity get() = Rarity.of(percent)
}

data class AchievementList(val total: Int, val unlocked: Int, val items: List<Achievement>, val error: String?,
                           val progressHidden: Boolean = false)

/** An achievement unlocked while playing (for the toast over the game). */
data class AchievementUnlock(
    val at: Long, val appId: Int, val game: String, val id: String, val name: String, val description: String,
    val icon: String, val percent: Double, val unlocked: Int, val total: Int,
) {
    val rarity get() = Rarity.of(percent)
}

/** How rare an achievement is, from the share of Steam players who have it. */
enum class Rarity(val label: String, val color: Int, val soft: Int, val ground: Int) {
    LEGENDARY("Lendária", Jinky.AMBER, 0xFFFFD58A.toInt(), 0xFF3A2E10.toInt()),
    EPIC("Épica", Jinky.VIOLET, 0xFFB9A4FF.toInt(), 0xFF1E1A3D.toInt()),
    RARE("Rara", Jinky.BLUE, Jinky.BLUE_SOFT, 0xFF12233F.toInt()),
    COMMON("Comum", Jinky.STEEL, Jinky.SOFT, 0xFF1C2233.toInt());

    companion object {
        fun of(percent: Double) = when {
            percent < 0 -> COMMON
            percent < 1 -> LEGENDARY
            percent < 5 -> EPIC
            percent < 20 -> RARE
            else -> COMMON
        }
    }
}

/** "Avisos do PC na TV": a download finished, a friend came online... (GET /notices). */
data class PcNotice(val id: Long, val kind: String, val title: String, val text: String, val gameId: Int, val icon: String) {
    /** The card's small caps line. */
    val label get() = when (kind) {
        "download" -> "DOWNLOAD CONCLUÍDO"
        "downloadStart" -> "BAIXANDO NO PC"
        "update" -> "JOGO ATUALIZADO"
        "friend" -> "AMIGOS"
        "paused" -> "JOGO PAUSADO FECHADO"
        else -> "NO PC"
    }
    /** A download or update finished: the PC's game list changed. */
    val changesLibrary get() = kind == "download" || kind == "update" || kind == "downloadStart"
}

object NoticesApi {
    /** The notices after [after] (-1: none, just where the list stands) and the last id. */
    fun after(pcIp: String, port: Int, after: Long): Pair<Long, List<PcNotice>> {
        val c = URL("http://$pcIp:$port/notices?after=$after").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 8000
        val json = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        val o = org.json.JSONObject(json)
        val a = o.optJSONArray("notices")
        val list = (0 until (a?.length() ?: 0)).map {
            val x = a!!.getJSONObject(it)
            PcNotice(x.optLong("id"), x.optString("kind"), x.optString("title"), x.optString("text"), x.optInt("gameId"), x.optString("icon"))
        }
        return o.optLong("last") to list
    }
}

object AchievementsApi {
    private fun get(url: String): org.json.JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 20000
        val json = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        return org.json.JSONObject(json)
    }

    fun fetch(pcIp: String, port: Int, gameId: Int): AchievementList {
        val o = get("http://$pcIp:$port/achievements?id=$gameId")
        val a = o.optJSONArray("items")
        val items = (0 until (a?.length() ?: 0)).map {
            val x = a!!.getJSONObject(it)
            Achievement(x.optString("id"), x.optString("name"), x.optString("desc"), x.optString("icon"),
                x.optString("iconGray"), x.optBoolean("hidden"), x.optBoolean("achieved"), x.optLong("time"),
                x.optDouble("percent", -1.0))
        }
        val error = if (o.isNull("error")) null else o.optString("error").ifBlank { null }
        return AchievementList(o.optInt("total"), o.optInt("unlocked"), items, error, o.optBoolean("progressHidden"))
    }

    /** Unlocks the PC noticed after [after] (its clock, ms; -1 = just learn the clock). */
    fun unlocks(pcIp: String, port: Int, after: Long): Pair<Long, List<AchievementUnlock>> {
        val o = get("http://$pcIp:$port/achievements/unlocks?after=$after")
        val a = o.optJSONArray("unlocks")
        val list = (0 until (a?.length() ?: 0)).map {
            val x = a!!.getJSONObject(it)
            AchievementUnlock(x.optLong("at"), x.optInt("appId"), x.optString("game"), x.optString("id"),
                x.optString("name"), x.optString("desc"), x.optString("icon"), x.optDouble("percent", -1.0),
                x.optInt("unlocked"), x.optInt("total"))
        }
        return o.optLong("now") to list
    }

    fun percentText(p: Double) = if (p < 0) "" else "%s%% dos jogadores".format(
        if (p < 10) String.format(java.util.Locale("pt", "BR"), "%.1f", p) else p.toInt().toString())
}

/** A progress ring (achievements completed): track, arc from the top, clockwise. */
class ProgressRing(context: Context, private val strokeDp: Float = 6f) : View(context) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Jinky.LINE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Jinky.BLUE }
    private val box = RectF()

    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color: Int
        get() = arc.color
        set(v) { arc.color = v; invalidate() }

    /** Fills the ring from empty to [to] (when it first appears). */
    fun animateTo(to: Float) {
        android.animation.ValueAnimator.ofFloat(0f, to).apply {
            duration = 900
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = strokeDp * density
        track.strokeWidth = w
        arc.strokeWidth = w
        box.set(w / 2, w / 2, width - w / 2, height - w / 2)
        canvas.drawArc(box, 0f, 360f, false, track)
        if (progress > 0f) canvas.drawArc(box, -90f, 360f * progress, false, arc)
    }
}
