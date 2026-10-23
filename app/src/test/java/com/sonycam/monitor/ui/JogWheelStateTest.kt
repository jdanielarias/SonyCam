package com.sonycam.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class JogWheelStateTest {
    @Test
    fun `settled wheel follows current camera index instead of stale remembered base`() {
        // Regression: after ISO 100 -> 2000, baseIndex could still contain the
        // old ISO index during the next composition/opening.
        assertEquals(
            18f,
            wheelSelectionPosition(
                currentIndex = 18,
                dragging = false,
                dragBaseIndex = 5,
                dragOffset = 0f,
                lastIndex = 40,
            ),
            0f,
        )
    }

    @Test
    fun `active drag remains relative to its gesture start`() {
        assertEquals(
            9.5f,
            wheelSelectionPosition(
                currentIndex = 18,
                dragging = true,
                dragBaseIndex = 7,
                dragOffset = 2.5f,
                lastIndex = 40,
            ),
            0f,
        )
    }

    @Test
    fun `wheel position is clamped to current list bounds`() {
        assertEquals(0f, wheelSelectionPosition(-4, false, 0, 0f, 10), 0f)
        assertEquals(10f, wheelSelectionPosition(0, true, 8, 9f, 10), 0f)
    }
}
