package com.sonycam.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measured on an A7 III recording 4K: ~15 fps, with arrival gaps between 55
 * and 170 ms. Shown as they arrive, motion trembles.
 */
class FramePacerTest {

    /** Arrivals with the measured irregular gaps, repeated to let the average settle. */
    private val irregularGaps = listOf(55L, 100L, 55L, 150L, 60L, 80L, 70L, 60L)

    private fun arrivals(cycles: Int): List<Long> {
        var t = 0L
        return buildList {
            add(t)
            repeat(cycles) { irregularGaps.forEach { t += it; add(t) } }
        }
    }

    private fun spread(values: List<Long>) = values.max() - values.min()

    @Test
    fun presentationGapsAreFarMoreEvenThanArrivalGaps() {
        val pacer = FramePacer()
        val arrive = arrivals(cycles = 12)
        val present = arrive.map(pacer::schedule)
        // Judge the settled stream, not the first second of learning.
        val tail = 40
        val arrivalGaps = arrive.zipWithNext { a, b -> b - a }.takeLast(tail)
        val presentGaps = present.zipWithNext { a, b -> b - a }.takeLast(tail)
        assertEquals(95L, spread(arrivalGaps))
        assertTrue("presented gaps spread ${spread(presentGaps)} ms: $presentGaps",
            spread(presentGaps) <= 50)
    }

    @Test
    fun aFrameIsNeverShownBeforeItArrives() {
        val pacer = FramePacer()
        arrivals(cycles = 6).forEach { a -> assertTrue(pacer.schedule(a) >= a) }
    }

    @Test
    fun theAddedDelayStaysBounded() {
        val pacer = FramePacer()
        arrivals(cycles = 6).forEach { a ->
            val delay = pacer.schedule(a) - a
            assertTrue("delay $delay ms", delay <= (pacer.averageIntervalMs * 1.5).toLong() + 1)
        }
    }

    @Test
    fun aSteadyStreamPassesThroughUntouched() {
        val pacer = FramePacer()
        for (i in 0 until 30) assertEquals(i * 66L, pacer.schedule(i * 66L))
    }

    /** A stall must not leave the next frames queued behind a stale schedule. */
    @Test
    fun aStallResynchronisesToTheArrival() {
        val pacer = FramePacer()
        for (i in 0 until 10) pacer.schedule(i * 66L)
        assertEquals(3_000L, pacer.schedule(3_000L))
    }

    @Test
    fun resetForgetsTheStream() {
        val pacer = FramePacer()
        arrivals(cycles = 2).forEach(pacer::schedule)
        pacer.reset()
        assertEquals(0.0, pacer.averageIntervalMs, 0.0)
        assertEquals(10_000L, pacer.schedule(10_000L))
    }
}
