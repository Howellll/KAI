package com.kai.keyboard

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class TypingSimTest {
    companion object {
        lateinit var ru: Dictionary
        lateinit var train: List<String>
        lateinit var eval: List<String>

        /** Свой сленг владельца: этих слов нет в словаре, клавиатура должна их выучить. */
        val personal = listOf(
            "клава норм работает", "щас приду", "го гулять вечером", "кринж какой-то", "имба просто",
            "чекни личку", "спс бро", "ок жду", "лол реально", "погнали в кафешку", "агась понял",
            "го созвон", "чел ты гений", "норм тема", "щас скину",
        )

        /** Для «месяца»: все фразы, кроме проверочных. */
        lateinit var trainAll: List<String>

        @BeforeClass @JvmStatic
        fun load() {
            ru = TypingSim.loadRu()
            val all = TypingSim.loadSim()
            train = all.subList(0, 700)
            trainAll = all.subList(0, 3000)
            eval = all.subList(3000, 3400)
        }
    }

    private fun mixPersonal(src: List<String>, every: Int) =
        src.flatMapIndexed { i, s -> if (i % every == 0) listOf(s, personal[(i / every) % personal.size]) else listOf(s) }

    private fun evalOn(e: Engine, set: List<String>, smart: Boolean, zoneState: Boolean = true,
                       finger: (TypingSim) -> Unit = {}): TypingSim.Stats {
        val st = TypingSim.Stats()
        val oovFresh = TypingSim.freshEngine(ru)
        TypingSim(e, 1).apply { useZoneState = zoneState; finger(this) }
            .type(set, smart, learn = false, missRate = 0.0, isOov = { !oovFresh.known(Langs.RU, it) }, stats = st)
        return st
    }

    private fun evening(e: Engine, missRate: Double, finger: (TypingSim) -> Unit = {}) {
        TypingSim(e, 2).apply(finger).type(mixPersonal(train, 4), smart = true, learn = true,
            missRate = missRate, isOov = { false }, stats = TypingSim.Stats())
    }

    /** Как сила автоисправления меняет итог: слова целиком и испорченные правильные слова. */
    @Test
    fun autocorrectLevels() {
        val evalSet = mixPersonal(eval, 6)
        for (level in listOf(10, 30, 50, 70, 90, 100)) {
            val e = TypingSim.freshEngine(ru).apply { autocorrectLevel = level }
            val st = TypingSim.Stats()
            TypingSim(e, 3).type(evalSet, smart = true, learn = false, missRate = 1.0,
                isOov = { !e.known(Langs.RU, it) }, stats = st)
            println(st.line("автоисправление $level"))
        }
    }

    @Test
    fun eveningOfLearning() {
        val evalSet = mixPersonal(eval, 6)
        val plain = evalOn(TypingSim.freshEngine(ru), evalSet, smart = false)
        val old = evalOn(TypingSim.freshEngine(ru), evalSet, smart = true, zoneState = false)
        val fresh = evalOn(TypingSim.freshEngine(ru), evalSet, smart = true)
        val learned = TypingSim.freshEngine(ru).also { evening(it, 0.3) }
        val afterEvening = evalOn(learned, evalSet, smart = true)
        val sloppy = TypingSim.freshEngine(ru).also { evening(it, 0.6) }
        val afterSloppy = evalOn(sloppy, evalSet, smart = true)

        val other: (TypingSim) -> Unit = { it.biasX = -0.12f; it.biasY = 0.22f }
        val otherPlain = evalOn(TypingSim.freshEngine(ru), evalSet, smart = false, finger = other)
        val otherFresh = evalOn(TypingSim.freshEngine(ru), evalSet, smart = true, finger = other)
        val otherLearned = evalOn(TypingSim.freshEngine(ru).also { evening(it, 0.3, other) }, evalSet, true, finger = other)

        println("\n=== Симуляция вечера набора ===")
        println(plain.line("обычные клавиши"))
        println(old.line("умные зоны, старая логика"))
        println(fresh.line("умные зоны, новые"))
        println(afterEvening.line("  …после вечера"))
        println(afterSloppy.line("  …после неряшливого вечера"))
        println("--- палец со смещением влево-вниз ---")
        println(otherPlain.line("обычные клавиши"))
        println(otherFresh.line("умные зоны, без обучения"))
        println(otherLearned.line("  …после вечера"))

        fun acc(s: TypingSim.Stats) = 100.0 * s.inOk / s.inTaps
        fun oovAcc(s: TypingSim.Stats) = 100.0 * s.oovOk / s.oovTaps
        // Защита от регрессий: умные зоны заметно лучше обычных, новые слова — не хуже,
        // буквы не «застревают», а обучение (даже на неряшливом наборе) не ухудшает.
        assertTrue(acc(fresh) > acc(plain) + 5)
        assertTrue(oovAcc(fresh) > oovAcc(plain) - 2)
        assertTrue(fresh.stuck <= 3)
        assertTrue(acc(afterEvening) >= acc(fresh) - 0.5)
        assertTrue(acc(afterSloppy) >= acc(fresh) - 1.0)
        assertTrue(acc(otherLearned) > acc(otherFresh))
    }

    /**
     * Долгое обучение («месяц»): тысячи фраз, слова набираются десятки раз. Выученное не должно со временем
     * раздувать все буквы сразу — после месяца не хуже, чем после вечера.
     */
    @Test
    fun monthOfLearning() {
        val evalSet = mixPersonal(eval, 6)
        val fresh = evalOn(TypingSim.freshEngine(ru), evalSet, smart = true)
        val e = TypingSim.freshEngine(ru)
        evening(e, 0.3)
        val week = evalOn(e, evalSet, smart = true)
        repeat(3) { pass ->
            TypingSim(e, 10L + pass).type(mixPersonal(trainAll, 4), smart = true, learn = true,
                missRate = 0.3, isOov = { false }, stats = TypingSim.Stats())
        }
        val month = evalOn(e, evalSet, smart = true)
        val out = StringBuilder()
        // Сколько клавиш «вероятны» (вероятность > 8%) и уверенность — до и после долгого обучения.
        fun shape(en: Engine): String {
            val probs = FloatArray(40)
            var n = 0; var wide = 0.0; var top = 0.0; var conf = 0.0
            for (snt in eval.take(150)) for (w in TypingSim.tokens(snt)) for (k in 0 until w.length) {
                val c = en.letterProbs(Langs.RU, w.substring(0, k), null, probs)
                if (c < 0f) continue
                n++; conf += c
                wide += probs.take(Langs.RU.size).count { it > 0.08f }
                top += probs.take(Langs.RU.size).maxOrNull() ?: 0f
            }
            return "клавиш >8%%: %.2f, самая вероятная: %.2f, уверенность: %.2f".format(wide / n, top / n, conf / n)
        }
        out.appendLine("без обучения:   " + shape(TypingSim.freshEngine(ru)))
        out.appendLine("после «месяца»: " + shape(e))
        out.appendLine(fresh.line("без обучения"))
        out.appendLine(week.line("после вечера"))
        out.appendLine(month.line("после «месяца»"))
        java.io.File("build").mkdirs(); java.io.File("build/typing_month.txt").writeText(out.toString())
        println(out)
        fun acc(s: TypingSim.Stats) = 100.0 * (s.inOk + s.oovOk) / (s.inTaps + s.oovTaps)
        assertTrue(out.toString(), acc(month) >= acc(week) - 0.5)
    }

    /** Подбор: свои слова долей против абсолютного веса, после вечера и после «месяца» (с опечатками). SWIPE_SWEEP=1. */
    @Test
    fun userMixSweep() {
        org.junit.Assume.assumeTrue(System.getenv("SWIPE_SWEEP") != null)
        val evalSet = mixPersonal(eval, 6)
        val out = StringBuilder()
        fun pct(s: TypingSim.Stats) = "всего %5.1f%%  слов. %5.1f%%  нов. %5.1f%%  повтор %5.1f%%".format(
            100.0 * (s.inOk + s.oovOk) / (s.inTaps + s.oovTaps), 100.0 * s.inOk / s.inTaps,
            100.0 * s.oovOk / maxOf(1, s.oovTaps), 100.0 * s.retriesOk / maxOf(1, s.retries))
        fun scenario(name: String) {
            val ev = TypingSim.freshEngine(ru).also { evening(it, 0.3) }
            val mo = TypingSim.freshEngine(ru).also { e ->
                evening(e, 0.3)
                repeat(3) { pass -> TypingSim(e, 10L + pass).type(mixPersonal(trainAll, 4), true, true, 0.6, { false }, TypingSim.Stats()) }
            }
            out.appendLine("%-26s вечер: %s".format(name, pct(evalOn(ev, evalSet, true))))
            out.appendLine("%-26s месяц: %s".format("", pct(evalOn(mo, evalSet, true))))
        }
        val saved = Triple(Engine.USER_RELATIVE, Engine.USER_K, Engine.USER_MAX)
        try {
            Engine.USER_RELATIVE = false; scenario("абсолютный вес (было)")
            Engine.USER_RELATIVE = true
            for ((k, mx) in listOf(20f to 0.6f, 10f to 0.5f, 40f to 0.7f, 20f to 0.4f)) {
                Engine.USER_K = k; Engine.USER_MAX = mx; scenario("доля K=$k max=$mx")
            }
        } finally { Engine.USER_RELATIVE = saved.first; Engine.USER_K = saved.second; Engine.USER_MAX = saved.third }
        java.io.File("build").mkdirs(); java.io.File("build/typing_mix.txt").writeText(out.toString())
        println(out)
    }

    /** Набор касаниями на живой переписке: доля слов, которых нет в словаре, и точность. */
    @Test
    fun chat() {
        val chat = TypingSim.loadChat()
        org.junit.Assume.assumeTrue(chat != null)
        val set = chat!!.take(1500)
        var words = 0; var oov = 0
        for (snt in set) for (w in TypingSim.tokens(snt)) { words++; if (!ru.contains(w)) oov++ }
        val st = evalOn(TypingSim.freshEngine(ru), set, smart = true)
        val out = "нет в словаре: %.1f%% слов (%d из %d)\n".format(100.0 * oov / words, oov, words) + st.line("переписка, умные зоны")
        java.io.File("build").mkdirs(); java.io.File("build/typing_chat.txt").writeText(out)
        println(out)
    }
}
