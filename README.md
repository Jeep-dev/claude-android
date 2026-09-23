# Claude standalone Android wrapper (v3.0.3)

Fresh Android project. Runs `https://claude.ai/` in an in-app Android WebView. No Chrome Custom Tabs or external browser is launched. Requires the OS-provided Android System WebView renderer (part of Android; not bundled into this APK).

- `INTERNET` plus **on-demand `RECORD_AUDIO`** for Claude voice dictation. Android asks for mic consent only when `claude.ai` requests audio; non-Claude origins and camera/video requests are denied. No phone/SIM, location, camera, Wi-Fi or storage permission. Rejecting the mic prompt leaves text chat and file upload usable.
- Android back/edge-swipe minimizes the app directly, not browser history. The launcher uses `singleTask`, so reopening an existing app returns to its live WebView without a new `loadUrl`. If Android kills the process under memory pressure, restoring a WebView may require a network load; no app can keep a killed process alive without a persistent service.
- Status/navigation bars follow the page color where it can be read; a light cream fallback is used.
- Uses Android's system document picker for file/image uploads, supports multiple files where the website allows it, and handles picker cancellation. No storage permission. Blocks external main-frame links and offers to copy them.
- Allows HTTPS Claude/Anthropic and Google top-level pages in the WebView, including Google sign-in popups. Third-party cookies are enabled for OAuth. The app no longer blocks Google login itself; however Google may return `403 disallowed_useragent` because it rejects embedded WebViews. This is a Google policy restriction, not something the app can guarantee to overcome. The app does not spoof its UA or launch an external browser.
- Network routing, DNS, IP, time zone and WebView fingerprint are controlled by device/network settings, **not** by this app. No claim of ban prevention.

GitHub Actions produces a signed release APK at `v3.0.3`. Signing secrets use the same local keystore as the previous installed v2 so upgrades don't require deletion; no old application code, resource, or runtime data is needed. The source is newly written. The signing keystore is stored privately outside the repository.
