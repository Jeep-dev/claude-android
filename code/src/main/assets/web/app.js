'use strict';
/* Claude Code app interface. Talks to the bridge in Termux through window.Native (MainActivity). */
(() => {
  // ---------- icons ----------
  const P = {
    sidebar: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16"/>',
    compose: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/>',
    folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>',
    chevronDown: '<path d="m6 9 6 6 6-6"/>',
    chevronRight: '<path d="m9 6 6 6-6 6"/>',
    arrowUp: '<path d="M12 19V5M5 12l7-7 7 7"/>',
    arrowDown: '<path d="M12 5v14M19 12l-7 7-7-7"/>',
    stop: '<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>',
    terminal: '<path d="m5 8 4 4-4 4M12 16h7"/>',
    file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"/><path d="M14 3v5h5"/>',
    filePlus: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"/><path d="M14 3v5h5M12 12v6M9 15h6"/>',
    pencil: '<path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
    list: '<path d="M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01"/>',
    spark: '<path d="M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4"/>',
    tool: '<path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4Z"/>',
    check: '<path d="m5 12 5 5L20 7"/>',
    x: '<path d="M18 6 6 18M6 6l12 12"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-2.6-6.4L21 8"/><path d="M21 3v5h-5"/>',
    brain: '<path d="M12 5a3 3 0 0 0-5.9.6A3 3 0 0 0 4 11a3 3 0 0 0 1 5.6A3 3 0 0 0 12 18Z"/><path d="M12 5a3 3 0 0 1 5.9.6A3 3 0 0 1 20 11a3 3 0 0 1-1 5.6A3 3 0 0 1 12 18Z"/><path d="M12 5v13"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="9" cy="10" r="2"/><path d="m21 16-5-5-9 9"/>',
    up: '<path d="m6 15 6-6 6 6"/>',
    termuxApp: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="m7 10 3 2-3 2M12 15h5"/>',
  };
  const MASCOT = '<svg class="ic" viewBox="0 0 13 8" shape-rendering="crispEdges"><path fill="#D97757" d="M2 0h9v1H2zM2 1h9v1H2zM0 2h13v2H0zM2 4h9v2H2zM2 6h1v2H2zM5 6h1v2H5zM7 6h1v2H7zM10 6h1v2h-1z"/><path fill="#1F1E1D" d="M3 1h1v1H3zM9 1h1v1H9z"/></svg>';
  const icon = name => name === 'mascot' ? MASCOT
    : `<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${P[name] || P.tool}</svg>`;
  const paintIcons = root => root.querySelectorAll('[data-icon]').forEach(el => {
    if (!el.firstChild) el.innerHTML = icon(el.dataset.icon);
  });

  // ---------- helpers ----------
  const $ = id => document.getElementById(id);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const base = p => String(p || '').replace(/\/+$/, '').split('/').pop() || '/';
  const tilde = p => (S.home && p && p.startsWith(S.home) ? '~' + p.slice(S.home.length) : p || '');
  const firstLine = s => String(s || '').split('\n')[0];
  const store = {
    get(k, d) { try { const v = localStorage.getItem('cc.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('cc.' + k, JSON.stringify(v)); } catch (e) { /* private mode */ } },
  };
  const fmtTokens = n => n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'k' : String(n);
  const shortModel = m => m ? m.replace(/\[1m\]$/i, '') : '默认模型';

  // ---------- native bridge ----------
  const native = window.Native;
  const waiting = new Map();
  let reqNo = 0;
  function call(method, path, body) {
    return new Promise(resolve => {
      const id = 'q' + (++reqNo);
      waiting.set(id, resolve);
      native.request(id, method, path, body == null ? null : JSON.stringify(body));
    });
  }
  function nativeAsync(fn) {
    return new Promise(resolve => {
      const id = 'q' + (++reqNo);
      waiting.set(id, resolve);
      native[fn](id);
    });
  }

  // ---------- state ----------
  const S = {
    linked: false, connecting: false, setup: null,
    home: '', defaultCwd: '', cwd: store.get('cwd', ''), model: store.get('model', ''), models: [],
    sessions: [], chats: new Map(), bySession: new Map(), current: null,
    stick: true, search: '',
  };
  const VERBS = ['思考中', '琢磨中', '推敲中', '酝酿中', '构思中', '运转中'];
  const GLYPHS = ['·', '✢', '✳', '✶', '✻', '✽', '✻', '✶', '✳', '✢'];

  function newChat() {
    const key = 'n-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    const chat = { key, sessionId: null, title: '', cwd: S.cwd || S.defaultCwd, messages: [], running: false, loaded: true, draft: '', images: [] };
    S.chats.set(key, chat);
    return chat;
  }
  const cur = () => S.chats.get(S.current);

  // ---------- markdown ----------
  const renderer = new marked.Renderer();
  renderer.code = (code, lang) => {
    const language = (lang || '').trim().split(/\s+/)[0];
    let html;
    try {
      html = language && hljs.getLanguage(language) ? hljs.highlight(code, { language }).value : esc(code);
    } catch (e) { html = esc(code); }
    return `<div class="code"><div class="code-head"><span>${esc(language || '代码')}</span><button class="code-copy" data-copy>${icon('copy')}<span>复制</span></button></div><pre><code class="hljs">${html}</code></pre></div>`;
  };
  renderer.table = (header, body) => `<div class="table-wrap"><table><thead>${header}</thead><tbody>${body}</tbody></table></div>`;
  marked.use({ renderer, gfm: true, breaks: false });
  const md = text => DOMPurify.sanitize(marked.parse(text || ''), { ADD_ATTR: ['data-copy'] });

  // ---------- tools ----------
  function toolInfo(b) {
    const i = b.input || {};
    switch (b.name) {
      case 'Bash': return { icon: 'terminal', label: '运行命令', detail: i.description || firstLine(i.command) };
      case 'Read': return { icon: 'file', label: '读取', detail: base(i.file_path) };
      case 'Write': return { icon: 'filePlus', label: '写入', detail: base(i.file_path) };
      case 'Edit': case 'MultiEdit': return { icon: 'pencil', label: '编辑', detail: base(i.file_path) };
      case 'NotebookEdit': return { icon: 'pencil', label: '编辑笔记本', detail: base(i.notebook_path) };
      case 'Glob': return { icon: 'search', label: '查找文件', detail: i.pattern };
      case 'Grep': return { icon: 'search', label: '搜索', detail: i.pattern };
      case 'WebFetch': return { icon: 'globe', label: '打开网页', detail: (String(i.url || '').match(/^\w+:\/\/([^/]+)/) || [])[1] || i.url };
      case 'WebSearch': return { icon: 'globe', label: '搜索网络', detail: i.query };
      case 'Task': case 'Agent': return { icon: 'spark', label: '子任务', detail: i.description };
      case 'TodoWrite': return { icon: 'list', label: '更新待办', detail: '' };
      case 'Skill': return { icon: 'spark', label: '使用技能', detail: i.skill || i.command };
      default: return { icon: 'tool', label: b.name || '工具', detail: '' };
    }
  }
  const clip = (s, n) => { s = String(s ?? ''); return s.length > n ? s.slice(0, n) + '\n…' : s; };
  function io(label, text, cls = '') {
    return `<div class="io ${cls}"><div class="io-label">${label}</div><pre>${esc(clip(text, 6000))}</pre></div>`;
  }
  function diff(oldText, newText) {
    const lines = [];
    String(oldText || '').split('\n').forEach(l => lines.push(`<span class="del">- ${esc(l)}</span>`));
    String(newText || '').split('\n').forEach(l => lines.push(`<span class="add">+ ${esc(l)}</span>`));
    return `<div class="io diff"><pre>${lines.slice(0, 400).join('')}</pre></div>`;
  }
  function toolBody(b) {
    const i = b.input || {};
    const parts = [];
    if (b.name === 'Bash') parts.push(io('命令', i.command));
    else if (b.name === 'Edit') parts.push(diff(i.old_string, i.new_string));
    else if (b.name === 'MultiEdit') (i.edits || []).forEach(e => parts.push(diff(e.old_string, e.new_string)));
    else if (b.name === 'Write') parts.push(io(esc(i.file_path || '内容'), i.content));
    else if (b.name !== 'Read' && b.name !== 'TodoWrite') parts.push(io('参数', JSON.stringify(i, null, 2)));
    if (b.result != null && b.result !== '') parts.push(io(b.isError ? '错误' : '结果', b.result, b.isError ? 'err' : ''));
    return parts.join('');
  }
  function todos(b) {
    const list = (b.input || {}).todos || [];
    if (!list.length) return '';
    return `<div class="todos">${list.map(t => `<div class="todo ${esc(t.status)}"><span class="box">${t.status === 'completed' ? icon('check') : ''}</span><span>${esc(t.content)}</span></div>`).join('')}</div>`;
  }

  // ---------- rendering ----------
  const thread = $('thread');
  const scroller = $('scroller');

  function blockHtml(b, live) {
    if (b.type === 'text') return `<div class="prose">${md(b.text)}</div>`;
    if (b.type === 'thinking') {
      const secs = b.ms ? `${Math.max(1, Math.round(b.ms / 1000))} 秒` : '';
      const head = live && b.streaming ? '思考中…' : `已思考${secs ? ' ' + secs : ''}`;
      return `<div class="thinking ${b.open ? 'open' : ''}"><button class="thinking-head" data-toggle>${icon('brain')}<span>${head}</span><span class="chev">${icon('chevronRight')}</span></button>${b.open ? `<div class="thinking-body">${esc(b.text)}</div>` : ''}</div>`;
    }
    if (b.type === 'tool') {
      const info = toolInfo(b);
      const state = b.status === 'running' ? '<span class="spinner"></span>' : b.status === 'error' ? icon('x') : icon('check');
      const body = b.open ? `<div class="tool-body">${toolBody(b)}</div>` : '';
      return `<div class="tool ${b.status} ${b.open ? 'open' : ''}"><button class="tool-head" data-toggle><span class="tool-icon">${icon(info.icon)}</span><span class="tool-text"><span class="tool-label">${esc(info.label)}</span><span class="tool-detail">${esc(info.detail || '')}</span></span><span class="tool-state">${state}</span><span class="chev">${icon('chevronRight')}</span></button>${b.name === 'TodoWrite' ? todos(b) : ''}${body}</div>`;
    }
    if (b.type === 'error') return `<div class="err-card"><b>${esc(b.title || '出错了')}</b>${esc(b.text || '')}</div>`;
    if (b.type === 'stopped') return '<div class="stopped">已停止</div>';
    return '';
  }

  function msgEl(m, chat, last) {
    const el = document.createElement('div');
    if (m.role === 'divider') {
      el.className = 'divider';
      el.textContent = m.text;
      return el;
    }
    el.className = 'msg ' + m.role;
    if (m.role === 'user') {
      const imgs = (m.images || []).map(src => src ? `<img src="${esc(src)}" alt="">` : '<div class="missing">图片</div>').join('');
      el.innerHTML = (imgs ? `<div class="user-images">${imgs}</div>` : '') + (m.text ? `<div class="bubble">${esc(m.text)}</div>` : '');
      return el;
    }
    const live = last && chat.running;
    m.blocks.forEach((b, i) => {
      const wrap = document.createElement('div');
      wrap.innerHTML = blockHtml(b, live);
      const node = wrap.firstElementChild;
      if (!node) return;
      node.dataset.block = i;
      el.appendChild(node);
    });
    if (live) el.appendChild(statusEl(chat));
    else if (m.done) {
      const foot = document.createElement('div');
      foot.className = 'msg-foot';
      const usage = m.usage ? `上下文 ${fmtTokens(m.usage.context)} · 输出 ${fmtTokens(m.usage.output)}${m.seconds ? ` · ${m.seconds} 秒` : ''}` : '';
      foot.innerHTML = `<button class="icon-btn" data-copy-msg aria-label="复制">${icon('copy')}</button><span class="usage">${usage}</span>`;
      if (m.blocks.some(b => b.type === 'text')) el.appendChild(foot);
    }
    return el;
  }

  function statusEl(chat) {
    const el = document.createElement('div');
    el.className = 'status';
    el.innerHTML = `<span class="glyph">${GLYPHS[0]}</span><span class="verb">${chat.verb || VERBS[0]}…</span><span class="elapsed"></span>`;
    return el;
  }

  function emptyEl(chat) {
    const el = document.createElement('div');
    if (S.setup) return setupEl();
    const h = new Date().getHours();
    const hello = h < 5 ? '夜深了' : h < 11 ? '早上好' : h < 13 ? '中午好' : h < 18 ? '下午好' : '晚上好';
    el.className = 'hero';
    el.innerHTML = `<div class="hero-mark">${MASCOT}</div><h1>${hello}</h1><p>${S.linked ? `在 <b>${esc(base(chat.cwd))}</b> 里，想做点什么？` : '正在连接 Termux 里的 Claude Code…'}</p>
      <div class="suggestions">${['看看这个文件夹里有什么', '帮我写一个 Python 脚本', '解释一下最近的报错'].map(s => `<button class="suggestion">${s}</button>`).join('')}</div>`;
    return el;
  }

  function setupEl() {
    const info = JSON.parse(native.info() || '{}');
    const reason = S.setup;
    const el = document.createElement('div');
    el.className = 'setup';
    const prop = 'mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings';
    const steps = [
      { done: info.termux, title: '安装 Termux 和 Claude Code', text: '需要 F-Droid 或 GitHub 版的 Termux，并已在里面装好 Claude Code（能运行 claude）和 Python：', cmd: 'pkg install -y python' },
      { done: reason !== 'timeout' && reason !== 'start' && info.termux, title: '允许其他 App 调用 Termux', text: '在 Termux 里运行一次：', cmd: prop, open: true },
      { done: info.permission, title: '授权本 App 在 Termux 中运行命令', text: '用来在后台启动 Claude Code 的桥接程序。', perm: !info.permission },
    ];
    const why = { termux: '没有找到 Termux。', permission: '还没有授权在 Termux 中运行命令。', timeout: 'Termux 没有响应：可能还没允许其他 App 调用，或者没装 Python。', start: '无法启动 Termux 的命令服务。' }[reason] || '';
    el.innerHTML = `<div class="hero-mark">${MASCOT}</div><h2>连接 Termux</h2><p>Claude Code 在 Termux 里运行，这个 App 是它的界面。${esc(why)}</p>
      ${steps.map((s, i) => `<div class="step ${s.done ? 'done' : ''}"><span class="step-no">${s.done ? icon('check') : i + 1}</span><div class="step-body"><div class="step-title">${s.title}</div><div class="step-text">${s.text}</div>
        ${s.cmd ? `<div class="cmd"><code>${esc(s.cmd)}</code><button data-copy-text="${esc(s.cmd)}">复制</button></div>` : ''}
        ${s.open ? `<div class="actions"><button class="btn" data-act="termux">${icon('termuxApp')}打开 Termux</button></div>` : ''}
        ${s.perm ? `<div class="actions"><button class="btn accent" data-act="perm">授权</button></div>` : ''}</div></div>`).join('')}
      <div class="actions" style="margin-top:18px"><button class="btn primary" data-act="retry">${icon('refresh')}重新连接</button></div>`;
    return el;
  }

  let renderQueued = false;
  function render() {
    if (renderQueued) return;
    renderQueued = true;
    requestAnimationFrame(() => { renderQueued = false; renderNow(); });
  }

  let shown = { key: null, count: 0 };
  function renderNow() {
    const chat = cur();
    if (!chat) return;
    const msgs = chat.messages;
    if (shown.key !== chat.key || !msgs.length || shown.empty || shown.count > msgs.length) {
      thread.innerHTML = '';
      if (!msgs.length) thread.appendChild(emptyEl(chat));
      shown = { key: chat.key, count: 0, empty: !msgs.length };
      msgs.forEach(m => { m.dirty = true; m.el = null; });
    }
    msgs.forEach((m, i) => {
      if (!m.dirty && m.el) return;
      const el = msgEl(m, chat, i === msgs.length - 1);
      if (m.el && m.el.parentNode) m.el.replaceWith(el); else thread.appendChild(el);
      m.el = el;
      m.dirty = false;
    });
    shown.count = msgs.length;
    if (chat.running && (!msgs.length || msgs[msgs.length - 1].role !== 'assistant')) {
      let st = thread.querySelector(':scope > .pending');
      if (!st) {
        st = document.createElement('div');
        st.className = 'msg assistant pending';
        st.appendChild(statusEl(chat));
        thread.appendChild(st);
      }
    } else {
      thread.querySelectorAll(':scope > .pending').forEach(n => n.remove());
    }
    tickStatus();
    if (S.stick) scroller.scrollTop = scroller.scrollHeight;
    updateChrome();
  }

  function tickStatus() {
    const chat = cur();
    if (!chat || !chat.running) return;
    const n = Math.floor(Date.now() / 120);
    const secs = Math.floor((Date.now() - (chat.startedAt || Date.now())) / 1000);
    thread.querySelectorAll('.status').forEach(el => {
      el.querySelector('.glyph').textContent = GLYPHS[n % GLYPHS.length];
      el.querySelector('.verb').textContent = (chat.verb || VERBS[0]) + '…';
      el.querySelector('.elapsed').textContent = secs ? `${secs} 秒` : '';
    });
  }
  setInterval(tickStatus, 120);

  function updateChrome() {
    const chat = cur();
    $('title').textContent = chat.title || '新对话';
    $('subtitle').textContent = chat.messages.length ? `${base(chat.cwd)} · ${shortModel(chat.model || S.model || S.defaultModel)}` : '';
    $('folderName').textContent = base(chat.cwd || S.defaultCwd || '~');
    $('folderBtn').classList.toggle('locked', !!chat.sessionId);
    $('modelName').textContent = shortModel(S.model || S.defaultModel);
    const send = $('sendBtn');
    send.classList.toggle('stop', chat.running);
    send.innerHTML = icon(chat.running ? 'stop' : 'arrowUp');
    send.setAttribute('aria-label', chat.running ? '停止' : '发送');
    send.disabled = !chat.running && (!S.linked || (!$('input').value.trim() && !chat.images.length));
    renderAttachments();
  }

  // ---------- sessions list ----------
  function groupOf(t) {
    const d = new Date(t * 1000), now = new Date();
    const day = 86400000;
    const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    if (d >= start) return '今天';
    if (d >= start - day) return '昨天';
    if (d >= start - 7 * day) return '过去 7 天';
    if (d >= start - 30 * day) return '过去 30 天';
    return '更早';
  }
  function renderSessions() {
    const box = $('sessions');
    const q = S.search.trim().toLowerCase();
    const items = S.sessions.filter(s => !q || s.title.toLowerCase().includes(q) || (s.cwd || '').toLowerCase().includes(q));
    if (!items.length) {
      box.innerHTML = `<div class="sessions-empty">${S.linked ? (q ? '没有匹配的对话' : '还没有对话') : '连接后显示历史对话'}</div>`;
      return;
    }
    const active = cur() && cur().sessionId;
    let html = '', group = '';
    items.forEach(s => {
      const g = groupOf(s.mtime);
      if (g !== group) { group = g; html += `<div class="group-label">${g}</div>`; }
      const chat = S.chats.get(S.bySession.get(s.id) || s.id);
      const live = chat ? chat.running : s.running;
      html += `<button class="session ${s.id === active ? 'active' : ''}" data-session="${esc(s.id)}"><div class="session-title">${live ? '<span class="live-dot"></span>' : ''}<span>${esc(s.title)}</span></div><div class="session-meta">${esc(base(s.cwd))}</div></button>`;
    });
    box.innerHTML = html;
  }
  async function loadSessions() {
    if (!S.linked) return renderSessions();
    const r = await call('GET', '/sessions');
    if (r.status === 200) S.sessions = r.data.sessions || [];
    renderSessions();
  }

  async function openSession(id) {
    closeDrawer();
    const key = S.bySession.get(id) || id;
    let chat = S.chats.get(key);
    if (!chat) {
      const s = S.sessions.find(x => x.id === id) || {};
      chat = { key, sessionId: id, title: s.title || '', cwd: s.cwd || S.defaultCwd, messages: [], running: false, loaded: false, draft: '', images: [] };
      S.chats.set(key, chat);
      S.bySession.set(id, key);
    }
    if (!chat.title) chat.title = (S.sessions.find(x => x.id === id) || {}).title || '';
    switchTo(chat.key);
    if (!chat.loaded) {
      chat.loaded = true;
      const r = await call('GET', '/history?id=' + encodeURIComponent(id));
      if (r.status === 200) {
        // A reply still running continues below what the transcript holds so far.
        chat.messages = r.data.messages.map(m => m.role === 'assistant' ? { ...m, done: true } : m);
        chat.cwd = r.data.cwd || chat.cwd;
        if (r.data.title) chat.title = r.data.title;
        if (S.current === chat.key) { shown.key = null; S.stick = true; render(); }
      } else {
        toast('读取对话失败');
      }
    }
  }

  function switchTo(key) {
    const prev = cur();
    if (prev) { prev.draft = $('input').value; }
    S.current = key;
    const chat = cur();
    $('input').value = chat.draft || '';
    autosize();
    shown.key = null;
    S.stick = true;
    render();
    renderSessions();
  }

  // ---------- events from the bridge ----------
  // A conversation not open in this app yet (it was started before the app was): its history
  // loads from the transcript when it is opened.
  function chatForEvent(ev) {
    let chat = S.chats.get(ev.chat);
    if (chat) return chat;
    const sid = (ev.data && ev.data.session_id) || (ev.chat.startsWith('n-') ? null : ev.chat);
    if (sid && S.bySession.has(sid)) return S.chats.get(S.bySession.get(sid));
    chat = { key: ev.chat, sessionId: sid, title: '', cwd: S.defaultCwd, messages: [], running: false, loaded: false, draft: '', images: [] };
    S.chats.set(ev.chat, chat);
    if (sid) S.bySession.set(sid, ev.chat);
    return chat;
  }
  function lastAssistant(chat, create) {
    const last = chat.messages[chat.messages.length - 1];
    if (last && last.role === 'assistant' && !last.done) return last;
    if (!create) return null;
    const m = { role: 'assistant', blocks: [], idx: {}, seen: new Set() };
    chat.messages.push(m);
    return m;
  }
  function findTool(chat, id) {
    for (let i = chat.messages.length - 1; i >= 0; i--) {
      const m = chat.messages[i];
      if (m.role !== 'assistant') continue;
      const b = m.blocks.find(x => x.type === 'tool' && x.id === id);
      if (b) return [m, b];
    }
    return [null, null];
  }
  function resultText(content) {
    if (typeof content === 'string') return content;
    if (Array.isArray(content)) return content.map(c => c.type === 'text' ? c.text : c.type === 'image' ? '[图片]' : '').join('\n');
    return '';
  }

  function onCc(chat, d) {
    const t = d.type;
    if (t === 'system' && d.subtype === 'init') {
      if (d.session_id && chat.sessionId !== d.session_id) {
        chat.sessionId = d.session_id;
        S.bySession.set(d.session_id, chat.key);
      }
      chat.model = d.model || chat.model;
      return;
    }
    if (t === 'system' && d.subtype === 'compact_boundary') {
      chat.messages.push({ role: 'divider', text: '上下文已压缩', dirty: true });
      return;
    }
    if (t === 'stream_event') {
      const e = d.event || {};
      if (d.parent_tool_use_id) return; // inside a sub-agent: shown as its Task step
      const m = lastAssistant(chat, true);
      if (e.type === 'message_start') {
        m.idx = {};
        if (e.message && e.message.id) m.seen.add(e.message.id);
      } else if (e.type === 'content_block_start') {
        const cb = e.content_block || {};
        let b = null;
        if (cb.type === 'text') b = { type: 'text', text: cb.text || '' };
        else if (cb.type === 'thinking') { b = { type: 'thinking', text: '', streaming: true, t0: Date.now() }; chat.verb = '思考中'; }
        else if (cb.type === 'tool_use') { b = { type: 'tool', id: cb.id, name: cb.name, input: {}, partial: '', status: 'running' }; chat.verb = '运行中'; }
        if (b) { m.blocks.push(b); m.idx[e.index] = b; }
      } else if (e.type === 'content_block_delta') {
        const b = m.idx[e.index];
        const dl = e.delta || {};
        if (!b) return;
        if (dl.type === 'text_delta') { b.text += dl.text; chat.verb = '生成中'; }
        else if (dl.type === 'thinking_delta') b.text += dl.thinking;
        else if (dl.type === 'input_json_delta') {
          b.partial += dl.partial_json;
          try { b.input = JSON.parse(b.partial); } catch (err) { /* incomplete */ }
        }
      } else if (e.type === 'content_block_stop') {
        const b = m.idx[e.index];
        if (b && b.type === 'thinking') { b.streaming = false; b.ms = Date.now() - b.t0; }
      }
      m.dirty = true;
      return;
    }
    if (t === 'assistant') {
      if (d.parent_tool_use_id) return;
      const msg = d.message || {};
      const m = lastAssistant(chat, true);
      const streamed = m.seen.has(msg.id);
      (msg.content || []).forEach(c => {
        if (c.type === 'tool_use') {
          const [, b] = findTool(chat, c.id);
          if (b) b.input = c.input || b.input;
          else m.blocks.push({ type: 'tool', id: c.id, name: c.name, input: c.input || {}, status: 'running' });
        } else if (!streamed) {
          if (c.type === 'text' && c.text) m.blocks.push({ type: 'text', text: c.text });
          if (c.type === 'thinking' && c.thinking) m.blocks.push({ type: 'thinking', text: c.thinking });
        }
      });
      m.dirty = true;
      return;
    }
    if (t === 'user') {
      if (d.parent_tool_use_id) return;
      const content = (d.message || {}).content;
      if (!Array.isArray(content)) return;
      content.forEach(c => {
        if (c.type !== 'tool_result') return;
        const [m, b] = findTool(chat, c.tool_use_id);
        if (!b) return;
        b.result = resultText(c.content);
        b.isError = !!c.is_error;
        b.status = b.isError ? 'error' : 'done';
        m.dirty = true;
      });
      chat.verb = VERBS[Math.floor(Math.random() * VERBS.length)];
      return;
    }
    if (t === 'result') {
      const m = lastAssistant(chat, true);
      m.blocks.forEach(b => { if (b.type === 'tool' && b.status === 'running') b.status = 'done'; if (b.type === 'thinking') b.streaming = false; });
      if (d.subtype === 'error_during_execution' || chat.stopping) {
        m.blocks.push({ type: 'stopped' });
      } else if (d.is_error) {
        m.blocks.push({ type: 'error', title: '请求失败', text: d.result || (d.errors || []).join('\n') || d.subtype });
      }
      const u = d.usage || {};
      const last = chat.lastUsage || u; // usage of the latest request: its context size
      m.usage = { context: (last.input_tokens || 0) + (last.cache_read_input_tokens || 0) + (last.cache_creation_input_tokens || 0), output: u.output_tokens || 0 };
      m.seconds = d.duration_ms ? Math.round(d.duration_ms / 100) / 10 : 0;
      m.done = true;
      m.dirty = true;
      chat.running = false;
      chat.stopping = false;
      if (d.session_id) { chat.sessionId = d.session_id; S.bySession.set(d.session_id, chat.key); }
      loadSessions();
    }
  }
  function onEvent(ev) {
    const chat = chatForEvent(ev);
    if (ev.kind === 'cc') {
      const d = ev.data;
      if (d.type === 'stream_event' && d.event && d.event.type === 'message_start' && !d.parent_tool_use_id) {
        chat.lastUsage = (d.event.message || {}).usage || chat.lastUsage;
      }
      onCc(chat, d);
    } else if (ev.kind === 'state') {
      if (ev.data && ev.data.running && !chat.running) { chat.running = true; chat.startedAt = Date.now(); }
    } else if (ev.kind === 'exit') {
      if (ev.data && ev.data.running) {
        const m = lastAssistant(chat, true);
        m.blocks.push({ type: 'error', title: 'Claude Code 意外退出', text: (ev.data.stderr || '').trim() || `退出码 ${ev.data.code}` });
        m.done = true; m.dirty = true;
      }
      chat.running = false;
    }
  }

  // ---------- sending ----------
  async function send() {
    const chat = cur();
    if (chat.running) return stop();
    const text = $('input').value.replace(/\s+$/, '');
    if (!text.trim() && !chat.images.length) return;
    if (!S.linked) return toast('还没连上 Termux');
    const content = [];
    chat.images.forEach(img => content.push({ type: 'image', source: { type: 'base64', media_type: img.type, data: img.data } }));
    if (text.trim()) content.push({ type: 'text', text });
    chat.messages.push({ role: 'user', text, images: chat.images.map(i => i.url), dirty: true });
    if (!chat.title) chat.title = (text || '图片').split('\n')[0].slice(0, 60);
    if (!chat.cwd) chat.cwd = S.cwd || S.defaultCwd;
    chat.images = [];
    chat.running = true;
    chat.startedAt = Date.now();
    chat.verb = VERBS[Math.floor(Math.random() * VERBS.length)];
    $('input').value = '';
    chat.draft = '';
    autosize();
    S.stick = true;
    render();
    const r = await call('POST', '/send', { chat: chat.key, sessionId: chat.sessionId, cwd: chat.cwd, model: S.model || null, content });
    if (r.status !== 200) {
      chat.running = false;
      const m = lastAssistant(chat, true);
      m.blocks.push({ type: 'error', title: '发送失败', text: r.status === -1 ? '连不上 Termux 里的桥接程序。' : (r.data && r.data.error) || String(r.status) });
      m.done = true; m.dirty = true;
      render();
    }
  }
  async function stop() {
    const chat = cur();
    chat.stopping = true;
    await call('POST', '/stop', { chat: chat.key });
  }

  // ---------- images ----------
  function addImages(files) {
    const chat = cur();
    [...files].slice(0, 8).forEach(file => {
      const img = new Image();
      const url = URL.createObjectURL(file);
      img.onload = () => {
        const max = 1568;
        const scale = Math.min(1, max / Math.max(img.width, img.height));
        const c = document.createElement('canvas');
        c.width = Math.round(img.width * scale);
        c.height = Math.round(img.height * scale);
        const g = c.getContext('2d');
        g.fillStyle = '#fff';
        g.fillRect(0, 0, c.width, c.height);
        g.drawImage(img, 0, 0, c.width, c.height);
        const dataUrl = c.toDataURL('image/jpeg', 0.86);
        URL.revokeObjectURL(url);
        chat.images.push({ type: 'image/jpeg', data: dataUrl.split(',')[1], url: dataUrl });
        updateChrome();
      };
      img.onerror = () => { URL.revokeObjectURL(url); toast('这张图片读不了'); };
      img.src = url;
    });
  }
  function renderAttachments() {
    const chat = cur();
    const box = $('attachments');
    const html = chat.images.map((img, i) => `<div class="thumb"><img src="${img.url}" alt=""><button data-remove="${i}" aria-label="移除">${icon('x')}</button></div>`).join('');
    if (box.dataset.html !== html) { box.innerHTML = html; box.dataset.html = html; }
  }

  // ---------- drawer & gestures ----------
  const appEl = $('app');
  const drawer = $('drawer');
  const scrim = $('scrim');
  function openDrawer() { appEl.classList.add('drawer-open'); loadSessions(); document.activeElement && document.activeElement.blur(); }
  function closeDrawer() { appEl.classList.remove('drawer-open'); }
  const drawerOpen = () => appEl.classList.contains('drawer-open');

  (() => {
    let sx = 0, sy = 0, st = 0, axis = null, open0 = false, w = 0, x = 0, active = false;
    const scrollsX = el => {
      for (; el && el !== document.body; el = el.parentElement) {
        if (el.scrollWidth > el.clientWidth + 2 && /(auto|scroll)/.test(getComputedStyle(el).overflowX)) return true;
      }
      return false;
    };
    document.addEventListener('touchstart', e => {
      if (e.touches.length !== 1 || $('sheetLayer').classList.contains('open')) { active = false; return; }
      const t = e.touches[0];
      sx = t.clientX; sy = t.clientY; st = Date.now(); axis = null;
      open0 = drawerOpen();
      w = drawer.offsetWidth;
      active = open0 || !scrollsX(e.target);
    }, { passive: true });
    document.addEventListener('touchmove', e => {
      if (!active) return;
      const t = e.touches[0];
      const dx = t.clientX - sx, dy = t.clientY - sy;
      if (!axis) {
        if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
        axis = Math.abs(dx) > Math.abs(dy) * 1.2 ? 'x' : 'y';
        if (axis === 'x' && ((!open0 && dx < 0) || (open0 && dx > 0))) axis = 'y';
        if (axis === 'x') { appEl.classList.add('dragging'); document.activeElement && document.activeElement.blur(); }
      }
      if (axis !== 'x') return;
      e.preventDefault();
      x = Math.max(0, Math.min(w, (open0 ? w : 0) + dx));
      drawer.style.transform = `translateX(${x - w}px)`;
      scrim.style.opacity = String(x / w);
      scrim.style.pointerEvents = 'none';
    }, { passive: false });
    document.addEventListener('touchend', () => {
      if (axis !== 'x') return;
      appEl.classList.remove('dragging');
      drawer.style.transform = '';
      scrim.style.opacity = '';
      scrim.style.pointerEvents = '';
      const v = (x - (open0 ? w : 0)) / Math.max(1, Date.now() - st);
      const shouldOpen = v > 0.35 ? true : v < -0.35 ? false : x > w / 2;
      if (shouldOpen) openDrawer(); else closeDrawer();
      axis = null;
    });
  })();

  // ---------- sheets ----------
  const layer = $('sheetLayer');
  const sheet = $('sheet');
  function openSheet(html, onClick) {
    sheet.innerHTML = '<div class="grabber"></div>' + html;
    paintIcons(sheet);
    sheet.onclick = onClick || null;
    layer.classList.add('open');
  }
  function closeSheet() { layer.classList.remove('open'); }
  layer.addEventListener('click', e => { if (e.target === layer) closeSheet(); });

  async function folderSheet(path) {
    const r = await call('GET', '/dirs?path=' + encodeURIComponent(path || S.cwd || S.defaultCwd));
    if (r.status !== 200) return toast('读取文件夹失败');
    const d = r.data;
    const home = d.home || S.home;
    const rel = d.path.startsWith(home) ? '~' + d.path.slice(home.length) : d.path;
    const segs = rel.split('/').filter(Boolean);
    let acc = rel.startsWith('~') ? home : '';
    const crumbs = segs.map((s, i) => {
      if (!(i === 0 && s === '~')) acc += '/' + s;
      return `<button data-dir="${esc(acc)}">${esc(s)}</button>`;
    }).join('<span>/</span>');
    openSheet(`<div class="sheet-title">选择工作文件夹</div><div class="sheet-sub">新对话会在这个文件夹里运行 Claude Code。</div>
      <div class="crumbs">${rel.startsWith('/') ? '<button data-dir="/">/</button>' : ''}${crumbs}</div>
      <div class="card dir-list">${d.parent ? `<button class="option" data-dir="${esc(d.parent)}">${icon('up')}<span>上一级</span></button>` : ''}
      ${d.dirs.map(n => `<button class="option" data-dir="${esc(d.path + '/' + n)}">${icon('folder')}<span>${esc(n)}</span></button>`).join('') || '<div class="sessions-empty">没有子文件夹</div>'}</div>
      <div class="field"><input id="newDir" placeholder="新建文件夹名"><button class="btn" data-mkdir>新建</button></div>
      <div class="sheet-actions"><button class="btn" data-close>取消</button><button class="btn primary" data-pick="${esc(d.path)}">使用这个文件夹</button></div>`, async e => {
      const t = e.target.closest('button');
      if (!t) return;
      if (t.dataset.dir) folderSheet(t.dataset.dir);
      else if (t.hasAttribute('data-close')) closeSheet();
      else if (t.dataset.pick) {
        S.cwd = t.dataset.pick; store.set('cwd', S.cwd);
        const chat = cur(); if (!chat.sessionId) chat.cwd = S.cwd;
        closeSheet(); render();
      } else if (t.hasAttribute('data-mkdir')) {
        const name = $('newDir').value.trim();
        if (!name || name.includes('/')) return toast('文件夹名不能为空，也不能带 /');
        const m = await call('POST', '/mkdir', { path: d.path + '/' + name });
        if (m.status === 200) folderSheet(d.path + '/' + name); else toast('新建失败');
      }
    });
  }

  function modelSheet() {
    const list = [''].concat(S.models);
    openSheet(`<div class="sheet-title">模型</div><div class="sheet-sub">对新消息生效；“默认”用 Claude Code 设置里的模型。</div><div class="card">${list.map(m => `<button class="option" data-model="${esc(m)}"><span>${m ? esc(shortModel(m)) : '默认'}<span class="opt-sub">${m ? esc(m) : esc(S.defaultModel || 'settings.json')}</span></span>${m === S.model ? `<span class="check">${icon('check')}</span>` : ''}</button>`).join('')}</div>`, e => {
      const t = e.target.closest('[data-model]');
      if (!t) return;
      S.model = t.dataset.model; store.set('model', S.model);
      closeSheet(); render();
    });
  }

  function settingsSheet() {
    const info = JSON.parse(native.info() || '{}');
    openSheet(`<div class="sheet-title">设置</div>
      <div class="section">连接</div>
      <div class="card"><div class="kv"><span>Termux 桥接</span><span>${S.linked ? '已连接' : '未连接'}</span></div>
        <div class="kv"><span>默认文件夹</span><span>${esc(tilde(S.cwd || S.defaultCwd) || '—')}</span></div>
        <div class="kv"><span>模型</span><span>${esc(S.model || S.defaultModel || '默认')}</span></div></div>
      <div class="actions"><button class="btn" data-act="restart">${icon('refresh')}重启桥接</button><button class="btn" data-act="termux">${icon('termuxApp')}打开 Termux</button></div>
      <div class="section">说明</div>
      <div class="card"><div class="kv"><span>权限模式</span><span>不询问（bypass）</span></div><div class="kv"><span>换行 / 发送</span><span>回车换行，按钮发送</span></div><div class="kv"><span>版本</span><span>${esc(info.version || '')}</span></div></div>`, async e => {
      const t = e.target.closest('[data-act]');
      if (!t) return;
      if (t.dataset.act === 'termux') native.openTermux();
      if (t.dataset.act === 'restart') {
        closeSheet(); toast('正在重启桥接…');
        S.linked = false; updateLink();
        const r = await nativeAsync('restartBridge');
        if (r.data === 'ok') { toast('已重新连接'); afterLink(); } else { S.setup = r.data; render(); }
      }
    });
  }

  function sessionActions(id) {
    const s = S.sessions.find(x => x.id === id);
    if (!s) return;
    openSheet(`<div class="sheet-title">${esc(s.title)}</div><div class="sheet-sub">${esc(tilde(s.cwd))}</div><div class="card"><button class="option danger" data-del>${icon('trash')}<span>删除对话</span></button></div>`, async e => {
      if (!e.target.closest('[data-del]')) return;
      const r = await call('POST', '/delete', { id });
      closeSheet();
      if (r.status === 200) {
        S.sessions = S.sessions.filter(x => x.id !== id);
        const key = S.bySession.get(id) || id;
        if (S.current === key) switchTo(newChat().key);
        S.chats.delete(key);
        renderSessions();
        toast('已删除');
      } else toast('删除失败');
    });
  }

  let toastTimer = 0;
  function toast(text) {
    const el = $('toast');
    el.textContent = text;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2000);
  }

  // ---------- connection ----------
  function updateLink() {
    const b = $('linkBtn');
    b.classList.toggle('up', S.linked);
    b.classList.toggle('down', !S.linked && !S.connecting);
    $('linkText').textContent = S.linked ? '已连接 Termux' : S.connecting ? '正在连接…' : '未连接 · 点此重试';
    $('banner').hidden = S.linked || S.connecting || !!S.setup || !S.everLinked;
    $('banner').textContent = '与 Termux 的连接断开了，正在重连…';
  }
  async function connect() {
    if (S.connecting) return;
    S.connecting = true; updateLink();
    const r = await nativeAsync('connect');
    S.connecting = false;
    if (r.data === 'ok') { S.setup = null; afterLink(); }
    else { S.setup = r.data; S.linked = false; updateLink(); shown.key = null; render(); }
  }
  async function afterLink() {
    S.linked = true; S.everLinked = true; S.setup = null;
    updateLink();
    const [h, c] = await Promise.all([call('GET', '/health'), call('GET', '/config')]);
    if (h.status === 200) { S.home = h.data.home; S.defaultCwd = h.data.defaultCwd; }
    if (c.status === 200) { S.models = c.data.models || []; S.defaultModel = c.data.defaultModel || ''; }
    S.chats.forEach(ch => { if (!ch.cwd) ch.cwd = S.cwd || S.defaultCwd; });
    shown.key = null;
    render();
    loadSessions();
  }

  // ---------- input ----------
  const input = $('input');
  function autosize() {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, window.innerHeight * 0.38) + 'px';
  }
  input.addEventListener('input', () => { autosize(); updateChrome(); });
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); }
  });
  $('sendBtn').addEventListener('click', send);
  $('attachBtn').addEventListener('click', () => $('fileInput').click());
  $('fileInput').addEventListener('change', e => { addImages(e.target.files); e.target.value = ''; });
  $('attachments').addEventListener('click', e => {
    const b = e.target.closest('[data-remove]');
    if (!b) return;
    cur().images.splice(Number(b.dataset.remove), 1);
    updateChrome();
  });
  $('folderBtn').addEventListener('click', () => {
    if (!S.linked) return toast('还没连上 Termux');
    if (cur().sessionId) return toast('已有对话的文件夹不能更改，新对话可以选');
    folderSheet();
  });
  $('modelBtn').addEventListener('click', modelSheet);
  $('menuBtn').addEventListener('click', openDrawer);
  $('scrim').addEventListener('click', closeDrawer);
  $('newBtn').addEventListener('click', () => switchTo(newChat().key));
  $('newChat').addEventListener('click', () => { closeDrawer(); switchTo(newChat().key); });
  $('settingsBtn').addEventListener('click', settingsSheet);
  $('linkBtn').addEventListener('click', () => { if (S.linked) settingsSheet(); else connect(); });
  $('search').addEventListener('input', e => { S.search = e.target.value; renderSessions(); });
  $('titleBtn').addEventListener('click', () => { S.stick = true; scroller.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' }); });
  $('toBottom').addEventListener('click', () => { S.stick = true; scroller.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' }); });

  let pressTimer = 0, pressed = false;
  $('sessions').addEventListener('touchstart', e => {
    const item = e.target.closest('[data-session]');
    if (!item) return;
    pressed = false;
    pressTimer = setTimeout(() => { pressed = true; if (navigator.vibrate) navigator.vibrate(12); sessionActions(item.dataset.session); }, 480);
  }, { passive: true });
  ['touchend', 'touchmove', 'touchcancel'].forEach(t => $('sessions').addEventListener(t, () => clearTimeout(pressTimer), { passive: true }));
  $('sessions').addEventListener('contextmenu', e => e.preventDefault());
  $('sessions').addEventListener('click', e => {
    const item = e.target.closest('[data-session]');
    if (!item || pressed) { pressed = false; return; }
    openSession(item.dataset.session);
  });

  scroller.addEventListener('scroll', () => {
    S.stick = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
    $('toBottom').classList.toggle('show', !S.stick);
  }, { passive: true });
  window.addEventListener('resize', () => { autosize(); if (S.stick) scroller.scrollTop = scroller.scrollHeight; });

  document.addEventListener('click', e => {
    const t = e.target;
    const copyCode = t.closest('[data-copy]');
    if (copyCode) {
      native.copy(copyCode.closest('.code').querySelector('code').textContent);
      return toast('已复制');
    }
    const copyText = t.closest('[data-copy-text]');
    if (copyText) { native.copy(copyText.dataset.copyText); return toast('已复制'); }
    const copyMsg = t.closest('[data-copy-msg]');
    if (copyMsg) {
      const m = cur().messages.find(x => x.el === copyMsg.closest('.msg'));
      if (m) native.copy(m.blocks.filter(b => b.type === 'text').map(b => b.text).join('\n\n'));
      return toast('已复制');
    }
    const toggle = t.closest('[data-toggle]');
    if (toggle) {
      const msgNode = toggle.closest('.msg');
      const m = cur().messages.find(x => x.el === msgNode);
      const b = m && m.blocks[Number(toggle.parentElement.dataset.block)];
      if (b) { b.open = !b.open; m.dirty = true; const keep = S.stick; S.stick = false; renderNow(); S.stick = keep; }
      return;
    }
    const sug = t.closest('.suggestion');
    if (sug) { input.value = sug.textContent; autosize(); input.focus(); updateChrome(); return; }
    const act = t.closest('[data-act]');
    if (act && !sheet.contains(act)) {
      if (act.dataset.act === 'retry') connect();
      if (act.dataset.act === 'termux') native.openTermux();
      if (act.dataset.act === 'perm') native.askPermission();
      return;
    }
    const a = t.closest('.prose a[href]');
    if (a) { e.preventDefault(); if (/^https?:/i.test(a.href)) native.openUrl(a.href); }
  });

  // ---------- called by MainActivity ----------
  window.app = {
    reply(id, status, text) {
      const r = waiting.get(id);
      if (!r) return;
      waiting.delete(id);
      let data = text;
      try { data = JSON.parse(text); } catch (e) { /* plain text */ }
      r({ status, data });
    },
    events(list) {
      list.forEach(onEvent);
      render();
      if (!drawerOpen()) return;
      renderSessions();
    },
    link(up) {
      if (up === S.linked) return;
      if (up) afterLink();
      else { S.linked = false; updateLink(); render(); }
    },
    permissionResult(granted) {
      if (granted) connect(); else { toast('没有授权，无法启动 Claude Code'); shown.key = null; render(); }
    },
    resumed() { if (!S.linked && !S.connecting) connect(); },
    back() {
      if (layer.classList.contains('open')) { closeSheet(); return true; }
      if (drawerOpen()) { closeDrawer(); return true; }
      return false;
    },
  };

  paintIcons(document);
  switchTo(newChat().key);
  renderSessions();
  connect();
})();
