const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-selection-guard.js', 'utf8');

// Minimal DOM: only what the script uses.
function element(name, parent = null, attrs = {}) {
  const node = {
    nodeType: 1, name, parentElement: parent, isConnected: true, attrs: { ...attrs }, events: [],
    getAttribute: key => (key in node.attrs ? node.attrs[key] : null),
    contains: other => { for (let n = other; n; n = n.parentElement) if (n === node) return true; return false; },
    closest: selector => {
      for (let n = node; n; n = n.parentElement) {
        if (selector.includes('contenteditable') && n.attrs.contenteditable === 'true') return n;
      }
      return null;
    },
    dispatchEvent: event => { node.events.push(event.type + (event.key ? ':' + event.key : '')); },
  };
  return node;
}

function setup() {
  let clock = 0;
  const timers = [];
  const listeners = {};
  const observers = [];
  const html = element('html');
  const body = element('body', html);
  const dialogs = [];
  let range = null;
  const document = {
    body, documentElement: html, activeElement: body,
    querySelectorAll: () => dialogs.filter(d => d.isConnected),
    getSelection: () => ({
      isCollapsed: !range, rangeCount: range ? 1 : 0,
      anchorNode: range && range.startContainer,
      getRangeAt: () => ({ ...range, cloneRange() { return { ...this }; } }),
    }),
    addEventListener: (type, handler) => { (listeners['doc:' + type] ||= []).push(handler); },
  };
  class Event { constructor(type, init = {}) { this.type = type; Object.assign(this, init); } }
  const window = { addEventListener: (type, handler) => { (listeners[type] ||= []).push(handler); },
    MouseEvent: Event, PointerEvent: Event };
  window.top = window;
  const context = {
    window, document, KeyboardEvent: Event, console: { info() {} }, Date: { now: () => clock },
    setTimeout: (fn, ms) => { timers.push({ at: clock + ms, fn }); return timers.length; },
    clearTimeout: id => { if (timers[id - 1]) timers[id - 1].fn = null; },
    MutationObserver: class {
      constructor(fn) { this.fn = fn; this.live = false; observers.push(this); }
      observe() { this.live = true; }
      disconnect() { this.live = false; }
    },
  };
  vm.runInNewContext(script, context);
  const emit = (type, event = {}) => (listeners[type] || []).forEach(h => h(event));
  return {
    body,
    advance(ms) {
      clock += ms;
      for (const t of timers) if (t.fn && t.at <= clock) { const fn = t.fn; t.fn = null; fn(); }
    },
    touch() { emit('touchstart', { touches: [1] }); },
    release() { emit('touchend', {}); },
    select(node, offset = 0) { range = node ? { startContainer: node, startOffset: offset, endContainer: node, endOffset: offset + 3 } : null; emit('doc:selectionchange'); },
    open(attrs = { role: 'dialog', 'data-state': 'open' }) {
      const dialog = element('dialog', body, attrs);
      dialogs.push(dialog);
      observers.filter(o => o.live).forEach(o => o.fn([]));
      return dialog;
    },
    watching: () => observers.some(o => o.live),
  };
}

const text = page => element('p', page.body);

{ // Drawer opened by the long-press before the selection appears: closed.
  const page = setup();
  page.touch();
  page.advance(500);
  const drawer = page.open();
  page.select(text(page));
  assert.deepEqual(page.body.events, ['keydown:Escape'], 'drawer opened before selection');
}

{ // Drawer opened on a timer after the selection: closed while watching, then observer stops.
  const page = setup();
  page.touch();
  page.select(text(page));
  assert.ok(page.watching(), 'watches briefly after a touch selection');
  page.advance(200);
  page.open({ 'data-vaul-drawer': '', 'data-state': 'open' });
  assert.deepEqual(page.body.events, ['keydown:Escape'], 'drawer opened after selection');
  page.advance(5100);
  assert.ok(!page.watching(), 'stops observing');
}

{ // Drawer still open after Escape: one outside press on the root.
  const page = setup();
  page.touch();
  const drawer = page.open();
  page.select(text(page));
  page.advance(200);
  assert.deepEqual(page.body.events, ['keydown:Escape']);
  assert.deepEqual(page.body.parentElement.events, ['pointerdown', 'mousedown', 'pointerup', 'mouseup']);
}

{ // Drawer closed by Escape: no outside press.
  const page = setup();
  page.touch();
  const drawer = page.open();
  page.select(text(page));
  drawer.isConnected = false;
  page.advance(200);
  assert.deepEqual(page.body.parentElement.events, []);
}

{ // Drawer opened on release of the long-press.
  const page = setup();
  page.touch();
  page.select(text(page));
  page.advance(1600);
  page.release();
  page.open();
  assert.deepEqual(page.body.events, ['keydown:Escape'], 'drawer opened on release');
}

{ // Long handle drag (no page touch events): selection changes keep it armed until release.
  const page = setup();
  const node = text(page);
  page.touch();
  page.select(node);
  page.release();
  for (let i = 1; i <= 8; i++) { page.advance(1000); page.select(node, i); }
  page.open();
  assert.deepEqual(page.body.events, ['keydown:Escape'], 'drawer after long handle drag');
}

{ // Quiet for longer than 5 s: observer stopped, later dialogs untouched.
  const page = setup();
  page.touch();
  page.select(text(page));
  page.release();
  page.advance(5100);
  assert.ok(!page.watching(), 'stops after quiet period');
  page.open();
  assert.deepEqual(page.body.events, [], 'dialog after quiet period');
}

{ // Dialog that was open before the touch: untouched.
  const page = setup();
  page.open();
  page.touch();
  page.select(text(page));
  assert.deepEqual(page.body.events, [], 'pre-existing dialog');
}

{ // Selection inside the new dialog: untouched.
  const page = setup();
  page.touch();
  const dialog = page.open();
  page.select(element('p', dialog));
  assert.deepEqual(page.body.events, [], 'selection inside dialog');
}

{ // Selection in an editor: untouched.
  const page = setup();
  page.touch();
  page.open();
  page.select(element('span', element('div', page.body, { contenteditable: 'true' })));
  assert.deepEqual(page.body.events, [], 'editor selection');
}

{ // Tap on a button (dialog opens) while an older selection stays: untouched.
  const page = setup();
  const node = text(page);
  page.touch();
  page.select(node);
  page.advance(6000);
  page.touch();
  page.open();
  page.release();
  assert.deepEqual(page.body.events, [], 'tap with existing selection');
}

{ // Long-press on another word while a selection exists: the new drawer is closed.
  const page = setup();
  const node = text(page);
  page.touch();
  page.select(node);
  page.advance(6000);
  page.touch();
  page.open();
  page.select(node, 10);
  assert.deepEqual(page.body.events, ['keydown:Escape'], 'new selection over old one');
}

{ // Dialog opened without any recent touch (e.g. keyboard): untouched.
  const page = setup();
  page.touch();
  page.advance(6000);
  page.open();
  page.select(text(page));
  assert.deepEqual(page.body.events, [], 'no recent touch');
}

console.log('selection-guard: all tests passed');
