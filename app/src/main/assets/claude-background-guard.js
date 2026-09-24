(() => {
  if (window.top !== window || window.__claudeBackgroundGuard) return;
  window.__claudeBackgroundGuard = true;

  // While dragging text-selection handles, Claude's page background turns black while text,
  // composer and gradients keep their light-theme colours. That matches a bottom-sheet drawer
  // setting an inline black background on <html>/<body> (e.g. vaul's scale-background effect).
  // Claude's own themes never use pure black, so an inline pure-black root background is
  // removed again, restoring the page's stylesheet colour.
  //
  // DIAGNOSE (temporary): shows a small read-only box with what the root backgrounds are and
  // which element paints the screen centre, so the cause can be confirmed from a screenshot.
  const DIAGNOSE = true;

  const isBlack = color => /^rgba?\(0,\s*0,\s*0(,\s*1)?\)$/.test(color || '');
  const background = el => getComputedStyle(el).backgroundColor;
  const lines = [];
  let box = null;
  let hideTimer = 0;

  function describe(el) {
    if (!el || !el.tagName) return '-';
    const classes = typeof el.className === 'string'
      ? el.className.trim().split(/\s+/).filter(Boolean).slice(0, 3).join('.') : '';
    return el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (classes ? '.' + classes : '');
  }

  // First element (from the screen centre outwards) that paints a background.
  function painter() {
    let el = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
    for (; el; el = el.parentElement) {
      const bg = background(el);
      if (bg && bg !== 'transparent' && bg !== 'rgba(0, 0, 0, 0)') return describe(el) + ' ' + bg;
    }
    return 'none (WebView base colour)';
  }

  function show() {
    if (!box) {
      box = document.createElement('div');
      box.setAttribute('aria-hidden', 'true');
      box.style.cssText = 'position:fixed;top:48px;left:4px;right:4px;z-index:2147483647;'
        + 'pointer-events:none;font:10px/1.35 monospace;white-space:pre-wrap;word-break:break-all;'
        + 'background:rgba(255,245,150,.95);color:#000;padding:4px;border:1px solid #a80;';
    }
    box.textContent = 'Claude App diagnostics\n' + lines.join('\n');
    if (!box.isConnected) document.documentElement.appendChild(box);
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => box.remove(), 15000);
  }

  function note(reason) {
    if (!DIAGNOSE || !document.body) return;
    const root = document.documentElement;
    const body = document.body;
    const dialog = document.querySelector('[vaul-drawer], [data-vaul-drawer], [role="dialog"]');
    const time = new Date().toISOString().slice(17, 23);
    lines.push(time + ' ' + reason
      + '\n html ' + background(root) + ' [' + (root.getAttribute('style') || '') + ']'
      + '\n body ' + background(body) + ' [' + (body.getAttribute('style') || '') + ']'
      + '\n centre ' + painter()
      + '\n dialog ' + (dialog ? describe(dialog) + ' ' + (dialog.getAttribute('data-state') || '') : 'none'));
    while (lines.length > 3) lines.shift();
    show();
  }

  function guard(el, reason, quiet) {
    const inline = el.style.background || el.style.backgroundColor;
    if (inline && isBlack(background(el))) {
      el.style.removeProperty('background');
      el.style.removeProperty('background-color');
      note(reason + ': removed inline black background');
    } else if (!quiet) {
      note(reason);
    }
  }

  function start() {
    // Attributes of <html> and <body> only: no subtree observation while Claude streams.
    const observer = new MutationObserver(records => {
      for (const record of records) guard(record.target, describe(record.target) + ' ' + record.attributeName);
    });
    const options = { attributes: true, attributeFilter: ['style', 'class'] };
    observer.observe(document.documentElement, options);
    observer.observe(document.body, options);
    guard(document.body, 'start', true);

    let selectionTimer = 0;
    document.addEventListener('selectionchange', () => {
      if (!DIAGNOSE) return;
      clearTimeout(selectionTimer);
      selectionTimer = setTimeout(() => {
        const selection = document.getSelection();
        if (selection && !selection.isCollapsed) note('selection');
      }, 400);
    });
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start, { once: true });
})();
