# VLESS Studio — Android APK

Android WebView wrapper for the VLESS Studio HTML app.

## Build APK on GitHub

1. Import the project files into the repository root, preserving folders.
2. Open **Actions** and run **Build VLESS Studio APK**, or push a commit to `main`.
3. When the workflow finishes, open the run and download **VLESS-Studio-debug-apk** from **Artifacts**.
4. Extract the downloaded artifact ZIP and install `app-debug.apk` on Android.

## Project structure

- `app/src/main/assets/index.html` — VLESS Studio interface
- `app/src/main/java/com/ragnar/vlessstudio/MainActivity.java` — Android WebView activity
- `app/src/main/AndroidManifest.xml` — app manifest
- `.github/workflows/build-apk.yml` — GitHub Actions build workflow

## Notes

- Minimum Android version: Android 6.0 (API 23).
- The HTML interface is bundled locally in the APK.
- Some visual resources (Tailwind CSS, Font Awesome, QRCode.js and Google Fonts) load from external CDNs and need internet access.
- This workflow creates a debug APK for testing, not a Play Store release-signed APK.
