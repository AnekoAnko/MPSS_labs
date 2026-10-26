package com.example.lab7_autorotatemanager.ai

/**
 * Confirms a class only after [confirmations] consecutive predictions with confidence ≥ [minConfidence].
 * Filters out single noisy predictions, e.g. at the walking/running boundary.
 */
class PredictionSmoother(
    val minConfidence: Float = MIN_CONFIDENCE,
    val confirmations: Int = CONFIRMATIONS
) {
    private var candidate: ActivityClass? = null
    private var streak = 0

    /** Confirmed class; STILL at start. */
    var stable: ActivityClass = ActivityClass.STILL
        private set

    /** Returns the new confirmed class if it just changed, otherwise null. */
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
