package com.sonycam.monitor.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The shutdown ordering, exercised against a release that takes longer than the
 * join is willing to wait.
 *
 * Pulling the cable cannot prove this. By the time the detach broadcast arrives
 * the USB link is already gone, so no release write could have succeeded either
 * way — that experiment tests the camera's own fail-safe, not this app's
 * ordering. What has to be shown is that a teardown *with the link up* lets the
 * release finish before the close begins, even when the release outlasts the
 * join timeout. So the slow part is injected here instead.
 */
class SessionShutdownTest {

    private val clock = AtomicLong(0)
    private fun stamp() = clock.incrementAndGet()

    /**
     * A BULB hold: takes the lock, and releases the shutter from a
     * NonCancellable block that outlives its own cancellation — exactly like
     * `holdBulbShutter`.
     */
    private fun CoroutineScope.bulbHold(
        lock: Mutex,
        releaseMillis: Long,
        onReleased: (Long) -> Unit,
    ) = launch {
        try {
            delay(60_000)
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    delay(releaseMillis)
                    onReleased(stamp())
                }
            }
        }
    }

    /** The guarantee: the close never starts while a release is on the wire. */
    @Test
    fun `a release slower than the join still finishes before the close`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lock = Mutex()
        var releasedAt = -1L
        var closedAt = -1L

        val hold = scope.bulbHold(lock, releaseMillis = 300) { releasedAt = it }
        delay(50)   // let it reach the delay and be cancellable

        // The join gives up long before the release is done.
        val outcome = SessionShutdown(joinTimeoutMs = 40, lockTimeoutMs = 5_000)
            .run(listOf(hold), lock) { closedAt = stamp() }

        assertTrue("the join was expected to time out", outcome.jobsTimedOut > 0)
        assertTrue("the close must have gone through the lock", outcome.closedUnderLock)
        assertTrue("the shutter was never released", releasedAt > 0)
        assertTrue(
            "closed at $closedAt before the release at $releasedAt",
            closedAt > releasedAt,
        )
    }

    /** With nothing slow in the way, the join alone is enough. */
    @Test
    fun `an idle session closes without waiting`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lock = Mutex()
        val idle = scope.launch { delay(60_000) }
        var closed = false

        val outcome = SessionShutdown(joinTimeoutMs = 5_000, lockTimeoutMs = 5_000)
            .run(listOf(idle), lock) { closed = true }

        assertEquals(0, outcome.jobsTimedOut)
        assertTrue(outcome.closedUnderLock)
        assertTrue(closed)
    }

    /**
     * A job that never lets go must not keep the USB interface claimed forever.
     * Losing the interface is the one outcome worse than the race, so the close
     * happens anyway — and says so.
     */
    @Test
    fun `a lock that never frees still ends with the interface closed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lock = Mutex()
        val wedged = scope.launch {
            lock.withLock { withContext(NonCancellable) { delay(60_000) } }
        }
        delay(50)
        var closed = false

        val outcome = SessionShutdown(joinTimeoutMs = 40, lockTimeoutMs = 80)
            .run(listOf(wedged), lock) { closed = true }

        assertFalse("the lock was never obtainable", outcome.closedUnderLock)
        assertTrue("the interface must still be released", closed)
        wedged.cancel()
    }

    /** Every job is joined, not only the first. */
    @Test
    fun `all jobs are cancelled`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = List(4) { scope.launch { delay(60_000) } }

        SessionShutdown(joinTimeoutMs = 5_000, lockTimeoutMs = 5_000)
            .run(jobs, Mutex()) {}

        assertTrue(jobs.all { it.isCompleted })
    }
}
