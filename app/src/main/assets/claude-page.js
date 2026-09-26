(() => {
  if (window.__claudePage) return;
  window.__claudePage = true;

  // Keyboard behaviour on claude.ai:
  // 1. Claude moving the cursor into a text field by script (after switching chats, after
  //    sending) opens no keyboard: focus() on a text field is ignored unless that field was
  //    just touched or a text field already has focus.
  // 2. Enter sends (clicks the message box's Send button); Shift+Enter makes a new line.
  //    Enter that confirms an input-method candidate only confirms it.
  // 3. In an empty message box, Enter and a tap on the box's Enter icon press Tab instead:
  //    Tab takes Claude's suggested prompt, which a phone keyboard has no key for.
  // 4. After sending, the keyboard closes.
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

  // Nothing typed: the text the user entered, not counting the grey suggestion Claude shows.
  function empty(field) {
    if (field.tagName === 'TEXTAREA') return !field.value.trim();
    let text = field.textContent || '';
    for (const shown of field.querySelectorAll('[contenteditable="false"]')) {
      text = text.replace(shown.textContent || '', '');
    }
    return !text.trim();
  }

  function pressTab(field) {
    const init = { key: 'Tab', code: 'Tab', keyCode: 9, which: 9, bubbles: true, cancelable: true };
    field.dispatchEvent(new KeyboardEvent('keydown', init));
    field.dispatchEvent(new KeyboardEvent('keyup', init));
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
    if (empty(field)) {
      event.preventDefault();
      event.stopImmediatePropagation();
      pressTab(field);
      return;
    }
    const buttons = sendButtons(field);
    if (!buttons.length) return; // No Send button here (e.g. Claude is replying): a new line.
    event.preventDefault();
    event.stopImmediatePropagation();
    // A disabled Send (e.g. an upload in progress) means nothing to send, and no new line either.
    const send = buttons.find(b => !b.disabled && b.getClientRects().length);
    if (send) send.click();
  }, true);

  // The Enter icon at the right end of an empty message box shows the keyboard when tapped.
  // A touch there (within ICON_PX of the box's right edge, on the text's row) presses Tab
  // instead, with no keyboard. The box is the field and its close ancestors that are still
  // small, unlike the page or the sidebar.
  const ICON_PX = 56;
  function iconTap(target, x, y) {
    if (!(target instanceof Element) || target.closest(SEND + ', a')) return null;
    let field = editable(target) && target.tagName !== 'INPUT' ? target : null;
    let right = 0;
    for (let area = field || target, i = 0; area && i < 5; area = area.parentElement, i++) {
      const rect = area.getBoundingClientRect();
      if (rect.height > window.innerHeight * 0.4) break;
      right = Math.max(right, rect.right);
      if (!field) {
        field = Array.from(area.querySelectorAll(EDITABLE)).find(f => editable(f) && f.tagName !== 'INPUT') || null;
      }
    }
    if (!field || !empty(field)) return null;
    const row = field.getBoundingClientRect();
    if (y < row.top - 16 || y > row.bottom + 16 || x < right - ICON_PX || x > right) return null;
    return field;
  }

  document.addEventListener('touchstart', event => {
    if (event.touches.length !== 1) return;
    const point = event.touches[0];
    const field = iconTap(event.target, point.clientX, point.clientY);
    if (!field) return;
    event.preventDefault(); // No tap on the field: no keyboard.
    event.stopImmediatePropagation();
    // Tab goes to a focused field, as a real key would; inputmode "none" keeps the keyboard shut.
    const mode = field.getAttribute('inputmode');
    field.setAttribute('inputmode', 'none');
    focus.call(field);
    pressTab(field);
    setTimeout(() => {
      field.blur();
      if (mode === null) field.removeAttribute('inputmode');
      else field.setAttribute('inputmode', mode);
    }, 100);
  }, { capture: true, passive: false });

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
