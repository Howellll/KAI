package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Эмодзи: поиск по-русски и по-английски, подсказка к слову, варианты цвета кожи. */
class EmojiTest {
    private val text = File("src/main/assets/emoji.txt").readText()
    private val data = EmojiData(text)

    private fun top(q: String, n: Int = 5) = data.search(q).take(n).map { it.e }

    @Test
    fun hasAllGroupsAndTones() {
        assertTrue(data.size > 1800)
        assertEquals(EmojiData.GROUPS.keys.toList(), data.groups.map { it.id })
        assertEquals(5, data.item("👋")!!.tones.size)
        assertEquals("👋", data.base("👋🏽"))
    }

    @Test
    fun unsupportedGlyphsAreDropped() {
        // Шрифт не умеет 👋🏽 — остальные цвета остаются; не умеет 🐈 — эмодзи нет вовсе.
        val d = EmojiData(text) { it != "👋🏽" && it != "🐈" }
        assertEquals(4, d.item("👋")!!.tones.size)
        assertNull(d.item("🐈"))
        assertTrue(d.search("кошка").none { it.e == "🐈" })
    }

    @Test
    fun windowsLineEndingsStillParse() {
        val d = EmojiData(text.replace("\n", "\r\n"))
        assertEquals(data.size, d.size)
        assertTrue(d.search("кошки").take(5).any { it.e == "🐈" }) // формы слов тоже на месте
    }

    @Test
    fun searchRussianWithWordForms() {
        assertTrue(top("кошка").contains("🐈"))
        assertTrue(top("кошки").contains("🐈")) // форма слова
        assertTrue(top("кош").contains("🐈")) // недописанное
        assertTrue(top("пицца").contains("🍕"))
        assertEquals("❤️", top("сердце")[0])
        assertTrue(top("россия").contains("🇷🇺"))
        assertTrue(top("черная кошка").contains("🐈‍⬛"))
        assertTrue("мой → ${top("мой")}", top("мой").none { it == "🧼" }) // «мой» — не «мыть»
    }

    @Test
    fun searchEnglish() {
        assertEquals("🐈", top("cat")[0])
        assertTrue(top("pizza").contains("🍕"))
        assertTrue(top("cats").contains("🐈"))
        assertEquals("📰", top("news")[0]) // «news» — не «new moon»
        assertTrue(top("wi-fi").contains("🛜"))
        assertTrue(top("curaçao").isNotEmpty() && top("curaçao") == top("curacao"))
    }

    @Test
    fun suggestsForWords() {
        val cases = mapOf(
            "пицца" to "🍕", "пиццу" to "🍕", "кофе" to "☕", "привет" to "👋", "спасибо" to "🙏",
            "люблю" to "❤️", "ахаха" to "😂", "pizza" to "🍕", "ок" to "👌", "собаку" to "🐕",
            "кот" to "🐈", "россия" to "🇷🇺", "cats" to "🐈", "рак" to "🦞", "раки" to "🦞",
            "ангел" to "👼", "обожаю" to "🥰", "любит" to "❤️", "moon" to "🌙", "foxes" to "🦊", "песню" to "🎵", "кофе" to "☕",
        )
        val bad = cases.filter { (w, e) -> data.suggest(w) != e }.map { (w, e) -> "$w → ${data.suggest(w)} (ждали $e)" }
        assertTrue(bad.joinToString("; "), bad.isEmpty())
    }

    @Test
    fun noSuggestionForGenericOrShortWords() {
        for (w in listOf("и", "в", "до", "лицо", "знак", "человек", "что", "это", "the", "есть", "того",
                "мой", "мою", "все", "потом", "дай", "пару", "not", "can", "like", "новая", "south",
                // половинки фраз CLDR и формы чужих слов
                "большие", "сделает", "полу", "первое", "новые", "плохая", "тест", "стоило", "ешь", "блин",
                "gods", "pick", "watch", "скоро")) {
            assertNull(w, data.suggest(w))
        }
    }

    /** Самые частые слова получают эмодзи, только если это их «чатовое» значение или точное имя. */
    @Test
    fun frequentWordsRarelyGetEmoji() {
        val top = File("../tools/ru_50k.txt").takeIf { it.exists() }?.readLines()?.take(500)
            ?.map { it.split(' ')[0] } ?: return
        val allowed = setOf("да", "нет", "хорошо", "спасибо", "привет", "пожалуйста", "пока", "люблю", "думаю",
            "мама", "папа", "деньги", "дом", "домой", "работа", "ночи", "сердце", "кофе", "машина")
        val hits = top.filter { data.suggest(it) != null }
        val other = hits.filter { it !in allowed }
        // Остальное — только существительные с точным именем эмодзи (дверь 🚪, глаза 👀): немного.
        assertTrue("слишком много: $other", other.size <= 40)
        for (w in listOf("есть", "того", "мой", "все", "потом", "здесь", "очень", "почему", "там", "два", "хочу"))
            assertNull(w, data.suggest(w))
    }

    @Test
    fun recentBonusDoesNotLowerThreshold() {
        // Сколько ни пользуйся 🤔, на «знаю» он не всплывёт.
        assertNull(data.suggest("знаю") { 10f })
    }
}
