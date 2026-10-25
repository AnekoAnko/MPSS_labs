import argparse
import os
import socket
import sys
import time

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ml_training"))
from motion_patterns import G, generate  # noqa: E402

RATE = 20.0
MODES = {
    "still":  ("STILL",   {"kind": "hand", "f": 0.5, "amp": 0.15}),
    "walk":   ("WALKING", {"f": 1.8, "amp": 2.5}),
    "run":    ("RUNNING", {"f": 3.0, "amp": 8.0}),
    "shake":  ("ANOMALY", {"kind": "shake", "f": 7.0, "amp": 15.0}),
    "impact": ("ANOMALY", {"kind": "impact", "hits": 1}),
}
# ЛР №7: детерміновані сигнали для перевірки Smart-сценаріїв
EXTRA_MODES = ["level", "spike"]
DEMO = [("still", 8), ("walk", 8), ("run", 8), ("walk", 6), ("shake", 5), ("still", 8)]
POSES = {"portrait": np.array([0.0, 0.98, 0.2]), "flat": np.array([0.0, 0.05, 1.0])}


class EmulatorConsole:
    """Мінімальний клієнт консолі емулятора (telnet localhost:5554)."""

    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=3)
        self._read_until_ok()
        token_file = os.path.join(os.path.expanduser("~"), ".emulator_console_auth_token")
        if os.path.exists(token_file):
            token = open(token_file).read().strip()
            self.cmd(f"auth {token}")

    def _read_until_ok(self):
        data = b""
        while not (data.endswith(b"OK\r\n") or self._is_error(data)):
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            data += chunk
        if self._is_error(data):
            raise RuntimeError(data.decode(errors="ignore"))
        return data

    @staticmethod
    def _is_error(data):
        return data.startswith(b"KO") or b"\r\nKO" in data

    def cmd(self, line):
        self.sock.sendall((line + "\r\n").encode())
        return self._read_until_ok()

    def set_accel(self, v):
        self.cmd(f"sensor set acceleration {v[0]:.3f}:{v[1]:.3f}:{v[2]:.3f}")

    def close(self):
        try:
            self.cmd("quit")
        except Exception:
            pass
        self.sock.close()


def samples(mode, seconds, rng, g_dir, x_level=11.0):
    n = int(seconds * RATE)
    rest = g_dir * G
    if mode == "level":                        # ЛР №7: стала X = x_level (сценарій «X > 10»)
        base = np.array([x_level, 9.8, 1.0])   # нахил ≈ 48° < 60° — DecisionEngine ЛР №4 не заважає
        return base + rng.normal(0, 0.1, (n, 3))
    if mode == "spike":                        # ЛР №7: спокій + одиночний викид X раз на 2 с
        data = rest + rng.normal(0, 0.05, (n, 3))
        data[int(RATE)::int(2 * RATE), 0] = x_level
        return data
    label, params = MODES[mode]
    if mode == "impact":                       # удари — по одному на кожну секунду
        chunks = [generate(label, int(RATE), rng, rate_hz=RATE, g_dir=g_dir, params=params)
                  for _ in range(int(np.ceil(seconds)))]
        return np.vstack(chunks)[:n]
    return generate(label, n, rng, rate_hz=RATE, t0=0.0, g_dir=g_dir, params=params)


def play(console, data, title):
    print(f"→ {title}: {len(data)} вимірів ({len(data) / RATE:.0f} с)")
    t_next = time.perf_counter()
    for v in data:
        console.set_accel(v)
        t_next += 1.0 / RATE
        delay = t_next - time.perf_counter()
        if delay > 0:
            time.sleep(delay)


def main():
    ap = argparse.ArgumentParser(description="Програвач рухів акселерометра для Android Emulator")
    ap.add_argument("mode", choices=list(MODES) + EXTRA_MODES + ["demo"])
    ap.add_argument("--x", type=float, default=11.0, help="ЛР №7: значення X для режимів level і spike, м/с²")
    ap.add_argument("--seconds", type=float, default=30)
    ap.add_argument("--port", type=int, default=5554)
    ap.add_argument("--pose", choices=list(POSES), default="portrait")
    args = ap.parse_args()

    rng = np.random.default_rng()
    g_dir = POSES[args.pose] / np.linalg.norm(POSES[args.pose])
    try:
        console = EmulatorConsole(args.port)
    except OSError as e:
        sys.exit(f"Не вдалося підключитися до емулятора на порту {args.port}: {e}\n"
                 f"Перевірте, що емулятор запущено (порт видно в заголовку вікна, напр. 'emulator-5554').")
    try:
        if args.mode == "demo":
            for mode, sec in DEMO:
                play(console, samples(mode, sec, rng, g_dir), mode)
        else:
            play(console, samples(args.mode, args.seconds, rng, g_dir, args.x), args.mode)
    except KeyboardInterrupt:
        print("\nЗупинено")
    finally:
        rest = g_dir * G                       # повертаємо телефон у спокійну позу
        console.set_accel(rest)
        console.close()
        print("Акселерометр повернуто в стан спокою")


if __name__ == "__main__":
    main()
