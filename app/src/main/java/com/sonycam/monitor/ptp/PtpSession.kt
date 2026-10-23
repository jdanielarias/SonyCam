package com.sonycam.monitor.ptp

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small, Android-free transport contract used by the PTP state machine.
 *
 * Keeping this boundary free of USB classes lets JVM tests replay recorded
 * camera traffic while production continues to pass its USB implementation.
 */
interface PtpTransport {
    val maxPacketIn: Int

    /** Round a read request up to a complete transport packet. */
    fun alignRead(n: Int): Int {
        require(n > 0) { "read size must be positive" }
        require(maxPacketIn > 0) { "maxPacketIn must be positive" }
        val aligned = ((n.toLong() + maxPacketIn - 1L) / maxPacketIn) * maxPacketIn
        require(aligned <= Int.MAX_VALUE) { "aligned read size is too large" }
        return aligned.toInt()
    }

    fun write(data: ByteArray, timeoutMs: Int = 5000): Int
    fun read(max: Int, timeoutMs: Int = 8000): ByteArray

    /**
     * Discard bulk-IN data until the endpoint has been quiet for
     * [quietPeriodMs], bounded by [maxDurationMs]. Returns bytes discarded.
     */
    fun drainInput(quietPeriodMs: Int = 120, maxDurationMs: Int = 960): Int

    /**
     * Read one pending PTP event from the interrupt endpoint, or an empty array
     * when nothing is waiting.
     *
     * Default: nothing. A transport without an interrupt endpoint — a replay
     * fixture, a body that exposes none — simply never has an event, which is
     * the honest answer rather than a special case for callers.
     */
    fun readInterrupt(timeoutMs: Int = 10): ByteArray = ByteArray(0)
}

/**
 * A PTP event as it arrives on the interrupt endpoint: the standard container
 * header followed by up to three u32 parameters.
 */
data class PtpEvent(val code: Int, val tid: Int, val params: List<Int>) {
    override fun toString(): String =
        "0x%04X tid=%d%s".format(code, tid,
            if (params.isEmpty()) "" else params.joinToString(",", " params=") { "0x%X".format(it) })
}

/** Transport failures are separate from valid, non-OK PTP response codes. */
open class PtpTransportException(message: String) : Exception(message)

/**
 * Generic PTP (ISO 15740) over the USB bulk transport. Android port of the
 * `PtpSession` half of `ptp_usb.py`: container framing, transactions,
 * GetDeviceInfo, OpenSession/CloseSession. Sony-specific logic lives in
 * [com.sonycam.monitor.sony.SonyCamera].
 *
 * Container layout (little-endian):
 *   u32 length | u16 type | u16 code | u32 transactionId | payload...
 */
open class PtpSession(protected val transport: PtpTransport) {

    protected var transactionId = 0
    var sessionOpen = false
        protected set

    /*
     * bulkTransfer() is a byte stream, not a container API. One read can end in
     * the middle of a header or contain DATA + RESPONSE + EVENT. Keep unread
     * bytes here so no concatenated container is dropped.
     */
    private var bufferedInput = ByteArray(FIRST_READ_HINT)
    private var bufferedStart = 0
    private var bufferedEnd = 0
    private val bufferedSize: Int get() = bufferedEnd - bufferedStart

    // -- low-level container I/O -------------------------------------------

    private fun transactionIdFor(opcode: Int): Int {
        // ISO 15740 requires OpenSession itself to use transaction id zero and
        // the first transaction inside the new session to start at one. Sony's
        // extended SDIO_OpenSession follows the same pre-session convention.
        if (opcode == OC_OPEN_SESSION || opcode == OC_SDIO_OPEN_SESSION) {
            transactionId = 0
            return 0
        }

        transactionId++
        // Outside OpenSession, zero is reserved. This only matters after an
        // extremely unlikely UInt32 wrap.
        if (transactionId == 0) transactionId = 1
        return transactionId
    }

    protected fun sendCommand(opcode: Int, params: IntArray, tid: Int, timeoutMs: Int) {
        val lengthLong = HEADER_SIZE.toLong() + params.size.toLong() * 4L
        if (lengthLong > MAX_CONTAINER_LENGTH) {
            throw PtpException("PTP command container is too large ($lengthLong bytes)")
        }
        val length = lengthLong.toInt()
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(length)
        buf.putShort(TYPE_COMMAND.toShort())
        buf.putShort(opcode.toShort())
        buf.putInt(tid)
        for (p in params) buf.putInt(p)
        writeFully(buf.array(), timeoutMs)
    }

    protected fun sendData(opcode: Int, tid: Int, data: ByteArray, timeoutMs: Int) {
        val lengthLong = HEADER_SIZE.toLong() + data.size.toLong()
        if (lengthLong > MAX_CONTAINER_LENGTH) {
            throw PtpException("PTP data container is too large ($lengthLong bytes)")
        }
        val length = lengthLong.toInt()
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(length)
        buf.putShort(TYPE_DATA.toShort())
        buf.putShort(opcode.toShort())
        buf.putInt(tid)
        buf.put(data)
        writeFully(buf.array(), timeoutMs)
    }

    private fun writeFully(bytes: ByteArray, timeoutMs: Int) {
        val written = transport.write(bytes, timeoutMs)
        if (written != bytes.size) {
            throw PtpTransportException("short PTP write ($written/${bytes.size} bytes)")
        }
    }

    /** Read one full PTP container. Returns (type, code, tid, payload). */
    private fun readContainer(
        timeoutMs: Int = 8000,
        maxPayload: Long = DEFAULT_MAX_PAYLOAD,
    ): Container {
        ensureBuffered(HEADER_SIZE, FIRST_READ_HINT, timeoutMs)

        val lengthUnsigned = readUInt32Le(bufferedInput, bufferedStart)
        val ceiling = minOf(maxPayload, MAX_CONTAINER_LENGTH)
        if (lengthUnsigned < HEADER_SIZE || lengthUnsigned > ceiling) {
            throw PtpException(
                "Invalid PTP container length $lengthUnsigned (allowed $HEADER_SIZE..$ceiling)"
            )
        }
        val length = lengthUnsigned.toInt()
        val ctype = readUInt16Le(bufferedInput, bufferedStart + 4)
        val code = readUInt16Le(bufferedInput, bufferedStart + 6)
        val tid = readInt32Le(bufferedInput, bufferedStart + 8)

        when (ctype) {
            TYPE_DATA, TYPE_RESPONSE, TYPE_EVENT -> Unit
            else -> throw PtpException("Unexpected inbound PTP container type $ctype")
        }
        if (code == 0) throw PtpException("PTP $ctype container has code 0")
        if (ctype == TYPE_RESPONSE && !isResponseCode(code)) {
            throw PtpException("Invalid PTP response code 0x%04X".format(code))
        }
        if (ctype == TYPE_EVENT && !isEventCode(code)) {
            throw PtpException("Invalid PTP event code 0x%04X".format(code))
        }

        discardBuffered(HEADER_SIZE)
        // A phone has far less headroom than the protocol allows, and the length
        // came off the wire. An allocation failure here would take the process
        // down mid-session; as a PtpException it is a failed transaction the
        // session recovers from, and one the diagnostics can report.
        val payload = try {
            ByteArray(length - HEADER_SIZE)
        } catch (e: OutOfMemoryError) {
            recoverInput("payload of ${length - HEADER_SIZE} bytes did not fit in memory")
            throw PtpException(
                "PTP payload too large for this device (${length - HEADER_SIZE} bytes)"
            )
        }
        var payloadOffset = 0
        while (payloadOffset < payload.size) {
            if (bufferedSize == 0) {
                readMore(minOf(MAX_READ_CHUNK, payload.size - payloadOffset), timeoutMs)
            }
            val n = minOf(bufferedSize, payload.size - payloadOffset)
            System.arraycopy(bufferedInput, bufferedStart, payload, payloadOffset, n)
            discardBuffered(n)
            payloadOffset += n
        }
        return Container(ctype, code, tid, payload)
    }

    /** Ensure [minimum] bytes are buffered, preserving any partial header. */
    private fun ensureBuffered(minimum: Int, readHint: Int, timeoutMs: Int) {
        while (bufferedSize < minimum) {
            readMore(maxOf(readHint, minimum - bufferedSize), timeoutMs)
        }
    }

    /**
     * Perform one non-empty stream read. Sony bodies can legitimately emit a
     * USB zero-length packet between transfers, so a bounded number is skipped.
     */
    private fun readMore(hint: Int, timeoutMs: Int) {
        val request = minOf(MAX_READ_CHUNK, maxOf(1, hint))
        val alignedRequest = transport.alignRead(request)
        var emptyReads = 0
        while (true) {
            // Ask for the already aligned size as well as validating against it.
            // Replay transports need not duplicate USB packet-alignment rules.
            val chunk = transport.read(alignedRequest, timeoutMs)
            if (chunk.isEmpty()) {
                emptyReads++
                if (emptyReads > MAX_EMPTY_READS) {
                    throw PtpException("Too many USB zero-length packets while reading PTP")
                }
                continue
            }
            if (chunk.size > alignedRequest) {
                throw PtpException(
                    "Transport returned ${chunk.size} bytes for a $alignedRequest-byte read"
                )
            }
            appendBuffered(chunk)
            return
        }
    }

    private fun appendBuffered(bytes: ByteArray) {
        val needed = bufferedSize.toLong() + bytes.size.toLong()
        if (needed > MAX_BUFFERED_INPUT) {
            throw PtpException("PTP carry-over buffer exceeded $MAX_BUFFERED_INPUT bytes")
        }

        if (bufferedEnd + bytes.size > bufferedInput.size) {
            if (bufferedStart > 0) {
                System.arraycopy(bufferedInput, bufferedStart, bufferedInput, 0, bufferedSize)
                bufferedEnd = bufferedSize
                bufferedStart = 0
            }
            if (bufferedEnd + bytes.size > bufferedInput.size) {
                var capacity = bufferedInput.size.coerceAtLeast(1)
                val required = bufferedEnd + bytes.size
                while (capacity < required) capacity = minOf(MAX_BUFFERED_INPUT, capacity * 2)
                bufferedInput = bufferedInput.copyOf(capacity)
            }
        }
        System.arraycopy(bytes, 0, bufferedInput, bufferedEnd, bytes.size)
        bufferedEnd += bytes.size
    }

    private fun discardBuffered(count: Int) {
        check(count in 0..bufferedSize)
        bufferedStart += count
        if (bufferedStart == bufferedEnd) {
            bufferedStart = 0
            bufferedEnd = 0
        }
    }

    private fun clearBufferedInput() {
        bufferedStart = 0
        bufferedEnd = 0
    }

    private fun recoverInput(reason: String) {
        clearBufferedInput()
        val result = runCatching {
            transport.drainInput(RECOVERY_QUIET_MS, RECOVERY_MAX_DURATION_MS)
        }
        if (verbose) {
            result.onSuccess { drained ->
                Log.w(TAG_PTP, "PTP input recovery after $reason: drained $drained byte(s)")
            }.onFailure { error ->
                Log.w(TAG_PTP, "PTP input recovery after $reason failed: ${error.message}")
            }
        }
    }

    // -- transaction --------------------------------------------------------

    /**
     * Run a full PTP transaction. Returns the data-phase payload (possibly
     * empty). Throws [PtpException] if the response code is not OK.
     */
    fun transact(
        opcode: Int,
        params: IntArray = IntArray(0),
        dataOut: ByteArray? = null,
        timeoutMs: Int = 8000,
        maxPayload: Long = DEFAULT_MAX_PAYLOAD,
    ): TransactionResult {
        val tid = transactionIdFor(opcode)
        try {
            sendCommand(opcode, params, tid, timeoutMs)
            if (dataOut != null) sendData(opcode, tid, dataOut, timeoutMs)

            // Live-view object fetches run ~20x/s — logging them would flood logcat.
            val loggable = verbose && opcode != OC_GET_OBJECT && opcode != OC_GET_OBJECT_INFO
            if (loggable) Log.d(TAG_PTP, ">> op=0x%04X tid=%d params=%s data=%s".format(
                opcode, tid, params.joinToString(",") { "0x%X".format(it) }.ifEmpty { "-" },
                dataOut?.let { hexPreview(it) } ?: "-"))

            var dataPayload = ByteArray(0)
            var hasDataPhase = false
            var skippedContainers = 0
            while (true) {
                val c = readContainer(timeoutMs, maxPayload)

                if (c.type == TYPE_EVENT) {
                    skippedContainers++
                    if (verbose) Log.d(TAG_PTP,
                        "== EVENT 0x%04X tid=%d during op=0x%04X".format(c.code, c.tid, opcode))
                    if (skippedContainers > MAX_SKIPPED_CONTAINERS) {
                        throw PtpException("Too many unrelated PTP containers during transaction $tid")
                    }
                    continue
                }

                if (c.tid != tid) {
                    if (isOlderTransaction(c.tid, tid)) {
                        skippedContainers++
                        if (verbose) Log.w(TAG_PTP,
                            "-- discarding stale type=${c.type} code=0x%04X tid=${c.tid}; expected tid=$tid"
                                .format(c.code))
                        if (skippedContainers > MAX_SKIPPED_CONTAINERS) {
                            throw PtpException("Too many stale PTP containers before transaction $tid")
                        }
                        continue
                    }
                    throw PtpException(
                        "Unexpected PTP transaction id ${c.tid}; expected $tid " +
                            "(type=${c.type}, code=0x%04X)".format(c.code)
                    )
                }

                when (c.type) {
                    TYPE_DATA -> {
                        if (c.code != opcode) {
                            throw PtpException(
                                "PTP data code 0x%04X does not match operation 0x%04X (tid=$tid)"
                                    .format(c.code, opcode)
                            )
                        }
                        if (hasDataPhase) {
                            throw PtpException("Duplicate PTP data container for tid=$tid")
                        }
                        dataPayload = c.payload
                        hasDataPhase = true
                    }
                    TYPE_RESPONSE -> {
                        if (loggable) Log.d(TAG_PTP,
                            "<< op=0x%04X tid=%d resp=0x%04X data[%dB]=%s".format(
                                opcode, tid, c.code, dataPayload.size, hexPreview(dataPayload)))
                        if (c.code != RC_OK) throw PtpException.forCode(c.code)
                        return TransactionResult(dataPayload, c.code)
                    }
                    else -> throw PtpException("Unexpected container type ${c.type}")
                }
            }
        } catch (e: PtpTransportException) {
            recoverInput("transport failure in op=0x%04X tid=$tid".format(opcode))
            throw e
        } catch (e: PtpException) {
            // A valid camera error response leaves framing aligned. Protocol and
            // framing errors (code == 0) do not, so clear carry-over and drain.
            if (e.code == 0) recoverInput("protocol failure in op=0x%04X tid=$tid".format(opcode))
            throw e
        }
    }

    /**
     * Decode one PTP event container. Returns null for anything that is not a
     * well-formed event, because this is a measurement: a malformed packet is a
     * finding to report, never something to act on.
     */
    fun parseEvent(packet: ByteArray): PtpEvent? {
        if (packet.size < HEADER_SIZE) return null
        val length = readUInt32Le(packet, 0)
        if (length < HEADER_SIZE || length > packet.size) return null
        if (readUInt16Le(packet, 4) != TYPE_EVENT) return null
        val code = readUInt16Le(packet, 6)
        if (!isEventCode(code)) return null
        val tid = readInt32Le(packet, 8)
        val params = ArrayList<Int>(3)
        var at = HEADER_SIZE
        while (at + 4 <= length.toInt()) {
            params += readInt32Le(packet, at)
            at += 4
        }
        return PtpEvent(code, tid, params)
    }

    /**
     * Drain whatever the interrupt endpoint has waiting, up to [max] packets.
     *
     * Nothing in the app acts on the result. Sony bodies advertise event codes
     * in DeviceInfo, but libgphoto2 polls 0x9209 for them rather than trusting
     * this endpoint, so whether they actually arrive is an open question that
     * only a measurement on real hardware can settle.
     */
    fun pollEvents(max: Int = 4, timeoutMs: Int = 10): List<PtpEvent> {
        val out = ArrayList<PtpEvent>()
        repeat(max) {
            val packet = runCatching { transport.readInterrupt(timeoutMs) }.getOrNull()
            if (packet == null || packet.isEmpty()) return out
            parseEvent(packet)?.let { out += it }
                ?: run {
                    if (verbose) Log.w(TAG_PTP, "interrupt: paquete no reconocido de ${packet.size}B")
                    return out
                }
        }
        return out
    }

    // -- standard operations -----------------------------------------------

    /** The last GetDeviceInfo result, so vendor layers can inspect it cheaply. */
    var lastDeviceInfo: DeviceInfo? = null
        protected set

    fun getDeviceInfo(timeoutMs: Int = 8000): DeviceInfo =
        parseDeviceInfo(transact(OC_GET_DEVICE_INFO, timeoutMs = timeoutMs).data)
            .also { lastDeviceInfo = it }

    fun openSession(sessionId: Int = 1) {
        try {
            transact(OC_OPEN_SESSION, intArrayOf(sessionId))
        } catch (e: PtpException) {
            // The camera keeps a session open across USB re-opens; a leftover
            // session (Session_Already_Open) is harmless.
            if (e.code != RC_SESSION_ALREADY_OPEN) throw e
        }
        sessionOpen = true
    }

    fun closeSession() {
        if (!sessionOpen) return
        runCatching { transact(OC_CLOSE_SESSION) }
        sessionOpen = false
    }

    protected data class Container(val type: Int, val code: Int, val tid: Int, val payload: ByteArray)

    companion object {
        /** gphoto2 --debug-style per-transaction PTP logging (tag "PtpTx"). */
        /**
         * Per-transaction PTP logging, on only in debug builds. It prints
         * property blobs and the connected body's identity, which is the whole
         * point when diagnosing and pure leakage in a shipped app. Tests set it
         * to false explicitly; nothing else should turn it on.
         */
        @Volatile var verbose = com.sonycam.monitor.BuildConfig.DEBUG
        const val TAG_PTP = "PtpTx"

        /** Hex-dump a payload, capping long blobs (e.g. the GetAllProps dump). */
        private fun hexPreview(b: ByteArray, max: Int = 96): String {
            if (b.isEmpty()) return "-"
            val n = minOf(b.size, max)
            val sb = StringBuilder(n * 2 + 12)
            for (i in 0 until n) sb.append("%02X".format(b[i]))
            if (b.size > max) sb.append("…+${b.size - max}B")
            return sb.toString()
        }

        /** Buffer for the first read of a container, before its length is known. */
        private const val FIRST_READ_HINT = 16384
        private const val HEADER_SIZE = 12
        private const val MAX_READ_CHUNK = 65536
        private const val MAX_BUFFERED_INPUT = 131072
        /**
         * Absolute ceiling for one PTP container.
         *
         * Sized for the one thing a supported body legitimately sends this big:
         * an uncompressed full-frame RAW. A 61 MP a7R V at 14 bits is about
         * 107 MB and the a1 about 88 MB, so a smaller number would reject real
         * photos from cameras this project claims to support.
         *
         * **It is not a memory-safety measure and must not be mistaken for one.**
         * The payload is still one contiguous array, so a faulty or hostile
         * device declaring this length can put the process under severe memory
         * pressure even though the allocation failure itself is caught. The
         * defence that matters is [DEFAULT_MAX_PAYLOAD]: every transaction is
         * capped small, and only the operations that genuinely download a
         * picture ask for more. Streaming a large object to a file, or fetching
         * it in pieces with 0x9211, is the real fix and is not done yet.
         */
        private const val MAX_CONTAINER_LENGTH = 192L * 1024L * 1024L

        /**
         * What an ordinary transaction may allocate. Property dumps, responses
         * and handshakes are kilobytes; nothing but an image goes past this.
         */
        const val DEFAULT_MAX_PAYLOAD = 4L * 1024L * 1024L

        /** A captured still, RAW included. The only transaction allowed to be big. */
        const val MAX_CAPTURED_STILL_BYTES = MAX_CONTAINER_LENGTH
        private const val MAX_EMPTY_READS = 6

        /**
         * Ceiling for a live-view frame. These arrive twenty-odd times a second
         * and are JPEGs of a few hundred kilobytes; anything near the RAW-sized
         * container limit is not a preview and must not be allocated as if it
         * were.
         */
        const val MAX_LIVE_VIEW_BYTES = 16L * 1024L * 1024L
        private const val MAX_SKIPPED_CONTAINERS = 128
        private const val RECOVERY_QUIET_MS = 350
        private const val RECOVERY_MAX_DURATION_MS = 1400

        private fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readInt32Le(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
            readInt32Le(bytes, offset).toLong() and 0xFFFF_FFFFL

        private fun isResponseCode(code: Int): Boolean =
            code in 0x2000..0x2FFF || code in 0xA000..0xAFFF

        private fun isEventCode(code: Int): Boolean =
            code in 0x4000..0x4FFF || code in 0xC000..0xCFFF

        /** UInt32 serial-number comparison, including wrap-around. */
        private fun isOlderTransaction(received: Int, expected: Int): Boolean {
            val delta = ((expected.toLong() and 0xFFFF_FFFFL) -
                (received.toLong() and 0xFFFF_FFFFL)) and 0xFFFF_FFFFL
            return delta in 1L..0x7FFF_FFFFL
        }

        const val TYPE_COMMAND = 1
        const val TYPE_DATA = 2
        const val TYPE_RESPONSE = 3
        const val TYPE_EVENT = 4

        const val OC_GET_DEVICE_INFO = 0x1001
        const val OC_OPEN_SESSION = 0x1002
        const val OC_CLOSE_SESSION = 0x1003
        const val OC_GET_OBJECT_INFO = 0x1008
        const val OC_GET_OBJECT = 0x1009
        private const val OC_SDIO_OPEN_SESSION = 0x9210

        const val RC_OK = 0x2001
        const val RC_SESSION_ALREADY_OPEN = 0x201E
    }
}

data class TransactionResult(val data: ByteArray, val responseCode: Int)

class PtpException(message: String, val code: Int = 0) : Exception(message) {
    companion object {
        private val names = mapOf(
            0x2002 to "General_Error", 0x2003 to "Session_Not_Open",
            0x2005 to "Operation_Not_Supported", 0x2006 to "Parameter_Not_Supported",
            0x2009 to "Invalid_ObjectHandle", 0x200F to "Access_Denied",
            0x2019 to "Device_Busy", 0x201E to "Session_Already_Open",
        )
        fun forCode(code: Int): PtpException {
            val name = names[code] ?: "0x%04X".format(code)
            return PtpException("PTP response $name", code)
        }
    }
}
