const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-send-enter.js', 'utf8');

function test(name, action, expected) {
  let clicks = 0;
  let blocked = 0;
  let hints = 0;
  const listeners = {};
  const editor = {
    textContent: 'hello',
    matches: selector => selector.includes('ProseMirror'),
    getAttribute: () => null,
    setAttribute: () => hints++,
  };
  const button = {
    disabled: false,
    getAttribute: () => null,
    getClientRects: () => [1],
    click: () => clicks++,
  };
  const document = {
    documentElement: {},
    activeElement: editor,
    querySelectorAll: selector => selector.includes('button') ? [button] : [editor],
    addEventListener: (type, handler) => { listeners[type] = handler; },
  };
  const context = { window: {}, document,
    MutationObserver: class { constructor() { throw Error('Do not observe Claude streaming DOM'); } },
  };
  vm.runInNewContext(script, context);
  function emit(type, props = {}) {
    const event = { target: editor, preventDefault: () => blocked++,
      stopImmediatePropagation: () => {}, ...props };
    listeners[type](event);
  }
  action({ emit, editor, button, document, hints: () => hints });
  assert.deepEqual([clicks, blocked], expected, name);
}

test('new composer is marked only when focused', ({ emit, editor, hints }) => {
  assert.equal(hints(), 1);
  emit('focusin', { target: editor });
  assert.equal(hints(), 2);
}, [0, 0]);
test('plain Enter sends', ({ emit }) => emit('keydown', { key: 'Enter', keyCode: 13 }), [1, 1]);
test('mobile beforeinput sends', ({ emit }) => emit('beforeinput', { inputType: 'insertParagraph' }), [1, 1]);
test('Shift+Enter remains newline', ({ emit }) => {
  emit('keydown', { key: 'Enter', keyCode: 13, shiftKey: true });
  emit('beforeinput', { inputType: 'insertLineBreak' });
}, [0, 0]);
test('IME composition confirms text first', ({ emit }) => {
  emit('compositionstart');
  emit('keydown', { key: 'Enter', keyCode: 13, isComposing: true });
  emit('beforeinput', { inputType: 'insertParagraph', isComposing: true });
  emit('compositionend');
}, [0, 0]);
test('other input not affected', ({ emit, document }) => {
  document.activeElement = { matches: () => false };
  emit('keydown', { key: 'Enter', keyCode: 13 });
}, [0, 0]);
test('disabled send button does not consume Enter', ({ emit, button }) => {
  button.disabled = true;
  emit('keydown', { key: 'Enter', keyCode: 13 });
}, [0, 0]);
console.log('send-enter tests passed');
