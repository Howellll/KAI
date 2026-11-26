package com.kai.keyboard

/**
 * Порядок ввода, пока свайп распознаётся в фоне. Главный поток не ждёт результата:
 * всё, что владелец нажимает дальше, встаёт в очередь и выполняется сразу после
 * вставки слова — в том же порядке. Без Android, чтобы проверять тестами.
 *
 * Упавшее действие не ломает очередь: ошибка уходит в [onError], остальные выполняются дальше.
 */
class InputGate(private val onError: (Exception) -> Unit = {}) {
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false

    var busy = false
        private set

    val replaying: Boolean get() = draining

    fun begin() { busy = true }

    /** Слово вставлено — выполняем отложенное. Если среди отложенного новый свайп, снова ждём его. */
    fun end() {
        busy = false
        if (draining) return
        draining = true
        try {
            while (!busy && queue.isNotEmpty()) {
                val action = queue.removeFirst()
                try { action() } catch (e: Exception) { onError(e) }
            }
        } finally {
            draining = false
        }
    }

    fun defer(action: () -> Unit): Boolean {
        // Во время разбора очереди действия выполняются сразу — иначе они встали бы в её конец.
        val wait = busy || (!draining && queue.isNotEmpty())
        if (wait) queue.addLast(action)
        return wait
    }

    fun reset() { busy = false; queue.clear() }
}
