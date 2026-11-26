package com.kai.keyboard.data.ai

/**
 * Стандартные действия: их нельзя удалить, у каждого есть название (в строках ресурсов) и промт по умолчанию.
 * [id] — то, под чем действие лежит в настройках.
 */
enum class Builtin(val id: String) {
    SPELL("spell"), TRANSLATE("translate"), EDIT("edit");

    companion object {
        fun of(id: String): Builtin? = values().firstOrNull { it.id == id }
    }
}

/**
 * Действие кнопки ИИ. Стандартные три создаются сами и помечены [builtin]: их название берут из строк,
 * удалить их нельзя, а промт по умолчанию подставляется, пока владелец не написал свой.
 * Свои действия владелец называет сам; промт у нового действия пустой — без него действие не запустится.
 */
data class AiAction(
    val id: String,
    val title: String = "",
    val prompt: String = "",
    val enabled: Boolean = true,
    val builtin: Builtin? = null,
    /**
     * Настройка «Выделять визуально изменения текста», как её выставил владелец;
     * null — ещё не трогали, тогда значение по умолчанию берётся из [showsDiff].
     */
    val highlight: Boolean? = null,
    /**
     * «Принадлежность модели»: каким моделям действие адресовано — список названий моделей
     * ([AiModel.model]). Пусто — действие доступно всем моделям (в том числе настройки прежних версий,
     * где выбора не было). Модель без единого своего действия в форму ИИ не попадает вовсе.
     */
    val models: List<String> = emptyList(),
) {
    val custom get() = builtin == null

    /**
     * Действие адресовано модели с этим названием: список пуст — значит, настройку ещё не трогали
     * и действие доступно всем моделям. [model] — то же название, что показано в форме ИИ
     * ([AiModel.shown]): владелец отмечает именно его, а не техническое имя модели.
     */
    fun forModel(model: String): Boolean = models.isEmpty() || model.trim() in models
    /**
     * Показывать ли в предпросмотре именно изменения (посимвольный diff); иначе подсвечен весь ответ.
     * У проверки орфографии подсветка отличий — суть действия, она там всегда включена и в настройках
     * не меняется; у остальных действий владелец включает её сам (или она идёт вместе с промтом).
     */
    val showsDiff get() = builtin == Builtin.SPELL || highlight == true

    companion object {
        const val MAX_CUSTOM = 5

        fun newId(now: Long): String = "c$now"
    }
}

/**
 * Модель для запросов. [title] — необязательное название для показа (в форме ИИ, в подписях ошибок);
 * пусто — показываем саму модель. Адрес сервера и API-ключ у всех моделей общие.
 */
data class AiModel(val model: String = "", val title: String = "") {
    val shown: String get() = title.ifBlank { model }
    val ready: Boolean get() = model.isNotBlank()

    companion object {
        /** Больше шести моделей в форме не помещается разумно: три вкладки по две. */
        const val MAX = 6
    }
}

const val MAX_AI_TABS = 3

/**
 * Запись действий в настройки — по действию в строке: «id \t включено \t название \t промт \t выделять отличия».
 * Название и промт бывают многострочными, поэтому переводы строк и табуляции экранируются: иначе пустая
 * строка внутри промта резала бы запись на части и превращалась в «новое действие без названия».
 */
object AiStore {
    private val CUSTOM_ID = Regex("""c\d+""")
    /** Разделитель названий моделей внутри поля «принадлежность»: табуляция уже занята полями записи. */
    private const val MODEL_SEP = '\u001f'

    fun validId(id: String) = Builtin.of(id) != null || CUSTOM_ID.matches(id)

    /** Название и промт — в одну строку. Обратный слэш экранируется первым: раскрытие однозначно. */
    fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    /** Обратное к [esc]; запись прежней версии (без экранирования) читается как есть. */
    fun unesc(s: String): String {
        if (s.indexOf('\\') < 0) return s
        return buildString(s.length) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) when (s[i + 1]) {
                    'n' -> { append('\n'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    else -> { append(c); i++ }
                } else { append(c); i++ }
            }
        }
    }

    fun line(a: AiAction): String = a.id + "\t" + (if (a.enabled) "1" else "0") + "\t" +
        esc(a.title) + "\t" + esc(a.prompt) + "\t" + (a.highlight?.let { if (it) "1" else "0" }.orEmpty()) + "\t" +
        a.models.map { esc(it) }.joinToString(MODEL_SEP.toString())

    fun parse(line: String): AiAction? {
        if (line.isBlank()) return null
        val p = line.split('\t')
        val id = p[0].trim()
        if (!validId(id)) return null
        // Шестое поле появилось позже: нет его — выбора моделей ещё не было (действие видно всем).
        val models = p.getOrNull(5).orEmpty().split(MODEL_SEP)
            .map { unesc(it).trim() }.filter { it.isNotEmpty() }
        return AiAction(id, unesc(p.getOrNull(2).orEmpty()), unesc(p.getOrNull(3).orEmpty()),
            p.getOrNull(1).orEmpty() != "0",
            highlight = when (p.getOrNull(4)?.trim()) { "1" -> true; "0" -> false; else -> null },
            models = models)
    }
}

class AiColumn(val model: AiModel?, val actions: List<Int>)

fun aiTabs(models: List<AiModel>): List<List<AiModel>> = models.chunked(2).take(MAX_AI_TABS)

class AiPages(val tabs: List<List<AiColumn>>, val actions: List<AiAction>) {
    val empty get() = actions.isEmpty() || tabs.all { tab -> tab.all { it.model == null } }
}

fun aiPages(models: List<AiModel>, shown: List<AiAction>, maxTabs: Int = MAX_AI_TABS): AiPages {
    val tabs = models.chunked(2).take(maxTabs).map { pair ->
        pair.map { m -> AiColumn(m, shown.indices.filter { i -> shown[i].forModel(m.shown) }) }
    }
    return AiPages(tabs, shown)
}

fun withBuiltinActions(stored: List<AiAction>): List<AiAction> {
    val byId = stored.associateBy { it.id }
    val builtins = Builtin.values().map { b ->
        byId[b.id]?.copy(builtin = b) ?: AiAction(b.id, builtin = b)
    }
    return builtins + stored.filter { it.builtin == null && Builtin.of(it.id) == null }
}
