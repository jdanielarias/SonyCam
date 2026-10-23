package com.sonycam.monitor.ui

import com.sonycam.monitor.sony.SonyLiveViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class CameraOperationGateTest {
    private class CameraState {
        var capturing = false
        var sinceCapture = Long.MAX_VALUE
        var liveViewStatus = 1L
        var liveView = SonyLiveViewState.AVAILABLE
        fun allowed() = mayFetchLiveView(liveView, liveViewStatus, capturing, sinceCapture)
    }

    private fun queuedOperationIsSkipped(change: (CameraState) -> Unit) = runBlocking {
        val state = CameraState()
        val mutex = Mutex(locked = true)
        var requests = 0
        assertTrue(state.allowed())
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCameraOperation(mutex, state::allowed) { requests++; byteArrayOf(1) }
        }
        // The operation has reached the held mutex but has not sent anything.
        assertFalse(pending.isCompleted)
        change(state)
        mutex.unlock()
        assertEquals(CameraOperation.Skipped, pending.await())
        assertEquals(0, requests)
    }

    @Test fun `a capture starting while live view waits prevents the queued request`() {
        queuedOperationIsSkipped { it.capturing = true }
    }

    @Test fun `capture completion starts a quiet period for an already queued request`() {
        queuedOperationIsSkipped { it.sinceCapture = 0 }
    }

    @Test fun `a newly disabled camera status prevents a queued capability probe`() {
        queuedOperationIsSkipped {
            it.liveView = SonyLiveViewState.UNVERIFIED
            it.liveViewStatus = 0
        }
    }

    @Test fun `a disabled session prevents an already queued request`() {
        queuedOperationIsSkipped { it.liveView = SonyLiveViewState.DISABLED }
    }

    @Test fun `eligible operation executes under the same USB lock`() = runBlocking {
        val mutex = Mutex()
        val image = byteArrayOf(0x01, 0x02)
        val result = runCameraOperation(mutex, { true }) {
            assertTrue(mutex.isLocked)
            image
        }
        assertSame(image, (result as CameraOperation.Completed).value)
        assertFalse(mutex.isLocked)
    }

    @Test fun `a camera returning no frame is distinct from a skipped request`() = runBlocking {
        val result = runCameraOperation<ByteArray?>(Mutex(), { true }) { null }
        assertTrue(result is CameraOperation.Completed)
        assertNull((result as CameraOperation.Completed).value)
    }

    @Test fun `cancelling queued work never sends its command`() = runBlocking {
        val mutex = Mutex(locked = true)
        var requests = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCameraOperation(mutex, { true }) { requests++ }
        }
        pending.cancelAndJoin()
        mutex.unlock()
        assertEquals(0, requests)
        assertTrue(pending.isCancelled)
    }

    @Test fun `cancelled work cannot send even when the USB mutex is free`() = runBlocking {
        val mutex = Mutex()
        var requests = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            // A gesture can end after its loop checks isActive but before it
            // reaches the gate. Acquiring this free mutex will not suspend.
            currentCoroutineContext().cancel()
            runCameraOperation(mutex, { true }) { requests++ }
        }

        val failure = runCatching { pending.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(pending.isCancelled)
        assertEquals(0, requests)
        assertFalse(mutex.isLocked)
    }
}
