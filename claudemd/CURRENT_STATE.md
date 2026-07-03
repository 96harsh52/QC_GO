# Current State — QC GO

**Version:** v0.2.0 (classifier trained + INT8 model shipped into the app)
**Last updated:** 2026-07-03

## What this project is
On-device Android quality-control app. Camera → counts items (OpenCV, fixed
background) → grades each clean/dirty (INT8 TFLite classifier on CPU). Overlay
shows Total / Clean / Dirty. No YOLO, no NPU.

## What exists and works
- [x] Android app source scaffolded (5 Kotlin files in `com.qcgo.quality`).
- [x] Python tooling for counting tuning, training, and INT8 export.
- [x] **Trained clean/dirty classifier on the user's data (`Model_data/`).**
- [x] **INT8 model shipped:** `app/src/main/assets/clean_dirty_int8.tflite`
      (MobileNetV3-Small, 1.22 MB, uint8[1,224,224,3] → float32[1,2]).
- [x] Isolated GPU training env at `model/.venv` (TF 2.16 + CUDA), launched via
      `model/run_gpu.sh`.

## Models trained (val set = 382 imgs)
| Backbone | Float val acc | INT8 val acc | Notes |
|----------|--------------|--------------|-------|
| MobileNetV3-Small | 1.000 | **0.958** | Fine-tuned. **Deployed in app.** |
| EfficientNet-Lite0 | 0.992 | ~0.77 | Frozen head (TF1-Hub can't fine-tune here); INT8-fragile, not deployed. |

Artifacts kept at `model/artifacts_mobilenetv3small/`,
`model/artifacts_efficientnet_lite0/`, and `.tflite` files in `model/artifacts/`.

## Fresh-photo sanity check (2026-07-03)
Ran shipped INT8 model on 3 user photos NOT from the val split: 2 clean → CLEAN
(0.996 each), 1 dirty → DIRTY (0.996). **3/3 correct**, both classes fire. Caveat:
tiny sample, and the dirty image came from `model_train_dirt/` (maybe training
data). Directionally good — model is not collapsed to one class.

## Deploy without Android Studio (set up 2026-07-03)
Added `.github/workflows/android-build.yml` (cloud APK build) + root guide
`DEPLOY_NO_ANDROID_STUDIO.md`. Machine has Java 21 but no Android SDK/Gradle/wrapper
and no adb device, so a local build would need ~600 MB–1 GB download first; cloud
CI avoids that. No build has run yet — APK still unverified.

## What is pending / not done yet
- [ ] Actually build the APK (push to GitHub for CI, or local CLI) — not run yet.
- [ ] Build + run the app on a device and verify end-to-end.
- [ ] Counting constants tuned against real background/sample frames.
- [ ] Bigger balanced fresh-photo test (train-independent) for real accuracy.
- [ ] No version control (project is not a git repo).

## How to reproduce training/export (from `model/`)
```
python prepare_data.py --src ../Model_data --out ./data          # build train/val split
./run_gpu.sh python train_classifier.py --data ./data --backbone mobilenetv3small --batch-size 16
./run_gpu.sh python export_clean.py --backbone mobilenetv3small \
    --weights artifacts_mobilenetv3small/best.keras --rep-data data/train \
    --validate data/val --out artifacts/clean_dirty_int8_mobilenetv3small.tflite
# EfficientNet path needs: TF_USE_LEGACY_KERAS=1 (and backbone efficientnet_lite0)
```

## Model contract (fixed — do not change)
uint8 `[1,224,224,3]` in `[0,255]` → float32 `[1,2]` softmax over `[clean, dirty]`.

## Key files map
- Counting: `app/.../ItemCounter.kt` ↔ `model/count_opencv.py`
- Classify: `app/.../QualityClassifier.kt`
- Pipeline: `app/.../AnalyzerPipeline.kt`
- UI/overlay: `app/.../OverlayView.kt`, `app/.../MainActivity.kt`
- Train: `model/train_classifier.py`  · Export: `model/export_clean.py` (clean,
  Flex-free) / `model/export_litert.py` (converter + calibration + validate)
- Data prep: `model/prepare_data.py`  · GPU launcher: `model/run_gpu.sh`
