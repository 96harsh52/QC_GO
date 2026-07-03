"""
Export a trained clean/dirty model to INT8 TFLite WITHOUT the augmentation layers.

The training model includes RandomFlip/RandomRotation/RandomBrightness. Those ops
survive `model.export()` and force TFLite Flex ops, which breaks pure-INT8
conversion (and the Android app has no Flex runtime). This script rebuilds the
same network with augment=False, copies the trained weights across (the aug
layers carry no weights, so the weight lists line up), writes a clean SavedModel,
then runs the existing INT8 converter + validation.

Usage (EfficientNet needs legacy Keras for the TF-Hub layer):
    TF_USE_LEGACY_KERAS=1 python export_clean.py --backbone efficientnet_lite0 \
        --weights artifacts_efficientnet_lite0/best.keras \
        --rep-data data/train --validate data/val \
        --out artifacts/clean_dirty_int8_efficientnet_lite0.tflite

    python export_clean.py --backbone mobilenetv3small \
        --weights artifacts_mobilenetv3small/best.keras \
        --rep-data data/train --validate data/val \
        --out artifacts/clean_dirty_int8_mobilenetv3small.tflite
"""
import argparse
import os
import shutil
import tempfile

import tensorflow as tf

import train_classifier as T
from export_litert import convert, validate


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--backbone", required=True,
                   choices=["efficientnet_lite0", "mobilenetv3small"])
    p.add_argument("--weights", required=True, help="trained best.keras")
    p.add_argument("--rep-data", required=True)
    p.add_argument("--validate", help="val dir for INT8 accuracy check")
    p.add_argument("--out", required=True)
    args = p.parse_args()

    # Trained model (with augmentation) -> source of weights.
    trained = T.build_model(args.backbone, augment=True)
    trained.load_weights(args.weights)

    # Clean inference model (no augmentation) -> gets the trained weights.
    infer = T.build_model(args.backbone, augment=False)
    infer.set_weights(trained.get_weights())

    tmp = tempfile.mkdtemp(prefix="clean_sm_")
    try:
        sm_dir = os.path.join(tmp, "saved_model")
        infer.export(sm_dir)  # inference graph, no aug ops
        os.makedirs(os.path.dirname(args.out), exist_ok=True)
        convert(sm_dir, args.rep_data, args.out)
        if args.validate:
            validate(args.out, args.validate)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
