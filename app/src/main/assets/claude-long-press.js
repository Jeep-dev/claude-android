(() => {
  if (window.top !== window || window.__claudeStandaloneLongPress) return;
  window.__claudeStandaloneLongPress = true;

  // A touch long-press on selectable text starts Android's text selection. Claude's own
  // long-press message sheet opens at the same time, and the two keep cancelling each
  // other (the screen flashes and nothing can be copied). Let the native selection win.
  const keepDefault = 'a, button, img, video, audio, canvas, svg, input, textarea, select, '
    + '[contenteditable="true"], [contenteditable=""], [role="button"], [role="menuitem"]';

  window.addEventListener('contextmenu', e => {
    if (e.pointerType && e.pointerType !== 'touch') return;
    const target = e.target;
    if (!target?.closest || target.closest(keepDefault)) return;
    if (!(target.textContent || '').trim()) return;
    const style = getComputedStyle(target);
    if ((style.userSelect || style.webkitUserSelect) === 'none') return;
    // Only stop the page from seeing it; the browser default (selection) still runs.
    e.stopImmediatePropagation();
  }, true);
})();
