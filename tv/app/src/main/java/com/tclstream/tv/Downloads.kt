package com.tclstream.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/** The game Steam is downloading on the PC right now (GET /downloads). */
data class LiveDownload(
    val id: Int, val name: String, val state: String, val percent: Int, val speed: Long, val peak: Long,
    val disk: Long, val eta: Int, val paused: Boolean, val install: Boolean,
) {
    /** "Baixando", "Atualizando", "Instalando"... from Steam's state. */
    val stateText get() = when {
        paused -> "Pausado"
        state.contains("Download", true) -> if (install) "Baixando" else "Atualizando"
        state.contains("Stag", true) || state.contains("Commit", true) -> "Instalando"
        state.contains("Verif", true) || state.contains("Valid", true) -> "Verificando"
        state.contains("Prealloc", true) || state.contains("Reconfig", true) -> "Preparando"
        else -> if (install) "Baixando" else "Atualizando"
    }
}

data class QueuedDownload(val id: Int, val name: String, val percent: Int, val paused: Boolean)

/** Live downloads: [available] false when the PC can't read Steam live ("Ler direto da Steam" off). */
data class DownloadsState(val available: Boolean, val active: LiveDownload?, val queue: List<QueuedDownload>, val history: List<Long>) {
    val any get() = active != null || queue.isNotEmpty()
}

object DownloadsApi {
    fun fetch(pcIp: String, port: Int): DownloadsState {
        val c = URL("http://$pcIp:$port/downloads").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 6000
        val json = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        val o = org.json.JSONObject(json)
        val a = o.optJSONObject("active")
        val active = a?.let {
            LiveDownload(it.optInt("id"), it.optString("name"), it.optString("state"), it.optInt("percent"),
                it.optLong("speed"), it.optLong("peak"), it.optLong("disk"), it.optInt("eta"),
                it.optBoolean("paused"), it.optBoolean("install", true))
        }
        val q = o.optJSONArray("queue")
        val queue = (0 until (q?.length() ?: 0)).map {
            val x = q!!.getJSONObject(it)
            QueuedDownload(x.optInt("id"), x.optString("name"), x.optInt("percent"), x.optBoolean("paused"))
        }
        val h = o.optJSONArray("history")
        val history = (0 until (h?.length() ?: 0)).map { h!!.optLong(it) }
        return DownloadsState(o.optBoolean("available"), active, queue, history)
    }

    /** "69,5 MB/s" (Steam's unit: megabytes). */
    fun speedText(bytesPerSecond: Long): String {
        val mb = bytesPerSecond / 1_000_000.0
        return if (mb >= 100) "${mb.toInt()} MB/s"
        else String.format(java.util.Locale("pt", "BR"), "%.1f MB/s", mb)
    }
}

/**
 * The download progress bar: a glowing fill that eases to each new value, with a light sweeping
 * across it while the download runs (still and amber when paused).
 */
class DownloadBar(context: Context, private val radiusDp: Float = 4f) : View(context) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val start = SystemClock.uptimeMillis()

    var paused = false
        set(v) { field = v; invalidate() }

    /** Moves to [value] (0..1), easing from where it is. */
    fun setProgress(value: Float, animate: Boolean = true) {
        val to = value.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate || !isAttachedToWindow) {
            shown = to
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(shown, to).apply {
            duration = 900
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = radiusDp * density
        track.color = 0x26FFFFFF
        box.set(0f, 0f, w, h)
        canvas.drawRoundRect(box, r, r, track)
        val fw = max(h, w * shown)
        if (shown <= 0f) return
        val from = if (paused) 0xFFB98522.toInt() else Jinky.BLUE_DEEP
        val to = if (paused) Jinky.AMBER else 0xFF5CE1FF.toInt()
        fill.shader = LinearGradient(0f, 0f, fw, 0f, intArrayOf(from, if (paused) Jinky.AMBER else Jinky.BLUE, to), null, Shader.TileMode.CLAMP)
        box.set(0f, 0f, fw, h)
        canvas.drawRoundRect(box, r, r, fill)
        if (paused) return
        // A band of light running along the fill, then a glow at its leading edge.
        val t = ((SystemClock.uptimeMillis() - start) % 1800L) / 1800f
        val band = max(fw * 0.35f, 60 * density)
        val x = -band + (fw + band) * t
        shine.shader = LinearGradient(x, 0f, x + band, 0f, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipRect(0f, 0f, fw, h)
        canvas.drawRoundRect(box, r, r, shine)
        canvas.restore()
        val gr = h * 2.2f
        glow.shader = RadialGradient(fw - h / 2, h / 2, gr, intArrayOf(0x99A6F0FF.toInt(), 0x00A6F0FF), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(fw - h / 2, h / 2, gr, glow)
        postInvalidateOnAnimation()
    }
}

/**
 * The network speed over the last couple of minutes, like Steam's downloads page: a soft area
 * with a bright line, the newest second drawn in as it arrives, a pulsing dot at its end.
 */
class SpeedGraph(context: Context, private val showGrid: Boolean = true) : View(context) {
    private val density = resources.displayMetrics.density
    private var samples: List<Long> = emptyList()
    private var peak = 0L
    private var grow = 1f
    private var growAnim: ValueAnimator? = null
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF; strokeWidth = 1f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val start = SystemClock.uptimeMillis()
    var paused = false

    /** At most [MAX] samples (bytes per second, one a second); [peak] tops the scale. */
    fun setSamples(list: List<Long>, peak: Long) {
        val added = list.size == samples.size + 1 || (list.size == MAX && samples.size == MAX && list != samples)
        samples = list.takeLast(MAX)
        this.peak = peak
        growAnim?.cancel()
        if (added && isAttachedToWindow) {
            growAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 950
                addUpdateListener { grow = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            grow = 1f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (showGrid) for (i in 1..3) canvas.drawLine(0f, h * i / 4f, w, h * i / 4f, grid)
        if (samples.size < 2) return
        val top = max(peak, samples.max()).coerceAtLeast(1L).toFloat() * 1.12f
        val step = w / (MAX - 1)
        val n = samples.size
        val x0 = w - step * (n - 2 + grow)
        fun y(v: Long) = h - (v / top) * h * 0.92f - 1.5f * density
        path.reset()
        var px = x0
        var py = y(samples[0])
        path.moveTo(px, py)
        for (i in 1 until n) {
            val last = i == n - 1
            val nx = x0 + step * (i - 1 + if (last) grow else 1f)
            val target = y(samples[i])
            val ny = if (last) py + (target - py) * grow else target
            val mx = (px + nx) / 2
            path.cubicTo(mx, py, mx, ny, nx, ny)
            px = nx
            py = ny
        }
        val color = if (paused) Jinky.AMBER else Jinky.BLUE_SOFT
        val lineEnd = Path(path)
        path.lineTo(px, h)
        path.lineTo(x0, h)
        path.close()
        area.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf((color and 0xFFFFFF) or 0x66000000, (color and 0xFFFFFF)), null, Shader.TileMode.CLAMP)
        canvas.drawPath(path, area)
        line.color = color
        line.strokeWidth = 2f * density
        canvas.drawPath(lineEnd, line)
        // The newest point, breathing.
        val t = ((SystemClock.uptimeMillis() - start) % 1400L) / 1400f
        val halo = (3f + 6f * t) * density
        dot.color = (color and 0xFFFFFF) or ((0x70 * (1 - t)).toInt() shl 24)
        canvas.drawCircle(px, py, halo, dot)
        dot.color = color
        canvas.drawCircle(px, py, 3f * density, dot)
        if (!paused) postInvalidateOnAnimation()
    }

    companion object {
        const val MAX = 90
    }
}

/** A number that counts up (or down) to each new value: "28%" -> "29%". */
private class CountingText(private val view: TextView, private val suffix: String) {
    private var value = -1
    private var anim: ValueAnimator? = null
    fun set(v: Int) {
        if (v == value) return
        anim?.cancel()
        if (value < 0 || !view.isAttachedToWindow) {
            value = v
            view.text = "$v$suffix"
            return
        }
        val from = value
        value = v
        anim = ValueAnimator.ofInt(from, v).apply {
            duration = 900
            addUpdateListener { view.text = "${it.animatedValue}$suffix" }
            start()
        }
    }
}

private fun Context.label(sp: Float, color: Int, weight: Int, mono: Boolean = false) = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (!mono && weight >= 800 && Jinky.theme.pixelFont) sp * 0.74f else sp)
    setTextColor(color)
    typeface = if (mono) Jinky.mono(this@label, weight) else Jinky.font(this@label, weight)
}

/** "62% · 69,5 MB/s · faltam 3 min" for a live download. */
private fun statsLine(d: LiveDownload, queued: Int): String = listOfNotNull(
    if (d.paused) "pausado" else DownloadsApi.speedText(d.speed),
    if (d.paused) null else Jinky.eta(d.eta).ifEmpty { null },
    if (queued > 0) "+$queued na fila" else null,
).joinToString(" · ")

/**
 * The download on the home screen (bottom left): cover, name, the live bar and the speed graph.
 * Not focusable: the "Downloads" tile in the row opens the full page.
 */
class DownloadCard(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val coverBox = FrameLayout(context)
    private val kind = context.label(7.5f, Jinky.BLUE_SOFT, 700, mono = true).apply { letterSpacing = 0.2f }
    private val percent = context.label(15f, Jinky.TEXT, 700, mono = true)
    private val name = context.label(11.5f, Jinky.TEXT, 800).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val bar = DownloadBar(context, 3f)
    private val stats = context.label(8.5f, Jinky.SOFT, 500, mono = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val graph = SpeedGraph(context, showGrid = false)
    private val counter = CountingText(percent, "%")
    private var gameId = 0

    init {
        background = Jinky.rounded(0xE00A0D14.toInt(), dp(14).toFloat(), dp(1), 0x22FFFFFF)
        coverBox.background = Jinky.rounded(Jinky.BLUE_DEEP, dp(7).toFloat())
        coverBox.clipToOutline = true
        coverBox.addView(cover, LayoutParams(-1, -1))
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(kind, LinearLayout.LayoutParams(0, -2, 1f))
            addView(percent)
        }
        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(6) })
            addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(14), dp(9))
            addView(coverBox, LinearLayout.LayoutParams(dp(42), dp(60)))
            addView(middle, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(12), 0, dp(14), 0) })
            addView(graph, LinearLayout.LayoutParams(dp(118), dp(46)))
        }, LayoutParams(-1, -2))
        visibility = View.GONE
    }

    /** Shows [state]'s download (hidden when there is none). */
    fun show(state: DownloadsState?, pcIp: String, port: Int) {
        val d = state?.active
        if (d == null) {
            if (visibility == View.VISIBLE) animate().alpha(0f).setDuration(250).withEndAction { visibility = View.GONE }.start()
            return
        }
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = dp(12).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
        }
        // (Only once the PC's address is known: the first numbers can arrive before it.)
        if (d.id != gameId && pcIp.isNotEmpty()) {
            gameId = d.id
            cover.setImageDrawable(null)
            if (Jinky.showArt) ArtLoader.load(pcIp, port, d.id, "cover", 2) { bmp -> if (gameId == d.id && bmp != null) cover.setImageBitmap(bmp) }
        }
        kind.text = "${d.stateText.uppercase()} NO PC"
        kind.setTextColor(if (d.paused) Jinky.AMBER else Jinky.BLUE_SOFT)
        name.text = d.name
        counter.set(d.percent)
        bar.paused = d.paused
        bar.setProgress(d.percent / 100f)
        stats.text = statsLine(d, state.queue.size)
        graph.paused = d.paused
        graph.setSamples(state.history, d.peak)
    }
}

/**
 * "Downloads": the full page, like the Steam client's: the game's artwork, a big live bar and
 * percent, the speed graph with current and peak speed, the disk, the time left, and the queue.
 */
class DownloadsScreen(context: Context, private val onOpenGame: (Int) -> Unit) : TvPage(context) {
    private val hero = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val stateLine = mono(9f, Jinky.BLUE_SOFT, 700).apply { letterSpacing = 0.2f }
    private val title = text(26f, Jinky.TEXT, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val percent = mono(54f, Jinky.TEXT, 700)
    private val eta = text(13f, Jinky.SOFT)
    private val bar = DownloadBar(context, 6f)
    private val graph = SpeedGraph(context)
    private val speedNow = mono(17f, Jinky.TEXT, 700)
    private val speedPeak = mono(17f, Jinky.TEXT, 700)
    private val disk = mono(17f, Jinky.TEXT, 700)
    private val timeLeft = mono(17f, Jinky.TEXT, 700)
    private val queueTitle = mono(8.5f, Jinky.MUTED, 700).apply { letterSpacing = 0.2f; text = "NA FILA" }
    private val queueList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val empty = text(15f, Jinky.SOFT).apply { gravity = Gravity.CENTER }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val openButton = smallButton("Ver o jogo")
    private val counter = CountingText(percent, "%")
    private var gameId = 0
    private var pc: Pair<String, Int>? = null

    init {
        addView(hero, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Jinky.ink(0xF5), Jinky.ink(0xD0), Jinky.ink(0x70)))
        }, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Jinky.ink(0x00), Jinky.ink(0x00), Jinky.ink(0xF0)))
        }, LayoutParams(-1, -1))
        val root = pageRoot()
        root.addView(brandHeader("Downloads"))
        fun stat(label: String, value: TextView) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(mono(8f, Jinky.MUTED, 700).apply { letterSpacing = 0.2f; text = label })
            addView(value, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        }
        val stats = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stat("VELOCIDADE", speedNow), LinearLayout.LayoutParams(0, -2, 1f))
            addView(stat("PICO", speedPeak), LinearLayout.LayoutParams(0, -2, 1f))
            addView(stat("DISCO", disk), LinearLayout.LayoutParams(0, -2, 1f))
            addView(stat("TEMPO RESTANTE", timeLeft), LinearLayout.LayoutParams(0, -2, 1.3f))
        }
        val percentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(percent)
            addView(eta, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(14), 0, 0, dp(12)) })
        }
        val main = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(stateLine)
            addView(title, LinearLayout.LayoutParams(dp(560), -2).apply { topMargin = dp(4) })
            addView(percentRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(bar, LinearLayout.LayoutParams(-1, dp(12)))
            addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            addView(graph, LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(16) })
        }
        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(queueTitle)
            addView(queueList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(main, LinearLayout.LayoutParams(0, -2, 1f))
            addView(side, LinearLayout.LayoutParams(dp(210), -2).apply { marginStart = dp(36) })
        })
        body.addView(openButton, LinearLayout.LayoutParams(-2, dp(38)).apply { topMargin = dp(18) })
        openButton.id = View.generateViewId()
        openButton.setOnClickListener { if (gameId != 0) onOpenGame(gameId) }
        root.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
        root.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(root, LayoutParams(-1, -1))
    }

    override fun focusFirst() {
        if (openButton.visibility == View.VISIBLE) openButton.requestFocus()
    }

    fun show(state: DownloadsState?, pcIp: String, port: Int) {
        pc = pcIp to port
        val d = state?.active
        val nothing = d == null && state?.queue.isNullOrEmpty()
        empty.gone(!nothing && state?.available != false)
        empty.text = when {
            state == null -> "Procurando os downloads do PC…"
            !state.available -> "Para ver o download ao vivo, ligue \"Ler direto da Steam\" no app Jinky do PC."
            else -> "Nenhum download no momento."
        }
        body.gone(nothing || state?.available == false)
        if (nothing || state == null) {
            hero.animate().alpha(0f).setDuration(300).start()
            gameId = 0
            return
        }
        if (d != null) {
            if (d.id != gameId) {
                gameId = d.id
                hero.animate().alpha(0f).setDuration(200).start()
                if (Jinky.showArt) ArtLoader.load(pcIp, port, d.id, "hero", 1) { bmp ->
                    if (gameId == d.id && bmp != null) {
                        hero.setImageBitmap(bmp)
                        hero.animate().alpha(1f).setDuration(600).start()
                    }
                }
            }
            stateLine.text = "${d.stateText.uppercase()} NO PC"
            stateLine.setTextColor(if (d.paused) Jinky.AMBER else Jinky.BLUE_SOFT)
            title.text = d.name
            counter.set(d.percent)
            eta.text = if (d.paused) "pausado na Steam" else Jinky.eta(d.eta)
            bar.paused = d.paused
            bar.setProgress(d.percent / 100f)
            speedNow.text = if (d.paused) "—" else DownloadsApi.speedText(d.speed)
            speedPeak.text = DownloadsApi.speedText(d.peak)
            disk.text = if (d.paused) "—" else DownloadsApi.speedText(d.disk)
            timeLeft.text = if (d.paused || d.eta <= 0) "—" else Jinky.eta(d.eta).removePrefix("faltam ").removePrefix("falta ")
            graph.paused = d.paused
            graph.setSamples(state.history, d.peak)
        }
        queueTitle.gone(state.queue.isEmpty())
        queueList.removeAllViews()
        for (q in state.queue.take(5)) queueList.addView(queueRow(q, pcIp, port), LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(6) })
    }

    private fun queueRow(q: QueuedDownload, pcIp: String, port: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Jinky.rounded(Jinky.GLASS, dp(10).toFloat())
        setPadding(dp(6), dp(5), dp(10), dp(5))
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val box = FrameLayout(context).apply {
            background = Jinky.rounded(Jinky.BLUE_DEEP, dp(5).toFloat())
            clipToOutline = true
            addView(art, LayoutParams(-1, -1))
        }
        if (Jinky.showArt) ArtLoader.load(pcIp, port, q.id, "cover", 4) { if (it != null) art.setImageBitmap(it) }
        addView(box, LinearLayout.LayoutParams(dp(29), dp(42)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(10f, Jinky.TEXT, bold = true).apply { text = q.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(mono(8.5f, Jinky.MUTED).apply { text = if (q.paused) "pausado" else if (q.percent > 0) "${q.percent}% · na fila" else "na fila" })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
    }
}
