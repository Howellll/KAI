package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Где стоит угловая кнопка полосы подсказок (кнопка ИИ или 😊) и как она подписана. */
class StripSideTest {

    @Test
    fun settingDecides() {
        assertFalse(StripSide.right(oneHand = 0, prefRight = false, panel = false))
        assertTrue(StripSide.right(oneHand = 0, prefRight = true, panel = false))
    }

    @Test
    fun oneHandWinsOverSetting() {
        // Клавиши у левого края — кнопка тоже слева, что бы ни стояло в настройке.
        assertFalse(StripSide.right(oneHand = 1, prefRight = true, panel = false))
        // Клавиши у правого края — кнопка справа, даже если настройка «Слева».
        assertTrue(StripSide.right(oneHand = 2, prefRight = false, panel = false))
    }

    @Test
    fun panelHasNoStrip() {
        // Полосы подсказок в панелях нет — кнопка не показывается ни слева, ни справа.
        assertFalse(StripSide.right(oneHand = 0, prefRight = true, panel = true))
        assertFalse(StripSide.right(oneHand = 2, prefRight = true, panel = true))
    }

    @Test
    fun labelFollowsKeyboardLanguage() {
        assertEquals("ИИ", StripSide.label(Langs.RU))
        assertEquals("AI", StripSide.label(Langs.EN))
    }
}
