package com.kai.keyboard

import kotlin.math.ln

/** Одно эмодзи: варианты цвета кожи и слова, по которым его ищут (из CLDR и наши «чатовые»). */
class EmojiItem(
    val e: String,
    val tones: List<String>,
    val order: Int,
    val flag: Boolean,
    val name: Array<String>,
    val words: Array<String>,
    val chat: Array<String>,
) {
    /** Те же слова в начальной форме — чтобы «кошки» находили «кошка», а «лицо» — «лицом». */
    internal var nameL: Array<String> = NONE
    internal var wordsL: Array<String> = NONE
    internal var chatL: Array<String> = NONE

    private companion object { val NONE = emptyArray<String>() }
}

class EmojiGroup(val id: String, val icon: String, val title: String, val items: List<EmojiItem>)

/**
 * Все эмодзи из assets/emoji.txt (формат — в tools/build_emoji.py), поиск по словам на русском
 * и английском и подсказка эмодзи к набранному слову. Без Android — чтобы проверять тестами.
 */
class EmojiData(text: String, supported: (String) -> Boolean = { true }) {
    val groups: List<EmojiGroup>
    private val items = ArrayList<EmojiItem>()
    private val baseOf = HashMap<String, String>()
    private val byEmoji = HashMap<String, EmojiItem>()
    private val index = HashMap<String, ArrayList<Hit>>()
    private val common = HashSet<String>()
    private val deny = HashSet<String>()
    private val ownLemma = HashMap<String, String>()

    // Русские формы → начальная форма. ~50 тысяч форм — одним массивом символов, а не тысячами строк:
    // формы по алфавиту подряд в formChars, границы — в formAt, номер начальной формы — в formLemma.
    private var formChars = CharArray(0)
    private var formAt = IntArray(1)
    private var formLemma = IntArray(0)
    private var lemmaNames = emptyArray<String>()

    private class Hit(val item: EmojiItem, val weight: Float)

    init {
        val lines = text.lines()
        val formsAt = lines.indexOf("=")
        val ownAt = lines.indexOf("^")
        val commonAt = lines.indexOf("%")
        fun section(at: Int): List<String> {
            if (at < 0) return emptyList()
            val end = listOf(formsAt, ownAt, commonAt).filter { it > at }.minOrNull() ?: lines.size
            return lines.subList(at + 1, end)
        }
        loadForms(section(formsAt))
        for (line in section(ownAt)) {
            val tab = line.indexOf('\t')
            if (tab > 0) ownLemma[line.substring(0, tab)] = line.substring(tab + 1)
        }
        for (w in section(commonAt)) when {
            w.isEmpty() -> {}
            w[0] == '!' -> deny.add(w.substring(1))
            else -> common.add(w)
        }

        // Одни и те же слова встречаются у сотен эмодзи — храним каждое один раз.
        val pool = HashMap<String, String>()
        fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { pool.getOrPut(it) { it } }
        fun lemmaArray(ws: Array<String>) = ws.flatMap { own(it) }.distinct().map { pool.getOrPut(it) { it } }.toTypedArray()

        val gs = ArrayList<EmojiGroup>()
        var cur: ArrayList<EmojiItem>? = null
        var curId = ""
        for (line in lines.subList(0, if (formsAt < 0) lines.size else formsAt)) {
            if (line.isEmpty()) continue
            if (line[0] == '@') {
                curId = line.substring(1)
                val meta = GROUPS[curId] ?: (curId to curId)
                cur = ArrayList()
                gs.add(EmojiGroup(curId, meta.first, meta.second, cur))
                continue
            }
            val f = line.split('\t')
            if (f.size < 6 || !supported(f[0])) continue
            val tones = f[1].split(' ').filter { it.isNotEmpty() && supported(it) }
            // «-слово» — служебное: длину имени считаем с ним, а ищем без него;
            // «~слово» — ищем, но в полосу подсказок по нему не предлагаем.
            val ruAll = words(f[2])
            val enAll = words(f[4])
            val ruName = ruAll.filter { it[0] != '-' }
            val enName = enAll.filter { it[0] != '-' }
            fun plain(ws: List<String>) = ws.map { w -> w.removePrefix("~").let { pool.getOrPut(it) { it } } }
            val kw = words(f[3]) + words(f[5])
            val item = EmojiItem(f[0], tones, items.size, curId == "flags", plain(ruName + enName).toTypedArray(),
                kw.filter { it[0] != '!' }.map { pool.getOrPut(it.removePrefix("~")) { it.removePrefix("~") } }.toTypedArray(),
                kw.filter { it[0] == '!' }.map { pool.getOrPut(it.substring(1)) { it.substring(1) } }.toTypedArray())
            items.add(item)
            cur?.add(item)
            byEmoji[item.e] = item
            for (t in tones) baseOf[t] = item.e
            for ((n, all) in listOf(ruName to ruAll, enName to enAll)) for (w in n) {
                if (w[0] == '~') addHit(w.substring(1), item, PART_W)
                else addHit(w, item, when (all.size) { 1 -> NAME_W; 2 -> NAME_W - 0.8f; else -> PART_W })
            }
            for (w in kw) when (w[0]) {
                '!' -> addHit(w.substring(1), item, CHAT_W)
                '~' -> addHit(w.substring(1), item, PART_W)
                else -> addHit(w, item, WORD_W)
            }
            item.nameL = lemmaArray(item.name)
            item.wordsL = lemmaArray(item.words)
            item.chatL = lemmaArray(item.chat)
        }
        groups = gs.filter { it.items.isNotEmpty() }
    }

    private fun loadForms(lines: List<String>) {
        val names = ArrayList<String>()
        val pairs = ArrayList<Pair<String, Int>>()
        for (line in lines) {
            val f = line.split('\t')
            if (f.size != 3) continue
            val id = names.size
            names.add(f[2])
            for (end in f[1].split(',')) pairs.add(f[0] + end to id)
        }
        pairs.sortBy { it.first }
        val sb = StringBuilder()
        formAt = IntArray(pairs.size + 1)
        formLemma = IntArray(pairs.size)
        for ((i, p) in pairs.withIndex()) {
            formAt[i] = sb.length
            sb.append(p.first)
            formLemma[i] = p.second
        }
        formAt[pairs.size] = sb.length
        formChars = CharArray(sb.length).also { sb.getChars(0, sb.length, it, 0) }
        lemmaNames = names.toTypedArray()
    }

    private fun compareForm(i: Int, w: String): Int {
        val a = formAt[i]
        val n = formAt[i + 1] - a
        for (k in 0 until minOf(n, w.length)) {
            val d = formChars[a + k] - w[k]
            if (d != 0) return d
        }
        return n - w.length
    }

    private fun findForm(w: String): Int {
        var lo = 0
        var hi = formLemma.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareForm(mid, w) < 0) lo = mid + 1 else hi = mid
        }
        return if (lo < formLemma.size && compareForm(lo, w) == 0) lo else -1
    }

    /**
     * Под какими словами индексировать слово эмодзи: само слово и одна его начальная форма, выбранная
     * при сборке. Не все, что дают формы: иначе «паром» попал бы и под «пар», «мыло» — под «мыть»
     * (и «мой» нашёл бы 🧼), а «стоит» — и под «стоить», и под «стоять».
     */
    private fun own(w: String): List<String> {
        val l = ownLemma[w]
        return if (l == null || l == w) listOf(w) else listOf(w, l)
    }

    private fun addHit(word: String, item: EmojiItem, weight: Float) {
        for (w in own(word)) {
            val list = index.getOrPut(w) { ArrayList(2) }
            val i = list.indexOfFirst { it.item === item }
            if (i < 0) list.add(Hit(item, weight)) else if (list[i].weight < weight) list[i] = Hit(item, weight)
        }
    }

    val size get() = items.size

    /** Базовое эмодзи для варианта с цветом кожи (или само эмодзи). */
    fun base(e: String): String = baseOf[e] ?: e

    fun item(e: String): EmojiItem? = byEmoji[base(e)]

    private fun lemmaCount(w: String): Int {
        val i0 = findForm(w)
        if (i0 < 0) return 0
        var i = i0
        val seen = HashSet<Int>(2)
        while (i < formLemma.size && compareForm(i, w) == 0) { seen.add(formLemma[i]); i++ }
        return seen.size
    }

    fun lemmas(w: String): List<String> {
        val i0 = findForm(w)
        if (i0 < 0) return listOf(w)
        val out = ArrayList<String>(3)
        out.add(w)
        var i = i0
        while (i < formLemma.size && compareForm(i, w) == 0) {
            val l = lemmaNames[formLemma[i]]
            if (l !in out) out.add(l)
            i++
        }
        return out
    }

    private fun singular(w: String): List<String> {
        if (w.length <= 3 || w.last() != 's' || w[w.length - 2] !in 'a'..'z') return emptyList()
        if (w.endsWith("ss") || w.endsWith("us") || w.endsWith("is")) return emptyList()
        val out = ArrayList<String>(2)
        when {
            w.endsWith("ies") -> out.add(w.dropLast(3) + "y")
            w.endsWith("ves") -> { out.add(w.dropLast(3) + "f"); out.add(w.dropLast(3) + "fe") }
            Regex("(s|x|z|ch|sh|o)es$").containsMatchIn(w) -> out.add(w.dropLast(2))
        }
        out.add(w.dropLast(1))
        return out.filter { it.length > 1 && index.containsKey(it) }
    }

    /**
     * Эмодзи к набранному слову — для полосы подсказок. Только если слово действительно его
     * называет: слова вроде «лицо» или «знак» подходят к сотне эмодзи и ничего не говорят,
     * а самые частые («есть», «того», «мой») чаще значат совсем другое.
     * @param bonus добавка для порядка (недавно использованным); порог она не снижает
     */
    fun suggest(word: String, bonus: (String) -> Float = { 0f }): String? {
        val w = qnorm(word)
        if (w.isEmpty()) return null
        val frequent = w in common
        val denied = w in deny
        var best: EmojiItem? = null
        var bestScore = Float.NEGATIVE_INFINITY
        val lem = lemmas(w)
        // Форма двух разных слов («тест» — и «тест», и «тесто») — гадать не будем: только само слово.
        val ambiguous = lemmaCount(w) > 1
        val cands = lem + singular(w)
        for ((n, lemma) in cands.withIndex()) {
            if (n > 0 && ambiguous) break
            val hits = index[lemma] ?: continue
            val spread = SPREAD * ln(hits.size.toFloat())
            for (h in hits) {
                if (w.length < 3 && h.weight < CHAT_W) continue
                if (denied && h.weight < CHAT_W) continue
                if (frequent && h.weight < CHAT_W && !(h.weight == NAME_W && n == 0 && !h.item.flag)) continue
                if (h.item.flag && !(h.weight == NAME_W && n == 0)) continue
                val raw = h.weight - spread - n * 0.1f
                if (raw <= MIN_SCORE) continue
                val s = raw + bonus(h.item.e)
                if (s > bestScore || (s == bestScore && best != null && h.item.order < best.order)) {
                    best = h.item; bestScore = s
                }
            }
        }
        return best?.e
    }

    /**
     * Поиск: каждое слово запроса должно совпасть со словом эмодзи (или быть его началом).
     * Русские формы понимает: «кошки», «котом».
     */
    fun search(query: String, limit: Int = 120, bonus: (String) -> Float = { 0f }): List<EmojiItem> {
        val q = qnorm(query).split(' ', ',').filter { it.isNotEmpty() }
        if (q.isEmpty()) return emptyList()
        val exact = q.map { lemmas(it) }
        val loose = q.map { t -> singular(t).flatMap { lemmas(it) } }
        val scored = ArrayList<Pair<EmojiItem, Float>>()
        for (item in items) {
            var total = 0f
            for ((i, t) in q.withIndex()) {
                val s = match(item, t, exact[i], loose[i])
                if (s == 0f) { total = 0f; break }
                total += s
            }
            if (total > 0f) scored.add(item to total + bonus(item.e))
        }
        scored.sortWith(compareByDescending<Pair<EmojiItem, Float>> { it.second }.thenBy { it.first.order })
        return scored.take(limit).map { it.first }
    }

    private fun match(item: EmojiItem, t: String, exact: List<String>, loose: List<String>): Float {
        val short = 2f / item.name.size.coerceAtLeast(1)
        if (exact.any { it in item.chatL }) return 7f + short
        if (exact.any { it in item.nameL }) return 6f + short
        if (exact.any { it in item.wordsL }) return 5f
        if (loose.any { it in item.nameL || it in item.chatL }) return 4.5f
        if (loose.any { it in item.wordsL }) return 4f
        var best = 0f
        for (w in item.name) if (w.startsWith(t)) best = 4f
        if (best > 0f) return best
        for (w in item.words) if (w.startsWith(t)) best = maxOf(best, 2f + t.length.toFloat() / w.length)
        return best
    }

    companion object {
        private const val NAME_W = 3f
        private const val WORD_W = 1.6f
        private const val CHAT_W = 5f
        private const val PART_W = 0.9f
        private const val SPREAD = 0.4f
        private const val MIN_SCORE = 1f

        /** Вкладки: значок и название группы. */
        val GROUPS = mapOf(
            "smileys" to ("😀" to "Смайлики"),
            "people" to ("👋" to "Люди"),
            "animals" to ("🐻" to "Природа"),
            "food" to ("🍔" to "Еда"),
            "travel" to ("🚗" to "Путешествия"),
            "activities" to ("⚽" to "Занятия"),
            "objects" to ("💡" to "Предметы"),
            "symbols" to ("🔣" to "Символы"),
            "flags" to ("🏁" to "Флаги"),
        )

        /** Как в данных: нижний регистр, ё→е, ’→', латиница без диакритики («curaçao» → «curacao»). */
        fun qnorm(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in normalize(s.trim())) {
                when {
                    ch == '’' -> sb.append('\'')
                    ch in 'à'..'ÿ' || ch in 'Ā'..'ž' -> {
                        val d = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD)[0]
                        sb.append(if (d in 'a'..'z') d else ch)
                    }
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}

/** Данные эмодзи приложения: грузятся в фоне при старте, только то, что умеет рисовать шрифт телефона. */
object Emoji {
    @Volatile var data: EmojiData? = null
        private set

    fun load(ctx: android.content.Context) {
        if (data != null) return
        val text = ctx.assets.open("emoji.txt").bufferedReader().use { it.readText() }
        val paint = android.graphics.Paint()
        data = EmojiData(text) { paint.hasGlyph(it) }
    }
}
