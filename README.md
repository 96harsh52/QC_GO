# QC GO — On-device Quality Matrix (Android, no YOLO)

Counts items in the camera frame and grades each one **clean / dirty**, fully
on-device on a mid-range Android phone (CPU-only, no NPU required).

## How it works

```
CameraX frame
  ├─ OpenCV (fixed background)  → count items            (no model)
  └─ EfficientNet-Lite0 INT8    → clean / dirty per item (tiny TFLite/LiteRT model)
Output overlay:  Total / Clean / Dirty
```

- **Counting** uses classical computer vision because the background is fixed —
  no neural network, deterministic and fast.
- **Clean/dirty** is a small INT8 classifier (EfficientNet-Lite0, ~5 MB; or
  MobileNetV3-Small fallback, ~3 MB) running on CPU via XNNPACK.

## Repository layout

```
model/                     Python: train + export the classifier, tune counting
  count_opencv.py          OpenCV item-counting prototype (mirrors ItemCounter.kt)
  train_classifier.py      Train EfficientNet-Lite0 / MobileNetV3-Small (clean/dirty)
  export_litert.py         Export SavedModel -> clean_dirty_int8.tflite
  requirements.txt
app/                       Android (Kotlin, CameraX + OpenCV + LiteRT)
  src/main/java/com/qcgo/quality/
    ItemCounter.kt         OpenCV counting (port of count_opencv.py)
    QualityClassifier.kt   LiteRT INT8 clean/dirty inference
    AnalyzerPipeline.kt    count -> crop -> classify per frame
    OverlayView.kt         draws boxes (green=clean / red=dirty) + counts
    MainActivity.kt        CameraX wiring + permissions + UI
  src/main/assets/         drop clean_dirty_int8.tflite here
```

## 1. Prepare data

Crop your labeled images to the item region (same as the runtime crop) and lay
them out as:

```
model/data/
  train/clean/*.jpg   train/dirty/*.jpg
  val/clean/*.jpg     val/dirty/*.jpg
```

Also capture one empty-background photo (`empty_bg.jpg`) for tuning the counter.

## 2. Tune counting (Python, optional but recommended)

```
cd model
pip install -r requirements.txt
python count_opencv.py --image sample_frame.jpg --background empty_bg.jpg --show
# adjust --thresh / --min-area-frac until 1, 2, 3 items count correctly
```

Mirror any changed constants into `app/.../ItemCounter.kt` (same names).

## 3. Train + export the classifier

```
cd model
python train_classifier.py --data ./data --epochs 25            # EfficientNet-Lite0 (default)
# or, if you need more speed on the device:
python train_classifier.py --data ./data --backbone mobilenetv3small

python export_litert.py --saved-model artifacts/saved_model --rep-data data/val --validate data/val
cp artifacts/clean_dirty_int8.tflite ../app/src/main/assets/clean_dirty_int8.tflite
```

The model contract is fixed so the app never changes: **uint8 [1,224,224,3] input
in [0,255] → float32 [1,2] softmax over [clean, dirty]**.

## 4. Build + run the app

1. Open the project root in Android Studio (it auto-detects the Gradle build).
2. Ensure `app/src/main/assets/clean_dirty_int8.tflite` exists (step 3).
3. Run on a device. Grant the camera permission.
4. With the **empty** fixed background in view, tap **Capture background** once.
5. Place items — the overlay shows boxes (green = clean, red = dirty) and the
   header shows **Total / Clean / Dirty**.

### Notes / gotchas
- OpenCV comes from the `org.opencv:opencv` Maven artifact. If your environment
  can't resolve it, import the OpenCV Android SDK module manually and drop the
  Gradle line in `app/build.gradle.kts`. `OpenCVLoader.initLocal()` in
  `MainActivity` initializes it.
- The GPU delegate is intentionally **not** used — it's unreliable for these
  graphs on many devices; CPU + XNNPACK is the reliable path and the model is
  tiny. Tune `numThreads` in `QualityClassifier` to the big-core count.
- If inference is too slow, retrain with `--backbone mobilenetv3small` and
  re-export; the app needs no changes.

## Verification checklist
- Python: `count_opencv.py` returns correct counts for 1/2/3 items; INT8
  validation accuracy (`export_litert.py --validate`) within ~1–2% of float.
- Device: Total matches items placed; clean→green, dirty→red; per-frame latency
  acceptable (a few FPS is fine for QC).
