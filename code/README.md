# Claude Code for Android (v0.3.8)

A native phone interface for Claude Code running in Termux. Separate from the claude.ai app in
`app/`: its own application id (`com.jeep.claudecode`), name and icon. No WebView: the
interface is plain Android views (Java), so scrolling and text selection are the system's own.

## How it works

- `assets/bridge.py` runs in Termux (standard-library Python). The app starts it in the
  background through Termux's `RUN_COMMAND` service, passing the script and a random
  per-install token. It listens on `127.0.0.1:8765` only and rejects requests without the
  token. A newly started bridge replaces one already running; the app also replaces a running
  bridge of another version. Requests to the bridge never go through a proxy (a VPN app's
  proxy could hold them); a health check times out after 3 s and a newly started bridge has
  12 s to answer, after which the app shows the setup steps. Tapping the connection status
  while connecting restarts the bridge. When the link stays down for 2 s with the app open, the
  app checks the bridge and starts it again if it is gone; turns the old bridge was running are
  marked interrupted (a new message continues the conversation). The bridge logs its starts,
  stops and crashes to `~/.ccapp/bridge.log` (a start with no stop before the next means it was
  killed).
- The bridge takes Termux's wake lock (`termux-wake-lock`) when it starts and never releases it;
  Termux asks once to be left out of battery optimization.
  Settings → 后台保活 opens Termux's app settings (ColorOS: allow background activity and
  auto-start), asks to leave this app out of battery optimization, and copies a `rish` command
  that adds both apps to the system's idle whitelist and allows Termux to run in the
  background.
- Each conversation is a `claude -p --input-format stream-json --output-format stream-json`
  process (bypass-permissions mode unless changed in Settings → 启动命令, which also sets text
  for `--append-system-prompt` and extra arguments, kept in `~/.ccapp/launch.json`, and edits
  the global `~/.claude/CLAUDE.md` and the working folder's `CLAUDE.md`; all apply from the next
  message), kept alive between turns (stopped after 15 idle minutes)
  and resumed from its saved session (`--resume`) when needed. Stop sends Claude Code's
  `interrupt` control request, and ends the process if the turn has not ended 5 s later.
- The conversation list and history come from Claude Code's own transcripts in
  `~/.claude/projects`. New conversations run in `~/work` by default; the folder can be
  changed per new conversation.
- `Bridge` talks to the bridge (HTTP, event stream); `Chat` turns Claude Code's stream-json
  output (or a transcript) into messages and blocks; `ThreadAdapter` shows them in a
  RecyclerView; `Md` renders Markdown with Markwon, fenced code as cards with a copy button;
  `DrawerFrame` is the drawer that a horizontal swipe anywhere opens.

## Interface

Drawer with the conversation list (swipe right anywhere to open, left to close; long-press a
conversation to delete it), floating composer where the keyboard's Enter (a Send key) or the button sends and the
keyboard then closes (Shift+Enter on a physical keyboard makes a new line), files from the system picker (pictures go to the model scaled to 1568 px JPEG; other
files are saved into the working folder, never overwriting, and their paths added to the
message), working-folder and model pickers, thinking (opened, it scrolls inside a box of at
most 300 dp) and tool steps collapsed into rows as in Claude Code's terminal
(the tool's name in a box, then the step's summary or file; opened, the full command; file edits with a diff, to-do lists), selectable text, code
blocks with copy, token usage per reply (↑ context, ↓ output, CH cache hits), light and dark
colours.

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

## Screenshots without a phone

`ScreensTest` renders the main screens with Robolectric's native graphics:
`gradle :code:testDebugUnitTest --tests com.jeep.claudecode.ScreensTest -Dshots=<dir>`.
