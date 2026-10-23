package com.sonycam.monitor.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After the poll loop lost the camera the app stayed on the monitor screen,
 * with no Connect button, and a camera replugged while the old session was
 * still closing was ignored without a word.
 */
class ConnectionRecoveryTest {

    /** `info == null` is what makes MonitorApp show the connect pane. */
    @Test
    fun lostConnectionReturnsToTheConnectPane() {
        val state = lostConnectionState("bulk read failed/timed out")
        assertNull(state.info)
        assertNull(state.readings)
        assertFalse(state.connecting)
        assertEquals("Lost connection.", state.status)
        assertNotNull(state.error)
        val error = state.error!!
        assertTrue(error.startsWith("bulk read failed/timed out"))
        assertTrue(error.contains("Connect"))
    }

    @Test
    fun awaitConditionReturnsAsSoonAsItHolds() = runBlocking {
        var checks = 0
        val held = awaitCondition(timeoutMs = 5_000, pollMs = 1) { ++checks >= 3 }
        assertTrue(held)
        assertEquals(3, checks)
    }

    @Test
    fun awaitConditionGivesUpAtTheDeadline() = runBlocking {
        var clock = 0L
        val held = awaitCondition(timeoutMs = 1_000, pollMs = 1, now = { clock.also { clock += 300 } }) {
            false
        }
        assertFalse(held)
    }

    /** A release in the last interval is a release, not a timeout. */
    @Test
    fun awaitConditionChecksOnceMoreAtTheDeadline() = runBlocking {
        var clock = 0L
        var released = false
        // The release happens exactly as the clock reaches the deadline.
        val held = awaitCondition(timeoutMs = 1_000, pollMs = 1, now = {
            clock.also {
                if (it >= 1_000) released = true
                clock += 600
            }
        }) { released }
        assertTrue(held)
    }
}
