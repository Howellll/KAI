package com.kai.keyboard

import android.content.Context
import com.kai.keyboard.data.ai.AiAction
import com.kai.keyboard.data.ai.AiModel
import com.kai.keyboard.data.ai.AiPrompts
import com.kai.keyboard.data.ai.AiStore
import com.kai.keyboard.data.ai.Builtin
import com.kai.keyboard.data.ai.withBuiltinActions

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("klava", Context.MODE_PRIVATE)

    /** Подписка на изменения настроек. Слушателя нужно хранить: SharedPreferences держит его слабой ссылкой. */
    fun listen(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unlisten(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setB(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    var vibrate: Boolean get() = b(VIBRATE, true); set(v) = setB(VIBRATE, v)
    var sound: Boolean get() = b(SOUND, false); set(v) = setB(SOUND, v)
    var autocap: Boolean get() = b(AUTOCAP, true); set(v) = setB(AUTOCAP, v)
    /** 0 — автоисправление выключено, 50 — обычное, 100 — самое смелое. */
    var autocorrectLevel: Int
        get() = sp.getInt(AUTOCORRECT_LEVEL, if (b(AUTOCORRECT, true)) 50 else 0)
        set(v) = sp.edit().putInt(AUTOCORRECT_LEVEL, v).apply()
    var suggestions: Boolean get() = b(SUGGESTIONS, true); set(v) = setB(SUGGESTIONS, v)
    var smartTargets: Boolean get() = b(SMART, true); set(v) = setB(SMART, v)
    var learn: Boolean get() = b(LEARN, true); set(v) = setB(LEARN, v)
    var doubleSpace: Boolean get() = b(DOUBLE_SPACE, true); set(v) = setB(DOUBLE_SPACE, v)
    var preview: Boolean get() = b(PREVIEW, true); set(v) = setB(PREVIEW, v)
    var swipe: Boolean get() = b(SWIPE, true); set(v) = setB(SWIPE, v)
    var showZones: Boolean get() = b(ZONES, true); set(v) = setB(ZONES, v)
    var altStyle: Boolean get() = b(ALT_STYLE, true); set(v) = setB(ALT_STYLE, v)
    /** Стиль 3: стиль 2 с 🌐 между точкой и Enter, № рядом с # и цифрами с 5 на н…х (только при altStyle). По умолчанию включён. */
    var style3: Boolean get() = b(STYLE3, true) && altStyle; set(v) = setB(STYLE3, v)
    var styleIndex: Int
        get() = if (!b(ALT_STYLE, true)) 0 else if (b(STYLE3, true)) 2 else 1
        set(v) { sp.edit().putBoolean(ALT_STYLE, v >= 1).putBoolean(STYLE3, v == 2).apply() }
    var doubleSymbols: Boolean get() = b(DOUBLE_SYMBOLS, false); set(v) = setB(DOUBLE_SYMBOLS, v)
    /** Выключено по умолчанию: скопированное — личное, пусть владелец включит сам. */
    var clipHistory: Boolean get() = b(CLIP_HISTORY, false); set(v) = setB(CLIP_HISTORY, v)
    var langSwipe: Boolean get() = b(LANG_SWIPE, false); set(v) = setB(LANG_SWIPE, v)
    var emojiSuggest: Boolean get() = b(EMOJI_SUGGEST, true); set(v) = setB(EMOJI_SUGGEST, v)
    var clipTtlMin: Int
        get() = sp.getInt(CLIP_TTL, 60)
        set(v) = sp.edit().putInt(CLIP_TTL, v).apply()
    var numberRow: Boolean get() = b(NUMBER_ROW, false); set(v) = setB(NUMBER_ROW, v)
    var lpSymbols: Boolean get() = b(LP_SYMBOLS, false); set(v) = setB(LP_SYMBOLS, v)
    /**
     * Кнопка 📋 в нижнем ряду букв (рядом с 🌐). Выключена по умолчанию: панель буфера и так
     * открывается кнопкой на клавиатуре символов, а нижний ряд — тесное место.
     */
    var clipBottomKey: Boolean get() = b(CLIP_BOTTOM_KEY, false); set(v) = setB(CLIP_BOTTOM_KEY, v)
    /**
     * Кнопка ИИ (а при выключенном ИИ — 😊) в полосе подсказок у правого края.
     * В режиме одной рукой не действует: там кнопка идёт за клавишами.
     */
    var aiKeyRight: Boolean get() = b(AI_KEY_RIGHT, true); set(v) = setB(AI_KEY_RIGHT, v)
    var reorder: Boolean get() = b(REORDER, true); set(v) = setB(REORDER, v)

    var emojiTones: Map<String, String>
        get() = (secret(EMOJI_TONES) ?: "").split(' ').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        set(v) = setSecret(EMOJI_TONES, v.entries.joinToString(" ") { "${it.key}=${it.value}" })

    var theme: String
        get() = sp.getString(THEME, Theme.BLACK.id) ?: Theme.BLACK.id
        set(v) = sp.edit().putString(THEME, v).apply()

    var accent: Int
        get() = sp.getInt(ACCENT, Accent.DEFAULT)
        set(v) = sp.edit().putInt(ACCENT, v).apply()

    var deleteWordsAfter: Int
        get() = sp.getInt(DELETE_WORDS_AFTER, 1000)
        set(v) = sp.edit().putInt(DELETE_WORDS_AFTER, v).apply()

    var deleteWordPause: Int
        get() = sp.getInt(DELETE_WORD_PAUSE, 500)
        set(v) = sp.edit().putInt(DELETE_WORD_PAUSE, v).apply()

    var height: Int
        get() = sp.getInt(HEIGHT, 100)
        set(v) = sp.edit().putInt(HEIGHT, v).apply()
    var keyGap: Int
        get() = sp.getInt(KEY_GAP, 12)
        set(v) = sp.edit().putInt(KEY_GAP, v).apply()

    var oneHand: Int
        get() = sp.getInt(ONE_HAND, 0)
        set(v) = sp.edit().putInt(ONE_HAND, v).apply()

    var lang: String
        get() = sp.getString(LANG, "ru") ?: "ru"
        set(v) = sp.edit().putString(LANG, v).apply()

    // ---- ИИ (по умолчанию всё выключено) ----
    var aiEnabled: Boolean get() = b(AI_ENABLED, false); set(v) = setB(AI_ENABLED, v)
    var aiBaseUrl: String get() = sp.getString(AI_BASE_URL, "") ?: ""; set(v) = sp.edit().putString(AI_BASE_URL, v.trim()).apply()
    var aiTemperature: Float get() = sp.getFloat(AI_TEMPERATURE, 0.3f); set(v) = sp.edit().putFloat(AI_TEMPERATURE, v).apply()
    var aiTargetLang: String get() = sp.getString(AI_TARGET_LANG, "") ?: ""; set(v) = sp.edit().putString(AI_TARGET_LANG, v.trim()).apply()

    /**
     * Модели для запросов, до шести. Адрес сервера и API-ключ у всех общие: они хранятся отдельно.
     * Запись — «модель\tназвание» на модель, строки через перевод строки. Первая модель — основная.
     */
    var aiModels: List<AiModel>
        get() {
            val raw = sp.getString(AI_MODELS, "").orEmpty()
            if (raw.isBlank()) {
                // Настройки прежних версий: одна модель без названия.
                val old = sp.getString(AI_MODEL, "").orEmpty().trim()
                return if (old.isEmpty()) emptyList() else listOf(AiModel(old))
            }
            return raw.split('\n').mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val p = line.split('\t')
                val m = p[0].trim()
                if (m.isEmpty()) null else AiModel(m, p.getOrNull(1).orEmpty().trim())
            }.take(AiModel.MAX)
        }
        set(v) = sp.edit().putString(AI_MODELS,
            v.filter { it.ready }.take(AiModel.MAX)
                .joinToString("\n") { it.model.trim() + "\t" + it.title.trim() }).apply()


    fun aiActionsFor(model: String): List<AiAction> = aiActions.filter { it.enabled && it.forModel(model) }

    fun aiModelsFor(action: AiAction): List<AiModel> {
        if (action.models.isEmpty()) return aiModels
        return aiModels.filter { it.shown in action.models }
    }

    fun removeAiModel(index: Int) {
        val list = aiModels
        val victim = list.getOrNull(index) ?: return
        // По номеру, а не по ссылке: геттер aiModels каждый раз собирает новые объекты,
        // и сравнение `!==` с объектом из другого чтения не удаляло ничего.
        aiModels = list.filterIndexed { i, _ -> i != index }
        val name = victim.shown
        if (aiModels.any { it.shown == name }) { aiSolo = aiSolo.map { if (it >= 0) -1 else it }; return }
        val changed = aiActions.filter { name in it.models }
        if (changed.isNotEmpty()) {
            aiActions = aiActions.map { a ->
                if (name !in a.models) a else a.copy(models = a.models.filter { it != name }
                    .let { if (it.isEmpty() && a.models.size > 1) emptyList() else it })
            }
        }
        aiSolo = aiSolo.map { if (it >= 0) -1 else it }
    }

    /**
     * Действия: три стандартных всегда на месте и идут первыми, дальше — свои (их владелец заводит сам).
     * Запись — по действию в строке, экранирование и разбор — в [AiStore] (промт бывает многострочным).
     * Пятое поле появилось позже: нет его — настройка подсветки ещё не тронута (значение по умолчанию).
     */
    var aiActions: List<AiAction>
        get() {
            val raw = sp.getString(AI_ACTIONS, "").orEmpty()
            val stored = raw.split('\n').mapNotNull { AiStore.parse(it) }
            val full = withBuiltinActions(stored)
            // Список ещё не сохраняли этой версией: состояние и промты стандартных действий переносим со старых ключей.
            if (raw.isBlank()) return full.map { a -> a.copy(prompt = legacyPrompt(a), enabled = a.enabled && legacyOn(a)) }
            return full
        }
        set(v) {
            sp.edit().putString(AI_ACTIONS, withBuiltinActions(v).joinToString("\n") { AiStore.line(it) }).apply()
        }

    fun aiPrompt(a: AiAction): String = a.prompt.ifBlank { a.builtin?.let { AiPrompts.default(it) } ?: "" }

    private fun legacyOn(a: AiAction): Boolean = when (a.builtin) {
        Builtin.SPELL -> b(AI_SPELL, true)
        Builtin.TRANSLATE -> b(AI_TRANSLATE, true)
        Builtin.EDIT -> b(AI_EDIT, true)
        null -> true
    }

    /** Промт стандартного действия, написанный в прежней версии (тогда они хранились по имени действия). */
    private fun legacyPrompt(a: AiAction): String {
        val b = a.builtin ?: return a.prompt
        return sp.getString(AI_PROMPT + b.name.uppercase(), "").orEmpty().ifBlank { a.prompt }
    }

    val aiEnabledActions: List<AiAction> get() = aiActions.filter { it.enabled }

    /**
     * Какой столбец оставлен в одиночку на каждой вкладке формы ИИ (-1 — показывать оба).
     * Владелец оставляет столбец удержанием его названия — выбор запоминается.
     */
    var aiSolo: List<Int>
        get() = sp.getString(AI_SOLO, "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        set(v) = sp.edit().putString(AI_SOLO, v.joinToString(",")).apply()

    /** Выключение истории буфера стирает и закреплённое: в переносе это отметка, а не «нет истории». */
    var clipsOff: Boolean
        get() = sp.getBoolean(CLIPS_OFF, false)
        set(v) = setB(CLIPS_OFF, v)

    var aiSideLeft: List<String>
        get() = AiShortcut.parse(sp.getString(AI_SIDE_L, ""))
        set(v) = sp.edit().putString(AI_SIDE_L, AiShortcut.join(v)).apply()
    var aiSideRight: List<String>
        get() = AiShortcut.parse(sp.getString(AI_SIDE_R, ""))
        set(v) = sp.edit().putString(AI_SIDE_R, AiShortcut.join(v)).apply()

    fun saveAiAction(a: AiAction) { aiActions = aiActions.map { if (it.id == a.id) a.copy(builtin = it.builtin) else it } }

    fun addAiAction(now: Long): String? {
        val cur = aiActions
        if (cur.count { it.custom } >= AiAction.MAX_CUSTOM) return null
        var n = now
        var id = AiAction.newId(n)
        while (cur.any { it.id == id }) { n++; id = AiAction.newId(n) }
        aiActions = cur + AiAction(id)
        return id
    }

    fun removeAiAction(id: String): Boolean {
        val a = aiActions.firstOrNull { it.id == id } ?: return false
        if (!a.custom) return false
        aiActions = aiActions.filter { it.id != id }
        return true
    }

    /** API-ключ хранится зашифрованным (Keystore) тем же способом, что выученные слова. Пусто — ключа нет или он сейчас недоступен. */
    var aiKey: String
        get() = secret(AI_KEY) ?: ""
        set(v) = setSecret(AI_KEY, v.trim())
    val hasAiKey: Boolean get() = sp.getString(AI_KEY, "").orEmpty().isNotEmpty()
    fun deleteAiKey() { sp.edit().remove(AI_KEY).apply() }
    /** ИИ можно использовать: включён, есть адрес и хотя бы одна модель. */
    val aiReady: Boolean get() = aiEnabled && aiBaseUrl.isNotBlank() && aiModels.any { it.ready }

    // Недавние эмодзи и цвета кожи — сведения о владельце: в настройках лежат зашифрованными.
    private val vault = Vault.of(ctx)
    /** null — ключ сейчас недоступен (значение на месте, но не прочитать). Старое открытое — перешифровываем. */
    private fun secret(key: String): String? {
        val raw = sp.getString(key, "")!!
        if (raw.isEmpty()) return ""
        return when (val r = vault.openString(raw, key)) {
            is Vault.Read.Ok -> String(r.bytes, Charsets.UTF_8).also { secretsReadable = true; if (r.legacy) setSecret(key, it) }
            Vault.Read.NoKey -> { secretsReadable = false; null }
            else -> ""
        }
    }

    @Volatile var secretsReadable = true
        private set

    private fun setSecret(key: String, v: String) {
        // Прежнее значение сейчас не прочитать — не затираем его тем, что собрано без него (и пустым тоже).
        val raw = sp.getString(key, "")!!
        if (raw.isNotEmpty() && vault.openString(raw, key) == Vault.Read.NoKey) return
        val sealed = if (v.isEmpty()) "" else vault.sealString(v, key) ?: return
        sp.edit().putString(key, sealed).apply()
    }

    fun exportSettings(): Map<String, Any> = linkedMapOf(
        VIBRATE to vibrate, SOUND to sound, AUTOCAP to autocap, SUGGESTIONS to suggestions, SMART to smartTargets,
        LEARN to learn, DOUBLE_SPACE to doubleSpace, PREVIEW to preview, SWIPE to swipe, ZONES to showZones,
        ALT_STYLE to altStyle, CLIP_HISTORY to clipHistory, LANG_SWIPE to langSwipe, EMOJI_SUGGEST to emojiSuggest,
        NUMBER_ROW to numberRow, LP_SYMBOLS to lpSymbols, CLIP_BOTTOM_KEY to clipBottomKey,
        AI_KEY_RIGHT to aiKeyRight, REORDER to reorder,
        STYLE3 to b(STYLE3, true), DOUBLE_SYMBOLS to doubleSymbols,
        AUTOCORRECT_LEVEL to autocorrectLevel, CLIP_TTL to clipTtlMin, ACCENT to accent,
        DELETE_WORDS_AFTER to deleteWordsAfter, DELETE_WORD_PAUSE to deleteWordPause, HEIGHT to height,
        KEY_GAP to keyGap, ONE_HAND to oneHand, THEME to theme, LANG to lang,
        AI_ENABLED to aiEnabled, AI_BASE_URL to aiBaseUrl, AI_MODELS to aiModelsRaw,
        AI_ACTIONS to sp.getString(AI_ACTIONS, "").orEmpty(), AI_TARGET_LANG to aiTargetLang,
        AI_TEMPERATURE to aiTemperature, AI_SOLO to sp.getString(AI_SOLO, "").orEmpty(),
        AI_SIDE_L to sp.getString(AI_SIDE_L, "").orEmpty(), AI_SIDE_R to sp.getString(AI_SIDE_R, "").orEmpty(),
        CLIPS_OFF to (sp.getBoolean(CLIPS_OFF, false) || !clipHistory),
    )

    /**
     * ИИ переносится целиком, кроме API-ключа: он зашифрован ключом этого телефона и на другом
     * не прочитается — владелец введёт его там заново. Смысл в том, чтобы после переноса не
     * заводить заново адрес сервера, модели и промты действий.
     */
    fun importAi(m: Map<String, Any>): Int {
        val e = sp.edit()
        var n = 0
        for ((k, v) in m) when (k) {
            AI_ENABLED -> if (v is Boolean) { e.putBoolean(AI_ENABLED, v); n++ }
            AI_BASE_URL, AI_ACTIONS, AI_SOLO, AI_SIDE_L, AI_SIDE_R ->
                if (v is String) { e.putString(k, v); n++ }
            AI_MODELS -> if (v is String) { e.putString(AI_MODELS, v); n++ } // сырая запись: содержит табуляции
            AI_TARGET_LANG -> if (v is String) { e.putString(AI_TARGET_LANG, v); n++ }
            AI_TEMPERATURE -> if (v is Int) { e.putFloat(AI_TEMPERATURE, v / 100f); n++ }
            CLIPS_OFF -> if (v is Boolean) { e.putBoolean(CLIPS_OFF, v); n++ }
        }
        e.apply()
        return n
    }

    internal val aiModelsRaw: String
        @JvmName("modelsRawForExport")
        get() = sp.getString(AI_MODELS, "").orEmpty().ifBlank {
            aiModels.joinToString("\n") { it.model.trim() + "\t" + it.title.trim() }
        }

    fun importSettings(m: Map<String, Any>): Int {
        val known = exportSettings()
        val e = sp.edit()
        var n = 0
        for ((k, v) in m) {
            val cur = known[k] ?: continue
            when {
                cur is Boolean && v is Boolean -> e.putBoolean(k, v)
                cur is Int && v is Int -> e.putInt(k, v)
                cur is String && v is String -> e.putString(k, v)
                else -> continue
            }
            n++
        }
        e.apply()
        return n
    }

    fun exportEmoji(): List<String> {
        val out = ArrayList<String>()
        val r = recentEmoji
        if (r.isNotEmpty()) out.add("r\t" + r.joinToString(" "))
        val t = emojiTones
        if (t.isNotEmpty()) out.add("t\t" + t.entries.joinToString(" ") { "${it.key}=${it.value}" })
        return out
    }

    fun importEmoji(lines: List<String>) {
        for (line in lines) {
            val p = line.split('\t', limit = 2)
            if (p.size != 2) continue
            when (p[0]) {
                "r" -> recentEmoji = (p[1].split(' ').filter { it.isNotEmpty() } + recentEmoji).distinct().take(64)
                "t" -> emojiTones = p[1].split(' ').mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
                }.toMap() + emojiTones
            }
        }
    }

    fun clearEmojiHistory() { sp.edit().remove(RECENT).remove(EMOJI_TONES).apply() }

    var recentEmoji: List<String>
        get() = (secret(RECENT) ?: "").split(' ').filter { it.isNotEmpty() }
        set(v) = setSecret(RECENT, v.joinToString(" "))

    companion object {
        const val VIBRATE = "vibrate"
        const val SOUND = "sound"
        const val AUTOCAP = "autocap"
        const val AUTOCORRECT = "autocorrect"
        const val AUTOCORRECT_LEVEL = "autocorrect_level"
        const val SUGGESTIONS = "suggestions"
        const val SMART = "smart_targets"
        const val LEARN = "learn"
        const val DOUBLE_SPACE = "double_space"
        const val PREVIEW = "preview"
        const val ZONES = "zones"
        const val SWIPE = "swipe"
        const val ALT_STYLE = "alt_style"
        const val STYLE3 = "style3"
        const val DOUBLE_SYMBOLS = "double_symbols"
        const val LANG_SWIPE = "lang_swipe"
        const val CLIP_HISTORY = "clip_history"
        const val HEIGHT = "height"
        const val KEY_GAP = "key_gap"
        const val DELETE_WORDS_AFTER = "delete_words_after"
        const val DELETE_WORD_PAUSE = "delete_word_pause"
        const val THEME = "theme"
        const val ACCENT = "accent"
        const val LANG = "lang"
        const val ONE_HAND = "one_hand"
        const val RECENT = "recent_emoji"
        const val EMOJI_TONES = "emoji_tones"
        const val EMOJI_SUGGEST = "emoji_suggest"
        const val REORDER = "reorder"
        const val LP_SYMBOLS = "lp_symbols"
        const val CLIP_BOTTOM_KEY = "clip_bottom_key"
        const val AI_KEY_RIGHT = "ai_key_right"
        const val NUMBER_ROW = "number_row"
        const val CLIP_TTL = "clip_ttl"
        const val AI_ENABLED = "ai_enabled"
        const val AI_CONSENT = "ai_consent"
        const val AI_BASE_URL = "ai_base_url"
        const val AI_MODEL = "ai_model"
        const val AI_MODELS = "ai_models"
        const val CLIPS_OFF = "clips_off"
        const val AI_ACTIONS = "ai_actions"
        const val AI_SOLO = "ai_solo"
        const val AI_SIDE_L = "ai_side_left"
        const val AI_SIDE_R = "ai_side_right"
        const val AI_TEMPERATURE = "ai_temperature"
        const val AI_SPELL = "ai_spell"
        const val AI_TRANSLATE = "ai_translate"
        const val AI_EDIT = "ai_edit"
        const val AI_TARGET_LANG = "ai_target_lang"
        const val AI_PROMPT = "ai_prompt_"
        const val AI_KEY = "ai_key"
    }
}
