(() => {
  if (window.__claudePage) return;
  window.__claudePage = true;

  // Keyboard behaviour on claude.ai:
  // 1. Claude moving the cursor into a text field by script (after switching chats, after
  //    sending) opens no keyboard: focus() on a text field is ignored unless that field was
  //    just touched or a text field already has focus.
  // 2. Enter sends (clicks the message box's Send button); Shift+Enter makes a new line.
  //    Enter that confirms an input-method candidate only confirms it.
  // 3. After sending, the keyboard closes.
  const EDITABLE = 'textarea, input, [contenteditable]:not([contenteditable="false"])';
  const SEND = 'button[aria-label*="send" i], button[aria-label*="submit" i], button[type="submit"]';
  const RECENT_MS = 1000;
  let touched = null;
  let touchedAt = 0;

  const editable = el => !!(el && el.matches && el.matches(EDITABLE)
    && !(el.tagName === 'INPUT' && !/^(text|search|url|email|tel|password)?$/i.test(el.type || '')));

  // The Send buttons next to a text field: the first ancestor that holds any decides.
  function sendButtons(field) {
    for (let area = field.parentElement, i = 0; area && i < 8; area = area.parentElement, i++) {
      const buttons = Array.from(area.querySelectorAll(SEND));
      if (buttons.length) return buttons;
    }
    return [];
  }

  function closeKeyboard() {
    const field = document.activeElement;
    if (editable(field)) field.blur();
  }

  window.addEventListener('pointerdown', event => {
    touched = event.target;
    touchedAt = Date.now();
  }, { capture: true, passive: true });

  const focus = HTMLElement.prototype.focus;
  HTMLElement.prototype.focus = function (options) {
    if (editable(this) && !editable(document.activeElement)) {
      const onField = touched instanceof Node && this.contains(touched);
      if (!onField || Date.now() - touchedAt > RECENT_MS) return;
    }
    return focus.call(this, options);
  };

  document.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey) return;
    if (event.isComposing || event.keyCode === 229) return;
    const field = event.target;
    if (!editable(field) || field.tagName === 'INPUT') return;
    const buttons = sendButtons(field);
    if (!buttons.length) return; // No Send button here (e.g. Claude is replying): a new line.
    event.preventDefault();
    event.stopImmediatePropagation();
    // A disabled Send (empty message) means nothing to send, and no new line either.
    const send = buttons.find(b => !b.disabled && b.getClientRects().length);
    if (send) send.click();
  }, true);

  // Sending by Enter or by tapping Send: close the keyboard once Claude has taken the text.
  document.addEventListener('click', event => {
    const button = event.target instanceof Element && event.target.closest(SEND);
    if (!button) return;
    touched = button; // A touch on Send does not let Claude reopen the keyboard.
    setTimeout(closeKeyboard, 0);
    setTimeout(closeKeyboard, 300);
  }, true);

  // Show a Send key on the keyboard for message boxes.
  document.addEventListener('focusin', event => {
    const field = event.target;
    if (editable(field) && field.tagName !== 'INPUT' && sendButtons(field).length) {
      field.setAttribute('enterkeyhint', 'send');
    }
  }, true);
})();
