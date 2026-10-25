# ЛР №7 — Мініпроєкт: цілісна смарт-система Auto-Rotate Manager

Проєкт **Lab7_AutoRotateManager** (пакет `com.example.lab7_autorotatemanager`) — копія ЛР №6, у якій усі модулі ЛР №1–6 зібрані в одну систему з наскрізним сценарієм:

```
Акселерометр (ЛР1) → TFLite «Аномалія» (ЛР6) → Room, подія + activity (ЛР5) → Firebase sensor_data / status (ЛР3)
                                            └→ сирена + прожектор (ЛР4) + ALARM:ON на BLE-хаб AR-Hub-46 (ЛР2)
```

Архітектура — **MVI** (як у методичці «Архітектура додатку: MVI»): кожен екран — `XxxContract` + `XxxViewModel` + `XxxScreen`, Android API — у `helpers/`, дані — у `repository/`.
Нових бібліотек і ключів **не потрібно** — `local.properties` той самий, що в ЛР №6.

## Що нового в ЛР №7

| Файл | Що робить |
|---|---|
| `AutoRotateApp.kt`, `di/AppContainer.kt`, `di/ViewModelFactory.kt` | **Manual DI**: контейнер створює залежності, фабрика передає їх у конструктори ViewModel. `AppGraph` видалено |
| `dashboard/Dashboard{Contract,ViewModel,Screen}.kt` | **Головний екран**: стан ML-моделі, останні 5 записів Room, статус хмари і BLE, кнопки швидкого керування |
| `scenario/Scenario{Contract,ViewModel,Screen}.kt` | **Smart-сценарії** «Якщо X > 10 → вібрація»: список правил, додавання, шумопоглинання, журнал |
| `scenario/ScenarioEngine.kt` | Двигун правил: витримка `holdMs`, гістерезис 5 %, зняття через 1 с (чистий Kotlin) |
| `helpers/NoiseFilter.kt` | `MedianNoiseFilter` — медіанний фільтр 1/3/5/9 вимірів (захист від хибних спрацювань) |
| `repository/ScenarioRule.kt`, `ScenarioRecord.kt`, `ScenarioDao.kt`, `ScenarioRepository.kt` | Модель правила, таблиця Room `scenario_rules`, репозиторій |
| `repository/SystemStateRepository.kt` | + `ai`, `cloud`, `ble`, `actuators` (для Dashboard) і **шина `requests`** (`ActuatorRequest`) |
| `repository/SensorRepository.kt` | + `recent(5)`, обробка **SQLiteFullException** (диск заповнено) і `storageFull` |
| `repository/SensorRecord.kt`, `SensorReading.kt`, `CloudApi.kt`, `AppDatabase.kt` | поле `activity` (стан ML) у Room і JSON, `critical` у `status/`, **БД версії 3** |
| `control/ControlViewModel.kt`, `helpers/SmartActuator.kt` | виконує запити з шини (Dashboard, сценарії), `vibratePulse()` |
| `ble/BleViewModel.kt`, `helpers/ble/MockBleConnector.kt` | критичний стан → `ALARM:ON/OFF` на хаб; реле хаба з Dashboard і сценаріїв |
| `MainActivity.kt` | 6 вкладок: Dashboard, Сенсор, BLE-хаб, Керування, Аналітика, Сценарії; `by viewModels { factory }` |
| `app/src/test/.../ScenarioEngineTest.kt` | unit-тести сценаріїв і фільтра |
| `tools/emulator_motion.py` | + режими `level` (стале X = 11) і `spike` (одиночні викиди X = 11 раз на 2 с) |

Хто чим керує: **кожним ресурсом володіє одна ViewModel**. Dashboard і сценарії лише кладуть `ActuatorRequest` у `SystemStateRepository.requests`; прожектор/сирену/лампу/вібрацію вмикає `ControlViewModel`, реле хаба — `BleViewModel`.

## Як запустити

1. Android Studio → **File → Open** → `MPSS_labs\Lab7_AutoRotateManager` (саме цю папку).
2. **Sync** (слоник або Ctrl+Shift+O) → **Run app**.
3. Версія БД змінилася (3), тому при першому запуску стара база ЛР №6 перестворюється — це нормально (`fallbackToDestructiveMigration`). Якщо з'явиться «Activity class does not exist» — `adb uninstall com.example.lab7_autorotatemanager`, потім Build → Clean Project.
4. Скрипт рухів: Python 3 + `pip install numpy`, у папці `Lab7_AutoRotateManager`:
```
python tools\emulator_motion.py demo            # спокій → ходьба → біг → тряска → спокій
python tools\emulator_motion.py shake --seconds 10
python tools\emulator_motion.py level --seconds 5   # X = 11 м/с² (сценарій «X > 10»)
python tools\emulator_motion.py spike --seconds 10  # одиночні викиди (хибне спрацювання)
```

## Тест-кейси і скриншоти для звіту

| Що зробити | Що має бути | Рис. |
|---|---|---|
| Запустити, телефон у спокої; вкладка **Dashboard** | «Спокій 9x %», хмара онлайн (зелена точка), записи з ☁ | 4 |
| **BLE-хаб** → Пошук → Підключити AR-Hub-46; потім `emulator_motion.py shake` | Dashboard: червоний банер «КРИТИЧНИЙ СТАН», «Аномалія», сирена й прожектор увімкнені | 5 |
| Після тряски відкрити **BLE-хаб** | у журналі TX `"ALARM:ON"`, RX `"OK:ALARM=ON"`; після спокою ≈ 5 с — `ALARM:OFF` | 6 |
| Консоль Firebase → Realtime Database | `sensor_data/AR-…`: запис з `activity: "ANOMALY"`, `actionTriggered: true`; `status/AR-…`: `critical: true` | 7 |
| Вкладка **Сценарії**, `emulator_motion.py level --seconds 5` | правило «X > 10 → Вібрація» — АКТИВНЕ (рожеве), журнал «СПРАЦЮВАЛО» | 8 |
| Увімкнути **режим польоту** (шторка), рухати телефон 1 хв | Dashboard: «офлайн», «у черзі: N», записи з ⏳ | 9 |
| Вимкнути режим польоту | Toast «Мережа відновлена: надіслано N записів», у черзі 0; Logcat `tag:CloudSync` | 10 |
| Сценарії: фільтр **1**, видалити правило X і додати «X > 10 → Вібрація» з витримкою **0**; `spike` | Logcat `tag:SmartScenario`: кілька «СПРАЦЮВАЛО» (хибні). Потім фільтр **5** і правило з витримкою 300 → жодного | 11 |
| **View → Tool Windows → Profiler** → процес → **Memory**; 10 хв `demo`, кілька разів повернути екран і згорнути | графік — «пилка» без постійного росту | 12 |
| У Profiler: 🗑 Force GC → **Capture heap dump** → у фільтрі `MainActivity` | 1 екземпляр, `Leaks: 0` | 13 |

Відео: у панелі **Running Devices** кнопка **Screen Record** (або Extended controls → Record and Playback). Показати: рух скриптом/Extended controls → Dashboard «Аномалія» → журнал BLE ALARM:ON → новий запис у Firebase. Посилання вставити у звіт (виділено жовтим).

Корисні фільтри Logcat: `tag:SmartScenario`, `tag:Control`, `tag:BLE_MOCK`, `tag:CloudSync`, `tag:SensorDB`, `tag:SensorRepo`, `tag:AI_Insights`.

## Типові проблеми

| Проблема | Рішення |
|---|---|
| Реле хаба не вмикається з Dashboard | Toast «BLE-хаб не підключено» — спершу підключіть хаб на вкладці BLE-хаб |
| ALARM:ON не видно в журналі BLE | хаб був відключений: команда піде одразу після підключення (у журналі є попередження) |
| Сценарій X > 10 не спрацьовує від повзунків Virtual sensors | повзунки лише повертають телефон (|X| ≤ 9,8) — використовуйте `emulator_motion.py level` |
| `level` вмикає ще й тривогу нахилу | вектор підібрано з нахилом ≈ 48° (< 60°); якщо поріг на вкладці Керування менший — поверніть 60° |
| Вкладки не вміщаються | рядок вкладок прокручується вбік (ScrollableTabRow) |
| `Module not specified` | відкрито не ту папку — потрібна `Lab7_AutoRotateManager` з `settings.gradle.kts` |
