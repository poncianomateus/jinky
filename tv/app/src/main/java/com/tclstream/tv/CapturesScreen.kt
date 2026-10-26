package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** A screenshot taken from the TV, as the PC lists it (GET /captures). */
data class Capture(val name: String, val time: Long, val gameId: Int, val game: String)

/**
 * Capturas: the screenshots taken from the TV ("Capturar" in the game menu), newest first, in a
 * grid. OK opens one full screen; left/right go through them; "Apagar" (pressed twice) sends it to
 * the PC's Recycle Bin.
 */
class CapturesScreen(context: Context, private val onBack: () -> Unit) : TvPage(context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val clock = mono(15f, Jinky.TEXT)
    private val count = mono(9.5f, Jinky.SOFT)
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, dp(6), 0, dp(40))
        addView(grid)
    }
    private val message = text(12f, Jinky.MUTED).apply { setLineSpacing(0f, 1.3f) }

    // Full-screen viewer.
    private val viewerImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val viewerTitle = text(13f, Jinky.TEXT, bold = true)
    private val viewerSub = mono(9f, Jinky.SOFT)
    private val deleteButton = smallButton("Apagar")
    private val viewerNote = text(9.5f, Jinky.AMBER)
    private val viewer = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
    }

    private var pc: Pair<String, Int>? = null
    private var captures = emptyList<Capture>()
    private var shown = -1  // index open in the viewer
    private var deleteArmedUntil = 0L
    private var generation = 0

    init {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_back); setColorFilter(Jinky.SOFT) },
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
            addView(text(11.5f, Jinky.SOFT, bold = true).apply { text = "Capturas" })
            addView(count, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clock)
        }, LayoutParams(-1, dp(36)).apply { setMargins(dp(48), dp(22), dp(48), 0) })
        addView(scroll, LayoutParams(-1, -1).apply { setMargins(dp(48), dp(68), dp(48), 0) })
        addView(message, LayoutParams(dp(640), -2).apply { setMargins(dp(48), dp(90), 0, 0) })

        viewer.addView(viewerImage, LayoutParams(-1, -1))
        viewer.addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xCC000000.toInt(), 0x00000000))
        }, LayoutParams(-1, dp(120), Gravity.BOTTOM))
        viewer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(viewerTitle)
                addView(viewerSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                addView(viewerNote, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(deleteButton)
        }, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(48), 0, dp(48), dp(26)) })
        addView(viewer, LayoutParams(-1, -1))
        deleteButton.setOnClickListener { deletePressed() }
        // Left/right go through the pictures while one is open.
        deleteButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { open(shown - 1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { open(shown + 1); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true  // nothing else to reach
                else -> false
            }
        }
    }

    fun show(pcIp: String, port: Int) {
        pc = pcIp to port
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        closeViewer()
        grid.removeAllViews()
        count.text = ""
        message.text = "Carregando…"
        message.visibility = View.VISIBLE
        val gen = ++generation
        io.execute {
            val (list, error) = try { fetch(pcIp, port) to null } catch (e: Exception) {
                Log.w("Jinky", "captures: $e")
                null to "não foi possível falar com o PC"
            }
            main.post {
                if (gen != generation) return@post
                if (list == null) message.text = "Capturas indisponíveis: $error"
                else fill(list)
            }
        }
    }

    private fun fetch(ip: String, port: Int): List<Capture> {
        val c = URL("http://$ip:$port/captures").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 15000
        if (c.responseCode == 404) { c.disconnect(); throw IllegalStateException("atualize o Jinky do PC") }
        val o = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        c.disconnect()
        val a = o.optJSONArray("captures") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Capture(it.optString("name"), it.optLong("time"), it.optInt("gameId"), it.optString("game"))
        }
    }

    private fun url(c: Capture): String {
        val (ip, port) = pc ?: return ""
        return "http://$ip:$port/captures/file?name=" + URLEncoder.encode(c.name, "UTF-8")
    }

    private fun fill(list: List<Capture>) {
        captures = list
        grid.removeAllViews()
        count.text = when (list.size) { 0 -> ""; 1 -> "1 captura"; else -> "${list.size} capturas" }
        if (list.isEmpty()) {
            message.text = "Nenhuma captura ainda.\nDurante um jogo, abra o menu (Select + Start) e escolha Capturar. " +
                "As fotos ficam no PC, em Imagens › Jinky."
            message.visibility = View.VISIBLE
            return
        }
        message.visibility = View.GONE
        val columns = 4
        list.chunked(columns).forEachIndexed { r, rowItems ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { i, c -> row.addView(tile(r * columns + i, c), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(12) }) }
            // Keep the last row's tiles the same size as the others.
            repeat(columns - rowItems.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(12) }) }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = dp(14) })
        }
        post { if (viewer.visibility != View.VISIBLE) focusFirst() }  // (after a delete, the picture keeps the focus)
    }

    private fun tile(index: Int, c: Capture) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(dp(3), dp(3), dp(3), dp(6))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Color.TRANSPARENT, dp(10).toFloat(), dp(2), Jinky.TEXT))
            addState(intArrayOf(), Jinky.rounded(Color.TRANSPARENT, dp(10).toFloat()))
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Jinky.rounded(Jinky.RAISED, dp(8).toFloat())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
            }
        }
        ArtLoader.loadUrl(url(c), 4) { bmp -> if (bmp != null) image.setImageBitmap(bmp) }
        addView(image, LinearLayout.LayoutParams(-1, dp(118)))
        addView(text(9.5f, Jinky.TEXT, bold = true).apply {
            text = c.game.ifBlank { "Captura" }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(3), 0, dp(3), 0)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(mono(8f, Jinky.MUTED).apply { text = whenText(c.time); setPadding(dp(3), 0, 0, 0) })
        setOnClickListener { open(index) }
        setOnFocusChangeListener { v, has -> if (has) scrollIntoView(v) }
    }

    private fun open(index: Int) {
        if (captures.isEmpty()) return
        val i = index.coerceIn(0, captures.size - 1)
        shown = i
        val c = captures[i]
        viewer.visibility = View.VISIBLE
        viewerImage.setImageDrawable(null)
        viewerTitle.text = c.game.ifBlank { "Captura" }
        viewerSub.text = "${whenText(c.time)}  ·  ${i + 1} de ${captures.size}  ·  ← → para passar"
        viewerNote.text = ""
        deleteArmedUntil = 0L
        deleteButton.text = "Apagar"
        ArtLoader.loadUrl(url(c), 1) { bmp -> if (shown == i && bmp != null) viewerImage.setImageBitmap(bmp) }
        deleteButton.requestFocus()
    }

    private fun deletePressed() {
        val c = captures.getOrNull(shown) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now > deleteArmedUntil) {
            deleteArmedUntil = now + 4000
            deleteButton.text = "Apertar de novo"
            viewerNote.text = "Vai para a Lixeira do PC (dá para recuperar lá)."
            return
        }
        val (ip, port) = pc ?: return
        deleteArmedUntil = 0L
        io.execute {
            val ok = try {
                val conn = URL("http://$ip:$port/captures/delete?name=" + URLEncoder.encode(c.name, "UTF-8")).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 8000
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                body == "ok"
            } catch (e: Exception) { false }
            main.post {
                if (!ok) { viewerNote.text = "Não deu para apagar."; deleteButton.text = "Apagar"; return@post }
                val index = shown
                fill(captures.filterNot { it.name == c.name })
                if (captures.isEmpty()) closeViewer() else open(index.coerceAtMost(captures.size - 1))
            }
        }
    }

    /** Back: closes the picture first; true when it did. */
    fun closeViewer(): Boolean {
        if (viewer.visibility != View.VISIBLE) return false
        viewer.visibility = View.GONE
        val target = shown
        shown = -1
        focusTile(target)
        return true
    }

    private fun focusTile(index: Int) {
        if (index < 0) return
        val row = grid.getChildAt(index / 4) as? LinearLayout ?: return
        row.getChildAt(index % 4)?.requestFocus()
    }

    private fun whenText(unix: Long): String =
        java.text.SimpleDateFormat("d 'de' MMM 'de' yyyy, HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(unix * 1000))

    private fun scrollIntoView(v: View) {
        val top = (v.parent as View).top
        val margin = dp(20)
        when {
            top - margin < scroll.scrollY -> scroll.smoothScrollTo(0, (top - margin).coerceAtLeast(0))
            top + v.height + margin > scroll.scrollY + scroll.height - scroll.paddingBottom ->
                scroll.smoothScrollTo(0, top + v.height + margin - (scroll.height - scroll.paddingBottom))
        }
    }

    override fun focusFirst() = focusTile(0)

    fun close() = onBack()
}
