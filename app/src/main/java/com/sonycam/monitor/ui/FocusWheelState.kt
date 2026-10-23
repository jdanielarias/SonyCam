package com.sonycam.monitor.ui

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** One repeating set of five 16dp marks; this is visual travel, not lens position. */
internal const val FOCUS_WHEEL_PERIOD_DP = 80f

/** Keep the drawn wheel separate from the signed drive that must stop on release. */
internal data class FocusWheelState(
    val phaseDp: Float = 0f,
    val driveTravelDp: Float = 0f,
    val speed: Int = 0,
) {
    fun drag(deltaDp: Float, maxSpeed: Int): FocusWheelState {
        if (!deltaDp.isFinite() || deltaDp == 0f || maxSpeed <= 0) return this
        // Reverse immediately, even when the finger is still on the same side
        // of its initial touch. Do not first unwind the preceding movement.
        val travel = if (deltaDp.sign != driveTravelDp.sign) deltaDp else driveTravelDp + deltaDp
        val boundedTravel = travel.coerceIn(-20f * maxSpeed, 20f * maxSpeed)
        val magnitude = (abs(boundedTravel) / 20f).roundToInt().coerceIn(1, maxSpeed)
        val phase = (phaseDp + deltaDp) % FOCUS_WHEEL_PERIOD_DP
        return FocusWheelState(
            phaseDp = if (phase < 0f) phase + FOCUS_WHEEL_PERIOD_DP else phase,
            driveTravelDp = boundedTravel,
            speed = if (deltaDp > 0f) magnitude else -magnitude,
        )
    }

    // Preserve the visible marks when stopping, cancelling or starting a new drag.
    fun stop(): FocusWheelState = copy(driveTravelDp = 0f, speed = 0)
}
