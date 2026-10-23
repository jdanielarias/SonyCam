package com.sonycam.monitor.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focus mode and the manual focus drive.
 *
 * The mode table is transcribed from Sony's *Camera Control PTP 3 Reference*
 * (pág. 176). The drive is `0xD2D1`, which libgphoto2 knows as "Near Far" and
 * our own control table as ManualFocusAdjust; an ILCE-7M3 advertises it as
 * `getset=0x82 en=1 write=NOTCH form=1 range=(-7, 7, 1)`.
 */
class FocusTest {

    /** 0x500A as the A7 III reports it: an enumeration the camera owns. */
    private fun focusProp(currentRaw: Long, announced: List<Long>, enabled: Int = 2) = SonyDeviceProp(
        code = DPC_FOCUS_MODE,
        dataType = DTC_UINT16,
        getSet = 0x00,
        isEnabled = enabled,
        currentValue = currentRaw,
        formFlag = DPFF_ENUM,
        enumValues = announced,
    )

    @Test
    fun everyDocumentedModeGetsItsNameOffTheDial() {
        assertEquals("MF", decodeFocusMode(0x0001L))
        assertEquals("AF-S", decodeFocusMode(0x0002L))
        assertEquals("Macro", decodeFocusMode(0x0003L))
        assertEquals("AF-C", decodeFocusMode(0x8004L))
        assertEquals("AF-A", decodeFocusMode(0x8005L))
        assertEquals("DMF", decodeFocusMode(0x8006L))
        assertEquals("MF-R", decodeFocusMode(0x8007L))
        assertEquals("AF-D", decodeFocusMode(0x8008L))
        assertEquals("PF", decodeFocusMode(0x8009L))
    }

    @Test
    fun anUndocumentedModeIsShownAsItself() {
        assertEquals("0x8099", decodeFocusMode(0x8099L))
    }

    /**
     * Which modes leave the ring in charge. This gates the remote focus drive:
     * in AF the camera ignores the notch, so offering the buttons there would
     * be a control that does nothing.
     */
    @Test
    fun onlyTheManualModesLeaveTheRingInCharge() {
        assertTrue(isManualFocusMode(0x0001L))   // MF
        assertTrue(isManualFocusMode(0x8006L))   // DMF
        assertTrue(isManualFocusMode(0x8007L))   // MF-R
        for (af in longArrayOf(0x0002L, 0x0003L, 0x8004L, 0x8005L, 0x8008L, 0x8009L)) {
            assertFalse("0x%04X is an AF mode".format(af), isManualFocusMode(af))
        }
    }

    @Test
    fun theReadingCarriesBothTheNameAndWhetherTheRingIsLive() {
        val mf = decodeReadings(mapOf(DPC_FOCUS_MODE to focusProp(0x0001L, listOf(0x0001L, 0x8004L))))
        assertEquals("MF", mf.focusMode)
        assertTrue(mf.manualFocus)

        val afc = decodeReadings(mapOf(DPC_FOCUS_MODE to focusProp(0x8004L, listOf(0x0001L, 0x8004L))))
        assertEquals("AF-C", afc.focusMode)
        assertFalse(afc.manualFocus)
    }

    /** A body with no focus descriptor at all must not claim manual focus. */
    @Test
    fun noDescriptorIsNotManualFocus() {
        val none = decodeReadings(emptyMap())
        assertEquals(READING_UNAVAILABLE, none.focusMode)
        assertFalse("absence is not permission to drive the ring", none.manualFocus)
    }

    /** The wheel offers what the camera announced, named from Sony's table. */
    @Test
    fun theWheelCarriesOnlyTheAnnouncedModes() {
        val announced = listOf(0x0001L, 0x0002L, 0x8004L, 0x8005L, 0x8006L, 0x8009L)
        val pick = buildPickList(
            ExposureControl.FOCUS,
            mapOf(DPC_FOCUS_MODE to focusProp(0x0001L, announced)),
        )!!

        assertEquals(announced, pick.raws)
        assertEquals(listOf("MF", "AF-S", "AF-C", "AF-A", "DMF", "PF"), pick.labels)
        assertEquals(0, pick.index)
    }

    /**
     * The A7 III marks 0x500A "display only" (`en=2`) and takes writes anyway,
     * which is why it sits on the mode-2 allow-list. Without that the tile would
     * be greyed on a body that can perfectly well change focus mode.
     */
    @Test
    fun displayOnlyDoesNotMeanUnwritableOnAClassicBody() {
        val asReported = focusProp(0x0001L, listOf(0x0001L, 0x8004L), enabled = 2)
        assertEquals(SonyWriteMode.ABSOLUTE, asReported.writeMode(2))
        assertTrue(
            ExposureControl.FOCUS.editable(
                mapOf(DPC_FOCUS_MODE to asReported), modeVer = 2, mode = 0x0001,
            ),
        )
    }

    /** Fully automatic exposure modes take the focus mode away with everything else. */
    @Test
    fun fullAutoOwnsTheFocusModeToo() {
        val props = mapOf(DPC_FOCUS_MODE to focusProp(0x0001L, listOf(0x0001L, 0x8004L)))
        assertFalse(ExposureControl.FOCUS.editable(props, modeVer = 2, mode = 0x8000))
        assertEquals("not in Auto", ExposureControl.FOCUS.lockReason(0x8000))
    }
}
