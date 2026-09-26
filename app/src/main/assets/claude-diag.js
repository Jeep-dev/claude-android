// TEMPORARY diagnostics. Saves what the page is made of (never its text) to files the user
// sends back: the message box around a touch, and the page's styling while text is selected.
(() => {
  if (window.__claudeDiag || !window.ClaudeDiag) return;
  window.__claudeDiag = true;
  const bridge = window.ClaudeDiag;
  const EDITABLE = 'textarea, [contenteditable]:not([contenteditable="false"])';
  const clip = (v, n) => (v && v.length > n ? v.slice(0, n) + '…' : v);
  const rect = el => { const r = el.getBoundingClientRect();
    return [r.left, r.top, r.width, r.height].map(Math.round).join(','); };

  // One element: tag, attributes (text replaced by its length), place, and the styles that matter.
  function describe(el, styles) {
    const out = { tag: el.tagName.toLowerCase(), rect: rect(el) };
    for (const a of el.attributes) {
      if (a.name === 'style' || a.name === 'd' || a.name === 'src' || a.name === 'href') {
        out[a.name] = '(' + a.value.length + ')';
      } else if (/^(aria-label|placeholder|data-placeholder|title|alt|value)$/.test(a.name)) {
        out[a.name] = a.value.length <= 2 ? a.value : '(' + a.value.length + ' chars)';
      } else {
        out[a.name] = clip(a.value, 200);
      }
    }
    const own = Array.from(el.childNodes).filter(n => n.nodeType === 3).map(n => n.textContent).join('').trim();
    if (own) out.text = own.length <= 2 ? own : '(' + own.length + ' chars)';
    const cs = getComputedStyle(el);
    for (const p of styles) {
      const v = cs.getPropertyValue(p);
      if (v && !/^(none|auto|normal|visible|0|1|static|rgba\(0, 0, 0, 0\))$/.test(v)) out[p] = clip(v, 160);
    }
    return out;
  }

  const BOX_STYLES = ['pointer-events', 'position', 'display', 'cursor', 'background-color', 'color', 'opacity'];
  function tree(el, depth) {
    const node = describe(el, BOX_STYLES);
    if (depth < 14 && el.children.length) {
      node.children = Array.from(el.children).slice(0, 40).map(c => tree(c, depth + 1));
    }
    return node;
  }

  // The message box: the touched field's close ancestors that are still small, and siblings.
  function composer(target) {
    let field = target.closest && target.closest(EDITABLE);
    let area = target;
    for (let i = 0; !field && area && i < 8; area = area.parentElement, i++) {
      field = area.querySelector && area.querySelector(EDITABLE);
    }
    if (!field) return null;
    let box = field;
    for (let a = field.parentElement, i = 0; a && i < 8; a = a.parentElement, i++) {
      if (a.getBoundingClientRect().height > innerHeight * 0.5) break;
      box = a;
    }
    return box;
  }

  function env() {
    return { ua: navigator.userAgent, dpr: devicePixelRatio, size: innerWidth + 'x' + innerHeight,
      dark: matchMedia('(prefers-color-scheme: dark)').matches,
      p3: matchMedia('(color-gamut: p3)').matches, url: location.pathname.replace(/[0-9a-f-]{20,}/g, ':id'),
      htmlClass: document.documentElement.className, htmlAttrs: Array.from(document.documentElement.attributes)
        .map(a => a.name + '=' + clip(a.value, 60)).join(' ') };
  }

  window.addEventListener('touchstart', event => {
    const box = composer(event.target);
    if (!box) return;
    const t = event.touches[0];
    bridge.save('composer', JSON.stringify({ env: env(), touch: [Math.round(t.clientX), Math.round(t.clientY)],
      target: describe(event.target, BOX_STYLES), box: tree(box, 0) }));
  }, { capture: true, passive: true });

  // While text is selected: the stack of elements at a grid of points, with their painting styles,
  // and the stylesheets' colour syntax and ::selection rules.
  const PAINT = ['background-color', 'background-image', 'color', 'opacity', 'filter', 'backdrop-filter',
    'mix-blend-mode', 'isolation', 'transform', 'will-change', 'contain', 'content-visibility',
    'mask-image', '-webkit-mask-image', 'position', 'z-index', 'overflow', 'color-scheme', 'forced-color-adjust'];
  function sheets() {
    const found = { rules: 0, colorSrgb: 0, oklch: 0, oklab: 0, colorMix: 0, p3: 0, lab: 0, backdrop: 0,
      selection: [], blocked: 0, samples: [] };
    for (const sheet of document.styleSheets) {
      let rules;
      try { rules = sheet.cssRules; } catch (e) { found.blocked++; continue; }
      const walk = list => { for (const r of list) {
        if (r.cssRules) walk(r.cssRules);
        const t = r.cssText || '';
        found.rules++;
        if (t.includes('color(srgb')) { found.colorSrgb++; if (found.samples.length < 8) found.samples.push(clip(t, 200)); }
        if (t.includes('oklch(')) found.oklch++;
        if (t.includes('oklab(')) found.oklab++;
        if (t.includes('color-mix(')) found.colorMix++;
        if (t.includes('display-p3')) found.p3++;
        if (/\blab\(|\blch\(/.test(t)) found.lab++;
        if (t.includes('backdrop-filter')) found.backdrop++;
        if (t.includes('::selection') || t.includes(':selection')) found.selection.push(clip(t, 300));
      } };
      walk(rules);
    }
    found.selection = found.selection.slice(0, 20);
    return found;
  }
  function stacks() {
    const seen = new Map();
    const points = [];
    for (let y = 0.05; y < 1; y += 0.1) for (let x = 0.1; x < 1; x += 0.4) {
      const px = Math.round(innerWidth * x), py = Math.round(innerHeight * y);
      const ids = [];
      for (const el of document.elementsFromPoint(px, py)) {
        if (!seen.has(el)) seen.set(el, seen.size);
        ids.push(seen.get(el));
      }
      points.push({ at: [px, py], stack: ids });
    }
    const elements = [];
    for (const [el, id] of seen) elements[id] = describe(el, PAINT);
    return { points, elements };
  }
  let timer = 0;
  document.addEventListener('selectionchange', () => {
    const sel = getSelection();
    if (!sel || sel.isCollapsed) return;
    clearTimeout(timer);
    timer = setTimeout(() => {
      bridge.save('selection', JSON.stringify({ env: env(), sheets: sheets(), stacks: stacks() }));
    }, 800);
  });
})();
