package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сообщение об ошибке на клавиатуре: его срок жизни, кадр проявления и подгонка положения —
 * чтобы плашка не закрывала клавиши нижнего ряда и пробел.
 */
class ToastSpecTest {

    private val total get() = ToastSpec.APPEAR_MS + ToastSpec.HOLD_MS + ToastSpec.FADE_MS

    // ---------- срок жизни и анимация ----------

    @Test
    fun appearsQuicklyAndDisappearsOnTime() {
        // Сразу после показа сообщение ещё не видно, к концу проявления — видно целиком.
        assertEquals(1f, ToastSpec.cover(0L), 0.001f)
        assertEquals(0f, ToastSpec.cover(ToastSpec.APPEAR_MS), 0.001f)
        assertEquals(255, ToastSpec.alpha(ToastSpec.APPEAR_MS + ToastSpec.HOLD_MS))
        // Держится весь срок, потом пропадает.
        assertFalse(ToastSpec.gone(ToastSpec.APPEAR_MS + ToastSpec.HOLD_MS))
        assertFalse(ToastSpec.gone(total - 1))
        assertTrue(ToastSpec.gone(total))
        assertEquals(0, ToastSpec.alpha(total))
    }

    @Test
    fun fadeGoesOnlyDown() {
        var from = 255
        for (t in (ToastSpec.APPEAR_MS + ToastSpec.HOLD_MS)..total step 20) {
            val a = ToastSpec.alpha(t)
            assertTrue("прозрачность выросла: $a после $from", a <= from)
            from = a
        }
    }

    // ---------- положение: клавиши под сообщением ----------

    /** Клавиатура: полоса сверху (44), четыре ряда по 230, нижний ряд и пробел — последние (3 и 4). */
    private val kbTop = 44f
    private val kbBottom = 44f + 4 * 230f
    private val plankH = 40f
    private val width = 1080f

    /** Раскладывает каждый ряд во всю ширину: клавиши получают ряд и видимые координаты от нуля клавиатуры. */
    private fun place(l: KbLayout, rowH: Float = 230f): List<Key> {
        val out = ArrayList<Key>()
        for ((r, row) in l.rows.withIndex()) {
            val w = width / row.sumOf { it.width.toDouble() }.toFloat()
            var x = 0f
            for (k in row) {
                k.row = r
                k.vx = x; k.vw = k.width * w
                k.vy = kbTop + r * rowH; k.vh = rowH
                x += k.vw
                out.add(k)
            }
        }
        return out
    }

    private fun fit(keys: List<Key>, startTop: Float) =
        KeyboardView.FitToast.fit(KeyboardView.ToastPlank(startTop, plankH), keys, kbTop, kbBottom)

    private fun hiddenBottom(keys: List<Key>, plankTop: Float) =
        ToastSpec.hidden(ToastSpec.bottomRow(keys), kbTop, plankTop, plankH)

    @Test
    fun messageDoesNotCoverBottomRow() {
        val keys = place(Layouts.letters(Langs.RU))
        val start = kbBottom - 60f - plankH
        // Начальное положение закрывает нижний ряд — подгонка поднимает сообщение над ним.
        assertTrue("для проверки нужно, чтобы сообщение перекрывало нижний ряд", hiddenBottom(keys, start).isNotEmpty())
        val fitted = fit(keys, start)
        assertTrue("сообщение не поднялось: ${fitted.top} против $start", fitted.top < start)
        assertTrue("сообщение поднялось выше клавиатуры: ${fitted.top}", fitted.top >= kbTop)
        assertTrue("сообщение закрыло нижний ряд: ${hiddenBottom(keys, fitted.top)}",
            hiddenBottom(keys, fitted.top).isEmpty())
    }

    @Test
    fun lettersWithNumberRowKeepBottomRowClear() {
        // Ряд цифр — сверху: нижний ряд по-прежнему последний, подгонка работает так же.
        val keys = place(Layouts.letters(Langs.RU, numberRow = true))
        val start = kbBottom - 60f - plankH
        val fitted = fit(keys, start)
        assertTrue(fitted.top >= kbTop)
        assertTrue(hiddenBottom(keys, fitted.top).isEmpty())
    }

    @Test
    fun messageNeverLeavesTheKeyboard() {
        // Под сообщением только нижний ряд, а прочих рядов нет: выше подниматься некуда — сообщение
        // не уезжает в чужое приложение, а остаётся над нижним рядом.
        val onlyBottom = KbLayout(Mode.CLIPBOARD, listOf(Layouts.letters(Langs.RU).rows[3]), 10f)
        val keys = place(onlyBottom)
        val fitted = fit(keys, kbTop - 100f)
        assertTrue("сообщение поднялось выше клавиатуры: ${fitted.top}", fitted.top >= kbTop)
        assertTrue("сообщение уехало ниже клавиатуры: ${fitted.top}", fitted.top + plankH <= kbBottom)
        assertTrue("сообщение закрыло нижний ряд: ${hiddenBottom(keys, fitted.top)}",
            hiddenBottom(keys, fitted.top).isEmpty())
    }

    @Test
    fun messageStandsStillWhenNothingIsInTheWay() {
        val keys = place(Layouts.letters(Langs.RU))
        // Плашка-крошка в самом низу: до нижнего ряда не дотягивается — сдвигать нечего.
        val fitted = fit(keys, kbBottom - 6f)
        assertEquals(kbBottom - 6f, fitted.top, 0.001f)
    }

    @Test
    fun panelAndAiHaveNothingToHide() {
        // В панели буфера и в форме ИИ — один нижний ряд: сообщение встаёт над ним, не накрывая его.
        val clip = place(Layouts.clipboard(Langs.RU))
        val fitted = fit(clip, kbBottom - 60f - plankH)
        assertTrue(fitted.top >= kbTop)
        assertTrue(hiddenBottom(clip, fitted.top).isEmpty())
        // У формы ИИ клавиш нет совсем — сдвигать нечего, сообщение остаётся где просили.
        assertNotNull(fit(place(Layouts.ai()), kbBottom - 60f - plankH))
    }
}
