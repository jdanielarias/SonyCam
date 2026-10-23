package com.sonycam.monitor.ui

import com.sonycam.monitor.sony.SonyLiveViewState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewGateTest {

    /**
     * The regression that killed the A7 III: the capability probe used to skip
     * the D221 check that steady-state fetches obey, so a body reporting live
     * view off was asked for the live-view object anyway. It answered by
     * halting bulk-IN and the session never recovered.
     */
    @Test
    fun `probe does not fetch while the camera reports live view off`() {
        assertFalse(mayFetchLiveView(SonyLiveViewState.UNVERIFIED, 0L))
        assertFalse(mayFetchLiveView(SonyLiveViewState.AVAILABLE, 0L))
    }

    @Test
    fun `probe runs once the camera reports live view on`() {
        assertTrue(mayFetchLiveView(SonyLiveViewState.UNVERIFIED, 1L))
        assertTrue(mayFetchLiveView(SonyLiveViewState.AVAILABLE, 1L))
    }

    /** Bodies that never expose D221 must keep working exactly as before. */
    @Test
    fun `a body without D221 is not gated`() {
        assertTrue(mayFetchLiveView(SonyLiveViewState.UNVERIFIED, null))
        assertTrue(mayFetchLiveView(SonyLiveViewState.AVAILABLE, null))
    }

    /** A settled negative verdict outranks whatever D221 currently says. */
    @Test
    fun `unsupported and disabled never fetch`() {
        for (status in listOf(null, 0L, 1L)) {
            assertFalse(mayFetchLiveView(SonyLiveViewState.UNSUPPORTED, status))
            assertFalse(mayFetchLiveView(SonyLiveViewState.DISABLED, status))
        }
    }
}
