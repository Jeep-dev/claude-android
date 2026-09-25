(() => {
  if (window.__noAutoKeyboard) return;
  window.__noAutoKeyboard = true;

  // Claude moves the cursor into its message box by script (e.g. after switching chats),
  // which opens the keyboard. Such a focus() is ignored unless the user just touched that
  // message box or its surroundings, or a text field already has focus (keyboard is up).
  const EDITABLE = 'input, textarea, [contenteditable]:not([contenteditable="false"])';
  const RECENT_MS = 1000;
  let touched = null;
  let touchedAt = 0;

  const editable = el => !!(el && el.matches && el.matches(EDITABLE)
    && !(el.tagName === 'INPUT' && /^(button|checkbox|radio|submit|reset|file|range|color|image)$/i.test(el.type)));

  // The message box together with its controls: the nearest ancestor that also holds a button.
  function surroundings(el) {
    let area = el;
    for (let i = 0; i < 5 && area.parentElement; i++) {
      area = area.parentElement;
      if (area.querySelector('button')) break;
    }
    return area;
  }

  window.addEventListener('pointerdown', event => {
    touched = event.target;
    touchedAt = Date.now();
  }, { capture: true, passive: true });

  const focus = HTMLElement.prototype.focus;
  HTMLElement.prototype.focus = function (options) {
    if (editable(this) && !editable(document.activeElement)) {
      const recent = Date.now() - touchedAt < RECENT_MS && touched instanceof Node
        && surroundings(this).contains(touched);
      if (!recent) return;
    }
    return focus.call(this, options);
  };
})();
