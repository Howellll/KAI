package com.kai.keyboard

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Random

/**
 * Быстрый набор двумя большими пальцами: нажатия разных рук идут почти вместе, и иногда
 * клавиатура получает их не в том порядке, что задумано («пр-ви-ет»). Меряем, сколько это стоит
 * и сколько возвращает Reorder — тот же код, что в клавиатуре.
 *
 * Модель: буквы левой половины — левый палец, правой — правый. Задуманные моменты нажатий идут
 * с интервалом (между руками — короче, одной рукой — длиннее), фактические — с разбросом σ.
 * Одной рукой порядок не перепутать: палец должен подняться. Человек не исправляет ошибки
 * по ходу слова — надеется на автоисправление на пробеле.
 */
class TwoThumbSimTest {
    companion object {
        lateinit var ru: Dictionary
        lateinit var eval: List<String>

        @BeforeClass @JvmStatic
        fun load() {
            ru = TypingSim.loadRu()
            eval = TypingSim.loadSim().subList(3000, 3400)
        }
    }

    class Stats {
        var words = 0; var wordsOk = 0
        var letters = 0; var lettersOk = 0
        var inverted = 0 // пар, пришедших не в том порядке
        var swaps = 0; var wrongSwaps = 0
        fun words() = 100.0 * wordsOk / maxOf(1, words)
        fun line(name: String) = "%-28s слова%5.1f%%  буквы%5.1f%%  перевёрнуто%5d  перестановок%5d (ошибочных%4d)".format(
            name, words(), 100.0 * lettersOk / maxOf(1, letters), inverted, swaps, wrongSwaps)
    }

    private class Touch(val target: Char, val tap: Reorder.Tap, val order: Int)

    private inner class Sim(seed: Long, val sigmaT: Double, val reorder: Boolean) {
        val engine = TypingSim.freshEngine(ru)
        val lang = Langs.RU
        val layout = Layouts.letters(lang)
        val stripH = 115f; val rowH = 142f; val gapX = 16f; val width = 1080f
        val pitch = KeyGeometry.place(layout, 0f, width, stripH + rowH * 4 + 10f, stripH, rowH, 8f, gapX, 29f)
        val keyOf = layout.letterKeys.associateBy { it.ch }
        val rnd = Random(seed)
        val probs = FloatArray(64)
        val text = StringBuilder()

        fun gauss() = rnd.nextGaussian()

        fun run(sentences: List<String>, st: Stats) {
            for (s in sentences) {
                val words = TypingSim.tokens(s)
                if (words.isEmpty()) continue
                for (w in words) typeWord(w, st)
                if (text.isNotEmpty()) text.setLength(text.length - 1)
                text.append(". ")
                if (text.length > 400) text.delete(0, text.length - 200)
            }
        }

        private fun probsAfter(prefix: String, out: FloatArray): Float {
            val before = (text.toString() + prefix).takeLast(64)
            val word = TextCtx.currentWord(before)
            val ctx = TextCtx.contextKey(before, before.length - word.length)
            return engine.letterProbs(lang, word, ctx, out)
        }

        private fun resolve(t: Reorder.Tap, p: FloatArray?, conf: Float) =
            TouchModel.resolve(layout, lang, t.x, t.y, stripH, p, conf, pitch, rowH, gapX) { null }

        private fun model(typed: String) = object : Reorder.Model {
            override fun probs(extra: String, out: FloatArray) = probsAfter(typed + extra, out)
            override fun resolve(t: Reorder.Tap, probs: FloatArray?, conf: Float) = this@Sim.resolve(t, probs, conf)
            override fun fit(t: Reorder.Tap, k: Key) = TouchModel.fit((t.x - k.cx) / pitch, (t.y - k.cy) / rowH)
        }

        private fun typeWord(w: String, st: Stats) {
            // Задуманные нажатия и их фактическое время.
            val touches = ArrayList<Touch>()
            var t = 0.0
            val lastByHand = doubleArrayOf(-1e9, -1e9)
            var prevLeft: Boolean? = null
            for ((i, c) in w.withIndex()) {
                val k = keyOf[c] ?: continue
                val left = k.cx < width / 2
                t += if (prevLeft == null) 0.0 else if (prevLeft != left) 140 + 40 * gauss() else 230 + 50 * gauss()
                var at = t + sigmaT * gauss()
                val h = if (left) 0 else 1
                at = maxOf(at, lastByHand[h] + 90) // тот же палец — только после предыдущего
                lastByHand[h] = at
                prevLeft = left
                val x = k.cx + (0.05f + 0.30f * gauss().toFloat()) * pitch
                val y = k.cy + (0.12f + 0.22f * gauss().toFloat()) * rowH
                touches.add(Touch(c, Reorder.Tap(x, y, at.toLong(), x < width / 2), i))
            }
            val arrived = touches.sortedBy { it.tap.at }
            for (j in 1 until arrived.size) if (arrived[j].order < arrived[j - 1].order) st.inverted++

            // Клавиатура: буквы в порядке прихода, умные зоны по уже набранному — как в KlavaIME.
            val typed = StringBuilder()
            val src = ArrayList<Touch>()
            val zone = ZoneState()
            var paired = -1
            for (tc in arrived) {
                val raw = probsAfter(typed.toString(), probs)
                val conf = if (raw >= 0f) zone.apply(raw) else raw
                val k = resolve(tc.tap, if (conf >= 0f) probs else null, maxOf(conf, 0f)) ?: continue
                if (k.kind != KeyKind.LETTER) continue
                zone.onLetter(if (raw >= 0f) probs[lang.index(k.ch)] else 1f)
                if (reorder && src.isNotEmpty() && paired != src.size - 1) {
                    val prev = src.last()
                    val base = typed.substring(0, typed.length - 1)
                    val sw = Reorder.swapped(lang, model(base), prev.tap, tc.tap)
                    if (sw != null) {
                        typed.setLength(typed.length - 1); typed.append(sw)
                        src.add(src.size - 1, tc)
                        paired = src.size - 1
                        st.swaps++
                        if (prev.order < tc.order) st.wrongSwaps++
                        continue
                    }
                }
                typed.append(k.ch); src.add(tc)
            }
            var final = typed.toString()
            val before = text.toString().takeLast(64)
            val ctx = TextCtx.contextKey(before, before.length)
            val s = engine.suggest(lang, final, ctx, true, false)
            val ac = s?.autocorrect
            if (ac != null && s.typed == final) final = ac
            st.words++
            if (normalize(final) == w) st.wordsOk++
            for (i in w.indices) { st.letters++; if (i < typed.length && typed[i] == w[i]) st.lettersOk++ }
            text.append(final).append(' ')
        }
    }

    private fun run(sigma: Double, reorder: Boolean) = Stats().also { Sim(7, sigma, reorder).run(eval, it) }

    /**
     * A/B запаса MARGIN: сколько пар исправлено верно и сколько испорчено (переставлены, хотя
     * пришли в правильном порядке), и что в итоге со словами. Отчёт — в build/margins.txt.
     */
    @Test
    fun marginSweep() {
        val saved = Reorder.MARGIN
        val out = StringBuilder()
        out.append("%-8s %-9s %8s %10s %10s %8s\n".format("σ, мс", "запас", "слова", "исправлено", "испорчено", "Δслова"))
        try {
            for (sigma in listOf(0.0, 25.0, 40.0, 60.0)) {
                val off = run(sigma, false)
                for (m in listOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 8f)) {
                    Reorder.MARGIN = m
                    val on = run(sigma, true)
                    out.append("%-8d %-9.0f %7.1f%% %10d %10d %+7.2f\n".format(sigma.toInt(), m, on.words(),
                        on.swaps - on.wrongSwaps, on.wrongSwaps, on.words() - off.words()))
                }
                out.append("%-8d %-9s %7.1f%%\n\n".format(sigma.toInt(), "выкл", off.words()))
            }
        } finally {
            Reorder.MARGIN = saved
        }
        println(out)
        java.io.File("build/margins.txt").writeText(out.toString())
    }

    @Test
    fun twoThumbs() {
        val out = StringBuilder()
        val gain = HashMap<Double, Double>()
        for (sigma in listOf(0.0, 25.0, 40.0, 60.0)) {
            val off = run(sigma, false)
            val on = run(sigma, true)
            gain[sigma] = on.words() - off.words()
            out.append("=== разброс по времени σ = ${sigma.toInt()} мс ===\n")
            out.append(off.line("как было")).append('\n')
            out.append(on.line("с исправлением порядка")).append('\n')
        }
        println(out)
        java.io.File("build/two-thumbs.txt").writeText(out.toString())
        // Помогает, а не мешает: без перепутанных пар — почти без потерь, при быстром наборе — заметно лучше.
        assertTrue(out.toString(), gain[0.0]!! > -0.3)
        assertTrue(out.toString(), gain[40.0]!! > 0.5)
        assertTrue(out.toString(), gain[60.0]!! > 2.0)
    }
}
