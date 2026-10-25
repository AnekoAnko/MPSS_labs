package com.example.lab7_autorotatemanager.control

import kotlin.math.acos
import kotlin.math.sqrt

/** Рішення, яке DecisionEngine приймає на кожен вимір сенсора. */
enum class Decision { ACTIVATE, DEACTIVATE, NO_CHANGE }

data class EngineResult(
    /** Кут нахилу пристрою від вертикального (портретного) положення, градуси. */
    val tiltDeg: Float,
    val decision: Decision,
    val alarmActive: Boolean
)

/**
 * Логічний блок прийняття рішень:
 *     IF tilt > threshold THEN activate_actuator (сирена + прожектор)
 *
 * Вхід — відфільтровані значення акселерометра з ЛР №1 (x, y, z, м/с²).
 * Контрольована величина — кут між віссю Y пристрою і вектором гравітації:
 *     tilt = arccos(y / |a|)
 *     0° — телефон стоїть рівно (портрет), 90° — лежить або в альбомі, 180° — догори дном.
 *
 * Захист від «брязкоту» (частого вмикання/вимикання біля порога):
 *  • гістерезис — тривога вимикається лише при tilt < threshold − hysteresis;
 *  • витримка   — умова має виконуватися безперервно holdMs мілісекунд.
 *
 * Клас не залежить від Android, тому його легко тестувати.
 */
class DecisionEngine(
    threshold: Float = DEFAULT_THRESHOLD,
    val hysteresis: Float = DEFAULT_HYSTERESIS,
    val holdMs: Long = DEFAULT_HOLD_MS
) {
    /** Критичне значення (Threshold), градуси. Можна змінити з UI або з хмари. */
    var threshold: Float = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        set(value) {
            field = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        }

    /** Чи активна зараз тривога (актуатори ввімкнено автоматикою). */
    var alarmActive = false
        private set

    /** Момент, з якого безперервно виконується умова зміни стану (null — не виконується). */
    private var conditionSince: Long? = null

    fun evaluate(x: Float, y: Float, z: Float, nowMs: Long): EngineResult {
        val tilt = tiltDegrees(x, y, z)

        // IF sensor_value > threshold (для ввімкнення) / < threshold − hysteresis (для вимкнення)
        val conditionMet = if (!alarmActive) tilt > threshold else tilt < threshold - hysteresis
        if (!conditionMet) {
            conditionSince = null
            return EngineResult(tilt, Decision.NO_CHANGE, alarmActive)
        }

        val since = conditionSince ?: nowMs.also { conditionSince = it }
        if (nowMs - since < holdMs) {
            return EngineResult(tilt, Decision.NO_CHANGE, alarmActive)   // ще чекаємо витримку
        }

        // THEN activate_actuator / deactivate_actuator
        alarmActive = !alarmActive
        conditionSince = null
        val decision = if (alarmActive) Decision.ACTIVATE else Decision.DEACTIVATE
        return EngineResult(tilt, decision, alarmActive)
    }

    /** Скидання стану (наприклад, коли автоматичний режим вимкнено). */
    fun reset() {
        alarmActive = false
        conditionSince = null
    }

    companion object {
        const val DEFAULT_THRESHOLD = 60f
        const val DEFAULT_HYSTERESIS = 10f
        const val DEFAULT_HOLD_MS = 300L
        const val MIN_THRESHOLD = 15f
        const val MAX_THRESHOLD = 170f

        /** Кут нахилу від вертикалі за проєкцією гравітації на вісь Y, градуси (0..180). */
        fun tiltDegrees(x: Float, y: Float, z: Float): Float {
            val norm = sqrt(x * x + y * y + z * z)
            if (norm < 1f) return 0f   // даних ще немає або пристрій у вільному падінні
            val cos = (y / norm).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }
    }
}
