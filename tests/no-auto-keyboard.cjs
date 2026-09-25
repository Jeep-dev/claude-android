const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/no-auto-keyboard.js', 'utf8');

class Node {
  constructor(tag, parent = null, attrs = {}) {
    this.tagName = tag.toUpperCase(); this.parentElement = parent; this.attrs = attrs; this.children = [];
    if (parent) parent.children.push(this);
  }
  matches(sel) {
    return (sel.includes('textarea') && this.tagName === 'TEXTAREA')
      || (sel.includes('contenteditable') && this.attrs.contenteditable === 'true');
  }
  contains(other) { for (let n = other; n; n = n.parentElement) if (n === this) return true; return false; }
  querySelector(sel) {
    const walk = n => n.children.some(c => (sel === 'button' && c.tagName === 'BUTTON') || walk(c));
    return walk(this) ? {} : null;
  }
}
class HTMLElement extends Node {}
HTMLElement.prototype.focus = function () { focused.push(this); document.activeElement = this; };

let focused = [];
const listeners = {};
const body = new HTMLElement('body');
const sidebar = new HTMLElement('nav', body);
const link = new HTMLElement('a', sidebar);
const composer = new HTMLElement('div', body);
const editor = new HTMLElement('div', composer, { contenteditable: 'true' });
const send = new HTMLElement('button', composer);
const document = { activeElement: body };
let now = 0;
const window = { addEventListener: (t, h) => { listeners[t] = h; } };
vm.runInNewContext(script, { window, document, HTMLElement, Node, Date: { now: () => now } });
const tap = target => listeners.pointerdown({ target });

editor.focus();
assert.deepEqual(focused, [], 'focus on page load is ignored');

tap(link); now += 200; editor.focus();
assert.deepEqual(focused, [], 'focus after switching chats in the sidebar is ignored');

tap(send); now += 100; editor.focus();
assert.deepEqual(focused, [editor], 'focus right after touching the message box area works');

focused = []; document.activeElement = editor; editor.focus();
assert.deepEqual(focused, [editor], 'focus while a text field is focused works');

focused = []; document.activeElement = body; tap(editor); now += 2000; editor.focus();
assert.deepEqual(focused, [], 'a touch long ago does not count');

focused = []; link.focus();
assert.deepEqual(focused, [link], 'non-text elements are unaffected');
console.log('no-auto-keyboard: all tests passed');
