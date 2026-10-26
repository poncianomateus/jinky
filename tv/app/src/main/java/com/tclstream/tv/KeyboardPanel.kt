package com.tclstream.tv

import android.content.Context
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

/**
 * "Teclado" (game menu): write on the PC with the TV's own keyboard — a Steam login, a chat
 * message, a save's name. What is written here is typed on the PC when "Enviar" is chosen (or the
 * keyboard's send key); "Enter" and "Apagar" press those keys on the PC. "Ocultar" hides the text
 * on the TV (passwords). The game sees nothing of this; the controller drives the TV keyboard.
 */
class KeyboardPanel(
    context: Context,
    private val onType: (text: String, enter: Boolean) -> Unit,
    private val onBackspace: () -> Unit,
    private val onClose: () -> Unit,
) : TvPage(context) {
    private val field = EditText(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Jinky.TEXT)
        setHintTextColor(Jinky.MUTED)
        hint = "Escreva aqui…"
        typeface = Jinky.font(context, 600)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Jinky.rounded(Jinky.RAISED, dp(12).toFloat(), dp(2), Jinky.TEXT))
            addState(intArrayOf(), Jinky.rounded(Jinky.SURFACE, dp(12).toFloat(), dp(1), Jinky.LINE))
        }
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        inputType = InputType.TYPE_CLASS_TEXT
        setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEND || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                send(enter = false)
                true
            } else false
        }
    }
    private val sendButton = smallButton("Enviar")
    private val enterButton = smallButton("Enter")
    private val backButton = smallButton("⌫ Apagar")
    private val hideButton = smallButton("Ocultar")
    private val closeButton = smallButton("Fechar")
    private val note = text(9.5f, Jinky.SOFT)
    private var hidden = false

    init {
        setBackgroundColor(Jinky.ink(0xC8))
        isClickable = true
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = Jinky.rounded(Jinky.surface(0xF5), dp(16).toFloat(), dp(1), Jinky.LINE)
            addView(text(14f, Jinky.TEXT, bold = true).apply { text = "Teclado" })
            addView(text(9.5f, Jinky.MUTED).apply {
                text = "O que você escrever aqui é digitado no PC, na janela em que o cursor estiver."
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (b in listOf(sendButton, enterButton, backButton, hideButton, closeButton))
                    addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (childCount > 0) marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(note, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        // Near the top: the TV's keyboard takes the bottom half of the screen.
        addView(card, LayoutParams(dp(640), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36) })
        sendButton.setOnClickListener { send(enter = false) }
        enterButton.setOnClickListener {
            if (field.text.isNotEmpty()) send(enter = true) else { onType("", true); note.text = "Enter apertado no PC." }
        }
        backButton.setOnClickListener { onBackspace(); note.text = "Apagado um caractere no PC." }
        hideButton.setOnClickListener {
            hidden = !hidden
            field.inputType = InputType.TYPE_CLASS_TEXT or (if (hidden) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
            field.setSelection(field.text.length)
            hideButton.text = if (hidden) "Mostrar" else "Ocultar"
        }
        closeButton.setOnClickListener { onClose() }
        visibility = View.GONE
    }

    private fun send(enter: Boolean) {
        val text = field.text.toString()
        if (text.isEmpty() && !enter) return
        onType(text, enter)
        note.text = if (enter) "Digitado no PC, com Enter." else "Digitado no PC."
        field.setText("")
    }

    fun open() {
        visibility = View.VISIBLE
        note.text = "Enviar digita o texto; Enter confirma no PC."
        field.requestFocus()
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun close() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(field.windowToken, 0)
        field.setText("")
        hidden = false
        field.inputType = InputType.TYPE_CLASS_TEXT
        hideButton.text = "Ocultar"
        visibility = View.GONE
    }

    val isOpen get() = visibility == View.VISIBLE
}
