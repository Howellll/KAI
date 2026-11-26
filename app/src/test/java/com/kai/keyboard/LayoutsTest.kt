package com.kai.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
    private fun bottom(alt: Boolean, globe: Boolean) =
        Layouts.letters(Langs.RU, alt, globe).rows[3].joinToString(" ") { it.label }

    @Test
    fun bottomRow() {
        assertEquals("123 lang ,   . enter", bottom(alt = false, globe = true))
        assertEquals("?123 , lang   . enter", bottom(alt = true, globe = true))
        assertEquals("?123 ,   . enter", bottom(alt = true, globe = false))
        assertEquals("123 ,   . enter", bottom(alt = false, globe = false))
    }

    @Test
    fun rowsFitWidth() {
        val all = listOf(true, false).flatMap { g -> listOf(true, false).map { Layouts.letters(Langs.RU, g, it) } } +
            listOf(true, false).flatMap { g -> listOf(true, false).flatMap { p ->
                listOf(true, false).map { Layouts.symbols(Langs.RU, p, g, it) } } } +
            listOf(true, false).map { Layouts.emojiSearch(Langs.RU, it) }
        for (l in all) for (r in l.rows) assertEquals(l.units, r.sumOf { it.width.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun clipboardKeyOnlyWhenEnabled() {
        fun has(on: Boolean) = Layouts.symbols(Langs.RU, false, false, on).keys.any { it.code == Code.CLIPBOARD }
        assertEquals(true, has(true))
        assertEquals(false, has(false))
    }

    /** Кнопка буфера — и в нижнем ряду букв, рядом с 🌐, и только когда история (или сейф) доступны. */
    @Test
    fun lettersBottomClipboard() {
        fun bottom(alt: Boolean, globe: Boolean, clip: Boolean) =
            Layouts.letters(Langs.RU, alt, globe, clipboard = clip).rows[3].joinToString(" ") { it.label }
        // по умолчанию — как раньше, без кнопки буфера
        assertEquals("123 lang ,   . enter", bottom(alt = false, globe = true, clip = false))
        assertEquals("?123 , lang   . enter", bottom(alt = true, globe = true, clip = false))
        assertEquals("?123 ,   . enter", bottom(alt = true, globe = false, clip = false))
        // кнопка встаёт сразу за 🌐, 🌐 спрятан — за «123»
        assertEquals("123 lang clip ,   . enter", bottom(alt = false, globe = true, clip = true))
        assertEquals("?123 , lang clip   . enter", bottom(alt = true, globe = true, clip = true))
        assertEquals("?123 clip ,   . enter", bottom(alt = true, globe = false, clip = true))
        // стиль 3: 🌐 (и буфер следом) между точкой и Enter
        fun s3(clip: Boolean) = Layouts.letters(Langs.RU, true, true, style3 = true, clipboard = clip).rows[3]
            .joinToString(" ") { it.label }
        assertEquals("?123 ,   . lang enter", s3(false))
        assertEquals("?123 ,   . lang clip enter", s3(true))
    }

    /** С кнопкой буфера нижний ряд всё той же ширины — пробел отдаёт ей место. */
    @Test
    fun lettersBottomClipboardFits() {
        for (alt in listOf(true, false)) for (globe in listOf(true, false)) for (s3 in listOf(true, false)) {
            val l = Layouts.letters(Langs.RU, alt, globe, style3 = s3, clipboard = true)
            for (r in l.rows) assertEquals(l.units, r.sumOf { it.width.toDouble() }.toFloat(), 0.01f)
        }
    }

    @Test
    fun longPressSymbols() {
        for (lang in listOf(Langs.RU, Langs.EN)) {
            val off = Layouts.letters(lang)
            val on = Layouts.letters(lang, symbols = true)
            // Без настройки — как раньше; с ней — у букв 2-3 рядов первым идёт символ, ь по-прежнему даёт ъ.
            for ((a, b) in off.letterKeys.zip(on.letterKeys)) {
                assertEquals(a.label, b.label)
                assertTrue(b.label, b.popup.containsAll(a.popup))
                if (b.ch == 'ь') assertEquals("ъ", b.popup[0])
            }
            val symbolKeys = on.rows.drop(1).take(2).flatten().filter { it.kind == KeyKind.LETTER && it.ch != 'ь' }
            for (k in symbolKeys) assertTrue(k.label, !k.popup[0][0].isLetterOrDigit())
            val all = symbolKeys.map { it.popup[0] }
            assertEquals(all.toSet().size, all.size) // символы не повторяются
            assertTrue(all.containsAll(listOf("@", "?", "!", "(", ")")))
        }
    }

    @Test
    fun numberRow() {
        for (lang in listOf(Langs.RU, Langs.EN)) {
            val l = Layouts.letters(lang, numberRow = true)
            assertEquals(5, l.rows.size)
            assertEquals("1234567890", l.rows[0].joinToString("") { it.label })
            assertEquals(l.units, l.rows[0].sumOf { it.width.toDouble() }.toFloat(), 0.01f)
            // цифр на долгом нажатии больше нет, ё и акценты остались
            assertTrue(l.letterKeys.none { k -> k.popup.any { it[0].isDigit() } })
            if (lang == Langs.RU) assertEquals(listOf("ё"), l.letterKeys.first { it.ch == 'е' }.popup)
        }
    }

    /** С рядом цифр клавиатура одной высоты во всех режимах: буквы, символы, эмодзи. */
    @Test
    fun numberRowKeepsHeight() {
        val top = 100f; val rowH = 150f; val numH = 115f
        val bottoms = listOf(
            Layouts.letters(Langs.RU, numberRow = true),
            Layouts.symbols(Langs.RU, false, false),
            Layouts.emoji(Langs.RU),
        ).map { l ->
            KeyGeometry.place(l, 0f, 1080f, 0f, top, rowH, 8f, 16f, 30f, numH)
            l.rows.last().maxOf { it.y + it.h }
        }
        // нижний ряд в одном месте; с viewH=0 высота последнего ряда = keysBottom
        for (b in bottoms) assertEquals(top + numH + rowH * 4, b, 0.5f)
    }

    /** Ряд цифр + символы: символы и на верхнем ряду, все разные, ё по-прежнему первая на е. */
    @Test
    fun topRowSymbolsWithNumberRow() {
        for (lang in listOf(Langs.RU, Langs.EN)) {
            val l = Layouts.letters(lang, symbols = true, numberRow = true)
            val top = l.rows[1]
            assertTrue(top.all { k -> k.popup.any { !it[0].isLetterOrDigit() } })
            val all = l.letterKeys.flatMap { k -> k.popup.filter { !it[0].isLetterOrDigit() } }
            assertEquals(all.toSet().size, all.size)
            if (lang == Langs.RU) assertEquals(listOf("ё", "["), l.letterKeys.first { it.ch == 'е' }.popup)
            // без ряда цифр верхний ряд — по-прежнему цифры
            val digits = Layouts.letters(lang, symbols = true).rows[0].flatMap { k -> k.popup.filter { it[0].isDigit() } }
            assertEquals("1234567890", digits.joinToString(""))
        }
    }

    /** Стиль 3: 🌐 между точкой и Enter, ширина рядов та же. */
    @Test
    fun style3BottomRow() {
        fun b(globe: Boolean) = Layouts.letters(Langs.RU, true, globe, style3 = true).rows[3].joinToString(" ") { it.label }
        assertEquals("?123 ,   . lang enter", b(true))
        assertEquals("?123 ,   . enter", b(false))
        for (lang in listOf(Langs.RU, Langs.EN)) for (g in listOf(true, false)) for (n in listOf(true, false)) {
            val s2 = Layouts.letters(lang, true, g, true, n)
            val s3 = Layouts.letters(lang, true, g, true, n, style3 = true, doubles = true)
            for ((a, b) in s2.rows.zip(s3.rows))
                assertEquals(a.sumOf { it.width.toDouble() }, b.sumOf { it.width.toDouble() }, 0.01)
        }
        val ru = Layouts.letters(Langs.RU, true, true, style3 = true)
        for (r in ru.rows) assertEquals(ru.units, r.sumOf { it.width.toDouble() }.toFloat(), 0.01f)
    }

    /** Стиль 3: на русской раскладке цифры с 5 — на н…х, на английской — как были. */
    @Test
    fun style3Digits() {
        fun digits(lang: Lang, s3: Boolean) = Layouts.letters(lang, true, style3 = s3).rows[0]
            .joinToString(" ") { k -> k.label + k.popup.filter { it[0].isDigit() }.joinToString("") }
        assertEquals("й1 ц2 у3 к4 е н5 г6 ш7 щ8 з9 х0", digits(Langs.RU, true))
        assertEquals("й1 ц2 у3 к4 е5 н6 г7 ш8 щ9 з0 х", digits(Langs.RU, false))
        assertEquals(digits(Langs.EN, false), digits(Langs.EN, true))
        // ё осталась на е
        assertEquals(listOf("ё"), Layouts.letters(Langs.RU, true, style3 = true).letterKeys.first { it.ch == 'е' }.popup)
    }

    /** Стиль 3: № вторым символом на клавише с #, только при символах долгим нажатием. */
    @Test
    fun style3Numero() {
        assertEquals(listOf("#", "№"), Layouts.letters(Langs.RU, true, symbols = true, style3 = true).letterKeys.first { it.ch == 'ы' }.popup)
        assertEquals(listOf("#", "№"), Layouts.letters(Langs.EN, true, symbols = true, style3 = true).letterKeys.first { it.ch == 's' }.popup.take(2))
        assertEquals(listOf("#"), Layouts.letters(Langs.RU, true, symbols = true).letterKeys.first { it.ch == 'ы' }.popup)
        assertTrue(Layouts.letters(Langs.RU, true, style3 = true).letterKeys.first { it.ch == 'ы' }.popup.isEmpty())
    }

    /** Двойные символы: пара вторым пунктом во всех стилях, только с символами долгим нажатием. */
    @Test
    fun doubleSymbols() {
        for (lang in listOf(Langs.RU, Langs.EN)) for (style in 0..2) {
            fun l(sym: Boolean, d: Boolean) = Layouts.letters(lang, style >= 1, symbols = sym, style3 = style == 2, doubles = d)
            val on = l(true, true).letterKeys.flatMap { it.popup }
            assertTrue(on.containsAll(listOf("**", "()", "\"\"", "''")))
            if (lang == Langs.RU) assertTrue(on.contains("«»"))
            assertTrue(l(true, false).letterKeys.flatMap { it.popup }.none { it in Layouts.DOUBLES })
            assertTrue(l(false, true).letterKeys.flatMap { it.popup }.none { it in Layouts.DOUBLES })
        }
        val star = Layouts.letters(Langs.RU, symbols = true, doubles = true).letterKeys.first { it.ch == 'я' }
        assertEquals(listOf("*", "**"), star.popup)
    }
}
