(() => {
  if (window.top !== window || window.__claudeSelectionGuard) return;
  window.__claudeSelectionGuard = true;

  // A touch long-press on message text starts Android's native text selection, and Claude's
  // own long-press message drawer opens as well (grey overlay plus a bottom sheet over the
  // selection), also when a selection handle is released. While a selection made by touch is
  // being made or adjusted, a dialog/drawer that appeared since the latest touch began is
  // closed again (Escape, then an outside press). Dialogs that were already open, dialogs
  // holding the selection, and selections in editors are left alone. The page is observed only
  // until 5 s after the latest touch or following selection change.
  const DIAG = true; // TEMPORARY: logs to logcat (tag ClaudeDiag) through the console.
  const DIALOGS = '[role="dialog"], [role="alertdialog"], [role="menu"], [data-vaul-drawer], '
    + '[vaul-drawer], [data-radix-popper-content-wrapper]';
  const EDITABLE = 'input, textarea, [contenteditable]:not([contenteditable="false"])';
  const QUIET = 5000;

  let touched = false;
  let activeAt = -Infinity;
  let rangeBefore = null;
  let before = new Set();
  let watching = null;
  let watchTimer = 0;
  const closed = new WeakSet();

  const now = () => Date.now();
  const dialogs = () => Array.from(document.querySelectorAll(DIALOGS));
  const log = message => { if (DIAG) console.info('[ClaudeDiag] ' + message); };
  const describe = el => {
    if (!el || !el.tagName) return '-';
    const role = el.getAttribute('role');
    const state = el.getAttribute('data-state');
    return el.tagName.toLowerCase() + (role ? '[' + role + ']' : '') + (state ? ':' + state : '');
  };

  const currentRange = selection => (selection && !selection.isCollapsed && selection.rangeCount
    ? selection.getRangeAt(0).cloneRange() : null);
  const sameRange = (a, b) => !!a && !!b && a.startContainer === b.startContainer
    && a.startOffset === b.startOffset && a.endContainer === b.endContainer
    && a.endOffset === b.endOffset;

  function nodeElement(node) {
    return node && node.nodeType === 1 ? node : node && node.parentElement;
  }

  /** The selection the user is making by touch, outside editors; null otherwise. */
  function touchSelection() {
    if (!touched || now() - activeAt > QUIET) return null;
    const selection = document.getSelection();
    const range = currentRange(selection);
    // A selection that was already there when the latest touch began (e.g. a tap on a button
    // that opens a dialog) was not made by that touch: leave that touch's dialogs alone.
    if (!range || sameRange(range, rangeBefore)) return null;
    const anchor = nodeElement(selection.anchorNode);
    if (!anchor || anchor.closest(EDITABLE)) return null;
    return anchor;
  }

  function press(target, type) {
    const init = { bubbles: true, cancelable: true, composed: true, button: 0 };
    const Event = type.startsWith('pointer') && window.PointerEvent ? window.PointerEvent : window.MouseEvent;
    target.dispatchEvent(new Event(type, init));
  }

  function close(dialog) {
    closed.add(dialog);
    log('guard: closing ' + describe(dialog));
    const target = document.activeElement || document.body || document.documentElement;
    target.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true,
    }));
    // Not every drawer closes on Escape: press outside it once if it is still open.
    setTimeout(() => {
      if (!dialog.isConnected || dialog.getAttribute('data-state') === 'closed') {
        log('guard: closed by Escape');
        return;
      }
      press(document.documentElement, 'pointerdown');
      press(document.documentElement, 'mousedown');
      press(document.documentElement, 'pointerup');
      press(document.documentElement, 'mouseup');
      setTimeout(() => log('guard: after outside press '
        + (dialog.isConnected ? describe(dialog) : 'removed')), 300);
    }, 150);
  }

  function sweep() {
    const anchor = touchSelection();
    if (!anchor) return stop();
    for (const dialog of dialogs()) {
      if (before.has(dialog) || closed.has(dialog)) continue;
      if (dialog.contains(anchor)) {
        log('guard: skip ' + describe(dialog) + ' (holds selection)');
        closed.add(dialog);
        continue;
      }
      if (dialog.getAttribute('data-state') === 'closed') continue;
      close(dialog);
    }
  }

  function stop() {
    if (!watching) return;
    log('guard: stop watching');
    watching.disconnect();
    watching = null;
    clearTimeout(watchTimer);
  }

  function watch() {
    sweep();
    if (!touchSelection() || !document.body) return;
    // The drawer may open on a long-press timer or when a handle is released: keep watching
    // until the selection has been quiet for a while.
    clearTimeout(watchTimer);
    if (!watching) {
      log('guard: watching');
      watching = new MutationObserver(sweep);
      watching.observe(document.body, { childList: true, subtree: true });
    }
    watchTimer = setTimeout(stop, QUIET);
  }

  window.addEventListener('touchstart', event => {
    if (event.touches && event.touches.length > 1) return;
    touched = true;
    activeAt = now();
    rangeBefore = currentRange(document.getSelection());
    before = new Set(dialogs());
  }, { capture: true, passive: true });

  window.addEventListener('touchend', () => {
    activeAt = now();
    if (touchSelection()) watch();
  }, { capture: true, passive: true });

  document.addEventListener('selectionchange', () => {
    const selection = document.getSelection();
    // Only a selection change following a touch (e.g. a handle drag) extends it.
    if (!selection || selection.isCollapsed || now() - activeAt > QUIET) return;
    activeAt = now();
    if (touchSelection()) watch();
  });
})();
