package com.kai.keyboard

import kotlin.math.ln
import kotlin.math.max

/** Раскладывает клавиши по экрану. Без Android — чтобы гонять в тестах-симуляторах. */
object KeyGeometry {
    /**
     * @param numH высота ряда цифр (0 — его нет). Клавиатура с ним выше на numH во всех режимах:
     * у букв это сам ряд цифр, у символов — прибавка к каждому из 4 рядов, у панелей — к панели.
     * @return ширина одной клавиши (pitch)
     */
    fun place(
        layout: KbLayout, x0: Float, kbW: Float, viewH: Float,
        stripH: Float, rowH: Float, padX: Float, gapX: Float, gapY: Float, numH: Float = 0f,
    ): Float {
        val rows = layout.rows
        val pitchX = (kbW - 2 * padX) / layout.units
        val keysBottom = stripH + numH + rowH * 4
        val firstRow = maxOf(0, 4 - rows.size) // эмодзи: только нижний ряд
        for ((ri, row) in rows.withIndex()) {
            if (row.isEmpty()) continue // формы без клавиш (ИИ) рядов не рисуют
            val (top, h) = when {
                rows.size == 5 -> if (ri == 0) stripH to numH else stripH + numH + rowH * (ri - 1) to rowH
                rows.size == 4 -> (stripH + (rowH + numH / 4) * ri) to rowH + numH / 4
                else -> stripH + numH + rowH * (firstRow + ri) to rowH
            }
            val gy = gapY * minOf(1f, h / rowH)
            val sum = row.sumOf { it.width.toDouble() }.toFloat()
            var x = x0 + padX + (layout.units - sum) / 2f * pitchX
            for (k in row) {
                val kw = k.width * pitchX
                k.vx = x + gapX / 2; k.vw = kw - gapX
                k.vy = top + gy / 2; k.vh = h - gy
                k.x = x; k.w = kw; k.y = top; k.h = h
                x += kw
            }
            // Смена языка отдаёт треть своей зоны касания соседу (запятой),
            // чтобы промах по запятой не переключал язык.
            val gi = row.indexOfFirst { it.code == Code.LANG }
            if (gi >= 0 && gi + 1 < row.size) {
                val g = row[gi]; val nb = row[gi + 1]
                val t = g.w * 0.35f
                g.w -= t; nb.x -= t; nb.w += t
            }
            // крайние клавиши ловят касания до самого края
            row.first().let { it.w += it.x - x0; it.x = x0 }
            row.last().let { it.w = x0 + kbW - it.x }
            if (ri == 0 && firstRow == 0) for (k in row) { k.h += k.y - stripH; k.y = stripH }
            if (ri == rows.size - 1) for (k in row) k.h = max(keysBottom, viewH) - k.y
        }
        return pitchX
    }
}

/**
 * Сердце клавиатуры. Видимые клавиши не меняются, но зона касания каждой буквы
 * растёт или сжимается в зависимости от того, насколько она вероятна следующей.
 * score = log P(касание | клавиша) + λ·conf·log P(клавиша | набранное).
 */
object TouchModel {
    // Параметры подбираются симулятором TypingSimTest.
    // Насколько далеко буква может «залезть» на соседнюю (в долях клавиши).
    var MAX_EXPAND_X = 0.7f
    var MAX_EXPAND_Y = 0.5f
    var CORE = 0.11f
    var SIGMA_X = 0.32f
    var SIGMA_Y = 0.30f
    // Вес языковой модели. Больше — хуже: модель начинает спорить с пальцем.
    var LAMBDA = 1.0f

    /** log P(касание | клавиша) по отклонению от центра (в долях клавиши), без константы. */
    fun fit(dx: Float, dy: Float): Float = -(dx * dx / (2 * SIGMA_X * SIGMA_X) + dy * dy / (2 * SIGMA_Y * SIGMA_Y))

    fun plainHit(layout: KbLayout, x: Float, y: Float, top: Float): Key? {
        if (y < top) return null
        for (k in layout.keys) if (k.contains(x, y)) return k
        return layout.keys.minByOrNull { (it.cx - x) * (it.cx - x) + (it.cy - y) * (it.cy - y) }
    }

    fun resolve(
        layout: KbLayout, lang: Lang, x: Float, y: Float, top: Float,
        probs: FloatArray?, conf: Float, pitchX: Float, rowH: Float, gapX: Float,
        offset: (Char) -> FloatArray?,
    ): Key? {
        val hit = plainHit(layout, x, y, top) ?: return null
        // Чем меньше модель уверена (новое слово), тем меньше растут зоны.
        if (hit.kind != KeyKind.LETTER || probs == null || conf < 0.05f) return hit
        // Попал точно в центр — значит, хотел именно эту букву.
        val hx = (x - hit.cx) / pitchX
        val hy = (y - hit.cy) / rowH
        if (hx * hx + hy * hy < CORE * CORE) return hit
        val ex = pitchX * MAX_EXPAND_X * conf
        val ey = rowH * MAX_EXPAND_Y * conf
        val lambda = LAMBDA * conf
        var best = hit
        var bestScore = Float.NEGATIVE_INFINITY
        for (k in layout.letterKeys) {
            if (x < k.vx - gapX / 2 - ex || x > k.vx + k.vw + gapX / 2 + ex) continue
            if (y < k.y - ey || y > k.y + k.h + ey) continue
            val idx = lang.index(k.ch)
            if (idx < 0) continue
            val off = offset(k.ch)
            val dx = (x - k.cx - (off?.get(0) ?: 0f) * pitchX) / pitchX
            val dy = (y - k.cy - (off?.get(1) ?: 0f) * rowH) / rowH
            val ll = fit(dx, dy)
            val s = ll + lambda * ln(max(probs[idx], 1e-6f))
            if (s > bestScore) { bestScore = s; best = k }
        }
        return best
    }
}

/**
 * Насколько можно доверять модели в текущем слове. Клавиатура не должна спорить с пальцем:
 * - после ⌫ следующее нажатие — строго по геометрии (владелец только что поправил);
 * - если в слове уже была правка или неожиданная для модели буква — это, скорее всего,
 *   новое слово, и до его конца зоны почти не растут.
 */
class ZoneState {
    private var retry = false
    private var damp = 1f

    /** Копия состояния — каким оно было до буквы (для пересчёта пары в Reorder). */
    fun copy() = ZoneState().also { it.retry = retry; it.damp = damp }

    fun onLetter(prob: Float) {
        retry = false
        if (prob < SURPRISE) damp = minOf(damp, SURPRISE_DAMP)
    }

    fun onBackspace() {
        retry = true
        damp = minOf(damp, FIX_DAMP)
    }

    fun onWordBoundary() {
        retry = false
        damp = 1f
    }

    fun apply(conf: Float): Float = if (retry) 0f else conf * damp

    companion object {
        var SURPRISE = 0.02f
        var SURPRISE_DAMP = 0.3f
        var FIX_DAMP = 0.4f
    }
}
