(() => {
  if (window.top !== window || window.__claudeSelectionPaint) return;
  window.__claudeSelectionPaint = true;

  // While text is selected, some devices draw Claude's large solid page backgrounds as black
  // (text, gradients and smaller surfaces stay correct; the DOM colours are unchanged). Solid
  // backgrounds can be drawn by the compositor as plain colour quads, gradients are rasterised
  // with the content. While a selection exists, large opaque backgrounds on screen get an
  // identical single-colour gradient as background image; it is removed when the selection
  // ends, restoring the page's own inline value.
  const DIAG = true; // TEMPORARY: logs to logcat (tag ClaudeDiag) through the console.
  const log = message => { if (DIAG) console.info('[ClaudeDiag] ' + message); };
  const patched = new Map();
  let timer = 0;
  let frame = 0;

  function alpha(color) {
    if (!color || color === 'transparent') return 0;
    const slash = /\/\s*([\d.]+%?)\s*\)$/.exec(color);
    const rgba = /^rgba\([^)]*,\s*([\d.]+)\)$/.exec(color);
    const value = slash ? slash[1] : rgba ? rgba[1] : '1';
    return value.endsWith('%') ? parseFloat(value) / 100 : parseFloat(value);
  }

  function large(el) {
    const r = el.getBoundingClientRect();
    return r.width >= window.innerWidth * 0.5 && r.height >= 40;
  }

  function candidates() {
    const found = new Set([document.documentElement, document.body]);
    if (!document.elementsFromPoint) return found;
    for (const fx of [0.1, 0.5, 0.9]) {
      for (const fy of [0.05, 0.2, 0.4, 0.6, 0.8, 0.95]) {
        for (const el of document.elementsFromPoint(window.innerWidth * fx, window.innerHeight * fy)) {
          found.add(el);
        }
      }
    }
    return found;
  }

  function apply() {
    let count = 0;
    for (const el of candidates()) {
      if (!el || patched.has(el) || !el.style) continue;
      const style = getComputedStyle(el);
      if (style.backgroundImage !== 'none' || alpha(style.backgroundColor) < 1 || !large(el)) continue;
      patched.set(el, [el.style.getPropertyValue('background-image'),
        el.style.getPropertyPriority('background-image')]);
      const color = style.backgroundColor;
      el.style.setProperty('background-image', 'linear-gradient(' + color + ', ' + color + ')', 'important');
      count++;
    }
    if (count) log('paint: gradient on ' + count + ' backgrounds');
  }

  function restore() {
    if (!patched.size) return;
    for (const [el, [value, priority]] of patched) {
      if (value) el.style.setProperty('background-image', value, priority);
      else el.style.removeProperty('background-image');
    }
    log('paint: restored ' + patched.size + ' backgrounds');
    patched.clear();
  }

  document.addEventListener('selectionchange', () => {
    clearTimeout(timer);
    const selection = document.getSelection();
    if (!selection || selection.isCollapsed) {
      // Collapsing briefly while handles are adjusted is common: restore only when it stays.
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
