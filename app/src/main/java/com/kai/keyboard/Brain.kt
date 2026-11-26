package com.kai.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Заглавные по буквам: «ß» не превращается в «SS», длина слова не меняется. */
fun upperChars(s: String) = buildString(s.length) { for (c in s) append(c.uppercaseChar()) }

/** @param id для CLIP — какая запись буфера (по ней и вставляем, а не по подписи). */
class Cand(val text: String, val kind: Int, val id: Long = 0) {
    companion object {
        const val NORMAL = 0
        const val CORRECTION = 1
        const val TYPED = 2
        const val SWIPE = 3
        const val FORGET = 4
        const val RELAYOUT = 5
        const val CLIP = 6
        const val EMOJI = 7
        const val EMPTY = 8
    }
}

/** Слоты подсказок: [0] — центр, [1] — слева, [2] — справа. */
class Suggestions(val typed: String, val items: List<Cand>, val autocorrect: String?,
                  /** Автоисправление — это слово другого языка: после него переключить раскладку. */
                  val autocorrectLang: Lang? = null)

/** Слово, набранное не в той раскладке. */
class Relayout(val word: String, val lang: Lang, val score: Float)

object Brain : Engine() {
    private val io = Executors.newSingleThreadExecutor()
    /** Распознавание свайпа — в своём потоке, чтобы не ждать загрузку/сохранение и не держать интерфейс. */
    @Volatile private var swipeExecutor = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var initialized = false
    var onReady: (() -> Unit)? = null
    var onEmojiReady: (() -> Unit)? = null
    val clips = Clips()
    lateinit var safe: Safe
        private set
    /**
     * Сейф только что открыли отпечатком (окно поверх приложения) — клавиатура, вернувшись в то же
     * приложение (safeBioPackage), покажет его. Поле — любое: многие приложения (браузер, WebView)
     * при возврате пересоздают поле с другим id. Время — монотонное, идёт и во сне (elapsedRealtime).
     */
    @Volatile var safeOpenedAt = 0L
    @Volatile var safeBioPackage: String? = null
    @Volatile var safeBioStartedAt = 0L
    @Volatile var safeBioPending = false
    @Volatile var onSafeOpened: (() -> Unit)? = null

    fun decodeSwipeAsync(
        lang: Lang, pts: FloatArray, times: FloatArray?, centers: Map<Char, FloatArray>, ctxKey: String?,
        aspect: Float,
        done: (List<String>) -> Unit,
    ): java.util.concurrent.Future<*> = swipeExecutor.submit {
        val found = try {
            decodeSwipe(lang, pts, centers, ctxKey, times, aspect)
        } catch (e: InterruptedException) {
            return@submit
        } catch (e: Exception) {
            android.util.Log.e("Klava", "swipe decode failed", e)
            emptyList()
        }
        if (!Thread.currentThread().isInterrupted) done(found)
    }

    /** Декодер завис: бросаем его поток (он остановится на ближайшей проверке) и заводим новый. */
    fun restartSwipeDecoder() {
        val old = swipeExecutor
        swipeExecutor = Executors.newSingleThreadExecutor()
        old.shutdownNow()
    }

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        val app = ctx.applicationContext
        val vault = Vault.of(app)
        // Ключ, которого не дождался основной поток, загружается здесь же, на общем фоновом потоке.
        Vault.background = { job -> io.execute { runCatching(job) } }
        safe = Safe(File(app.noBackupFilesDir, "safe"), vault)
        // Остаток стороннего свайпа (был в прежних версиях): библиотека и её словари — удалить.
        io.execute { runCatching { File(app.noBackupFilesDir, "gswipe").deleteRecursively() } }
        user = UserStore(File(app.noBackupFilesDir, "learned"), vault)
        // Ключ — заранее, в фоне: первое обращение к хранилищу ключей не должно ждать на экране.
        io.execute { runCatching { vault.seal(ByteArray(0), "warm-up") } }
        io.execute { runCatching { user.load(); user.save() } }
        io.execute { runCatching { File(app.noBackupFilesDir, "learned/learned.tmp").delete() } } // остаток прежней версии
        clips.store = PinStore(File(app.noBackupFilesDir, "pins"), vault) { io.execute { runCatching(it) } }
        io.execute { runCatching { clips.loadPins() } }
        Vault.whenReady { clips.store?.flushPending(); clips.ensurePins() }
        for (lang in listOf(Langs.RU, Langs.EN)) {
            val d = Dictionary(lang)
            dicts[lang.code] = d
            io.execute {
                runCatching { app.assets.open("${lang.code}.dict").use { d.load(it) } }
                // указатель слов с дефисом — заранее, в фоне (иначе — заминка на первом нажатии)
                if (d.ready) runCatching { d.hyphenated("") }
                runCatching { app.assets.open("${lang.code}.bi").use { d.loadContext(it) } }
                main.post { onReady?.invoke() }
            }
        }
        // Эмодзи — после словарей: без них нет подсказок и умных зон, а эмодзи открывают не сразу.
        loadEmoji(app)
    }

    private var emojiLoading = false

    fun loadEmoji(ctx: Context) {
        if (Emoji.data != null || emojiLoading) return
        emojiLoading = true
        val app = ctx.applicationContext
        io.execute {
            runCatching { Emoji.load(app) }
            main.post { emojiLoading = false; onEmojiReady?.invoke() }
        }
    }

    fun background(r: () -> Unit) { io.execute { runCatching(r) } }
    fun onMain(r: () -> Unit) { main.post(r) }

    fun lockSafeLater(ms: Long) {
        val at = safeOpenedAt
        main.postDelayed({ if (safeOpenedAt == at && at != 0L) { safe.lock(); safeOpenedAt = 0L; safeBioPackage = null } }, ms)
    }

    fun saveAsync() {
        if (initialized) io.execute { runCatching { user.save() } }
    }

    fun clearLearned() {
        if (initialized) io.execute { runCatching { user.clear() } }
    }
}

/**
 * Языковая модель клавиатуры: словари + то, что выучено у владельца.
 * Всё работает локально, сети в приложении нет вообще. Без Android — тестируется на JVM.
 */
open class Engine {
    val dicts = HashMap<String, Dictionary>()
    lateinit var user: UserStore

    protected fun dict(lang: Lang): Dictionary? = dicts[lang.code]?.takeIf { it.ready }

    private val tmp = FloatArray(40)
    private val userDist = FloatArray(40)
    private val ctxDist = FloatArray(40)
    private val big = FloatArray(40)

    /**
     * Вероятность следующей буквы с учётом набранной части слова и контекста
     * (предыдущее слово, начало предложения, запятая).
     * Именно она невидимо «увеличивает» вероятные клавиши.
     * @return уверенность 0..1 (0 — такого начала слова нет в словаре, это новое слово),
     *         или -1, если модель недоступна.
     */
    fun letterProbs(lang: Lang, prefix: String, ctxKey: String?, out: FloatArray): Float {
        val d = dict(lang) ?: return -1f
        val norm = normalize(prefix)
        if (!lang.accepts(norm)) return -1f
        val n = lang.size
        java.util.Arrays.fill(tmp, 0f)
        java.util.Arrays.fill(ctxDist, 0f)
        java.util.Arrays.fill(userDist, 0f)
        val dTotal = d.addNextChars(norm, tmp)
        val uniTotal: Float
        val lam: Float
        if (USER_RELATIVE) {
            // Свои слова — отдельным распределением (доли от своих использований, а не абсолютные веса),
            // смешанным со словарём: доля своих растёт с числом использований, но не выше USER_MAX —
            // со временем выученное не раздувает все буквы сразу и не задавливает словарь.
            val uCount = user.addNextCharCounts(lang, norm, userDist)
            lam = when { uCount <= 0f -> 0f; dTotal <= 0f -> 1f; else -> min(USER_MAX, uCount / (uCount + USER_K)) }
            uniTotal = dTotal + min(uCount * 150f, 6000f) // «масса» для уверенности — как раньше, с потолком
        } else {
            lam = 0f
            uniTotal = dTotal + user.addNextChars(lang, norm, tmp)
        }
        val uTot = userDist.sum()

        var ctxTotal = 0f
        val ctx = d.context(ctxKey)
        if (ctx != null) ctxTotal += d.addNextCharsCtx(ctx, norm, ctxDist)
        if (ctxKey != null) {
            val scale = max(1f, (ctx?.total ?: 0f) / 200f)
            val len = norm.length
            user.forEachNext(TextCtx.lastWord(ctxKey)) { w, c ->
                if (w.length > len && w.startsWith(norm)) {
                    val ci = lang.index(w[len])
                    if (ci >= 0) { ctxDist[ci] += c * scale; ctxTotal += c * scale }
                }
            }
        }
        val mu = if (ctxTotal > 0f) 0.7f * ctxTotal / (ctxTotal + 20f) else 0f

        val last = norm.lastOrNull()?.takeIf { lang.index(it) >= 0 }
        d.charBigram(last, big)
        for (i in 0 until n) {
            val uni = if (USER_RELATIVE) {
                val dp = if (dTotal > 0f) tmp[i] / dTotal else 0f
                val up = if (uTot > 0f) userDist[i] / uTot else 0f
                (1 - lam) * dp + lam * up
            } else if (uniTotal > 0f) tmp[i] / uniTotal else 0f
            val cx = if (ctxTotal > 0f) ctxDist[i] / ctxTotal else 0f
            out[i] = if (uniTotal > 0f || ctxTotal > 0f) {
                val p = if (uniTotal > 0f) (1 - mu) * uni + mu * cx else cx
                0.85f * p + 0.12f * big[i] + 0.03f / n
            } else 0.85f * big[i] + 0.15f / n
        }
        // Уверенность растёт с «массой» слов, подходящих под набранное начало:
        // единичные редкие совпадения почти не увеличивают зоны. Но чем длиннее совпавшее
        // начало, тем меньше шанс, что это новое слово («кузнец…» — почти наверняка форма «кузнеца»).
        val support = uniTotal / (uniTotal + CONF_SUPPORT / (1f + CONF_LEN * max(0, norm.length - 3)))
        return 1f - (1f - support) * (1f - mu)
    }


    fun suggest(lang: Lang, typed: String, ctxKey: String?, correct: Boolean, capNext: Boolean): Suggestions? {
        val d = dict(lang) ?: return null
        val ctx = d.context(ctxKey)
        if (typed.isEmpty()) {
            if (ctxKey == null) return Suggestions("", emptyList(), null)
            val p = (user.nextWords(TextCtx.lastWord(ctxKey), 3) + d.topNext(ctx, 5))
                .distinctBy { normalize(it) }.filter { (it.length > 1 || it == "я" || it == "I") && !blockedWord(it) }.take(3)
            return Suggestions("", p.map { Cand(if (capNext) capitalize(it) else it, Cand.NORMAL) }, null)
        }
        val norm = normalize(typed)
        if (!lang.accepts(norm)) return Suggestions(typed, emptyList(), null)

        val scores = HashMap<String, Float>()
        fun put(w: String, s: Float) { if ((scores[w] ?: Float.NEGATIVE_INFINITY) < s) scores[w] = s }
        d.completions(norm, 6) { w, x -> put(w, ln(x)) }
        if (norm.length >= 3 && !norm.contains('-')) d.hyphenCompletions(norm, 2) { w, x -> put(w, ln(x)) }
        if (USER_PRIORITY) {
            val k = userScale(d)
            user.completionShares(norm) { w, sh -> put(w, ln(d.weight(w) + k * sh)) }
        } else user.completions(norm) { w, x -> put(w, ln(x + d.weight(w))) }

        val known = d.contains(norm) || user.isKnown(norm)
        val corr = HashMap<String, Float>()
        if (!known && norm.length >= 2) d.corrections(norm, corr)
        // Дефис уже поставлен («кто-»): дописывает слово с дефисом — не «исправляем» на слово без него.
        if (norm.endsWith('-')) corr.clear()
        // Набрал без дефиса то, что пишут с дефисом («ктото», «помоему»): такое написание есть в словаре
        // (из текстов с опечатками), но с дефисом — намного чаще. Исправляем на пробеле, дефис набирать не нужно.
        val hy = if (!norm.contains('-') && norm.length >= 3 && !user.isKnown(norm)) d.hyphenated(norm) else null
        val hyFix = hy != null && hy.second > HYPHEN_RATIO * d.weight(norm)
        scores.keys.removeAll { blockedWord(it) }
        corr.keys.removeAll { blockedWord(it) }

        fun boost(w: String): Float = d.contextBoost(ctx, w) + userBigramBoost(ctxKey, w)
        for (k in scores.keys.toList()) scores[k] = scores[k]!! + boost(k)
        for (k in corr.keys.toList()) corr[k] = corr[k]!! + boost(k)

        var auto: String? = null
        var bestCorr = Float.NEGATIVE_INFINITY
        if (hyFix && !blockedWord(hy!!.first)) {
            if (correct) auto = hy.first
            scores[hy.first] = Float.MAX_VALUE / 4
        } else if (correct && corr.isNotEmpty()) {
            val best = corr.maxByOrNull { it.value }!!
            bestCorr = best.value
            if (best.value > autocorrectThreshold()) auto = best.key
        }
        // Забыл переключить язык: «ghbdtn» — не слово, а «привет» — очень даже.
        // Только если в этом языке с такого начала не начинается ни одно слово — иначе это просто недописанное слово.
        val rl = if (!known && norm.length >= 2 && scores.isEmpty()) relayout(lang, norm) else null
        if (rl != null) {
            val items = ArrayList<Cand>(3)
            items.add(Cand(applyCase(rl.word, typed), Cand.RELAYOUT))
            items.add(Cand(typed, Cand.TYPED))
            val fix = correct && norm.length >= 3 && rl.score > autocorrectThreshold() && rl.score > bestCorr
            val other = (corr.entries.sortedByDescending { it.value }.map { it.key } + scores.keys).firstOrNull()
            if (other != null) items.add(Cand(applyCase(other, typed), Cand.NORMAL))
            return Suggestions(typed, items, if (fix) applyCase(rl.word, typed) else null, if (fix) rl.lang else null)
        }

        val ranked = LinkedHashMap<String, Float>()
        for ((w, s) in scores) ranked[w] = s
        for ((w, s) in corr) if ((ranked[w] ?: Float.NEGATIVE_INFINITY) < s - 0.5f) ranked[w] = s - 0.5f
        val order = ranked.entries.sortedByDescending { it.value }.map { it.key }

        val items = ArrayList<Cand>(3)
        val seen = HashSet<String>()
        if (auto != null) {
            items.add(Cand(applyCase(auto, typed), Cand.CORRECTION))
            items.add(Cand(typed, Cand.TYPED))
            seen.add(normalize(auto)); seen.add(norm)
        } else if (known) {
            // Набранное слово существует — оно в центре, на случай если подсказки хуже.
            val exact = order.firstOrNull { normalize(it) == norm }
            if (exact != null && order.indexOf(exact) > 0) {
                items.add(Cand(applyCase(exact, typed), Cand.NORMAL)); seen.add(norm)
            }
        }
        for (w in order) {
            if (items.size >= 3) break
            val k = normalize(w)
            if (k in seen) continue
            seen.add(k)
            items.add(Cand(applyCase(w, typed), Cand.NORMAL))
        }
        // Готовое короткое слово, с которого часто начинается слово с дефисом («во» → «во-первых», «из» → «из-за»):
        // предлагаем его, даже если обычные продолжения частотнее, — дефис набирать неудобно.
        if (known && auto == null && !norm.contains('-') && norm.length <= 6 && items.none { it.text.contains('-') }) {
            var hc: Pair<String, Float>? = null
            d.completions("$norm-", 1) { w, x -> if (!blockedWord(w)) hc = w to x }
            val h = hc
            if (h != null && h.second >= HYPHEN_SHOW * d.weight(norm)) {
                val c = Cand(applyCase(h.first, typed), Cand.NORMAL)
                if (items.size >= 3) items[2] = c else items.add(c)
            }
        }
        return Suggestions(typed, items, auto?.let { applyCase(it, typed) })
    }

    /**
     * Слово, набранное не в той раскладке: переводим буквы в другую раскладку и ищем в другом словаре.
     * Буква переводится как на компьютере (q→й, ;→ж) или по месту на нашей клавиатуре —
     * у русской 11 колонок, у английской 10, и большой палец промахивается по-своему.
     */
    fun relayout(lang: Lang, norm: String): Relayout? {
        val to = if (lang == Langs.RU) Langs.EN else Langs.RU
        val od = dict(to) ?: return null
        if (user.isKnown(norm)) return null
        val opts = Array(norm.length) { i -> LayoutMap.options(lang, norm[i]) ?: return null }
        var best: String? = null
        var bestScore = Float.NEGATIVE_INFINITY
        val sb = StringBuilder()
        fun dfs(i: Int, cost: Float) {
            if (i == norm.length) {
                val w = sb.toString()
                val wt = od.weight(w)
                if (wt > 0f && !blockedWord(w)) {
                    val s = ln(wt) - cost
                    if (s > bestScore) { bestScore = s; best = w }
                }
                return
            }
            for ((c, extra) in opts[i]) {
                if (cost + extra > LayoutMap.MAX_COST) continue
                sb.append(c)
                if (od.hasPrefix(sb.toString())) dfs(i + 1, cost + extra)
                sb.setLength(sb.length - 1)
            }
        }
        dfs(0, 0f)
        val w = best ?: return null
        return Relayout(od.display(w), to, bestScore)
    }

    /** Распознаёт слово по пути пальца. */
    /** Подстройка свайпа под руку владельца (включено — сдвиг вычитается из жеста и учится). */
    var swipeAdapt = true

    private fun rowYs(centers: Map<Char, FloatArray>): FloatArray =
        centers.values.map { it[1] }.distinct().sorted().toFloatArray()

    fun adjustSwipe(pts: FloatArray, centers: Map<Char, FloatArray>): FloatArray {
        if (!swipeAdapt) return pts
        val rows = rowYs(centers)
        if (rows.isEmpty()) return pts
        val off = Array(rows.size) { user.swipeOffset(it) ?: FloatArray(2) }
        if (off.all { it[0] == 0f && it[1] == 0f }) return pts
        val out = pts.copyOf()
        for (i in 0 until pts.size / 2) {
            val y = pts[2 * i + 1]
            var r = 0
            while (r < rows.size - 1 && y > rows[r + 1]) r++
            val f = if (r < rows.size - 1) ((y - rows[r]) / (rows[r + 1] - rows[r])).coerceIn(0f, 1f) else 0f
            val o2 = off[minOf(r + 1, rows.size - 1)]
            out[2 * i] -= off[r][0] * (1 - f) + o2[0] * f
            out[2 * i + 1] -= off[r][1] * (1 - f) + o2[1] * f
        }
        return out
    }

    fun learnSwipe(pts: FloatArray, word: String, centers: Map<Char, FloatArray>) {
        if (!swipeAdapt) return
        val rows = rowYs(centers)
        val letters = ArrayList<FloatArray>()
        for (c in normalize(word)) {
            val p = centers[c] ?: return
            if (letters.isEmpty() || letters.last() !== p) letters.add(p)
        }
        if (letters.size < 2 || pts.size < 8) return
        val n = 64
        val g = FloatArray(2 * n)
        Swipe.resample(pts, pts.size / 2, n, g)
        val cum = FloatArray(letters.size)
        for (j in 1 until letters.size)
            cum[j] = cum[j - 1] + kotlin.math.hypot(letters[j][0] - letters[j - 1][0], letters[j][1] - letters[j - 1][1])
        val total = cum.last().takeIf { it > 0f } ?: return
        val sum = Array(rows.size) { FloatArray(3) } // dx, dy, сколько
        var from = 0
        for ((j, p) in letters.withIndex()) {
            val at = (cum[j] / total * (n - 1)).toInt()
            var best = -1; var bd = Float.MAX_VALUE
            for (i in maxOf(from, at - 10)..minOf(n - 1, at + 10)) {
                val d = kotlin.math.hypot(g[2 * i] - p[0], g[2 * i + 1] - p[1])
                if (d < bd) { bd = d; best = i }
            }
            if (best < 0 || bd > 0.9f) continue // далеко — не учимся на этой букве
            from = best
            val r = rows.indexOfFirst { kotlin.math.abs(it - p[1]) < 1e-3f }.takeIf { it >= 0 } ?: continue
            sum[r][0] += g[2 * best] - p[0]; sum[r][1] += g[2 * best + 1] - p[1]; sum[r][2] += 1f
        }
        for (r in rows.indices) if (sum[r][2] > 0f)
            user.learnSwipeOffset(r, sum[r][0] / sum[r][2], sum[r][1] / sum[r][2], SWIPE_ALPHA)
    }

    fun decodeSwipe(lang: Lang, pts: FloatArray, centers: Map<Char, FloatArray>, ctxKey: String?,
                    times: FloatArray? = null, aspect: Float = 1.45f): List<String> {
        val d = dict(lang) ?: return emptyList()
        val ctx = d.context(ctxKey)
        return Swipe.decode(d, adjustSwipe(pts, centers), centers, times0 = times, aspect = aspect,
            boost = { w -> d.contextBoost(ctx, w) + userBigramBoost(ctxKey, w) },
            extra = { add ->
                if (USER_PRIORITY) {
                    val k = userScale(d)
                    user.completionShares("") { w, sh -> if (lang.accepts(w)) add(w, d.weight(w) + k * sh) }
                } else user.completions("") { w, x -> if (lang.accepts(w)) add(w, x + d.weight(w)) }
            },
        ).filter { !blockedWord(it) }
    }

    private fun blockedWord(w: String) = user.isBlocked(normalize(w))

    /**
     * Во сколько «весов словаря» переводится доля своего слова: свои слова — отдельное распределение,
     * смешанное со словарём в пропорции λ : (1 − λ), λ = N / (N + PRI_K) (N — сколько раз владелец набирал
     * свои слова), не больше PRI_MAX. Пока выучено мало — свои доли шумные, и вес у них маленький.
     */
    private fun userScale(d: Dictionary): Float {
        val n = user.uses().toFloat()
        if (n <= 0f) return 0f
        val lam = min(PRI_MAX, n / (n + PRI_K))
        return lam / (1 - lam) * d.totalWeight()
    }

    private fun userBigramBoost(ctxKey: String?, w: String): Float {
        if (ctxKey == null) return 0f
        val prev = TextCtx.lastWord(ctxKey)
        if (!USER_PRIORITY) return if (user.bigram(prev, normalize(w)) > 0) 1.5f else 0f
        val c = user.bigram(prev, normalize(w))
        if (c <= 0) return 0f
        return ln(1f + BI_GAIN * user.bigramShare(prev, normalize(w)) * c / (c + 1f))
    }

    /** Последнее выученное слово — чтобы отменить, если владелец его сотрёт. */
    private var lastLearned: Pair<String, String?>? = null

    fun unlearnLast(word: String) {
        val l = lastLearned ?: return
        if (l.first != normalize(word)) return
        user.unlearn(l.first, l.second)
        lastLearned = null
    }

    fun forget(word: String) {
        user.forget(normalize(word))
        lastLearned = null
    }

    fun known(lang: Lang, word: String): Boolean {
        val norm = normalize(word)
        return dict(lang)?.contains(norm) == true || user.isKnown(norm)
    }

    fun learn(word: String, ctxKey: String?, lang: Lang) {
        val norm = normalize(word)
        if (norm.length < 2 && norm != "я" && norm != "i") return
        if (!lang.accepts(norm) || norm.startsWith('-') || norm.endsWith('-')) return
        user.addWord(norm)
        val prev = ctxKey?.let { TextCtx.lastWord(it) }
        if (prev != null) user.addBigram(prev, norm)
        lastLearned = norm to prev
    }

    /**
     * Слово закончено: учим словарь и (осторожно) — куда владелец попадает пальцем.
     * @param touches касания букв слова: [код буквы, dx, dy] относительно центра клавиши
     */
    fun onWordFinished(lang: Lang, typed: String, final: String, ctxKey: String?,
                       touches: List<FloatArray>, corrected: Boolean) {
        val wasKnown = known(lang, typed)
        learn(final, ctxKey, lang)
        if (!corrected && wasKnown && touches.size == typed.length &&
            touches.withIndex().all { (i, t) -> t[0].toInt().toChar() == typed[i].lowercaseChar() }
        ) {
            for (t in touches) user.learnOffset(t[0].toInt().toChar(), t[1], t[2])
        }
    }

    fun learnRejected(word: String) {
        user.addWord(normalize(word), minCount = 2)
    }

    fun applyCase(word: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> upperChars(word)
        typed.isNotEmpty() && typed[0].isUpperCase() -> capitalize(word)
        else -> word
    }

    private fun capitalize(w: String) = w.replaceFirstChar { it.uppercaseChar() }

    /** Сила автоисправления 1..100 (0 — выключено, это решает вызывающий через correct=false). 50 — по умолчанию. */
    var autocorrectLevel = 50

    // 50 → исправляем на слово с частотой от ~90 употреблений (после штрафа за правку);
    // каждые ±10 делений — порог в ~1.8 раза ниже/выше.
    private fun autocorrectThreshold() = ln(30f) + (50 - autocorrectLevel) * 0.06f

    companion object {
        private const val CONF_SUPPORT = 300f
        private const val CONF_LEN = 1.0f
        /** Во сколько раз слово с дефисом должно быть частотнее того же без дефиса, чтобы исправлять. */
        private const val HYPHEN_RATIO = 3f
        /** Слово с дефисом в подсказках к готовому короткому слову — если оно хотя бы такая доля его частоты. */
        private const val HYPHEN_SHOW = 0.08f
        /** Свои слова в вероятностях букв — долей (USER_RELATIVE) или абсолютным весом (как было). */
        var USER_RELATIVE = true
        /** Доля своих слов: uCount / (uCount + USER_K), не выше USER_MAX. */
        var USER_K = 20f
        var USER_MAX = 0.6f
        /** Подсказки и свайп: свои слова — долей среди своих (USER_PRIORITY) или «150 × число, до 40 раз» (как было). */
        var USER_PRIORITY = true
        var PRI_K = 300f
        var PRI_MAX = 0.4f
        /** Своё продолжение после слова: прибавка ln(1 + BI_GAIN · доля · c/(c+1)). */
        var BI_GAIN = 6f
        /** Насколько одно принятое слово сдвигает подстройку свайпа (подобрано в SwipeSimTest). */
        var SWIPE_ALPHA = 0.1f
    }
}
