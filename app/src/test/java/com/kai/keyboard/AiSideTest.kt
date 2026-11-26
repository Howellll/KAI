package com.kai.keyboard

import org.junit.Assert.*
import org.junit.Test

class AiSideTest {
    @Test fun capacityFitsButtonsWithGaps() {
        assertEquals(0, AiSideLayout.capacity(30f, 40f, 8f))
        assertEquals(1, AiSideLayout.capacity(40f, 40f, 8f))
        assertEquals(3, AiSideLayout.capacity(136f, 40f, 8f)) // 3*40 + 2*8
        assertEquals(2, AiSideLayout.capacity(135f, 40f, 8f))
    }

    @Test fun singlePlusIsInTheCentre() {
        val t = AiSideLayout.tops(100f, 300f, 40f, 8f, 1)
        assertEquals(180f, t[0], 0.001f) // центр 200
    }

    @Test fun stackGrowsBothWaysAndStaysInside() {
        val t = AiSideLayout.tops(100f, 300f, 40f, 8f, 2)
        assertTrue(t[0] < 180f)            // шорткат ушёл вверх
        assertTrue(t[1] > 180f)            // «+» съехал вниз
        assertEquals(48f, t[1] - t[0], 0.001f)
        val n = AiSideLayout.capacity(200f, 40f, 8f)
        val full = AiSideLayout.tops(100f, 300f, 40f, 8f, n)
        assertTrue(full.first() >= 100f)
        assertTrue(full.last() + 40f <= 300f + 0.001f)
    }

    @Test fun shortcutIdsAreCleaned() {
        assertEquals(listOf("clip", "safe"), AiShortcut.parse("clip, safe,clip,bogus,"))
        assertEquals(emptyList<String>(), AiShortcut.parse(null))
        assertEquals("clip,left", AiShortcut.join(listOf("clip", "x", "left", "clip")))
        assertEquals(6, AiShortcut.ALL.distinct().size)
    }
}
