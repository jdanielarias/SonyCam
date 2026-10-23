package com.sonycam.monitor.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table is generated from Sony's compatibility matrix, so these tests do
 * not re-check Sony's data. They check the two things that could silently go
 * wrong in the generation and lookup: a column shifting against its model, and
 * the comparison accusing a body of a shortfall the table cannot speak to.
 *
 * Spot values were read straight out of pages 87-109 of the reference.
 */
class DocumentedCodesTest {

    private val V3 = SonyDocumentedCodes.PROTOCOL_3
    private val V2 = SonyDocumentedCodes.PROTOCOL_2

    /** Codes every listed body supports, and which the app depends on. */
    @Test
    fun `the SDIO core is documented for every listed body`() {
        for (model in listOf("ILCE-6700", "ILCE-7M4", "ILME-FX3", "ZV-E10", "BRC-AM7")) {
            val core = setOf(0x9201, 0x9202, 0x9205, 0x9207, 0x9209)
            assertEquals(
                "$model should be missing none of the SDIO core",
                emptyList<Pair<Int, String>>(),
                SonyDocumentedCodes.missingFor(V3, model, core + setOf(0x1001, 0x1002, 0x1003)).filter {
                    it.first in core
                },
            )
        }
    }

    /** A column shift would show up here first: these differ between bodies. */
    @Test
    fun `optional operations are attributed to the right bodies`() {
        // 0x9215 GetDisplayStringList is documented for the a6700 but not for
        // the a7C, so only the a6700 can be short of it. A column shifted by one
        // would swap these two.
        assertTrue(0x9215 in SonyDocumentedCodes.missingFor(V3, "ILCE-6700", emptySet()).map { it.first })
        assertFalse(0x9215 in SonyDocumentedCodes.missingFor(V3, "ILCE-7C", emptySet()).map { it.first })
        // And a body Sony does not document it for is never told it is extra
        // either — it simply is not part of that body's list.
        assertEquals(listOf(0x9215), SonyDocumentedCodes.undocumentedFor(V3, "ILCE-7C", setOf(0x9215)))
        // Once advertised, it stops being reported as missing.
        assertFalse(0x9215 in SonyDocumentedCodes.missingFor(V3, "ILCE-6700", setOf(0x9215)).map { it.first })
    }

    /** The "(A)" columns cover both spellings a body may report. */
    @Test
    fun `parenthesised column variants resolve to both model strings`() {
        for (model in listOf("ILME-FX3", "ILME-FX3A", "ZV-1", "ZV-1A", "DSC-RX100M7", "DSC-RX100M7A")) {
            assertTrue(model, SonyDocumentedCodes.knows(V3, model))
        }
        assertEquals(
            SonyDocumentedCodes.missingFor(V3, "ILME-FX3", emptySet()),
            SonyDocumentedCodes.missingFor(V3, "ILME-FX3A", emptySet()),
        )
    }

    /**
     * A body is looked up in the table for the protocol it negotiated. The
     * a7 III speaks PTP 2 and is absent from the PTP 3 matrix, so asking the
     * wrong table must yield "no contrast", never a camera missing 91 codes.
     */
    @Test
    fun `the wrong protocol table never accuses a body of a shortfall`() {
        assertFalse(SonyDocumentedCodes.knows(V3, "ILCE-7M3"))
        assertEquals(emptyList<Pair<Int, String>>(), SonyDocumentedCodes.missingFor(V3, "ILCE-7M3", emptySet()))
        assertEquals(emptyList<Int>(), SonyDocumentedCodes.undocumentedFor(V3, "ILCE-7M3", setOf(0x9209)))
        // Nor does an unnegotiated protocol version.
        assertFalse(SonyDocumentedCodes.knows(0, "ILCE-7M3"))
    }

    /**
     * The real check for an a7 III: its DeviceInfo, transcribed from a live
     * session, against Sony's PTP 2 list. Every documented code is there.
     */
    @Test
    fun `a real a7 III handshake is complete against Sony's PTP 2 list`() {
        val advertised = setOf(
            0x1002, 0x1003, 0x1001, 0x1004, 0x1005, 0x1006, 0x1007, 0x1008,
            0x1009, 0x100A, 0x101B, 0x9201, 0x9202, 0x9205, 0x9207, 0x9209,
            0xC201, 0xC202, 0xC203,
        )
        assertTrue(SonyDocumentedCodes.knows(V2, "ILCE-7M3"))
        assertEquals(
            emptyList<Pair<Int, String>>(),
            SonyDocumentedCodes.missingFor(V2, "ILCE-7M3", advertised),
        )
        // The storage operations it also offers are outside Sony's remote-control
        // reference, so they are reported as undocumented rather than as faults.
        assertEquals(
            listOf(0x1004, 0x1005, 0x1006, 0x1007),
            SonyDocumentedCodes.undocumentedFor(V2, "ILCE-7M3", advertised),
        )
    }

    @Test
    fun `model matching tolerates case and whitespace`() {
        assertTrue(SonyDocumentedCodes.knows(V3, " ilce-6700 "))
        assertEquals(
            SonyDocumentedCodes.missingFor(V3, "ILCE-6700", emptySet()),
            SonyDocumentedCodes.missingFor(V3, " ilce-6700 ", emptySet()),
        )
    }

    /** A code the table does not carry for this body is reported as extra. */
    @Test
    fun `codes beyond the table are listed as undocumented`() {
        val extra = SonyDocumentedCodes.undocumentedFor(V3, "ILCE-6700", setOf(0x9209, 0xABCD))
        assertEquals(listOf(0xABCD), extra)
    }

    @Test
    fun `documented names come back for the codes the app negotiates`() {
        assertEquals("SDIO GetAllExtDevicePropInfo", SonyDocumentedCodes.nameOf(V3, 0x9209))
        assertEquals("SDIO GetPartialLargeObject", SonyDocumentedCodes.nameOf(V3, 0x9211))
        assertEquals("SDIO OpenSession", SonyDocumentedCodes.nameOf(V3, 0x9210))
    }
}
