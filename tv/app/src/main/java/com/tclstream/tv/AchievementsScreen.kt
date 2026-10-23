package com.tclstream.tv

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A game's achievements (design v2): a vertical list with the whole description, how rare each one
 * is and when it was unlocked. Hidden ones stay hidden until revealed one at a time (Y), so nothing
 * spoils the story by accident. X follows an achievement: it then shows in the menu over the game.
 */
class AchievementsScreen(
    context: Context,
    private val settings: TvSettings,
    private val onBack: () -> Unit,
) : TvPage(context) {

    private enum class Filter(val label: String) { ALL("Todas"), LOCKED("Bloqueadas"), UNLOCKED("Desbloqueadas"), RAREST("Mais raras") }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val hero = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val crumb = text(11.5f, Jinky.SOFT, bold = true)
    private val clock = mono(15f, Jinky.TEXT)
    private val ring = ProgressRing(context, 6f)
    private val ringText = mono(16f, Jinky.TEXT, 700)
    private val countText = text(15f, Jinky.TEXT, bold = true)
    private val countSub = text(9f, Jinky.MUTED)
    private val rarityRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val trackTitle = mono(7f, Jinky.MUTED, 700).apply { letterSpacing = 0.18f }
    private val trackRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val hiddenChip = chip("")
    private val message = text(12f, Jinky.MUTED)
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        clipChildren = false
        setPadding(dp(4), dp(6), dp(4), dp(40))
        addView(list, LayoutParams(-1, -2))
    }

    private var game: LibraryGame? = null
    private var pc: Pair<String, Int>? = null
    private var data: AchievementList? = null
    private var filter = Filter.ALL
    private var showHidden = false
    private val revealed = HashSet<String>()
    private var generation = 0
    private val rows = mutableListOf<Pair<View, Achievement>>()

    init {
        addView(hero, LayoutParams(-1, dp(180)))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x9907090D.toInt(), Jinky.INK))
        }, LayoutParams(-1, dp(180)))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Jinky.SOFT)
            }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
            addView(crumb)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clock)
        }
        addView(top, LayoutParams(-1, dp(36)).apply { setMargins(dp(48), dp(22), dp(48), 0) })

        // --- summary on the left ---
        val summary = card().apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply {
                    addView(ring, LayoutParams(-1, -1))
                    addView(ringText, LayoutParams(-2, -2, Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(70), dp(70)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(countText)
                    addView(countSub)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            })
            addView(rarityRows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val tracking = card().apply {
            addView(trackTitle)
            addView(text(9f, Jinky.SOFT).apply { text = "Aparecem no menu durante o jogo." },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(trackRows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(summary, LinearLayout.LayoutParams(-1, -2))
            addView(tracking, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })
        }, LayoutParams(dp(250), -2).apply { setMargins(dp(48), dp(75), 0, 0) })

        // --- the list on the right ---
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chips)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(hiddenChip, LinearLayout.LayoutParams(-2, dp(26)))
        }, LayoutParams(-1, dp(26)).apply { setMargins(dp(326), dp(75), dp(48), 0) })
        addView(message, LayoutParams(-2, -2).apply { setMargins(dp(330), dp(118), 0, 0) })
        addView(scroll, LayoutParams(-1, -1).apply { setMargins(dp(322), dp(108), dp(44), 0) })

        hiddenChip.setOnClickListener {
            showHidden = !showHidden
            rebuild(keepFocus = false)
            hiddenChip.requestFocus()
        }
    }

    /** Opens the achievements of [g] from the PC at [pcIp]. */
    fun show(g: LibraryGame, pcIp: String, port: Int) {
        val gen = ++generation
        game = g
        pc = pcIp to port
        data = null
        filter = Filter.ALL
        showHidden = false
        revealed.clear()
        crumb.text = "${g.name}   /   Conquistas"
        clock.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        hero.setImageDrawable(null)
        list.removeAllViews()
        rows.clear()
        chips.removeAllViews()
        rarityRows.removeAllViews()
        ring.progress = 0f
        ringText.text = ""
        countText.text = ""
        countSub.text = ""
        showMessage("Carregando conquistas…")
        refreshTracking()
        ArtLoader.load(pcIp, port, g.id, "hero", 2) { bmp ->
            if (game?.id == g.id && bmp != null) {
                hero.setImageBitmap(bmp)
                hero.alpha = 0f
                hero.animate().alpha(1f).setDuration(400).start()
            }
        }
        io.execute {
            val result = try { AchievementsApi.fetch(pcIp, port, g.id) } catch (e: Exception) {
                Log.w(TAG, "achievements: $e")
                null
            }
            main.post {
                if (gen != generation) return@post
                when {
                    result == null -> showMessage("Não foi possível falar com o PC.")
                    result.error != null -> showMessage("Conquistas indisponíveis: ${result.error}.")
                    result.items.isEmpty() -> showMessage("Este jogo não tem conquistas na Steam.")
                    else -> fill(result)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focusedRow = rows.firstOrNull { it.first.hasFocus() }
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_X -> { focusedRow?.let { toggleTrack(it.second) }; return true }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    val a = focusedRow?.second
                    if (a != null && a.hidden && !a.achieved && !showHidden && a.id !in revealed) {
                        revealed += a.id
                        rebuild(keepFocus = true)
                    } else hiddenChip.performClick()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun fill(d: AchievementList) {
        data = d
        showMessage(null)
        val fraction = if (d.total > 0) d.unlocked.toFloat() / d.total else 0f
        ring.animateTo(fraction)
        ringText.text = "${(fraction * 100).toInt()}%"
        countText.text = "${d.unlocked} de ${d.total}"
        countSub.text = "conquistas"
        rarityRows.removeAllViews()
        for (r in Rarity.values()) {
            val all = d.items.count { it.rarity == r }
            if (all == 0) continue
            val mine = d.items.count { it.rarity == r && it.achieved }
            rarityRows.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply { setBackgroundColor(r.color) }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
                addView(text(9.5f, Jinky.TEXT).apply { text = r.label }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(mono(9f, Jinky.SOFT).apply { text = "$mine / $all" })
            }, LinearLayout.LayoutParams(-1, -2).apply { if (rarityRows.childCount > 0) topMargin = dp(6) })
        }
        chips.removeAllViews()
        for (f in Filter.values()) {
            val n = when (f) {
                Filter.ALL, Filter.RAREST -> d.total
                Filter.LOCKED -> d.total - d.unlocked
                Filter.UNLOCKED -> d.unlocked
            }
            chips.addView(chip(if (f == Filter.RAREST) f.label else "${f.label}  $n").apply {
                tag = f
                setOnClickListener {
                    filter = f
                    rebuild(keepFocus = false)
                    requestFocus()
                }
            }, LinearLayout.LayoutParams(-2, dp(26)).apply { if (chips.childCount > 0) marginStart = dp(6) })
        }
        refreshTracking()
        rebuild(keepFocus = false)
        rows.firstOrNull()?.first?.requestFocusFromTouch()
    }

    private fun sorted(d: AchievementList): List<Achievement> = when (filter) {
        // Unlocked first (most recent), then the locked ones from the most common (easiest) on.
        Filter.ALL -> d.items.filter { it.achieved }.sortedByDescending { it.unlockTime } +
            d.items.filter { !it.achieved }.sortedByDescending { it.percent }
        Filter.LOCKED -> d.items.filter { !it.achieved }.sortedByDescending { it.percent }
        Filter.UNLOCKED -> d.items.filter { it.achieved }.sortedByDescending { it.unlockTime }
        Filter.RAREST -> d.items.sortedBy { if (it.percent < 0) 101.0 else it.percent }
    }

    private fun rebuild(keepFocus: Boolean) {
        val d = data ?: return
        val keep = if (keepFocus) rows.firstOrNull { it.first.hasFocus() }?.second?.id else null
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i) as Button
            val on = c.tag == filter
            c.background = chipBackground(on)
            c.setTextColor(if (on) focusColors(Jinky.INK, Jinky.INK) else focusColors(Jinky.INK, Jinky.TEXT))
        }
        hiddenChip.text = (if (Jinky.playStationPad()) "△" else "Y") + (if (showHidden) "   Esconder ocultas" else "   Mostrar ocultas")
        list.removeAllViews()
        rows.clear()
        for (a in sorted(d)) {
            val row = row(a)
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (list.childCount > 0) topMargin = dp(8) })
            rows += row to a
        }
        if (keep != null) rows.firstOrNull { it.second.id == keep }?.first?.requestFocus()
        refreshTracking()
    }

    private fun row(a: Achievement): View {
        val secret = a.hidden && !a.achieved && !showHidden && a.id !in revealed
        val tracked = game?.let { a.id in settings.tracked(it.id) } == true
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.RAISED, dp(10).toFloat(), dp(2), Jinky.TEXT))
                addState(intArrayOf(), Jinky.rounded(Jinky.SURFACE, dp(10).toFloat()))
            }
            // Icon: the game's own, in colour when unlocked, grey when not; a padlock for secrets.
            val icon = FrameLayout(context).apply {
                background = Jinky.rounded(if (a.achieved) a.rarity.ground else Jinky.RAISED, dp(8).toFloat())
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
            if (secret) {
                icon.addView(ImageView(context).apply { setImageResource(R.drawable.ic_lock); setColorFilter(Jinky.DIM) },
                    FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            } else {
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                icon.addView(image, FrameLayout.LayoutParams(-1, -1))
                val url = if (a.achieved) a.icon else a.iconGray.ifBlank { a.icon }
                if (url.isNotBlank()) ArtLoader.loadUrl(url, 1) { bmp ->
                    if (bmp == null) return@loadUrl
                    image.setImageBitmap(bmp)
                    if (!a.achieved) image.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                }
            }
            addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(11f, if (a.achieved || !secret) Jinky.TEXT else Jinky.SOFT, bold = true).apply {
                    text = if (secret) "Conquista oculta" else a.name
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(text(9f, if (a.achieved) Jinky.SOFT else Jinky.MUTED).apply {
                    text = if (secret) "Pode revelar parte da história. Aperte ${if (Jinky.playStationPad()) "△" else "Y"} para ver só esta."
                           else a.description.ifBlank { if (a.hidden) "Conquista secreta: a Steam não mostra a descrição." else "Sem descrição." }
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.15f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(11), 0, dp(10), 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(text(8f, a.rarity.soft, bold = true).apply {
                    text = a.rarity.label.uppercase() + if (a.percent >= 0) " · " + pct(a.percent) else ""
                }, LinearLayout.LayoutParams(-2, -2))
                when {
                    tracked -> addView(text(7f, Jinky.TEXT, bold = true).apply {
                        text = "ACOMPANHANDO"
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        background = Jinky.rounded(Jinky.LINE, dp(8).toFloat())
                    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                    a.achieved -> addView(mono(7.5f, Jinky.MUTED).apply { text = date(a.unlockTime) },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                    else -> addView(mono(7.5f, Jinky.DIM).apply { text = "bloqueada" },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                }
            })
            setOnClickListener { toggleTrack(a) }
            setOnFocusChangeListener { v, has -> if (has) v.post { scroll.smoothScrollTo(0, (v.top - dp(60)).coerceAtLeast(0)) } }
        }
    }

    private fun toggleTrack(a: Achievement) {
        val g = game ?: return
        if (a.achieved) return  // nothing left to follow
        val before = a.id in settings.tracked(g.id)
        val now = settings.toggleTrack(g.id, a.id)
        if (!before && !now) {
            showMessage("Você já acompanha ${TvSettings.MAX_TRACKED}. Tire uma para escolher outra.")
            main.postDelayed({ showMessage(null) }, 3000)
            return
        }
        refreshTracking()
        rebuild(keepFocus = true)
    }

    private fun refreshTracking() {
        val g = game ?: return
        val ids = settings.tracked(g.id)
        trackTitle.text = "ACOMPANHANDO   ${ids.size}/${TvSettings.MAX_TRACKED}"
        trackRows.removeAllViews()
        val items = data?.items.orEmpty()
        if (ids.isEmpty()) {
            trackRows.addView(text(9f, Jinky.MUTED).apply {
                text = "Aperte ${if (Jinky.playStationPad()) "□" else "X"} numa conquista bloqueada para acompanhar."
            })
            return
        }
        for (id in ids) {
            val a = items.firstOrNull { it.id == id } ?: continue
            trackRows.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply { setBackgroundColor(a.rarity.color) }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(7) })
                addView(text(10f, Jinky.TEXT, bold = true).apply {
                    text = if (a.hidden && !a.achieved && a.id !in revealed && !showHidden) "Conquista oculta" else a.name
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(mono(8.5f, a.rarity.soft).apply { text = if (a.achieved) "✓" else pct(a.percent) })
            }, LinearLayout.LayoutParams(-1, -2).apply { if (trackRows.childCount > 0) topMargin = dp(6) })
        }
    }

    private fun showMessage(text: String?) {
        message.text = text ?: ""
        message.gone(text == null)
    }

    private fun pct(p: Double) = if (p < 0) "" else
        (if (p < 10) String.format(java.util.Locale("pt", "BR"), "%.1f", p) else p.toInt().toString()) + "%"

    private fun date(unix: Long) = if (unix <= 0) "" else
        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale("pt", "BR")).format(java.util.Date(unix * 1000))

    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = Jinky.rounded(0xF010141F.toInt(), dp(13).toFloat())
    }

    private fun chipBackground(on: Boolean) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.TEXT, dp(13).toFloat(), dp(2), Jinky.BLUE))
        addState(intArrayOf(), if (on) Jinky.rounded(Jinky.TEXT, dp(13).toFloat()) else Jinky.rounded(Jinky.SURFACE, dp(13).toFloat()))
    }

    private fun chip(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Jinky.font(context, 700)
        setPadding(dp(10), 0, dp(10), 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextColor(focusColors(Jinky.INK, Jinky.TEXT))
        background = chipBackground(false)
        stateListAnimator = null
    }

    override fun focusFirst() {
        (rows.firstOrNull()?.first ?: hiddenChip).requestFocusFromTouch()
    }

    /** Back closes the page (handled here so it also works over a running game). */
    fun back() = onBack()

    companion object {
        private const val TAG = "Jinky"
    }
}
