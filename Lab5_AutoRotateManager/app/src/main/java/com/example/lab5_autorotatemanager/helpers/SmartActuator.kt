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

/** Actuator abstraction: ViewModels don't know if hardware is real or simulated. */
interface IActuator {
    /** Device has a camera flash. */
    val hasFlash: Boolean
    /** Device has a vibrator. */
    val hasVibrator: Boolean
    /** Called when the torch changes outside the app (e.g. quick settings). */
    var onTorchChanged: ((Boolean) -> Unit)?

    /** Starts the siren: repeating vibration + alarm tone. */
    fun triggerAlarm()
    fun stopAlarm()

    /** Torch control. Returns true if done in hardware, false if only simulated. */
    fun toggleFlashlight(status: Boolean): Boolean

    /** Turns everything off and releases resources. */
    fun release()
}

/**
 * Drives phone hardware as actuators: siren (vibration + ToneGenerator on the ALARM stream)
 * and floodlight (CameraManager.setTorchMode, no CAMERA permission needed).
 * Hardware availability is checked before every call.
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

    /** Id of the camera with a flash (usually the back camera "0"). */
    private val torchCameraId: String? = findTorchCamera()

    override val hasFlash: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) &&
            torchCameraId != null

    override val hasVibrator: Boolean = vibrator?.hasVibrator() == true

    override var onTorchChanged: ((Boolean) -> Unit)? = null

    /** System feedback: actual torch state. */
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
        // Vibration: repeat the siren pattern until stopAlarm()
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

        // Tone: ToneGenerator needs no audio resources
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
        // No flash (e.g. emulator): simulate in the UI
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

    /** Finds a camera with FLASH_INFO_AVAILABLE. */
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
        /** Vibrate 600 ms, pause 300 ms, repeat. */
        val ALARM_PATTERN = longArrayOf(0, 600, 300, 600, 300)
        const val SIREN_SOUND_MS = 3000
        const val TONE_VOLUME = 80
    }
}
