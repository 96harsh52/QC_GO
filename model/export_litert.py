"""
Export the trained clean/dirty SavedModel to an INT8 LiteRT (.tflite) model.

- Full-integer quantization using a representative dataset (required for good CPU
  speed and small size on the phone).
- Input is quantized to uint8 in range [0, 255] -> the Android app feeds raw camera
  pixels directly, no float preprocessing needed.
- Output is kept float32 (softmax) for easy reading on device.

Usage:
    python export_litert.py --saved-model artifacts/saved_model --rep-data data/val
    python export_litert.py --saved-model artifacts/saved_model --rep-data data/val --validate data/val

Output: artifacts/clean_dirty_int8.tflite  (copy this into the Android app's assets/)

Note: in Ultralytics/TF tooling "tflite" is now branded "LiteRT"; the produced
.tflite file is exactly what the LiteRT Android runtime loads.
"""

import argparse
import glob
import os

import numpy as np
import tensorflow as tf

IMG_SIZE = 224
CLASS_NAMES = ["clean", "dirty"]


def _load_image(path):
    raw = tf.io.read_file(path)
    img = tf.io.decode_image(raw, channels=3, expand_animations=False)
    img = tf.image.resize(img, (IMG_SIZE, IMG_SIZE))
    return tf.cast(img, tf.float32)  # range [0, 255]


def representative_dataset_gen(rep_dir, max_samples=300):
    paths = []
    for ext in ("*.jpg", "*.jpeg", "*.png", "*.bmp"):
        paths += glob.glob(os.path.join(rep_dir, "**", ext), recursive=True)
    if not paths:
        raise SystemExit(f"No calibration images found under {rep_dir}")
    # Shuffle so calibration sees ALL classes. Without this, a recursive glob
    # returns one class first (e.g. all 'clean'), so the first max_samples are a
    # single class and the INT8 activation ranges are mis-calibrated -> large
    # accuracy loss for the unseen class.
    import random
    random.Random(0).shuffle(paths)
    paths = paths[:max_samples]

    def gen():
        for p in paths:
            img = _load_image(p)
            yield [tf.expand_dims(img, 0).numpy().astype(np.float32)]

    return gen


def convert(saved_model_dir, rep_dir, out_path):
    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset_gen(rep_dir)
    # Force a full-integer kernel set; keep input uint8 (camera pixels), output float.
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.uint8
    converter.inference_output_type = tf.float32

    tflite_model = converter.convert()
    with open(out_path, "wb") as f:
        f.write(tflite_model)
    size_mb = os.path.getsize(out_path) / 1e6
    print(f"Wrote {out_path} ({size_mb:.2f} MB)")
    return out_path


def validate(tflite_path, val_dir):
    """Run the quantized model over val_dir and report accuracy."""
    interpreter = tf.lite.Interpreter(model_path=tflite_path)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    out = interpreter.get_output_details()[0]

    total, correct = 0, 0
    for label_idx, cls in enumerate(CLASS_NAMES):
        cls_dir = os.path.join(val_dir, cls)
        if not os.path.isdir(cls_dir):
            continue
        for ext in ("*.jpg", "*.jpeg", "*.png", "*.bmp"):
            for p in glob.glob(os.path.join(cls_dir, ext)):
                img = _load_image(p).numpy()
                x = np.expand_dims(img, 0)
                # Input tensor is uint8 [0,255].
                x = np.clip(np.round(x), 0, 255).astype(inp["dtype"])
                interpreter.set_tensor(inp["index"], x)
                interpreter.invoke()
                probs = interpreter.get_tensor(out["index"])[0]
                pred = int(np.argmax(probs))
                correct += int(pred == label_idx)
                total += 1
    if total:
        print(f"INT8 validation accuracy: {correct}/{total} = {correct/total:.4f}")
    else:
        print("No validation images found.")


def main():
    p = argparse.ArgumentParser(description="Export SavedModel to INT8 LiteRT")
    p.add_argument("--saved-model", required=True, help="path to SavedModel dir")
    p.add_argument("--rep-data", required=True, help="folder of calibration images")
    p.add_argument("--out", default=os.path.join(
        os.path.dirname(__file__), "artifacts", "clean_dirty_int8.tflite"))
    p.add_argument("--validate", help="optional val/ dir to measure INT8 accuracy")
    args = p.parse_args()

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    convert(args.saved_model, args.rep_data, args.out)
    if args.validate:
        validate(args.out, args.validate)
    print("\nCopy the .tflite into the app:")
    print("  cp", args.out, "../app/src/main/assets/clean_dirty_int8.tflite")


if __name__ == "__main__":
    main()
