package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RelayoutTest {
    private val engine = Engine().apply {
        for (l in listOf(Langs.RU, Langs.EN)) dicts[l.code] = Dictionary(l).apply {
            File("src/main/assets/${l.code}.dict").inputStream().use { load(it) }
        }
        user = UserStore(Files.createTempDirectory("klava").toFile())
    }

    private fun fix(lang: Lang, typed: String): String? {
        val s = engine.suggest(lang, typed, null, correct = true, capNext = false)!!
        println("$typed -> auto=${s.autocorrect} (${s.autocorrectLang?.code}) ${s.items.map { it.text }}")
        return s.autocorrect
    }

    @Test
    fun pcLayout() {
        assertEquals("привет", fix(Langs.EN, "ghbdtn"))
        assertEquals("Привет", fix(Langs.EN, "Ghbdtn"))
        assertEquals("как", fix(Langs.EN, "rfr"))
        assertEquals("нормально", fix(Langs.EN, "yjhvfkmyj"))
        assertEquals("hello", fix(Langs.RU, "руддщ"))
        assertEquals("thanks", fix(Langs.RU, "ерфтлы"))
    }

    @Test
    fun ownKeyboardPositions() {
        // На нашей клавиатуре у русской раскладки 11 колонок: «х» — крайняя справа, как «p».
        assertEquals("хорошо", fix(Langs.EN, "pjhjij"))
    }

    @Test
    fun realWordsUntouched() {
        assertNull(fix(Langs.EN, "hello"))
        assertNull(fix(Langs.EN, "hel"))
        assertNull(fix(Langs.RU, "привет"))
        assertNull(fix(Langs.RU, "прив"))
    }
}
