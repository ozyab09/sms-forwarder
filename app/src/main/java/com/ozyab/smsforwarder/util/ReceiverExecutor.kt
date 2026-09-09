package com.ozyab.smsforwarder.util

import android.content.BroadcastReceiver
import java.util.concurrent.Executors

/**
 * Вынос работы BroadcastReceiver'ов с main thread.
 *
 * `onReceive` выполняется на главном потоке; тяжёлые операции (ContentResolver,
 * CallLog, контакты, фильтры) под пачкой событий могут вызвать ANR.
 * `goAsync()` + фоновый поток — стандартный способ избежать этого.
 *
 * Один поток (а не пул) — чтобы события обрабатывались строго по порядку:
 * для PHONE_STATE последовательность RINGING → OFFHOOK → IDLE критична.
 */
object ReceiverExecutor {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "receiver-worker").apply { isDaemon = true }
    }

    /** Выполняет [block] в фоне; receiver остаётся живым до finish(). */
    fun goAsync(receiver: BroadcastReceiver, block: () -> Unit) {
        val pendingResult = receiver.goAsync()
        executor.execute {
            try {
                block()
            } finally {
                pendingResult.finish()
            }
        }
    }
}