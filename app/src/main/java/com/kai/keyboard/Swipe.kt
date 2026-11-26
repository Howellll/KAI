package com.kai.keyboard

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Набор свайпом. Путь пальца сравнивается с «идеальными» путями слов
 * (ломаная через центры букв): оба пути раскладываются на N равноотстоящих точек,
 * считается среднее расстояние между ними. Итог = похожесть формы + частота слова + контекст.
 * Координаты — в «клавишах»: 1 по X = ширина клавиши, 1 по Y = высота ряда.
 */
object Swipe {
    private const val N = 48
    // Подобрано тестом SwipeTest.sloppySwipes (небрежные свайпы с дрожанием и срезанием углов).
    private const val SIGMA = 0.3f         // допустимое среднее отклонение пути
    private const val FREQ = 0.85f         // вес частоты слова
    private const val END_RADIUS = 1.1f    // первая/последняя буква — рядом с началом/концом
    private const val PASS_RADIUS = 1.3f   // каждая буква слова — рядом с путём

    /**
     * Гибкое выравнивание (DTW): точки жеста и шаблона сопоставляются не по номеру, а так, чтобы
     * путь лёг лучше всего (палец начал медленно, закончил быстро — ничего не съезжает). Дорого,
     * поэтому — только для DTW_K лучших по обычному сравнению. Окно — насколько далеко (в точках
     * из N) можно сдвинуть соответствие: шире — шаблоны чужих слов тоже «подгибаются», хуже. Подобрано
     * в SwipeSimTest.dtwSweep на отдельных фразах; окно 0 (то же поточечное сравнение с этим σ) — хуже
     * прежнего, то есть прирост даёт именно выравнивание.
     */
    var DTW = true
    var DTW_K = 20
    var DTW_BAND = 4
    var DTW_SIGMA = 0.12f
    var DTW_MIX = 1f
    /**
     * Короткий жест (путь короче DTW_SHORT_LEN клавиш) почти не «гнём»: ему легко подогнуться под чужое
     * короткое слово. Окно DTW_SHORT_BAND; -1 — без выравнивания (обычное сравнение).
     */
    var DTW_SHORT_LEN = 4f
    var DTW_SHORT_BAND = -1
    /**
     * Скорость: выравнивание сокращает отклонение не больше чем до DTW_RATIO от поточечного (так почти
     * всегда), поэтому слово, которое и с таким сокращением не обгонит n-е, дальше не считаем — даже
     * не досчитав поточечное. 0 — только точная граница (медленнее).
     */
    var DTW_RATIO = 0.5f

    /** Вес выравнивания букв по точкам (SwipeAlign): 0 — выключено. */
    var ALIGN_W = 0.3f

    /**
     * Петелька над клавишей — двойная буква. LOOPS — вырезать петли из жеста (иначе путь длиннее и форма
     * сбита); слову с удвоенной этой буквой — бонус LOOP_BONUS, слову без — штраф LOOP_PENALTY.
     */
    var LOOPS = false
    var LOOP_BONUS = 3f
    var LOOP_PENALTY = 0f

    /**
     * Баланс «словарь против формы» по длине жеста: вес частоты слова × (L / LM_LEN_REF)^(−LM_LEN_EXP).
     * Короткий жест мало что говорит формой — больше верим частоте; длинный — наоборот. 0 — не менять.
     */
    var LM_LEN_EXP = 0f

    /** Второй шаг: веса частоты слова и контекста (первый — FREQ и как есть). */
    var FREQ2 = FREQ
    var CTX_W = 1f
    /**
     * Терпимость к небрежности: если даже лучшее слово отстоит по форме на md, σ формы — не меньше
     * SLOPPY_K · md (форма весит меньше, частота и контекст больше). 0 — выключено.
     */
    var SLOPPY_K = 0f
    var LM_LEN_REF = 4f

    /**
     * Длина «идеального» пути слова должна быть похожа на длину жеста: от LEN_LO·L − LEN_SLACK до
     * LEN_HI·L + LEN_SLACK клавиш (L — длина жеста). Остальные слова даже не достаём из словаря.
     */
    var LEN_LO = 0.7f
    var LEN_HI = 1.5f
    var LEN_SLACK = 0.7f

    /**
     * Замедления: где палец заметно притормозил (не в начале и не в конце жеста), там буква — обычно
     * поворот. Слово без буквы рядом с таким местом получает штраф SLOW_W · (расстояние − SLOW_R)².
     * Притормозил — это скорость ниже SLOW_FRAC от обычной для этого жеста.
     * Пока выключено (SLOW_W = 0): в симуляторе скорость выводится из формы пути, и время ничего не
     * добавляет к DTW (SwipeSimTest.dtwSweep: ±0,1%). Проверять — на живых жестах.
     */
    var SLOW_W = 0f
    var SLOW_R = 0.5f
    var SLOW_FRAC = 0.6f

    fun slowPoints(pts: FloatArray, times: FloatArray?): FloatArray {
        val m = pts.size / 2
        if (times == null || times.size != m || m < 7) return FloatArray(0)
        val v = FloatArray(m)
        for (i in 1 until m - 1) {
            val d = hypot(pts[2 * i + 2] - pts[2 * i - 2], pts[2 * i + 3] - pts[2 * i - 1])
            v[i] = d / maxOf(1f, times[i + 1] - times[i - 1])
        }
        v[0] = v[1]; v[m - 1] = v[m - 2]
        // сглаживание: один медленный кадр — не замедление
        val sv = FloatArray(m) { i -> (v[maxOf(0, i - 1)] + v[i] + v[minOf(m - 1, i + 1)]) / 3 }
        val med = sv.copyOfRange(1, m - 1).sorted()[(m - 2) / 2]
        if (med <= 0f) return FloatArray(0)
        val sx = pts[0]; val sy = pts[1]; val ex = pts[2 * m - 2]; val ey = pts[2 * m - 1]
        val found = ArrayList<Int>()
        for (i in 2 until m - 2) {
            if (sv[i] >= SLOW_FRAC * med) continue
            if (sv[i] > sv[i - 1] || sv[i] > sv[i + 1] || sv[i] > sv[i - 2] || sv[i] > sv[i + 2]) continue
            val x = pts[2 * i]; val y = pts[2 * i + 1]
            if (hypot(x - sx, y - sy) < 0.7f || hypot(x - ex, y - ey) < 0.7f) continue // начало и конец — и так буквы
            // рядом с уже найденным — одно и то же замедление: оставляем более медленное
            val near = found.indexOfFirst { hypot(pts[2 * it] - x, pts[2 * it + 1] - y) < 0.8f }
            if (near >= 0) { if (sv[i] < sv[found[near]]) found[near] = i } else found.add(i)
        }
        return FloatArray(found.size * 2) { k -> pts[2 * found[k / 2] + (k % 2)] }
    }

    fun resample(src: FloatArray, m: Int, n: Int, out: FloatArray) {
        var total = 0f
        for (i in 1 until m) total += hypot(src[2 * i] - src[2 * i - 2], src[2 * i + 1] - src[2 * i - 1])
        out[0] = src[0]; out[1] = src[1]
        if (m < 2 || total < 1e-4f) {
            for (k in 1 until n) { out[2 * k] = src[0]; out[2 * k + 1] = src[1] }
            return
        }
        val step = total / (n - 1)
        var k = 1
        var acc = 0f
        var px = src[0]
        var py = src[1]
        var i = 1
        while (i < m && k < n) {
            val qx = src[2 * i]
            val qy = src[2 * i + 1]
            val seg = hypot(qx - px, qy - py)
            if (seg > 0f && acc + seg >= step) {
                val t = (step - acc) / seg
                px += t * (qx - px); py += t * (qy - py)
                out[2 * k] = px; out[2 * k + 1] = py
                k++; acc = 0f
            } else {
                acc += seg; px = qx; py = qy; i++
            }
        }
        while (k < n) { out[2 * k] = src[2 * m - 2]; out[2 * k + 1] = src[2 * m - 1]; k++ }
    }

    /**
     * @param pts путь пальца (x0,y0,x1,y1…) в единицах клавиш
     * @param centers центры букв в тех же единицах
     * @param boost бонус контекста для слова
     * @param extra дополнительные слова (выученные): (ключ, вес)
     */
    fun decode(
        dict: Dictionary,
        pts0: FloatArray,
        centers: Map<Char, FloatArray>,
        boost: (String) -> Float,
        extra: ((String, Float) -> Unit) -> Unit,
        n: Int = 4,
        times0: FloatArray? = null,
        aspect: Float = 1.45f,
    ): List<String> {
        // Петельки (двойные буквы): вырезаем из жеста, сами — признак двойной буквы при подсчёте.
        val lp = if (LOOPS || LOOP_BONUS > 0f || LOOP_PENALTY > 0f) SwipeAlign.loops(pts0, times0, centers, aspect) else null
        val pts = if (LOOPS) lp?.pts ?: pts0 else pts0
        val times = if (LOOPS) lp?.times ?: times0 else times0
        val loops = lp?.keys ?: emptySet()
        val m = pts.size / 2
        if (m < 2) return emptyList()
        val slow = if (SLOW_W > 0f) slowPoints(pts, times) else FloatArray(0)
        val g = FloatArray(2 * N)
        resample(pts, m, N, g)
        val sx = g[0]; val sy = g[1]
        val ex = g[2 * N - 2]; val ey = g[2 * N - 1]
        val first = HashSet<Char>()
        val last = HashSet<Char>()
        for ((c, p) in centers) {
            if (hypot(p[0] - sx, p[1] - sy) < END_RADIUS) first.add(c)
            if (hypot(p[0] - ex, p[1] - ey) < END_RADIUS) last.add(c)
        }
        if (first.isEmpty() || last.isEmpty()) return emptyList()

        // Буквы рядом с путём пальца: в слове могут быть только они (один раз на жест, а не на слово).
        val near = BooleanArray(dict.lang.size)
        val nearChars = HashSet<Char>()
        for ((c, p) in centers) {
            var bd = Float.MAX_VALUE
            for (j in 0 until N) {
                val dx = g[2 * j] - p[0]; val dy = g[2 * j + 1] - p[1]
                bd = minOf(bd, dx * dx + dy * dy)
            }
            if (bd <= PASS_RADIUS * PASS_RADIUS) {
                nearChars.add(c)
                val ci = dict.lang.index(c)
                if (ci >= 0) near[ci] = true
            }
        }

        val path = FloatArray(64)
        val t = FloatArray(2 * N)
        val best = HashMap<String, Pair<String, Float>>()
        // Сколько лучших держим: n — сразу ответ; с DTW — больше, их потом пересчитаем точнее.
        val keep = if (DTW || ALIGN_W > 0f) maxOf(n, DTW_K) else n
        // keep лучших оценок: слово, которое не обгонит худшую из них даже при идеальной форме,
        // не стоит сравнивать по форме вовсе.
        val top = FloatArray(keep) { Float.NEGATIVE_INFINITY }
        // С DTW — точная планка: выравнивание не даёт отклонения больше поточечного, поэтому оценка
        // с поточечным отклонением — нижняя граница итоговой. Слово, которое не обгонит n-ю такую
        // границу даже с идеальной формой, в ответ не попадёт.
        val topLb = FloatArray(n) { Float.NEGATIVE_INFINITY }
        var glen = 0f
        for (j in 1 until N) glen += hypot(g[2 * j] - g[2 * j - 2], g[2 * j + 1] - g[2 * j - 1])
        val band = if (glen < DTW_SHORT_LEN) DTW_SHORT_BAND else DTW_BAND
        val dtwOn = DTW && band >= 0
        val freqW = if (LM_LEN_EXP == 0f) FREQ
            else FREQ * Math.pow((maxOf(glen, 0.5f) / LM_LEN_REF).toDouble(), -LM_LEN_EXP.toDouble()).toFloat().coerceIn(0.5f, 2f)
        val priors = HashMap<String, Float>()
        val lin = HashMap<String, Float>()
        // части оценки — для второго шага (там свои веса частоты и контекста)
        val lnW = HashMap<String, Float>(); val bst = HashMap<String, Float>(); val pen = HashMap<String, Float>()

        fun consider(key: String, word: String, weight: Float) {
            val len = key.length
            if (len < 2 || len > 30) return
            if (key[0] !in first || key[len - 1] !in last) return
            val bw = boost(word)
            var prior = freqW * ln(1f + weight) + bw
            // Замедление без буквы рядом — штраф (сразу в «лучшую возможную» оценку: отсев честный).
            var slowPen = 0f
            if (slow.isNotEmpty()) {
                var pen = 0f
                for (s in 0 until slow.size / 2) {
                    var bd = Float.MAX_VALUE
                    for (c in key) centers[c]?.let { bd = minOf(bd, hypot(it[0] - slow[2 * s], it[1] - slow[2 * s + 1])) }
                    if (bd > SLOW_R) pen += (bd - SLOW_R) * (bd - SLOW_R)
                }
                prior -= SLOW_W * pen
                slowPen = SLOW_W * pen
            }
            // С DTW: в пересчёт попадают только keep лучших по обычному сравнению — слово, которое не
            // войдёт и туда, тоже не нужно.
            if (prior <= when {
                    ALIGN_W > 0f -> top[keep - 1] // выравнивание только снижает оценку — точной планки нет
                    dtwOn -> maxOf(topLb[n - 1], top[keep - 1])
                    else -> top[n - 1]
                }) return
            var pm = 0
            for (c in key) {
                if (c == '-' || c == '\'') continue // не клавиши — на пути их нет
                if (c !in nearChars) return
                val cc = centers[c] ?: return
                if (pm > 0 && path[2 * pm - 2] == cc[0] && path[2 * pm - 1] == cc[1]) continue
                if (pm >= path.size / 2) return
                path[2 * pm] = cc[0]; path[2 * pm + 1] = cc[1]; pm++
            }
            // все буквы на одной клавише («её», «аа»): шаблон — точка на ней
            if (pm == 1) { path[2] = path[0]; path[3] = path[1]; pm = 2 }
            resample(path, pm, N, t)
            var sum = 0f
            val limit = if (dtwOn && DTW_RATIO > 0f && topLb[n - 1] > Float.NEGATIVE_INFINITY)
                sqrt(maxOf(0f, (prior - topLb[n - 1]) * 2 * DTW_SIGMA * DTW_SIGMA)) / DTW_RATIO * N
            else Float.MAX_VALUE
            for (j in 0 until N) {
                val dx = g[2 * j] - t[2 * j]; val dy = g[2 * j + 1] - t[2 * j + 1]
                sum += sqrt(dx * dx + dy * dy)
                if (sum > limit) return
            }
            val md = sum / N
            val score = -md * md / (2 * SIGMA * SIGMA) + prior
            val prev = best[key]
            if (prev != null && prev.second >= score) return
            best[key] = word to score
            priors[key] = prior
            lin[key] = md
            lnW[key] = ln(1f + weight); bst[key] = bw; pen[key] = slowPen
            if (prev == null && score > top[keep - 1]) {
                var j = keep - 1
                while (j > 0 && top[j - 1] < score) { top[j] = top[j - 1]; j-- }
                top[j] = score
            }
            if (dtwOn) {
                val lb = -md * md / (2 * DTW_SIGMA * DTW_SIGMA) + prior
                if (lb > topLb[n - 1]) {
                    var j = n - 1
                    while (j > 0 && topLb[j - 1] < lb) { topLb[j] = topLb[j - 1]; j-- }
                    topLb[j] = lb
                }
            }
        }

        // Сначала частые слова: они быстро задают планку, и хвост редких форм отсекается без расчётов.
        class C(val key: String, val word: String, val weight: Float)
        val cands = ArrayList<C>()
        dict.forEachWord(first, last, near, centers, LEN_LO * glen - LEN_SLACK, LEN_HI * glen + LEN_SLACK) { k, w, wt ->
            cands.add(C(k, w, wt))
        }
        extra { k, wt -> cands.add(C(k, k, wt)) }
        cands.sortByDescending { it.weight }
        for ((i, c) in cands.withIndex()) {
            // Распознавание могли отменить (сторож в клавиатуре) — не досчитываем зря.
            if (i and 1023 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException()
            consider(c.key, c.word, c.weight)
        }
        val alignOn = ALIGN_W > 0f || LOOP_BONUS > 0f || LOOP_PENALTY > 0f
        if (!dtwOn && !alignOn) return best.values.sortedByDescending { it.second }.take(n).map { it.first }
        val al = if (alignOn) SwipeAlign.prepare(pts, times, centers, aspect) else null
        val dp = FloatArray((N + 1) * (N + 1))
        val bar = if (alignOn) Float.NEGATIVE_INFINITY else topLb[n - 1]
        class R(val word: String, val key: String, val md: Float, val sigma: Float, val rest: Float)
        val parts = best.entries.filter { (priors[it.key] ?: 0f) > bar }
            .sortedByDescending { it.value.second }.take(keep).map { (key, v) ->
            var pm = 0
            for (c in key) {
                val cc = centers[c] ?: continue
                if (pm > 0 && path[2 * pm - 2] == cc[0] && path[2 * pm - 1] == cc[1]) continue
                path[2 * pm] = cc[0]; path[2 * pm + 1] = cc[1]; pm++
            }
            resample(path, pm, N, t)
            val md = if (dtwOn) DTW_MIX * dtw(g, t, dp, band) + (1 - DTW_MIX) * (lin[key] ?: 0f) else lin[key] ?: 0f
            var rest = (lnW[key] ?: 0f) * FREQ2 + (bst[key] ?: 0f) * CTX_W - (pen[key] ?: 0f)
            if (al != null && ALIGN_W > 0f) rest -= ALIGN_W * SwipeAlign.cost(al, key)
            for (c in loops) {
                val doubled = (1 until key.length).any { key[it] == c && key[it - 1] == c }
                rest += if (doubled) LOOP_BONUS else -LOOP_PENALTY
            }
            R(v.first, key, md, if (dtwOn) DTW_SIGMA else SIGMA, rest)
        }
        if (parts.isEmpty()) return emptyList()
        // Небрежный жест: даже лучшее слово далеко по форме — форма весит меньше, частота и контекст больше.
        val mdMin = parts.minOf { it.md }
        return parts.map { r ->
            val sg = maxOf(r.sigma, SLOPPY_K * mdMin)
            r.word to (-r.md * r.md / (2 * sg * sg) + r.rest)
        }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    /**
     * Среднее расстояние при лучшем сопоставлении точек a и b (по N штук) в окне DTW_BAND.
     * Шаг по диагонали весит вдвое: путь целиком всегда весит 2N, и прямое соответствие даёт
     * то же число, что поточечное сравнение.
     */
    private fun dtw(a: FloatArray, b: FloatArray, dp: FloatArray, band: Int): Float {
        val w = N + 1
        val inf = Float.MAX_VALUE / 4
        java.util.Arrays.fill(dp, inf)
        dp[0] = 0f
        for (i in 1..N) {
            val lo = maxOf(1, i - band); val hi = minOf(N, i + band)
            for (j in lo..hi) {
                val dx = a[2 * i - 2] - b[2 * j - 2]; val dy = a[2 * i - 1] - b[2 * j - 1]
                val d = sqrt(dx * dx + dy * dy)
                val diag = dp[(i - 1) * w + j - 1] + 2 * d
                val up = dp[(i - 1) * w + j] + d
                val left = dp[i * w + j - 1] + d
                dp[i * w + j] = minOf(diag, minOf(up, left))
            }
        }
        return dp[N * w + N] / (2 * N)
    }
}
