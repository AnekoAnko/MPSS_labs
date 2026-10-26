package com.example.lab7_autorotatemanager.ai

import com.example.lab7_autorotatemanager.helpers.ModelInfo

/** Classes predicted by the model. Order matches the model output and activity_labels.txt. */
enum class ActivityClass(val label: String, val english: String) {
    STILL("Спокій", "Still"),
    WALKING("Ходьба", "Walking"),
    RUNNING("Біг", "Running"),
    ANOMALY("Аномалія", "Anomaly");

    companion object {
        fun fromIndex(index: Int): ActivityClass = entries[index]
    }
}

/** Result of one inference: class probabilities and metadata. */
data class Prediction(
    /** Softmax probabilities (sum ≈ 1), indexed by ActivityClass.ordinal. */
    val probabilities: FloatArray,
    /** Duration of interpreter.run(), ms. */
    val inferenceMs: Float,
    /** Window timestamp, epoch ms. */
    val timestamp: Long
) {
    val best: ActivityClass get() = ActivityClass.fromIndex(probabilities.indices.maxByOrNull { probabilities[it] } ?: 0)
    val confidence: Float get() = probabilities.maxOrNull() ?: 0f

    /** e.g. "Walking (92%)". */
    fun format(): String = "${best.english} (${"%.0f".format(confidence * 100)}%)"

    override fun equals(other: Any?): Boolean =
        other is Prediction && probabilities.contentEquals(other.probabilities) &&
            inferenceMs == other.inferenceMs && timestamp == other.timestamp

    override fun hashCode(): Int = probabilities.contentHashCode() * 31 + timestamp.hashCode()
}

// State: the AI Insights card.
data class AiState(
    val enabled: Boolean = true,
    val modelInfo: ModelInfo? = null,
    val modelError: String? = null,
    val bufferFill: Int = 0,
    val bufferSize: Int = SensorWindowBuffer.WINDOW_SIZE,
    /** Latest raw prediction (~4 per second). */
    val prediction: Prediction? = null,
    /** Confirmed class after smoothing; this one drives actions. */
    val stable: ActivityClass = ActivityClass.STILL,
    val inferenceCount: Int = 0,
    val avgInferenceMs: Float = 0f,
    val skippedWindows: Int = 0,
    val workerThread: String? = null,
    val history: List<String> = emptyList()
)

// Action: user intents.
sealed interface AiAction {
    data class SetEnabled(val enabled: Boolean) : AiAction
}

// Event: one-off messages.
sealed interface AiEvent {
    data class ShowMessage(val text: String) : AiEvent
}
