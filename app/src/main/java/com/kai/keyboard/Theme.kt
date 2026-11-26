package com.kai.keyboard

/** Цвета клавиатуры. Без Android — чтобы проверять контраст тестами. */
class Theme(
    val id: String,
    val title: String,
    val bg: Int,
    val key: Int,
    val fn: Int,
    val pressed: Int,
    val text: Int,
    val textDim: Int,
    val bubble: Int,
    /** Тень под клавишами (0 — без тени): на светлом фоне клавиши иначе сливаются. */
    val shadow: Int,
    val light: Boolean,
    /** Собственный акцент темы (у «Системной» — из Material You); null — берётся выбранный пользователем. */
    val accent: Int? = null,
) {
    companion object {
        val BLACK = Theme("black", "Чёрная", bg = rgb(0x000000), key = rgb(0x232325), fn = rgb(0x121214),
            pressed = rgb(0x3E3E42), text = rgb(0xFFFFFF), textDim = rgb(0x8E8E93), bubble = rgb(0x2C2C2E),
            shadow = 0, light = false)
        val DARK = Theme("dark", "Тёмная", bg = rgb(0x1C1C1E), key = rgb(0x3A3A3C), fn = rgb(0x2A2A2C),
            pressed = rgb(0x5A5A5E), text = rgb(0xFFFFFF), textDim = rgb(0x9A9AA0), bubble = rgb(0x48484A),
            shadow = 0, light = false)
        // Текст — не чисто чёрный: чёрное на белом выглядит жирнее, чем есть.
        val LIGHT = Theme("light", "Светлая", bg = rgb(0xD2D4DA), key = rgb(0xFFFFFF), fn = rgb(0xADB2BC),
            pressed = rgb(0xC3C7CF), text = rgb(0x202124), textDim = rgb(0x6B6E76), bubble = rgb(0xFFFFFF),
            shadow = rgb(0x898A8D), light = true)

        const val SYSTEM_ID = "system"

        /** «Системная»: до [SystemTheme.refresh] — обычная тёмная (так же на Android < 12). */
        @Volatile var system: Theme = Theme(SYSTEM_ID, "Системная", DARK.bg, DARK.key, DARK.fn, DARK.pressed,
            DARK.text, DARK.textDim, DARK.bubble, 0, false)

        val ALL: List<Theme> get() = listOf(BLACK, DARK, LIGHT, system)

        fun of(id: String) = ALL.firstOrNull { it.id == id } ?: BLACK

        private fun rgb(c: Int) = c or 0xFF000000.toInt()
    }
}

object Accent {
    /** По умолчанию — фиолетовый: он же четвёртый в списке готовых цветов. */
    const val DEFAULT = 0xFFBF5AF2.toInt()

    val PRESETS = listOf(
        DEFAULT, 0xFF30D158.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF375F.toInt(),
        0xFF64D2FF.toInt(), 0xFFFFD60A.toInt(), 0xFF8E8E93.toInt(), 0xFF0A84FF.toInt(),
    )

    /**
     * Цвет текста и иконок поверх акцента: белый, пока он читается (контраст по WCAG от 3),
     * иначе чёрный — на зелёном, оранжевом, жёлтом.
     */
    fun onAccent(c: Int): Int =
        if (1.05 / (luminance(c) + 0.05) >= 3.0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
}
