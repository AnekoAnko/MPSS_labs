package com.example.lab6_autorotatemanager.ai

import com.example.lab6_autorotatemanager.helpers.SensorReader

/**
 * ЛР №6: кільцевий буфер (Circular Buffer) для вікна вимірів акселерометра.
 *
 * Модель приймає не одне число, а «вікно» — останні [windowSize] вимірів (x, y, z),
 * тобто ≈ 1 с руху при частоті 20 Гц. Буфер — масив фіксованого розміру з індексом
 * запису [head]: новий вимір перезаписує найстаріший, тому пам'ять не виділяється
 * на кожну подію сенсора (важливо при 20–60 подіях за секунду).
 *
 *   індекси:  [0][1][2] … [19]
 *                    ↑ head — сюди піде наступний вимір (найстаріший буде перезаписано)
 *
 * Додатково буфер:
 *  • прорiджує потік за часом (не частіше ніж раз на [minPeriodNs]), щоб вікно завжди
 *    покривало ≈ 1 с незалежно від реальної частоти сенсора;
 *  • повідомляє, коли пора запускати inference — кожні [hop] нових вимірів
 *    (вікна перекриваються на 75 %, прогноз оновлюється ~4 рази на секунду).
 *
 * Клас не залежить від Android — його легко перевірити звичайним тестом.
 */
class SensorWindowBuffer(
    val windowSize: Int = WINDOW_SIZE,
    val hop: Int = HOP,
    private val minPeriodNs: Long = MIN_PERIOD_NS
) {
    private val data = FloatArray(windowSize * AXES)   // x0 y0 z0 x1 y1 z1 …
    private var head = 0                                // позиція наступного запису
    private var lastAcceptedNs = Long.MIN_VALUE
    private var sinceLastInference = 0

    /** Скільки вимірів уже є у вікні (0..windowSize). */
    var size = 0
        private set

    val isFull: Boolean get() = size == windowSize

    /**
     * Додає вимір. [timestampNs] — SensorEvent.timestamp (наносекунди).
     * Повертає true, якщо вікно заповнене і настав час для нового inference.
     */
    fun add(x: Float, y: Float, z: Float, timestampNs: Long): Boolean {
        if (lastAcceptedNs != Long.MIN_VALUE && timestampNs - lastAcceptedNs < minPeriodNs) return false
        lastAcceptedNs = timestampNs

        val i = head * AXES
        data[i] = x
        data[i + 1] = y
        data[i + 2] = z
        head = (head + 1) % windowSize
        if (size < windowSize) size++

        sinceLastInference++
        if (isFull && sinceLastInference >= hop) {
            sinceLastInference = 0
            return true
        }
        return false
    }

    /**
     * Перетворення сирих даних у вхідний формат моделі — тензор [1, windowSize, 3] float32,
     * поданий як плаский масив у порядку «від найстарішого до найновішого виміру»:
     *   1) розгортаємо кільце (найстаріший вимір стоїть на позиції head);
     *   2) з кожної осі віднімаємо середнє по вікну → прибираємо гравітацію і позу телефону,
     *      лишається лише динаміка руху;
     *   3) ділимо на g = 9.80665 → значення в одиницях g (≈ −3…3), як під час навчання.
     * Повертає новий масив (знімок), тому буфер можна далі заповнювати з головного потоку,
     * поки inference працює у фоновому потоці.
     */
    fun toModelInput(): FloatArray {
        check(isFull) { "Вікно ще не заповнене: $size/$windowSize" }
        val out = FloatArray(windowSize * AXES)
        val mean = FloatArray(AXES)
        for (t in 0 until windowSize) {
            val src = ((head + t) % windowSize) * AXES      // t = 0 → найстаріший вимір
            for (a in 0 until AXES) {
                out[t * AXES + a] = data[src + a]
                mean[a] += data[src + a]
            }
        }
        for (a in 0 until AXES) mean[a] /= windowSize
        for (t in 0 until windowSize) {
            for (a in 0 until AXES) {
                out[t * AXES + a] = (out[t * AXES + a] - mean[a]) / GRAVITY
            }
        }
        return out
    }

    fun clear() {
        data.fill(0f)
        head = 0
        size = 0
        sinceLastInference = 0
        lastAcceptedNs = Long.MIN_VALUE
    }

    companion object {
        const val AXES = 3
        /** 20 вимірів ≈ 1 с при 20 Гц — розмір вікна, на якому навчена модель. */
        const val WINDOW_SIZE = 20
        /** Новий прогноз — кожні 5 вимірів (≈ 250 мс). */
        const val HOP = 5
        /** 20 Гц = період 50 мс; беремо 45 мс із запасом на джитер подій. */
        const val MIN_PERIOD_NS = 45_000_000L
        /** Період опитування сенсора для registerListener, мкс (20 Гц). */
        const val SAMPLING_PERIOD_US = SensorReader.PERIOD_20_HZ_US
        const val GRAVITY = 9.80665f
    }
}
