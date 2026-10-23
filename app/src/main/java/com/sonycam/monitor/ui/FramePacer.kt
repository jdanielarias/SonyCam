package com.sonycam.monitor.ui

import kotlin.math.roundToLong

/**
 * Evens out when live-view frames reach the screen.
 *
 * While it records 4K an A7 III serves live view at ~15 fps, and not evenly:
 * measured gaps run from 55 to 170 ms. Shown the moment they arrive, those
 * frames make any camera movement look like trembling, because the eye reads
 * the uneven steps as shake. The frames themselves are fine; their timing is
 * not.
 *
 * So each frame gets a presentation time one average interval after the
 * previous one, with two bounds that keep this honest:
 * - never before the frame has arrived (no invented timing), and
 * - never more than [maxDelayIntervals] intervals after it, so the delay this
 *   adds stays bounded even if the average is momentarily wrong.
 *
 * The average is an exponential moving average of arrival gaps, clamped to a
 * plausible range so that one stall cannot poison it.
 */
internal class FramePacer(
    private val minIntervalMs: Long = 25,
    private val maxIntervalMs: Long = 150,
    private val maxDelayIntervals: Double = 1.5,
    private val smoothing: Double = 0.1,
) {
    private var lastArrivalMs = NONE
    private var lastPresentMs = NONE
    private var intervalMs = 0.0
    private var gapsSeen = 0

    /** The current estimate of the camera's frame interval, 0 until known. */
    val averageIntervalMs: Double get() = intervalMs

    /** Presentation time for a frame that arrived at [arrivalMs]. */
    fun schedule(arrivalMs: Long): Long {
        if (lastArrivalMs != NONE) {
            val gap = (arrivalMs - lastArrivalMs).coerceIn(minIntervalMs, maxIntervalMs).toDouble()
            // A plain running mean over the first gaps, then the slower moving
            // average. With the moving average alone the first 2–4 s of a
            // recording — while the camera drops from 30 to 15 fps — still
            // showed uneven 20–144 ms steps (measured).
            gapsSeen++
            val weight = maxOf(smoothing, 1.0 / gapsSeen)
            intervalMs += weight * (gap - intervalMs)
        }
        lastArrivalMs = arrivalMs
        val present = if (lastPresentMs == NONE || intervalMs == 0.0) {
            arrivalMs
        } else {
            val even = lastPresentMs + intervalMs.roundToLong()
            val latest = arrivalMs + (intervalMs * maxDelayIntervals).roundToLong()
            even.coerceIn(arrivalMs, latest)
        }
        lastPresentMs = present
        return present
    }

    /** Forget the stream, e.g. when pacing stops and frames go straight through. */
    fun reset() {
        lastArrivalMs = NONE
        lastPresentMs = NONE
        intervalMs = 0.0
        gapsSeen = 0
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
