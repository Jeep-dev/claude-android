# Claude for Android (v4.0.6)

A plain Android WebView showing https://claude.ai/, written from scratch. One small page
script (below), no theme tweaks; apart from keeping the page rendered in the background (below), the
WebView runs with its default behaviour.

- Claude (`claude.ai`, `*.claude.ai`), Anthropic pages and `accounts.google.com` open in the app;
  every other link opens in the system browser. Embedded frames load normally.
- File uploads open the system file picker directly. The microphone is requested only when Claude
  starts voice dictation.
- `https` downloads go to Downloads through the system download manager. Downloads that
  exist only inside the page (`blob:`) are not supported.
- Returning from the background shows the page at once: rendered content is kept
  (offscreen pre-raster) and Chromium is not told the window was hidden, so Claude does not
  refresh. Costs some memory, and a streaming reply keeps running in the background.
- The status bar takes Claude's background colour (set by Claude's own theme setting: dark
  page, dark status bar; light page, light status bar), with icons to match. The navigation
  bar stays white.
- The keyboard opens only after the page is touched: until then a container holds focus,
  so Claude focusing its message box on load or on return opens no keyboard.
- `assets/claude-page.js` (claude.ai only): Claude's scripted focus of a text field is ignored
  unless that field was just touched or a text field already has focus, so switching chats
  or tapping Send opens no keyboard. Enter sends (clicks the Send button next to the message
  box); Shift+Enter makes a new line; Enter confirming an input-method candidate only
  confirms it; with no Send button (Claude replying) Enter is a new line. In an empty box (nothing typed),
  Enter and a tap on the box's Enter icon itself (found as an element, which otherwise only
  shows the keyboard) press Tab instead, with no keyboard for the tap, which takes Claude's grey
  suggested prompt (a phone keyboard has no Tab key); then Enter or Send sends it. After sending, the
  keyboard closes. The keyboard shows a Send key for message boxes.
- Back goes back in the page; at the start it moves the app to the background.
- Google may refuse sign-in inside a WebView (`disallowed_useragent`); sign in with email.

Builds: every push builds a signed APK (GitHub Actions artifact); `main` also publishes a
release `v<versionName>` once per version. The signing key and application id
(`com.jeep.claude`) are the same as earlier versions, so it installs over them.
