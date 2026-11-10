# VLESS Studio — Android APK

Android WebView wrapper for the VLESS Studio HTML app.

## Build APK on GitHub

1. Upload the contents of this folder to the root of your `Vless-Studio` GitHub repository (keep the `.github` folder).
2. Open the repository's **Actions** tab.
3. Select **Build VLESS Studio APK** and press **Run workflow**, or push a commit to `main`.
4. When the workflow finishes, open the run and download **VLESS-Studio-debug-apk** from **Artifacts**.
5. Extract the ZIP and install `app-debug.apk` on Android. You may need to allow installation from this source.

## Notes

- Minimum Android version: Android 6.0 (API 23).
- The app loads the HTML interface locally from the APK.
- The HTML currently uses external CDN resources (Tailwind CSS, Font Awesome, QRCode.js and Google Fonts), so those resources require an internet connection.
- This workflow creates a debug APK for testing, not a Play Store release-signed APK.
