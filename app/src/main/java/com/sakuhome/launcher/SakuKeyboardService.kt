package com.sakuhome.launcher

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

class SakuKeyboardService : InputMethodService() {
    private var shifted = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
            setBackgroundColor(Color.rgb(23, 29, 42))
        }
        val rows = listOf("qwertyuiop", "asdfghjkl", "⇧zxcvbnm⌫")
        rows.forEachIndexed { rowIndex, row ->
            val line = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.HORIZONTAL
            }
            row.forEach { raw ->
                val label = if (raw == '⇧' || raw == '⌫') raw.toString() else if (shifted) raw.uppercaseChar().toString() else raw.toString()
                line.addView(key(label, if (raw == '⇧' || raw == '⌫') 1.35f else 1f) {
                    when (raw) {
                        '⇧' -> { shifted = !shifted; setInputView(onCreateInputView()) }
                        '⌫' -> currentInputConnection?.deleteSurroundingText(1, 0)
                        else -> {
                            currentInputConnection?.commitText(label, 1)
                            if (shifted) { shifted = false; setInputView(onCreateInputView()) }
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(45), if (rowIndex == 1) 1f else 1f).apply {
                    marginStart = dp(2); marginEnd = dp(2); bottomMargin = dp(4)
                })
            }
            root.addView(line)
        }
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        bottom.addView(key("?123", 1f) { currentInputConnection?.commitText("?123", 1) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(3) })
        bottom.addView(key(",", 0.7f) { currentInputConnection?.commitText(",", 1) }, LinearLayout.LayoutParams(0, dp(44), 0.7f).apply { marginEnd = dp(3) })
        bottom.addView(key("Spasi", 4f) { currentInputConnection?.commitText(" ", 1) }, LinearLayout.LayoutParams(0, dp(44), 4f).apply { marginEnd = dp(3) })
        bottom.addView(key(".", 0.7f) { currentInputConnection?.commitText(".", 1) }, LinearLayout.LayoutParams(0, dp(44), 0.7f).apply { marginEnd = dp(3) })
        bottom.addView(key("↵", 1f) {
            val ic: InputConnection? = currentInputConnection
            ic?.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                ?: ic?.commitText("\n", 1)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(bottom)
        return root
    }

    private fun key(label: String, weight: Float, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = if (label.length > 1) 12f else 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (label == "Spasi") Color.rgb(72, 88, 119) else Color.rgb(48, 59, 81))
            cornerRadius = dp(7).toFloat()
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
