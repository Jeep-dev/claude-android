# Claude Android v2 — browser-backed launcher

A minimal Android launcher for `https://claude.ai/`. The *entire* website and its Google sign-in run in the same browser Custom Tab, rather than a WebView. This avoids Google's embedded-WebView OAuth block and the WebView/browser cookie split. Requires an installed compatible browser; feature support depends on that browser.

## What this does / does not do

- The launcher requests **zero Android permissions**, has no WebView, JavaScript injection, forged User-Agent, cookie access, telemetry, or password access. Backing up launcher data is disabled.
- The browser has its **own** permissions, networking, profile, cookies, and fingerprint. The launcher cannot hide country, carrier, DNS, IP, time zone or device signals. Configure and verify the browser/device/VPN independently. No app can guarantee an account won't be restricted.
- Google login stays in the browser, with its browser cookie store. Whether the site accepts login depends on Anthropic, Google, and the browser; it is not a bypass.
- The launcher offers a *Return to home* button and its own Back key minimizes it. **While the Custom Tab is open, edge-swipe/back is controlled by the browser**, not this app; it may navigate webpage history. An unverified third-party wrapper cannot both take over the browser's back gesture and use the browser's protected Google login session.
- Toolbar and system bars use Claude-like colors. Exact bar color inside the tab is controlled by the browser, and can differ from webpage pixels.
- The launcher icon is a Claude-inspired vector. No claim of affiliation with Anthropic.

## Building and signing

GitHub Actions builds on every push to `main`, verifies APK signature and verifies no permission is present, and publishes `v2.0.0`. CI uses a fixed signing key in GitHub repository secrets (`CLAUDE_KEYSTORE_B64`, `CLAUDE_KEYSTORE_PASSWORD`, `CLAUDE_KEY_PASSWORD`). **Back up that keystore securely:** without it, subsequent APKs cannot update v2 in place. Do not commit the keystore. The previous v1 CI used a different ephemeral debug key, so installing v2 over v1 requires first removing v1 and **losing its app data** (browser data remains independent). Do not uninstall v1 without consent.

Local build: provide environment variables `CLAUDE_KEYSTORE_FILE`, `CLAUDE_KEYSTORE_PASSWORD`, `CLAUDE_KEY_ALIAS=claude`, `CLAUDE_KEY_PASSWORD`; run `gradle :app:assembleRelease` with JDK 17 and Android SDK 34.

## Test checklist (on the device)

1. Open launcher, check bar and icon in light/dark modes.
2. Open Claude, use Google sign-in, return to Claude and verify session persists after reopening.
3. Open other pages and test back gesture; confirm browser handles it, rather than expecting forced exit.
4. Inspect APK manifest permissions and check browser/device VPN, DNS and IP separately.
