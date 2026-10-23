package com.sonycam.monitor.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [UsbTransport] against the real Android USB stack.
 *
 * The safety property here — that the app only ever touches a still-image
 * interface — cannot be checked in a JVM test, because `UsbDevice` has no public
 * constructor and its class/endpoint data comes from the platform. It also
 * cannot be checked by reasoning: the whole point is what happens with a device
 * that is *not* a camera in front of us.
 *
 * So the interesting tests here need hardware attached, and say so rather than
 * passing vacuously: each one states what it needs and is skipped when it is not
 * there. Plug in anything at all — a stick, a hub, a keyboard — and the guard
 * tests become real; plug in the camera and the lifecycle tests do too.
 */
@RunWith(AndroidJUnit4::class)
class UsbTransportTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val devices: List<UsbDevice> get() = manager.deviceList.values.toList()

    private fun UsbDevice.hasStillImageInterface(): Boolean =
        (0 until interfaceCount).any {
            getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE
        }

    private fun cameras() = devices.filter { it.hasStillImageInterface() }
    private fun nonCameras() = devices.filterNot { it.hasStillImageInterface() }

    // -- the guard that keeps the app off other people's hardware -----------

    /**
     * The regression this exists for. The app claims interfaces by force and can
     * escalate as far as re-applying the USB configuration; doing that to a
     * drive that merely shares Sony's vendor id is damage, not a degraded
     * experience. Anything without a still-image interface must be invisible.
     */
    @Test
    fun aDeviceWithoutAStillImageInterfaceIsNeverPicked() {
        val others = nonCameras()
        assumeTrue("needs any non-camera USB device attached", others.isNotEmpty())
        for (d in others) {
            assertNull(
                "picked a non-camera: ${d.deviceName} vid=${d.vendorId} pid=${d.productId}",
                UsbTransport.pickCameraInterface(d),
            )
        }
    }

    /** And opening one must refuse before it claims anything. */
    @Test
    fun openingANonCameraRefusesInsteadOfClaiming() {
        val others = nonCameras()
        assumeTrue("needs any non-camera USB device attached", others.isNotEmpty())
        val d = others.first()
        val transport = UsbTransport(manager, d)
        val e = assertThrows(UsbException::class.java) { transport.open() }
        assertTrue("unexpected message: ${e.message}", e.message!!.contains("still-image"))
    }

    /** A camera is picked, and the interface really is the still-image one. */
    @Test
    fun aCameraYieldsItsStillImageInterface() {
        val cams = cameras()
        assumeTrue("needs a camera attached", cams.isNotEmpty())
        val intf = UsbTransport.pickCameraInterface(cams.first())
        assertNotNull(intf)
        assertEquals(UsbConstants.USB_CLASS_STILL_IMAGE, intf!!.interfaceClass)
    }

    // -- the closed transport ------------------------------------------------

    /**
     * Every entry point has to survive being called on a transport that was
     * never opened, or was closed underneath it. Teardown races and detach both
     * produce exactly that, and an exception escaping here would surface as a
     * lost session rather than a no-op.
     */
    @Test
    fun aClosedTransportRefusesWorkWithoutThrowingItsWayOut() {
        assumeTrue("needs any USB device attached", devices.isNotEmpty())
        val transport = UsbTransport(manager, devices.first())

        assertThrows(UsbException::class.java) { transport.write(byteArrayOf(1, 2, 3)) }
        assertThrows(UsbException::class.java) { transport.read(512) }
        // These are recovery paths: on a dead transport they report failure.
        // `resetPtpDevice` used to be checked here too; it was removed along
        // with the forced claim — measured on a wedged body, the class reset was
        // accepted and revived nothing, and it doubles as MTP's own reset on the
        // shared 6/1/1 class. `drainInput` is the one that actually recovers.
        assertEquals(0, transport.readInterrupt().size)
        assertEquals(0, transport.drainInput())
        // And closing something never opened must not throw.
        transport.close()
    }

    /** Timeouts are validated rather than passed down to the USB stack. */
    @Test
    fun nonPositiveTimeoutsAreRejected() {
        assumeTrue("needs any USB device attached", devices.isNotEmpty())
        val transport = UsbTransport(manager, devices.first())
        assertThrows(UsbException::class.java) { transport.write(byteArrayOf(1), timeoutMs = 0) }
        assertThrows(UsbException::class.java) { transport.read(512, timeoutMs = 0) }
    }

    // -- the camera, when there is one ---------------------------------------

    /**
     * The full lifecycle on real hardware. Needs the camera in PC Remote and the
     * USB permission already granted, so it is skipped rather than failed when
     * the phone has not been told to trust it.
     */
    @Test
    fun aGrantedCameraOpensAndCloses() {
        val cam = cameras().firstOrNull { manager.hasPermission(it) }
        assumeTrue("needs an attached camera with USB permission granted", cam != null)
        val transport = UsbTransport(manager, cam!!)
        transport.open()
        try {
            assertTrue("bulk-IN packet size looks wrong", transport.maxPacketIn >= 64)
            // Reads round up to a whole packet, so this must never come back
            // longer than asked for.
            assertTrue(transport.alignRead(1) >= transport.maxPacketIn)
        } finally {
            transport.close()
        }
        // Closing twice is what a teardown race looks like; it must be harmless.
        transport.close()
    }
}
