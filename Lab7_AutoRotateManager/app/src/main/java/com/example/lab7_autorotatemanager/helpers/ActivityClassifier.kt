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

/** ML abstraction so ViewModels don't depend on TensorFlow Lite (easy to fake in tests). */
interface IActivityClassifier : AutoCloseable {
    val info: ModelInfo
    fun classify(sensorWindow: FloatArray): Prediction
}

/** Model description for the UI: input/output tensors. */
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
 * Loads activity_classifier.tflite from assets and runs inference on the device.
 * Input [1, 20, 3] float32, output [1, 4] class probabilities.
 * Interpreter isn't thread-safe, so classify() is synchronized; call it off the main thread.
 */
class ActivityClassifier(context: Context) : IActivityClassifier {

    private val model: ByteBuffer = loadModelFile(context, MODEL_FILE)
    private var interpreter: Interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })

    // Buffers are allocated once and reused
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

    /** Runs the model on one window and returns class probabilities. */
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
         * Memory-maps the model from assets (requires noCompress for .tflite);
         * falls back to reading the bytes into a direct buffer.
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
