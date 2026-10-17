"""Claude Code bridge for the Claude Code app.

Runs inside the app's own Linux userland (Alpine under proot, started by the app's foreground
service) and lets the app talk to Claude Code: each conversation is a `claude -p` process in stream-json mode, kept
alive between turns and resumed from its saved session when needed. Listens on 127.0.0.1
only, and every request must carry the token the app started it with.

Standard library only.
"""
import argparse
import base64
import hmac
import json
import os
import shlex
import shutil
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSION = 12  # Bridge.VERSION in the app: an app update replaces an older running bridge
HOME = os.path.expanduser('~')
CLAUDE_DIR = os.path.join(HOME, '.claude')
PROJECTS = os.path.join(CLAUDE_DIR, 'projects')
STATE_DIR = os.path.join(HOME, '.ccapp')
PID_FILE = os.path.join(STATE_DIR, 'bridge.pid')
LOCK_FILE = os.path.join(STATE_DIR, 'bridge.lock')
DEFAULT_CWD = os.path.join(HOME, 'work')
LOG_FILE = os.path.join(STATE_DIR, 'bridge.log')
LAUNCH_FILE = os.path.join(STATE_DIR, 'launch.json')
PERMISSION_MODES = ('bypassPermissions', 'acceptEdits', 'plan', 'default')
STARTED = time.time()  # tells the app a new bridge took over (turns of the old one are gone)
IDLE_SECONDS = 15 * 60  # an idle conversation's process is stopped; it resumes on the next message


class Hub:
    """Numbered events for the app; a reconnecting client asks for those after its last one."""

    def __init__(self, keep=4000):
        self.events = []
        self.seq = 0
        self.keep = keep
        self.cond = threading.Condition()

    def emit(self, chat, kind, data=None):
        with self.cond:
            self.seq += 1
            self.events.append({'seq': self.seq, 'chat': chat, 'kind': kind, 'data': data})
            if len(self.events) > self.keep:
                del self.events[:len(self.events) - self.keep]
            self.cond.notify_all()

    def after(self, seq, timeout):
        with self.cond:
            if self.seq <= seq:
                self.cond.wait(timeout)
            return [e for e in self.events if e['seq'] > seq]


hub = Hub()


class Launch:
    """How Claude Code is started, set in the app: permission mode, text appended to the system
    prompt and extra arguments. A change restarts idle conversations on their next message."""

    def __init__(self):
        self.gen = 0
        self.data = {'permissionMode': 'bypassPermissions', 'appendSystemPrompt': '', 'extraArgs': ''}
        try:
            with open(LAUNCH_FILE, encoding='utf-8') as f:
                self.data.update(json.load(f))
        except (OSError, ValueError):
            pass

    def set(self, data):
        new = dict(self.data)
        for key in ('permissionMode', 'appendSystemPrompt', 'extraArgs'):
            if key in data:
                new[key] = str(data[key] or '')
        if new['permissionMode'] not in PERMISSION_MODES:
            raise ValueError('unknown permission mode %s' % new['permissionMode'])
        shlex.split(new['extraArgs'])  # ValueError on bad quoting
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(LAUNCH_FILE, 'w', encoding='utf-8') as f:
            json.dump(new, f, ensure_ascii=False, indent=1)
        self.data = new
        self.gen += 1

    def args(self):
        args = ['--permission-mode', self.data['permissionMode']]
        if self.data['appendSystemPrompt'].strip():
            args += ['--append-system-prompt', self.data['appendSystemPrompt']]
        args += [os.path.expanduser(a) for a in shlex.split(self.data['extraArgs'])]
        return args


launch = Launch()


def memory_path(scope, cwd=None):
    """CLAUDE.md the app edits: the user's own (every conversation) or a working folder's."""
    if scope == 'global':
        return os.path.join(CLAUDE_DIR, 'CLAUDE.md')
    if scope == 'folder' and cwd:
        return os.path.join(os.path.expanduser(cwd), 'CLAUDE.md')
    raise ValueError('unknown scope')


def claude_path():
    return shutil.which('claude') or os.path.join(os.environ.get('PREFIX', '/usr'), 'bin', 'claude')


class Chat:
    def __init__(self, key):
        self.key = key
        self.session_id = None
        self.cwd = DEFAULT_CWD
        self.model = None
        self.effort = None
        self.proc = None
        self.running = False
        self.used = time.time()
        self.stderr = []
        self.lock = threading.Lock()
        self.stopping = None
        self.launch_gen = None
        self.last_out = time.time()
        self.quiet_logged = False

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def start(self):
        args = [claude_path(), '-p', '--input-format', 'stream-json', '--output-format', 'stream-json',
                '--verbose', '--include-partial-messages']
        if self.session_id:
            args += ['--resume', self.session_id]
        if self.model:
            args += ['--model', self.model]
        if self.effort:
            # Through --settings: CLAUDE_CODE_EFFORT_LEVEL in the user's settings would otherwise
            # override both --effort and /effort.
            args += ['--settings', json.dumps({'env': {'CLAUDE_CODE_EFFORT_LEVEL': self.effort}})]
        args += launch.args()
        self.launch_gen = launch.gen
        os.makedirs(self.cwd, exist_ok=True)
        self.stderr = []
        self.proc = subprocess.Popen(args, cwd=self.cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='replace',
                                     bufsize=1, start_new_session=True)
        proc = self.proc
        self.last_out = time.time()
        self.quiet_logged = False
        log('chat %s: claude pid %d started, %s, model %s, effort %s, folder %s' % (
            self.key[:8], proc.pid, 'resuming ' + self.session_id if self.session_id else 'new session',
            self.model or 'default', self.effort or 'default', self.cwd))
        threading.Thread(target=self.read_out, args=(proc,), daemon=True).start()
        threading.Thread(target=self.read_err, args=(proc,), daemon=True).start()

    def read_out(self, proc):
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            self.last_out = time.time()
            if self.quiet_logged:
                log('chat %s: output again' % self.key[:8])
                self.quiet_logged = False
            if msg.get('session_id'):
                self.session_id = msg['session_id']
            kind, sub = msg.get('type'), msg.get('subtype')
            if kind == 'system' and sub == 'api_retry':
                log('chat %s: API retry %s/%s in %sms: %s %s' % (
                    self.key[:8], msg.get('attempt'), msg.get('max_retries'), msg.get('retry_delay_ms'),
                    msg.get('error_status') or '', msg.get('error') or ''))
            elif kind == 'result':
                self.running = False
                self.used = time.time()
                log('chat %s: turn ended (%s%s), %.1fs, API %.1fs, %s turns' % (
                    self.key[:8], sub, ', error' if msg.get('is_error') else '',
                    (msg.get('duration_ms') or 0) / 1000, (msg.get('duration_api_ms') or 0) / 1000,
                    msg.get('num_turns')))
                if msg.get('is_error') and msg.get('result'):
                    log('chat %s: %s' % (self.key[:8], str(msg.get('result'))[:500]))
            elif kind == 'assistant':
                err = msg.get('error')
                if err:
                    log('chat %s: assistant error %s' % (self.key[:8], err))
            hub.emit(self.key, 'cc', msg)
        code = proc.wait()
        log('chat %s: claude pid %d exited with %s%s' % (
            self.key[:8], proc.pid, code, ', stderr:\n  ' + '\n  '.join(self.stderr[-12:]) if self.stderr else ''))
        if proc is self.proc:
            was_running = self.running
            self.running = False
            self.proc = None
            hub.emit(self.key, 'exit', {'code': code, 'running': was_running,
                                        'stderr': '\n'.join(self.stderr[-12:])})

    def read_err(self, proc):
        for line in proc.stderr:
            self.stderr.append(line.rstrip())
            del self.stderr[:-50]

    def send(self, content, cwd=None, model=None, session_id=None, effort=None):
        with self.lock:
            if session_id and not self.session_id:
                self.session_id = session_id
            if cwd and not self.alive():
                self.cwd = cwd
            if model != self.model or effort != self.effort or self.launch_gen != launch.gen:
                self.model, self.effort = model, effort
                if self.alive() and not self.running:
                    self.close()  # restarts with the new settings, resuming the session
            if not self.alive():
                self.start()
            self.running = True
            self.used = time.time()
            self.last_out = time.time()
            if isinstance(content, list):
                kinds = [b.get('type') for b in content if isinstance(b, dict)]
                log('chat %s: message sent (%d text, %d image)' % (self.key[:8], kinds.count('text'), kinds.count('image')))
            else:
                log('chat %s: message sent' % self.key[:8])
            line = json.dumps({'type': 'user', 'message': {'role': 'user', 'content': content}}, ensure_ascii=False)
            self.proc.stdin.write(line + '\n')
            self.proc.stdin.flush()
            hub.emit(self.key, 'state', {'running': True})

    def stop(self):
        with self.lock:
            if not self.alive() or not self.running:
                return
            log('chat %s: stop requested' % self.key[:8])
            request = {'type': 'control_request', 'request_id': 'stop-%d' % time.time(),
                       'request': {'subtype': 'interrupt'}}
            try:
                self.proc.stdin.write(json.dumps(request) + '\n')
                self.proc.stdin.flush()
            except OSError:
                pass
            proc = self.proc

        def force():
            time.sleep(5)
            if self.running and self.proc is proc:
                self.close()
        threading.Thread(target=force, daemon=True).start()

    def close(self):
        proc = self.proc
        if proc is None:
            return
        try:
            proc.stdin.close()
        except OSError:
            pass
        try:
            proc.wait(3)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except OSError:
                proc.kill()


chats = {}
chats_lock = threading.Lock()
seen_messages = []  # ids of messages already sent: the app may repeat a request whose answer it lost


def chat_for(key, session_id=None):
    """The conversation for the app's key. A session already running under another key (the app
    was restarted meanwhile) is taken over, so one session never has two processes."""
    with chats_lock:
        if key in chats:
            return chats[key]
        if session_id:
            for old_key, chat in list(chats.items()):
                if chat.session_id == session_id:
                    del chats[old_key]
                    chat.key = key
                    chats[key] = chat
                    return chat
        chats[key] = Chat(key)
        return chats[key]


def reaper():
    """Stops idle conversations, and logs a running turn with no output for a minute and the
    bridge itself having been paused (frozen by the system: it did not run for a while)."""
    tick = time.monotonic()
    while True:
        time.sleep(5)
        now = time.monotonic()
        if now - tick > 20:
            log('bridge was paused for %ds (frozen by the system?)' % (now - tick - 5))
        tick = now
        for chat in list(chats.values()):
            if chat.alive() and not chat.running and time.time() - chat.used > IDLE_SECONDS:
                log('chat %s: idle, stopping its claude' % chat.key[:8])
                chat.close()
            if chat.running and not chat.quiet_logged and time.time() - chat.last_out > 60:
                chat.quiet_logged = True
                log('chat %s: no output from claude for 60s (alive: %s)' % (chat.key[:8], chat.alive()))


# ---- Saved sessions (Claude Code's own transcripts) ----

def is_prompt_text(text):
    t = text.lstrip()
    return bool(t) and not t.startswith(('<system-reminder>', '<command-', '<local-command', '<bash-',
                                         '<user-memory', 'Caveat:', '[Request interrupted'))


def entry_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return '\n'.join(b.get('text', '') for b in content if isinstance(b, dict) and b.get('type') == 'text')
    return ''


_summary_cache = {}


def session_summary(path):
    st = os.stat(path)
    cached = _summary_cache.get(path)
    if cached and cached[0] == st.st_mtime:
        return cached[1]
    title, cwd, custom = None, None, None
    with open(path, encoding='utf-8', errors='replace') as f:
        for n, line in enumerate(f):
            if '"customTitle"' in line or '"custom-title"' in line:
                try:
                    custom = json.loads(line).get('customTitle') or custom
                except ValueError:
                    pass
            if title and cwd:
                if n > 400 and custom is None and st.st_size > 2_000_000:
                    break
                continue
            try:
                e = json.loads(line)
            except ValueError:
                continue
            cwd = cwd or e.get('cwd')
            if not title and e.get('type') == 'user' and not e.get('isMeta') and not e.get('isSidechain'):
                text = entry_text((e.get('message') or {}).get('content'))
                if is_prompt_text(text):
                    title = ' '.join(text.split())[:80]
    info = None
    if title:
        info = {'id': os.path.basename(path)[:-6], 'title': custom or title, 'cwd': cwd or '',
                'mtime': st.st_mtime, 'size': st.st_size}
    _summary_cache[path] = (st.st_mtime, info)
    return info


def session_files():
    if not os.path.isdir(PROJECTS):
        return []
    out = []
    for project in os.listdir(PROJECTS):
        d = os.path.join(PROJECTS, project)
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            if name.endswith('.jsonl'):
                out.append(os.path.join(d, name))
    return out


def find_session(session_id):
    for path in session_files():
        if os.path.basename(path) == session_id + '.jsonl':
            return path
    return None


def list_sessions():
    items = []
    for path in session_files():
        try:
            info = session_summary(path)
        except OSError:
            continue
        if info:
            items.append(info)
    items.sort(key=lambda i: -i['mtime'])
    for chat in chats.values():
        for item in items:
            if item['id'] == chat.session_id:
                item['running'] = chat.running
    return items[:300]


def tool_result_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for b in content:
            if isinstance(b, dict):
                if b.get('type') == 'text':
                    parts.append(b.get('text', ''))
                elif b.get('type') == 'image':
                    parts.append('[图片]')
        return '\n'.join(parts)
    return ''


def load_history(session_id):
    path = find_session(session_id)
    if not path:
        return None
    messages, tools, cwd, custom, skipped = [], {}, None, None, 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if not isinstance(e, dict):
                continue
            try:
                kind = e.get('type')
                if kind == 'custom-title' or e.get('customTitle'):
                    custom = e.get('customTitle') or custom
                if e.get('isSidechain') or e.get('isMeta'):
                    continue
                cwd = e.get('cwd') or cwd
                if kind == 'system' and e.get('subtype') == 'compact_boundary':
                    messages.append({'role': 'divider', 'text': '上下文已压缩'})
                    continue
                msg = e.get('message') or {}
                content = msg.get('content')
                if kind == 'user':
                    if isinstance(content, str):
                        content = [{'type': 'text', 'text': content}]
                    if not isinstance(content, list):
                        continue
                    text_parts, images = [], []
                    for b in content:
                        if not isinstance(b, dict):
                            continue
                        if b.get('type') == 'tool_result':
                            tool = tools.get(b.get('tool_use_id'))
                            if tool is not None:
                                tool['result'] = tool_result_text(b.get('content'))[:20000]
                                tool['isError'] = bool(b.get('is_error'))
                                tool['status'] = 'error' if tool['isError'] else 'done'
                        elif b.get('type') == 'text' and is_prompt_text(b.get('text', '')):
                            text_parts.append(b['text'])
                        elif b.get('type') == 'image':
                            src = b.get('source') or {}
                            if src.get('type') == 'base64' and len(src.get('data', '')) < 1_500_000:
                                images.append('data:%s;base64,%s' % (src.get('media_type', 'image/png'), src['data']))
                            else:
                                images.append(None)
                    if text_parts or images:
                        messages.append({'role': 'user', 'text': '\n'.join(text_parts), 'images': images})
                elif kind == 'assistant' and isinstance(content, list):
                    if not messages or messages[-1]['role'] != 'assistant':
                        messages.append({'role': 'assistant', 'blocks': []})
                    blocks = messages[-1]['blocks']
                    for b in content:
                        if not isinstance(b, dict):
                            continue
                        t = b.get('type')
                        if t == 'text' and b.get('text', '').strip():
                            blocks.append({'type': 'text', 'text': b['text']})
                        elif t == 'thinking' and b.get('thinking', '').strip():
                            blocks.append({'type': 'thinking', 'text': b['thinking']})
                        elif t == 'tool_use':
                            tool = {'type': 'tool', 'id': b.get('id'), 'name': b.get('name'),
                                    'input': b.get('input') or {}, 'status': 'done'}
                            tools[b.get('id')] = tool
                            blocks.append(tool)
            except Exception:  # an entry this bridge does not understand is left out
                if not skipped:
                    import traceback
                    log('history %s: skipped an entry\n%s' % (session_id, traceback.format_exc()))
                skipped += 1
    return {'id': session_id, 'cwd': cwd, 'title': custom, 'messages': messages}


def read_config():
    models, settings = [], {}
    try:
        with open(os.path.join(CLAUDE_DIR, 'settings.json'), encoding='utf-8') as f:
            settings = json.load(f)
    except (OSError, ValueError):
        pass
    if not isinstance(settings, dict):
        settings = {}
    env = settings.get('env') if isinstance(settings.get('env'), dict) else {}
    # Something to sign in with: a key or token in settings or the environment, a key helper, or a login.
    keys = ('ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_API_KEY', 'CLAUDE_CODE_OAUTH_TOKEN')
    auth = (any(env.get(k) or os.environ.get(k) for k in keys) or bool(settings.get('apiKeyHelper'))
            or os.path.exists(os.path.join(CLAUDE_DIR, '.credentials.json')))
    for key in ('ANTHROPIC_MODEL', 'ANTHROPIC_DEFAULT_OPUS_MODEL', 'ANTHROPIC_DEFAULT_SONNET_MODEL',
                'ANTHROPIC_DEFAULT_HAIKU_MODEL'):
        value = env.get(key)
        if value and value not in models:
            models.append(value)
    base = env.get('ANTHROPIC_BASE_URL', '')
    return {'models': models, 'defaultModel': env.get('ANTHROPIC_MODEL'), 'baseUrl': base, 'auth': auth}


def list_dirs(path):
    path = os.path.realpath(os.path.expanduser(path or DEFAULT_CWD))
    names = []
    try:
        for name in sorted(os.listdir(path), key=str.lower):
            if not name.startswith('.') and os.path.isdir(os.path.join(path, name)):
                names.append(name)
    except OSError as e:
        return {'path': path, 'parent': os.path.dirname(path), 'dirs': [], 'error': str(e)}
    return {'path': path, 'parent': os.path.dirname(path) if path != '/' else None, 'dirs': names, 'home': HOME}


# ---- HTTP ----

class Handler(BaseHTTPRequestHandler):
    server_version = 'ccbridge/%d' % VERSION
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def authorized(self):
        token = self.headers.get('X-Token', '')
        if hmac.compare_digest(token.encode(), self.server.token.encode()):
            return True
        self.reply(401, {'error': 'token'})
        return False

    def reply(self, status, data):
        self.status = status
        body = json.dumps(data, ensure_ascii=False).encode('utf-8', 'replace')  # lone surrogates
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return json.loads(self.rfile.read(n) or b'{}') if n else {}

    def logged(self, method, run):
        """Runs a request and logs it (not health checks), with its status and time."""
        path = self.path.split('?')[0]
        start = time.time()
        self.status = None
        try:
            run()
        finally:
            if path not in ('/health', '/events'):
                log('%s %s -> %s, %dms' % (method, path, self.status or 'no answer', (time.time() - start) * 1000))

    def do_GET(self):
        self.logged('GET', self.do_get)

    def do_POST(self):
        self.logged('POST', self.do_post)

    def do_get(self):
        if not self.authorized():
            return
        try:
            self.get()
        except ValueError as e:
            self.reply(400, {'error': str(e)})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            self.failed(e)

    def get(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == '/health':
            self.reply(200, {'ok': True, 'version': VERSION, 'started': STARTED, 'home': HOME, 'defaultCwd': DEFAULT_CWD,
                             'claude': shutil.which('claude')})
        elif url.path == '/events':
            self.events(int(q.get('since') or 0))
        elif url.path == '/sessions':
            self.reply(200, {'sessions': list_sessions()})
        elif url.path == '/history':
            data = load_history(q.get('id', ''))
            self.reply(200 if data else 404, data or {'error': 'not found'})
        elif url.path == '/config':
            self.reply(200, read_config())
        elif url.path == '/launch':
            self.reply(200, launch.data)
        elif url.path == '/memory':
            path = memory_path(q.get('scope'), q.get('cwd'))
            try:
                with open(path, encoding='utf-8') as f:
                    text = f.read()
            except OSError:
                text = ''
            self.reply(200, {'path': path, 'text': text})
        elif url.path == '/dirs':
            self.reply(200, list_dirs(q.get('path')))
        else:
            self.reply(404, {'error': 'not found'})

    def do_post(self):
        if not self.authorized():
            return
        try:
            data = self.body()
            self.post(urlparse(self.path).path, data)
        except (ValueError, KeyError, TypeError) as e:
            self.reply(400, {'error': 'bad request: %s' % e})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            self.failed(e)

    def failed(self, e):
        """An error the bridge did not expect: logged, and answered so the app can show it."""
        import traceback
        log('%s failed:\n%s' % (self.path.split('?')[0], traceback.format_exc()))
        try:
            self.reply(500, {'error': '%s: %s' % (type(e).__name__, e)})
        except OSError:
            pass

    def post(self, path, data):
        if path == '/send':
            mid = data.get('mid')
            with chats_lock:
                repeated = mid is not None and mid in seen_messages
                if mid is not None and not repeated:
                    seen_messages.append(mid)
                    del seen_messages[:-200]
            if repeated:
                return self.reply(200, {'ok': True, 'repeated': True})
            chat = chat_for(data['chat'], data.get('sessionId'))
            try:
                chat.send(data['content'], cwd=data.get('cwd'), model=data.get('model') or None,
                          session_id=data.get('sessionId'), effort=data.get('effort') or None)
            except (OSError, ValueError) as e:
                chat.running = False
                return self.reply(500, {'error': str(e)})
            self.reply(200, {'ok': True})
        elif path == '/launch':
            launch.set(data)
            self.reply(200, launch.data)
        elif path == '/memory':
            target = memory_path(data.get('scope'), data.get('cwd'))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, 'w', encoding='utf-8') as f:
                f.write(data.get('text') or '')
            launch.gen += 1  # Claude Code reads CLAUDE.md when it starts
            self.reply(200, {'ok': True, 'path': target})
        elif path == '/stop':
            chat_for(data['chat']).stop()
            self.reply(200, {'ok': True})
        elif path == '/delete':
            session_id = data.get('id', '')
            target = find_session(session_id) if session_id else None
            if target:
                os.remove(target)
                shutil.rmtree(target[:-6], ignore_errors=True)
            self.reply(200, {'ok': bool(target)})
        elif path == '/upload':
            folder = os.path.expanduser(data.get('cwd') or DEFAULT_CWD)
            name = os.path.basename(str(data.get('name') or 'file')).strip() or 'file'
            os.makedirs(folder, exist_ok=True)
            stem, ext = os.path.splitext(name)
            target, n = os.path.join(folder, name), 1
            while os.path.exists(target):  # never overwrite: name (1).ext, name (2).ext …
                target, n = os.path.join(folder, '%s (%d)%s' % (stem, n, ext)), n + 1
            try:
                with open(target, 'wb') as f:
                    f.write(base64.b64decode(data.get('data') or ''))
            except (OSError, ValueError) as e:
                return self.reply(400, {'error': str(e)})
            self.reply(200, {'ok': True, 'path': target})
        elif path == '/mkdir':
            try:
                os.makedirs(os.path.expanduser(data['path']), exist_ok=True)
                self.reply(200, {'ok': True})
            except OSError as e:
                self.reply(400, {'error': str(e)})
        else:
            self.reply(404, {'error': 'not found'})

    def events(self, since):
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        try:
            if since < 0:  # only what happens from now on
                since = hub.seq
            elif since > hub.seq:  # the bridge restarted: start over
                since = 0
            self.wfile.write(b': hello\n\n')
            self.wfile.flush()
            log('events: app connected')
            while True:
                batch = hub.after(since, 5)  # a ping at least every 5 s: the app can tell a live bridge
                if not batch:
                    self.wfile.write(b': ping\n\n')
                for e in batch:
                    since = e['seq']
                    self.wfile.write(b'data: ' + json.dumps(e, ensure_ascii=False).encode('utf-8', 'replace') + b'\n\n')
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            log('events: app gone')


def running_bridge():
    try:
        with open(PID_FILE) as f:
            pid = int(f.read().strip())
        return pid if pid != os.getpid() else None
    except (OSError, ValueError):
        return None


_lock = None


def replace_running_bridge():
    """Leaves exactly one bridge, the newest: it stops the one running and takes the lock that
    bridge held for its life, so two started at once end with one."""
    import fcntl
    global _lock
    os.makedirs(STATE_DIR, exist_ok=True)
    _lock = open(LOCK_FILE, 'a')
    deadline = time.time() + 12
    while True:
        old = running_bridge()
        if old:
            try:
                os.kill(old, signal.SIGTERM)
            except OSError:
                pass
        try:
            fcntl.flock(_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except OSError:
            if time.time() > deadline:
                log('exit pid %d: another bridge keeps the lock' % os.getpid())
                sys.exit(1)
            time.sleep(0.2)
    old = running_bridge()  # a bridge from before the lock
    if old:
        try:
            os.kill(old, signal.SIGTERM)
            for _ in range(30):
                time.sleep(0.1)
                os.kill(old, 0)
        except OSError:
            pass
    with open(PID_FILE, 'w') as f:
        f.write(str(os.getpid()))


def log(text):
    try:
        if os.path.getsize(LOG_FILE) > 200_000:
            os.replace(LOG_FILE, LOG_FILE + '.1')
    except OSError:
        pass
    try:
        with open(LOG_FILE, 'a') as f:
            f.write(time.strftime('%Y-%m-%d %H:%M:%S ') + text + '\n')
    except OSError:
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--token', required=True)
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--reason', default='')
    args = parser.parse_args()
    replace_running_bridge()
    log('start pid %d version %d (%s)' % (os.getpid(), VERSION, args.reason or 'started by hand'))
    os.makedirs(DEFAULT_CWD, exist_ok=True)

    def shutdown(signum, _frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        log('stop pid %d: signal %d' % (os.getpid(), signum))
        procs = [chat.proc for chat in list(chats.values()) if chat.proc is not None]
        for proc in procs:
            try:
                proc.stdin.close()
            except OSError:
                pass
        end = time.time() + 3
        for proc in procs:
            try:
                proc.wait(max(0.1, end - time.time()))
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except OSError:
                    pass
        os._exit(0)
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGHUP, signal.SIG_IGN)

    def crashed(kind, value, tb):
        import traceback
        log('crash pid %d:\n%s' % (os.getpid(), ''.join(traceback.format_exception(kind, value, tb))))
    sys.excepthook = crashed
    threading.excepthook = lambda a: crashed(a.exc_type, a.exc_value, a.exc_traceback)

    server = None
    for _ in range(50):
        try:
            server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
            break
        except OSError:
            time.sleep(0.1)
    if server is None:
        log('exit pid %d: port %d busy' % (os.getpid(), args.port))
        sys.exit('port %d busy' % args.port)
    server.daemon_threads = True
    server.token = args.token
    threading.Thread(target=reaper, daemon=True).start()
    server.serve_forever()


if __name__ == '__main__':
    main()
