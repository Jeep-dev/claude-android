const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-long-press.js', 'utf8');

function run(target, props = {}, style = {}) {
  let handler;
  const window = { addEventListener: (type, h) => { if (type === 'contextmenu') handler = h; } };
  window.top = window;
  vm.runInNewContext(script, { window, getComputedStyle: () => style });
  let stopped = 0;
  let prevented = 0;
  handler({ target, pointerType: 'touch', stopImmediatePropagation: () => stopped++,
    preventDefault: () => prevented++, ...props });
  return [stopped, prevented];
}
const text = (inside = null) => ({ textContent: 'Some answer text', closest: () => inside });

assert.deepEqual(run(text()), [1, 0], 'touch long-press on text keeps native selection');
assert.deepEqual(run(text({})), [0, 0], 'links/buttons/images/editors keep page behavior');
assert.deepEqual(run(text(), { pointerType: 'mouse' }), [0, 0], 'mouse right-click untouched');
assert.deepEqual(run(text(), {}, { userSelect: 'none' }), [0, 0], 'unselectable text untouched');
assert.deepEqual(run({ textContent: ' ', closest: () => null }), [0, 0], 'empty area untouched');
{
  let added = 0;
  vm.runInNewContext(script, { window: { top: {}, addEventListener: () => added++ } });
  assert.equal(added, 0, 'iframe ignored');
}
console.log('long-press tests passed');
