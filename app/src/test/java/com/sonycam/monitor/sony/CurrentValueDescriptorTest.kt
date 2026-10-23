package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.PtpReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A descriptor carries two values of the same type — a factory default and the
 * current one — and both have to be consumed before it can be trusted.
 *
 * Checking only the default was not a smaller version of the same guard, it was
 * a hole: the second read would refuse an impossible field, leave the reader
 * untouched, and nothing noticed. The form flag was then read out of the middle
 * of the bytes that had just been refused.
 */
class CurrentValueDescriptorTest {

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )

    /**
     * The reproduction: a valid empty default array, then a current array
     * claiming 0x40000001 four-byte elements. This used to return a descriptor
     * with the reader left at offset 11, in the middle of that count.
     */
    @Test
    fun `a malformed current array is refused, not built into a descriptor`() {
        val blob = le16(0xD200) +
            le16(DTC_UINT32 or DTC_ARRAY_MASK) +
            byteArrayOf(0x01, 0x01) +
            le32(0L) +                    // default array, count 0 — legitimate
            le32(0x4000_0001L) +          // current array, an impossible count
            ByteArray(16)

        val r = PtpReader(blob)
        assertNull("a refused current value cannot yield a descriptor", parseSonyDpd(r))
        assertEquals("and the reader must be back at the start", 0, r.pos)
    }

    /** The same shape with both arrays well formed still parses. */
    @Test
    fun `a descriptor whose arrays are both valid still parses`() {
        val blob = le16(0xD200) +
            le16(DTC_UINT32 or DTC_ARRAY_MASK) +
            byteArrayOf(0x01, 0x01) +
            le32(0L) +                    // default array, empty
            le32(0L) +                    // current array, empty
            byteArrayOf(DPFF_NONE.toByte())

        val r = PtpReader(blob)
        val dpd = parseSonyDpd(r)
        assertEquals(0xD200, dpd?.code)
        assertEquals("the whole descriptor should be consumed", blob.size, r.pos)
    }
}
