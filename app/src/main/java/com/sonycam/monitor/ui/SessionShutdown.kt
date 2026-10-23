package com.sonycam.monitor.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Ends a camera session in an order that cannot leave the body holding anything.
 *
 * The hazard this exists for: a BULB exposure releases the shutter from a
 * `NonCancellable` block, so cancelling its job does not stop that release — it
 * runs *afterwards*. Closing the transport as soon as `cancel()` returned meant
 * the release found the transport already shut, the failure was swallowed, and
 * the camera kept exposing with nobody left to stop it.
 *
 * Waiting on the jobs is not enough by itself. The join needs a bound, or a
 * wedged endpoint would hold the USB interface forever — and the moment it is
 * bounded, a release slower than the bound puts the race straight back.
 *
 * So the ordering is structural rather than a matter of timing: the release
 * holds [lock] while it is in flight, and the close is taken *through the same
 * lock*. Whatever the join does, the close cannot start while a release is
 * still on the wire. The lock wait is bounded too, and only if that also
 * expires does it close regardless — losing the interface entirely is the one
 * outcome worse than the race.
 */
internal class SessionShutdown(
    private val joinTimeoutMs: Long,
    private val lockTimeoutMs: Long,
) {
    /** What the sequence did, for the diagnostic transcript. */
    data class Outcome(
        val jobsTimedOut: Int,
        val closedUnderLock: Boolean,
    )

    suspend fun run(jobs: List<Job>, lock: Mutex, close: suspend () -> Unit): Outcome {
        var timedOut = 0
        for (job in jobs) {
            val joined = runCatching { withTimeout(joinTimeoutMs) { job.cancelAndJoin() } }
            if (joined.isFailure) timedOut++
        }
        val underLock = runCatching {
            withTimeout(lockTimeoutMs) { lock.withLock { close() } }
        }.isSuccess
        if (!underLock) close()
        return Outcome(jobsTimedOut = timedOut, closedUnderLock = underLock)
    }
}
