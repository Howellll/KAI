package com.kai.keyboard

import java.io.File
import kotlin.math.min

/**
 * Всё, чему клавиатура научилась у владельца. Хранится только в
 * noBackupFilesDir — не попадает ни в облачные бэкапы, ни в перенос на другой телефон —
 * и зашифровано (Vault), если хранилище ключей доступно.
 */
class UserStore(private val dir: File, private val vault: Vault? = null) {
    private val words = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private val offsets = HashMap<Char, FloatArray>()
    private val swipeOffsets = HashMap<Int, FloatArray>()
    private val blocked = HashSet<String>()
    private var sinceAge = 0
    private var mass = Float.NaN
    private var uses = 0
    private var dirty = false
    /**
     * Файл прочитан (или его нет). Пока нет — сохранять нельзя: ключ мог быть временно недоступен,
     * и запись нового файла стёрла бы всё выученное раньше.
     */
    private var loaded = false

    private val file get() = File(dir, "learned.txt")

    /**
     * Прочитать выученное. Повторный вызов (после «ключ недоступен») дочитывает и складывает с новым.
     * Чтение и расшифровка — без замка: набор (addWord и т.п. на основном потоке) их не ждёт;
     * под замком — только сложение прочитанного с тем, что в памяти.
     */
    fun load() {
        if (synchronized(this) { loaded }) return
        val f = file
        val r = synchronized(writeLock) {
            if (vault != null) vault.read(f, PURPOSE)
            else if (f.exists()) Vault.Read.Ok(f.readBytes(), false) else Vault.Read.Missing
        }
        synchronized(this) {
            if (loaded) return
            when (r) {
                is Vault.Read.Ok -> { if (vault != null && r.legacy) dirty = true; merge(r.bytes) } // старый — перешифруем
                Vault.Read.Missing -> {
                    val back = if (vault == null) null else synchronized(writeLock) { Vault.recoverAside(f) { vault.read(it, PURPOSE) } }
                    if (back != null) merge(back.bytes) else loaded = true
                }
                Vault.Read.NoKey -> {} // попробуем при следующем сохранении
                Vault.Read.Corrupt -> {
                    // Не расшифровать (порча, подмена) — откладываем файл. Может, читается отложенный раньше
                    // (вернулся прежний ключ) — тогда он на место; иначе начинаем заново.
                    val back = synchronized(writeLock) {
                        f.renameTo(Vault.asideName(f, "bad"))
                        vault?.let { v -> Vault.recoverAside(f) { v.read(it, PURPOSE) } }
                    }
                    if (back != null) merge(back.bytes) else loaded = true
                }
            }
        }
    }

    private fun merge(bytes: ByteArray) {
        loaded = true
        runCatching {
            String(bytes, Charsets.UTF_8).lineSequence().forEach { line ->
                val p = line.split('\t')
                when (p[0]) {
                    "w" -> if (p.size == 3 && p[1] !in forgotten) { words.merge(p[1], p[2].toIntOrNull() ?: 0, Int::plus); mass = Float.NaN }
                    "b" -> if (p.size == 4 && p[1] !in forgotten && p[2] !in forgotten)
                        bigrams.getOrPut(p[1]) { HashMap() }.merge(p[2], p[3].toIntOrNull() ?: 0, Int::plus)
                    "x" -> if (p.size == 2 && (words[p[1]] ?: 0) < 2) blocked.add(p[1])
                    "a" -> p.getOrNull(1)?.toIntOrNull()?.let { sinceAge += it }
                    "o" -> if (p.size == 4 && p[1].length == 1 && p[1][0] !in offsets) {
                        offsets[p[1][0]] = floatArrayOf(p[2].toFloatOrNull() ?: 0f, p[3].toFloatOrNull() ?: 0f)
                    }
                    "s" -> p.getOrNull(1)?.toIntOrNull()?.let { r ->
                        if (p.size == 4 && r !in swipeOffsets)
                            swipeOffsets[r] = floatArrayOf(p[2].toFloatOrNull() ?: 0f, p[3].toFloatOrNull() ?: 0f)
                    }
                }
            }
        }
    }

    /**
     * Сохранить. Под замком — только снимок (быстро): шифрование и запись на диск идут без замка,
     * чтобы набор (addWord и т.п. на основном потоке) не ждал диск и хранилище ключей.
     */
    fun save() {
        load() // если ещё не прочитано (ключ был недоступен) — сначала дочитать (без замка)
        val bytes = synchronized(this) {
            if (!dirty || !loaded) return
            prune()
            dirty = false
            snapshot()
        }
        val ok = synchronized(writeLock) {
            runCatching {
                if (vault != null) {
                    vault.write(file, bytes, PURPOSE)
                } else {
                    dir.mkdirs(); Vault.writeAtomically(file, bytes); true
                }
            }.getOrDefault(false) // нет места на диске и т.п. — не падаем, повторим при следующем сохранении
        }
        if (!ok) synchronized(this) { dirty = true }
    }

    /**
     * Выученное для переноса на другое устройство: слова, пары, «не предлагать» (строки w, b, x).
     * Подстройка под палец (o, s) не переносится: на другом экране она только мешала бы.
     * null — файл ещё не прочитан (ключ недоступен): отдать неполное нельзя.
     */
    fun exportLines(): List<String>? {
        load()
        synchronized(this) {
            if (!loaded) return null
            val out = ArrayList<String>(words.size + 16)
            for ((w, c) in words) out.add("w\t$w\t$c")
            for ((a, m) in bigrams) for ((b, c) in m) out.add("b\t$a\t$b\t$c")
            for (w in blocked) out.add("x\t$w")
            return out
        }
    }

    /** Принять перенесённое: складывается с тем, что уже выучено здесь. @return false — файл ещё не прочитан. */
    fun importLines(lines: List<String>): Boolean {
        load()
        synchronized(this) {
            if (!loaded) return false
            val text = lines.filter { it.startsWith("w\t") || it.startsWith("b\t") || it.startsWith("x\t") }
                .joinToString("\n")
            merge(text.toByteArray(Charsets.UTF_8))
            mass = Float.NaN
            dirty = true
        }
        save()
        return true
    }

    /** Один писатель за раз (сохранение и стирание не должны перехлестнуться). */
    private val writeLock = Any()

    private fun snapshot(): ByteArray {
        val out = StringBuilder()
        for ((w, c) in words) out.append("w\t").append(w).append('\t').append(c.toString()).append('\n')
        for ((a, m) in bigrams) for ((b, c) in m) {
            out.append("b\t").append(a).append('\t').append(b).append('\t').append(c.toString()).append('\n')
        }
        for (w in blocked) out.append("x\t").append(w).append('\n')
        out.append("a\t").append(sinceAge.toString()).append('\n')
        for ((ch, o) in offsets) out.append("o\t").append(ch).append('\t')
            .append(o[0].toString()).append('\t').append(o[1].toString()).append('\n')
        for ((r, o) in swipeOffsets) out.append("s\t").append(r.toString()).append('\t')
            .append(o[0].toString()).append('\t').append(o[1].toString()).append('\n')
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    @Synchronized
    fun clear() {
        words.clear(); bigrams.clear(); offsets.clear(); swipeOffsets.clear(); blocked.clear(); forgotten.clear()
        sinceAge = 0; mass = Float.NaN
        synchronized(writeLock) {
            dir.listFiles()?.forEach { if (it.name.startsWith("learned")) it.delete() }
        }
        dirty = false
        loaded = true
    }

    private fun prune() {
        mass = Float.NaN
        if (words.size > MAX_WORDS) {
            val keep = words.entries.sortedByDescending { it.value }.take(MAX_WORDS * 3 / 4).map { it.key }.toHashSet()
            words.keys.retainAll(keep)
        }
        val total = bigrams.values.sumOf { it.size }
        if (total > MAX_BIGRAMS) {
            for (m in bigrams.values) m.values.removeAll { it < 2 }
            bigrams.values.removeAll { it.isEmpty() }
        }
    }

    @Synchronized
    fun addWord(norm: String, minCount: Int = 0) {
        val c = (words[norm] ?: 0) + 1
        words[norm] = maxOf(c, minCount)
        if (words[norm]!! >= 2) blocked.remove(norm)
        mass = Float.NaN
        if (++sinceAge >= AGE_EVERY) age()
        dirty = true
    }

    /**
     * «Остывание»: раз в AGE_EVERY выученных слов все счётчики умножаются на AGE_FACTOR (с округлением вверх —
     * выученное не забывается). Слово, которое писал часто, но давно, уступает тому, что пишешь сейчас.
     */
    private fun age() {
        sinceAge = 0
        for (e in words.entries) e.setValue(kotlin.math.ceil(e.value * AGE_FACTOR).toInt())
        for (m in bigrams.values) for (e in m.entries) e.setValue(kotlin.math.ceil(e.value * AGE_FACTOR).toInt())
        mass = Float.NaN
    }

    private fun ensureMass() {
        if (!mass.isNaN()) return
        var m = 0.0
        var u = 0
        for (c in words.values) if (c >= 2) { m += Math.pow(c.toDouble(), 0.75); u += c }
        mass = m.toFloat(); uses = u
    }

    @Synchronized
    fun uses(): Int { ensureMass(); return uses }

    /**
     * Свои слова, начинающиеся с prefix, и их доля среди своих: c^0.75 / Σ c^0.75 (как частоты словаря —
     * со сглаживанием). Часто набираемое — выше, редкое — ниже; общий рост выученного долю не раздувает.
     */
    @Synchronized
    fun completionShares(prefix: String, sink: (String, Float) -> Unit) {
        ensureMass()
        if (mass <= 0f) return
        for ((w, c) in words) if (c >= 2 && w.startsWith(prefix)) sink(w, Math.pow(c.toDouble(), 0.75).toFloat() / mass)
    }

    @Synchronized
    fun bigramShare(prev: String, word: String): Float {
        val m = bigrams[prev] ?: return 0f
        val c = m[word] ?: return 0f
        return c / m.values.sum().toFloat()
    }

    @Synchronized
    fun unlearn(norm: String, ctx: String?) {
        val c = (words[norm] ?: return) - 1
        if (c <= 0) words.remove(norm) else words[norm] = c
        mass = Float.NaN
        if (ctx != null) bigrams[ctx]?.let { m ->
            val b = (m[norm] ?: 0) - 1
            if (b <= 0) m.remove(norm) else m[norm] = b
        }
        dirty = true
    }

    @Synchronized
    fun forget(norm: String) {
        words.remove(norm)
        mass = Float.NaN
        bigrams.remove(norm)
        for (m in bigrams.values) m.remove(norm)
        blocked.add(norm)
        if (!loaded) forgotten.add(norm) // файл ещё не прочитан — при чтении это слово не вернём
        dirty = true
    }

    private val forgotten = HashSet<String>()

    @Synchronized
    fun isBlocked(norm: String) = blocked.isNotEmpty() && norm in blocked

    @Synchronized
    fun addBigram(prev: String, word: String) {
        val m = bigrams.getOrPut(prev) { HashMap() }
        m[word] = (m[word] ?: 0) + 1
        dirty = true
    }

    @Synchronized
    fun isKnown(norm: String) = (words[norm] ?: 0) >= 2

    @Synchronized
    fun count(norm: String) = words[norm] ?: 0

    @Synchronized
    fun bigram(prev: String, word: String) = bigrams[prev]?.get(word) ?: 0

    private fun weight(c: Int) = 150f * min(c, 40)

    /**
     * Следующие буквы по своим словам — сырые числа использований (без потолка), чтобы считать долю:
     * слово, набранное 400 раз, весит в 80 раз больше набранного 5 раз. @return сумма использований
     */
    @Synchronized
    fun addNextCharCounts(lang: Lang, prefix: String, out: FloatArray): Float {
        var total = 0f
        val len = prefix.length
        for ((w, c) in words) {
            if (c < 2 || w.length <= len || !w.startsWith(prefix)) continue
            val ci = lang.index(w[len])
            if (ci >= 0) { out[ci] += c.toFloat(); total += c }
        }
        return total
    }

    @Synchronized
    fun addNextChars(lang: Lang, prefix: String, out: FloatArray): Float {
        var total = 0f
        val len = prefix.length
        for ((w, c) in words) {
            if (c < 2 || w.length <= len || !w.startsWith(prefix)) continue
            val ci = lang.index(w[len])
            if (ci >= 0) { val x = weight(c); out[ci] += x; total += x }
        }
        return total
    }

    @Synchronized
    fun completions(prefix: String, sink: (String, Float) -> Unit) {
        for ((w, c) in words) if (c >= 2 && w.startsWith(prefix)) sink(w, weight(c))
    }

    @Synchronized
    fun forEachNext(prev: String, fn: (String, Int) -> Unit) {
        bigrams[prev]?.forEach { (w, c) -> fn(w, c) }
    }

    @Synchronized
    fun nextWords(prev: String, n: Int): List<String> =
        bigrams[prev]?.entries?.sortedByDescending { it.value }?.take(n)?.map { it.key } ?: emptyList()

    @Synchronized
    fun offset(ch: Char): FloatArray? = offsets[ch]

    @Synchronized
    fun swipeOffset(row: Int): FloatArray? = swipeOffsets[row]?.copyOf()

    @Synchronized
    fun learnSwipeOffset(row: Int, dx: Float, dy: Float, alpha: Float) {
        val o = swipeOffsets.getOrPut(row) { FloatArray(2) }
        o[0] = (o[0] * (1 - alpha) + dx * alpha).coerceIn(-MAX_SWIPE_OFFSET, MAX_SWIPE_OFFSET)
        o[1] = (o[1] * (1 - alpha) + dy * alpha).coerceIn(-MAX_SWIPE_OFFSET, MAX_SWIPE_OFFSET)
        dirty = true
    }

    @Synchronized
    fun learnOffset(ch: Char, dx: Float, dy: Float) {
        // Учимся только на «честных» касаниях внутри своей клавиши: касания, которые
        // буква отобрала у соседа благодаря умной зоне, иначе раздували бы её ещё сильнее.
        if (kotlin.math.abs(dx) > 0.5f || kotlin.math.abs(dy) > 0.5f) return
        val o = offsets.getOrPut(ch) { FloatArray(2) }
        o[0] = (o[0] * (1 - ALPHA) + dx * ALPHA).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        o[1] = (o[1] * (1 - ALPHA) + dy * ALPHA).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        dirty = true
    }

    companion object {
        private const val PURPOSE = "learned"
        private const val MAX_SWIPE_OFFSET = 0.6f
        private const val MAX_WORDS = 30000
        private const val MAX_BIGRAMS = 40000
        /** «Остывание» счётчиков: раз в столько выученных слов (~неделя-две набора) — умножить на AGE_FACTOR. */
        var AGE_EVERY = 2000
        var AGE_FACTOR = 0.8
        private const val ALPHA = 0.03f
        private const val MAX_OFFSET = 0.15f
    }
}
