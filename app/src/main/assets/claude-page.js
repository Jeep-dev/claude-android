(() => {
  if (window.__claudePage) return;
  window.__claudePage = true;

  // Keyboard behaviour on claude.ai:
  // 1. Claude moving the cursor into a text field by script (after switching chats, after
  //    sending) opens no keyboard: focus() on a text field is ignored unless that field was
  //    just touched or a text field already has focus. A tap on the message box's own
  //    controls (e.g. the suggested prompt's Enter icon) lets the focus through, but without
  //    a keyboard (inputmode "none") until the field itself is touched.
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

  // A field focused without a keyboard, and its own inputmode to put back.
  let quiet = null;
  let quietMode = null;
  function unquiet() {
    if (!quiet) return;
    if (quietMode === null) quiet.removeAttribute('inputmode');
    else quiet.setAttribute('inputmode', quietMode);
    quiet = null;
  }

  window.addEventListener('pointerdown', event => {
    touched = event.target;
    touchedAt = Date.now();
    if (quiet && touched instanceof Node && quiet.contains(touched)) unquiet();
  }, { capture: true, passive: true });
  document.addEventListener('focusout', event => { if (event.target === quiet) unquiet(); }, true);

  // The touched control is part of the message box around the field (not its Send button):
  // a close ancestor of the field that is still small, unlike the page or the sidebar.
  function onBox(field) {
    if (!(touched instanceof Element) || touched.closest(SEND)) return false;
    for (let area = field.parentElement, i = 0; area && i < 4; area = area.parentElement, i++) {
      if (area.getBoundingClientRect().height > window.innerHeight * 0.4) return false;
      if (area.contains(touched)) return true;
    }
    return false;
  }

  const focus = HTMLElement.prototype.focus;
  HTMLElement.prototype.focus = function (options) {
    if (editable(this) && !editable(document.activeElement)) {
      const recent = touched instanceof Node && Date.now() - touchedAt <= RECENT_MS;
      if (!recent) return;
      if (!this.contains(touched)) {
        if (!onBox(this)) return;
        unquiet();
        quiet = this;
        quietMode = this.getAttribute('inputmode');
        this.setAttribute('inputmode', 'none');
      }
    }
    return focus.call(this, options);
  };

  document.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey) return;
    if (event.isComposing || event.keyCode === 229) return;
    const field = event.target;
    if (!editable(field) || field.tagName === 'INPUT') return;
    // An empty box: Enter is Claude's own (e.g. it sends the suggested prompt shown there).
    const text = field.tagName === 'TEXTAREA' ? field.value : field.textContent;
    if (!(text || '').trim()) return;
    const buttons = sendButtons(field);
    if (!buttons.length) return; // No Send button here (e.g. Claude is replying): a new line.
    event.preventDefault();
    event.stopImmediatePropagation();
    // A disabled Send (e.g. an upload in progress) means nothing to send, and no new line either.
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

  // The status bar takes Claude's background colour, so it follows the Claude theme setting.
  const bar = window.ClaudeStatusBar;
  if (bar && window.getComputedStyle && window.MutationObserver) {
    const rgb = css => {
      let m = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:[\s,/]+([\d.]+%?))?/.exec(css);
      let c = m && [+m[1], +m[2], +m[3]];
      if (!m) {
        m = /^color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+%?))?/.exec(css);
        c = m && [m[1] * 255, m[2] * 255, m[3] * 255];
      }
      if (!m || (m[4] !== undefined && parseFloat(m[4]) === 0)) return null; // transparent
      return c.map(v => Math.max(0, Math.min(255, Math.round(v))));
    };
    let last = null;
    const update = () => {
      const c = (document.body && rgb(getComputedStyle(document.body).backgroundColor))
        || rgb(getComputedStyle(document.documentElement).backgroundColor);
      if (!c) return;
      const value = (c[0] << 16) | (c[1] << 8) | c[2];
      if (value !== last) { last = value; bar.setColor(value); }
    };
    // Claude switches theme through attributes on <html>/<body>; its colours may fade in.
    const soon = () => { update(); setTimeout(update, 400); };
    new MutationObserver(soon).observe(document.documentElement, { attributes: true });
    if (document.body) new MutationObserver(soon).observe(document.body, { attributes: true });
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', soon);
    soon();
  }
})();
