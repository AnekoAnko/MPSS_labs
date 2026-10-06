package com.example.lab5_autorotatemanager.monitor

import android.content.pm.ActivityInfo
import kotlin.math.abs

/**
 * Можливі стани орієнтації пристрою.
 * screenOrientation — значення для Activity.requestedOrientation
 * (null для FLAT: коли телефон лежить, орієнтацію не змінюємо).
 */
enum class DeviceOrientation(val label: String, val screenOrientation: Int?) {
    PORTRAIT("Портрет", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE_LEFT("Альбом (ліворуч)", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    LANDSCAPE_RIGHT("Альбом (праворуч)", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
    UPSIDE_DOWN("Перевернутий портрет", ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT),
    FLAT("Лежить горизонтально", null)
}

/** Порогові значення (Threshold) системи Auto-Rotate Manager (ЛР №1). */
object Thresholds {
    /** Мінімальна проєкція g на вісь X або Y (м/с²), щоб зафіксувати орієнтацію (~45°). */
    const val TILT = 7.0f
    /** Якщо |Z| більше цього значення — телефон лежить на столі (нахил < ~30°). */
    const val FLAT = 8.5f
    /** Скільки мс новий стан має утримуватися, перш ніж ми повернемо екран (гістерезис). */
    const val HOLD_TIME_MS = 400L
}

/**
 * ЛР №1: визначення орієнтації за відфільтрованими даними акселерометра з гістерезисом.
 * Клас не залежить від Android API (крім констант) — його легко перевірити тестом.
 */
class OrientationDetector(private val holdTimeMs: Long = Thresholds.HOLD_TIME_MS) {

    var current: DeviceOrientation = DeviceOrientation.PORTRAIT
        private set

    // Кандидат на нову орієнтацію і час, з якого він тримається
    private var pending: DeviceOrientation? = null
    private var pendingSince = 0L

    /** Повертає нову орієнтацію, якщо вона щойно підтвердилася, інакше null. */
    fun update(x: Float, y: Float, z: Float, nowMs: Long): DeviceOrientation? {
        val candidate = classify(x, y, z)

        // Проміжна зона (нахил ~45°) або той самий стан — нічого не змінюємо
        if (candidate == null || candidate == current) {
            pending = null
            return null
        }

        // Гістерезис: новий стан має протриматися holdTimeMs
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
        /** Орієнтація за тим, на яку вісь «лягає» гравітація; null — проміжне положення. */
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
