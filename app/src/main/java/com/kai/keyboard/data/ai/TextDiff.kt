package com.kai.keyboard.data.ai

/**
 * Посимвольный diff для предпросмотра ИИ: какие именно места нового текста отличаются от старого.
 * Без Android — проверяется юнит-тестом.
 *
 * Так же сверяется и текст перед заменой: сравнение идёт по символам, а не по кускам вокруг курсора,
 * поэтому «Заменить» работает и когда курсор стоит не в конце текста.
 */
object TextDiff {
    /**
     * Длиннее этого — промежуток считаем изменённым целиком: посимвольная сверка (n·m) была бы тяжёлой,
     * а такие ответы ИИ — это уже переписанный текст.
     */
    private const val MAX_CHARS = 900

    class Span(val start: Int, val end: Int)

    fun same(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    fun changed(old: String, new: String): List<Span> {
        if (new.isEmpty()) return emptyList()
        if (same(old, new)) return emptyList()
        // Общее начало и общий конец отбрасываем сразу: у опечатки и правки слова остаётся пара символов.
        val lim = minOf(old.length, new.length)
        var pre = 0
        while (pre < lim && old[pre] == new[pre]) pre++
        var suf = 0
        while (suf < lim - pre && old[old.length - 1 - suf] == new[new.length - 1 - suf]) suf++
        val a = old.substring(pre, old.length - suf)
        val b = new.substring(pre, new.length - suf)
        if (a.isEmpty()) return listOf(Span(pre, pre + b.length))
        // Пусто справа — только удаление: в новом тексте удалённого места нет — подсвечиваем соседний
        // знак, чтобы место правки было видно (иначе сказать «здесь стало короче» нечем).
        if (b.isEmpty()) return listOf(neighbour(pre, new))
        if (a.length > MAX_CHARS || b.length > MAX_CHARS) return listOf(Span(pre, pre + b.length))

        val n = a.length
        val m = b.length
        val w = m + 1
        val dp = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i * w + j] = if (a[i] == b[j]) dp[(i + 1) * w + j + 1] + 1
            else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
        val same = BooleanArray(m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { same[j] = true; i++; j++ }
                dp[(i + 1) * w + j] >= dp[i * w + j + 1] -> i++
                else -> j++
            }
        }
        val spans = ArrayList<Span>()
        var start = -1
        for (k in 0 until m) {
            if (!same[k]) { if (start < 0) start = k } else if (start >= 0) {
                // Пробел между двумя изменёнными кусками тоже подсвечиваем: так читается как один блок.
                if (b[k].isWhitespace() && k + 1 < m && !same[k + 1]) continue
                spans.add(Span(pre + start, pre + k)); start = -1
            }
        }
        if (start >= 0) spans.add(Span(pre + start, pre + m))
        if (spans.isEmpty()) return listOf(neighbour(pre, new))
        return spans
    }

    private fun neighbour(pos: Int, new: String): Span =
        if (pos < new.length) Span(pos, pos + 1) else Span(maxOf(0, new.length - 1), new.length)
}
