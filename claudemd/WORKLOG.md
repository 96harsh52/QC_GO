# Work Log — QC GO

> Newest entry at the top. Format: what was done / what changed / why / result.

---

## 2026-07-03 — Fix box-spam: merge blobs into one object box

**What was done**
- User reported the app draws boxes on everything (a single bottle split into 3
  "items"). Root cause: counting is OpenCV blob detection (NO YOLO / no object
  model — confirmed against the Obsidian vault), so it fragments one object.
- Interim fix: `AnalyzerPipeline` now merges ALL detected foreground blobs into a
  single UNION bounding box = the object, classifies that one crop. Per-blob
  multi-count is disabled until OpenCV params are tuned on real device frames.

**What changed (files)**
- `AnalyzerPipeline.kt`: added `unionBox()`; analyze() now yields exactly one item
  (whole-frame fallback when nothing is detected).

**Still open**
- "Invalid item" (reject non-bottles) is NOT possible with OpenCV alone — needs an
  object-aware model: either a 3-class classifier (clean/dirty/invalid) or a real
  detector (YOLO). Pending user's choice of direction.

**Result**
- Pushed. Fixes the 1-bottle-into-3-boxes issue; invalid detection still pending.

---

## 2026-07-03 — Advanced UI + guided flow + dedicated Result screen

**What was done**
- Reworked the app into a polished, multi-screen experience per user request:
  a guided 2-step capture flow and a separate result page with per-item cards.

**New flow**
- Step 1: capture empty background. Step 2: capture item(s). On capture, a new
  `ResultActivity` opens showing: verdict headline (ALL CLEAN / DIRTY DETECTED),
  Total/Clean/Dirty stat tiles, a clean-vs-dirty ratio bar with %, and a list of
  each detected item with its cropped ROI thumbnail + prediction + confidence.

**What changed (files)**
- Added `ResultActivity.kt` (renders verdict/stats/ratio/item list),
  `ResultStore.kt` (in-memory hand-off of the last FrameResult incl. thumbnails).
- `AnalyzerPipeline.kt`: `ItemResult` now carries a downscaled ROI `thumb` bitmap
  (`thumbOf`, max 400px) for display.
- `MainActivity.kt`: rewritten as a guided state machine (backgroundReady /
  capturing), navigates to ResultActivity on capture; nicer step pill + button.
- Layouts: redesigned `activity_main.xml` (dark theme, framing guide, step pill,
  bottom scrim, primary capture button, retake button); added
  `activity_result.xml` and `item_result.xml`.
- Resources: added `colors.xml` (dark palette + clean/dirty accents),
  `themes.xml` (`Theme.QCGO`), 5 drawables (chips, frame guide, scrim, step pill);
  rewrote `strings.xml`.
- `AndroidManifest.xml`: registered ResultActivity, switched app theme to
  `Theme.QCGO`.
- Removed now-unused `OverlayView.kt` (boxes shown as crops on the result screen).

**Why**
- User wanted an advanced-developer-grade UI, a guided background→item flow, and a
  result screen listing each item's photo + clean/dirty prediction and counts.

**Result**
- Committed + pushed; CI will build a new versioned APK/Release. NOT yet verified
  on a device (built in cloud). Counting still untuned; whole-frame fallback keeps
  a single-item clean/dirty answer reliable.

---

## 2026-07-03 — Versioned GitHub releases

**What was done**
- Made CI publish a UNIQUE versioned release per build instead of overwriting a
  single `latest` release, so version history is preserved on GitHub.

**What changed (files)**
- `.github/workflows/android-build.yml`: added `env.APP_VERSION = 1.0.${{ github.run_number }}`,
  passes `-PappVersionCode/-PappVersionName` to gradle, names the APK
  `QC_GO-v<version>.apk`, and creates a release with tag `v<version>` (was fixed
  tag `latest`).
- `app/build.gradle.kts`: `versionCode`/`versionName` now read from project
  properties (`appVersionCode`/`appVersionName`) with fallback `1` / `1.0.0`, so
  the installed app shows the CI version.

**Why**
- User asked to maintain versions on GitHub.

**Result**
- Pushed. Next build creates release `v1.0.<run_number>` with its own APK; old
  releases stay. NOTE: the earlier `latest` release/tag from prior runs still
  exists on GitHub and can be deleted manually (can't remove it via SSH-only auth).

---

## 2026-07-03 — App flow: capture-triggered check + clean/dirty count summary

**What was done**
- Changed the app from a continuous live-overlay analyzer to a **capture-triggered**
  flow at the user's request: point camera → tap **"Capture & Check"** → app
  classifies that frame and shows a clean/dirty verdict. User also asked for a
  count breakdown ("1 clean, 2 dirty"), so counting was kept, not removed.

**What changed (files)**
- `AnalyzerPipeline.kt`: now idle until `requestCapture()`; on the next frame it
  counts (OpenCV) + classifies each item. **Robust fallback**: if the counter
  finds 0 boxes (no background set / single bottle filling frame), the WHOLE frame
  is treated as one item, so a clean/dirty answer is ALWAYS produced.
- `MainActivity.kt`: two buttons — "Capture background" (optional, aids counting)
  and "Capture & Check" (primary). New `onCaptureResult` shows a big colored
  banner: single item → "✓ CLEAN (95%)" / "✗ DIRTY (96%)"; multiple → "N items /
  X clean · Y dirty". Green if all clean, red if any dirty.
- `activity_main.xml`: big centered `resultText` banner + the two buttons; kept
  `OverlayView` (now shows the frozen boxes of the last capture).
- `strings.xml`: added capture_check / result_hint / analyzing / result_none /
  result_clean_one / result_dirty_one / result_many; removed old stats strings.
- Untouched but now effectively optional: `ItemCounter.kt` (still used),
  `OverlayView.kt` (still used to draw boxes).

**Why**
- User wants: capture a bottle → tell clean or dirty; and if possible, count how
  many are clean vs dirty.

**Result**
- Code committed + pushed; GitHub Actions will rebuild the APK and update the
  `latest` Release. NOT yet verified on a device. Counting is still untuned, but
  the whole-frame fallback guarantees a clean/dirty verdict regardless.

---

## 2026-07-03 — Fresh-photo sanity check + no-Android-Studio deploy path

**What was done**
- Ran the SHIPPED INT8 model (`app/src/main/assets/clean_dirty_int8.tflite`) on 3
  fresh photos the user supplied, mirroring `export_litert.py` preprocessing
  (decode → resize 224 → uint8[0,255]). Results:
  - `clear/...1795_clean.jpg` (Glacia) → CLEAN 0.996
  - `clear/...1747_clean.jpg` (Anjaneri) → CLEAN 0.996
  - `model_train_dirt/...45_dirty.jpg` → DIRTY 0.996
  - **3/3 correct**, both classes covered. Note: the dirty one is from a
    `model_train_dirt/` folder so it may be training data (result possibly
    optimistic). Still a good directional signal that the model isn't collapsed
    to one class.
- Set up a build path that needs NO Android Studio (user has no IDE and doesn't
  want a big local download). Added a GitHub Actions workflow so APK builds in the
  cloud; laptop downloads nothing, phone just grabs the ~30 MB APK.

**What changed (files)**
- Added `.github/workflows/android-build.yml` (JDK 17 + setup-android + Gradle 8.7
  → `gradle assembleDebug` → uploads `qcgo-debug-apk` artifact). Note: project has
  no gradle wrapper, so the workflow provisions Gradle 8.7 itself.
- Added `DEPLOY_NO_ANDROID_STUDIO.md` (root) — Raasta A cloud build (recommended)
  vs Raasta B local CLI build, plus phone-side install steps.
- No app/model code changed.

**Environment facts discovered**
- Machine has Java 21, but NO Android SDK, NO Gradle, NO gradle wrapper in project,
  no device on adb. So any local build needs ~600 MB–1 GB of SDK/deps first.

**Why**
- User asked how to deploy directly to phone without Android Studio / heavy local
  downloads; cloud CI is the lowest-footprint path.

**Result**
- Cloud build path ready. Pending: user picks A (push to GitHub) or B (local CLI);
  no build has actually run yet, so the APK is unverified. OpenCV Maven dep may
  still need the manual-SDK fallback (README caveat) if it fails to resolve in CI.

---

## 2026-07-03 — Pushed to GitHub, CI triggered

**What was done**
- Made the project a git repo and pushed to the user's GitHub to trigger the
  cloud APK build.

**What changed (files)**
- Added `.gitignore` (excludes model/.venv 4.8G, Model_data 174M, model/data,
  model/artifacts*, build/, .gradle/, .claude/ — keeps app + gradle + the shipped
  tflite + python source + claudemd). Repo = 28 files, ~1.4 MB.
- `git init` + commit + branch `main`, remote `git@github.com:96harsh52/QC_GO.git`.

**Auth / push notes**
- No `gh` CLI on the machine. User's `~/.ssh/id_rsa.pub` was NOT an account key,
  so they added it as a **deploy key with write access** on the QC_GO repo — SSH
  auth then succeeded ("Hi 96harsh52/QC_GO!").
- Remote had an auto-generated `README.md` ("Initial commit"); force-pushed local
  `main` over it (throwaway auto-init, safe).

**Why**
- User wanted to deploy to phone without Android Studio; cloud CI builds the APK.

**Result**
- Code live at https://github.com/96harsh52/QC_GO . GitHub Actions "Build APK"
  triggered on push. APK artifact = `qcgo-debug-apk`. Still UNVERIFIED until the
  run goes green; OpenCV Maven resolution is the most likely failure point.

---

## 2026-07-01 — Train clean/dirty classifier (both backbones)

**What was done**
- Set up an isolated GPU training environment and trained the clean/dirty
  classifier on the user's dataset for BOTH backbones, one at a time.

**Environment setup**
- Dataset lives in `Model_data/{clean,dirt}` (1829 clean, 2000 dirt), flat — but
  the trainer needs `data/train|val/{clean,dirty}`. Added `model/prepare_data.py`
  which builds that layout via symlinks (no copies) and maps `dirt`→`dirty`.
  Split: train/clean 1647, train/dirty 1800, val/clean 182, val/dirty 200.
- The system Python had no pip/TF; the conda `gpu` env is PyTorch/YOLO (no TF)
  with numpy 2 (would break TF). So created an isolated venv `model/.venv`
  (bootstrapped pip via get-pip.py, no sudo) and installed
  `tensorflow[and-cuda]==2.16.1`, `tensorflow-hub==0.16.1`, `numpy<2`, Pillow.
- Fixed two stack issues:
  1. TF 2.16 didn't auto-load the pip CUDA libs → GPU invisible. Added
     `model/run_gpu.sh` wrapper that puts `nvidia/*/lib` on `LD_LIBRARY_PATH`
     (+ ptxas on PATH). GPU (RTX 3050) then detected.
  2. `tensorflow_hub` import broke because `setuptools 82` removed
     `pkg_resources` → pinned `setuptools<81`.

**What changed (files)**
- Added: `model/prepare_data.py`, `model/run_gpu.sh`, `model/.venv/` (ignored),
  `model/data/` (symlink split), `model/artifacts*/` (trained outputs).
- Edited: `model/train_classifier.py` — EfficientNet hub layer set
  `trainable=False` (see why below).

**MobileNetV3-Small — SUCCESS**
- Fine-tuned (928K trainable params). Early-stopped ~epoch 8.
- **Validation accuracy: 1.0000** (val_loss ~4e-4). SavedModel + labels.json
  exported. Artifacts preserved at `model/artifacts_mobilenetv3small/`.
- Caveat: 100% val acc likely means the clean/dirty task is very easy/separable
  OR train and val are visually very similar — worth sanity-checking on fresh
  photos before trusting it in production.

**EfficientNet-Lite0 — needed workarounds**
- The TF-Hub asset is TF1-Hub format. `trainable=True` is unsupported on that
  format under TF 2.16, and under Keras 3 the symbolic functional trace fails
  ("too many positional arguments"). Fix: run with `TF_USE_LEGACY_KERAS=1`
  (Keras 2 / tf-keras, the combo hub was built for) and use the backbone
  FROZEN (feature extractor, head-only training). Model builds (3.4M params).
- Training in progress at time of writing (result recorded next entry/update).

**Why**
- User asked to train the model in the repo on their data, both backbones.

**Result**
- MobileNetV3-Small: done, 100% val acc. EfficientNet-Lite0: running.
- Next: INT8 TFLite export (`export_litert.py`) → drop into
  `app/src/main/assets/clean_dirty_int8.tflite`.

---

## 2026-07-01 — INT8 export, bug fixes, model shipped to app

**What was done**
- Finished EfficientNet-Lite0 training and exported BOTH models to INT8 TFLite,
  fixed two export bugs, and installed the best model into the Android app.

**Training result**
- EfficientNet-Lite0 (frozen feature extractor): **val acc 0.9921** (float).

**Two export bugs found & fixed**
1. Augmentation ops in the graph: `model.export()` kept RandomFlip/Rotation/
   Brightness, which need TFLite **Flex ops** and break pure-INT8 conversion
   (`ERROR_NEEDS_FLEX_OPS`; the app has no Flex runtime). Fix: added an
   `augment` flag to `build_model()` and a new `model/export_clean.py` that
   rebuilds the network with `augment=False`, copies the trained weights, and
   exports a clean SavedModel before quantizing.
2. Single-class calibration: `representative_dataset_gen` took the first 300
   images of a recursive glob, which were ALL `clean` (0 `dirty`), so INT8
   activation ranges were mis-calibrated. Fix: shuffle calibration paths in
   `model/export_litert.py`. This lifted MobileNet INT8 from 85% -> 95.8%.

**INT8 accuracy (val, 382 imgs)**
- MobileNetV3-Small: **0.9581** (float 1.000). Size 1.22 MB. -> SHIPPED.
- EfficientNet-Lite0: **~0.77** even with 1000 calibration imgs. The frozen
  linear head is brittle under INT8 and can't be fine-tuned on this stack, so
  it's NOT recommended for deployment. Kept for reference only.

**What changed (files)**
- Added: `model/export_clean.py`.
- Edited: `model/train_classifier.py` (augment flag),
  `model/export_litert.py` (shuffle calibration).
- Produced: `model/artifacts/clean_dirty_int8_mobilenetv3small.tflite` (1.22 MB),
  `model/artifacts/clean_dirty_int8_efficientnet_lite0.tflite` (3.95 MB).
- App: copied MobileNet INT8 -> `app/src/main/assets/clean_dirty_int8.tflite`,
  removed `PLACE_MODEL_HERE.txt`.

**Verified**
- Shipped model I/O matches the app contract exactly:
  input uint8 [1,224,224,3], output float32 [1,2].

**Why**
- Deliver a working, app-ready INT8 clean/dirty classifier from the user's data.

**Result**
- App now has a real model. MobileNetV3-Small INT8 @ 95.8% val is the deployed
  classifier. Caveat: float val acc was ~100%, which suggests train/val are very
  similar — validate on fresh real-world photos before trusting in production.

---

## 2026-07-01 — Baseline recorded + tracking system created

**What was done**
- Set up this `claudemd/` work-journal folder to start keeping a record of all
  changes going forward.
- Captured the current state of the project as the baseline (see below).

**What changed**
- Added `claudemd/README.md`, `claudemd/WORKLOG.md`, `claudemd/CURRENT_STATE.md`.
- No application code changed.

**Why**
- A lot of work had already been done with no record. This folder fixes that:
  from now on every step is logged.

**Result**
- Tracking system in place. Baseline snapshot below.

**Baseline snapshot (state as of today)**
- Project: QC GO — on-device Android app. Counts items in a camera frame
  (OpenCV, fixed background) and grades each clean/dirty (EfficientNet-Lite0
  INT8 TFLite, CPU + XNNPACK). No YOLO, no NPU.
- Android source present:
  - `app/src/main/java/com/qcgo/quality/ItemCounter.kt`
  - `app/src/main/java/com/qcgo/quality/QualityClassifier.kt`
  - `app/src/main/java/com/qcgo/quality/AnalyzerPipeline.kt`
  - `app/src/main/java/com/qcgo/quality/OverlayView.kt`
  - `app/src/main/java/com/qcgo/quality/MainActivity.kt`
- Python model tooling present:
  - `model/count_opencv.py`, `model/train_classifier.py`,
    `model/export_litert.py`, `model/requirements.txt`
- Gradle build files present at root and `app/`.
- **Model NOT yet in place**: `app/src/main/assets/` only has
  `PLACE_MODEL_HERE.txt`; no `clean_dirty_int8.tflite` yet.
- Not a git repository (no version control history exists).

---

<!-- Add new entries ABOVE this line, newest first. Template:

## YYYY-MM-DD — Short title

**What was done**
-

**What changed**
-

**Why**
-

**Result**
-

-->
