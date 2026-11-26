package com.kai.keyboard.data.ai

/** Сборка промтов. Без Android — проверяется юнит-тестами. */
object AiPrompts {

    const val TEXT = "{{text}}"
    const val INSTRUCTION = "{{instruction}}"
    const val TARGET_LANG = "{{target_lang}}"

    val DEFAULT_SPELL = """
        Исправь орфографию, пунктуацию и опечатки в тексте ниже. Не меняй смысл, стиль, язык и форматирование, ничего не добавляй и не убирай.
        Верни только исправленный текст, без пояснений и кавычек.

        Текст:
        {{text}}
    """.trimIndent()

    val DEFAULT_TRANSLATE = """
        Переведи текст ниже на язык: {{target_lang}}. Сохрани смысл, тон и форматирование.
        Верни только перевод, без пояснений и кавычек.

        Текст:
        {{text}}
    """.trimIndent()

    val DEFAULT_EDIT = """
        Измени текст ниже по инструкции пользователя. Всё, что инструкция не затрагивает, оставь как есть.
        Верни только итоговый текст, без пояснений и кавычек.

        Инструкция: {{instruction}}

        Текст:
        {{text}}
    """.trimIndent()

    fun default(b: Builtin) = when (b) {
        Builtin.SPELL -> DEFAULT_SPELL
        Builtin.TRANSLATE -> DEFAULT_TRANSLATE
        Builtin.EDIT -> DEFAULT_EDIT
    }

    // Все скобки экранированы: ICU на Android строже JVM к одиночной «}».
    private val PLACEHOLDER by lazy { Regex("""\{\{(text|instruction|target_lang)\}\}""") }

    /**
     * Подставляет значения в шаблон за один проход: если пользовательский текст сам содержит `{{instruction}}`,
     * он останется буквальным текстом. Нет ни `{{text}}`, ни `{{instruction}}` в шаблоне — текст добавляется
     * в конец, чтобы модель его получила. Есть только `{{instruction}}` — текст не приписываем: такое действие
     * и задумано без исходного текста (например «придумай поздравление»).
     */
    fun build(template: String, text: String, instruction: String = "", targetLang: String = ""): String {
        val t = template.ifBlank { TEXT }
        val out = PLACEHOLDER.replace(t) { m ->
            when (m.groupValues[1]) {
                "text" -> text
                "instruction" -> instruction
                else -> targetLang
            }
        }
        return if (t.contains(TEXT) || t.contains(INSTRUCTION)) out else out + "\n\n" + text
    }

    fun autoTargetLang(text: String): String {
        var cyr = 0; var lat = 0
        for (ch in text) when (Character.UnicodeBlock.of(ch)) {
            Character.UnicodeBlock.CYRILLIC -> cyr++
            Character.UnicodeBlock.BASIC_LATIN -> if (ch.isLetter()) lat++
            else -> {}
        }
        return if (cyr >= lat) "английский" else "русский"
    }

    fun needsText(template: String) = template.contains(TEXT)

    fun hasRequired(template: String) = template.contains(TEXT) || template.contains(INSTRUCTION)

    fun canRun(template: String, hasSourceText: Boolean): Boolean = hasRequired(template) && !(needsText(template) && !hasSourceText)
}
