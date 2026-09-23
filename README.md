# Claude standalone Android wrapper (v3.0.1)

Fresh Android project. Runs `https://claude.ai/` in an in-app Android WebView. No Chrome Custom Tabs or external browser is launched. Requires the OS-provided Android System WebView renderer (part of Android; not bundled into this APK).

- Only `INTERNET` permission; no phone/SIM, location, camera, microphone, Wi-Fi, or storage permission.
- Android back/edge-swipe minimizes the app directly, not browser history.
- Status/navigation bars follow the page color where it can be read; a light cream fallback is used.
- Uses Android's system document picker for file/image uploads, supports multiple files where the website allows it, and handles picker cancellation. No storage permission. Blocks external main-frame links and offers to copy them.
- Allows only HTTPS Claude/Anthropic top-level pages. Google OAuth is **not supported**: Google disallows login inside third-party embedded WebViews. The app does not spoof the UA or claim a working Google login; use Claude's email sign-in when available. Without Anthropic's first-party integration, independent WebView + reliable Google OAuth cannot both be guaranteed.
- Network routing, DNS, IP, time zone and WebView fingerprint are controlled by device/network settings, **not** by this app. No claim of ban prevention.

GitHub Actions produces a signed release APK at `v3.0.1`. Signing secrets use the same local keystore as the previous installed v2 so upgrades don't require deletion; no old application code, resource, or runtime data is needed. The source is newly written. The signing keystore is stored privately outside the repository.
