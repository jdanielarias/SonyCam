package com.sonycam.monitor.ui

import kotlin.math.abs
import kotlin.math.sign

/**
 * Follow new travel in the chosen direction. Tiny retreats and their return
 * to the previous extreme create no work, so finger jitter cannot reverse the
 * lens or ratchet it forward while the finger is almost stationary.
 * A deliberate 4dp reversal takes effect at the current position, without
 * requiring the finger to cross its original touch point.
 */
internal class FocusGestureFilter {
    private var position = 0f
    private var extreme = 0f
    private var direction = 0f

    fun move(deltaDp: Float): Float {
        if (!deltaDp.isFinite() || deltaDp == 0f) return 0f
        position += deltaDp
        val travel = position - extreme
        if (direction == 0f) {
            if (abs(travel) < 1f) return 0f
        } else if (travel * direction < 0f && abs(travel) < 4f) {
            return 0f
        }
        if (travel == 0f) return 0f
        direction = travel.sign
        extreme = position
        return travel
    }
}
