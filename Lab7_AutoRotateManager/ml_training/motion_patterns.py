"""
ЛР №6 (Auto-Rotate Manager, варіант 21): генератор патернів акселерометра.

Один і той самий код використовується:
  • train_activity_model.py — щоб згенерувати навчальний набір для моделі;
  • emulator_motion.py      — щоб «програвати» ці патерни в Android Emulator
                              (команда консолі `sensor set acceleration x:y:z`).

Класи (порядок = індекси виходу моделі = рядки activity_labels.txt):
  0 STILL    — спокій (телефон лежить / тримають у руці / повільно повертають)
  1 WALKING  — ходьба: періодичні поштовхи ~1.4–2.3 Гц, амплітуда 1–4 м/с²
  2 RUNNING  — біг: поштовхи ~2.4–3.6 Гц, амплітуда 5–12 м/с²
  3 ANOMALY  — аномалія (система безпеки): удари/різкі ривки > 12 м/с² або хаотична тряска
"""
import numpy as np

G = 9.80665
LABELS = ["STILL", "WALKING", "RUNNING", "ANOMALY"]
WINDOW = 20          # вимірів у вікні
RATE_HZ = 20.0       # номінальна частота дискретизації


def _unit(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-9 else np.array([0.0, 1.0, 0.0])


def random_gravity_dir(rng):
    """Випадкова поза телефону: найчастіше портрет/альбом/на столі, інколи довільна."""
    poses = [np.array([0, 1, 0.15]), np.array([1, 0, 0.15]), np.array([-1, 0, 0.15]),
             np.array([0, 0, 1.0]), np.array([0, 0.7, 0.7]), np.array([0, -1, 0.1])]
    if rng.random() < 0.7:
        base = poses[rng.integers(len(poses))].astype(float)
        base = base + rng.normal(0, 0.25, 3)
    else:
        base = rng.normal(0, 1, 3)
    return _unit(base)


def _orthonormal(g_dir, rng):
    a = _unit(np.cross(g_dir, rng.normal(0, 1, 3)))
    b = np.cross(g_dir, a)
    return a, b


def generate(label, n, rng, rate_hz=None, t0=None, g_dir=None, params=None):
    """
    Повертає масив (n, 3) — «сирі» значення акселерометра в м/с² (як event.values в Android),
    тобто гравітація + рух + шум. params — словник фіксованих параметрів руху (для емулятора,
    щоб патерн не «стрибав» між вікнами); якщо None — параметри випадкові.
    """
    rate = rate_hz or rng.uniform(14.0, 26.0)
    t = (t0 if t0 is not None else rng.uniform(0, 10)) + np.arange(n) / rate
    t = t + rng.normal(0, 0.002, n)                    # джитер часу надходження подій
    g_dir = g_dir if g_dir is not None else random_gravity_dir(rng)
    a, b = _orthonormal(g_dir, rng)
    p = dict(params or {})
    noise = p.get("noise", rng.uniform(0.03, 0.18))
    out = np.tile(g_dir * G, (n, 1))

    if label == "STILL":
        kind = p.get("kind", rng.choice(["rest", "hand", "turn"], p=[0.4, 0.35, 0.25]))
        if kind == "hand":                               # тремор руки: дрібні повільні коливання
            f = p.get("f", rng.uniform(0.2, 1.2))
            amp = p.get("amp", rng.uniform(0.05, 0.45))
            out += amp * np.outer(np.sin(2 * np.pi * f * t + rng.uniform(0, 6.3)), _unit(rng.normal(0, 1, 3)))
        elif kind == "turn":                             # повільний поворот (як мишкою в емуляторі)
            speed = np.deg2rad(p.get("deg_s", rng.uniform(10, 70))) * rng.choice([-1, 1])
            ang = speed * (t - t[0])
            out = G * (np.outer(np.cos(ang), g_dir) + np.outer(np.sin(ang), a))
    elif label in ("WALKING", "RUNNING"):
        if label == "WALKING":
            f = p.get("f", rng.uniform(1.4, 2.3))
            amp = p.get("amp", rng.uniform(1.2, 4.0))
        else:
            f = p.get("f", rng.uniform(2.4, 3.6))
            amp = p.get("amp", rng.uniform(5.0, 12.0))
        ph = rng.uniform(0, 2 * np.pi)
        s = np.sin(2 * np.pi * f * t + ph)
        if label == "RUNNING":                           # біг — гостріші удари стопи
            s = np.sign(s) * np.abs(s) ** 0.6
        vert = amp * s + 0.25 * amp * np.sin(4 * np.pi * f * t + ph * 1.3)
        side = 0.35 * amp * np.sin(np.pi * f * t + rng.uniform(0, 6.3))
        fwd = 0.25 * amp * np.sin(2 * np.pi * f * t + ph + 1.1)
        out += np.outer(vert, g_dir) + np.outer(side, a) + np.outer(fwd, b)
    elif label == "ANOMALY":
        kind = p.get("kind", rng.choice(["impact", "shake"], p=[0.5, 0.5]))
        if kind == "impact":                             # удар / спроба зняти телефон
            k = int(p.get("hits", rng.integers(1, 4)))
            for _ in range(k):
                i = rng.integers(0, n)
                d = _unit(rng.normal(0, 1, 3))
                mag = rng.uniform(13, 32)
                out[i] += d * mag
                if i + 1 < n:
                    out[i + 1] += d * mag * rng.uniform(-0.6, 0.4)
            out += rng.normal(0, rng.uniform(0.2, 1.5), (n, 3))
        else:                                            # хаотична тряска / вібрація
            f = p.get("f", rng.uniform(4.5, 9.0))
            amp = p.get("amp", rng.uniform(9.0, 22.0))
            d1, d2 = _unit(rng.normal(0, 1, 3)), _unit(rng.normal(0, 1, 3))
            out += amp * (np.outer(np.sin(2 * np.pi * f * t), d1)
                          + 0.6 * np.outer(np.sin(2 * np.pi * 1.7 * f * t + 1.0), d2))
            out += rng.normal(0, amp * 0.25, (n, 3))
    else:
        raise ValueError(label)

    out += rng.normal(0, noise, (n, 3))
    return np.clip(out, -39.2, 39.2).astype(np.float32)   # діапазон акселерометра ±4g


def preprocess(window):
    """
    ТЕ САМЕ, що SensorWindowBuffer.toModelInput() у Kotlin:
      1) з кожної осі віднімаємо середнє по вікну → прибираємо гравітацію і позу телефону;
      2) ділимо на G → значення в одиницях g (приблизно −3..3);
    Результат — (WINDOW, 3) float32 = вхідний тензор [1, 20, 3] без batch-виміру.
    """
    w = np.asarray(window, dtype=np.float32)
    return ((w - w.mean(axis=0, keepdims=True)) / np.float32(G)).astype(np.float32)
