# Claude for Android (v4.0.0)

A plain Android WebView showing https://claude.ai/, written from scratch. No page scripts,
no rendering or theme tweaks: the WebView runs with its default behaviour.

- Claude (`claude.ai`, `*.claude.ai`), Anthropic pages and `accounts.google.com` open in the app;
  every other link opens in the system browser. Embedded frames load normally.
- File uploads use the system file picker. The microphone is requested only when Claude
  starts voice dictation.
- `https` downloads go to Downloads through the system download manager. Downloads that
  exist only inside the page (`blob:`) are not supported.
- Back goes back in the page; at the start it moves the app to the background.
- Google may refuse sign-in inside a WebView (`disallowed_useragent`); sign in with email.

Builds: every push builds a signed APK (GitHub Actions artifact); `main` also publishes a
release `v<versionName>` once per version. The signing key and application id
(`com.jeep.claude`) are the same as earlier versions, so it installs over them.
