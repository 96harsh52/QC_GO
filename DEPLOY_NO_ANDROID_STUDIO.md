# APK banao bina Android Studio ke

Android Studio ki zaroorat nahi. APK banane ke 2 tareeke — apni pasand ka chuno.

---

## Raasta A — Cloud build (laptop pe kuch download nahi) ✅ recommended

GitHub ke free servers APK banate hain. Aapke laptop pe kuch heavy download nahi;
aap sirf final APK (~30 MB) phone pe download karke install karte ho.

Ek baar setup:

```bash
cd /home/re-hyd-006/Downloads/Downloads/Sep_25/QC_GO
git init
git add .
git commit -m "QC GO initial"
# GitHub pe ek naya (private) repo banao, phir:
git remote add origin https://github.com/<username>/<repo>.git
git branch -M main
git push -u origin main
```

Push ke baad:
1. GitHub repo -> **Actions** tab -> "Build APK" workflow apne aap chalega.
2. Green tick aane par us run ko kholo -> neeche **Artifacts** -> `qcgo-debug-apk`
   download karo (ye ek zip hai).
3. Phone par: zip unzip karo -> andar `app-debug.apk` -> tap -> install.
   (Pehli baar "Install unknown apps" allow karna padega.)

> Workflow file already ready hai: `.github/workflows/android-build.yml`

---

## Raasta B — Local CLI build (Android Studio ke bina, par SDK download hoga)

IDE nahi chahiye, par Android **SDK + Gradle + dependencies** (~600 MB–1 GB) ek
baar laptop pe download honge. Uske baad sab offline.

Zaroori: JDK (already hai — Java 21), internet ek baar.

Steps (main automate kar sakta hoon — bolo to script bana dunga):
1. Android **command-line tools** download + `sdkmanager` se `platform-tools`,
   `platforms;android-35`, `build-tools;35.0.0` install + licenses accept.
2. Project mein Gradle 8.7 wrapper generate.
3. `./gradlew assembleDebug` -> APK yahan banti hai:
   `app/build/outputs/apk/debug/app-debug.apk`
4. APK phone pe bhejo (USB / WhatsApp / cloud) -> install (unknown apps allow).

---

## Phone pe install (dono raaston mein same)

- PC ki zaroorat nahi.
- Settings -> Apps -> Special access -> **Install unknown apps** -> apne
  file-manager/browser ko allow karo.
- APK file tap karo -> Install.

## Dhyan do
- Ye **debug** APK hai (test ke liye theek). Play Store ke liye alag signed
  release build chahiye.
- `app/src/main/assets/clean_dirty_int8.tflite` model already project mein hai,
  to APK mein wo bundle ho jaayega.
- OpenCV dependency (`org.opencv:opencv:4.10.0`) agar CI/local mein resolve na ho
  to `app/build.gradle.kts` mein us line ko OpenCV Android SDK module se replace
  karna pad sakta hai (README mein note hai).
