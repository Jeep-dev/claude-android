(() => {
  if (window.top !== window || window.__claudeSelectionGuard) return;
  window.__claudeSelectionGuard = true;

  // A touch long-press on message text starts Android's native text selection, and Claude's
  // own long-press message drawer opens as well (grey overlay plus a bottom sheet over the
  // selection). When a touch creates a text selection, a dialog/drawer that appeared since
  // that touch began is closed again (Escape, then an outside press), so the native selection
  // and its handles stay usable. Dialogs that were already open, dialogs holding the selection,
  // and selections in editors are left alone. Nothing is observed outside that short window.
  const DIALOGS = '[role="dialog"], [role="alertdialog"], [data-vaul-drawer], [vaul-drawer]';
  const EDITABLE = 'input, textarea, [contenteditable]:not([contenteditable="false"])';
  const TOUCH_WINDOW = 3000;
  const WATCH = 1500;

  let touchAt = -Infinity;
  let rangeBefore = null;
  let before = new Set();
  let watching = null;
  let watchTimer = 0;
  const closed = new WeakSet();

  const now = () => Date.now();
  const dialogs = () => Array.from(document.querySelectorAll(DIALOGS));

  const currentRange = selection => (selection && !selection.isCollapsed && selection.rangeCount
    ? selection.getRangeAt(0).cloneRange() : null);
  const sameRange = (a, b) => !!a && !!b && a.startContainer === b.startContainer
    && a.startOffset === b.startOffset && a.endContainer === b.endContainer
    && a.endOffset === b.endOffset;

  function nodeElement(node) {
    return node && node.nodeType === 1 ? node : node && node.parentElement;
  }

  /** The selection the user just made by touch, outside editors; null otherwise. */
  function touchSelection() {
    if (now() - touchAt > TOUCH_WINDOW) return null;
    const selection = document.getSelection();
    const range = currentRange(selection);
    // A selection that was already there when the touch began (e.g. a tap on a button that
    // opens a dialog) was not made by this touch: leave that touch's dialogs alone.
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
    const target = document.activeElement || document.body || document.documentElement;
    target.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true,
    }));
    // Not every drawer closes on Escape: press outside it once if it is still open.
    setTimeout(() => {
      if (!dialog.isConnected || dialog.getAttribute('data-state') === 'closed') return;
      press(document.documentElement, 'pointerdown');
      press(document.documentElement, 'mousedown');
      press(document.documentElement, 'pointerup');
      press(document.documentElement, 'mouseup');
    }, 150);
  }

  function sweep() {
    const anchor = touchSelection();
    if (!anchor) return stop();
    for (const dialog of dialogs()) {
      if (before.has(dialog) || closed.has(dialog) || dialog.contains(anchor)) continue;
      if (dialog.getAttribute('data-state') === 'closed') continue;
      close(dialog);
    }
  }

  function stop() {
    if (watching) watching.disconnect();
    watching = null;
    clearTimeout(watchTimer);
  }

  function watch() {
    sweep();
    if (!touchSelection() || !document.body) return;
    // The drawer may open on a long-press timer after the selection exists: watch briefly.
    clearTimeout(watchTimer);
    if (!watching) {
      watching = new MutationObserver(sweep);
      watching.observe(document.body, { childList: true, subtree: true });
    }
    watchTimer = setTimeout(stop, WATCH);
  }

  window.addEventListener('touchstart', event => {
    if (event.touches && event.touches.length > 1) return;
    rangeBefore = currentRange(document.getSelection());
    touchAt = now();
    before = new Set(dialogs());
  }, { capture: true, passive: true });

  // The drawer may also open when the long-press is released.
  window.addEventListener('touchend', () => {
    if (touchSelection()) {
      touchAt = now();
      watch();
    }
  }, { capture: true, passive: true });

  document.addEventListener('selectionchange', () => {
    if (touchSelection()) watch();
  });
})();
