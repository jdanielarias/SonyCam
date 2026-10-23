package com.sonycam.monitor.ui

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionResourcesTest {
    private class Resource {
        var opened = false
        var handshaken = false
        var closeCalls = 0

        fun close() {
            closeCalls++
            opened = false
        }
    }

    /** Allows cancellation exactly between IO completion and caller resumption. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runNext() {
            check(queue.isNotEmpty()) { "No queued continuation" }
            queue.removeFirst().run()
        }
    }

    @Test
    fun `successful initialization transfers the open resource to the session`() = runBlocking {
        val resource = Resource()
        var published: Resource? = null
        val result = initializeConnection(
            resources = resource,
            initialize = { it.opened = true; "camera info" },
            publish = { value, info ->
                assertEquals("camera info", info)
                published = value
            },
            close = Resource::close,
        )

        assertEquals("camera info", result)
        assertSame(resource, published)
        assertTrue(resource.opened)
        assertEquals(0, resource.closeCalls)
        // The receiving session now owns shutdown.
        published!!.close()
        assertEquals(1, resource.closeCalls)
    }

    @Test
    fun `a failure after claiming USB closes the partially opened resource`() = runBlocking {
        val resource = Resource()
        var published = false
        val failure = IllegalStateException("endpoint drain failed")
        val result = runCatching {
            initializeConnection(
                resources = resource,
                initialize = {
                    it.opened = true
                    throw failure
                },
                publish = { _, _ -> published = true },
                close = Resource::close,
            )
        }

        val observed = result.exceptionOrNull()
        // Coroutine stack-trace recovery may copy the exception when it
        // crosses dispatchers, retaining the original as its cause.
        assertEquals(failure.javaClass, observed?.javaClass)
        assertEquals(failure.message, observed?.message)
        assertTrue(generateSequence(observed) { it.cause }.any { it === failure })
        assertFalse(published)
        assertFalse(resource.opened)
        assertEquals(1, resource.closeCalls)
    }

    @Test
    fun `cancellation while open blocks closes its late result without handshaking`() = runBlocking {
        val resource = Resource()
        val enteredOpen = CountDownLatch(1)
        val finishOpen = CountDownLatch(1)
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            initializeConnection(
                resources = resource,
                initialize = {
                    // USB open is a blocking call: cancellation cannot prevent
                    // it from acquiring a connection just before it returns.
                    enteredOpen.countDown()
                    check(finishOpen.await(5, TimeUnit.SECONDS))
                    it.opened = true
                    currentCoroutineContext().ensureActive()
                    it.handshaken = true
                },
                publish = { _, _ -> published = true },
                close = Resource::close,
                dispatcher = Dispatchers.IO,
            )
        }

        try {
            assertTrue(enteredOpen.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertEquals(0, resource.closeCalls)
        } finally {
            finishOpen.countDown()
            job.join()
        }

        assertFalse(published)
        assertFalse(resource.handshaken)
        assertFalse(resource.opened)
        assertEquals(1, resource.closeCalls)
    }

    @Test
    fun `cancellation after IO completes still closes before job completion`() {
        val caller = QueuedDispatcher()
        val io = QueuedDispatcher()
        val scopeJob = SupervisorJob()
        val resource = Resource()
        var published = false
        val job = CoroutineScope(scopeJob + caller).launch {
            initializeConnection(
                resources = resource,
                initialize = {
                    it.opened = true
                    it.handshaken = true
                },
                publish = { _, _ -> published = true },
                close = Resource::close,
                dispatcher = io,
            )
        }

        caller.runNext() // launch schedules initialization on IO
        io.runNext() // initialized successfully; delivery to caller is pending
        assertTrue(resource.opened)
        job.cancel()
        caller.runNext() // prompt cancellation runs finally, schedules close
        assertFalse(job.isCompleted)
        io.runNext() // NonCancellable cleanup owns the initialized resource
        caller.runNext()

        assertTrue(job.isCompleted)
        assertFalse(published)
        assertFalse(resource.opened)
        assertEquals(1, resource.closeCalls)
        scopeJob.cancel()
    }

    @Test
    fun `cancellation before IO starts disposes unpublished resources`() {
        val caller = QueuedDispatcher()
        val io = QueuedDispatcher()
        val scopeJob = SupervisorJob()
        val resource = Resource()
        var initialized = false
        var published = false
        val job = CoroutineScope(scopeJob + caller).launch {
            initializeConnection(
                resources = resource,
                initialize = { initialized = true },
                publish = { _, _ -> published = true },
                close = Resource::close,
                dispatcher = io,
            )
        }

        caller.runNext()
        job.cancel()
        io.runNext() // cancellation skips initialization
        caller.runNext() // finally schedules disposal on IO
        io.runNext()
        caller.runNext()

        assertTrue(job.isCompleted)
        assertFalse(initialized)
        assertFalse(published)
        assertEquals(1, resource.closeCalls)
        scopeJob.cancel()
    }
}
