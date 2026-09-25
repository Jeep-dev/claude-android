const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-selection-color.js', 'utf8');

function element(bg, parent = null) {
  const props = {};
  return {
    computed: { backgroundColor: bg }, props, parentElement: parent,
    style: {
      getPropertyValue: n => (props[n] ? props[n][0] : ''),
      getPropertyPriority: n => (props[n] ? props[n][1] : ''),
      setProperty: (n, v, p = '') => { props[n] = [v, p]; },
      removeProperty: n => { delete props[n]; },
    },
  };
}

const body = element('rgb(250, 249, 245)');
const main = element('color(srgb 0.988235 0.988235 0.984314)', body);
const chip = element('color(srgb 0.0431373 0.0431373 0.0431373 / 0.05)', main);
const composer = element('rgb(255, 255, 255)', main);
const clear = element('rgba(0, 0, 0, 0)', main);
const header = element('color(srgb 1 1 1)', body);
header.style.setProperty('background-color', 'red', '');
header.computed.backgroundColor = 'color(srgb 1 1 1)';

let collapsed = true;
const timers = [];
const frames = [];
const listeners = {};
const document = {
  elementsFromPoint: () => [chip, composer, clear, header],
  getSelection: () => ({ isCollapsed: collapsed }),
  addEventListener: (t, h) => { listeners[t] = h; },
};
const window = { innerWidth: 400, innerHeight: 800 };
window.top = window;
vm.runInNewContext(script, {
  window, document, getComputedStyle: el => el.computed,
  setTimeout: fn => timers.push(fn), clearTimeout: () => {},
  requestAnimationFrame: fn => frames.push(fn),
});

collapsed = false;
listeners.selectionchange();
listeners.selectionchange();
assert.equal(frames.length, 1, 'one screen pass per frame');
frames.shift()();
assert.deepEqual(main.props['background-color'], ['rgb(252, 252, 251)', 'important'], 'ancestor panel');
assert.deepEqual(chip.props['background-color'], ['rgba(11, 11, 11, 0.05)', 'important'], 'translucent');
assert.deepEqual(header.props['background-color'], ['rgb(255, 255, 255)', 'important'], 'header');
for (const [el, name] of [[body, 'rgb body'], [composer, 'rgb composer'], [clear, 'transparent']]) {
  assert.equal(el.props['background-color'], undefined, name);
}

collapsed = true;
listeners.selectionchange();
timers.shift()();
assert.equal(main.props['background-color'], undefined, 'restored');
assert.deepEqual(header.props['background-color'], ['red', ''], 'page inline value restored');
console.log('selection-color: all tests passed');
