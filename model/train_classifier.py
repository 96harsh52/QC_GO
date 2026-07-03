"""
Train the clean/dirty classifier.

Two backbones (pick with --backbone):
  - efficientnet_lite0  (default, recommended: best accuracy, quantization-friendly)
  - mobilenetv3small    (fallback: faster, slightly lower accuracy)

Both are designed for edge devices and export cleanly to INT8 TFLite/LiteRT.

Expected dataset layout (ImageFolder style):
    data/
      train/clean/*.jpg
      train/dirty/*.jpg
      val/clean/*.jpg
      val/dirty/*.jpg

IMPORTANT (train/serve match): train on CROPPED item images, the same way the
OpenCV step crops at runtime (count_opencv.crop on the largest contour). If your
labeled data is full frames on the fixed background, crop them first.

Model I/O contract (kept identical for both backbones so Android stays simple):
  - input : float32 image, shape [1, 224, 224, 3], pixel range [0, 255]
            (any scaling the backbone needs is baked into the model)
  - output: float32 [1, 2] softmax over [clean, dirty]

Usage:
    python train_classifier.py --data ./data --epochs 25
    python train_classifier.py --data ./data --backbone mobilenetv3small
"""

import argparse
import json
import os

import tensorflow as tf

IMG_SIZE = 224
CLASS_NAMES = ["clean", "dirty"]  # index 0 = clean, 1 = dirty (alphabetical)
ARTIFACT_DIR = os.path.join(os.path.dirname(__file__), "artifacts")


def make_datasets(data_dir, batch_size):
    train_dir = os.path.join(data_dir, "train")
    val_dir = os.path.join(data_dir, "val")

    train_ds = tf.keras.utils.image_dataset_from_directory(
        train_dir,
        labels="inferred",
        label_mode="categorical",
        class_names=CLASS_NAMES,
        image_size=(IMG_SIZE, IMG_SIZE),
        batch_size=batch_size,
        shuffle=True,
    )
    val_ds = tf.keras.utils.image_dataset_from_directory(
        val_dir,
        labels="inferred",
        label_mode="categorical",
        class_names=CLASS_NAMES,
        image_size=(IMG_SIZE, IMG_SIZE),
        batch_size=batch_size,
        shuffle=False,
    )

    autotune = tf.data.AUTOTUNE
    train_ds = train_ds.prefetch(autotune)
    val_ds = val_ds.prefetch(autotune)
    return train_ds, val_ds


def build_model(backbone, augment=True):
    """Return a Keras model that accepts pixels in [0, 255] and outputs softmax(2).

    augment=True adds the training-time augmentation layers. For export/inference
    pass augment=False: the augmentation ops (RandomRotation etc.) otherwise stay
    in the graph and require TFLite Flex ops, which breaks pure-INT8 conversion.
    The augmentation layers hold no weights, so a model built with augment=False
    is weight-compatible with one built augment=True (same backbone + head).
    """
    inputs = tf.keras.Input(shape=(IMG_SIZE, IMG_SIZE, 3), dtype=tf.float32)

    if augment:
        # Light augmentation (only active at training time).
        x = tf.keras.layers.RandomFlip("horizontal")(inputs)
        x = tf.keras.layers.RandomRotation(0.05)(x)
        x = tf.keras.layers.RandomBrightness(0.1, value_range=(0, 255))(x)
    else:
        x = inputs

    if backbone == "mobilenetv3small":
        # include_preprocessing=True => backbone expects raw [0,255], so no rescale.
        base = tf.keras.applications.MobileNetV3Small(
            input_shape=(IMG_SIZE, IMG_SIZE, 3),
            include_top=False,
            weights="imagenet",
            include_preprocessing=True,
            pooling="avg",
        )
        features = base(x)
    elif backbone == "efficientnet_lite0":
        # EfficientNet-Lite0 lives in TF Hub; it expects inputs in [0, 1].
        import tensorflow_hub as hub
        x = tf.keras.layers.Rescaling(1.0 / 255)(x)
        url = "https://tfhub.dev/tensorflow/efficientnet/lite0/feature-vector/2"
        # This asset is in the TF1-Hub format, which cannot be fine-tuned
        # (trainable=True) under TF 2.16 / Keras 3 -- it raises
        # "Setting hub.KerasLayer.trainable = True is unsupported when loading
        # from the TF1 Hub format". So we use it as a FROZEN feature extractor
        # and train only the classification head (still valid transfer learning).
        base = hub.KerasLayer(url, trainable=False)
        features = base(x)
    else:
        raise ValueError(f"unknown backbone: {backbone}")

    x = tf.keras.layers.Dropout(0.2)(features)
    outputs = tf.keras.layers.Dense(len(CLASS_NAMES), activation="softmax")(x)
    return tf.keras.Model(inputs, outputs, name=f"clean_dirty_{backbone}")


def main():
    p = argparse.ArgumentParser(description="Train clean/dirty classifier")
    p.add_argument("--data", required=True, help="dataset root (train/ and val/)")
    p.add_argument(
        "--backbone",
        default="efficientnet_lite0",
        choices=["efficientnet_lite0", "mobilenetv3small"],
    )
    p.add_argument("--epochs", type=int, default=25)
    p.add_argument("--batch-size", type=int, default=32)
    p.add_argument("--lr", type=float, default=1e-3)
    args = p.parse_args()

    os.makedirs(ARTIFACT_DIR, exist_ok=True)

    train_ds, val_ds = make_datasets(args.data, args.batch_size)
    model = build_model(args.backbone)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(args.lr),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    model.summary()

    ckpt_path = os.path.join(ARTIFACT_DIR, "best.keras")
    callbacks = [
        tf.keras.callbacks.ModelCheckpoint(
            ckpt_path, monitor="val_accuracy", save_best_only=True, mode="max"
        ),
        tf.keras.callbacks.EarlyStopping(
            monitor="val_accuracy", patience=6, restore_best_weights=True, mode="max"
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor="val_loss", factor=0.5, patience=3
        ),
    ]
    model.fit(train_ds, validation_data=val_ds, epochs=args.epochs, callbacks=callbacks)

    # Save a SavedModel for the INT8 export step + metadata for the app.
    saved_dir = os.path.join(ARTIFACT_DIR, "saved_model")
    model.export(saved_dir)  # inference-only graph (drops augmentation layers)
    with open(os.path.join(ARTIFACT_DIR, "labels.json"), "w") as f:
        json.dump(
            {"classes": CLASS_NAMES, "input_size": IMG_SIZE, "input_range": "[0,255]"},
            f,
            indent=2,
        )

    loss, acc = model.evaluate(val_ds)
    print(f"\nValidation accuracy: {acc:.4f}")
    print(f"SavedModel -> {saved_dir}")
    print("Next: python export_litert.py --saved-model artifacts/saved_model "
          "--rep-data data/val")


if __name__ == "__main__":
    main()
