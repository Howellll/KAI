package com.kai.keyboard

/**
 * Боковые шорткаты формы ИИ (только в режиме одного столбца). Список — по стороне:
 * в настройках лежит строкой id через запятую, в форме — кнопки у края, по одной на id.
 */
object AiShortcut {
    const val CLIP = "clip"
    const val SAFE = "safe"
    const val SETTINGS = "settings"
    const val EMOJI = "emoji"
    const val LEFT = "left"
    const val RIGHT = "right"

    val ALL = listOf(CLIP, SAFE, SETTINGS, EMOJI, RIGHT, LEFT)

    /** Известные id без повторов, в сохранённом порядке; чужое и мусор отбрасывается. */
    fun parse(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it in ALL }.distinct()

    fun join(ids: List<String>): String = ids.filter { it in ALL }.distinct().joinToString(",")
}

object AiSideLayout {
    fun capacity(avail: Float, size: Float, gap: Float): Int {
        if (size <= 0f || avail < size) return 0
        return ((avail + gap) / (size + gap)).toInt()
    }

    fun tops(top: Float, bottom: Float, size: Float, gap: Float, count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        val total = count * size + (count - 1) * gap
        val first = ((top + bottom) / 2f - total / 2f).coerceAtLeast(top)
        return FloatArray(count) { first + it * (size + gap) }
    }
}
