package com.sonycam.monitor.ui

import org.junit.Assert.*
import org.junit.Test

class FocusGestureFilterTest {
    @Test fun `small retreats never reverse a stroke in either direction`() {
        for (direction in listOf(-1f, 1f)) {
            val filter = FocusGestureFilter()
            assertEquals(10f * direction, filter.move(10f * direction), 0f)
            repeat(100) {
                assertEquals(0f, filter.move(-2f * direction), 0f)
                assertEquals(0f, filter.move(1f * direction), 0f)
                assertEquals(2f * direction, filter.move(3f * direction), 0f)
            }
        }
    }

    @Test fun `jitter around a stationary point does not ratchet the lens`() {
        val filter = FocusGestureFilter()
        filter.move(20f)
        repeat(200) {
            assertEquals(0f, filter.move(-3f), 0f)
            assertEquals(0f, filter.move(3f), 0f)
        }
    }

    @Test fun `slow intentional reversal accumulates from the last extreme`() {
        val filter = FocusGestureFilter()
        filter.move(120f)
        repeat(3) { assertEquals(0f, filter.move(-1f), 0f) }
        assertEquals(-4f, filter.move(-1f), 0f)
        assertEquals(-1f, filter.move(-1f), 0f)
    }

    @Test fun `first movement and new gestures have no reversal deadband`() {
        for (direction in listOf(-1f, 1f)) {
            val filter = FocusGestureFilter()
            assertEquals(0f, filter.move(0.5f * direction), 0f)
            assertEquals(1f * direction, filter.move(0.5f * direction), 0f)
        }
    }

    @Test fun `wheel and camera preserve the same direction under noisy travel`() {
        val filter = FocusGestureFilter()
        val motion = FocusMotionQueue()
        var wheel = FocusWheelState()
        val raw = listOf(8f, -1f, 3f, -2f, 1f, 3f, -3f, 3f, 4f)
        for (delta in raw) {
            val accepted = filter.move(delta)
            wheel = wheel.drag(accepted, 7)
            motion.add(accepted)
            assertTrue(wheel.speed > 0)
            assertTrue(motion.takeSteps() >= 0)
        }
        val reverse = filter.move(-8f)
        wheel = wheel.drag(reverse, 7)
        motion.add(reverse)
        assertTrue(wheel.speed < 0)
        assertTrue(motion.takeSteps() < 0)
    }

    @Test fun `invalid samples do not poison subsequent motion`() {
        val filter = FocusGestureFilter()
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f).forEach {
            assertEquals(0f, filter.move(it), 0f)
        }
        assertEquals(2f, filter.move(2f), 0f)
    }
}
