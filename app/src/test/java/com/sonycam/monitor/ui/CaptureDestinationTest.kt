package com.sonycam.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureDestinationTest {
    @Test
    fun `D222 accepts only Sony's exact camera destinations`() {
        assertNull(captureDestinationFromRaw(0x0001))
        assertEquals(CaptureDestination.CAMERA_ONLY, captureDestinationFromRaw(0x0010))
        assertEquals(CaptureDestination.HOST_AND_CAMERA, captureDestinationFromRaw(0x0011))

        // Seen during Sony property warm-up; their bit patterns must not be
        // mistaken for a confirmed card destination.
        assertNull(captureDestinationFromRaw(0x0000))
        assertNull(captureDestinationFromRaw(0xFFFF))
        assertNull(captureDestinationFromRaw(0xFFFF_FFFFL))
    }
}
