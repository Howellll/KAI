package com.kai.keyboard

import org.junit.Test
import java.io.File

/** Подсказка следующего слова до первой буквы: в тройке ли нужное слово, с тройками слов и без. */
class NextWordTest {
    private val ru = TypingSim.loadRu()
    private val word = Regex("[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?")

    private fun measure(set: List<String>): String {
        val engine = TypingSim.freshEngine(ru)
        var n = 0; var top1 = 0; var top3 = 0
        for (snt in set) for (m in word.findAll(snt)) {
            if (m.range.first == 0) continue // первое слово — без контекста почти не угадать, не считаем
            val ctx = TextCtx.contextKey(snt.substring(0, m.range.first), m.range.first) ?: continue
            val s = engine.suggest(Langs.RU, "", ctx, true, false) ?: continue
            val got = s.items.map { normalize(it.text) }
            val want = normalize(m.value)
            n++
            if (got.firstOrNull() == want) top1++
            if (want in got) top3++
        }
        return "первым %.1f%%  в тройке %.1f%%  (слов %d)".format(100.0 * top1 / n, 100.0 * top3 / n, n)
    }

    @Test
    fun nextWord() {
        val out = StringBuilder()
        val sets = listOfNotNull("tatoeba" to TypingSim.loadSim(), TypingSim.loadChat()?.let { "переписка" to it.take(1500) })
        for ((name, set) in sets) for (tri in listOf(false, true)) {
            Dictionary.TRIGRAMS = tri
            out.appendLine("%-10s %-9s %s".format(name, if (tri) "тройки" else "пары", measure(set)))
        }
        Dictionary.TRIGRAMS = true
        File("build").mkdirs(); File("build/next_word.txt").writeText(out.toString())
        println(out)
    }
}
