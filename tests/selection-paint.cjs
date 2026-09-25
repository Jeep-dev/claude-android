const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-selection-paint.js', 'utf8');

function element(bg, image = 'none', width = 400, height = 800) {
  const props = {};
  return {
    computed: { backgroundColor: bg, backgroundImage: image }, props,
    getBoundingClientRect: () => ({ width, height }),
    style: {
      getPropertyValue: n => (props[n] ? props[n][0] : ''),
      getPropertyPriority: n => (props[n] ? props[n][1] : ''),
      setProperty: (n, v, p = '') => { props[n] = [v, p]; },
      removeProperty: n => { delete props[n]; },
    },
  };
}

const html = element('rgba(0, 0, 0, 0)');
const body = element('rgb(250, 249, 245)');
const main = element('color(srgb 0.988 0.988 0.984)');
const chip = element('color(srgb 0.04 0.04 0.04 / 0.05)');
const small = element('rgb(255, 255, 255)', 'none', 100, 30);
const faded = element('rgb(255, 255, 255)', 'linear-gradient(red, blue)');
const preset = element('rgb(1, 2, 3)');
preset.style.setProperty('background-image', 'url(x)', '');
preset.computed.backgroundImage = 'none';

let collapsed = true;
const timers = [];
const frames = [];
const listeners = {};
const document = {
  documentElement: html, body,
  elementsFromPoint: () => [chip, small, faded, main, preset],
  getSelection: () => ({ isCollapsed: collapsed }),
  addEventListener: (t, h) => { listeners[t] = h; },
};
const window = { innerWidth: 400, innerHeight: 800 };
window.top = window;
vm.runInNewContext(script, {
  window, document, console: { info() {} },
  getComputedStyle: el => el.computed,
  setTimeout: fn => timers.push(fn), clearTimeout: () => {},
  requestAnimationFrame: fn => frames.push(fn),
});

collapsed = false;
listeners.selectionchange();
listeners.selectionchange();
assert.equal(frames.length, 1, 'one screen pass per frame');
frames.shift()();
assert.deepEqual(body.props['background-image'], ['linear-gradient(rgb(250, 249, 245), rgb(250, 249, 245))', 'important']);
assert.ok(main.props['background-image'][0].startsWith('linear-gradient(color(srgb'), 'opaque color() background');
for (const [el, name] of [[html, 'transparent'], [chip, 'translucent'], [small, 'small'], [faded, 'own image']]) {
  assert.equal(el.props['background-image'], undefined, name);
}

collapsed = true;
listeners.selectionchange();
timers.shift()();
assert.equal(body.props['background-image'], undefined, 'restored');
assert.deepEqual(preset.props['background-image'], ['url(x)', ''], 'page inline value restored');
console.log('selection-paint: all tests passed');
