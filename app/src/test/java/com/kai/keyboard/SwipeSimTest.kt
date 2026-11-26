package com.kai.keyboard

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Замеры свайпа: «человек» (SwipeSim) проводит пальцем слова настоящих фраз (Tatoeba) — с их частотами
 * и с предыдущим словом в контексте, как в жизни. Распознаёт тот же код, что на телефоне (Engine.decodeSwipe).
 * Считаем: нужное слово первым (вставится само) и в первой тройке (выбрать в полосе подсказок).
 * Отчёт — в app/build/swipe_sim.txt.
 */
class SwipeSimTest {
    companion object {
        lateinit var ru: Dictionary
        /** На них меряем (до/после). */
        lateinit var sentences: List<String>
        /** На них подбираем параметры — другие фразы, чтобы не подогнать под замеры. */
        lateinit var tuning: List<String>

        @BeforeClass @JvmStatic
        fun load() {
            ru = TypingSim.loadRu()
            val all = TypingSim.loadSim()
            sentences = all.subList(0, 600)
            tuning = all.subList(1000, 1300)
        }
    }

    class Stats(val name: String) {
        var n = 0; var top1 = 0; var top3 = 0; var oov = 0
        var hyph = 0; var hyphOk = 0 // слова с дефисом («кто-то»)
        val byLen = Array(3) { IntArray(2) } // [короткие 2–3, 4–6, 7+][всего, верно первым]
        val ms = ArrayList<Double>()
        val misses = HashMap<String, Int>()
        var points = 0L; var duration = 0.0

        fun add(word: String, got: List<String>, time: Double) {
            n++
            ms.add(time)
            val norm = got.map { normalize(it) }
            val b = when { word.length <= 3 -> 0; word.length <= 6 -> 1; else -> 2 }
            byLen[b][0]++
            if (word.contains('-')) { hyph++; if (norm.firstOrNull() == word) hyphOk++ }
            if (norm.firstOrNull() == word) { top1++; byLen[b][1]++ }
            else misses.merge("$word → ${norm.firstOrNull() ?: "∅"}", 1, Int::plus)
            if (norm.take(3).contains(word)) top3++
        }

        fun pct(a: Int, b: Int) = 100.0 * a / maxOf(1, b)
        fun line(): String {
            val s = ms.sorted()
            return "%-12s первым %5.1f%%  в тройке %5.1f%%  | 2–3 букв %5.1f%%  4–6 %5.1f%%  7+ %5.1f%%  | %.1f мс (95%% — %.1f)  | слов %d, нет в словаре %d | с дефисом %d/%d".format(
                name, pct(top1, n), pct(top3, n), pct(byLen[0][1], byLen[0][0]), pct(byLen[1][1], byLen[1][0]),
                pct(byLen[2][1], byLen[2][0]), s.average(), s[(s.size * 95 / 100).coerceAtMost(s.size - 1)], n, oov, hyphOk, hyph)
        }
    }

    /** Прогон одного профиля «человека» по всем фразам. */
    private fun run(profile: SwipeSim.Profile, seed: Long, engine: Engine = TypingSim.freshEngine(ru),
                    set: List<String> = sentences): Stats {
        val sim = SwipeSim(profile, seed)
        val st = Stats(profile.name)
        for (s in set) {
            val before = StringBuilder()
            for (w in TypingSim.tokens(s)) {
                if (sim.canSwipe(w)) {
                    if (!ru.contains(w)) st.oov++
                    else {
                        val ctx = TextCtx.contextKey(before.toString(), before.length)
                        val gst = sim.gesture(w)
                        st.points += gst.pts.size / 2; st.duration += gst.times.last()
                        val t0 = System.nanoTime()
                        val got = engine.decodeSwipe(Langs.RU, gst.pts, sim.centers, ctx, gst.times, sim.rowH / sim.pitch)
                        st.add(w, got, (System.nanoTime() - t0) / 1e6)
                    }
                }
                before.append(w).append(' ')
            }
        }
        return st
    }

    @Test
    fun baseline() {
        // прогрев JIT, чтобы время было честным
        run(SwipeSim.NORMAL, 99).also { }
        val out = StringBuilder()
        val all = SwipeSim.ALL.mapIndexed { i, p -> run(p, 1000L + i) }
        for (st in all) out.appendLine(st.line())
        val normal = all[1]
        out.appendLine()
        out.appendLine("Жест «обычно»: в среднем %.0f точек, %.0f мс".format(
            normal.points.toDouble() / normal.n, normal.duration / normal.n))
        out.appendLine()
        for (st in listOf(normal, all[2], all[3])) {
            out.appendLine("Частые промахи («${st.name}»):")
            st.misses.entries.sortedByDescending { it.value }.take(25).forEach { out.appendLine("  ${it.value}× ${it.key}") }
        }
        File("build").mkdirs()
        File("build/swipe_sim.txt").writeText(out.toString())
        println(out)
        // Нижняя граница — чтобы не откатиться незаметно (подстраивается вверх по мере улучшений).
        assertTrue(normal.line(), normal.pct(normal.top1, normal.n) > 40)
    }

    /** До/после: те же жесты (те же зёрна) — без выравнивания по точкам и петелек (как было) и с ними. */
    @Test
    fun alignBeforeAfter() {
        val a = Swipe.ALIGN_W; val b = Swipe.LOOP_BONUS
        try {
            val out = StringBuilder()
            for (on in listOf(false, true)) {
                Swipe.ALIGN_W = if (on) a else 0f; Swipe.LOOP_BONUS = if (on) b else 0f
                out.appendLine(if (on) "С выравниванием ($a) и петельками (+$b):" else "Без выравнивания и петелек:")
                SwipeSim.ALL.forEachIndexed { i, p -> out.appendLine("  " + run(p, 1000L + i).line()) }
            }
            File("build").mkdirs()
            File("build/swipe_align.txt").writeText(out.toString())
            println(out)
        } finally { Swipe.ALIGN_W = a; Swipe.LOOP_BONUS = b }
    }

    /** Подбор параметров DTW на отдельных фразах. Долго — только по SWIPE_SWEEP=1. */
    @Test
    fun dtwSweep() {
        org.junit.Assume.assumeTrue(System.getenv("SWIPE_SWEEP") != null)
        val saved = Triple(Swipe.DTW_SIGMA, Swipe.DTW_BAND, Swipe.DTW_K)
        val out = StringBuilder()
        fun score(): String {
            val r = listOf(SwipeSim.NORMAL, SwipeSim.SLOPPY, SwipeSim.SHIFTED).mapIndexed { i, p -> run(p, 7000L + i, set = tuning) }
            val avg = r.map { it.pct(it.top1, it.n) }.average()
            val t3 = r.map { it.pct(it.top3, it.n) }.average()
            val ms = r.flatMap { it.ms }.sorted()
            val short = r.map { it.pct(it.byLen[0][1], it.byLen[0][0]) }.average()
            return "первым %5.1f%%  в тройке %5.1f%%  2–3 букв %5.1f%%  %.1f мс (95%% — %.1f)".format(
                avg, t3, short, ms.average(), ms[ms.size * 95 / 100])
        }
        try {
            Swipe.DTW = false; out.appendLine("без DTW: " + score()); Swipe.DTW = true
            out.appendLine("сейчас: " + score())
            // Веса второго шага (частота, контекст) и терпимость к небрежности.
            for (f in listOf(0.7f, 1.0f, 1.2f)) { Swipe.FREQ2 = f; out.appendLine("частота $f: " + score()) }
            Swipe.FREQ2 = 0.85f
            for (c in listOf(0.7f, 1.5f, 2f)) { Swipe.CTX_W = c; out.appendLine("контекст $c: " + score()) }
            Swipe.CTX_W = 1f
            for (k in listOf(0.6f, 0.8f, 1.0f, 1.3f)) { Swipe.SLOPPY_K = k; out.appendLine("небрежность $k: " + score()) }
            Swipe.SLOPPY_K = 0f
            File("build").mkdirs(); File("build/swipe_sweep.txt").writeText(out.toString())
            println(out)
        } finally { Swipe.DTW_SIGMA = saved.first; Swipe.DTW_BAND = saved.second; Swipe.DTW_K = saved.third; Swipe.DTW_MIX = 1f }
    }

    /**
     * Подстройка под руку: «человек» с постоянной привычкой (и обычный — проверить, что не вредит)
     * набирает подряд; клавиатура учится на принятых словах (первое верное — принял; верное в тройке —
     * выбрал его; иначе — стёр, не учимся). Точность — за всё время и за последние 2/3 (уже подстроилась).
     */
    @Test
    fun handAdaptation() {
        val out = StringBuilder()
        val savedAlpha = Engine.SWIPE_ALPHA
        try {
            for (profile in listOf(SwipeSim.HABIT, SwipeSim.NORMAL)) for (adapt in listOf(false, true)) {
                val engine = TypingSim.freshEngine(ru).apply { swipeAdapt = adapt }
                val sim = SwipeSim(profile, 4242)
                var n = 0; var ok = 0; var late = 0; var lateOk = 0
                val words = sentences.flatMap { TypingSim.tokens(it) }.filter { !it.contains('-') && sim.canSwipe(it) && ru.contains(it) }
                for ((idx, w) in words.withIndex()) {
                    val gst = sim.gesture(w)
                    val got = engine.decodeSwipe(Langs.RU, gst.pts, sim.centers, null, gst.times, sim.rowH / sim.pitch).map { normalize(it) }
                    val hit = got.firstOrNull() == w
                    n++; if (hit) ok++
                    if (idx >= words.size / 3) { late++; if (hit) lateOk++ }
                    if (hit || got.take(3).contains(w)) engine.learnSwipe(gst.pts, w, sim.centers)
                }
                val o = (0..2).joinToString(" ") { r -> engine.user.swipeOffset(r)?.let { "(%.2f, %.2f)".format(it[0], it[1]) } ?: "—" }
                out.appendLine("%-10s %-14s первым %5.1f%%, после подстройки %5.1f%%  сдвиг по рядам: %s".format(
                    profile.name, if (adapt) "с подстройкой" else "без", 100.0 * ok / n, 100.0 * lateOk / late, o))
            }
            File("build").mkdirs(); File("build/swipe_adapt.txt").writeText(out.toString())
            println(out)
        } finally { Engine.SWIPE_ALPHA = savedAlpha }
    }

    /** Свайп на живой переписке (отложенной из корпуса, словарь её не видел). */
    @Test
    fun chat() {
        val chat = TypingSim.loadChat()
        org.junit.Assume.assumeTrue(chat != null)
        val out = StringBuilder()
        for ((i, p) in listOf(SwipeSim.NORMAL, SwipeSim.SLOPPY).withIndex())
            out.appendLine(run(p, 3000L + i, set = chat!!.take(1500)).line())
        File("build").mkdirs(); File("build/swipe_chat.txt").writeText(out.toString())
        println(out)
    }
}
