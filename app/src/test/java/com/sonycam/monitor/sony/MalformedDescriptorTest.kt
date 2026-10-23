package com.sonycam.monitor.sony

import com.sonycam.monitor.ptp.PtpReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A descriptor blob is untrusted input: it arrives over the wire and its counts
 * are u32. The parser must reject a length it cannot honour rather than compute
 * a smaller one by accident.
 */
class MalformedDescriptorTest {

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )

    /**
     * An array count that wraps a signed Int into a small positive number.
     *
     * 0x40000001 four-byte elements is 0x100000004 bytes; as Int that truncates
     * to **4**. The old check asked whether it could read four bytes, said yes,
     * and skipped four — so a sixteen-gigabyte array was accepted as a
     * well-formed field and the reader carried on four bytes later, in the
     * middle of nothing. Sized in Long the same descriptor is refused.
     *
     * A count that wraps to exactly zero would not show this: skipping nothing
     * and refusing both leave the reader where it was. It has to wrap small.
     */
    @Test
    fun `an array count that wraps Int is refused, not skipped`() {
        val blob = le16(0xD200) +
            le16(DTC_UINT32 or DTC_ARRAY_MASK) +
            byteArrayOf(0x01, 0x01) +
            le32(0x4000_0001L) +
            ByteArray(16)                        // enough that a 4-byte skip fits

        val r = PtpReader(blob)
        assertNull("a 16 GB array is not a descriptor", parseSonyDpd(r))
        assertEquals("and the reader must not have moved", 0, r.pos)
    }

    /** A count that fits but overruns the blob is refused the same way. */
    @Test
    fun `an array longer than the blob is refused`() {
        val blob = le16(0xD200) +
            le16(DTC_UINT32 or DTC_ARRAY_MASK) +
            byteArrayOf(0x01, 0x01) +
            le32(1000L) +
            ByteArray(16)

        val r = PtpReader(blob)
        assertNull(parseSonyDpd(r))
        assertEquals(0, r.pos)
    }

    /** A truncated blob must not throw its way out of the parser. */
    @Test
    fun `a truncated descriptor returns null instead of throwing`() {
        for (n in 0..7) {
            val blob = (le16(0xD200) + le16(DTC_UINT32) + byteArrayOf(0x01, 0x01)).copyOf(n)
            assertNull("truncated at $n", parseSonyDpd(PtpReader(blob)))
        }
    }

    /**
     * The regression this file exists for. A blob whose string lengths run past
     * its own end made PtpReader throw, and that escaped parseSonyAllProps
     * entirely — past the resync machinery written for malformed blobs, which
     * only runs when the descriptor parser returns null. In the app it surfaced
     * as a poll failure, and three of those end the session.
     */
    @Test
    fun `a blob that lies about its lengths is resynced, not thrown`() {
        val warnings = mutableListOf<String>()
        val props = parseSonyAllProps(
            le32(0xFFFF_FFFFL) + le32(0) + ByteArray(64) { 0xFF.toByte() },
            warnings::add,
        )
        assertTrue("must return a map rather than throw", props.isEmpty())
        assertTrue("the caller should be told why", warnings.isNotEmpty())
    }

    /** And the descriptor parser leaves the reader where it found it. */
    @Test
    fun `a throwing descriptor rewinds the reader`() {
        val blob = le16(0xD200) + le16(DTC_STR) + byteArrayOf(0x01, 0x01, 0x7F)
        val r = PtpReader(blob)
        r.skip(0)
        val at = r.pos
        assertNull(parseSonyDpd(r))
        assertEquals("position must be restored", at, r.pos)
    }
}
