package com.kai.keyboard

object Code {
    const val SHIFT = -1
    const val DELETE = -2
    const val SYMBOLS = -3
    const val LETTERS = -4
    const val LANG = -5
    const val ENTER = -6
    const val SYMBOLS2 = -7
    const val EMOJI = -8
    const val CLIPBOARD = -9
    const val CLIP_CLEAR = -10
    const val SAFE_OK = -12
    const val SPACE = 32
}

enum class KeyKind { LETTER, CHAR, FUNCTION, SPACE, ENTER }

enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, EMOJI, EMOJI_SEARCH, CLIPBOARD, SAFE, SAFE_PIN, NUMBER, PHONE, AI }

class Key(
    val label: String,
    val code: Int,
    val kind: KeyKind,
    val width: Float = 1f,
    val popup: List<String> = emptyList(),
) {
    var vx = 0f; var vy = 0f; var vw = 0f; var vh = 0f

    /**
     * Ряд клавиши (0 — верхний), а начинается он с ряда цифр, если тот вынесен отдельно:
     * нужен там, где важно, попадает клавиша в нижний ряд или нет (плашка сообщения над клавишами).
     */
    var row = 0

    val cx: Float get() = vx + vw / 2f
    val cy: Float get() = vy + vh / 2f
    /**
     * Буква клавиши. Ярлык всегда непуст (даже у пробела — « »), но берём безопасно: пустой ярлык
     * раньше валил приложение IndexOutOfBoundsException прямо здесь.
     */
    val ch: Char get() = label.firstOrNull() ?: ' '

    fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h

    /** Зона касания (тайлы без зазоров, покрывают всю клавиатуру). */
    var x = 0f; var y = 0f; var w = 0f; var h = 0f
}

class KbLayout(val mode: Mode, val rows: List<List<Key>>, val units: Float) {
    val keys = rows.flatten()
    val letterKeys = keys.filter { it.kind == KeyKind.LETTER }
}

object Layouts {
    private val RU_ROWS = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
    private val EN_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private val RU_POPUPS = mapOf(
        'й' to "1", 'ц' to "2", 'у' to "3", 'к' to "4", 'е' to "ё5", 'н' to "6",
        'г' to "7", 'ш' to "8", 'щ' to "9", 'з' to "0", 'ь' to "ъ",
    )
    /** Стиль 3: цифры с 5 сдвинуты на одну клавишу вправо — 5 на н, 6 на г … 0 на х. */
    private val RU_POPUPS_S3 = mapOf(
        'й' to "1", 'ц' to "2", 'у' to "3", 'к' to "4", 'е' to "ё", 'н' to "5",
        'г' to "6", 'ш' to "7", 'щ' to "8", 'з' to "9", 'х' to "0", 'ь' to "ъ",
    )
    private val EN_POPUPS = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3éèêë", 'r' to "4", 't' to "5", 'y' to "6",
        'u' to "7úüû", 'i' to "8íïî", 'o' to "9óöô", 'p' to "0", 'a' to "áàâäã",
        'c' to "ç", 'n' to "ñ", 's' to "ß",
    )

    /**
     * Символы долгим нажатием на буквах второго и третьего рядов (по желанию, как у Gboard):
     * знакомые места — скобки рядом, кавычки «ёлочки» на б/ю. ь оставлен за ъ.
     */
    private val RU_SYMBOLS = mapOf(
        'ф' to "@", 'ы' to "#", 'в' to "₽", 'а' to "_", 'п' to "&", 'р' to "-", 'о' to "+",
        'л' to "(", 'д' to ")", 'ж' to ":", 'э' to ";",
        'я' to "*", 'ч' to "\"", 'с' to "'", 'м' to "/", 'и' to "?", 'т' to "!", 'б' to "«", 'ю' to "»",
    )
    private val EN_SYMBOLS = mapOf(
        'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&", 'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
        'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";", 'n' to "!", 'm' to "?",
    )

    /** Верхний ряд — только когда цифры вынесены в отдельный ряд (иначе тут цифры долгим нажатием). */
    private val RU_TOP_SYMBOLS = mapOf(
        'й' to "%", 'ц' to "^", 'у' to "~", 'к' to "|", 'е' to "[", 'н' to "]",
        'г' to "<", 'ш' to ">", 'щ' to "{", 'з' to "}", 'х' to "=",
    )
    private val EN_TOP_SYMBOLS = mapOf(
        'q' to "%", 'w' to "^", 'e' to "~", 'r' to "|", 't' to "[", 'y' to "]",
        'u' to "<", 'i' to ">", 'o' to "{", 'p' to "}",
    )

    private const val DEL_EXTRA = 0.1f

    private val COMMA_POPUP = listOf("!", "?", ";", ":", "-", "\"", "(", ")")
    private val PERIOD_POPUP = listOf("…", "!", "?", "@", "#")

    fun neighbors(lang: Lang): Set<Long> {
        val rows = if (lang == Langs.RU) RU_ROWS else EN_ROWS
        val offsets = if (lang == Langs.RU) floatArrayOf(0f, 0f, 1f) else floatArrayOf(0f, 0.5f, 1.5f)
        val pos = ArrayList<Triple<Char, Int, Float>>()
        rows.forEachIndexed { r, s -> s.forEachIndexed { i, c -> pos.add(Triple(c, r, offsets[r] + i)) } }
        val out = HashSet<Long>()
        for (a in pos) for (b in pos) {
            if (a.first == b.first) continue
            val dr = kotlin.math.abs(a.second - b.second)
            val dx = kotlin.math.abs(a.third - b.third)
            if ((dr == 0 && dx <= 1.01f) || (dr == 1 && dx <= 1.01f)) out.add(pair(a.first, b.first))
        }
        return out
    }

    fun pair(a: Char, b: Char): Long = (a.code.toLong() shl 32) or b.code.toLong()

    /** Двойные символы: выбрав их из попапа, получаешь пару, а курсор встаёт между ними. */
    val DOUBLE_PAIRS: Map<Char, String> = mapOf(
        '*' to "**", '(' to "()", ')' to "()", '"' to "\"\"", '\'' to "''", '«' to "«»", '»' to "«»",
    )
    val DOUBLES: Set<String> = DOUBLE_PAIRS.values.toSet()

    private fun popupItems(c: Char, doubled: Boolean): List<String> {
        val pair = if (doubled) DOUBLE_PAIRS[c] else null
        return if (pair != null) listOf(c.toString(), pair) else listOf(c.toString())
    }

    private fun letter(c: Char, popup: List<String>?) =
        Key(c.toString(), c.code, KeyKind.LETTER, 1f, popup ?: emptyList())

    private fun chr(s: String, w: Float = 1f, popup: List<String> = emptyList()) =
        Key(s, s.codePointAt(0), KeyKind.CHAR, w, popup)

    private fun fn(label: String, code: Int, w: Float) = Key(label, code, KeyKind.FUNCTION, w)
    private fun space(w: Float) = Key(" ", Code.SPACE, KeyKind.SPACE, w)
    private fun enter(w: Float) = Key("enter", Code.ENTER, KeyKind.ENTER, w)

    /** Долгое нажатие на 🌐 (или на 123, если 🌐 спрятан): выбор клавиатуры, одной рукой слева, во всю ширину, справа. */
    val GLOBE_POPUP = listOf("⌨", "◀", "▣", "▶")

    /**
     * @param alt альтернативный нижний ряд: «?123 , 🌐 пробел . ⏎» (классический — «123 🌐 , пробел . ⏎»)
     * @param globe показывать 🌐 (выключено — язык меняется свайпом по пробелу)
     */
    /**
     * @param symbols символы долгим нажатием на буквах (символ — первым в списке и в подсказке на клавише)
     * @param numberRow ряд цифр над буквами; тогда цифр на долгом нажатии верхнего ряда нет
     */
    fun letters(lang: Lang, alt: Boolean = false, globe: Boolean = true, symbols: Boolean = false,
                numberRow: Boolean = false, style3: Boolean = false, doubles: Boolean = false,
                clipboard: Boolean = false): KbLayout {
        val ru = lang == Langs.RU
        val rows = if (ru) RU_ROWS else EN_ROWS
        // Стиль 3: на русской раскладке цифры от 5 сдвинуты на одну букву вправо (5 на н ... 0 на х).
        val basePopups = if (ru && style3) RU_POPUPS_S3 else if (ru) RU_POPUPS else EN_POPUPS
        val base = basePopups.let { m ->
            if (!numberRow) m else m.mapValues { (_, v) -> v.filter { !it.isDigit() } }.filterValues { it.isNotEmpty() }
        }
        var sym = (if (ru) RU_SYMBOLS else EN_SYMBOLS) +
            (if (numberRow) (if (ru) RU_TOP_SYMBOLS else EN_TOP_SYMBOLS) else emptyMap())
        // Стиль 3: № вторым символом на той же клавише, где #.
        if (style3) sym = sym + (if (ru) 'ы' to "#№" else 's' to "#№")
        // Символ — первым; но русские ё и ъ важнее символа и остаются первыми.
        val pop: Map<Char, List<String>> = (base.keys + (if (symbols) sym.keys else emptySet())).associateWith {
            val b = base[it] ?: ""
            val s = if (symbols) sym[it] ?: "" else ""
            val joined = if (ru && b.isNotEmpty()) b + s else s + b
            joined.flatMap { c -> popupItems(c, symbols && doubles && c in s) }
        }
        val units = if (ru) 11f else 10f
        val side = (units - rows[2].length) / 2f
        // Ярлык клавиши — от буквы; буква без ярлыка (такого быть не должно) получила бы пустое имя
        // и упала бы на Key.ch. Пустой ярлык заменяем пробелом — раскладка важнее строгости.
        val r1 = rows[0].map { letter(it, pop[it]) }
        val r2 = rows[1].map { letter(it, pop[it]) }
        // ⌫ нажимают куда чаще, чем ⇧ (заглавные после точки ставятся сами), поэтому
        // ⌫ шире за счёт ⇧, а буквы не трогаем.
        val r3 = listOf(fn("shift", Code.SHIFT, side - DEL_EXTRA)) + rows[2].map { letter(it, pop[it]) } +
            fn("del", Code.DELETE, side + DEL_EXTRA)
        val symbols = Key(if (alt) "?123" else "123", Code.SYMBOLS, KeyKind.FUNCTION, 1.25f,
            if (globe) emptyList() else GLOBE_POPUP)
        val lang = Key("lang", Code.LANG, KeyKind.FUNCTION, 1f, GLOBE_POPUP)
        // Кнопка истории буфера — та же, что в символьной раскладке: ставим её рядом с 🌐, там же, где он.
        val clip = fn("clip", Code.CLIPBOARD, 1.1f)
        val comma = chr(",", 1.3f, COMMA_POPUP)
        val period = chr(".", 1.3f, PERIOD_POPUP)
        // Стиль 3: 🌐 между точкой и Enter — туда же встаёт и буфер; иначе — сразу за 🌐 (🌐 спрятан — за «123»).
        val clipRight = clipboard && style3 && globe
        val clipLeft = if (clipboard && !clipRight) listOf(clip) else emptyList()
        val left = when {
            !globe || style3 -> listOf(symbols) + clipLeft + listOf(comma)
            alt -> listOf(symbols, comma, lang) + clipLeft
            else -> listOf(symbols, lang) + clipLeft + listOf(comma)
        }
        val right = if (style3 && globe) listOf(period, lang) + (if (clipRight) listOf(clip) else emptyList()) +
            listOf(enter(1.85f)) else listOf(period, enter(1.85f))
        val fixed = (left + right).sumOf { it.width.toDouble() }.toFloat()
        val r4 = left + space(units - fixed) + right
        val digits = if (numberRow) listOf("1234567890".map { chr(it.toString(), units / 10f) }) else emptyList()
        return KbLayout(Mode.LETTERS, digits + listOf(r1, r2, r3, r4), units)
    }

    /** @param clipboard показывать кнопку истории буфера обмена (только если история включена) */
    fun symbols(lang: Lang, page2: Boolean, alt: Boolean, clipboard: Boolean = false): KbLayout =
        if (alt) altSymbols(lang, page2, clipboard) else classicSymbols(lang, page2, clipboard)

    private val CURRENCY_POPUP = listOf("$", "€", "£", "¥", "₸", "₴")
    private val DASH_POPUP = listOf("—", "–", "_", "·")
    private val QUOTE_POPUP = listOf("«", "»", "„", "“", "”")

    private fun symbolsBottom(lang: Lang, units: Float, clipboard: Boolean): List<Key> {
        val left = listOf(fn(lang.abc, Code.LETTERS, 1.3f), fn("emoji", Code.EMOJI, 1.1f)) +
            (if (clipboard) listOf(fn("clip", Code.CLIPBOARD, 1.1f)) else emptyList())
        val fixed = left.sumOf { it.width.toDouble() }.toFloat() + 1.9f
        return left + space(units - fixed) + enter(1.9f)
    }

    /** Альтернативный стиль: «@ # ₽ _ & - + ( ) /», вторая страница — «=\\<». */
    private fun altSymbols(lang: Lang, page2: Boolean, clipboard: Boolean): KbLayout {
        val units = 10f
        val rows = if (!page2) listOf(
            "1234567890".map { chr(it.toString()) },
            listOf(chr("@"), chr("#", popup = listOf("№")), chr("₽", popup = CURRENCY_POPUP), chr("_"), chr("&"),
                chr("-", popup = DASH_POPUP), chr("+", popup = listOf("±")), chr("("), chr(")"),
                chr("/", popup = listOf("\\"))),
            listOf(fn("=\\<", Code.SYMBOLS2, 1.5f)) +
                listOf(chr("*", popup = listOf("†", "★")), chr("\"", popup = QUOTE_POPUP),
                    chr("'", popup = listOf("’", "`")), chr(":"), chr(";"), chr("!", popup = listOf("¡")),
                    chr("?", popup = listOf("¿"))) +
                fn("del", Code.DELETE, 1.5f),
        ) else listOf(
            listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { chr(it) },
            listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { chr(it) },
            listOf(fn("?123", Code.SYMBOLS, 1.5f)) +
                listOf(chr("%", popup = listOf("‰")), chr("©"), chr("®"), chr("™"), chr("✓"),
                    chr("[", popup = listOf("<")), chr("]", popup = listOf(">"))) +
                fn("del", Code.DELETE, 1.5f),
        )
        return KbLayout(if (page2) Mode.SYMBOLS2 else Mode.SYMBOLS, rows + listOf(symbolsBottom(lang, units, clipboard)), units)
    }

    private fun classicSymbols(lang: Lang, page2: Boolean, clipboard: Boolean): KbLayout {
        val units = 10f
        val r1: List<Key>
        val r2: List<Key>
        if (!page2) {
            r1 = "1234567890".map { chr(it.toString()) }
            r2 = listOf(
                chr("-", popup = DASH_POPUP),
                chr("/", popup = listOf("\\")),
                chr(":"), chr(";"), chr("("), chr(")"),
                chr("₽", popup = CURRENCY_POPUP),
                chr("&", popup = listOf("§")),
                chr("@"),
                chr("\"", popup = QUOTE_POPUP),
            )
        } else {
            r1 = listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { chr(it) }
            r2 = listOf("_", "\\", "|", "~", "<", ">", "€", "$", "£", "•").map { chr(it) }
        }
        val r3 = listOf(if (page2) fn("123", Code.SYMBOLS, 1.5f) else fn("#+=", Code.SYMBOLS2, 1.5f)) +
            listOf(".", ",", "?", "!", "'").map { chr(it, 1.4f, if (it == "'") listOf("’", "`") else emptyList()) } +
            fn("del", Code.DELETE, 1.5f)
        return KbLayout(if (page2) Mode.SYMBOLS2 else Mode.SYMBOLS, listOf(r1, r2, r3, symbolsBottom(lang, units, clipboard)), units)
    }

    fun numpad(lang: Lang, phone: Boolean): KbLayout {
        val units = 4f
        val r1 = listOf(chr("1"), chr("2"), chr("3"),
            if (phone) chr("+", popup = listOf("(", ")", "/")) else chr("-", popup = listOf("+", "/")))
        val r2 = listOf(chr("4"), chr("5"), chr("6"),
            if (phone) chr("*", popup = listOf("#", ";")) else chr(",", popup = listOf(":", "%")))
        val r3 = listOf(chr("7"), chr("8"), chr("9"), fn("del", Code.DELETE, 1f))
        val r4 = listOf(fn(lang.abc, Code.LETTERS, 1f), chr("0"),
            if (phone) space(1f) else chr("."), enter(1f))
        return KbLayout(if (phone) Mode.PHONE else Mode.NUMBER, listOf(r1, r2, r3, r4), units)
    }

    fun clipboard(lang: Lang): KbLayout {
        val units = 10f
        // Сейф — неприметным замком в шапке панели, не здесь.
        val row = listOf(fn(lang.abc, Code.LETTERS, 1.5f), space(5f),
            fn("Очистить", Code.CLIP_CLEAR, 2f), fn("del", Code.DELETE, 1.5f))
        return KbLayout(Mode.CLIPBOARD, listOf(row), units)
    }

    /** Сейф открыт: записи (и «Новая запись») — в панели, запереть — замок в шапке; внизу — abc, пробел и ⌫. */
    fun safe(lang: Lang): KbLayout {
        val units = 10f
        val row = listOf(fn(lang.abc, Code.LETTERS, 1.5f), space(7f), fn("del", Code.DELETE, 1.5f))
        return KbLayout(Mode.SAFE, listOf(row), units)
    }

    /**
     * Форма ИИ: вкладки, столбцы моделей и действия — во всей панели. Клавиш нет совсем: буквы, пробел
     * и ⌫ здесь не нужны (вернуться к буквам — кнопка «ИИ» в шапке формы).
     */
    fun ai(): KbLayout = KbLayout(Mode.AI, emptyList(), 10f)

    fun safePin(): KbLayout {
        val units = 3f
        val rows = listOf("123", "456", "789").map { r -> r.map { chr(it.toString()) } } +
            listOf(listOf(fn("del", Code.DELETE, 1f), chr("0"), fn("ОК", Code.SAFE_OK, 1f)))
        return KbLayout(Mode.SAFE_PIN, rows, units)
    }

    fun emojiSearch(lang: Lang, globe: Boolean = true): KbLayout {
        val l = letters(lang, false, globe)
        val left = listOf(fn("emoji", Code.EMOJI, 1.4f)) +
            (if (globe) listOf(Key("lang", Code.LANG, KeyKind.FUNCTION, 1f, GLOBE_POPUP)) else emptyList())
        // Справа от пробела — «Готово»: обратно к обычной клавиатуре, туда, где привычен Enter.
        val done = fn(if (lang == Langs.RU) "Готово" else "Done", Code.LETTERS, 2.2f)
        val fixed = left.sumOf { it.width.toDouble() }.toFloat() + done.width
        return KbLayout(Mode.EMOJI_SEARCH, l.rows.take(3) + listOf(left + space(l.units - fixed) + done), l.units)
    }

    fun emoji(lang: Lang): KbLayout {
        val units = 10f
        val row = listOf(fn(lang.abc, Code.LETTERS, 1.5f), space(7f), fn("del", Code.DELETE, 1.5f))
        return KbLayout(Mode.EMOJI, listOf(row), units)
    }
}

/** Соответствие букв русской и английской раскладок — для исправления «ghbdtn» → «привет». */
object LayoutMap {
    const val PLACE_COST = 0.7f
    const val MAX_COST = 2.2f

    private const val PC_EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val PC_RU = "йцукенгшщзхъфывапролджэячсмитьбюё"

    private val ruToEn = build(Langs.RU, Langs.EN)
    private val enToRu = build(Langs.EN, Langs.RU)

    fun options(from: Lang, c: Char): List<Pair<Char, Float>>? =
        (if (from == Langs.RU) ruToEn else enToRu)[c]

    private fun build(from: Lang, to: Lang): Map<Char, List<Pair<Char, Float>>> {
        val src = centers(from)
        val dst = centers(to)
        val toPitch = 1f / Layouts.letters(to).units
        val out = HashMap<Char, List<Pair<Char, Float>>>()
        for ((c, p) in src) {
            val opts = LinkedHashMap<Char, Float>()
            val pc = if (from == Langs.EN) PC_EN.indexOf(c).takeIf { it >= 0 }?.let { PC_RU[it] }
            else PC_RU.indexOf(c).takeIf { it >= 0 }?.let { PC_EN[it] }
            if (pc != null && to.index(pc) >= 0) opts[pc] = 0f
            for ((d, q) in dst) if (q[1] == p[1] && kotlin.math.abs(q[0] - p[0]) < 0.75f * toPitch) {
                if (d !in opts) opts[d] = PLACE_COST
            }
            if (opts.isNotEmpty()) out[c] = opts.map { it.key to it.value }
        }
        if (from == Langs.RU) out['ё'] = out['е'] ?: emptyList()
        return out
    }

    private fun centers(lang: Lang): Map<Char, FloatArray> {
        val l = Layouts.letters(lang)
        val m = HashMap<Char, FloatArray>()
        for ((r, row) in l.rows.withIndex()) {
            val sum = row.sumOf { it.width.toDouble() }.toFloat()
            var x = (l.units - sum) / 2f
            for (k in row) {
                if (k.kind == KeyKind.LETTER) m[k.ch] = floatArrayOf((x + k.width / 2f) / l.units, r.toFloat())
                x += k.width
            }
        }
        return m
    }
}
