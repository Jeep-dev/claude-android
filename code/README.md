# Claude Code for Android (v0.1.0)

A phone interface for Claude Code running in Termux. Separate from the claude.ai app in
`app/`: its own application id (`com.jeep.claudecode`), name and icon.

## How it works

- `assets/bridge.py` runs in Termux (standard-library Python). The app starts it in the
  background through Termux's `RUN_COMMAND` service, passing the script and a random
  per-install token. It listens on `127.0.0.1:8765` only and rejects requests without the
  token. A newly started bridge replaces one already running.
- Each conversation is a `claude -p --input-format stream-json --output-format stream-json`
  process in bypass-permissions mode, kept alive between turns (stopped after 15 idle minutes)
  and resumed from its saved session (`--resume`) when needed. Stop sends Claude Code's
  `interrupt` control request, and ends the process if the turn has not ended 5 s later.
- The conversation list and history come from Claude Code's own transcripts in
  `~/.claude/projects`. New conversations run in `~/work` by default; the folder can be
  changed per new conversation.
- `assets/web` is the interface, served from assets on an https origin
  (`WebViewAssetLoader`). The page calls `window.Native`; `MainActivity` does the HTTP
  requests, reads the bridge's event stream and passes events to the page in batches.
- Markdown is rendered with marked, sanitised with DOMPurify and highlighted with
  highlight.js, bundled in `assets/web/vendor` (licences in `LICENSES.txt`).

## Interface

Drawer with the conversation list (swipe right anywhere to open, left to close; long-press a
conversation to delete it), floating composer where Enter makes a new line and the button
sends, images from the system picker (scaled to 1568 px JPEG), working-folder and model
pickers, thinking and tool steps collapsed into rows (Bash, file edits with a diff, to-do
lists), code blocks with copy, token usage per reply, light and dark colours.

## Setup on the phone

1. Termux (F-Droid or GitHub build) with Claude Code and Python (`pkg install python`).
2. Allow other apps to run Termux commands, once:
   `mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings`
3. Grant the app "Run commands in Termux environment" when it asks.

The app shows these steps itself when it cannot reach the bridge.
