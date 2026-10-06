# ЛР №6 — On-device AI: класифікація активності TensorFlow Lite

Проєкт **Lab6_AutoRotateManager** (пакет `com.example.lab6_autorotatemanager`) — копія ЛР №5 з новим пакетом `ml/`.
Модель уже навчена й лежить в `app/src/main/assets`, тож **Teachable Machine та окреме навчання не потрібні**.

## Що де лежить

| Файл | Призначення |
|---|---|
| `app/src/main/assets/activity_classifier.tflite` | Модель 1D-CNN (14,2 КБ, float32). Вхід `[1, 20, 3]`, вихід `[1, 4]` |
| `app/src/main/assets/activity_labels.txt` | Назви класів у порядку виходів: STILL, WALKING, RUNNING, ANOMALY |
| `ml/ActivityClass.kt` | Матриця станів (enum) + `Prediction` (ймовірності, час inference, `format()` → «Walking (92%)») |
| `ml/SensorWindowBuffer.kt` | Кільцевий буфер на 20 вимірів (~1 с при 20 Гц), проріджування за часом, `toModelInput()` — перетворення сирих даних у вхід моделі |
| `ml/ActivityClassifier.kt` | ML-Wrapper: `loadModelFile()` (memory-map з assets) + `Interpreter` + `classify(sensorWindow)` |
| `ml/PredictionSmoother.kt` | Підтвердження класу: 2 прогнози поспіль із впевненістю ≥ 60 % |
| `ml/AiInsightsViewModel.kt` | Буфер → `Dispatchers.Default` → модель → StateFlow для UI; `stableActivity` для інтеграції |
| `ui/AiInsightsCard.kt` | Блок «AI Insights» на вкладці «Сенсор»: «Current Activity: Running (91%)», смуги ймовірностей, час inference, потік |
| `MainActivity.kt` | Сирі `event.values` → `aiViewModel.onRawSample()`; сенсор 20 Гц; підписка на `stableActivity` |
| `analytics/RecordPolicy.kt`, `SensorViewModel.kt` | `fastMode`: при «Біг» запис у Room кожні 200 мс (ЛР №5) |
| `control/ControlViewModel.kt`, `ControlModels.kt` | `onAiActivity()`: «Аномалія» → сирена + прожектор, джерело `AI`; вимкнення через 5 с після зникнення (ЛР №4) |
| `ui/ControlScreen.kt`, `ui/AnalyticsScreen.kt` | Банер «ТРИВОГА (ШІ)» і позначка прискореного запису |
| `app/src/test/.../SensorWindowBufferTest.kt` | Unit-тести буфера і згладжування |
| `ml_training/` | `train_activity_model.py` (навчання й експорт), `motion_patterns.py` (генератор патернів), `confusion_matrix.csv` |
| `tools/emulator_motion.py` | «Програвач» ходьби/бігу/тряски для емулятора через консоль (`sensor set acceleration`) |

Зміни Gradle (вже внесені):

```toml
# gradle/libs.versions.toml
[versions]
tensorflowLite = "2.17.0"
[libraries]
tensorflow-lite = { group = "org.tensorflow", name = "tensorflow-lite", version.ref = "tensorflowLite" }
```
```kotlin
// app/build.gradle.kts
android {
    androidResources { noCompress += "tflite" }   // модель не стискається → її можна відобразити в пам'ять
}
dependencies {
    implementation(libs.tensorflow.lite)
}
```
Методичка пропонує `tensorflow-lite:+`. Тут версія зафіксована (`2.17.0` — остання), щоб збірка не змінювалась сама по собі.
Якщо Android Studio попереджає про **16 KB page size**, замініть залежність на
`com.google.ai.edge.litert:litert:1.4.0` (новий бренд TFLite з вирівнюванням 16 KB). Kotlin-код змінювати не треба.

## Як запустити

1. Android Studio → **File → Open** → `MPSS_labs\Lab6_AutoRotateManager`. Відкривайте саме цю папку, а не корінь репозиторію.
2. **Sync** (іконка слоника або Ctrl+Shift+O). Перший раз Gradle завантажить `tensorflow-lite`, тому потрібен інтернет.
3. `local.properties` уже скопійовано з ЛР №5 (Firebase працює як раніше). Нових ключів не потрібно.
4. Запустіть **app** на емуляторі. На вкладці «Сенсор» угорі з'явиться **AI Insights**.
5. У **Logcat** задайте фільтр `tag:AI_Insights`. Видно завантаження моделі і кожен прогноз з потоком `DefaultDispatcher-worker-N`.

## Як змінювати дані сенсора в емуляторі

**Спосіб 1 (рекомендовано, повторюваний результат)** — скрипт.
Потрібні Python 3 і `pip install numpy`. У терміналі Windows виконайте в папці `Lab6_AutoRotateManager`:
```
python tools\emulator_motion.py demo        # спокій → ходьба → біг → ходьба → тряска → спокій (по 5–8 с)
python tools\emulator_motion.py walk        # або run / shake / impact / still;  Ctrl+C — стоп
```
Порт емулятора видно в заголовку вікна (`emulator-5554`). Якщо він інший, додайте `--port 5556`.

**Спосіб 2 (вручну)** — у Running Devices: **⋮ (Extended controls) → Virtual sensors → Device Pose**.
- Спокій: нічого не чіпати.
- Аномалія: швидко «смикати» повзунки/модель телефона мишкою вперед-назад.
- Повільний поворот модель класифікує як «Спокій», бо це не ходьба.

Ходьбу і біг мишкою відтворити важко, тому для їх скриншотів використовуйте скрипт.

## Що зробити → що має бути → рисунок у звіті (рис. 1–3 — готові діаграми)

| Дія | Очікуваний результат | Рис. |
|---|---|---|
| Project → вигляд **Project**, розгорнути `app/src/main/assets` | видно `activity_classifier.tflite` і `activity_labels.txt` | 4 |
| Запуск, нічого не робити | AI Insights: «Current Activity: Still (~91%)», вікно 20/20 | 5 |
| `emulator_motion.py walk` | «Walking (~92%)», смуга «Ходьба» найдовша | 6 |
| `emulator_motion.py run` | «Running (~91%)», напис «→ ЛР №5: запис у БД прискорено»; на «Аналітиці» — «прискорений запис кожні 200 мс» | 7, 9 |
| `emulator_motion.py shake` | «Anomaly (~93%)», «→ ЛР №4: сирена + прожектор»; на «Керуванні» — червоний банер «ТРИВОГА (ШІ)» | 8, 10 |
| Logcat `tag:AI_Insights` під час demo | рядки `#N Walking (92%) · 0.3 мс · [DefaultDispatcher-worker-2] · [...]` і «Стабільна активність: …» | 11 |

## Типові помилки

| Симптом | Причина / рішення |
|---|---|
| `Could not find org.tensorflow:tensorflow-lite` | немає інтернету під час Sync або в `settings.gradle.kts` немає `mavenCentral()`; повторіть Sync |
| `This file can not be opened as a file descriptor; it is probably compressed` у Logcat (W) | не застосувався `noCompress`; код сам перейде на запасне читання, але перевірте `androidResources` у `app/build.gradle.kts` |
| «Помилка моделі: …FileNotFoundException» | файлу немає в `app/src/main/assets` (саме `main/assets`, не `res`) |
| Прогноз завжди «Still» | емулятор не отримує нових значень: перевірте порт скрипта; модель працює на сирих даних, а повзунки повороту дають лише «Спокій» |
| `Connection refused` у скрипті | емулятор не запущено або інший порт (`adb devices` покаже `emulator-5556`) |
| `KO: authentication required` | видаліть порожній `%USERPROFILE%\.emulator_console_auth_token` або перезапустіть емулятор |
| Lint: «16 KB alignment» | див. вище: LiteRT 1.4.0 |

## Перенавчання моделі (необов'язково)

```
cd ml_training
py -3.12 -m pip install "tensorflow==2.17.1" numpy     # TF 2.17 = версія рантайму в Android
py -3.12 train_activity_model.py
copy activity_classifier.tflite ..\app\src\main\assets\
```
