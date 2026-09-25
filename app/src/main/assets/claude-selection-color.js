(() => {
  if (window.top !== window || window.__claudeSelectionColor) return;
  window.__claudeSelectionColor = true;

  // While text is selected, backgrounds whose colour is written as CSS color(srgb …) are drawn
  // black on the reported device (Claude's main panel, header, sidebars), while rgb()
  // backgrounds (composer, code blocks, page body) stay correct; the DOM colours are
  // unchanged. While a selection exists, such backgrounds on screen get the identical colour
  // as an inline rgb()/rgba() value; the page's own inline value is restored afterwards.
  const SRGB = /^color\(srgb\s+([\d.e+-]+)\s+([\d.e+-]+)\s+([\d.e+-]+)(?:\s*\/\s*([\d.e+-]+%?))?\s*\)$/;
  const patched = new Map();
  let timer = 0;
  let frame = 0;

  function toRgb(color) {
    const m = SRGB.exec(color || '');
    if (!m) return null;
    const channel = v => Math.round(Math.min(1, Math.max(0, parseFloat(v))) * 255);
    let alpha = m[4] == null ? 1 : m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4]);
    alpha = Math.min(1, Math.max(0, alpha));
    const rgb = channel(m[1]) + ', ' + channel(m[2]) + ', ' + channel(m[3]);
    return alpha === 1 ? 'rgb(' + rgb + ')' : 'rgba(' + rgb + ', ' + alpha + ')';
  }

  // Elements on screen: those hit on a grid of points, plus all their ancestors.
  function onScreen() {
    const found = new Set();
    if (!document.elementsFromPoint) return found;
    for (let fx = 0.05; fx < 1; fx += 0.225) {
      for (let fy = 0.02; fy < 1; fy += 0.08) {
        for (let el of document.elementsFromPoint(window.innerWidth * fx, window.innerHeight * fy)) {
          for (; el && !found.has(el); el = el.parentElement) found.add(el);
        }
      }
    }
    return found;
  }

  function apply() {
    for (const el of onScreen()) {
      if (patched.has(el) || !el.style) continue;
      const rgb = toRgb(getComputedStyle(el).backgroundColor);
      if (!rgb) continue;
      patched.set(el, [el.style.getPropertyValue('background-color'),
        el.style.getPropertyPriority('background-color')]);
      el.style.setProperty('background-color', rgb, 'important');
    }
  }

  function restore() {
    for (const [el, [value, priority]] of patched) {
      if (value) el.style.setProperty('background-color', value, priority);
      else el.style.removeProperty('background-color');
    }
    patched.clear();
  }

  document.addEventListener('selectionchange', () => {
    clearTimeout(timer);
    const selection = document.getSelection();
    if (!selection || selection.isCollapsed) {
      // The selection may collapse briefly while handles are adjusted: restore only if it stays.
      timer = setTimeout(() => {
        const current = document.getSelection();
        if (!current || current.isCollapsed) restore();
      }, 300);
      return;
    }
    // Handle drags fire many selection changes: look at the screen once per frame.
    if (!frame) frame = requestAnimationFrame(() => { frame = 0; apply(); });
  });
})();
