package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class DictionaryTest {
    companion object {
        lateinit var ru: Dictionary

        @BeforeClass @JvmStatic
        fun load() {
            ru = Dictionary(Langs.RU)
            File("src/main/assets/ru.dict").inputStream().use { ru.load(it) }
            File("src/main/assets/ru.bi").inputStream().use { ru.loadContext(it) }
        }
    }

    private fun next(prefix: String): List<Pair<Char, Float>> {
        val out = FloatArray(Langs.RU.size)
        val total = ru.addNextChars(normalize(prefix), out)
        return Langs.RU.alphabet.mapIndexed { i, c -> c to out[i] / total }.sortedByDescending { it.second }
    }

    @Test
    fun nextLetterIsPredicted() {
        val top = next("приве")
        println("приве -> " + top.take(3))
        assertEquals('т', top[0].first)
        val top2 = next("спасиб")
        println("спасиб -> " + top2.take(3))
        assertEquals('о', top2[0].first)
    }

    @Test
    fun correctsNeighborTypos() {
        for ((typo, want) in listOf("приает" to "привет", "спвсибо" to "спасибо", "хорощо" to "хорошо",
            "сегодгя" to "сегодня", "пожалуйта" to "пожалуйста")) {
            val m = HashMap<String, Float>()
            ru.corrections(typo, m)
            val best = m.maxByOrNull { it.value }
            println("$typo -> $best")
            assertEquals(want, best?.key)
            assertTrue(best!!.value > kotlin.math.ln(30f))
        }
    }

    @Test
    fun charBigramFillsLargerBuffer() {
        val out = FloatArray(40)
        ru.charBigram('п', out)
        ru.charBigram(null, out)
        assertTrue(out.take(Langs.RU.size).sum() in 0.99f..1.01f)
    }

    private fun ctxNext(ctxKey: String, prefix: String): Map<Char, Float> {
        val out = FloatArray(Langs.RU.size)
        val total = ru.addNextCharsCtx(ru.context(ctxKey)!!, prefix, out)
        return Langs.RU.alphabet.mapIndexed { i, c -> c to out[i] / total }.toMap()
    }

    @Test
    fun sentenceStartPrefersYa() {
        val p = ctxNext("<s>", "")
        println("<s>: я=${p['я']} ч=${p['ч']}")
        assertTrue(p['я']!! > 1.5f * p['ч']!!)
    }

    @Test
    fun contextShiftsLetters() {
        // «как дела» — после «как» буква «д» вероятнее соседней «л»
        val p = ctxNext("как", "")
        println("как: д=${p['д']} л=${p['л']}")
        assertTrue(p['д']!! > p['л']!!)
        assertTrue(ru.contextBoost(ru.context("спокойной"), "ночи") > 2f)
        println("as <s> top: " + ru.topNext(ru.context("<s>"), 3))
    }

    @Test
    fun completes() {
        val got = ArrayList<String>()
        ru.completions("здр", 3) { w, _ -> got.add(w) }
        println("здр -> $got")
        assertTrue(got.any { it.startsWith("здравств") })
    }
}
