(() => {
  if (window.top !== window || window.__claudeDiagnostics) return;
  window.__claudeDiagnostics = true;

  // TEMPORARY diagnostics for the text-selection drawer/rendering problem. Read-only: it only
  // logs (console → logcat tag ClaudeDiag) touch/pointer events, selection changes, dialogs
  // and drawers appearing, and what is painted on screen shortly after a touch ends.
  const DIALOGISH = '[role="dialog"], [role="alertdialog"], [role="menu"], [data-vaul-drawer], '
    + '[vaul-drawer], [data-vaul-overlay], [vaul-overlay], [data-radix-popper-content-wrapper]';
  const start = Date.now();
  const log = message => console.info('[ClaudeDiag] ' + ((Date.now() - start) / 1000).toFixed(2)
    + ' ' + message);
  log('diagnostics loaded ' + location.pathname.split('/').slice(0, 2).join('/'));

  const selected = () => { const s = document.getSelection(); return s ? String(s).length : 0; };
  function describe(el) {
    if (!el || !el.tagName) return '-';
    const attr = name => el.getAttribute(name);
    const cls = typeof el.className === 'string'
      ? el.className.trim().split(/\s+/).filter(Boolean).slice(0, 2).join('.') : '';
    const vaul = Array.from(el.attributes || []).map(a => a.name).filter(n => n.includes('vaul'));
    return el.tagName.toLowerCase() + (attr('role') ? '[' + attr('role') + ']' : '')
      + (attr('data-state') ? ':' + attr('data-state') : '') + (vaul.length ? '{' + vaul.join(',') + '}' : '')
      + (cls ? '.' + cls.slice(0, 40) : '');
  }
  function rect(el) {
    const r = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    return Math.round(r.top) + '+' + Math.round(r.height) + ' ' + style.position
      + (style.transform !== 'none' ? ' tf=' + style.transform.slice(0, 40) : '')
      + ' bg=' + style.backgroundColor + ' op=' + style.opacity;
  }
  function painter(y) {
    let el = document.elementFromPoint(window.innerWidth / 2, y);
    const hit = el;
    for (; el; el = el.parentElement) {
      const bg = getComputedStyle(el).backgroundColor;
      if (bg && bg !== 'transparent' && bg !== 'rgba(0, 0, 0, 0)') break;
    }
    return describe(hit) + ' bg<' + (el ? describe(el) + ' ' + getComputedStyle(el).backgroundColor : 'none');
  }
  function snapshot(reason) {
    log('snap ' + reason + ' sel=' + selected() + ' vis=' + document.visibilityState
      + ' vh=' + window.innerHeight);
    for (const f of [0.1, 0.4, 0.7, 0.85, 0.95]) log(' y' + f + ' ' + painter(window.innerHeight * f));
    for (const d of document.querySelectorAll(DIALOGISH)) log(' dlg ' + describe(d) + ' ' + rect(d));
  }

  let moves = 0;
  let snapTimer = 0;
  const events = ['pointerdown', 'pointerup', 'pointercancel', 'touchstart', 'touchend',
    'touchcancel', 'contextmenu'];
  for (const type of events) {
    window.addEventListener(type, event => {
      if (type.startsWith('pointer') && event.pointerType !== 'touch') return;
      const y = event.clientY != null ? event.clientY
        : event.changedTouches && event.changedTouches[0] ? event.changedTouches[0].clientY : '?';
      log(type + ' y=' + Math.round(y) + (type.endsWith('start') || type.endsWith('down') ? ''
        : ' moves=' + moves) + ' sel=' + selected() + ' ' + describe(event.target));
      if (type === 'touchstart') moves = 0;
      if (type === 'touchend' || type === 'touchcancel') {
        clearTimeout(snapTimer);
        snapTimer = setTimeout(() => snapshot('after ' + type), 600);
      }
    }, { capture: true, passive: true });
  }
  window.addEventListener('touchmove', () => { moves++; }, { capture: true, passive: true });

  let selectionTimer = 0;
  document.addEventListener('selectionchange', () => {
    clearTimeout(selectionTimer);
    selectionTimer = setTimeout(() => {
      const s = document.getSelection();
      const node = s && s.anchorNode;
      log('selection len=' + selected() + ' in=' + describe(node && node.nodeType === 1 ? node : node && node.parentElement));
    }, 250);
  });

  function watchDialogs() {
    new MutationObserver(records => {
      for (const record of records) {
        if (record.type === 'attributes') {
          if (record.target.matches && record.target.matches(DIALOGISH)) log('state ' + describe(record.target));
          continue;
        }
        for (const node of record.addedNodes) {
          if (node.nodeType !== 1) continue;
          const found = node.matches(DIALOGISH) ? [node] : Array.from(node.querySelectorAll(DIALOGISH));
          for (const d of found) log('open ' + describe(d) + ' sel=' + selected() + ' ' + rect(d));
        }
        for (const node of record.removedNodes) {
          if (node.nodeType === 1 && (node.matches(DIALOGISH) || node.querySelector(DIALOGISH))) {
            log('removed ' + describe(node));
          }
        }
      }
    }).observe(document.body, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['data-state'] });
  }
  if (document.body) watchDialogs();
  else document.addEventListener('DOMContentLoaded', watchDialogs, { once: true });
})();
