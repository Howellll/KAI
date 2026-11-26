package com.kai.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test

/** Клавиатура знает не только частые слова, но и их формы: «кузнец» → «кузнецы», «кузнецах». */
class FormsTest {
    private val ru = TypingSim.loadRu()
    private val engine = TypingSim.freshEngine(ru)

    private fun top(prefix: String): List<Pair<Char, Float>> {
        val out = FloatArray(40)
        val conf = engine.letterProbs(Langs.RU, prefix, null, out)
        val top = Langs.RU.alphabet.mapIndexed { i, c -> c to out[i] }.sortedByDescending { it.second }
        println("$prefix -> conf=%.2f ".format(conf) + top.take(4).map { (c, p) -> "$c=%.2f".format(p) })
        assertTrue("$prefix: зоны должны работать", conf > 0.1f)
        return top
    }

    @Test
    fun knowsInflectedForms() {
        for (w in listOf("кузнецы", "кузнецах", "кузнецами", "табуретками", "подоконниках", "прочитаешь", "холодильнике"))
            assertTrue(w, ru.contains(w))
    }

    @Test
    fun predictsEndingAfterStem() {
        val p = top("кузнец").toMap()
        assertTrue("ы вероятнее в", p['ы']!! > 20 * p['в']!!)
        top("табуретк")
        top("подоконник")
    }

    /** Палец промахнулся из «ы» в сторону «в»: после «кузнец» должно получиться «ы». */
    @Test
    fun tapBetweenKeysGoesToEnding() {
        val sim = TypingSim(engine, 1)
        val probs = FloatArray(40)
        val conf = engine.letterProbs(Langs.RU, "кузнец", null, probs)
        val keys = sim.layout.letterKeys.associateBy { it.ch }
        val y = keys['ы']!!.cy
        val got = (1..9).map { i ->
            val x = keys['ы']!!.cx + (keys['в']!!.cx - keys['ы']!!.cx) * i / 10f
            sim.resolve(x, y, probs, conf)
        }
        println("ы→в: " + got.joinToString(""))
        assertTrue(got.count { it == 'ы' } >= 7)
    }
}

class DictionaryPerfTest {
    @Test
    fun loadTimeAndMemory() {
        val rt = Runtime.getRuntime()
        System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val d = Dictionary(Langs.RU)
        java.io.File("src/main/assets/ru.dict").inputStream().use { d.load(it) }
        val t1 = System.nanoTime()
        System.gc(); val after = rt.totalMemory() - rt.freeMemory()
        val out = FloatArray(40)
        val t2 = System.nanoTime()
        repeat(200) { for (p in listOf("п", "пр", "при", "прив", "кузнец")) { java.util.Arrays.fill(out, 0f); d.addNextChars(p, out); d.completions(p, 6) { _, _ -> } } }
        val t3 = System.nanoTime()
        val m = HashMap<String, Float>()
        repeat(100) { d.corrections("кузнецв", m) }
        val t4 = System.nanoTime()
        println("load %d ms, heap +%d MB, keystroke %.3f ms, corrections %.2f ms".format(
            (t1 - t0) / 1_000_000, (after - before) / 1_000_000, (t3 - t2) / 1e6 / 1000, (t4 - t3) / 1e6 / 100))
        assertTrue(d.contains("кузнецы"))
    }
}
