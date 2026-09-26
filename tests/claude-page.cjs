const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-page.js', 'utf8');

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
    if (sel === '*') return true;
    if (sel === 'svg') return this.tagName === 'SVG';
    if (sel.startsWith('textarea')) return this.tagName === 'TEXTAREA' || this.attrs.contenteditable === 'true';
    return this.tagName === 'BUTTON' && /send|submit/i.test(this.attrs['aria-label'] || '');
  }
  closest(sel) { for (let n = this; n; n = n.parentElement) if (n.matches(sel)) return n; return null; }
  contains(o) { for (let n = o; n; n = n.parentElement) if (n === this) return true; return false; }
  querySelectorAll(sel) {
    const out = [];
    (function walk(n) { for (const c of n.children) { if (c.matches(sel)) out.push(c); walk(c); } })(this);
    return out;
  }
  getClientRects() { return [1]; }
  getBoundingClientRect() {
    return this.rect || { top: 900, bottom: 940, left: 0, right: 400, width: 400,
      height: this.tagName === 'BODY' ? 1000 : 60 };
  }
  removeAttribute(k) { delete this.attrs[k]; }
  dispatchEvent(e) { (this.events ||= []).push(e.type + ':' + e.key); }
  setAttribute(k, v) { this.attrs[k] = v; }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
  click() { this.clicks++; document.listeners.click.forEach(h => h({ target: this })); }
  blur() { if (document.activeElement === this) document.activeElement = body; }
}
class HTMLElement extends Element {}
HTMLElement.prototype.focus = function () { document.activeElement = this; };

const document = { listeners: { click: [], keydown: [], focusin: [], touchstart: [] },
  addEventListener(t, h) { (this.listeners[t] ||= []).push(h); } };
const body = new HTMLElement('body');
const link = new HTMLElement('a', new HTMLElement('nav', body));
const composer = new HTMLElement('div', body);
const editor = new HTMLElement('div', new HTMLElement('div', composer), { contenteditable: 'true' });
const send = new HTMLElement('button', new HTMLElement('div', composer), { 'aria-label': 'Send message' });
const hint = new HTMLElement('svg', composer);
hint.rect = { top: 910, bottom: 930, left: 370, right: 390, width: 20, height: 20 };
editor.rect = { top: 900, bottom: 940, left: 20, right: 360, width: 340, height: 40 };
document.activeElement = body;

let now = 0;
const timers = [];
const windowListeners = {};
const window = { innerHeight: 1000, addEventListener: (t, h) => { windowListeners[t] = h; } };
class KeyboardEvent { constructor(type, init) { this.type = type; Object.assign(this, init); } }
vm.runInNewContext(script, { window, document, HTMLElement, Element, Node, KeyboardEvent,
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

editor.textContent = '';
assert.equal(key({}), true, 'empty box: Enter is taken over');
assert.deepEqual(editor.events, ['keydown:Tab', 'keyup:Tab'], 'empty box: Enter presses Tab');
editor.textContent = 'hello';
assert.equal(key({}), true, 'Enter is taken over');
assert.equal(send.clicks, 2, 'Enter sends');
flush();
assert.equal(document.activeElement, body, 'keyboard closes after sending');

document.activeElement = editor;
assert.equal(key({ shiftKey: true }), false, 'Shift+Enter makes a new line');
assert.equal(key({ isComposing: true }), false, 'Enter confirming an IME candidate');
assert.equal(key({ keyCode: 229 }), false, 'IME Enter (keyCode 229)');
send.disabled = true;
assert.equal(key({}), true, 'Send disabled: no new line');
assert.equal(send.clicks, 2, 'Send disabled: nothing sent');
send.attrs['aria-label'] = 'Stop response';
assert.equal(key({}), false, 'no Send button (Claude replying): Enter is a new line');
let dragBlocked = false;
document.listeners.dragstart.forEach(h => h({ preventDefault() { dragBlocked = true; } }));
assert.equal(dragBlocked, true, 'dragging selected text does not start drag and drop');
console.log('claude-page: all tests passed');
