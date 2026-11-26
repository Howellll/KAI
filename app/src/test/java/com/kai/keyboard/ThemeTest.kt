package com.kai.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test

/** Текст на клавишах читается в каждой теме и на любом акцентном цвете. */
class ThemeTest {
    private fun contrast(a: Int, b: Int): Double {
        val la = Accent.luminance(a); val lb = Accent.luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun keyTextIsReadable() {
        for (t in Theme.ALL) {
            val onKey = contrast(t.text, t.key)
            val onFn = contrast(t.text, t.fn)
            println("${t.id}: key %.1f, fn %.1f, dim %.1f".format(onKey, onFn, contrast(t.textDim, t.bg)))
            assertTrue(t.id, onKey >= 4.5 && onFn >= 4.5)
            assertTrue(t.id, contrast(t.textDim, t.bg) >= 2.5)
        }
    }

    @Test
    fun textOnAccentIsReadable() {
        for (c in Accent.PRESETS + listOf(0xFFFFFF00.toInt(), 0xFF000080.toInt(), 0xFF808080.toInt())) {
            val r = contrast(Accent.onAccent(c), c or 0xFF000000.toInt())
            println("accent %06X: %.1f".format(c and 0xFFFFFF, r))
            assertTrue("%06X".format(c and 0xFFFFFF), r >= 3.0)
        }
    }
}
