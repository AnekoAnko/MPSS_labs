"""
Trains the 1D-CNN activity classifier and exports it to TensorFlow Lite.

Run (requires Python 3.9–3.12 and TensorFlow 2.17 to match
org.tensorflow:tensorflow-lite:2.17.0 on Android):
    pip install "tensorflow-cpu==2.17.1" numpy
    python train_activity_model.py
Output:
    activity_classifier.tflite  → app/src/main/assets/
    activity_labels.txt         → app/src/main/assets/
"""
import os
import numpy as np
import tensorflow as tf

from motion_patterns import LABELS, WINDOW, generate, preprocess, random_gravity_dir

SEED = 21                       # fixed seed for reproducibility
PER_CLASS_TRAIN = 6000
PER_CLASS_TEST = 1000
OUT_DIR = os.path.dirname(os.path.abspath(__file__))


def make_set(per_class, rng):
    xs, ys = [], []
    for ci, label in enumerate(LABELS):
        for _ in range(per_class):
            w = generate(label, WINDOW, rng)
            # 25 % of normal windows are transitions (start belongs to another activity),
            # so a change of pace isn't mistaken for an anomaly.
            if label != "ANOMALY" and rng.random() < 0.25:
                other = rng.choice([l for l in LABELS[:3] if l != label])
                k = int(rng.integers(1, WINDOW // 2))
                g_dir = random_gravity_dir(rng)
                w = np.vstack([generate(other, k, rng, rate_hz=20, g_dir=g_dir),
                               generate(label, WINDOW - k, rng, rate_hz=20, g_dir=g_dir)])
            xs.append(preprocess(w))
            ys.append(ci)
    xs, ys = np.stack(xs), np.array(ys, dtype=np.int32)
    idx = rng.permutation(len(ys))
    return xs[idx], ys[idx]


def build_model():
    inp = tf.keras.Input(shape=(WINDOW, 3), batch_size=1, name="accel_window")
    x = tf.keras.layers.Conv1D(16, 5, padding="same", activation="relu")(inp)
    x = tf.keras.layers.Conv1D(24, 3, padding="same", activation="relu")(x)
    avg = tf.keras.layers.GlobalAveragePooling1D()(x)
    mx = tf.keras.layers.GlobalMaxPooling1D()(x)
    x = tf.keras.layers.Concatenate()([avg, mx])
    x = tf.keras.layers.Dense(24, activation="relu")(x)
    out = tf.keras.layers.Dense(len(LABELS), activation="softmax", name="probabilities")(x)
    return tf.keras.Model(inp, out, name="activity_cnn")


def main():
    rng = np.random.default_rng(SEED)
    tf.keras.utils.set_random_seed(SEED)
    x_train, y_train = make_set(PER_CLASS_TRAIN, rng)
    x_test, y_test = make_set(PER_CLASS_TEST, rng)

    # Free batch size for training; fixed batch=1 for export ([1, 20, 3])
    train_model = build_model()
    train_in = tf.keras.Input(shape=(WINDOW, 3))
    trainable = tf.keras.Model(train_in, train_model(train_in))
    trainable.compile(optimizer=tf.keras.optimizers.Adam(2e-3),
                      # label smoothing keeps probabilities below 100 %
                      loss=tf.keras.losses.CategoricalCrossentropy(label_smoothing=0.1),
                      metrics=["accuracy"])
    y_train_1h = tf.keras.utils.to_categorical(y_train, len(LABELS))
    y_test_1h = tf.keras.utils.to_categorical(y_test, len(LABELS))
    trainable.fit(x_train, y_train_1h, epochs=25, batch_size=128, validation_split=0.1, verbose=2,
                  callbacks=[tf.keras.callbacks.EarlyStopping(patience=4, restore_best_weights=True)])
    loss, acc = trainable.evaluate(x_test, y_test_1h, verbose=0)
    print(f"TEST accuracy = {acc:.4f}")

    pred = trainable.predict(x_test, verbose=0).argmax(1)
    cm = tf.math.confusion_matrix(y_test, pred, num_classes=len(LABELS)).numpy()
    print("Confusion matrix (rows = truth, columns = prediction):", LABELS)
    print(cm)
    np.savetxt(os.path.join(OUT_DIR, "confusion_matrix.csv"), cm, fmt="%d", delimiter=",")

    converter = tf.lite.TFLiteConverter.from_keras_model(train_model)
    tflite = converter.convert()          # float32, no quantization
    with open(os.path.join(OUT_DIR, "activity_classifier.tflite"), "wb") as f:
        f.write(tflite)
    with open(os.path.join(OUT_DIR, "activity_labels.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(LABELS) + "\n")
    print(f"Saved activity_classifier.tflite ({len(tflite)} bytes)")


if __name__ == "__main__":
    main()
