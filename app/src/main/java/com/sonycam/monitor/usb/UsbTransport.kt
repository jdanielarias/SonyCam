package com.sonycam.monitor.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.sonycam.monitor.ptp.PtpTransport
import com.sonycam.monitor.ptp.PtpTransportException

private const val TAG = "SonyMon"

/** Logcat carries the connected body's identity; keep it to debug builds. */
private val DEBUG = com.sonycam.monitor.BuildConfig.DEBUG

/** Sony USB vendor id (0x054C). */
const val SONY_VID = 0x054C

/**
 * Low-level USB bulk transport for a single still-image / vendor interface.
 *
 * Android port of the desktop harness `ptp_usb.py` transport layer: it claims
 * the PTP interface, locates the bulk-IN / bulk-OUT / interrupt endpoints, and
 * exposes raw read/write plus a drain-on-open recovery (an aborted GetObject on
 * Sony leaves the pipe desynced; draining the IN endpoint clears it).
 */
class UsbTransport(
    private val manager: UsbManager,
    private val device: UsbDevice,
) : PtpTransport {
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    lateinit var epIn: UsbEndpoint
        private set
    lateinit var epOut: UsbEndpoint
        private set
    var epInt: UsbEndpoint? = null
        private set

    val productName: String? get() = device.productName

    /** Open the device, claim the PTP interface, bind endpoints, drain stale data. */
    fun open() {
        // The transport enforces this itself rather than trusting its caller:
        // everything below claims by force and can go as far as re-applying the
        // USB configuration, so "is this a camera?" has to be answered at the
        // boundary that does the damage, not only at the one that picks devices.
        val chosen = pickCameraInterface(device)
            ?: throw UsbException("No still-image (PTP) interface on this device.")

        val conn = manager.openDevice(device)
            ?: throw UsbException("openDevice failed (permission not granted?).")
        // Own it immediately so connection initialization can close it even if
        // claiming the interface or binding endpoints fails partway through.
        connection = conn

        // Never `force = true`. Forcing tears the interface away from whatever
        // driver holds it, and class 6/1/1 is not proof of a camera — Android
        // uses the same triple for MTP, so the thing being seized could be a
        // phone in the middle of a file transfer. It is also not needed: on the
        // A7 III a polite claim succeeds on a cold connect *and* straight after
        // this app was killed mid-session, because what actually recovers that
        // state is the stale-input drain at the end of this method (measured:
        // 156 556 bytes discarded, then a clean handshake). Failing to claim is
        // the correct outcome.
        if (!conn.claimInterface(chosen, false)) {
            close()
            throw UsbException(
                "Otra aplicación está usando este dispositivo. Ciérrala, o " +
                    "desconecta y vuelve a conectar el cable."
            )
        }
        iface = chosen

        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        var interrupt: UsbEndpoint? = null
        for (i in 0 until chosen.endpointCount) {
            val ep = chosen.getEndpoint(i)
            when {
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN -> bulkIn = ep
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT -> bulkOut = ep
                ep.type == UsbConstants.USB_ENDPOINT_XFER_INT -> interrupt = ep
            }
        }
        if (bulkIn == null || bulkOut == null) {
            close()
            throw UsbException("PTP interface is missing bulk endpoints.")
        }

        epIn = bulkIn
        epOut = bulkOut
        epInt = interrupt

        if (DEBUG) Log.i(TAG, "open: vid=%04X pid=%04X intf#%d class=0x%02X alt=%d epIn=0x%02X epOut=0x%02X epInt=%s".format(
            device.vendorId, device.productId, chosen.id, chosen.interfaceClass,
            chosen.alternateSetting, bulkIn.address, bulkOut.address,
            interrupt?.let { "0x%02X".format(it.address) } ?: "none"))

        // Note: endpoints are NOT reset here. A body that is behaving needs no
        // control traffic it did not ask for, so [recoverEndpoints] is driven
        // lazily off an actual failed write instead.
        drainInput()
    }

    fun close() {
        val conn = connection
        val claimed = iface
        connection = null
        iface = null
        try {
            claimed?.let { conn?.releaseInterface(it) }
        } finally {
            conn?.close()
        }
    }

    /**
     * Clear a HALT (stall) condition on one endpoint.
     *
     * `libusb_clear_halt` maps to the `USBDEVFS_CLEAR_HALT` ioctl, which Android
     * does not expose, so send the standard CLEAR_FEATURE(ENDPOINT_HALT) request
     * of USB 2.0 §9.4.1 by hand. It is a legal no-op on an endpoint that is not
     * halted, so it is safe to issue speculatively.
     */
    private fun clearStall(ep: UsbEndpoint): Boolean {
        val conn = connection ?: return false
        val n = conn.controlTransfer(
            REQTYPE_ENDPOINT_OUT, REQ_CLEAR_FEATURE, FEATURE_ENDPOINT_HALT,
            ep.address, null, 0, CONTROL_TIMEOUT_MS,
        )
        return n >= 0
    }

    /**
     * Unhalt both bulk endpoints and resynchronise their data toggles.
     *
     * CLEAR_FEATURE alone only unhalts the *device* side: the kernel resets its
     * own toggle in `usb_clear_halt()`, which the raw control request does not
     * reach. SET_INTERFACE does reset both, so it follows. A body that exposes a
     * single alternate setting may legally stall SET_INTERFACE (§9.4.10); the
     * kernel then resets the endpoints itself, so neither step is fatal here.
     *
     * Only ever called after a bulk transfer has already failed, so a session
     * that is working never sees any of this traffic.
     */
    private fun recoverEndpoints(reason: String): Boolean {
        val conn = connection ?: return false
        val inCleared = clearStall(epIn)
        val outCleared = clearStall(epOut)
        val reselected = iface?.let { conn.setInterface(it) } ?: false
        if (DEBUG) Log.w(TAG, "endpoint recovery ($reason): clearHalt in=%b out=%b setInterface=%b"
            .format(inCleared, outCleared, reselected))
        return inCleared || outCleared || reselected
    }

    /** Write [data] to the bulk-OUT endpoint. Returns bytes written. */
    override fun write(data: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: throw UsbException("not open")
        if (timeoutMs <= 0) throw UsbException("bulk write timeout must be positive")
        if (data.isEmpty()) return 0

        // Android may legally return a short positive bulk write. Complete it
        // here so callers never put half a PTP container on the wire.
        val deadline = deadlineAfter(timeoutMs)
        var offset = 0
        var recovered = false
        while (offset < data.size) {
            val chunk = minOf(data.size - offset, MAX_BULK_TRANSFER)
            val n = conn.bulkTransfer(
                epOut, data, offset, chunk, remainingMs(deadline, "bulk write")
            )
            if (n <= 0) {
                // Nothing of this container is on the wire yet, so unhalting and
                // resending it once is safe and cannot duplicate bytes. Past the
                // first chunk it would, so that case still fails to the caller.
                if (offset == 0 && !recovered && recoverEndpoints("bulk write")) {
                    recovered = true
                    continue
                }
                throw UsbException("bulk write failed/timed out ($offset/${data.size} bytes sent)")
            }
            offset += n
        }
        return offset
    }

    /**
     * Bulk-IN wMaxPacketSize: 512 on USB 2.0 High-Speed, **1024 on SuperSpeed**
     * (USB 3.x), which every 2020+ body has. A bulkTransfer whose buffer is not a
     * whole number of packets can overflow when the device sends a full packet
     * into the remainder, so every read is rounded up to a multiple of this.
     */
    override val maxPacketIn: Int
        get() = if (::epIn.isInitialized) epIn.maxPacketSize.coerceAtLeast(64) else 512

    /**
     * Read from the bulk-IN endpoint into a fresh array. [max] is rounded up to a
     * packet multiple; the returned array is the actual number of bytes received.
     */
    override fun read(max: Int, timeoutMs: Int): ByteArray {
        val conn = connection ?: throw UsbException("not open")
        if (timeoutMs <= 0) throw UsbException("bulk read timeout must be positive")
        val aligned = try {
            alignRead(max)
        } catch (e: IllegalArgumentException) {
            throw UsbException("invalid bulk read size $max")
        }
        if (aligned > MAX_USB_READ) {
            throw UsbException("bulk read request is too large ($aligned > $MAX_USB_READ bytes)")
        }
        // usbfs rejects an oversized bulk request outright, and Android stopped
        // clamping it for us at targetSdk 28. Ask for at most one usbfs buffer;
        // callers already loop, and a short read is how bulk-IN ends anyway.
        val buf = ByteArray(minOf(aligned, MAX_BULK_TRANSFER))
        val deadline = deadlineAfter(timeoutMs)
        var n = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs)
        if (n < 0) {
            // A responder aborts a data phase by HALTing bulk-IN (PIMA 15740
            // §D.5.2) — that is how a camera says "I have no such object", and
            // it is how an A7 III answers a live-view fetch while live view is
            // off. It fails at once instead of consuming the timeout, which is
            // what tells it apart from a camera that is merely slow. Left
            // halted it takes the whole session down; cleared, the RESPONSE
            // container carrying the real PTP error code is still waiting.
            val leftMs = millisUntil(deadline)
            if (leftMs > 0 && recoverEndpoints("bulk read")) {
                n = conn.bulkTransfer(epIn, buf, buf.size, leftMs)
            }
        }
        if (n < 0) throw UsbException("bulk read failed/timed out")
        return buf.copyOf(n)
    }

    /**
     * Read one packet from the interrupt endpoint, or nothing.
     *
     * Purely a measurement. It is called from inside the same lock that
     * serialises PTP transactions, never from a second thread, so it cannot
     * interleave with a bulk transfer — this app's failures have all come from
     * traffic arriving where it was not expected, and an experiment must not add
     * to that. The cost is [timeoutMs] per call and nothing else.
     */
    override fun readInterrupt(timeoutMs: Int): ByteArray {
        val conn = connection ?: return ByteArray(0)
        val ep = epInt ?: return ByteArray(0)
        if (timeoutMs <= 0) return ByteArray(0)
        val buf = ByteArray(ep.maxPacketSize.coerceIn(8, 512))
        val n = conn.bulkTransfer(ep, buf, buf.size, timeoutMs)
        // A timeout is the ordinary answer: no event was waiting.
        if (n <= 0) return ByteArray(0)
        return buf.copyOf(n)
    }

    /**
     * Drain leftover bytes from a previously aborted transaction. The endpoint
     * must stay quiet for [quietPeriodMs], so a DATA packet immediately followed
     * by its RESPONSE cannot leave the latter queued for the next transaction.
     */
    override fun drainInput(quietPeriodMs: Int, maxDurationMs: Int): Int {
        val conn = connection ?: return 0
        require(quietPeriodMs > 0) { "quietPeriodMs must be positive" }
        require(maxDurationMs >= quietPeriodMs) {
            "maxDurationMs must be at least quietPeriodMs"
        }

        val buf = ByteArray(alignRead(16384))
        val deadline = deadlineAfter(maxDurationMs)
        var quietSince = System.nanoTime()
        var drained = 0L

        while (true) {
            val now = System.nanoTime()
            val quietRemainingNs = quietPeriodMs * NANOS_PER_MS - (now - quietSince)
            val deadlineRemainingNs = deadline - now
            if (quietRemainingNs <= 0L || deadlineRemainingNs <= 0L) break

            val waitNs = minOf(quietRemainingNs, deadlineRemainingNs)
            val waitMs = ((waitNs + NANOS_PER_MS - 1L) / NANOS_PER_MS)
                .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val n = conn.bulkTransfer(epIn, buf, buf.size, waitMs)
            if (n > 0) {
                drained = (drained + n).coerceAtMost(Int.MAX_VALUE.toLong())
                quietSince = System.nanoTime()
            } else if (n < 0) {
                // The call used the entire remaining quiet window as timeout.
                // A negative result therefore means either "quiet achieved" or
                // an endpoint error which another tight-loop read cannot repair.
                break
            }
        }

        if (drained > 0 && DEBUG) Log.w(TAG, "drainInput: discarded $drained stale byte(s)")
        return drained.toInt()
    }

    private fun deadlineAfter(timeoutMs: Int): Long =
        System.nanoTime() + timeoutMs.toLong() * NANOS_PER_MS

    /** Milliseconds left before [deadline], or 0 once it has passed. */
    private fun millisUntil(deadline: Long): Int {
        val remainingNs = deadline - System.nanoTime()
        if (remainingNs <= 0L) return 0
        return ((remainingNs + NANOS_PER_MS - 1L) / NANOS_PER_MS)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun remainingMs(deadline: Long, operation: String): Int {
        val remainingNs = deadline - System.nanoTime()
        if (remainingNs <= 0L) throw UsbException("$operation timed out")
        return ((remainingNs + NANOS_PER_MS - 1L) / NANOS_PER_MS)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {

        private const val MAX_USB_READ = 1024 * 1024
        private const val NANOS_PER_MS = 1_000_000L

        /**
         * usbfs copies a bulk transfer through one kernel buffer of this size
         * (`MAX_USBFS_BUFFER_SIZE`), and returns EINVAL — an instant -1, not a
         * timeout — for anything larger. Android used to clamp to it silently;
         * since targetSdk 28 the oversized request is passed straight through.
         */
        private const val MAX_BULK_TRANSFER = 16384

        /** CLEAR_FEATURE(ENDPOINT_HALT), USB 2.0 §9.4.1. */
        private const val REQTYPE_ENDPOINT_OUT = 0x02
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val FEATURE_ENDPOINT_HALT = 0x00
        private const val CONTROL_TIMEOUT_MS = 1000

        /** Device Reset Request, still-image class (PIMA 15740 §D.2.1). */
        private const val REQTYPE_CLASS_INTERFACE_OUT = 0x21
        private const val PTP_REQ_DEVICE_RESET = 0x66
        private const val RESET_SETTLE_MS = 800L

        /**
         * The still-image (class 6) interface, or null.
         *
         * This is what decides whether the app touches a device at all, so it
         * insists on the class that means "camera". Sony's vendor id is shared
         * with drives, controllers and audio gear, and the recovery ladder
         * escalates as far as re-applying the USB configuration — doing that to
         * somebody's SSD because it happens to expose bulk endpoints is not a
         * degraded experience, it is damage.
         */
        fun pickCameraInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_STILL_IMAGE) continue
                if (hasBulkPair(intf)) return intf
            }
            return null
        }

        private fun hasBulkPair(intf: UsbInterface): Boolean {
            var hasIn = false
            var hasOut = false
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
            }
            return hasIn && hasOut
        }

    }
}

class UsbException(message: String) : PtpTransportException(message)

/**
 * The body enumerates, answers control requests, and even accepts the PTP class
 * reset, but will not move a single byte over its bulk endpoints. Nothing the
 * host can send fixes that — the camera's own PTP stack has to be restarted.
 */
class CameraNotResponding(cause: Exception) : Exception(
    "La cámara está conectada pero no acepta datos PTP (${cause.message}).\n\n" +
        "Desconecta el cable USB y vuelve a conectarlo, o apaga y enciende la " +
        "cámara. Suele quedar así cuando una sesión anterior no se cerró bien.",
    cause,
)
