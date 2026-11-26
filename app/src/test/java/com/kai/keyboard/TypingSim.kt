package com.kai.keyboard

import java.io.File
import java.nio.file.Files
import java.util.Random

/**
 * Симулятор набора: «человек» с неточными пальцами печатает фразы на той же геометрии
 * и том же коде (TouchModel, Engine), что работает на телефоне.
 */
class TypingSim(val engine: Engine, seed: Long) {
    val lang = Langs.RU
    val layout = Layouts.letters(lang)
    private val stripH = 115f
    private val rowH = 142f
    private val gapX = 16f
    val pitch = KeyGeometry.place(layout, 0f, 1080f, stripH + rowH * 4 + 10f, stripH, rowH, 8f, gapX, 29f)
    private val keyOf = layout.letterKeys.associateBy { it.ch }
    private val rnd = Random(seed)
    private val probs = FloatArray(40)
    private val text = StringBuilder()

    // Палец: разброс и привычное смещение (большой палец обычно попадает чуть ниже центра).
    var sigmaX = 0.30f
    var sigmaY = 0.22f
    var biasX = 0.05f
    var biasY = 0.12f
    var useZoneState = true

    class Stats {
        var inTaps = 0; var inOk = 0      // буквы в словарных словах, с первой попытки
        var oovTaps = 0; var oovOk = 0    // буквы в новых словах
        var words = 0; var wordsOk = 0    // слова целиком после автоисправления
        var stuck = 0                     // буква не далась за 5 попыток
        var acDamage = 0                  // автоисправление испортило верно набранное слово
        var retries = 0; var retriesOk = 0 // повторные нажатия после ⌫
        fun line(name: String) = "%-30s всего%5.1f%%  слов.%5.1f%%  нов.%5.1f%%  слова%5.1f%%  повтор%5.1f%%  застр.%3d  автоиспр-%3d".format(
            name, 100.0 * (inOk + oovOk) / maxOf(1, inTaps + oovTaps),
            100.0 * inOk / maxOf(1, inTaps), 100.0 * oovOk / maxOf(1, oovTaps), 100.0 * wordsOk / maxOf(1, words),
            100.0 * retriesOk / maxOf(1, retries), stuck, acDamage)
    }

    /**
     * @param smart умные зоны
     * @param learn учиться по ходу
     * @param missRate доля ошибок, которые человек не замечает и не исправляет
     */
    fun type(sentences: List<String>, smart: Boolean, learn: Boolean, missRate: Double,
             isOov: (String) -> Boolean, stats: Stats) {
        for (sentence in sentences) {
            val words = tokens(sentence)
            if (words.isEmpty()) continue
            for (w in words) typeWord(w, smart, learn, missRate, isOov(w), stats)
            if (text.isNotEmpty()) text.setLength(text.length - 1)
            text.append(". ")
            if (text.length > 400) text.delete(0, text.length - 200)
        }
    }

    private fun typeWord(w: String, smart: Boolean, learn: Boolean, missRate: Double, oov: Boolean, st: Stats) {
        val typed = StringBuilder()
        val touches = ArrayList<FloatArray>()
        val zone = ZoneState()
        for (c in w) {
            val target = keyOf[c]
            if (target == null) { typed.append(c); continue } // ъ, дефис — через долгое нажатие, без промахов
            var attempt = 0
            while (true) {
                val before = (text.toString() + typed).takeLast(64)
                val word = TextCtx.currentWord(before)
                val ctx = TextCtx.contextKey(before, before.length - word.length)
                val raw = if (smart) engine.letterProbs(lang, word, ctx, probs) else -1f
                val conf = if (raw >= 0f && useZoneState) zone.apply(raw) else raw
                val x = target.cx + (biasX + gauss() * sigmaX) * pitch
                val y = target.cy + (biasY + gauss() * sigmaY) * rowH
                val k = TouchModel.resolve(layout, lang, x, y, stripH, if (conf >= 0f) probs else null,
                    maxOf(conf, 0f), pitch, rowH, gapX) { engine.user.offset(it) }
                val ok = k?.kind == KeyKind.LETTER && k.ch == c
                if (attempt == 0) {
                    if (oov) { st.oovTaps++; if (ok) st.oovOk++ } else { st.inTaps++; if (ok) st.inOk++ }
                } else { st.retries++; if (ok) st.retriesOk++ }
                if (!ok && attempt >= 4) st.stuck++
                if (ok || attempt >= 4 || rnd.nextDouble() < missRate) {
                    if (k != null && k.kind == KeyKind.LETTER) {
                        zone.onLetter(if (raw >= 0f) probs[lang.index(k.ch)] else 1f)
                        typed.append(k.ch)
                        touches.add(floatArrayOf(k.ch.code.toFloat(), (x - k.cx) / pitch, (y - k.cy) / rowH))
                    }
                    break
                }
                attempt++ // заметил ошибку: ⌫ и ещё раз
                zone.onBackspace()
            }
        }
        val before = text.toString().takeLast(64)
        val ctx = TextCtx.contextKey(before, before.length)
        var final = typed.toString()
        val s = engine.suggest(lang, final, ctx, true, false)
        val fix = s?.autocorrect
        if (fix != null && s.typed == final) final = fix
        st.words++
        if (normalize(final) == w) st.wordsOk++
        else if (normalize(typed.toString()) == w) st.acDamage++
        if (learn && typed.isNotEmpty()) engine.onWordFinished(lang, typed.toString(), final, ctx, touches, fix != null)
        text.append(final).append(' ')
    }

    private fun gauss() = rnd.nextGaussian().toFloat()

    fun resolve(x: Float, y: Float, probs: FloatArray, conf: Float): Char? =
        TouchModel.resolve(layout, lang, x, y, stripH, probs, conf, pitch, rowH, gapX) { null }?.ch

    companion object {
        fun tokens(s: String): List<String> =
            Regex("[а-яё]+(?:-[а-яё]+)?").findAll(s.lowercase().replace('ё', 'е')).map { it.value }.toList()

        fun freshEngine(ru: Dictionary): Engine = Engine().apply {
            dicts["ru"] = ru
            user = UserStore(Files.createTempDirectory("klava").toFile())
        }

        /** Тестовые фразы: Tatoeba (CC BY 2.0 FR), формат «id<TAB>текст» — см. tools/make_sim.py. */
        fun loadSim(): List<String> = TypingSim::class.java.getResourceAsStream("/sim_ru.tsv")!!
            .bufferedReader().readLines().map { it.substringAfter('\t') }

        /** Отложенная живая переписка (соцсети Taiga, CC BY-SA) — см. tools/build_corpora.py; null — файла нет. */
        fun loadChat(): List<String>? = TypingSim::class.java.getResourceAsStream("/sim_ru_chat.tsv")
            ?.bufferedReader()?.readLines()?.map { it.substringAfter('\t') }

        fun loadRu(): Dictionary = Dictionary(Langs.RU).apply {
            // NO_TRI=1 — замер без троек слов (только пары)
            Dictionary.TRIGRAMS = System.getenv("NO_TRI") == null
            File("src/main/assets/ru.dict").inputStream().use { load(it) }
            File("src/main/assets/ru.bi").inputStream().use { loadContext(it) }
        }
    }
}
