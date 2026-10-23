package com.sonycam.monitor.ui

import org.junit.Assert.*
import org.junit.Test

class FocusWheelStateTest {
    @Test fun `release stops the drive without snapping the wheel back`() {
        val moved = FocusWheelState().drag(53f, 7)
        val released = moved.stop()
        assertEquals(53f, released.phaseDp, 0f)
        assertEquals(moved.phaseDp, released.phaseDp, 0f)
        assertEquals(0, released.speed)
        assertEquals(0f, released.driveTravelDp, 0f)
    }

    @Test fun `reversal drives away immediately without crossing the original touch`() {
        val near = FocusWheelState().drag(120f, 7)
        val far = near.drag(-4f, 7)
        assertEquals(6, near.speed)
        assertEquals(-1, far.speed)
        assertEquals(36f, far.phaseDp, 0f)
        assertEquals(1, far.drag(2f, 7).speed)
    }

    @Test fun `next gesture continues the marks but starts its own speed`() {
        val stopped = FocusWheelState().drag(137f, 7).stop()
        val next = stopped.drag(3f, 7)
        assertEquals(60f, next.phaseDp, 0f)
        assertEquals(1, next.speed)
        assertEquals(-1, stopped.drag(-3f, 7).speed)
    }

    @Test fun `long strokes keep scrolling after maximum drive speed`() {
        var wheel = FocusWheelState().drag(140f, 7)
        assertEquals(7, wheel.speed)
        repeat(1000) {
            val before = wheel.phaseDp
            wheel = wheel.drag(17f, 7)
            assertEquals((before + 17f) % FOCUS_WHEEL_PERIOD_DP, wheel.phaseDp, 0f)
            assertEquals(7, wheel.speed)
        }
        assertEquals(-7, wheel.drag(-500f, 7).speed)
    }

    @Test fun `small deliberate drags start at the slowest speed`() {
        assertEquals(1, FocusWheelState().drag(1f, 7).speed)
        assertEquals(-1, FocusWheelState().drag(-1f, 7).speed)
        assertEquals(0, FocusWheelState().drag(0f, 7).speed)
    }

    @Test fun `stopping does not generate a reverse drive`() {
        for (delta in listOf(-75f, -10f, 10f, 75f)) {
            val moving = FocusWheelState().drag(delta, 7)
            val stopped = moving.stop().stop()
            assertEquals(moving.phaseDp, stopped.phaseDp, 0f)
            assertEquals(0, stopped.speed)
        }
    }
}
