package com.sonycam.monitor.ui

import com.sonycam.monitor.sony.DTC_UINT16
import com.sonycam.monitor.sony.SonyDeviceProp
import com.sonycam.monitor.sony.ShutterReading
import com.sonycam.monitor.sony.shutterReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting for a still is driven by the camera's own busy flag, not a clock.
 *
 * The bytes below are the first descriptor of an ILCE-7M3 0x9209 dump, read off
 * the wire across one 15" exposure: `...0450 0200 01 01...` while idle and
 * `...0450 0200 01 00...` from the shutter opening until 32 s later, a beat
 * before the JPEG appeared.
 */
class CaptureBusyFlagTest {

    private fun prop(code: Int, isEnabled: Int) = SonyDeviceProp(
        code = code,
        dataType = DTC_UINT16,
        getSet = 0x01,
        isEnabled = isEnabled,
        currentValue = 0L,
        formFlag = 0,
    )

    private val idle = listOf(
        prop(0x5004, isEnabled = 1),
        prop(0xD20D, isEnabled = 1),
        prop(0xD218, isEnabled = 2),   // display only, never adjustable
        prop(0xD20F, isEnabled = 0),   // greyed out before the shot too
    ).associateBy { it.code }

    private val baseline = enabledCodes(idle)

    @Test
    fun `only the camera's adjustable codes form the baseline`() {
        assertEquals(setOf(0x5004, 0xD20D), baseline)
    }

    @Test
    fun `an idle camera is not mid-shot`() {
        assertFalse(stillCaptureInProgress(baseline, idle))
    }

    /** The observed signal: a control that was adjustable is greyed out. */
    @Test
    fun `greying out one baseline control marks the shot in progress`() {
        val exposing = idle + (0x5004 to prop(0x5004, isEnabled = 0))
        assertTrue(stillCaptureInProgress(baseline, exposing))
    }

    /** Restoring it ends the wait, whether that took 15 s or ten minutes. */
    @Test
    fun `restoring the control ends the shot`() {
        val exposing = idle + (0x5004 to prop(0x5004, isEnabled = 0))
        assertTrue(stillCaptureInProgress(baseline, exposing))
        assertFalse(stillCaptureInProgress(baseline, exposing + (0x5004 to prop(0x5004, 1))))
    }

    /** Controls that were already greyed out must not read as a shot. */
    @Test
    fun `a control disabled before the shot is not a busy signal`() {
        assertFalse(stillCaptureInProgress(baseline, idle + (0xD20F to prop(0xD20F, 0))))
        assertFalse(stillCaptureInProgress(baseline, idle + (0xD218 to prop(0xD218, 2))))
    }

    /** A dump that arrives short must not be mistaken for an idle camera. */
    @Test
    fun `a missing baseline control counts as busy`() {
        assertTrue(stillCaptureInProgress(baseline, idle - 0x5004))
        assertTrue(stillCaptureInProgress(baseline, emptyMap()))
    }

    /**
     * The shutter no longer bounds the wait; it only shapes the notice shown to
     * the user. It still has to decode, and BULB still has to be recognisable as
     * "no announced duration".
     */
    @Test
    fun `shutter decodes for the long-exposure notice`() {
        assertEquals(15.0, (shutterReading(0x0096_000AL) as ShutterReading.Seconds).value, 1e-9)
        assertEquals(1.0 / 50.0, (shutterReading(0x0001_0032L) as ShutterReading.Seconds).value, 1e-9)
        assertEquals(ShutterReading.Bulb, shutterReading(0L))
    }
}
