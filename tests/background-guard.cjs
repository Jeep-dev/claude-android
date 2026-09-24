const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync('app/src/main/assets/claude-background-guard.js', 'utf8');

// Minimal DOM: <html> and <body> with inline style and a stylesheet colour.
function element(tag, sheetColor) {
  const inline = {};
  const el = {
    tagName: tag.toUpperCase(), id: '', className: '', parentElement: null, sheetColor,
    style: {
      get background() { return inline.background || ''; },
      get backgroundColor() { return inline['background-color'] || ''; },
      removeProperty: name => { delete inline[name]; },
      set: (name, value) => { inline[name] = value; },
    },
    getAttribute: name => (name === 'style'
      ? Object.entries(inline).map(([k, v]) => `${k}: ${v}`).join('; ') : null),
    setAttribute() {},
    appendChild() {},
    remove() {},
  };
  el.computed = () => (inline.background === 'black' || inline['background-color'] === 'black'
    ? 'rgb(0, 0, 0)' : inline['background-color'] || el.sheetColor);
  return el;
}

function setup() {
  const html = element('html', 'rgba(0, 0, 0, 0)');
  const body = element('body', 'rgb(250, 249, 245)');
  body.parentElement = html;
  let observerCallback;
  const document = {
    documentElement: html, body,
    querySelector: () => null,
    elementFromPoint: () => body,
    createElement: () => ({ setAttribute() {}, style: {}, remove() {}, isConnected: false }),
    addEventListener() {},
    getSelection: () => null,
  };
  const window = { innerWidth: 400, innerHeight: 800 };
  window.top = window;
  vm.runInNewContext(script, {
    window, document, setTimeout: () => 0, clearTimeout() {},
    getComputedStyle: el => ({ backgroundColor: el.computed() }),
    MutationObserver: class { constructor(cb) { observerCallback = cb; } observe() {} },
  });
  const mutate = (el, name, value) => { el.style.set(name, value); observerCallback([{ target: el, attributeName: 'style' }]); };
  return { html, body, mutate };
}

{
  const { body, mutate } = setup();
  mutate(body, 'background', 'black');
  assert.equal(body.computed(), 'rgb(250, 249, 245)', 'inline black body background is removed');
}
{
  const { html, mutate } = setup();
  mutate(html, 'background-color', 'black');
  assert.equal(html.computed(), 'rgba(0, 0, 0, 0)', 'inline black html background is removed');
}
{
  const { body, mutate } = setup();
  mutate(body, 'background-color', 'rgb(38, 38, 36)');
  assert.equal(body.computed(), 'rgb(38, 38, 36)', "Claude's dark theme colour is kept");
}
{
  let added = 0;
  vm.runInNewContext(script, { window: { top: {} }, document: { addEventListener: () => added++ } });
  assert.equal(added, 0, 'iframe ignored');
}
console.log('background-guard tests passed');
