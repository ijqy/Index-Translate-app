package com.example.aitranslate

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/** 悬浮球「输入」：底部一条输入框，回车即译 */
class InputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(90, 0, 0, 0)); setOnClickListener { finish() } }
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.round(ui.surface, ui.dp(24f).toFloat(), topOnly = true)
            setPadding(ui.dp(16f), ui.dp(14f), ui.dp(12f), ui.dp(14f)); isClickable = true
        }
        val et = EditText(this).apply {
            hint = "输入单词或句子"; maxLines = 5; textSize = 16f
            background = ui.round(ui.surfaceVar, ui.dp(16f).toFloat())
            setPadding(ui.dp(14f), ui.dp(10f), ui.dp(14f), ui.dp(10f))
            setTextColor(ui.onSurface); setHintTextColor(ui.onSurfaceVar)
            imeOptions = EditorInfo.IME_ACTION_GO
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        }
        fun go() {
            val t = et.text.toString().trim(); if (t.isEmpty()) return
            startActivity(Intent(this, TranslateActivity::class.java).setAction(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, t)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
            finish()
        }
        et.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(); true } else false }
        card.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(ui.pill("翻译", true, weight = false) { go() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(10f) })
        root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        et.requestFocus()
    }
}
