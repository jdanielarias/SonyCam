package com.sonycam.monitor.ui

import android.util.Log
import java.util.Locale

/** Debug measurements of the app pipeline, not sensor-to-screen latency. */
internal class LiveViewPerformance {
    private var startedAt = 0L
    private var lastFrameAt = 0L
    private var count = 0
    private var waitNs = 0L
    private var fetchNs = 0L
    private var decodeNs = 0L
    private var maxGapNs = 0L
    private var misses = 0

    /**
     * Frame-to-frame intervals, bucketed. Motion looks like trembling when the
     * *spread* is wide, even at a steady average: 30 fps delivered as 16/50 ms
     * alternations judders where an even 33 ms does not.
     */
    private val gapBuckets = IntArray(GAP_BOUNDS_MS.size + 1)

    /** Byte-identical JPEGs in a row: the camera handed back the same frame. */
    private var duplicates = 0
    private var lastHash = 0

    fun notReady() { misses++ }

    fun frame(request: Long, fetch: Long, received: Long, ready: Long, bytes: Int, width: Int, height: Int,
              contentHash: Int = 0) {
        if (startedAt == 0L) startedAt = request
        if (lastFrameAt != 0L) {
            val gapNs = ready - lastFrameAt
            maxGapNs = maxOf(maxGapNs, gapNs)
            val gapMs = gapNs / 1_000_000L
            gapBuckets[GAP_BOUNDS_MS.indexOfFirst { gapMs < it }.let { if (it < 0) GAP_BOUNDS_MS.size else it }]++
        }
        if (contentHash != 0 && contentHash == lastHash) duplicates++
        lastHash = contentHash
        lastFrameAt = ready
        waitNs += fetch - request
        fetchNs += received - fetch
        decodeNs += ready - received
        count++
        val elapsed = ready - startedAt
        if (elapsed < 2_000_000_000L) return
        Log.d("LiveViewPerf", String.format(Locale.US,
            "fps=%.1f wait=%.1fms fetch=%.1fms decode=%.1fms maxGap=%.1fms notReady=%d jpeg=%d %dx%d " +
                "gaps[<20,<30,<40,<55,55+]=%s dup=%d",
            count * 1e9 / elapsed, waitNs / count / 1e6, fetchNs / count / 1e6,
            decodeNs / count / 1e6, maxGapNs / 1e6, misses, bytes, width, height,
            gapBuckets.joinToString("/"), duplicates))
        startedAt = ready
        count = 0
        waitNs = 0L
        fetchNs = 0L
        decodeNs = 0L
        maxGapNs = 0L
        misses = 0
        gapBuckets.fill(0)
        duplicates = 0
    }

    private companion object {
        val GAP_BOUNDS_MS = longArrayOf(20, 30, 40, 55)
    }
}

/**
 * The same interval histogram, measured where it matters: when each frame was
 * put on screen. Compared with [LiveViewPerformance]'s arrival gaps it shows
 * what [FramePacer] actually changed. Debug only; one thread (the presenter).
 */
internal class LiveViewPresentationPerformance {
    private var startedAt = 0L
    private var lastShownAt = 0L
    private var count = 0
    private val buckets = IntArray(BOUNDS_MS.size + 1)
    private var minGapNs = Long.MAX_VALUE
    private var maxGapNs = 0L

    fun shown(nowNs: Long, paced: Boolean) {
        if (startedAt == 0L) startedAt = nowNs
        if (lastShownAt != 0L) {
            val gapNs = nowNs - lastShownAt
            minGapNs = minOf(minGapNs, gapNs)
            maxGapNs = maxOf(maxGapNs, gapNs)
            val gapMs = gapNs / 1_000_000L
            buckets[BOUNDS_MS.indexOfFirst { gapMs < it }.let { if (it < 0) BOUNDS_MS.size else it }]++
        }
        lastShownAt = nowNs
        count++
        val elapsed = nowNs - startedAt
        if (elapsed < 2_000_000_000L) return
        Log.d("LiveViewShown", String.format(Locale.US,
            "paced=%b fps=%.1f gap=%.1f..%.1fms gaps[<45,<55,<65,<75,<90,90+]=%s",
            paced, count * 1e9 / elapsed, minGapNs / 1e6, maxGapNs / 1e6, buckets.joinToString("/")))
        startedAt = nowNs
        count = 0
        buckets.fill(0)
        minGapNs = Long.MAX_VALUE
        maxGapNs = 0L
    }

    private companion object {
        val BOUNDS_MS = longArrayOf(45, 55, 65, 75, 90)
    }
}
