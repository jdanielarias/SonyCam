package com.sonycam.monitor.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Sony reserves values inside the exposure properties that are not readings at
 * all, and dividing them produced confident nonsense. Every value here is
 * quoted from Sony's Camera Remote Command PTP 3 reference, cross-checked
 * against dumps from a real ILCE-7M3.
 */
class ExposureSentinelTest {

    // -- Shutter Speed (0xD20D) --------------------------------------------

    /**
     * The regression. `0xFFFFFFFF` is "nothing to display" in the reference, and
     * it really does arrive: an ILCE-7M3 emits it during warm-up
     * (`prop 0xD20D ... cur=0xFFFFFFFF`). The old decoder split it into
     * 65535/65535 and rendered a confident `1"`.
     */
    @Test
    fun `nothing-to-display is not a one second exposure`() {
        assertEquals(ShutterReading.Unavailable, shutterReading(0xFFFFFFFFL))
        assertEquals(READING_UNAVAILABLE, decodeShutter(0xFFFFFFFFL))
        assertNotEquals("1\"", decodeShutter(0xFFFFFFFFL))
    }

    /**
     * And it must not arm BULB either. The old test for "no duration" was a zero
     * numerator or denominator, which `0xFFFFFFFF` passes on neither count — but
     * the nullable-Double API it fed collapsed both into `null`, so an unreadable
     * shutter would have offered to hold the shutter open.
     */
    @Test
    fun `nothing-to-display is not BULB`() {
        assertNotEquals(ShutterReading.Bulb, shutterReading(0xFFFFFFFFL))
    }

    /** 0x00000000 is BULB, read off the body with the dial past 30". */
    @Test
    fun `zero is BULB`() {
        assertEquals(ShutterReading.Bulb, shutterReading(SHUTTER_BULB_RAW))
        assertEquals("BULB", decodeShutter(0L))
    }

    /** Both worked examples printed in Sony's reference for Shutter Speed. */
    @Test
    fun `Sony's own worked examples decode`() {
        // "0x000F000A: 0x000F (means 15) / 0x000A (means 10) = 1.5""
        assertEquals(1.5, (shutterReading(0x000F_000AL) as ShutterReading.Seconds).value, 1e-9)
        // "0x000103E8: 0x0001 (means 1) / 0x03E8 (means 1000) = 1/1000"
        assertEquals("1/1000", decodeShutter(0x0001_03E8L))
    }

    /** Read off the ILCE-7M3 at 15" and at 30". */
    @Test
    fun `real captures decode`() {
        assertEquals("15\"", decodeShutter(0x0096_000AL))
        assertEquals("30\"", decodeShutter(0x012C_000AL))
    }

    /** A pair that cannot be a duration must report nothing, not divide by zero. */
    @Test
    fun `a malformed pair reports nothing`() {
        assertEquals(ShutterReading.Unavailable, shutterReading(0x000F_0000L))
        assertEquals(READING_UNAVAILABLE, decodeShutter(0x000F_0000L))
    }

    // -- F-Number (0x5007) -------------------------------------------------

    /**
     * The reference lists three codes under F-Number that are not apertures.
     * Dividing them by 100 produced "f/655.35" and friends.
     */
    @Test
    fun `F-Number sentinels are not apertures`() {
        assertEquals(READING_UNAVAILABLE, decodeFNumber(0xFFFFL))   // nothing to display
        assertEquals(READING_UNAVAILABLE, decodeFNumber(0xFFFEL))   // "--"
        assertEquals("Iris cerrado", decodeFNumber(0xFFFDL))        // Iris Close
    }

    /** Sony's worked example, plus a value read off the ILCE-7M3. */
    @Test
    fun `real apertures still decode`() {
        // "0x01C2 = 450 / 100 = 4.5"  (the reference writes the decimal, not the hex)
        assertEquals("f/4.5", decodeFNumber(450L))
        assertEquals("f/7.1", decodeFNumber(0x02C6L))   // read off the body
        assertEquals("f/11", decodeFNumber(1100L))
    }

    // -- ISO (0xD21E / 0xD226) ---------------------------------------------

    /**
     * The extended-range offset this app inferred from an a6700 capture is in
     * the reference after all: "To specify extended ISO values, add an offset
     * value 0x10000000." So an extended stop is an ordinary selectable ISO and
     * must not be filtered out as a multi-frame variant.
     */
    @Test
    fun `the extended ISO offset is not a multi-frame variant`() {
        assertEquals("50", decodeIso(0x1000_0032L))        // 0x10000000 + 50
        assertEquals("102400", decodeIso(0x1001_9000L))    // 0x10000000 + 102400
        assertEquals(false, isSonyIsoMfnr(0x1000_0032L))
        assertEquals(false, isSonyIsoMfnr(0x1001_9000L))
    }

    /**
     * The two kinds Sony does document as multi-frame, quoted from its ISO
     * value table: 0x0100000A "Multi Frame NR ISO 10" and 0x0200000A
     * "Multi Frame NR High ISO 10".
     */
    @Test
    fun `the documented multi-frame kinds stay marked`() {
        assertEquals(true, isSonyIsoMfnr(0x0100_000AL))
        assertEquals(true, isSonyIsoMfnr(0x0200_000AL))
        assertEquals("10 +MFNR", decodeIso(0x0100_000AL))
        assertEquals("10 +MFNR+", decodeIso(0x0200_000AL))
    }
}
