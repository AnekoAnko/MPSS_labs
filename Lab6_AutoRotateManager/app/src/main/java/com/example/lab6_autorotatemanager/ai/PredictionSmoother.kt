package com.example.lab6_autorotatemanager.ai

/**
 * ЛР №6: згладжування прогнозів перед тим, як за ними щось вмикати.
 *
 * Окремий прогноз може «смикнутися» — наприклад, на межі Ходьба→Біг у вікні змішано
 * обидва патерни. Тому дії (прискорений запис у БД, актуатори) запускаються лише тоді,
 * коли той самий клас із впевненістю ≥ [minConfidence] прийшов [confirmations] рази поспіль.
 * Це той самий принцип гістерезису, що й у ЛР №1 (400 мс) та ЛР №4 (витримка holdMs).
 */
class PredictionSmoother(
    val minConfidence: Float = MIN_CONFIDENCE,
    val confirmations: Int = CONFIRMATIONS
) {
    private var candidate: ActivityClass? = null
    private var streak = 0

    /** Підтверджений (стабільний) клас; на старті — STILL. */
    var stable: ActivityClass = ActivityClass.STILL
        private set

    /** Повертає новий стабільний клас, якщо він щойно змінився, інакше null. */
    fun update(prediction: Prediction): ActivityClass? {
        val best = prediction.best
        if (prediction.confidence < minConfidence) {
            candidate = null
            streak = 0
            return null
        }
        if (best == candidate) streak++ else {
            candidate = best
            streak = 1
        }
        if (streak >= confirmations && best != stable) {
            stable = best
            return best
        }
        return null
    }

    fun reset() {
        candidate = null
        streak = 0
        stable = ActivityClass.STILL
    }

    companion object {
        const val MIN_CONFIDENCE = 0.6f
        const val CONFIRMATIONS = 2
    }
}
