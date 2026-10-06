package com.example.lab6_autorotatemanager.ml

/**
 * ЛР №6: матриця станів — класи, які розрізняє модель activity_classifier.tflite.
 * Порядок елементів = порядок виходів моделі (індекс у тензорі [1, 4]) = рядки activity_labels.txt.
 */
enum class ActivityClass(val label: String, val english: String) {
    STILL("Спокій", "Still"),
    WALKING("Ходьба", "Walking"),
    RUNNING("Біг", "Running"),
    ANOMALY("Аномалія", "Anomaly");

    companion object {
        fun fromIndex(index: Int): ActivityClass = entries[index]
    }
}

/** Результат одного inference: ймовірності всіх класів + службова інформація. */
data class Prediction(
    /** Ймовірності класів (softmax), сума ≈ 1. Індекс = ActivityClass.ordinal. */
    val probabilities: FloatArray,
    /** Тривалість interpreter.run(), мс. */
    val inferenceMs: Float,
    /** Час формування вікна (мс від 01.01.1970). */
    val timestamp: Long
) {
    val best: ActivityClass get() = ActivityClass.fromIndex(probabilities.indices.maxByOrNull { probabilities[it] } ?: 0)
    val confidence: Float get() = probabilities.maxOrNull() ?: 0f

    /** «Current Activity: Walking (92%)». */
    fun format(): String = "${best.english} (${"%.0f".format(confidence * 100)}%)"

    override fun equals(other: Any?): Boolean =
        other is Prediction && probabilities.contentEquals(other.probabilities) &&
            inferenceMs == other.inferenceMs && timestamp == other.timestamp

    override fun hashCode(): Int = probabilities.contentHashCode() * 31 + timestamp.hashCode()
}
