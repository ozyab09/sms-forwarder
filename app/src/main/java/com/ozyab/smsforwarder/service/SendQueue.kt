package com.ozyab.smsforwarder.service

import java.util.PriorityQueue

/** Событие, ожидающее отправки. */
data class QueuedEvent(
    val text: String,
    /** Сколько раз уже пытались отправить. */
    val attempts: Int = 0,
    /** Ранний timestamp (ms), когда событию разрешена следующая попытка. */
    val nextRetryAt: Long = 0L,
)

/**
 * Очередь событий пересылки с ретраями (чистая Kotlin-логика, тестируется без Android).
 *
 * Два хранилища:
 *  - [pending] — события, готовые к отправке (FIFO). Новые события всегда попадают сюда
 *    и обрабатываются немедленно, не дожидаясь бэк-оффа старых (нет head-of-line blocking).
 *  - [retries] — события, ожидающие повторной попытки (min-heap по [QueuedEvent.nextRetryAt]).
 *
 * Свойства:
 *  - новые события не блокируются бэк-оффом упавших;
 *  - бэк-офф per-event (15с → 30с → … кап 10 мин), а не общий на сервис;
 *  - событие отбрасывается после [maxAttempts] попыток ([fail] вернёт true);
 *  - очередь ограничена [maxSize] — при переполнении отбрасывается самое старое.
 */
class SendQueue(
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val maxSize: Int = MAX_QUEUE_SIZE,
    private val initialDelayMs: Long = INITIAL_RETRY_MS,
    private val maxDelayMs: Long = MAX_RETRY_MS,
) {

    private val pending = ArrayDeque<QueuedEvent>()
    private val retries = PriorityQueue<QueuedEvent>(compareBy { it.nextRetryAt })
    private val lock = Any()

    /** События, отброшенные после исчерпания попыток. */
    @Volatile
    var droppedAfterAttempts: Int = 0
        private set

    /** События, отброшенные из-за переполнения очереди. */
    @Volatile
    var droppedOverflow: Int = 0
        private set

    val size: Int
        get() = synchronized(lock) { pending.size + retries.size }

    fun isEmpty(): Boolean = size == 0

    /** Добавить новое событие (можно из любого потока). */
    fun enqueue(text: String) {
        synchronized(lock) {
            if (pending.size + retries.size >= maxSize) {
                when {
                    pending.isNotEmpty() -> {
                        pending.removeFirst()
                        droppedOverflow++
                    }
                    retries.isNotEmpty() -> {
                        retries.poll()
                        droppedOverflow++
                    }
                }
            }
            pending.addLast(QueuedEvent(text = text, nextRetryAt = now()))
        }
    }

    /** Восстановить события после рестарта (все становятся готовыми к отправке). */
    fun restore(events: List<QueuedEvent>) {
        synchronized(lock) {
            for (e in events) {
                if (pending.size + retries.size >= maxSize) break
                pending.addLast(e.copy(nextRetryAt = now()))
            }
        }
    }

    /**
     * Взять следующее событие для отправки: сначала готовые [pending] (FIFO),
     * затем «созревшие» ретраи. null — отправлять сейчас нечего.
     */
    fun pollReady(): QueuedEvent? = synchronized(lock) {
        // Подтягиваем созревшие ретраи в хвост pending
        while (true) {
            val head = retries.peek() ?: break
            if (head.nextRetryAt <= now()) {
                retries.poll()
                pending.addLast(head)
            } else {
                break
            }
        }
        pending.removeFirstOrNull()
    }

    /** Задержка до следующего ретрая, или null если ретраев нет. */
    fun nextRetryDelayMs(): Long? = synchronized(lock) {
        retries.peek()?.let { (it.nextRetryAt - now()).coerceAtLeast(0) }
    }

    /**
     * Событие не отправлено — запланировать ретрай с бэк-оффом.
     * @return true, если попытки исчерпаны и событие отброшено.
     */
    fun fail(ev: QueuedEvent): Boolean {
        val nextAttempt = ev.attempts + 1
        synchronized(lock) {
            if (nextAttempt > maxAttempts) {
                droppedAfterAttempts++
                return true
            }
            val delay = nextDelayMs(nextAttempt)
            retries.add(ev.copy(attempts = nextAttempt, nextRetryAt = now() + delay))
            return false
        }
    }

    /** Снимок всех событий (для персистентности). */
    fun snapshot(): List<QueuedEvent> = synchronized(lock) {
        pending.toList() + retries.sortedBy { it.nextRetryAt }
    }

    /** Бэк-офф: initialDelay × 2^(attempt-1), кап maxDelay. */
    private fun nextDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 40)
        return minOf(initialDelayMs * (1L shl shift), maxDelayMs)
    }

    companion object {
        /** Стартовый интервал ретрая: 15 секунд. */
        const val INITIAL_RETRY_MS = 15_000L
        /** Кап одиночного интервала: 10 минут. */
        const val MAX_RETRY_MS = 600_000L
        /** Максимум попыток на событие (суммарное окно ~36 минут), затем отброс. */
        const val MAX_ATTEMPTS = 8
        /** Лимит очереди: при переполнении отбрасывается самое старое событие. */
        const val MAX_QUEUE_SIZE = 100
    }
}