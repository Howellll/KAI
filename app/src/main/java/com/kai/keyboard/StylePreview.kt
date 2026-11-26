package com.kai.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Мини-превью стиля для настроек: первый ряд символов и нижний ряд букв,
 * нарисованные из тех же раскладок, что и сама клавиатура, — чтобы стиль узнавали глазами.
 */
@SuppressLint("ViewConstructor")
class StylePreview(ctx: Context, alt: Boolean, style3: Boolean = false) : View(ctx) {
    private val theme = Theme.of(Prefs(ctx).theme)
    private val letters = Layouts.letters(Langs.RU, alt, globe = true, symbols = style3, style3 = style3)
    private val rows = listOfNotNull(
        // Стиль 3: верхний ряд букв — с символами долгого нажатия в углу клавиши.
        if (style3) letters.rows[0] else null,
        Layouts.symbols(Langs.RU, false, alt).rows[1],
        letters.rows[3],
    )
    private val units = 11f
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.text; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (rows.size * 34 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val pitch = width / units
        val rowH = height / rows.size.toFloat()
        val gap = 3 * d
        for ((r, row) in rows.withIndex()) {
            val sum = row.sumOf { it.width.toDouble() }.toFloat()
            var x = (units - sum) / 2f * pitch
            for (k in row) {
                val w = k.width * pitch
                rect.set(x + gap / 2, r * rowH + gap, x + w - gap / 2, (r + 1) * rowH - gap)
                val key = k.kind == KeyKind.CHAR || k.kind == KeyKind.SPACE
                if (theme.shadow != 0) {
                    fill.color = theme.shadow
                    c.drawRoundRect(rect.left, rect.top + d, rect.right, rect.bottom + d, 4 * d, 4 * d, fill)
                }
                fill.color = if (key) theme.key else theme.fn
                c.drawRoundRect(rect, 4 * d, 4 * d, fill)
                val label = when (k.code) {
                    Code.LANG -> "🌐"
                    Code.ENTER -> "⏎"
                    Code.EMOJI -> "☺"
                    Code.SPACE -> ""
                    else -> k.label
                }
                text.textSize = (if (label.length > 1) 10 else 13) * d
                c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
                if (k.kind == KeyKind.LETTER && k.popup.isNotEmpty()) {
                    val savedColor = text.color
                    val savedAlign = text.textAlign
                    text.color = theme.textDim
                    text.textAlign = Paint.Align.RIGHT
                    text.textSize = 8 * d
                    c.drawText(k.popup[0], rect.right - 2 * d, rect.top + 9 * d, text)
                    text.color = savedColor
                    text.textAlign = savedAlign
                }
                x += w
            }
        }
    }
}
