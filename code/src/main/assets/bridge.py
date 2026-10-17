"""Claude Code bridge for the Claude Code app.

Runs in Termux (started by the app through Termux's RUN_COMMAND service) and lets the app
talk to Claude Code: each conversation is a `claude -p` process in stream-json mode, kept
alive between turns and resumed from its saved session when needed. Listens on 127.0.0.1
only, and every request must carry the token the app started it with.

Standard library only.
"""
import argparse
import base64
import hmac
import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSION = 4  # Bridge.VERSION in the app: an app update replaces an older running bridge
HOME = os.path.expanduser('~')
CLAUDE_DIR = os.path.join(HOME, '.claude')
PROJECTS = os.path.join(CLAUDE_DIR, 'projects')
STATE_DIR = os.path.join(HOME, '.ccapp')
PID_FILE = os.path.join(STATE_DIR, 'bridge.pid')
DEFAULT_CWD = os.path.join(HOME, 'work')
LOG_FILE = os.path.join(STATE_DIR, 'bridge.log')
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

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def start(self):
        args = [claude_path(), '-p', '--input-format', 'stream-json', '--output-format', 'stream-json',
                '--verbose', '--include-partial-messages', '--permission-mode', 'bypassPermissions']
        if self.session_id:
            args += ['--resume', self.session_id]
        if self.model:
            args += ['--model', self.model]
        if self.effort:
            # Through --settings: CLAUDE_CODE_EFFORT_LEVEL in the user's settings would otherwise
            # override both --effort and /effort.
            args += ['--settings', json.dumps({'env': {'CLAUDE_CODE_EFFORT_LEVEL': self.effort}})]
        os.makedirs(self.cwd, exist_ok=True)
        self.stderr = []
        self.proc = subprocess.Popen(args, cwd=self.cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='replace',
                                     bufsize=1, start_new_session=True)
        proc = self.proc
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
            if msg.get('session_id'):
                self.session_id = msg['session_id']
            if msg.get('type') == 'result':
                self.running = False
                self.used = time.time()
            hub.emit(self.key, 'cc', msg)
        code = proc.wait()
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
            if model != self.model or effort != self.effort:
                self.model, self.effort = model, effort
                if self.alive() and not self.running:
                    self.close()  # restarts with the new model or effort, resuming the session
            if not self.alive():
                self.start()
            self.running = True
            self.used = time.time()
            line = json.dumps({'type': 'user', 'message': {'role': 'user', 'content': content}}, ensure_ascii=False)
            self.proc.stdin.write(line + '\n')
            self.proc.stdin.flush()
            hub.emit(self.key, 'state', {'running': True})

    def stop(self):
        with self.lock:
            if not self.alive() or not self.running:
                return
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


_awake = False


def wake(on):
    """Termux's wake lock, taken when the bridge starts and never released (a replaced bridge
    releasing it could undo its successor's). Termux asks once to be left out of battery
    optimization when it is first taken."""
    global _awake
    if on == _awake:
        return
    command = shutil.which('termux-wake-lock' if on else 'termux-wake-unlock')
    if command:
        try:
            subprocess.Popen([command], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL)
            _awake = on
        except OSError:
            pass


def reaper():
    while True:
        time.sleep(60)
        for chat in list(chats.values()):
            if chat.alive() and not chat.running and time.time() - chat.used > IDLE_SECONDS:
                chat.close()


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
    messages, tools, cwd, custom = [], {}, None, None
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            try:
                e = json.loads(line)
            except ValueError:
                continue
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
    return {'id': session_id, 'cwd': cwd, 'title': custom, 'messages': messages}


def read_config():
    models, env = [], {}
    try:
        with open(os.path.join(CLAUDE_DIR, 'settings.json'), encoding='utf-8') as f:
            env = json.load(f).get('env') or {}
    except (OSError, ValueError):
        pass
    for key in ('ANTHROPIC_MODEL', 'ANTHROPIC_DEFAULT_OPUS_MODEL', 'ANTHROPIC_DEFAULT_SONNET_MODEL',
                'ANTHROPIC_DEFAULT_HAIKU_MODEL'):
        value = env.get(key)
        if value and value not in models:
            models.append(value)
    base = env.get('ANTHROPIC_BASE_URL', '')
    if 'deepseek' in base and 'deepseek-v4-pro' not in models:
        models.append('deepseek-v4-pro')
    return {'models': models, 'defaultModel': env.get('ANTHROPIC_MODEL'), 'baseUrl': base}


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
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return json.loads(self.rfile.read(n) or b'{}') if n else {}

    def do_GET(self):
        if not self.authorized():
            return
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
        elif url.path == '/dirs':
            self.reply(200, list_dirs(q.get('path')))
        else:
            self.reply(404, {'error': 'not found'})

    def do_POST(self):
        if not self.authorized():
            return
        try:
            data = self.body()
            self.post(urlparse(self.path).path, data)
        except (ValueError, KeyError, TypeError) as e:
            self.reply(400, {'error': 'bad request: %s' % e})

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
            while True:
                batch = hub.after(since, 15)
                if not batch:
                    self.wfile.write(b': ping\n\n')
                for e in batch:
                    since = e['seq']
                    self.wfile.write(b'data: ' + json.dumps(e, ensure_ascii=False).encode() + b'\n\n')
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass


def replace_running_bridge():
    os.makedirs(STATE_DIR, exist_ok=True)
    try:
        with open(PID_FILE) as f:
            old = int(f.read().strip())
        if old != os.getpid():
            os.kill(old, signal.SIGTERM)
            for _ in range(30):
                time.sleep(0.1)
                os.kill(old, 0)
    except (OSError, ValueError):
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
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    replace_running_bridge()
    log('start pid %d version %d' % (os.getpid(), VERSION))
    os.makedirs(DEFAULT_CWD, exist_ok=True)
    wake(True)

    def shutdown(signum, _frame):
        log('stop pid %d: signal %d' % (os.getpid(), signum))
        for chat in list(chats.values()):
            chat.close()
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
        sys.exit('port %d busy' % args.port)
    server.daemon_threads = True
    server.token = args.token
    threading.Thread(target=reaper, daemon=True).start()
    server.serve_forever()


if __name__ == '__main__':
    main()
