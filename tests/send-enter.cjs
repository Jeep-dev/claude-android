const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-send-enter.js', 'utf8');

// Stands in for HTMLElement.prototype; the script wraps its focus().
class FakeElement {}
const baseFocus = function () { this.focusCalls++; if (FakeElement.document) FakeElement.document.activeElement = this; };

// Minimal DOM: only the selectors and properties the script uses.
function element(tag, props = {}, children = []) {
  const attrs = { ...(props.attrs || {}) };
  const node = Object.assign(Object.create(FakeElement.prototype), {
    tagName: tag.toUpperCase(), disabled: false, readOnly: false, hidden: false,
    textContent: '', value: '', clicks: 0, focusCalls: 0, blurs: 0, parentElement: null, children,
    isContentEditable: attrs.contenteditable === 'true',
    ...props,
    getAttribute: name => (name in attrs ? attrs[name] : null),
    setAttribute: (name, value) => { attrs[name] = value; },
    getClientRects: () => (node.hidden ? [] : [1]),
    click: () => { node.clicks++; },
    blur: () => { node.blurs++; },
    contains: other => { for (let n = other; n; n = n.parentElement) if (n === node) return true; return false; },
    matches: selector => (selector.includes('textarea') && node.tagName === 'TEXTAREA')
      || (selector.includes('contenteditable') && attrs.contenteditable === 'true'),
    closest: selector => {
      for (let n = node; n; n = n.parentElement) {
        if (selector.includes('dialog') && n.getAttribute('role') === 'dialog') return n;
      }
      return null;
    },
    querySelectorAll: selector => {
      const found = [];
      (function walk(n) {
        for (const c of n.children) {
          if (selector.startsWith('button') ? c.tagName === 'BUTTON' : c.matches(selector)) found.push(c);
          walk(c);
        }
      })(node);
      return found;
    },
  });
  children.forEach(c => { c.parentElement = node; });
  return node;
}

const chatEditor = () => element('div', { textContent: 'hello', attrs: { contenteditable: 'true' } });
const button = (label, props = {}) => element('button', { attrs: { 'aria-label': label }, ...props });

function test(name, build, action, expected) {
  let blocked = 0;
  const listeners = {};
  FakeElement.prototype.focus = baseFocus;
  const { editor, extras = {} } = build();
  let root = editor;
  while (root.parentElement) root = root.parentElement;
  const body = element('body', {}, [root]);
  const document = { body, documentElement: {}, activeElement: editor };
  FakeElement.document = document;
  const window = { addEventListener: (type, handler) => { (listeners[type] ||= []).push(handler); } };
  window.top = window;
  const context = { window, document, HTMLElement: FakeElement,
    MutationObserver: class { constructor() { throw Error('Do not observe Claude streaming DOM'); } },
  };
  vm.runInNewContext(script, context);
  function emit(type, props = {}) {
    const event = { target: editor, preventDefault: () => blocked++,
      stopImmediatePropagation: () => {}, ...props };
    (listeners[type] || []).forEach(handler => handler(event));
  }
  const sendButtons = body.querySelectorAll('button');
  action({ emit, editor, document, body, ...extras });
  const clicks = sendButtons.reduce((sum, b) => sum + b.clicks, 0);
  assert.deepEqual([clicks, blocked], expected, name);
}

function chat(extraButtons = [], sendProps = {}) {
  const editor = chatEditor();
  const send = button('Send message', sendProps);
  element('div', {}, [element('div', {}, [editor]), element('div', {}, [...extraButtons, send])]);
  return { editor, extras: { send } };
}

function code(label = 'Submit') {
  const editor = element('textarea', { value: 'fix the build' });
  const send = button(label);
  element('form', {}, [editor, element('div', {}, [button('Attach files'), send])]);
  return { editor, extras: { send } };
}

test('chat: plain Enter sends', chat, ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [1, 1]);
test('chat: mobile beforeinput sends', chat,
  ({ emit }) => emit('beforeinput', { inputType: 'insertParagraph' }), [1, 1]);
test('code: textarea composer with Submit button sends', () => code(),
  ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [1, 1]);
test('code: textarea insertLineBreak sends', () => code('Send'),
  ({ emit }) => emit('beforeinput', { inputType: 'insertLineBreak' }), [1, 1]);
test('code: icon-only submit button sends', () => {
  const editor = element('textarea', { value: 'x' });
  element('div', {}, [editor, element('button', { attrs: { type: 'submit' } })]);
  return { editor };
}, ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [1, 1]);
test('code: empty textarea keeps Enter', () => {
  const built = code();
  built.editor.value = '  ';
  built.editor.textContent = 'stale default text';
  return built;
}, ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [0, 0]);
test('Latin word commit followed by Enter sends', chat, ({ emit }) => {
  emit('compositionstart');
  emit('compositionend');
  emit('beforeinput', { inputType: 'insertParagraph', isComposing: false });
}, [1, 1]);
test('Shift+Enter remains newline', chat, ({ emit }) => {
  emit('keydown', { key: 'Enter', keyCode: 13, shiftKey: true });
  emit('beforeinput', { inputType: 'insertLineBreak' });
}, [0, 0]);
test('IME composition confirms text first', chat, ({ emit }) => {
  emit('keydown', { key: 'Enter', keyCode: 13, isComposing: true });
  emit('keydown', { key: 'Enter', keyCode: 229 });
  emit('beforeinput', { inputType: 'insertParagraph', isComposing: true });
}, [0, 0]);
test('other input not affected', chat, ({ emit, document }) => {
  document.activeElement = { matches: () => false };
  emit('keydown', { key: 'Enter', keyCode: 13 });
}, [0, 0]);
test('disabled send button does not consume Enter', () => chat([], { disabled: true }),
  ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [0, 0]);
test('streaming Stop button: Enter is left alone', () => {
  const editor = chatEditor();
  element('div', {}, [element('div', {}, [editor]), button('Stop response'),
    element('section', {}, [button('Submit feedback')])]);
  return { editor };
}, ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [0, 0]);
test('two send buttons are ambiguous', () => chat([button('Send now')]),
  ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [0, 0]);
test('hidden duplicate send button is ignored', () => chat([button('Send', { hidden: true })]),
  ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [1, 1]);
test('textarea in a dialog is not affected', () => {
  const editor = element('textarea', { value: 'feedback' });
  element('div', { attrs: { role: 'dialog' } }, [editor, button('Submit')]);
  return { editor };
}, ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [0, 0]);
test('settings textarea with Save button is not affected', () => {
  const editor = element('textarea', { value: 'instructions' });
  element('div', {}, [editor, element('button', { textContent: 'Save' })]);
  return { editor };
}, ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [0, 0]);
test('composer gets send key hint on focus', chat, ({ emit, editor }) => {
  emit('pointerdown', { target: editor });
  emit('focusin', { target: editor });
  assert.equal(editor.getAttribute('enterkeyhint'), 'send');
}, [0, 0]);
test('repeated Enter within 600ms sends once', chat, ({ emit }) => {
  emit('keydown', { key: 'Enter', keyCode: 13 });
  emit('keydown', { key: 'Enter', keyCode: 13 });
}, [1, 2]);

// Soft keyboard must not pop up by itself.
test('page load: script focus on composer is ignored', chat, ({ editor, document, body }) => {
  document.activeElement = body;
  editor.focus();
  assert.equal(editor.focusCalls, 0);
  assert.equal(document.activeElement, body);
}, [0, 0]);
test('tapping the composer lets it focus', chat, ({ emit, editor, document, body }) => {
  document.activeElement = body;
  emit('pointerdown', { target: editor });
  editor.focus();
  assert.equal(editor.focusCalls, 1);
}, [0, 0]);
test('tapping Send keeps the composer focusable', chat, ({ emit, editor, document, body, send }) => {
  document.activeElement = body;
  emit('pointerdown', { target: send });
  editor.focus();
  assert.equal(editor.focusCalls, 1);
}, [0, 0]);
test('opening a chat from elsewhere does not focus the composer', () => {
  const built = chat();
  let root = built.editor;
  while (root.parentElement) root = root.parentElement;
  const sidebarLink = element('a');
  element('main', {}, [element('nav', {}, [sidebarLink]), root]);
  built.extras.sidebarLink = sidebarLink;
  return built;
}, ({ emit, editor, document, body, sidebarLink }) => {
  document.activeElement = body;
  emit('pointerdown', { target: sidebarLink });
  editor.focus();
  assert.equal(editor.focusCalls, 0);
}, [0, 0]);
test('returning from background: focus without a touch is dropped', chat, ({ emit, editor }) => {
  emit('focusin', { target: editor, relatedTarget: null });
  assert.equal(editor.blurs, 1);
}, [0, 0]);
test('keyboard already up for another field: focus allowed', chat, ({ emit, editor }) => {
  const other = element('input');
  emit('focusin', { target: editor, relatedTarget: other });
  assert.equal(editor.blurs, 0);
}, [0, 0]);
test('edit-message box (Cancel/Save) may focus itself', () => {
  const editor = element('textarea', { value: 'old message' });
  element('div', {}, [editor, button('Cancel'), element('button', { textContent: 'Save' })]);
  return { editor };
}, ({ editor, document, body }) => {
  document.activeElement = body;
  editor.focus();
  assert.equal(editor.focusCalls, 1);
}, [0, 0]);

// Not installed in iframes (document-start scripts also run in same-origin frames).
{
  let added = 0;
  const window = { top: {}, addEventListener: () => added++ };
  vm.runInNewContext(script, { window, document: {} });
  assert.equal(added, 0, 'iframe ignored');
}
console.log('send-enter tests passed');
