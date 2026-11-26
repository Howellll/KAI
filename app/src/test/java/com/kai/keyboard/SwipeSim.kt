package com.kai.keyboard

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * «Человек» со свайпом: проводит пальцем слово по той же раскладке и геометрии, что на телефоне
 * (1080 px в ширину, как TypingSim), и отдаёт путь так же, как KeyboardView: первая точка — касание,
 * дальше — точки не ближе 3 dp друг к другу, в единицах клавиш (x / ширина клавиши, y / высота ряда).
 *
 * Модель жеста (всё в пикселях, 1 dp = 3 px):
 *  - цель у каждой буквы — центр клавиши + привычное смещение руки на весь жест + разброс σ;
 *  - углы срезаются: у каждой промежуточной буквы палец поворачивает по дуге, не доходя до цели;
 *  - путь чуть «плывёт» (плавная волна поперёк движения);
 *  - в конце палец проскакивает или не доходит;
 *  - скорость: разгон, замедление на поворотах, сенсор присылает точку раз в 8 мс.
 * Двойные буквы (сс, нн) — палец просто проходит клавишу, без петли.
 */
class SwipeSim(val profile: Profile, seed: Long) {
    /** Аккуратность: разброс у буквы, срезание углов, сдвиг руки, «плавание», конец жеста (доли клавиши). */
    class Profile(val name: String, val sigma: Float, val cut: Float, val shift: Float, val wobble: Float,
                  val overshoot: Float, val speed: Float,
                  /** Постоянная привычка: палец всегда уезжает на столько (клавиш; по y — рядов), сильнее вверху. */
                  val biasX: Float = 0f, val biasY: Float = 0f,
                  /** Насколько палец притормаживает у нужной буквы (0 — скорость только от поворотов). */
                  val dip: Float = 0.3f,
                  /** Как часто на двойной букве (сс, нн) рисует петельку. */
                  val loop: Float = 0.35f)

    companion object {
        val CAREFUL = Profile("аккуратно", 0.16f, 0.12f, 0.06f, 0.03f, 0.08f, 0.8f)
        val NORMAL = Profile("обычно", 0.26f, 0.25f, 0.12f, 0.06f, 0.15f, 1.0f)
        val SLOPPY = Profile("небрежно", 0.38f, 0.38f, 0.18f, 0.10f, 0.25f, 1.3f)
        /** Одной рукой сбоку: вся траектория сдвинута (большой палец не дотягивается до дальнего края). */
        val SHIFTED = Profile("со сдвигом", 0.26f, 0.25f, 0.35f, 0.06f, 0.15f, 1.0f)
        val ALL = listOf(CAREFUL, NORMAL, SLOPPY, SHIFTED)
        /** Привычка: всегда правее и ниже (большой палец правой руки), на верхнем ряду — сильнее. */
        val HABIT = Profile("привычка", 0.26f, 0.25f, 0.08f, 0.06f, 0.15f, 1.0f, biasX = 0.3f, biasY = 0.25f)

        const val DP = 3f
        /** Множитель «притормаживания у букв» для всех профилей: 0 — проверить, что выигрыш не только от него. */
        var DIP_SCALE = 1f
    }

    val lang = Langs.RU
    val layout = Layouts.letters(lang)
    private val stripH = 115f
    val rowH = 142f
    val pitch = KeyGeometry.place(layout, 0f, 1080f, stripH + rowH * 4 + 10f, stripH, rowH, 8f, 16f, 29f)
    private val keyOf = layout.letterKeys.associateBy { it.ch }
    /** Центры букв так же, как KeyboardView.letterCenters. */
    val centers: Map<Char, FloatArray> = HashMap<Char, FloatArray>().also { m ->
        for (k in layout.letterKeys) m[k.ch] = floatArrayOf(k.cx / pitch, k.cy / rowH)
        m['ь']?.let { m['ъ'] = it }
    }
    private val rnd = Random(seed)
    private fun g() = rnd.nextGaussian().toFloat()

    /** Жест: точки (x,y в единицах клавиш) и время каждой точки, мс. */
    class Gesture(val pts: FloatArray, val times: FloatArray)

    fun canSwipe(word: String) = word.length >= 2 && word.all { it == '-' || keyOf.containsKey(if (it == 'ъ') 'ь' else it) }

    fun gesture(word: String): Gesture {
        val p = profile
        // Цели: буквы без повторов подряд. Двойную букву палец проходит — или рисует на ней петельку.
        val keys = ArrayList<Key>()
        val doubled = HashSet<Int>()
        for (c in word) {
            val k = keyOf[if (c == 'ъ') 'ь' else c] ?: continue
            if (keys.isEmpty() || keys.last() !== k) keys.add(k) else doubled.add(keys.size - 1)
        }
        val loops = doubled.filter { rnd.nextFloat() < p.loop }.toSet()
        val ox = g() * p.shift * pitch + 0.03f * pitch
        val oy = g() * p.shift * 0.7f * pitch + 0.08f * rowH // большой палец — чуть ниже центра
        val tx = FloatArray(keys.size); val ty = FloatArray(keys.size)
        val topY = layout.letterKeys.minOf { it.cy }
        for ((i, k) in keys.withIndex()) {
            val row = ((k.cy - topY) / rowH).coerceIn(0f, 2f) // 0 — верхний ряд
            val reach = 1f + 0.5f * (1f - row) // верхний ряд — дальше от пальца
            tx[i] = k.cx + ox + g() * p.sigma * pitch + p.biasX * reach * pitch
            ty[i] = k.cy + oy + g() * p.sigma * 0.75f * pitch + p.biasY * reach * rowH
        }
        if (keys.size == 1) { // одна клавиша (например, «аа»): короткий штрих по ней
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            return finish(floatArrayOf(tx[0], ty[0], tx[0] + 0.3f * pitch * kotlin.math.cos(ang), ty[0] + 0.3f * pitch * sin(ang)), tx, ty)
        }

        // Ломаная со срезанными углами: у промежуточной цели — дуга (квадратичная Безье) от точки на
        // входящем отрезке до точки на исходящем; чем больше cut, тем дальше дуга от самой цели.
        val dense = ArrayList<Float>()
        fun add(x: Float, y: Float) { dense.add(x); dense.add(y) }
        // Начало: касание не всегда точно в цели — чуть раньше по пути.
        add(tx[0] - (tx[1] - tx[0]) * 0.05f * abs(g()), ty[0] - (ty[1] - ty[0]) * 0.05f * abs(g()))
        // Петелька на клавише: небольшой круг радиусом ~¼ клавиши, в случайную сторону.
        fun loopAt(i: Int) {
            val r = 0.22f * pitch * (0.8f + 0.4f * rnd.nextFloat())
            val px = dense[dense.size - 2]; val py = dense[dense.size - 1]
            val a0 = kotlin.math.atan2(py - ty[i], px - tx[i])
            val cx = tx[i] + r * kotlin.math.cos(a0) * 0.2f; val cy = ty[i] + r * sin(a0) * 0.2f
            val dir = if (rnd.nextBoolean()) 1f else -1f
            for (s in 1..16) {
                val a = a0 + dir * 2 * PI.toFloat() * s / 16
                add(cx + r * kotlin.math.cos(a), cy + r * sin(a))
            }
        }
        if (0 in loops) loopAt(0)
        for (i in 1 until keys.size) {
            val last = i == keys.size - 1
            if (i in loops) {
                // двойная буква с петелькой: доходим до клавиши, круг, дальше — как обычно
                lineTo(dense, tx[i], ty[i]); loopAt(i)
                if (last) break
                continue
            }
            if (last) {
                // Конец: проскочил или не дошёл (вдоль последнего отрезка).
                val f = 1f + g() * p.overshoot * pitch / maxOf(1f, hypot(tx[i] - tx[i - 1], ty[i] - ty[i - 1])) + 0.02f
                lineTo(dense, tx[i - 1] + (tx[i] - tx[i - 1]) * f, ty[i - 1] + (ty[i] - ty[i - 1]) * f)
                break
            }
            val c = (p.cut * (0.7f + 0.6f * rnd.nextFloat())).coerceIn(0.02f, 0.48f)
            val ax = tx[i] + (tx[i - 1] - tx[i]) * c; val ay = ty[i] + (ty[i - 1] - ty[i]) * c
            val bx = tx[i] + (tx[i + 1] - tx[i]) * c; val by = ty[i] + (ty[i + 1] - ty[i]) * c
            lineTo(dense, ax, ay)
            for (s in 1..12) {
                val t = s / 12f; val u = 1 - t
                add(u * u * ax + 2 * u * t * tx[i] + t * t * bx, u * u * ay + 2 * u * t * ty[i] + t * t * by)
            }
        }
        // Плавная волна поперёк движения.
        val amp = p.wobble * pitch * (0.5f + rnd.nextFloat())
        val ph = rnd.nextFloat() * 6.28f
        val freq = 1.5f + rnd.nextFloat() * 2f
        val m = dense.size / 2
        val len = FloatArray(m)
        for (i in 1 until m) len[i] = len[i - 1] + hypot(dense[2 * i] - dense[2 * i - 2], dense[2 * i + 1] - dense[2 * i - 1])
        val total = maxOf(1f, len[m - 1])
        val out = FloatArray(dense.size)
        for (i in 0 until m) {
            val j = minOf(m - 1, i + 1); val h = maxOf(0, i - 1)
            var nx = -(dense[2 * j + 1] - dense[2 * h + 1]); var ny = dense[2 * j] - dense[2 * h]
            val nl = hypot(nx, ny).takeIf { it > 1e-3f } ?: 1f
            nx /= nl; ny /= nl
            val w = amp * sin(ph + freq * 2 * PI.toFloat() * len[i] / total) * sin(PI.toFloat() * len[i] / total)
            out[2 * i] = dense[2 * i] + nx * w; out[2 * i + 1] = dense[2 * i + 1] + ny * w
        }
        return finish(out, tx, ty)
    }

    private fun lineTo(dense: ArrayList<Float>, x: Float, y: Float) {
        val px = dense[dense.size - 2]; val py = dense[dense.size - 1]
        val n = maxOf(1, (hypot(x - px, y - py) / (2 * DP)).toInt())
        for (s in 1..n) { val t = s.toFloat() / n; dense.add(px + (x - px) * t); dense.add(py + (y - py) * t) }
    }

    /**
     * Время вдоль пути: на прямых — быстрее, на поворотах — медленнее, разгон в начале и торможение
     * в конце; сенсор присылает положение раз в 8 мс; KeyboardView берёт точки не ближе 3 dp.
     */
    private fun finish(path: FloatArray, tx: FloatArray, ty: FloatArray): Gesture {
        val m = path.size / 2
        val speed = FloatArray(m) // px/мс
        val base = 2.2f * profile.speed * (0.85f + 0.3f * rnd.nextFloat())
        for (i in 0 until m) {
            val h = maxOf(0, i - 3); val j = minOf(m - 1, i + 3)
            val ax = path[2 * i] - path[2 * h]; val ay = path[2 * i + 1] - path[2 * h + 1]
            val bx = path[2 * j] - path[2 * i]; val by = path[2 * j + 1] - path[2 * i + 1]
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            val turn = if (la < 1e-3f || lb < 1e-3f) 0f else acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f))
            speed[i] = base * (0.3f + 0.7f * (1f - turn / PI.toFloat()))
        }
        // У нужных букв (кроме первой и последней — там и так разгон и торможение) палец притормаживает.
        val dip = profile.dip * DIP_SCALE * (0.6f + 0.8f * rnd.nextFloat())
        if (dip > 0f && tx.size > 2) for (i in 0 until m) {
            var bd = Float.MAX_VALUE
            for (j in 1 until tx.size - 1) bd = minOf(bd, hypot(path[2 * i] - tx[j], path[2 * i + 1] - ty[j]))
            val r = 0.35f * pitch
            speed[i] *= 1f - minOf(0.8f, dip) * kotlin.math.exp(-(bd / r) * (bd / r))
        }
        val ramp = minOf(10, m / 3)
        for (i in 0 until ramp) { speed[i] *= 0.35f + 0.65f * i / ramp; speed[m - 1 - i] *= 0.35f + 0.65f * i / ramp }
        // Время каждой плотной точки.
        val tm = FloatArray(m)
        for (i in 1 until m) {
            val d = hypot(path[2 * i] - path[2 * i - 2], path[2 * i + 1] - path[2 * i - 1])
            tm[i] = tm[i - 1] + d / maxOf(0.2f, (speed[i] + speed[i - 1]) / 2)
        }
        // Выборка раз в 8 мс (линейно между плотными точками) и фильтр 3 dp, как в KeyboardView.
        val xs = ArrayList<Float>(); val ts = ArrayList<Float>()
        xs.add(path[0]); xs.add(path[1]); ts.add(0f)
        var j = 1
        var t = 8f
        while (true) {
            while (j < m && tm[j] < t) j++
            val x: Float; val y: Float
            if (j >= m) { x = path[2 * m - 2]; y = path[2 * m - 1] } else {
                val f = (t - tm[j - 1]) / maxOf(1e-3f, tm[j] - tm[j - 1])
                x = path[2 * j - 2] + (path[2 * j] - path[2 * j - 2]) * f
                y = path[2 * j - 1] + (path[2 * j + 1] - path[2 * j - 1]) * f
            }
            if (hypot(x - xs[xs.size - 2], y - xs[xs.size - 1]) > 3 * DP) { xs.add(x); xs.add(y); ts.add(t) }
            if (j >= m) break
            t += 8f
        }
        val pts = FloatArray(xs.size) { i -> if (i % 2 == 0) xs[i] / pitch else xs[i] / rowH }
        return Gesture(pts, ts.toFloatArray())
    }
}
