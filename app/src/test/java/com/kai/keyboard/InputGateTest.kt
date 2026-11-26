package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ввод во время распознавания свайпа не теряется и не меняет порядок. */
class InputGateTest {
    private val errors = ArrayList<Exception>()
    private val gate = InputGate { errors.add(it) }
    private val text = StringBuilder()
    private var pendingWord: String? = null

    private fun key(c: Char) { if (gate.defer { key(c) }) return; text.append(c) }

    private fun swipe(word: String) {
        if (gate.defer { swipe(word) }) return
        pendingWord = word
        gate.begin()
    }

    /** Фон досчитал: вставляем слово, затем отложенное. */
    private fun decoded() {
        text.append(pendingWord).append(' ')
        pendingWord = null
        gate.end()
    }

    @Test
    fun typingDuringDecodeKeepsOrder() {
        swipe("привет")
        key('к'); key('а'); key('к')
        assertEquals("", text.toString()) // главный поток не ждал и ничего не вставил вперёд слова
        decoded()
        assertEquals("привет как", text.toString())
        key('!')
        assertEquals("привет как!", text.toString())
    }

    @Test
    fun swipeInsideQueueWaitsAgain() {
        swipe("раз")
        key('x'); swipe("два"); key('y')
        decoded()
        assertEquals("раз x", text.toString()) // «y» ждёт второй свайп
        assertTrue(gate.busy)
        decoded()
        assertEquals("раз xдва y", text.toString())
        assertTrue(!gate.busy)
    }

    @Test
    fun resetDropsQueueForOldField() {
        swipe("раз"); key('x')
        gate.reset()
        key('y')
        assertEquals("y", text.toString())
    }

    @Test
    fun failingActionDoesNotBreakQueue() {
        swipe("раз")
        key('a')
        gate.defer { error("boom") }
        key('b')
        decoded()
        assertEquals("раз ab", text.toString()) // остальное выполнилось по порядку
        assertEquals(1, errors.size)
        assertTrue(!gate.busy)
        key('c') // очередь пуста — ввод идёт напрямую, а не копится навсегда
        assertEquals("раз abc", text.toString())
    }

    @Test
    fun watchdogReleaseLetsInputThrough() {
        swipe("завис")
        key('a'); key('b')
        pendingWord = null
        gate.end() // сторож: слово потеряно, но ввод не застрял
        assertEquals("ab", text.toString())
        key('c')
        assertEquals("abc", text.toString())
    }

    @Test
    fun replayingIsTrueOnlyForQueuedInput() {
        val seen = ArrayList<Boolean>()
        fun probe() { if (gate.defer { probe() }) return; seen.add(gate.replaying) }
        probe()
        swipe("раз"); probe()
        decoded()
        probe()
        assertEquals(listOf(false, true, false), seen)
    }
}
