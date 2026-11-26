package com.kai.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.kai.keyboard.data.ai.TextDiff

/**
 * Окна ИИ поверх клавиатуры: «работаю…» и предпросмотр результата. Само меню действий — не окно,
 * а форма в самой клавиатуре (раздел «ИИ» в панели над клавишами).
 * Все окна не берут фокус ввода — клавиатура и поле приложения работают как обычно.
 * Цвета — из темы клавиатуры и акцента (подходят и динамической теме «Системная»).
 */
class AiUi(private val ctx: Context, private val anchor: () -> View?) {
    private var progress: PopupWindow? = null
    private var preview: PopupWindow? = null

    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun box(t: Theme, r: Float = 10f) = GradientDrawable().apply {
        setColor(t.bubble); cornerRadius = dp(r).toFloat(); setStroke(dp(1f), t.pressed)
    }

    private fun pop(content: View, w: Int, h: Int, focusable: Boolean = false) = PopupWindow(content, w, h, focusable).apply {
        isTouchable = true
        isOutsideTouchable = false
        isClippingEnabled = false
        setBackgroundDrawable(null)
        animationStyle = 0
    }

    fun dismissAll() { dismissProgress(); dismissPreview() }

    // ---------- работаю ----------

    fun showProgress(t: Theme, accent: Int, onCancel: () -> Unit) {
        val a = anchor() ?: return
        dismissProgress()
        val tv = TextView(ctx).apply {
            setText(R.string.ai_working); textSize = 14f; setTextColor(t.text)
            gravity = Gravity.CENTER
            background = box(t, 8f).apply { setStroke(dp(1.5f), accent) }
            setOnClickListener { onCancel() }
        }
        val loc = IntArray(2); a.getLocationInWindow(loc)
        val p = pop(tv, a.width - dp(12f), dp(38f))
        p.showAtLocation(a, Gravity.NO_GRAVITY, loc[0] + dp(6f), loc[1] + dp(4f))
        progress = p
    }

    fun dismissProgress() { progress?.dismiss(); progress = null }

    // ---------- предпросмотр ----------

    fun showPreview(
        t: Theme, accent: Int, original: String, result: String, diff: Boolean,
        onReplace: () -> Unit, onInsert: () -> Unit, onCopy: () -> Unit, onCancel: () -> Unit,
    ) {
        val a = anchor() ?: return
        dismissPreview()
        val tint = Color.argb(0x66, Color.red(accent), Color.green(accent), Color.blue(accent))
        val s = SpannableString(result)
        val spans = if (diff) TextDiff.changed(original, result) else listOf(TextDiff.Span(0, result.length))
        for (sp in spans) if (sp.end > sp.start)
            s.setSpan(BackgroundColorSpan(tint), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val body = TextView(ctx).apply {
            text = s; textSize = 16f; setTextColor(t.text)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(ctx).apply { addView(body, MATCH_PARENT, WRAP_CONTENT) }
        fun button(res: Int, primary: Boolean, click: () -> Unit) = TextView(ctx).apply {
            setText(res); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            // Четыре кнопки в ряд на узком экране: подпись в две строки, а не обрезанная.
            setSingleLine(false); maxLines = 2
            setTextColor(if (primary) Accent.onAccent(accent) else t.text)
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat(); setColor(if (primary) accent else t.key)
            }
            setOnClickListener { click() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
            fun add(v: View) = addView(v, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(4f); marginEnd = dp(4f) })
            add(button(R.string.ai_cancel, false, onCancel))
            add(button(R.string.ai_copy, false, onCopy))
            add(button(R.string.ai_insert, true, onInsert))
            add(button(R.string.ai_replace, true, onReplace))
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(t.bg) }
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val loc = IntArray(2); a.getLocationInWindow(loc)
        val p = pop(col, a.width, a.height)
        p.showAtLocation(a, Gravity.NO_GRAVITY, loc[0], loc[1])
        preview = p
    }

    fun dismissPreview() { preview?.dismiss(); preview = null }
}
