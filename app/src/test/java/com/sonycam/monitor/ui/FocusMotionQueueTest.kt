package com.sonycam.monitor.ui

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FocusMotionQueueTest {
    private fun drain(queue: FocusMotionQueue): List<Int> = buildList {
        while (queue.hasSteps()) {
            check(size < 100)
            add(queue.takeSteps())
        }
    }

    @Test fun `normal starts with one intermediate adjustment then waits for more travel`() {
        val normal = FocusMotionQueue()
        normal.add(0.5f)
        assertEquals(0, normal.takeSteps())
        normal.add(0.5f)
        assertEquals(2, normal.takeSteps())
        normal.add(1f)
        assertEquals(0, normal.takeSteps())
        normal.add(1f)
        assertEquals(2, normal.takeSteps())
    }

    @Test fun `batching the same stroke does not change its camera commands`() {
        for (sign in listOf(-1f, 1f)) {
            val batched = FocusMotionQueue().apply { add(5f * sign) }
            val individual = FocusMotionQueue()
            val commands = buildList {
                repeat(10) { individual.add(0.5f * sign); addAll(drain(individual)) }
            }
            assertEquals(commands, drain(batched))
            assertEquals(listOf(2, 2, 2).map { it * sign.toInt() }, commands)
        }
    }

    @Test fun `fractional travel survives sends and USB batching`() {
        val grouped = FocusMotionQueue()
        grouped.add(3.5f)
        assertEquals(listOf(2, 2), drain(grouped))
        grouped.add(1f)
        assertFalse(grouped.hasSteps())
        grouped.add(0.5f)
        assertEquals(listOf(2), drain(grouped))
    }

    @Test fun `fine uses wider detents with the smallest magnitude`() {
        val fine = FocusMotionQueue(fine = true)
        fine.add(13f)
        assertEquals(listOf(1, 1, 1), drain(fine))
        fine.add(-13f)
        assertEquals(listOf(-1, -1, -1), drain(fine))
    }

    @Test fun `a stationary finger cannot repeat an already consumed adjustment`() {
        val motion = FocusMotionQueue().apply { add(1f) }
        assertEquals(2, motion.takeSteps())
        repeat(100) { assertEquals(0, motion.takeSteps()) }
        assertFalse(motion.hasSteps())
    }

    @Test fun `extreme flicks have at most three commands pending`() {
        val motion = FocusMotionQueue()
        repeat(100) { motion.add(1000f) }
        assertEquals(listOf(4, 4, 4), drain(motion))
        repeat(100) { motion.add(1000f) }
        assertEquals(listOf(4, 4, 4), drain(motion))
        assertEquals(0, motion.takeSteps())
    }

    @Test fun `fine never accelerates under a large burst`() {
        val fine = FocusMotionQueue(fine = true).apply { add(-1000f) }
        assertEquals(listOf(-1, -1, -1), drain(fine))
    }

    @Test fun `release clears all whole and fractional travel`() {
        val motion = FocusMotionQueue().apply { add(12.5f) }
        motion.clear()
        assertEquals(emptyList<Int>(), drain(motion))
        motion.add(-1f)
        assertEquals(listOf(-2), drain(motion))
    }

    @Test fun `reversal discards all unsent commands in the old direction`() {
        val motion = FocusMotionQueue().apply { add(100f) }
        motion.add(-1f)
        assertEquals(listOf(-2), drain(motion))
        motion.add(-2f)
        assertEquals(listOf(-2), drain(motion))
    }

    @Test fun `reversal after an exact detent gets its first adjustment promptly`() {
        val motion = FocusMotionQueue().apply { add(3f) }
        assertEquals(listOf(2, 2), drain(motion))
        motion.add(-1f)
        assertEquals(listOf(-2), drain(motion))
    }

    @Test fun `each gesture owns its pending travel`() {
        val old = FocusMotionQueue().apply { add(13f); clear() }
        val next = FocusMotionQueue().apply { add(-3f) }
        assertEquals(emptyList<Int>(), drain(old))
        assertEquals(listOf(-2, -2), drain(next))
    }

    @Test fun `first movement wakes the worker without a periodic timer`() = runBlocking {
        val motion = FocusMotionQueue()
        val first = async(start = CoroutineStart.UNDISPATCHED) { motion.awaitMotion(); motion.takeSteps() }
        motion.add(0.5f)
        assertFalse(first.isCompleted)
        motion.add(0.5f)
        assertEquals(2, withTimeout(1000) { first.await() })
        val idle = async(start = CoroutineStart.UNDISPATCHED) { motion.awaitMotion() }
        assertFalse(idle.isCompleted)
        idle.cancel()
    }

    @Test fun `remaining detents wake the worker even without a new touch event`() = runBlocking {
        val motion = FocusMotionQueue().apply { add(5f) }
        repeat(3) {
            withTimeout(1000) { motion.awaitMotion() }
            assertEquals(2, motion.takeSteps())
        }
        val idle = async(start = CoroutineStart.UNDISPATCHED) { motion.awaitMotion() }
        assertFalse(idle.isCompleted)
        idle.cancel()
    }

    @Test fun `movement during USB work wakes the next adjustment`() = runBlocking {
        val motion = FocusMotionQueue().apply { add(1f) }
        motion.awaitMotion()
        assertEquals(2, motion.takeSteps())
        motion.add(2f)
        withTimeout(1000) { motion.awaitMotion() }
        assertEquals(2, motion.takeSteps())
    }

    @Test fun `invalid samples cannot poison the queue`() {
        val motion = FocusMotionQueue()
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f).forEach(motion::add)
        assertFalse(motion.hasSteps())
        motion.add(1f)
        assertEquals(2, motion.takeSteps())
    }

    @Test fun `normal progressively accelerates a deliberate stroke while fine stays small`() {
        val normal = FocusMotionQueue()
        val fine = FocusMotionQueue(fine = true)
        val normalCommands = mutableListOf<Int>()
        val fineCommands = mutableListOf<Int>()
        repeat(18) {
            normal.add(1f); normalCommands += drain(normal)
            fine.add(1f); fineCommands += drain(fine)
        }
        assertEquals(List(6) { 2 } + List(3) { 3 }, normalCommands)
        assertEquals(List(3) { 1 }, fineCommands)
    }

    @Test fun `wide strokes cross the same speed bands with grouped or individual events`() {
        for (sign in listOf(-1f, 1f)) {
            fun stroke(batchSize: Int): List<Int> {
                val motion = FocusMotionQueue()
                return buildList {
                    repeat(60 / batchSize) {
                        motion.add(batchSize * sign)
                        addAll(drain(motion))
                    }
                }
            }
            val expected = (List(6) { 2 } + List(12) { 3 } + List(12) { 4 })
                .map { it * sign.toInt() }
            assertEquals(expected, stroke(1))
            assertEquals(expected, stroke(5))
        }
    }

    @Test fun `reversing a wide stroke starts gently and drops the previous direction`() {
        val motion = FocusMotionQueue().apply { add(100f) }
        assertEquals(4, motion.takeSteps())
        motion.add(-5f)
        assertEquals(listOf(-2, -2, -2), drain(motion))
    }

    @Test fun `releasing after a wide stroke resets acceleration for the next adjustment`() {
        val motion = FocusMotionQueue().apply { add(100f) }
        assertEquals(4, motion.takeSteps())
        motion.clear()
        assertFalse(motion.hasSteps())
        motion.add(1f)
        assertEquals(2, motion.takeSteps())
    }

    @Test fun `accelerated motion stops when its finite pending work is consumed`() {
        val motion = FocusMotionQueue().apply { add(Float.MAX_VALUE) }
        assertEquals(listOf(4, 4, 4), drain(motion))
        repeat(100) { assertEquals(0, motion.takeSteps()) }
        motion.add(2f)
        assertEquals(listOf(4), drain(motion))
    }
}
