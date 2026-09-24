const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-long-press.js', 'utf8');

function setup({ style = {} } = {}) {
  const listeners = {};
  const timers = [];
  const window = { innerWidth: 400,
    addEventListener: (type, handler) => { (listeners[type] ||= []).push(handler); } };
  window.top = window;
  vm.runInNewContext(script, { window, getComputedStyle: () => style,
    setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout: () => {} });
  let hidden = 0;
  let prevented = 0;
  function emit(type, props) {
    const event = { stopImmediatePropagation: () => hidden++, preventDefault: () => prevented++, ...props };
    (listeners[type] || []).forEach(h => h(event));
  }
  return { emit, counts: () => [hidden, prevented], flush: () => timers.splice(0).forEach(fn => fn()) };
}
const text = (inside = null) => ({ textContent: 'Some answer text', closest: () => inside });
const touch = (target, x = 200) => ({ target, pointerType: 'touch', clientX: x, touches: [{ clientX: x }] });

{
  const { emit, counts } = setup();
  emit('pointerdown', touch(text()));
  emit('touchstart', touch(text()));
  assert.deepEqual(counts(), [2, 0], 'Claude never sees a touch starting on message text');
}
{
  const { emit, counts } = setup();
  emit('contextmenu', { target: text(), pointerType: 'touch' });
  assert.deepEqual(counts(), [1, 0], 'long-press hidden from Claude, native selection not prevented');
}
{
  const { emit, counts, flush } = setup();
  emit('contextmenu', { target: text(), pointerType: 'touch' });
  emit('pointermove', touch(text()));
  emit('touchmove', touch(text()));
  emit('pointerup', touch(text()));
  emit('touchend', { target: text(), touches: [] });
  assert.deepEqual(counts(), [5, 0], 'drag after long-press does not reach Claude (no drawer drag)');
  flush();
  emit('pointermove', touch(text()));
  assert.deepEqual(counts(), [5, 0], 'normal moves reach Claude again after release');
}
{
  const { emit, counts } = setup();
  emit('pointerdown', touch(text({})));
  emit('touchstart', touch(text({})));
  emit('contextmenu', { target: text({}), pointerType: 'touch' });
  assert.deepEqual(counts(), [0, 0], 'links/buttons/editors/dialogs keep page behavior');
}
{
  const { emit, counts } = setup();
  emit('pointerdown', touch(text(), 5));
  emit('touchstart', touch(text(), 395));
  assert.deepEqual(counts(), [0, 0], 'edge swipes stay with the page');
}
{
  const { emit, counts } = setup();
  emit('pointermove', touch(text()));
  emit('pointerdown', { target: text(), pointerType: 'mouse', clientX: 200 });
  emit('contextmenu', { target: text(), pointerType: 'mouse' });
  assert.deepEqual(counts(), [0, 0], 'mouse and ordinary scrolling moves untouched');
}
{
  const { emit, counts } = setup({ style: { userSelect: 'none' } });
  emit('pointerdown', touch(text()));
  emit('contextmenu', { target: text(), pointerType: 'touch' });
  assert.deepEqual(counts(), [0, 0], 'unselectable text keeps Claude behavior');
}
{
  const { emit, counts } = setup();
  emit('pointerdown', touch({ textContent: ' ', closest: () => null }));
  assert.deepEqual(counts(), [0, 0], 'empty area untouched');
}
{
  let added = 0;
  vm.runInNewContext(script, { window: { top: {}, addEventListener: () => added++ } });
  assert.equal(added, 0, 'iframe ignored');
}
console.log('long-press tests passed');
