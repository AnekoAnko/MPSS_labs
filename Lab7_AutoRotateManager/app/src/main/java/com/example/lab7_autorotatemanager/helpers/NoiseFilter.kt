package com.example.lab7_autorotatemanager.helpers

/**
 * ЛР №7: рівень шумопоглинання для Smart-сценаріїв.
 * [window] — кількість вимірів у медіанному фільтрі (при 20 Гц: 5 вимірів ≈ 0,25 с).
 */
enum class NoiseLevel(val label: String, val window: Int) {
    OFF("вимкнено", 1),
    LOW("слабке (3)", 3),
    MEDIUM("середнє (5)", 5),
    HIGH("сильне (9)", 9)
}

/**
 * ЛР №7: медіанний фільтр по кожній осі (захист від «хибного спрацювання»).
 *
 * Низькочастотний фільтр ЛР №1 згладжує сигнал, але одиночний викид (удар по столу,
 * «глюк» датчика) все одно зсуває середнє. Медіана вікна з N вимірів повністю ігнорує
 * викиди, які займають менше половини вікна, і майже не спотворює фронти справжнього руху.
 * Клас не залежить від Android — перевіряється звичайним unit-тестом.
 */
class MedianNoiseFilter(window: Int = NoiseLevel.MEDIUM.window) {

    var window: Int = window.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            reset()
        }

    private val xs = ArrayDeque<Float>()
    private val ys = ArrayDeque<Float>()
    private val zs = ArrayDeque<Float>()

    /** Додає вимір і повертає відфільтроване значення (x, y, z). */
    fun apply(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        if (window == 1) return Triple(x, y, z)
        push(xs, x); push(ys, y); push(zs, z)
        return Triple(median(xs), median(ys), median(zs))
    }

    fun reset() {
        xs.clear(); ys.clear(); zs.clear()
    }

    private fun push(q: ArrayDeque<Float>, v: Float) {
        q.addLast(v)
        while (q.size > window) q.removeFirst()
    }

    private fun median(q: ArrayDeque<Float>): Float {
        val sorted = q.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
