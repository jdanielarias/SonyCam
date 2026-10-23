package com.sonycam.monitor.ui

import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.channels.Channel

/** Finite lens nudges from wheel travel; holding a finger creates no more work. */
internal class FocusMotionQueue(private val fine: Boolean = false) {
    private var pendingDp = 0f
    private var direction = 0f
    private var firstNudge = true
    private var strokeTravelDp = 0f
    private val detentDp = if (fine) 6f else 2f
    private val thresholdDp get() = if (firstNudge) 1f else detentDp
    private val ready = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun add(deltaDp: Float) {
        if (!deltaDp.isFinite() || deltaDp == 0f) return
        // A reversal replaces unsent movement instead of first unwinding it.
        if (direction != deltaDp.sign) {
            pendingDp = 0f
            direction = deltaDp.sign
            firstNudge = true
            strokeTravelDp = 0f
        }
        // Count accepted travel in this direction, not event size or USB timing.
        // Beyond 42dp every retained detent is already in the fastest band.
        strokeTravelDp = (strokeTravelDp + abs(deltaDp)).coerceAtMost(42f)
        // Keep up to three unsent detents. The first adjustment is immediate;
        // further adjustments use the same distance regardless of event batching.
        // Travel beyond this budget is discarded instead of adding a long tail.
        val capacityDp = thresholdDp + 2f * detentDp
        pendingDp = (pendingDp + deltaDp).coerceIn(-capacityDp, capacityDp)
        if (hasSteps()) ready.trySend(Unit)
    }

    /** Wake on real travel rather than polling every 60 ms. One pending wakeup. */
    suspend fun awaitMotion() { ready.receive() }

    @Synchronized
    fun hasSteps(): Boolean = abs(pendingDp) >= thresholdDp

    @Synchronized
    fun takeSteps(): Int {
        if (!hasSteps()) return 0
        val commandDirection = pendingDp.sign.toInt()
        // Evaluate this detent's position, not the newest input position: a
        // batch crossing a band boundary must match individually delivered input.
        val detentPositionDp = strokeTravelDp - abs(pendingDp) + thresholdDp
        val magnitude = when {
            fine -> 1
            detentPositionDp < 12f -> 2
            detentPositionDp < 36f -> 3
            else -> 4
        }
        // Consume one detent, not the whole accumulated stroke. Sony drive
        // magnitudes are not a count of interchangeable lens-position units.
        pendingDp -= commandDirection * thresholdDp
        firstNudge = false
        if (hasSteps()) ready.trySend(Unit)
        return commandDirection * magnitude
    }

    @Synchronized
    fun clear() {
        pendingDp = 0f
        direction = 0f
        firstNudge = true
        strokeTravelDp = 0f
    }
}
