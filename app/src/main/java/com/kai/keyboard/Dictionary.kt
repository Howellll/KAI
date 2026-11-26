package com.kai.keyboard

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

class Lang(val code: String, val alphabet: String, val title: String, val abc: String) {
    val size = alphabet.length
    private val table = IntArray(0x460) { -1 }

    init {
        alphabet.forEachIndexed { i, c -> table[c.code] = i }
        if (code == "ru") table['ё'.code] = table['е'.code]
    }

    fun index(c: Char): Int = if (c.code < table.size) table[c.code] else -1

    fun accepts(norm: String): Boolean = norm.all { index(it) >= 0 || it == '-' || it == '\'' }
}

object Langs {
    val RU = Lang("ru", "абвгдежзийклмнопрстуфхцчшщъыьэюя", "русский", "АБВ")
    val EN = Lang("en", "abcdefghijklmnopqrstuvwxyz", "English", "ABC")
    fun of(code: String) = if (code == "en") EN else RU
}

fun normalize(s: String): String = s.lowercase().replace('ё', 'е')

/**
 * Частотный словарь. Ключи (нормализованные, отсортированные) лежат подряд в одном массиве байтов:
 * ~750 тысяч русских словоформ занимают ~10 МБ памяти вместо сотен тысяч объектов String.
 * Префиксный поиск — двоичным поиском, без деревьев и хеш-таблиц.
 */
class Dictionary(val lang: Lang) {
    private var blob = ByteArray(0)
    private var start = IntArray(1)
    private var q = ByteArray(0)
    private var chars = CharArray(0)
    private var shown: Map<Int, String> = emptyMap()
    private var size = 0
    private val first = FloatArray(lang.size)
    private val bigram = Array(lang.size) { FloatArray(lang.size) }
    private val neighbors = Layouts.neighbors(lang)
    private var weightSum = 1f

    /** Короткие префиксы охватывают десятки тысяч слов — их распределения считаем один раз. */
    private val nextCache = HashMap<String, FloatArray>()
    private val topCache = HashMap<String, IntArray>()

    /** Что идёт после слова (или после начала предложения «<s>», после запятой «,»); idx — по убыванию counts. */
    class Ctx(val total: Float, val idx: IntArray, val counts: FloatArray)

    /**
     * Файл пар и троек как есть (формат — в tools/build_bigrams.py) и оглавление: ключ контекста → место
     * в файле (off — номера слов, qOff — их доли). Слова в файле пронумерованы по частоте: rankWord — номер
     * в словаре по рангу, usedWords (по возрастанию) и usedRank — обратно.
     */
    private class Contexts(val data: ByteArray, val keys: LongArray, val off: IntArray, val qOff: IntArray,
                           val rankWord: IntArray, val usedWords: IntArray, val usedRank: IntArray)

    @Volatile
    private var contexts: Contexts? = null

    /** Недавно спрошенные контексты: за одно нажатие один и тот же нужен несколько раз. */
    private val ctxCache = object : LinkedHashMap<String, Ctx?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Ctx?>) = size > 32
    }

    @Volatile
    var ready = false
        private set

    /** Бинарный словарь, формат — в tools/build_dicts.py. */
    fun load(input: InputStream) {
        val din = DataInputStream(BufferedInputStream(input, 1 shl 16))
        val magic = ByteArray(4).also { din.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == "KLD1") { "bad dictionary" }
        val ch = CharArray(din.readUnsignedShort()) { din.readChar() }
        val n = din.readInt()
        val st = IntArray(n + 1)
        val qq = ByteArray(n)
        var b = ByteArray(n * 10)
        var len = 0
        var prev = 0
        for (i in 0 until n) {
            val shared = din.readUnsignedByte()
            val suffix = din.readUnsignedByte()
            if (len + shared + suffix > b.size) b = b.copyOf(maxOf(b.size * 3 / 2, len + 512))
            System.arraycopy(b, prev, b, len, shared)
            din.readFully(b, len + shared, suffix)
            st[i] = len
            prev = len
            len += shared + suffix
            qq[i] = din.readByte()
        }
        st[n] = len
        val m = din.readInt()
        val sh = HashMap<Int, String>(m * 2)
        repeat(m) {
            val i = din.readInt()
            val bytes = ByteArray(din.readUnsignedByte()).also { din.readFully(it) }
            sh[i] = String(bytes, Charsets.UTF_8)
        }

        var sum = 0f
        for (i in 0 until n) {
            val wt = Q_WEIGHT[qq[i].toInt() and 0xff]
            sum += wt
            var p = -1
            for (pos in st[i] until st[i + 1]) {
                val ci = lang.index(ch[b[pos].toInt() and 0xff])
                if (ci < 0) { p = -1; continue }
                if (pos == st[i]) first[ci] += wt else if (p >= 0) bigram[p][ci] += wt
                p = ci
            }
        }
        blob = b.copyOf(len); start = st; q = qq; chars = ch; shown = sh; size = n
        weightSum = sum
        ready = true
    }

    private fun len(i: Int) = start[i + 1] - start[i]
    private fun charAt(i: Int, pos: Int) = chars[blob[start[i] + pos].toInt() and 0xff]
    private fun weightAt(i: Int) = Q_WEIGHT[q[i].toInt() and 0xff]
    private fun key(i: Int): String {
        val n = len(i)
        val sb = StringBuilder(n)
        for (pos in 0 until n) sb.append(charAt(i, pos))
        return sb.toString()
    }

    private fun word(i: Int): String = shown[i] ?: key(i)

    private fun compare(i: Int, s: String): Int {
        val n = len(i)
        val m = minOf(n, s.length)
        for (pos in 0 until m) {
            val c = charAt(i, pos)
            if (c != s[pos]) return c - s[pos]
        }
        return n - s.length
    }

    private fun startsWith(i: Int, prefix: String): Boolean {
        if (len(i) < prefix.length) return false
        for (pos in prefix.indices) if (charAt(i, pos) != prefix[pos]) return false
        return true
    }

    private fun indexOf(norm: String): Int {
        val i = lowerBound(norm)
        return if (i < size && compare(i, norm) == 0) i else -1
    }

    fun loadContext(input: InputStream) {
        val data = input.readBytes()
        require(data.size >= 8 && String(data, 0, 4, Charsets.US_ASCII) == "KLB2") { "bad contexts" }
        val r = Reader(data, 4)
        val v = r.varint()
        val used = IntArray(v)
        var last = 0
        for (j in 0 until v) { last += r.varint(); used[j] = last }
        val byFreq = LongArray(v) { (255L - (q[used[it]].toInt() and 0xff)) shl 32 or used[it].toLong() }
        java.util.Arrays.sort(byFreq)
        val rankWord = IntArray(v) { (byFreq[it] and 0xffffffffL).toInt() }
        val usedRank = IntArray(v)
        for (rk in 0 until v) usedRank[java.util.Arrays.binarySearch(used, rankWord[rk])] = rk

        val n = r.int()
        // ключ (42 бита) и номер контекста (21 бит) — в одном long, чтобы отсортировать без объектов
        val packed = LongArray(n)
        val offs = IntArray(n)
        val qOffs = IntArray(n)
        var qn = 0
        for (c in 0 until n) {
            val k2 = r.varint().toLong()
            val k1 = r.varint().toLong()
            offs[c] = r.pos
            r.pos += 5
            val m = r.varint()
            repeat(m) { r.varint() }
            qOffs[c] = qn
            qn += m
            packed[c] = ((k2 shl 21 or k1) shl 21) or c.toLong()
        }
        val qBase = r.pos
        require(qBase + qn == data.size) { "bad contexts" }
        java.util.Arrays.sort(packed)
        val keys = LongArray(n) { packed[it] ushr 21 }
        val off = IntArray(n) { offs[(packed[it] and 0x1fffff).toInt()] }
        val qOff = IntArray(n) { qBase + qOffs[(packed[it] and 0x1fffff).toInt()] }
        synchronized(ctxCache) { ctxCache.clear() }
        contexts = Contexts(data, keys, off, qOff, rankWord, used, usedRank)
    }

    private class Reader(val b: ByteArray, var pos: Int) {
        fun u8() = b[pos++].toInt() and 0xff
        fun varint(): Int {
            var v = 0
            var shift = 0
            while (true) {
                val x = u8()
                v = v or ((x and 0x7f) shl shift)
                if (x < 0x80) return v
                shift += 7
            }
        }
        fun int(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun f32(): Float = Float.fromBits(int())
    }

    private fun ctxCode(cs: Contexts, tok: String): Int = when (tok) {
        "<s>" -> 1
        "," -> 2
        else -> {
            val i = indexOf(tok)
            val at = if (i >= 0) java.util.Arrays.binarySearch(cs.usedWords, i) else -1
            if (at >= 0) cs.usedRank[at] + 3 else -1
        }
    }

    private class Raw(val total: Float, val lam: Float, val idx: IntArray, val share: FloatArray)

    private fun raw(cs: Contexts, k2: Int, k1: Int): Raw? {
        val key = (k2.toLong() shl 21) or k1.toLong()
        val at = java.util.Arrays.binarySearch(cs.keys, key)
        if (at < 0) return null
        val r = Reader(cs.data, cs.off[at])
        val total = r.f32()
        val lam = r.u8() / 255f
        val m = r.varint()
        val idx = IntArray(m)
        val share = FloatArray(m)
        var last = 0
        val q0 = cs.qOff[at]
        for (j in 0 until m) {
            last += r.varint()
            idx[j] = cs.rankWord[last]
            share[j] = Q_SHARE[cs.data[q0 + j].toInt() and 0xff]
        }
        return Raw(total, lam, idx, share)
    }

    /**
     * Контекст по ключу: «слово», «<s>», «,» — или два слова через пробел («я тебя»). Для двух слов
     * доли из тройки поверх пары: что тройка знает — её доля, остальное — доля пары, умноженная на (1 − λ).
     */
    fun context(key: String?): Ctx? {
        if (key == null) return null
        val cs = contexts ?: return null
        val ck = if (TRIGRAMS) key else "2|$key"
        synchronized(ctxCache) { if (ctxCache.containsKey(ck)) return ctxCache[ck] }
        val sp = key.lastIndexOf(' ')
        val k1 = ctxCode(cs, if (sp < 0) key else key.substring(sp + 1))
        val ctx = if (k1 < 0) null else raw(cs, 0, k1)?.let { b ->
            val k2 = if (sp < 0 || !TRIGRAMS) -1 else ctxCode(cs, key.substring(0, sp))
            val t = if (k2 > 0) raw(cs, k2, k1) else null
            val counts = HashMap<Int, Float>(b.idx.size * 2)
            val keep = if (t == null) 1f else 1f - t.lam
            for (j in b.idx.indices) counts[b.idx[j]] = b.share[j] * keep
            if (t != null) for (j in t.idx.indices) counts[t.idx[j]] = t.share[j]
            val order = counts.entries.sortedByDescending { it.value }
            Ctx(b.total, IntArray(order.size) { order[it].key }, FloatArray(order.size) { order[it].value * b.total })
        }
        synchronized(ctxCache) { ctxCache[ck] = ctx }
        return ctx
    }

    fun addNextCharsCtx(ctx: Ctx, prefix: String, out: FloatArray): Float {
        var total = 0f
        val len = prefix.length
        for (j in ctx.idx.indices) {
            val i = ctx.idx[j]
            if (len(i) <= len || !startsWith(i, prefix)) continue
            val ci = lang.index(charAt(i, len))
            if (ci >= 0) { out[ci] += ctx.counts[j]; total += ctx.counts[j] }
        }
        return total
    }

    fun contextBoost(ctx: Ctx?, word: String): Float {
        if (ctx == null) return 0f
        val i = indexOf(normalize(word)).takeIf { it >= 0 } ?: return 0f
        for (j in ctx.idx.indices) if (ctx.idx[j] == i) {
            val pCtx = ctx.counts[j] / ctx.total
            val pUni = weightAt(i) / weightSum
            return min(4f, ln(1f + 1.5f * pCtx / pUni))
        }
        return 0f
    }

    fun topNext(ctx: Ctx?, n: Int): List<String> =
        ctx?.idx?.take(n)?.map { word(it) } ?: emptyList()

    private fun lowerBound(s: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (compare(m, s) < 0) lo = m + 1 else hi = m
        }
        return lo
    }

    private fun range(prefix: String): IntRange = lowerBound(prefix) until lowerBound(prefix + '￿')

    fun contains(norm: String) = indexOf(norm) >= 0

    /**
     * Слова для свайпа: начинаются на одну из first, заканчиваются на одну из last и состоят
     * только из букв, отмеченных в near (индекс — lang.index). Отсев идёт прямо по байтам,
     * строки создаются лишь для подошедших слов — их единицы процентов.
     */
    fun forEachWord(first: Set<Char>, last: Set<Char>, near: BooleanArray,
                    fn: (key: String, word: String, weight: Float) -> Unit) =
        forEachWord(first, last, near, null, 0f, Float.MAX_VALUE, fn)

    /**
     * То же, и ещё длина «идеального» пути слова (ломаная через центры букв, centers) — в пределах
     * [minLen, maxLen]: слово, путь которого вдвое длиннее или короче жеста, не тот. Считается по байтам,
     * до создания строк.
     */
    fun forEachWord(first: Set<Char>, last: Set<Char>, near: BooleanArray,
                    centers: Map<Char, FloatArray>?, minLen: Float, maxLen: Float,
                    fn: (key: String, word: String, weight: Float) -> Unit) {
        // дефис и апостроф («кто-то», «don't») — не клавиши: при свайпе их просто нет на пути
        val ok = BooleanArray(chars.size) { chars[it] == '-' || chars[it] == '\'' || lang.index(chars[it]).let { ci -> ci >= 0 && near[ci] } }
        val cx = FloatArray(chars.size) { Float.NaN }; val cy = FloatArray(chars.size)
        if (centers != null) for (j in chars.indices) centers[chars[j]]?.let { cx[j] = it[0]; cy[j] = it[1] }
        for (c in first) for (i in range(c.toString())) {
            val s = start[i]; val e = start[i + 1]
            if (charAt(i, e - s - 1) !in last) continue
            var pos = s
            while (pos < e && ok[blob[pos].toInt() and 0xff]) pos++
            if (pos != e) continue
            if (centers != null) {
                var len = 0f
                var px = Float.NaN; var py = 0f
                for (p in s until e) {
                    val b = blob[p].toInt() and 0xff
                    val x = cx[b]; val y = cy[b]
                    if (x.isNaN()) continue
                    if (!px.isNaN()) len += kotlin.math.hypot(x - px, y - py)
                    px = x; py = y
                }
                if (len < minLen || len > maxLen) continue
            }
            fn(key(i), word(i), weightAt(i))
        }
    }

    fun hasPrefix(prefix: String): Boolean {
        val i = lowerBound(prefix)
        return i < size && startsWith(i, prefix)
    }

    /** Как показывать слово («все» → «всё», «im» → «I'm»). */
    fun display(norm: String): String = indexOf(norm).let { if (it >= 0) word(it) else norm }

    fun weight(norm: String): Float = indexOf(norm).let { if (it >= 0) weightAt(it) else 0f }

    /** Сумма весов всех слов — чтобы переводить долю в вес словаря. */
    fun totalWeight(): Float = weightSum

    /** Слова с дефисом по написанию без дефиса («ктото» → самое частое «кто-то»); строится при первом обращении. */
    @Volatile private var hyphenIndex: Map<String, Int>? = null
    /** Те же написания без дефиса — по алфавиту, для поиска по началу. */
    @Volatile private var hyphenPlain: Array<String> = emptyArray()

    /** То же слово с дефисом (как показывать) и его вес; null — такого нет. */
    fun hyphenated(norm: String): Pair<String, Float>? {
        val idx = hyphenIndex ?: synchronized(this) {
            hyphenIndex ?: HashMap<String, Int>().also { m ->
                val dash = chars.indexOf('-')
                if (dash >= 0) for (i in 0 until size) {
                    var has = false
                    for (p in start[i] until start[i + 1]) if ((blob[p].toInt() and 0xff) == dash) { has = true; break }
                    if (!has) continue
                    val plain = key(i).replace("-", "")
                    val prev = m[plain]
                    if (prev == null || weightAt(prev) < weightAt(i)) m[plain] = i
                }
            }.also { hyphenPlain = it.keys.sorted().toTypedArray(); hyphenIndex = it }
        }
        val i = idx[norm] ?: return null
        return word(i) to weightAt(i)
    }

    /** Слова с дефисом, которые без дефиса начинаются с prefix («вопер» → «во-первых»), до n самых частых. */
    fun hyphenCompletions(prefix: String, n: Int, sink: (String, Float) -> Unit) {
        hyphenated("") // построить указатель
        val idx = hyphenIndex ?: return
        val arr = hyphenPlain
        var lo = 0; var hi = arr.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (arr[mid] < prefix) lo = mid + 1 else hi = mid }
        val found = ArrayList<Int>()
        var j = lo
        while (j < arr.size && arr[j].startsWith(prefix)) { idx[arr[j]]?.let { found.add(it) }; j++ }
        for (i in found.sortedByDescending { weightAt(it) }.take(n)) sink(word(i), weightAt(i))
    }

    fun addNextChars(prefix: String, out: FloatArray): Float {
        var total = 0f
        if (prefix.isEmpty()) {
            for (i in first.indices) { out[i] += first[i]; total += first[i] }
            return total
        }
        val cached = if (prefix.length <= CACHE_LEN) synchronized(nextCache) { nextCache[prefix] } else null
        val dist = cached ?: FloatArray(lang.size + 1).also { d ->
            val len = prefix.length
            for (i in range(prefix)) {
                if (len(i) <= len) continue
                val ci = lang.index(charAt(i, len))
                if (ci >= 0) { d[ci] += weightAt(i); d[lang.size] += weightAt(i) }
            }
            if (prefix.length <= CACHE_LEN) synchronized(nextCache) { nextCache[prefix] = d }
        }
        for (i in 0 until lang.size) out[i] += dist[i]
        return dist[lang.size]
    }

    fun charBigram(prev: Char?, out: FloatArray) {
        val pi = if (prev == null) -1 else lang.index(prev)
        val src = if (pi >= 0) bigram[pi] else first
        val sum = src.sum()
        for (i in src.indices) out[i] = if (sum > 0f) src[i] / sum else 1f / src.size
    }

    fun completions(prefix: String, n: Int, sink: (String, Float) -> Unit) {
        val cached = if (prefix.length <= CACHE_LEN) synchronized(topCache) { topCache[prefix] } else null
        val top = cached?.takeIf { it.size >= n } ?: IntArray(n) { -1 }.also { top ->
            for (i in range(prefix)) {
                val w = weightAt(i)
                var j = n - 1
                if (top[j] >= 0 && weightAt(top[j]) >= w) continue
                while (j > 0 && (top[j - 1] < 0 || weightAt(top[j - 1]) < w)) { top[j] = top[j - 1]; j-- }
                top[j] = i
            }
            if (prefix.length <= CACHE_LEN) synchronized(topCache) { topCache[prefix] = top }
        }
        for (k in 0 until n) { val i = top[k]; if (i >= 0) sink(word(i), weightAt(i)) }
    }

    /**
     * Кандидаты на исправление на расстоянии одной правки.
     * Замена на соседнюю клавишу — самая дешёвая ошибка.
     */
    fun corrections(typed: String, out: MutableMap<String, Float>) {
        fun add(cand: String, cost: Float) {
            val i = indexOf(cand)
            if (i < 0) return
            val s = ln(weightAt(i)) - cost
            val w = word(i)
            if ((out[w] ?: Float.NEGATIVE_INFINITY) < s) out[w] = s
        }

        val n = typed.length
        val sb = StringBuilder(n + 1)
        val insertable = if (lang == Langs.RU) lang.alphabet + "-" else lang.alphabet + "'"
        for (i in 0 until n) {
            sb.setLength(0); sb.append(typed, 0, i).append(typed, i + 1, n)
            add(sb.toString(), if (i > 0 && typed[i] == typed[i - 1]) 1.5f else 2.5f)
            if (i < n - 1 && typed[i] != typed[i + 1]) {
                sb.setLength(0); sb.append(typed)
                sb.setCharAt(i, typed[i + 1]); sb.setCharAt(i + 1, typed[i])
                add(sb.toString(), 1.5f)
            }
            for (c in lang.alphabet) {
                if (c == typed[i]) continue
                sb.setLength(0); sb.append(typed); sb.setCharAt(i, c)
                add(sb.toString(), if (Layouts.pair(typed[i], c) in neighbors) 1.0f else 3.5f)
            }
        }
        for (i in 0..n) for (c in insertable) {
            sb.setLength(0); sb.append(typed, 0, i).append(c).append(typed, i, n)
            val cost = when {
                c == '-' || c == '\'' -> 1.0f
                (i > 0 && typed[i - 1] == c) || (i < n && typed[i] == c) -> 1.8f
                else -> 2.5f
            }
            add(sb.toString(), cost)
        }
    }

    companion object {
        private const val CACHE_LEN = 2
        /** Частота хранится как q = 16·ln(count); вес слова = count^0.75. */
        private val Q_WEIGHT = FloatArray(256) { exp(0.75 * it / 16.0).toFloat() }
        /** Доля слова в контексте хранится как q = −16·ln(доля). */
        private val Q_SHARE = FloatArray(256) { exp(-it / 16.0).toFloat() }
        /** Учитывать тройки слов (выключается в тестах — для замеров «до/после»). */
        @Volatile
        var TRIGRAMS = true
    }
}
