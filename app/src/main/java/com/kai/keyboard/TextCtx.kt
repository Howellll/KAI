package com.kai.keyboard

/** Разбор текста перед курсором: текущее слово и контекст для него. */
object TextCtx {
    fun isWordChar(c: Char) = c.isLetter() || c == '-' || c == '\'' || c == '’'

    fun currentWord(before: String): String {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        var w = before.substring(i)
        while (w.isNotEmpty() && !w[0].isLetter()) w = w.substring(1)
        return w
    }

    /**
     * Контекст для текущего слова: два предыдущих слова через пробел («я тебя»), одно предыдущее,
     * «<s>» — начало предложения, «,» — после запятой, null — непонятно (цифры, символы).
     * Перед словом может стоять «<s>» или «,»: «<s> привет» — «привет» в начале предложения.
     */
    fun contextKey(before: String, wordStart: Int): String? {
        val (w1, at) = prevToken(before, wordStart) ?: return null
        if (w1 == "<s>" || w1 == ",") return w1
        val w2 = prevToken(before, at)?.first ?: return w1
        return "$w2 $w1"
    }

    fun lastWord(key: String): String = key.substringAfterLast(' ')

    /** Одно предыдущее слово (или «<s>», «,») — и где оно начинается. */
    private fun prevToken(before: String, wordStart: Int): Pair<String, Int>? {
        var i = wordStart
        while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\u00A0')) i--
        if (i == 0) return if (before.length < 64) "<s>" to 0 else null
        val c = before[i - 1]
        if (c in ".!?…\n") return "<s>" to i
        if (c == ',') return "," to i
        if (i == wordStart) return null
        val end = i
        while (i > 0 && isWordChar(before[i - 1])) i--
        val w = before.substring(i, end).trim('-', '\'')
        return if (w.isEmpty()) null else normalize(w) to i
    }
}
