package com.example.lab7_autorotatemanager

import com.example.lab7_autorotatemanager.helpers.MedianNoiseFilter
import com.example.lab7_autorotatemanager.repository.ActuatorType
import com.example.lab7_autorotatemanager.repository.Comparison
import com.example.lab7_autorotatemanager.repository.Metric
import com.example.lab7_autorotatemanager.repository.ScenarioRule
import com.example.lab7_autorotatemanager.scenario.ScenarioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР №7: Smart-сценарії і захист від хибних спрацювань (JVM, без Android). */
class ScenarioEngineTest {

    private val rule = ScenarioRule(
        id = 1, metric = Metric.X, comparison = Comparison.GREATER, threshold = 10f,
        action = ActuatorType.VIBRATION, holdMs = 300
    )
    private val step = 50L   // 20 Гц

    @Test
    fun steadyValueAboveThresholdFiresAfterHold() {
        val engine = ScenarioEngine()
        var firedAt = -1L
        for (i in 0..20) {
            val t = i * step
            val out = engine.evaluate(listOf(rule), 11f, 9.8f, 1f, t)
            if (out.any { it.active } && firedAt < 0) firedAt = t
        }
        assertEquals(300L, firedAt)   // рівно через holdMs
    }

    @Test
    fun singleSpikeIsIgnoredWithHold() {
        val engine = ScenarioEngine()
        val fired = (0..100).flatMap { i ->
            val x = if (i % 40 == 20) 15f else 0f   // одиночний викид раз на 2 с
            engine.evaluate(listOf(rule), x, 9.8f, 1f, i * step)
        }.filter { it.active }
        assertTrue(fired.isEmpty())
    }

    @Test
    fun singleSpikeFiresWithoutProtection() {
        val engine = ScenarioEngine()
        val noHold = rule.copy(holdMs = 0)
        val fired = (0..100).flatMap { i ->
            val x = if (i % 40 == 20) 15f else 0f
            engine.evaluate(listOf(noHold), x, 9.8f, 1f, i * step)
        }.filter { it.active }
        assertTrue(fired.isNotEmpty())   // саме це і є «хибне спрацювання»
    }

    @Test
    fun medianFilterRemovesSpike() {
        val filter = MedianNoiseFilter(5)
        val outputs = (0 until 10).map { i -> filter.apply(if (i == 5) 15f else 0f, 9.8f, 1f).first }
        assertTrue(outputs.all { it < 1f })
    }

    @Test
    fun releaseNeedsHysteresisAndDelay() {
        val engine = ScenarioEngine(releaseMs = 1000)
        var t = 0L
        repeat(10) { engine.evaluate(listOf(rule), 11f, 0f, 0f, t); t += step }
        assertTrue(rule.id in engine.activeIds)
        // 9,8 — нижче порога, але в межах гістерезису (10 − 0,5) → правило лишається активним
        repeat(30) { engine.evaluate(listOf(rule), 9.8f, 0f, 0f, t); t += step }
        assertTrue(rule.id in engine.activeIds)
        // значно нижче порога, але менше 1 с → ще активне
        repeat(10) { engine.evaluate(listOf(rule), 2f, 0f, 0f, t); t += step }
        assertTrue(rule.id in engine.activeIds)
        val out = (0 until 15).flatMap { engine.evaluate(listOf(rule), 2f, 0f, 0f, t).also { t += step } }
        assertTrue(out.any { !it.active })
        assertFalse(rule.id in engine.activeIds)
    }

    @Test
    fun disabledRuleIsReleased() {
        val engine = ScenarioEngine()
        var t = 0L
        repeat(10) { engine.evaluate(listOf(rule), 11f, 0f, 0f, t); t += step }
        val out = engine.evaluate(listOf(rule.copy(enabled = false)), 11f, 0f, 0f, t)
        assertEquals(1, out.size)
        assertFalse(out[0].active)
    }
}
