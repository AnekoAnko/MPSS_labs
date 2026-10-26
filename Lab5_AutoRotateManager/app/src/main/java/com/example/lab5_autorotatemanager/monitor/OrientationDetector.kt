package com.example.lab5_autorotatemanager.monitor

import android.content.pm.ActivityInfo
import kotlin.math.abs

/**
 * Device orientations. screenOrientation is the value for requestedOrientation
 * (null for FLAT: orientation is left unchanged).
 */
enum class DeviceOrientation(val label: String, val screenOrientation: Int?) {
    PORTRAIT("Портрет", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE_LEFT("Альбом (ліворуч)", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    LANDSCAPE_RIGHT("Альбом (праворуч)", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
    UPSIDE_DOWN("Перевернутий портрет", ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT),
    FLAT("Лежить горизонтально", null)
}

/** Orientation thresholds. */
object Thresholds {
    /** Minimum gravity projection on X or Y (m/s²) to switch orientation (~45°). */
    const val TILT = 7.0f
    /** |Z| above this means the phone lies flat (tilt < ~30°). */
    const val FLAT = 8.5f
    /** How long a new orientation must hold before rotating (hysteresis). */
    const val HOLD_TIME_MS = 400L
}

/** Detects orientation from filtered accelerometer data with a hold time. */
class OrientationDetector(private val holdTimeMs: Long = Thresholds.HOLD_TIME_MS) {

    var current: DeviceOrientation = DeviceOrientation.PORTRAIT
        private set

    // Candidate orientation and since when it holds
    private var pending: DeviceOrientation? = null
    private var pendingSince = 0L

    /** Returns the new orientation once confirmed, otherwise null. */
    fun update(x: Float, y: Float, z: Float, nowMs: Long): DeviceOrientation? {
        val candidate = classify(x, y, z)

        // In-between zone or same state: no change
        if (candidate == null || candidate == current) {
            pending = null
            return null
        }

        // New state must hold for holdTimeMs
        if (candidate != pending) {
            pending = candidate
            pendingSince = nowMs
        } else if (nowMs - pendingSince >= holdTimeMs) {
            current = candidate
            pending = null
            return candidate
        }
        return null
    }

    companion object {
        /** Orientation by the axis gravity points along; null in between. */
        fun classify(x: Float, y: Float, z: Float): DeviceOrientation? = when {
            abs(z) > Thresholds.FLAT -> DeviceOrientation.FLAT
            abs(y) >= abs(x) -> when {
                y > Thresholds.TILT -> DeviceOrientation.PORTRAIT
                y < -Thresholds.TILT -> DeviceOrientation.UPSIDE_DOWN
                else -> null
            }
            else -> when {
                x > Thresholds.TILT -> DeviceOrientation.LANDSCAPE_LEFT
                x < -Thresholds.TILT -> DeviceOrientation.LANDSCAPE_RIGHT
                else -> null
            }
        }
    }
}
