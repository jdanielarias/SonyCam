package com.sonycam.monitor.ui

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A skipped operation must not count as a failed frame or trigger recovery. */
internal sealed interface CameraOperation<out T> {
    data object Skipped : CameraOperation<Nothing>
    data class Completed<T>(val value: T) : CameraOperation<T>
}

/** Re-read permission after queued USB work finishes, immediately before sending. */
internal suspend fun <T> runCameraOperation(
    mutex: Mutex,
    allowed: () -> Boolean,
    action: () -> T,
): CameraOperation<T> = mutex.withLock {
    // An uncontended Mutex does not suspend or check cancellation. A stopped
    // focus job must not send a nudge after its gesture has been cancelled.
    currentCoroutineContext().ensureActive()
    if (allowed()) CameraOperation.Completed(action()) else CameraOperation.Skipped
}
