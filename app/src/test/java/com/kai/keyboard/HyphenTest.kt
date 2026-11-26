package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Слова с дефисом при обычном наборе: дефис можно не набирать, а набранный — не «исправлять». */
class HyphenTest {
    private val e = TypingSim.freshEngine(TypingSim.loadRu())
    private fun s(w: String) = e.suggest(Langs.RU, w, null, true, false)!!

    @Test
    fun withoutHyphenIsCorrected() {
        for ((typed, want) in listOf("ктото" to "кто-то", "помоему" to "по-моему", "изза" to "из-за",
                "вопервых" to "во-первых", "какойто" to "какой-то", "чтонибудь" to "что-нибудь"))
            assertEquals(typed, want, s(typed).autocorrect)
        for (w in listOf("потом", "тоже", "также", "кстати", "вовсе", "так", "как")) assertNull(w, s(w).autocorrect)
    }

    @Test
    fun typedHyphenIsKept() {
        for (w in listOf("кто-", "во-", "по-", "из-")) {
            val r = s(w)
            assertNull(w, r.autocorrect)
            assertTrue("$w → ${r.items.map { it.text }}", r.items.first().text.startsWith(w))
        }
    }

    @Test
    fun hyphenWordsAreOffered() {
        for ((typed, want) in listOf("во" to "во-первых", "из" to "из-за", "вопер" to "во-первых", "кто" to "кто-то"))
            assertTrue("$typed → ${s(typed).items.map { it.text }}", s(typed).items.any { it.text == want })
        for (w in listOf("так", "как", "все", "то")) assertTrue(w, s(w).items.none { it.text.contains('-') })
    }
}
