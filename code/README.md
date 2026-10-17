# Claude Code for Android (v0.2.1)

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
sends, files from the system picker (pictures go to the model scaled to 1568 px JPEG; other
files are saved into the working folder, never overwriting, and their paths added to the
message), working-folder and model pickers, thinking and tool steps collapsed into rows (Bash,
file edits with a diff, to-do lists), code blocks with copy, token usage per reply
(↑ context, ↓ output, CH cache hits), light and dark colours.

While Claude Code works, the status line shows elapsed time, API retries (attempt and error)
and how long nothing has arrived once that passes 20 s. A send whose answer is lost is
repeated with the same message id, which the bridge ignores the second time.

## Slash commands

Typing `/` lists commands. Handled by the app: `/help`, `/new` and `/clear` (new
conversation), `/effort low|medium|high|xhigh|max|default`, `/model [name]`, `/folder`,
`/stop`. Effort and model apply from the next message: the bridge restarts the idle process
with `--model` and `--settings '{"env":{"CLAUDE_CODE_EFFORT_LEVEL":…}}'` and resumes the
session (a `CLAUDE_CODE_EFFORT_LEVEL` in the user's settings would override `--effort` and
Claude Code's own `/effort`). Everything else, such as `/compact`, `/context`, `/cost`,
`/init` and skills, goes to Claude Code as typed.

## Setup on the phone

1. Termux (F-Droid or GitHub build) with Claude Code and Python (`pkg install python`).
2. Allow other apps to run Termux commands, once:
   `mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings`
3. Grant the app "Run commands in Termux environment" when it asks.

The app shows these steps itself when it cannot reach the bridge.
