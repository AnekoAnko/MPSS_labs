package com.example.lab7_autorotatemanager.helpers

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.lab7_autorotatemanager.ai.ActivityClass
import com.example.lab7_autorotatemanager.ai.Prediction
import com.example.lab7_autorotatemanager.ai.SensorWindowBuffer
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Помічник ML: ViewModel не знає, що всередині TensorFlow Lite (зручно для тестів). */
interface IActivityClassifier : AutoCloseable {
    val info: ModelInfo
    fun classify(sensorWindow: FloatArray): Prediction
}

/** Опис моделі для UI і звіту: вхідні/вихідні тензори. */
data class ModelInfo(
    val fileName: String,
    val sizeBytes: Long,
    val inputShape: List<Int>,
    val inputType: String,
    val outputShape: List<Int>,
    val outputType: String,
    val labels: List<String>
) {
    fun describe(): String =
        "in ${inputShape} $inputType → out ${outputShape} $outputType"
}

/**
 * ЛР №6: ML-Wrapper — завантажує модель activity_classifier.tflite з assets у пам'ять
 * і виконує inference (прогноз) локально, без мережі (On-device AI / Edge AI).
 *
 *   Вхід:  тензор [1, 20, 3] float32 — вікно 20 вимірів (x, y, z), підготовлене SensorWindowBuffer
 *   Вихід: тензор [1, 4]     float32 — ймовірності класів STILL, WALKING, RUNNING, ANOMALY (softmax)
 *
 * Interpreter не потокобезпечний, тому classify() синхронізовано; викликати його слід
 * з фонового потоку (Dispatchers.Default), а не з головного.
 */
class ActivityClassifier(context: Context) : IActivityClassifier {

    private val model: ByteBuffer = loadModelFile(context, MODEL_FILE)
    private var interpreter: Interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })

    // Буфери створюються один раз і перевикористовуються (без алокацій на кожен прогноз)
    private val inputBuffer: ByteBuffer
    private val output: Array<FloatArray>

    override val info: ModelInfo

    init {
        val inTensor = interpreter.getInputTensor(0)
        val outTensor = interpreter.getOutputTensor(0)
        inputBuffer = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder())
        output = Array(outTensor.shape()[0]) { FloatArray(outTensor.shape()[1]) }

        val labels = context.assets.open(LABELS_FILE).bufferedReader().readLines().filter { it.isNotBlank() }
        info = ModelInfo(
            fileName = MODEL_FILE,
            sizeBytes = model.capacity().toLong(),
            inputShape = inTensor.shape().toList(),
            inputType = inTensor.dataType().toString(),
            outputShape = outTensor.shape().toList(),
            outputType = outTensor.dataType().toString(),
            labels = labels
        )
        require(labels.size == ActivityClass.entries.size) { "Кількість міток ≠ кількість класів" }
        require(inTensor.numElements() == SensorWindowBuffer.WINDOW_SIZE * SensorWindowBuffer.AXES) {
            "Модель очікує ${inTensor.numElements()} значень, а буфер дає ${SensorWindowBuffer.WINDOW_SIZE * SensorWindowBuffer.AXES}"
        }
        Log.i(TAG, "Модель завантажено: ${info.fileName}, ${info.sizeBytes} Б, ${info.describe()}, класи ${info.labels}")
    }

    /** Передає вікно в модель і повертає масив ймовірностей для кожного класу. */
    @Synchronized
    override fun classify(sensorWindow: FloatArray): Prediction {
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(sensorWindow)

        val start = SystemClock.elapsedRealtimeNanos()
        interpreter.run(inputBuffer, output)
        val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f

        return Prediction(output[0].copyOf(), ms, System.currentTimeMillis())
    }

    @Synchronized
    override fun close() = interpreter.close()

    companion object {
        const val TAG = "AI_Insights"
        const val MODEL_FILE = "activity_classifier.tflite"
        const val LABELS_FILE = "activity_labels.txt"

        /**
         * Відображає файл моделі з assets у пам'ять (memory-mapped), без копіювання в heap.
         * Працює, лише якщо .tflite не стиснуто в APK (див. androidResources.noCompress у build.gradle.kts);
         * інакше — запасний варіант: читаємо байти в direct ByteBuffer.
         */
        fun loadModelFile(context: Context, fileName: String): ByteBuffer = try {
            context.assets.openFd(fileName).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            Log.w(TAG, "openFd неможливий (файл стиснуто?) — читаю модель у пам'ять: ${e.message}")
            val bytes = context.assets.open(fileName).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }
}
