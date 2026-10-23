package com.sonycam.monitor.ptp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a Sony camera from a Sony phone, before a session is opened.
 *
 * A camera and an MTP responder share USB class 6/1/1, so both land in this app
 * and neither can be recognised by its manufacturer string — a string the far
 * end of the cable chooses. These predicates are what `refuseIfNotACamera`
 * decides on.
 *
 * They were written to gate a forced interface claim and the still-image class
 * reset. Both operations have since been **removed** rather than gated: the
 * reset never once revived a wedged body when it was measured, a polite claim
 * proved sufficient on real hardware, and the thing that actually recovers a
 * lost session is the stale-input drain. The predicates stay because refusing
 * an MTP device before opening a session still needs them.
 */
class DeviceTrustTest {

    private fun info(
        vendorExtensionId: Long,
        manufacturer: String,
        ops: IntArray,
        desc: String = "",
    ) = DeviceInfo(
        standardVersion = 100,
        vendorExtensionId = vendorExtensionId,
        vendorExtensionVersion = 100,
        vendorExtensionDesc = desc,
        functionalMode = 0,
        operationsSupported = ops,
        eventsSupported = IntArray(0),
        devicePropertiesSupported = IntArray(0),
        captureFormats = IntArray(0),
        imageFormats = IntArray(0),
        manufacturer = manufacturer,
        model = "TEST",
        deviceVersion = "1.00",
        serialNumber = "",
    )

    /** Measured on the physical A7 III: vendorExt=0x11, ops include 0x9201. */
    @Test
    fun aRealSonyCameraIsTrusted() {
        val di = info(0x11L, "Sony", intArrayOf(0x1001, 0x1002, 0x9201, 0x9209))
        assertTrue(di.isTrustedSonyCamera)
        assertTrue(di.isSonyBrand)
    }

    /**
     * The hole this class exists for. A Sony phone in MTP answers GetDeviceInfo
     * with manufacturer "Sony" and no camera operation whatsoever. The old
     * predicate accepted it on the string alone and handed it the forced claim
     * and the class reset.
     */
    @Test
    fun aSonyBrandedPhoneIsNotTrusted() {
        val di = info(
            vendorExtensionId = 6L,
            manufacturer = "Sony",
            ops = intArrayOf(0x1001, 0x1002, 0x1014, 0x9801, 0x9802),
            desc = "microsoft.com: 1.0;",
        )
        assertTrue("brand is still readable, for the error message", di.isSonyBrand)
        assertFalse("but the brand must not unlock anything", di.isTrustedSonyCamera)
    }

    /**
     * Each MTP signal on its own. Testing them together proves neither: a
     * device that fills in the id but leaves the description blank used to sail
     * through, and a test supplying both could not have caught it.
     */
    @Test
    fun mtpIsRecognisedByItsExtensionIdWithNoDescription() {
        assertTrue(info(6L, "Acme", intArrayOf(0x1001, 0x1014), desc = "").isMtpResponder)
    }

    @Test
    fun mtpIsRecognisedByItsDescriptionWithNoId() {
        assertTrue(info(0L, "Acme", intArrayOf(0x1001, 0x1014), desc = "microsoft.com: 1.0;").isMtpResponder)
    }

    @Test
    fun aSonyCameraIsNotAnMtpResponder() {
        assertFalse(info(0x11L, "Sony", intArrayOf(0x1001, 0x9201), desc = "Sony PTP Extensions").isMtpResponder)
    }

    /**
     * The extension id on its own is a declared constant, as forgeable as the
     * manufacturer string. The operation list is a capability the device has to
     * honour on the next transaction, so proof needs both.
     */
    @Test
    fun theVendorExtensionIdAloneIsNotProof() {
        assertFalse(info(0x11L, "Sony", intArrayOf(0x1001, 0x1002, 0x1014)).isTrustedSonyCamera)
    }

    @Test
    fun aVendorOperationUnderTheWrongExtensionIsNotProof() {
        assertFalse(info(6L, "Sony", intArrayOf(0x1001, 0x9201)).isTrustedSonyCamera)
    }

    /** Every camera dialect counts: SDIO, legacy per-property, and QX. */
    @Test
    fun anyCameraDialectCounts() {
        for (op in intArrayOf(0x9201, 0x9202, 0x9203, 0x9205, 0x9207, 0x9209, 0x96FE)) {
            assertTrue(
                "0x%04X should prove a camera dialect".format(op),
                info(0x11L, "Sony", intArrayOf(0x1001, op)).isTrustedSonyCamera,
            )
        }
    }

    @Test
    fun aPlainPtpCompactIsNotMistakenForMtp() {
        val di = info(0L, "Sony", intArrayOf(0x1001, 0x1002, 0x1014), desc = "")
        assertFalse(di.isMtpResponder)
        assertFalse(di.isTrustedSonyCamera)
    }
}
