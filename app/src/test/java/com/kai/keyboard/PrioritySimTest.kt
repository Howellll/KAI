package com.kai.keyboard

import org.junit.Test
import java.io.File

/**
 * Приоритет своих слов. «Человек» пишет переписку со своими привычками («норм», «щас», «спс», «конеш»…),
 * клавиатура учится на одной части, проверяем на другой:
 *  — дописывание после двух букв (своё слово первым? в тройке?) — для привычных слов и для остальных;
 *  — свайп привычных и остальных слов;
 *  — смена привычки: долго писал «норм», потом перешёл на «нормально» — как быстро новое выходит вперёд.
 * Сравнение — старая механика (USER_PRIORITY=false) и новая.
 */
class PrioritySimTest {
    private val ru = TypingSim.loadRu()
    private val text = TypingSim.loadChat() ?: TypingSim.loadSim()
    private val train = text.drop(text.size / 2)
    private val test = text.take(text.size / 2).take(1500)

    /** Привычки: вместо слова — своё; часть своих есть в словаре, но редкие, части нет совсем. */
    private val habits = mapOf(
        "нормально" to "норм", "сейчас" to "щас", "спасибо" to "спс", "конечно" to "конеш",
        "вообще" to "ваще", "только" to "тока", "наверное" to "наверно", "пожалуйста" to "пожалуйста",
        "короче" to "кароч", "привет" to "прив", "хорошо" to "хорошо", "что-то" to "чет",
        "сегодня" to "седня", "человек" to "чел", "почему" to "почему", "ничего" to "ниче",
    ).filter { it.key != it.value }
    // и редкие для словаря слова, которые этот человек пишет часто
    private val favourites = listOf("прикольно", "капец", "кринж", "залипать", "го", "ладушки", "мда", "жиза")

    private fun personal(snt: String, h: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        for ((i, w) in TypingSim.tokens(snt).withIndex()) {
            out.add(h[w] ?: w)
            if (i % 9 == 4) out.add(favourites[(snt.length + i) % favourites.size])
        }
        return out
    }

    private fun learn(e: Engine, set: List<String>, h: Map<String, String>) {
        for (snt in set) {
            val before = StringBuilder()
            for (w in personal(snt, h)) {
                e.learn(w, TextCtx.contextKey(before.toString(), before.length), Langs.RU)
                before.append(w).append(' ')
            }
        }
    }

    private class Score { var n = 0; var top1 = 0; var top3 = 0
        fun add(got: List<String>, w: String) { n++; if (got.firstOrNull() == w) top1++; if (w in got.take(3)) top3++ }
        override fun toString() = "первым %5.1f%%  в тройке %5.1f%%".format(100.0 * top1 / maxOf(1, n), 100.0 * top3 / maxOf(1, n)) }

    /** Дописывание после двух букв: [привычные, остальные]. */
    private fun completion(e: Engine, set: List<String>, h: Map<String, String>): Pair<Score, Score> {
        val mine = Score(); val other = Score()
        val habitual = h.values.toSet() + favourites
        for (snt in set) {
            val before = StringBuilder()
            for (w in personal(snt, h)) {
                if (w.length >= 3) {
                    val ctx = TextCtx.contextKey(before.toString(), before.length)
                    val got = e.suggest(Langs.RU, w.substring(0, 2), ctx, false, false)?.items?.map { normalize(it.text) } ?: emptyList()
                    (if (w in habitual) mine else other).add(got, w)
                }
                before.append(w).append(' ')
            }
        }
        return mine to other
    }

    /** Свайп: [привычные, остальные]. */
    private fun swipe(e: Engine, set: List<String>, h: Map<String, String>): Pair<Score, Score> {
        val mine = Score(); val other = Score()
        val habitual = h.values.toSet() + favourites
        val sim = SwipeSim(SwipeSim.NORMAL, 7)
        for (snt in set.take(500)) {
            val before = StringBuilder()
            for (w in personal(snt, h)) {
                if (sim.canSwipe(w) && (ru.contains(w) || e.known(Langs.RU, w))) {
                    val ctx = TextCtx.contextKey(before.toString(), before.length)
                    val g = sim.gesture(w)
                    val got = e.decodeSwipe(Langs.RU, g.pts, sim.centers, ctx, g.times, sim.rowH / sim.pitch).map { normalize(it) }
                    (if (w in habitual) mine else other).add(got, w)
                }
                before.append(w).append(' ')
            }
        }
        return mine to other
    }

    /**
     * Смена привычки: учимся на train со «старыми» словами («норм», «наверно», «конеш», «ниче» — каждое сотни раз),
     * потом человек n раз пишет новое («нормально»…). Доля, где новое — первым и в тройке после общего начала.
     */
    private fun switch(n: Int): String {
        val old = mapOf("нормально" to "норм", "наверное" to "наверно", "конечно" to "конеш", "ничего" to "ниче")
        val e = TypingSim.freshEngine(ru)
        learn(e, train, old)
        // и дальше обычная переписка, в которой новое слово встречается n раз
        val rest = test.drop(1000)
        var k = 0
        for (snt in rest) {
            learn(e, listOf(snt), emptyMap())
            if (k < n * old.size) { e.learn(old.keys.elementAt(k % old.size), null, Langs.RU); k++ }
        }
        while (k < n * old.size) { e.learn(old.keys.elementAt(k % old.size), null, Langs.RU); k++ }
        val sc = Score()
        val miss = ArrayList<String>()
        for ((full, short) in old) {
            val p = full.commonPrefixWith(short).take(3)
            val got = e.suggest(Langs.RU, p, null, false, false)?.items?.map { normalize(it.text) } ?: emptyList()
            sc.add(got, full)
            if (got.firstOrNull() != full) miss.add("$full ${e.user.count(full)} < $short ${e.user.count(short)}")
        }
        return "написал новое %3d раз: новое слово %s  %s".format(n, sc, miss.joinToString(", "))
    }

    private fun measure(label: String, out: StringBuilder, full: Boolean) {
        val fresh = TypingSim.freshEngine(ru)
        val e = TypingSim.freshEngine(ru).also { learn(it, train, habits) }
        val (cm, co) = completion(e, test, habits)
        val (sm, so) = swipe(e, test, habits)
        out.appendLine("== $label")
        if (full) {
            val (cm0, co0) = completion(fresh, test, habits)
            out.appendLine("без обучения: дописывание привычных $cm0 | остальных $co0")
        }
        out.appendLine("дописывание: привычные $cm | остальные $co")
        out.appendLine("свайп:       привычные $sm | остальные $so")
        for (k in listOf(3, 10, 30)) out.appendLine("смена привычки, " + switch(k))
    }

    /** Подбор силы своих слов: SWIPE_SWEEP=1. */
    @Test
    fun sweep() {
        org.junit.Assume.assumeTrue(System.getenv("SWIPE_SWEEP") != null)
        val out = StringBuilder()
        val saved = listOf(Engine.PRI_K, Engine.PRI_MAX, Engine.BI_GAIN)
        val ages = UserStore.AGE_EVERY to UserStore.AGE_FACTOR
        try {
            for ((mx, every, f) in listOf(Triple(0.5f, 3000, 0.8), Triple(0.4f, 3000, 0.8), Triple(0.4f, 2000, 0.8),
                                          Triple(0.3f, 3000, 0.8))) {
                Engine.PRI_MAX = mx; UserStore.AGE_EVERY = every; UserStore.AGE_FACTOR = f
                measure("max=$mx остывание $every×$f", out, false)
            }
        } finally {
            Engine.PRI_K = saved[0]; Engine.PRI_MAX = saved[1]; Engine.BI_GAIN = saved[2]
            UserStore.AGE_EVERY = ages.first; UserStore.AGE_FACTOR = ages.second
        }
        File("build").mkdirs(); File("build/priority_sweep.txt").writeText(out.toString())
        println(out)
    }

    @Test
    fun priority() {
        val out = StringBuilder()
        val saved = Engine.USER_PRIORITY
        val age = UserStore.AGE_EVERY
        try {
            Engine.USER_PRIORITY = false; UserStore.AGE_EVERY = Int.MAX_VALUE
            measure("было", out, true)
            Engine.USER_PRIORITY = true; UserStore.AGE_EVERY = age
            measure("стало", out, false)
        } finally { Engine.USER_PRIORITY = saved; UserStore.AGE_EVERY = age }
        File("build").mkdirs(); File("build/priority.txt").writeText(out.toString())
        println(out)
    }
}
