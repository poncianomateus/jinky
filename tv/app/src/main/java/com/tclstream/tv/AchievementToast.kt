package com.tclstream.tv

import android.content.Context
import android.graphics.Outline
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Conquista desbloqueada" over the game: a card drops in at the top right with the achievement's
 * icon, name and rarity, stays a few seconds and leaves. Several unlocks queue up one after another.
 */
class AchievementToast(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val iconBox = FrameLayout(context)
    private val label = text(7f, Jinky.BLUE_SOFT, 700, mono = true).apply { letterSpacing = 0.2f }
    private val name = text(14f, Jinky.TEXT, 800).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val detail = text(8.5f, Jinky.SOFT, 500)
    private val count = text(10f, Jinky.AMBER, 700, mono = true)
    private val progress = View(context).apply { pivotX = 0f }
    private val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val queue = ArrayDeque<AchievementUnlock>()
    private var showing = false

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false
        iconBox.addView(icon, LayoutParams(-1, -1))
        iconBox.clipToOutline = true
        iconBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10))
            addView(iconBox, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(11), 0, dp(10), 0) })
            addView(count)
        }
        card.addView(body)
        card.addView(FrameLayout(context).apply {
            setBackgroundColor(Jinky.LINE)
            addView(progress, LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, dp(2) + dp(1) / 2))
        card.background = Jinky.rounded(0xF00A0D14.toInt(), dp(14).toFloat())
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
        card.elevation = dp(12).toFloat()
        card.visibility = View.INVISIBLE
        addView(card, LayoutParams(dp(320), -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(26), dp(32), 0) })
    }

    fun show(u: AchievementUnlock) {
        queue.addLast(u)
        if (!showing) next()
    }

    private fun next() {
        val u = queue.removeFirstOrNull() ?: run { showing = false; return }
        showing = true
        val r = u.rarity
        iconBox.background = Jinky.rounded(r.ground, dp(10).toFloat())
        icon.setImageDrawable(null)
        if (u.icon.isNotBlank()) ArtLoader.loadUrl(u.icon, 1) { bmp -> if (bmp != null) icon.setImageBitmap(bmp) }
        label.text = "CONQUISTA DESBLOQUEADA"
        label.setTextColor(r.soft)
        name.text = u.name
        detail.text = r.label + if (u.percent >= 0) " · " + AchievementsApi.percentText(u.percent) else ""
        count.text = if (u.total > 0) "${u.unlocked}/${u.total}" else ""
        progress.setBackgroundColor(r.color)
        progress.scaleX = if (u.total > 0) u.unlocked.toFloat() / u.total else 1f
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.translationY = -dp(40).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(OvershootInterpolator(1.2f)).withEndAction {
            card.animate().alpha(0f).translationY(-dp(20).toFloat()).setStartDelay(5200).setDuration(320)
                .setInterpolator(DecelerateInterpolator()).withEndAction {
                    card.visibility = View.INVISIBLE
                    card.animate().setStartDelay(0)
                    next()
                }.start()
        }.start()
    }

    private fun text(sp: Float, color: Int, weight: Int, mono: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = if (mono) Jinky.mono(context, weight) else Jinky.font(context, weight)
    }
}
