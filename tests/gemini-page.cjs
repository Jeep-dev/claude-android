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
    if (sel === 'rich-textarea') return this.tagName === 'RICH-TEXTAREA';
    if (this.tagName !== 'BUTTON') return false;
    // .send-button:not(.stop) button: the wrapper's classes decide, not the localized label.
    const cls = (this.parentElement.attrs.class || '').split(' ');
    return cls.includes('send-button') && !cls.includes('stop');
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
// Gemini's real structure: <gem-icon-button class="send-button submit"><button aria-label="…">.
const wrapper = new HTMLElement('gem-icon-button', inner.parentElement.parentElement,
  { class: 'send-button submit' });
const send = new HTMLElement('button', wrapper, { 'aria-label': 'プロンプトを送信' });
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

editor.textContent = 'hello';
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
wrapper.attrs.class = 'send-button stop';
send.attrs['aria-label'] = '回答を停止';
assert.equal(key({}), false, 'Stop button in place of Send (Gemini replying): Enter is a new line');
assert.equal(send.clicks, 2);
editor.textContent = '';
assert.equal(key({}), true, 'empty box while replying: no new line');
// Empty box: Gemini shows no Send button at all, only the microphone.
wrapper.children.length = 0;
assert.equal(key({}), true, 'empty box, no Send button: no new line');
assert.equal(send.clicks, 2, '...and nothing sent');
document.activeElement = body;
document.listeners.focusin.forEach(h => h({ target: editor }));
editor.attrs.enterkeyhint = null;
document.listeners.focusin.forEach(h => h({ target: editor }));
assert.equal(editor.getAttribute('enterkeyhint'), 'send', 'empty box still gets a Send key');
console.log('gemini-page: all tests passed');
