package com.kai.keyboard

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Свайп: выравнивание букв слова по точкам жеста — второй, «вероятностный» взгляд на жест рядом с DTW.
 *
 * Модель точек — по открытой части Android LatinIME (ProximityInfoStateUtils::updateAlignPointProbabilities,
 * ProximityInfoParams; Apache 2.0, © The Android Open Source Project), переписана под Klava: для каждой точки
 * жеста — вероятность, что палец её «проскочил» (быстро и по прямой — да; медленно, на повороте, у самой
 * клавиши — нет), и вероятности ближних клавиш (нормальное распределение, тем шире, чем быстрее и круче шёл
 * палец). Выравнивание — своё: каждой букве слова по порядку — своя точка, остальные точки проскочены;
 * цена слова — сумма −log вероятностей, лучший вариант ищется динамическим программированием.
 * Без Android — проверяется тестами.
 */
object SwipeAlign {
    var STEP = 0.25f

    // ---- параметры модели (из AOSP ProximityInfoParams) ----
    private const val MAX_SKIP = 0.95f
    private const val SKIP_FIRST = 0.01f
    private const val SKIP_LAST = 0.1f
    private const val MIN_SPEED_RATE_FOR_SKIP = 0.15f
    private const val SPEED_WEIGHT_FOR_SKIP = 0.9f
    private const val SLOW_STRAIGHT_WEIGHT_FOR_SKIP = 0.6f
    private const val NEAREST_W = 0.5f
    private const val NEAREST_B = 0.5f
    private const val NEAREST_W_LAST = 0.6f
    private const val NEAREST_B_LAST = 0.4f
    private const val ANGLE_W = 0.9f
    private val DEEP_CORNER = (PI * 60 / 180).toFloat()
    private const val SKIP_DEEP_CORNER = 0.1f
    private val CORNER = (PI * 30 / 180).toFloat()
    private val STRAIGHT = (PI * 15 / 180).toFloat()
    private const val SKIP_CORNER = 0.4f
    private const val SPEED_MARGIN = 0.1f
    private const val SPEEDxANGLE_W = 0.3f
    private const val MAX_SPEEDxANGLE = 0.25f
    private const val SPEEDxNEAREST_W = 0.5f
    private const val MAX_SPEEDxNEAREST = 0.15f
    private const val MIN_SD = 0.37f
    private const val MIN_PROB = 0.000005f
    private const val SUPPRESSION_LEN = 1.5f
    private const val MIN_SUPPRESSION = 0.1f
    private const val SUPPRESSION_W = 0.5f
    private const val GAIN_W = 0.1f
    private const val SKIP_GAIN_W = 0.3f

    class Points(val n: Int, val skip: FloatArray, val keyCost: Array<FloatArray>, val keyOf: Map<Char, Int>)

    /**
     * @param pts путь в единицах клавиш (x — ширины клавиши, y — ряды)
     * @param aspect высота ряда / ширина клавиши: расстояния считаем в ширинах клавиши по обеим осям
     */
    fun prepare(pts: FloatArray, times: FloatArray?, centers: Map<Char, FloatArray>, aspect: Float): Points? {
        val m = pts.size / 2
        if (m < 2) return null
        // клавиши: у ъ и ь (ё и е) один центр — одна клавиша
        val keyX = ArrayList<Float>(); val keyY = ArrayList<Float>()
        val keyOf = HashMap<Char, Int>()
        for ((c, p) in centers) {
            val x = p[0]; val y = p[1] * aspect
            var id = (0 until keyX.size).firstOrNull { abs(keyX[it] - x) < 1e-3f && abs(keyY[it] - y) < 1e-3f }
            if (id == null) { keyX.add(x); keyY.add(y); id = keyX.size - 1 }
            keyOf[c] = id
        }
        val kc = keyX.size

        val rx = FloatArray(m) { pts[2 * it] }; val ry = FloatArray(m) { pts[2 * it + 1] * aspect }
        val cum = FloatArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + hypot(rx[i] - rx[i - 1], ry[i] - ry[i - 1])
        val total = cum[m - 1]
        val n = maxOf(2, (total / STEP).toInt() + 1)
        val xs = FloatArray(n); val ys = FloatArray(n); val ts = FloatArray(n)
        val hasTime = times != null && times.size == m && times[m - 1] > times[0]
        var j = 0
        for (s in 0 until n) {
            val at = if (n == 1) 0f else total * s / (n - 1)
            while (j < m - 2 && cum[j + 1] < at) j++
            val seg = cum[j + 1] - cum[j]
            val f = if (seg > 1e-6f) ((at - cum[j]) / seg).coerceIn(0f, 1f) else 0f
            xs[s] = rx[j] + (rx[j + 1] - rx[j]) * f; ys[s] = ry[j] + (ry[j + 1] - ry[j]) * f
            ts[s] = if (hasTime) times!![j] + (times[j + 1] - times[j]) * f else s.toFloat()
        }

        val avg = if (hasTime && ts[n - 1] > ts[0]) total / (ts[n - 1] - ts[0]) else 0f
        val speed = FloatArray(n) { i ->
            if (avg <= 0f) 1f else {
                val a = maxOf(0, i - 2); val b = minOf(n - 1, i + 2)
                val dt = ts[b] - ts[a]
                if (dt <= 0f) 1f else ((b - a) * STEP / dt) / avg
            }
        }
        fun dir(a: Int, b: Int) = atan2(ys[b] - ys[a], xs[b] - xs[a])
        fun diff(a: Float, b: Float): Float { var d = abs(a - b); if (d > PI) d = (2 * PI).toFloat() - d; return d }
        // угол поворота — на масштабе полклавиши (у соседних точек на шаге ¼ клавиши — шум)
        val angle = FloatArray(n) { i ->
            if (i < 2 || i > n - 3) 0f else diff(dir(i - 2, i), dir(i, i + 2))
        }

        val skip = FloatArray(n)
        val prob = Array(n) { FloatArray(kc) }
        for (i in 0 until n) {
            var nearest = Float.MAX_VALUE
            for (k in 0 until kc) {
                val dx = xs[i] - keyX[k]; val dy = ys[i] - keyY[k]
                nearest = min(nearest, dx * dx + dy * dy) // нормированный квадрат расстояния (ширина клавиши = 1)
            }
            var sp = MAX_SKIP
            val v = speed[i]; val a = angle[i]
            when (i) {
                0 -> { sp *= min(1f, nearest * NEAREST_W + NEAREST_B); sp *= SKIP_FIRST }
                n - 1 -> { sp *= min(1f, nearest * NEAREST_W_LAST + NEAREST_B_LAST); sp *= SKIP_LAST }
                else -> {
                    if (speed[i - 1] - SPEED_MARGIN > v && v < speed[i + 1] - SPEED_MARGIN) {
                        sp *= if (a < CORNER) min(1f, v * SLOW_STRAIGHT_WEIGHT_FOR_SKIP)
                        else min(1f, v * SPEED_WEIGHT_FOR_SKIP + MIN_SPEED_RATE_FOR_SKIP)
                    }
                    sp *= min(1f, v * nearest * NEAREST_W + NEAREST_B)
                    sp *= ((PI.toFloat() - a) / PI.toFloat()) * ANGLE_W + (1 - ANGLE_W)
                    if (a > DEEP_CORNER) sp *= SKIP_DEEP_CORNER
                    if (i >= 3) {
                        val prev = diff(dir(i - 3, i - 2), dir(i - 2, i))
                        if (prev < STRAIGHT && a > CORNER) sp *= SKIP_CORNER
                    }
                }
            }
            skip[i] = sp
            val rest = 1f - sp
            val sigma = min(v * a / PI.toFloat() * SPEEDxANGLE_W, MAX_SPEEDxANGLE) +
                min(v * nearest * SPEEDxNEAREST_W, MAX_SPEEDxNEAREST) + MIN_SD
            val (wx, wy) = when (i) { 0 -> 1.25f to 0.85f; n - 1 -> 1.4f to 0.95f; else -> 1.1f to 0.95f }
            val sx = sigma * wx; val sy = sigma * wy
            val th = if (i == 0) dir(0, minOf(1, n - 1)) else dir(i - 1, i)
            val ct = cos(th); val st = sin(th)
            var sum = 0f
            val dens = FloatArray(kc)
            for (k in 0 until kc) {
                val dx = keyX[k] - xs[i]; val dy = keyY[k] - ys[i]
                val u = dx * ct + dy * st; val w = -dx * st + dy * ct
                dens[k] = exp(-(u * u / (2 * sx * sx) + w * w / (2 * sy * sy)))
                sum += dens[k]
            }
            if (sum > 0f) for (k in 0 until kc) prob[i][k] = rest * dens[k] / sum
        }

        for (i in 1 until n) {
            var q = i + 1
            while (q < n && suppress(i, q, prob, skip)) q++
            q = i - 1
            while (q >= 0 && suppress(i, q, prob, skip)) q--
        }

        val keyCost = Array(n) { i -> FloatArray(kc) { k -> -ln(maxOf(prob[i][k], MIN_PROB)) } }
        val skipCost = FloatArray(n) { -ln(maxOf(skip[it], MIN_PROB)) }
        return Points(n, skipCost, keyCost, keyOf)
    }

    /** Петельки в жесте: на каких клавишах и жест без них (петля заменена точкой в её центре). */
    class Loops(val keys: Set<Char>, val pts: FloatArray, val times: FloatArray?)

    /**
     * Петельки (палец обвёл клавишу — так часто показывают двойную букву): путь пересекает сам себя на
     * коротком отрезке. Петлю из жеста вырезаем — иначе она удлиняет путь и сбивает сравнение формы.
     * @return null — петелек нет
     */
    fun loops(pts: FloatArray, times: FloatArray?, centers: Map<Char, FloatArray>, aspect: Float): Loops? {
        val m = pts.size / 2
        if (m < 6) return null
        val rx = FloatArray(m) { pts[2 * it] }; val ry = FloatArray(m) { pts[2 * it + 1] * aspect }
        val cum = FloatArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + hypot(rx[i] - rx[i - 1], ry[i] - ry[i - 1])
        val step = 0.12f
        val n = (cum[m - 1] / step).toInt() + 1
        if (n < 6) return null
        val hasTime = times != null && times.size == m
        val xs = FloatArray(n); val ys = FloatArray(n); val ts = FloatArray(n)
        var j = 0
        for (s in 0 until n) {
            val at = cum[m - 1] * s / (n - 1)
            while (j < m - 2 && cum[j + 1] < at) j++
            val seg = cum[j + 1] - cum[j]
            val f = if (seg > 1e-6f) ((at - cum[j]) / seg).coerceIn(0f, 1f) else 0f
            xs[s] = rx[j] + (rx[j + 1] - rx[j]) * f; ys[s] = ry[j] + (ry[j + 1] - ry[j]) * f
            ts[s] = if (hasTime) times!![j] + (times[j + 1] - times[j]) * f else 0f
        }
        val keys = HashSet<Char>()
        val keep = BooleanArray(n) { true }
        val cxs = FloatArray(n); val cys = FloatArray(n); val center = BooleanArray(n)
        var i = 0
        while (i < n - 1) {
            var found = -1
            for (k in i + 5..minOf(n - 2, i + 20)) {
                if (cross(xs[i], ys[i], xs[i + 1], ys[i + 1], xs[k], ys[k], xs[k + 1], ys[k + 1])) { found = k; break }
            }
            if (found < 0) { i++; continue }
            var cx = 0f; var cy = 0f
            for (q in i + 1..found) { cx += xs[q]; cy += ys[q]; keep[q] = false }
            cx /= (found - i); cy /= (found - i)
            val mid = (i + 1 + found) / 2
            keep[mid] = true; center[mid] = true; cxs[mid] = cx; cys[mid] = cy
            var best: Char? = null; var bd = Float.MAX_VALUE
            for ((c, p) in centers) {
                val d = hypot(p[0] - cx, p[1] * aspect - cy)
                if (d < bd) { bd = d; best = c }
            }
            if (best != null && bd < 0.8f) {
                val bp = centers[best]!!
                for ((c, p) in centers) if (abs(p[0] - bp[0]) < 1e-3f && abs(p[1] - bp[1]) < 1e-3f) keys.add(c)
            }
            i = found + 1
        }
        if (keys.isEmpty() && keep.all { it }) return null
        val idx = (0 until n).filter { keep[it] }
        val out = FloatArray(idx.size * 2)
        val ot = if (hasTime) FloatArray(idx.size) else null
        for ((q, s) in idx.withIndex()) {
            out[2 * q] = if (center[s]) cxs[s] else xs[s]
            out[2 * q + 1] = (if (center[s]) cys[s] else ys[s]) / aspect
            ot?.set(q, ts[s])
        }
        return Loops(keys, out, ot)
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        fun o(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = o(cx, cy, dx, dy, ax, ay); val d2 = o(cx, cy, dx, dy, bx, by)
        val d3 = o(ax, ay, bx, by, cx, cy); val d4 = o(ax, ay, bx, by, dx, dy)
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)
    }

    private fun suppress(i0: Int, i1: Int, prob: Array<FloatArray>, skip: FloatArray): Boolean {
        val d = abs(i0 - i1) * STEP
        if (d > SUPPRESSION_LEN) return false
        val rate = MIN_SUPPRESSION + d / SUPPRESSION_LEN * SUPPRESSION_W
        val p0 = prob[i0]; val p1 = prob[i1]
        for (k in p0.indices) {
            if (p0[k] > 0f && p0[k] < p1[k]) {
                val np = p0[k] * rate
                val sup = p0[k] - np
                p0[k] = np; skip[i0] += sup
                val gain = min(sup * GAIN_W, skip[i1] * SKIP_GAIN_W)
                p1[k] += gain; skip[i1] -= gain
            }
        }
        return true
    }

    /**
     * Цена слова (−log вероятности): каждой букве по порядку — своя точка, остальные точки проскочены.
     * Двойные буквы (и ъ/ь подряд) — одна клавиша. Слово с буквой не с этой раскладки — Float.MAX_VALUE.
     */
    fun cost(p: Points, word: String): Float {
        val ids = ArrayList<Int>(word.length)
        for (c in word) {
            if (c == '-' || c == '\'') continue
            val k = p.keyOf[c] ?: return Float.MAX_VALUE
            if (ids.isEmpty() || ids.last() != k) ids.add(k)
        }
        val n = p.n
        // Слово из одних дефисов/апострофов: букв в нём нет — цены у такого слова не бывает.
        if (ids.isEmpty()) return Float.MAX_VALUE
        if (ids.size > n) return Float.MAX_VALUE
        val pre = FloatArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + p.skip[i]
        var cur = FloatArray(n) { i -> pre[i] + p.keyCost[i][ids[0]] }
        for (jj in 1 until ids.size) {
            val k = ids[jj]
            val next = FloatArray(n) { Float.MAX_VALUE }
            var best = Float.MAX_VALUE // min по t < i: cur[t] − pre[t + 1]
            for (i in 0 until n) {
                if (best < Float.MAX_VALUE) next[i] = p.keyCost[i][k] + pre[i] + best
                if (cur[i] < Float.MAX_VALUE) best = min(best, cur[i] - pre[i + 1])
            }
            cur = next
        }
        var res = Float.MAX_VALUE
        for (i in 0 until n) if (cur[i] < Float.MAX_VALUE) res = min(res, cur[i] + pre[n] - pre[i + 1])
        return res
    }
}
