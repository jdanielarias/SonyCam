package com.sonycam.monitor.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Wait until [condition] holds, checking every [pollMs], for at most
 * [timeoutMs]. Returns whether it held. Checked once more at the deadline so a
 * release landing in the last interval is not reported as a timeout.
 */
internal suspend fun awaitCondition(
    timeoutMs: Long,
    pollMs: Long,
    now: () -> Long = System::currentTimeMillis,
    condition: () -> Boolean,
): Boolean {
    val deadline = now() + timeoutMs
    while (!condition()) {
        if (now() >= deadline) return condition()
        delay(pollMs)
    }
    return true
}

/**
 * Own opening resources until initialization returns to the caller and [publish]
 * transfers them to the live session. In particular, cancellation during the
 * dispatch back from IO still closes a successfully opened USB connection.
 *
 * [initialize] must check cancellation between blocking operations. [publish]
 * must transfer ownership without suspending; [close] must tolerate partially
 * initialized resources and attempt each resource's cleanup independently.
 */
internal suspend fun <T, R> initializeConnection(
    resources: T,
    initialize: suspend (T) -> R,
    publish: (T, R) -> Unit,
    close: (T) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): R {
    var transferred = false
    try {
        val result = withContext(dispatcher) { initialize(resources) }
        currentCoroutineContext().ensureActive()
        publish(resources, result)
        transferred = true
        return result
    } finally {
        if (!transferred) {
            // This finally encloses withContext itself: putting it inside the
            // IO block misses cancellation after the block has already returned.
            withContext(NonCancellable + dispatcher) { close(resources) }
        }
    }
}
