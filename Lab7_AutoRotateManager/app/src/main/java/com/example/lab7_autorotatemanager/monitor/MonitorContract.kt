package com.example.lab7_autorotatemanager.monitor

// State: the Sensor tab.
data class MonitorState(
    /** Filtered accelerometer values, m/s². */
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    /** Tilt in the screen plane, degrees (0° = upright portrait). */
    val screenAngle: Float = 0f,
    val orientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
    val sensorAvailable: Boolean = true,
    val autoRotate: Boolean = false,
    val isRunning: Boolean = false
)

// Action: user intents and Activity lifecycle events.
sealed interface MonitorAction {
    /** onResume: start the sensor. */
    data object Start : MonitorAction
    /** onPause: stop the sensor to save battery. */
    data object Stop : MonitorAction
    /** "Control screen rotation" switch. */
    data class SetAutoRotate(val enabled: Boolean) : MonitorAction
}

// Event: one-off commands to the screen.
sealed interface MonitorEvent {
    /** Rotate the screen: value for Activity.requestedOrientation. */
    data class RotateScreen(val requestedOrientation: Int) : MonitorEvent
}
