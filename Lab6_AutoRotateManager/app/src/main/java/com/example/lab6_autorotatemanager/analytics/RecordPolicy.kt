package com.example.lab6_autorotatemanager.analytics

import kotlin.math.abs

/** Причина, з якої вимір записано в базу. */
enum class RecordReason(val label: String) {
    FIRST("перший вимір"),
    EVENT("спрацювання актуаторів"),
    CHANGE("суттєва зміна"),
    TIMER("таймер"),
    FAST("біг · прискорений запис")      // ЛР №6
}

/**
 * ЛР №5: політика запису в БД. Акселерометр видає ~15–60 вимірів за секунду;
 * якщо писати кожен, база швидко розростеться, а графік стане «шумом».
 * Тому запис робимо лише якщо:
 *  • це перший вимір;
 *  • спрацювала система безпеки (force = true) — подію не можна пропустити;
 *  • значення змінилося щонайменше на [minDelta] (суттєва зміна);
 *  • або від останнього запису минуло [heartbeatMs] (запис за таймером).
 * Додатково між записами має пройти не менше [minIntervalMs] (захист від «зливи» записів).
 * Клас не залежить від Android — його легко перевірити звичайним тестом.
 */
class RecordPolicy(
    val minDelta: Float = DEFAULT_MIN_DELTA,
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS
) {
    private var lastValue: Float? = null
    private var lastTime = 0L

    /**
     * ЛР №6: прискорений режим. Вмикається, коли ML-модель розпізнала «Біг»:
     * рух швидкий, тому пишемо частіше — мінімальний інтервал [FAST_INTERVAL_MS]
     * без перевірки minDelta (≈ 5 записів/с замість «раз на 5 с у спокої»).
     */
    var fastMode = false

    /** Повертає причину запису або null, якщо вимір записувати не треба. */
    fun check(value: Float, nowMs: Long, force: Boolean = false): RecordReason? {
        val previous = lastValue
        val reason = when {
            previous == null -> RecordReason.FIRST
            force -> RecordReason.EVENT
            fastMode && nowMs - lastTime >= FAST_INTERVAL_MS -> RecordReason.FAST
            nowMs - lastTime < minIntervalMs -> null
            abs(value - previous) >= minDelta -> RecordReason.CHANGE
            nowMs - lastTime >= heartbeatMs -> RecordReason.TIMER
            else -> null
        }
        if (reason != null) {
            lastValue = value
            lastTime = nowMs
        }
        return reason
    }

    fun reset() {
        lastValue = null
        lastTime = 0L
    }

    companion object {
        const val DEFAULT_MIN_DELTA = 3f          // градуси
        const val DEFAULT_MIN_INTERVAL_MS = 250L  // не частіше 4 записів/с
        const val DEFAULT_HEARTBEAT_MS = 5_000L   // у спокої — раз на 5 с
        const val FAST_INTERVAL_MS = 200L         // ЛР №6: під час бігу — 5 записів/с
    }
}
