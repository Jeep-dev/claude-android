const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('gemini/src/main/assets/gemini-page.js', 'utf8');

// Minimal DOM: only what the script uses.
class Node {}
class Element extends Node {
  constructor(tag, parent = null, attrs = {}) {
    super();
    this.tagName = tag.toUpperCase(); this.parentElement = parent; this.attrs = { ...attrs };
    this.children = []; this.disabled = false; this.clicks = 0; this.type = attrs.type || '';
    if (parent) parent.children.push(this);
  }
  matches(sel) {
    if (sel.startsWith('textarea')) return this.tagName === 'TEXTAREA' || this.attrs.contenteditable === 'true';
    if (this.tagName !== 'BUTTON') return false;
    const cls = (this.attrs.class || '').split(' ');
    return (cls.includes('send-button') && !cls.includes('stop'))
      || /send|submit|发送/i.test(this.attrs['aria-label'] || '');
  }
  closest(sel) { for (let n = this; n; n = n.parentElement) if (n.matches(sel)) return n; return null; }
  contains(o) { for (let n = o; n; n = n.parentElement) if (n === this) return true; return false; }
  querySelectorAll(sel) {
    const out = [];
    (function walk(n) { for (const c of n.children) { if (c.matches(sel)) out.push(c); walk(c); } })(this);
    return out;
  }
  getClientRects() { return [1]; }
  setAttribute(k, v) { this.attrs[k] = v; }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
  click() { this.clicks++; document.listeners.click.forEach(h => h({ target: this })); }
  blur() { if (document.activeElement === this) document.activeElement = body; }
}
class HTMLElement extends Element {}
HTMLElement.prototype.focus = function () { document.activeElement = this; };

const document = { listeners: { click: [], keydown: [], focusin: [] },
  addEventListener(t, h) { (this.listeners[t] ||= []).push(h); } };
// Gemini's message box sits deep inside its input area, the Send button beside it.
const body = new HTMLElement('body');
const link = new HTMLElement('a', new HTMLElement('nav', body));
const inputArea = new HTMLElement('input-area-v2', body);
let inner = inputArea;
for (let i = 0; i < 6; i++) inner = new HTMLElement('div', inner);
const editor = new HTMLElement('div', new HTMLElement('rich-textarea', inner), { contenteditable: 'true' });
const send = new HTMLElement('button', new HTMLElement('div', inputArea),
  { class: 'send-button', 'aria-label': '发送' });
document.activeElement = body;

let now = 0;
const timers = [];
const windowListeners = {};
const window = { addEventListener: (t, h) => { windowListeners[t] = h; } };
vm.runInNewContext(script, { window, document, HTMLElement, Element, Node,
  Date: { now: () => now }, setTimeout: fn => timers.push(fn) });
const tap = target => windowListeners.pointerdown({ target });
const flush = () => { while (timers.length) timers.shift()(); };
function key(props) {
  let prevented = false;
  const event = { key: 'Enter', target: editor, keyCode: 13, preventDefault() { prevented = true; },
    stopImmediatePropagation() {}, ...props };
  document.listeners.keydown.forEach(h => h(event));
  return prevented;
}

editor.focus();
assert.equal(document.activeElement, body, 'scripted focus on load opens no keyboard');
tap(link); now += 100; editor.focus();
assert.equal(document.activeElement, body, 'no keyboard after switching chats');
tap(send); send.click(); flush(); now += 50; editor.focus();
assert.equal(document.activeElement, body, 'tapping Send sends without opening the keyboard');
assert.equal(send.clicks, 1);

tap(editor); now += 10; editor.focus();
assert.equal(document.activeElement, editor, 'touching the message box opens the keyboard');
document.listeners.focusin.forEach(h => h({ target: editor }));
assert.equal(editor.getAttribute('enterkeyhint'), 'send', 'keyboard shows a Send key');

assert.equal(key({}), true, 'Enter is taken over');
assert.equal(send.clicks, 2, 'Enter sends');
flush();
assert.equal(document.activeElement, body, 'keyboard closes after sending');

document.activeElement = editor;
assert.equal(key({ shiftKey: true }), false, 'Shift+Enter makes a new line');
assert.equal(key({ isComposing: true }), false, 'Enter confirming an IME candidate');
assert.equal(key({ keyCode: 229 }), false, 'IME Enter (keyCode 229)');
send.attrs['aria-disabled'] = 'true';
assert.equal(key({}), true, 'Send unavailable (aria-disabled): no new line');
assert.equal(send.clicks, 2, 'Send unavailable: nothing sent');
delete send.attrs['aria-disabled'];
send.disabled = true;
assert.equal(key({}), true, 'Send disabled: no new line');
assert.equal(send.clicks, 2, 'Send disabled: nothing sent');
send.disabled = false;
send.attrs = { class: 'send-button stop', 'aria-label': '停止回答' };
assert.equal(key({}), false, 'Stop button in place of Send (Gemini replying): Enter is a new line');
assert.equal(send.clicks, 2);
console.log('gemini-page: all tests passed');
