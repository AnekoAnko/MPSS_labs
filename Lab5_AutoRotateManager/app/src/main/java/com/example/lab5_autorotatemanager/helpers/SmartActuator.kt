package com.example.lab5_autorotatemanager.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Абстракція виконавчих пристроїв: ViewModel не знає, реальне це залізо чи імітація. */
interface IActuator {
    /** Є апаратний спалах камери (FEATURE_CAMERA_FLASH + камера з FLASH_INFO_AVAILABLE). */
    val hasFlash: Boolean
    /** Є вібромотор. */
    val hasVibrator: Boolean
    /** Виклик, коли стан ліхтарика змінився поза додатком (наприклад, із шторки). */
    var onTorchChanged: ((Boolean) -> Unit)?

    /** Увімкнути «сирену»: циклічна вібрація + звуковий сигнал. */
    fun triggerAlarm()
    fun stopAlarm()

    /** Керування ліхтариком. true — команду виконано апаратно, false — лише імітація. */
    fun toggleFlashlight(status: Boolean): Boolean

    /** Вимкнути все та звільнити ресурси. */
    fun release()
}

/**
 * Актуатор-сервіс: керує апаратними ресурсами смартфона як промисловими виконавчими пристроями.
 *  • «Сирена»    — VibratorManager (API 31+) / Vibrator (API < 31) + ToneGenerator (потік ALARM);
 *  • «Прожектор» — CameraManager.setTorchMode() (дозвіл CAMERA не потрібен).
 * Перед кожним викликом перевіряється наявність апаратної можливості (Error Handling).
 */
class SmartActuator(context: Context) : IActuator {

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val vibratorManager: VibratorManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)
        } else null
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    private var toneGenerator: ToneGenerator? = null

    /** Ідентифікатор камери зі спалахом (зазвичай задня камера "0"). */
    private val torchCameraId: String? = findTorchCamera()

    override val hasFlash: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) &&
            torchCameraId != null

    override val hasVibrator: Boolean = vibrator?.hasVibrator() == true

    override var onTorchChanged: ((Boolean) -> Unit)? = null

    /** Зворотний зв'язок від системи: реальний стан ліхтарика. */
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) onTorchChanged?.invoke(enabled)
        }
    }

    init {
        if (hasFlash) cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        Log.i(TAG, "Можливості: FEATURE_CAMERA_FLASH=$hasFlash (камера $torchCameraId), вібромотор=$hasVibrator")
    }

    override fun triggerAlarm() {
        // 1) Вібрація: повторюваний патерн «сирени» (repeat = 0 — з початку патерну до stopAlarm)
        if (hasVibrator) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val effect = VibrationEffect.createWaveform(ALARM_PATTERN, 0)
                    vibratorManager?.vibrate(CombinedVibration.createParallel(effect))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(ALARM_PATTERN, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(ALARM_PATTERN, 0)
                }
                Log.i(TAG, "Вібрація: увімкнено (патерн ${ALARM_PATTERN.joinToString("/")} мс)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Немає дозволу VIBRATE: ${e.message}")
            }
        } else {
            Log.w(TAG, "Вібромотор відсутній — вібрацію пропущено")
        }

        // 2) Звук сирени: ToneGenerator не потребує аудіофайлів у ресурсах
        try {
            if (toneGenerator == null) toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, SIREN_SOUND_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator недоступний: ${e.message}")
        }
    }

    override fun stopAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) vibratorManager?.cancel() else vibrator?.cancel()
        toneGenerator?.stopTone()
        Log.i(TAG, "Сирену вимкнено")
    }

    override fun toggleFlashlight(status: Boolean): Boolean {
        val id = torchCameraId
        // Перевірка апаратної можливості перед викликом (в емуляторі спалаху може не бути)
        if (!hasFlash || id == null) {
            Log.w(TAG, "FEATURE_CAMERA_FLASH відсутній — ліхтарик імітується в UI (status=$status)")
            return false
        }
        return try {
            cameraManager?.setTorchMode(id, status)
            Log.i(TAG, "setTorchMode(camera=$id, $status)")
            true
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Камера зайнята або недоступна: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Камера $id не підтримує ліхтарик: ${e.message}")
            false
        }
    }

    override fun release() {
        stopAlarm()
        if (hasFlash) {
            toggleFlashlight(false)
            cameraManager?.unregisterTorchCallback(torchCallback)
        }
        toneGenerator?.release()
        toneGenerator = null
    }

    /** Шукаємо камеру, у якої є спалах (CameraCharacteristics.FLASH_INFO_AVAILABLE). */
    private fun findTorchCamera(): String? = try {
        val cm = cameraManager
        cm?.cameraIdList?.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Не вдалося прочитати список камер: ${e.message}")
        null
    }

    companion object {
        const val TAG = "Actuator"
        /** Пауза 0 мс → вібро 600 мс → пауза 300 мс → вібро 600 мс → пауза 300 мс (по колу). */
        val ALARM_PATTERN = longArrayOf(0, 600, 300, 600, 300)
        const val SIREN_SOUND_MS = 3000
        const val TONE_VOLUME = 80
    }
}
