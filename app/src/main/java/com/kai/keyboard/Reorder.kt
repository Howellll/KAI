package com.kai.keyboard

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Быстрый набор двумя пальцами: нажатия разных рук идут почти вместе, и клавиатура может получить
 * их не в том порядке, что задуман («пр-ви-ет»). Для такой пары распознаём оба касания в обоих
 * порядках заново и берём тот, что лучше объясняет и касания, и слово, — только с запасом,
 * чтобы не спорить с пальцами зря. Окно и запас подобраны симулятором (TwoThumbSimTest).
 * Без Android — чтобы проверять тестами; время нажатий нигде не хранится.
 */
object Reorder {
    /** Пара «почти вместе»: между нажатиями меньше этого. */
    var WINDOW_MS = 70L
    /**
     * Насколько обратный порядок должен быть лучше (в единицах log-вероятности). A/B в симуляторе
     * (TwoThumbSimTest.marginSweep): 3 вдвое реже портит правильно пришедшие пары, чем 2, почти
     * без потери пользы; 4 и выше режут пользу заметно.
     */
    var MARGIN = 3f

    class Tap(val x: Float, val y: Float, val at: Long, val left: Boolean)

    interface Model {
        fun probs(extra: String, out: FloatArray): Float
        fun resolve(t: Tap, probs: FloatArray?, conf: Float): Key?
        fun fit(t: Tap, k: Key): Float
    }

    fun close(a: Tap, b: Tap) = a.left != b.left && abs(b.at - a.at) < WINDOW_MS

    /**
     * @return буквы пары в обратном порядке (сначала b, потом a), если так заметно правдоподобнее;
     * null — оставить как пришло.
     */
    fun swapped(lang: Lang, m: Model, a: Tap, b: Tap): String? {
        if (!close(a, b)) return null
        val asIs = score(lang, m, a, b) ?: return null
        val sw = score(lang, m, b, a) ?: return null
        return if (sw.first > asIs.first + MARGIN) sw.second else null
    }

    private fun score(lang: Lang, m: Model, first: Tap, second: Tap): Pair<Float, String>? {
        val probs = FloatArray(64)
        val sb = StringBuilder(2)
        var s = 0f
        for (t in listOf(first, second)) {
            val conf = m.probs(sb.toString(), probs)
            val k = m.resolve(t, if (conf >= 0f) probs else null, max(conf, 0f)) ?: return null
            if (k.kind != KeyKind.LETTER) return null
            s += m.fit(t, k)
            val i = lang.index(k.ch)
            if (conf > 0f && i >= 0) s += TouchModel.LAMBDA * conf * ln(max(probs[i], 1e-6f))
            sb.append(k.ch)
        }
        return s to sb.toString()
    }
}
