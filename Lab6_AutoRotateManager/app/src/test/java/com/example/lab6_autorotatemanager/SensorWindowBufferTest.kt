package com.example.lab6_autorotatemanager

import com.example.lab6_autorotatemanager.ml.ActivityClass
import com.example.lab6_autorotatemanager.ml.Prediction
import com.example.lab6_autorotatemanager.ml.PredictionSmoother
import com.example.lab6_autorotatemanager.ml.SensorWindowBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР №6: unit-тести кільцевого буфера і згладжування (запуск: ПКМ по файлу → Run). */
class SensorWindowBufferTest {

    private val periodNs = 50_000_000L   // 20 Гц

    @Test
    fun inferenceEveryHopAfterWindowIsFull() {
        val buffer = SensorWindowBuffer()
        val readyAt = (1..40).filter { i -> buffer.add(0f, 9.8f, 0f, i * periodNs) }
        assertEquals(listOf(20, 25, 30, 35, 40), readyAt)
    }

    @Test
    fun modelInputIsCenteredAndInGUnits() {
        val buffer = SensorWindowBuffer()
        // гравітація по Y + «крок» по Z: +g на парних вимірах, −g на непарних
        repeat(20) { i -> buffer.add(0f, 9.80665f, if (i % 2 == 0) 9.80665f else -9.80665f, i * periodNs) }
        val input = buffer.toModelInput()
        assertEquals(60, input.size)
        for (t in 0 until 20) {
            assertEquals(0f, input[t * 3], 1e-6f)            // X: без руху
            assertEquals(0f, input[t * 3 + 1], 1e-5f)        // Y: гравітацію прибрано
            assertEquals(if (t % 2 == 0) 1f else -1f, input[t * 3 + 2], 1e-5f)   // Z: ±1 g, порядок збережено
        }
    }

    @Test
    fun tooFrequentEventsAreSkipped() {
        val buffer = SensorWindowBuffer()
        var accepted = 0
        repeat(100) { i -> buffer.add(0f, 9.8f, 0f, i * 10_000_000L); accepted = buffer.size }
        assertTrue("100 Гц за 1 с має дати ~20 вимірів, а не $accepted", accepted in 19..20)
        assertFalse(SensorWindowBuffer().isFull)
    }

    @Test
    fun smootherNeedsTwoConfidentPredictions() {
        val smoother = PredictionSmoother()
        fun p(c: Int, conf: Float) = Prediction(FloatArray(4) { if (it == c) conf else (1 - conf) / 3 }, 0.3f, 0)
        assertNull(smoother.update(p(2, 0.9f)))
        assertEquals(ActivityClass.RUNNING, smoother.update(p(2, 0.9f)))
        assertNull(smoother.update(p(3, 0.4f)))               // невпевнений прогноз ігнорується
        assertEquals("Running (90%)", p(2, 0.9f).format())
    }
}
