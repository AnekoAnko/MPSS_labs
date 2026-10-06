package com.example.lab4_autorotatemanager.monitor

// State — усе, що зараз видно на вкладці «Сенсор». Тільки val, значення за замовчуванням обов'язкові.
data class MonitorState(
    /** Відфільтровані значення акселерометра, м/с². */
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    /** Кут нахилу від вертикалі в площині екрана, градуси (0° — рівно в портреті). */
    val screenAngle: Float = 0f,
    val orientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
    val sensorAvailable: Boolean = true,
    val autoRotate: Boolean = false,
    val isRunning: Boolean = false
)

// Action — усі дії, які приходять на цей екран (від користувача та з життєвого циклу Activity).
sealed interface MonitorAction {
    /** onResume: екран видно → слухаємо сенсор. */
    data object Start : MonitorAction
    /** onPause: екран сховано → відписуємось, економимо батарею. */
    data object Stop : MonitorAction
    /** Перемикач «Керувати поворотом екрана». */
    data class SetAutoRotate(val enabled: Boolean) : MonitorAction
}

// Event — разові команди від ViewModel до екрана.
sealed interface MonitorEvent {
    /** Повернути екран: значення для Activity.requestedOrientation. */
    data class RotateScreen(val requestedOrientation: Int) : MonitorEvent
}
