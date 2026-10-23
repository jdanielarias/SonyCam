package com.sonycam.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Measured: the A7 III sends 1024×576; the Pixel 10 screen is 2424×1080. */
class LiveViewFramingTest {
    private val w = 1024f
    private val h = 576f
    private val sw = 2424f
    private val sh = 1080f

    @Test
    fun fitShowsTheWholeFrameWithSideBands() {
        val s = LiveViewFraming.FIT.scale(w, h, sw, sh)
        assertEquals(sh, h * s, 0.5f)          // full height
        assertEquals(1920f, w * s, 0.5f)       // 504 px of bands in total
    }

    @Test
    fun fillCoversTheScreenAndCropsTopAndBottom() {
        val s = LiveViewFraming.FILL.scale(w, h, sw, sh)
        assertEquals(sw, w * s, 0.5f)          // full width
        val croppedFraction = (h * s - sh) / (h * s)
        assertEquals(0.21f, croppedFraction, 0.01f) // ~10.5 % each edge
    }

    @Test
    fun theToggleAlternates() {
        assertEquals(LiveViewFraming.FIT, LiveViewFraming.FILL.next())
        assertEquals(LiveViewFraming.FILL, LiveViewFraming.FIT.next())
    }
}
