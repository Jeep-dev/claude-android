(() => {
  if (window.top !== window || window.__claudeStandaloneLongPress) return;
  window.__claudeStandaloneLongPress = true;

  // Long-press on message text must only start Android's native text selection. Claude also
  // opens its own long-press sheet (a drawer that dims and scales the whole page); with both
  // active, keeping the finger down and dragging to extend the selection also drags that
  // drawer, and the screen changes colour. So Claude never sees:
  //  - touches that start on plain selectable text (its long-press timer cannot start),
  //  - the browser's long-press (contextmenu) on such text,
  //  - the moves/release of a native selection gesture.
  // Default actions are never prevented: scrolling, taps (click) and selection still work.
  const interactive = 'a, button, input, textarea, select, label, summary, video, audio, canvas, '
    + 'iframe, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], '
    + '[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"], [role="slider"], '
    + '[data-vaul-drawer], [vaul-drawer]';
  // Touches starting at the screen edges stay with the page (e.g. swipe to open the sidebar).
  const edge = 24;
  let selecting = false;
  let endTimer = 0;

  function plainText(target, x) {
    if (!target?.closest || target.closest(interactive)) return false;
    if (typeof x === 'number' && (x < edge || x > window.innerWidth - edge)) return false;
    if (!(target.textContent || '').trim()) return false;
    const style = getComputedStyle(target);
    return (style.userSelect || style.webkitUserSelect) !== 'none';
  }

  const hide = e => e.stopImmediatePropagation();
  // Passive: never delays scrolling.
  const passive = { capture: true, passive: true };

  window.addEventListener('pointerdown', e => {
    if (e.pointerType === 'touch' && plainText(e.target, e.clientX)) hide(e);
  }, passive);
  window.addEventListener('touchstart', e => {
    const touch = e.touches && e.touches[0];
    if (e.touches.length === 1 && plainText(e.target, touch && touch.clientX)) hide(e);
  }, passive);

  window.addEventListener('contextmenu', e => {
    if (e.pointerType && e.pointerType !== 'touch') return;
    if (!plainText(e.target)) return;
    hide(e);
    selecting = true;
    clearTimeout(endTimer);
  }, true);

  for (const type of ['pointermove', 'touchmove']) {
    window.addEventListener(type, e => { if (selecting) hide(e); }, passive);
  }
  for (const type of ['pointerup', 'pointercancel', 'touchend', 'touchcancel']) {
    window.addEventListener(type, e => {
      if (!selecting) return;
      hide(e);
      // pointerup and touchend of the same release arrive back to back; hide both.
      clearTimeout(endTimer);
      endTimer = setTimeout(() => { selecting = false; }, 50);
    }, passive);
  }
})();
