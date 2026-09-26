(() => {
  if (window.__claudePage) return;
  window.__claudePage = true;

  // Keyboard behaviour on claude.ai:
  // 1. Claude moving the cursor into a text field by script (after switching chats, after
  //    sending) opens no keyboard: focus() on a text field is ignored unless that field was
  //    just touched or a text field already has focus.
  // 2. Enter sends (clicks the message box's Send button); Shift+Enter makes a new line.
  //    Enter that confirms an input-method candidate only confirms it.
  // 3. In an empty message box, Enter and a tap on the ↵ (disabled Send) press Tab instead:
  //    Tab takes Claude's suggested prompt, which a phone keyboard has no key for.
  // 4. After sending, the keyboard closes.
  const EDITABLE = 'textarea, input, [contenteditable]:not([contenteditable="false"])';
  const SEND = 'button[aria-label*="send" i], button[aria-label*="submit" i], button[type="submit"], '
    + 'button[data-testid$="-send"]';
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

  // The ↵ icon in an empty message box is Claude's Send button, disabled while nothing is
  // typed; a touch on a disabled button falls through to the box and opens the keyboard.
  // A touch on it presses Tab on the box instead, with no keyboard.
  function iconTap(target) {
    const button = target instanceof Element && target.closest(SEND);
    if (!button || !(button.disabled || button.getAttribute('aria-disabled') === 'true')) return null;
    for (let area = button.parentElement, i = 0; area && i < 12; area = area.parentElement, i++) {
      const field = Array.from(area.querySelectorAll(EDITABLE)).find(f => editable(f) && f.tagName !== 'INPUT');
      if (field) return empty(field) ? field : null;
    }
    return null;
  }

  document.addEventListener('touchstart', event => {
    if (event.touches.length !== 1) return;
    const field = iconTap(event.target);
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

  // While text is selected, WebView paints backgrounds whose computed colour is not plain sRGB
  // (Claude's color-mix() colours compute to color(srgb …), or oklab/oklch) as black; Chrome
  // does not. From the moment a selection starts until it ends, such backgrounds are pinned
  // inline to the same colour written as rgb(), then put back as they were.
  const toByte = v => Math.max(0, Math.min(255, Math.round(v * 255)));
  const linToSrgb = v => (v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055);
  const num = (v, scale) => (v === 'none' ? 0 : v.endsWith('%') ? parseFloat(v) / 100 * scale : parseFloat(v));
  const rgba = (r, g, b, a) => (a >= 1 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${+a.toFixed(4)})`);
  function oklabRgb(L, A, B) {
    const l = (L + 0.3963377774 * A + 0.2158037573 * B) ** 3;
    const m = (L - 0.1055613458 * A - 0.0638541728 * B) ** 3;
    const s2 = (L - 0.0894841775 * A - 1.2914855480 * B) ** 3;
    return [4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s2,
      -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s2,
      -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s2].map(v => toByte(linToSrgb(v)));
  }
  // One CSS colour function (as getComputedStyle writes it) to rgb()/rgba(), or null.
  function plain(fn, args) {
    const [body, alpha] = args.split('/');
    const a = alpha === undefined ? 1 : num(alpha.trim(), 1);
    const p = body.trim().split(/\s+/);
    if (fn === 'color') {
      const space = p.shift();
      if (space !== 'srgb' && space !== 'srgb-linear') return null;
      const c = p.map(v => num(v, 1)).map(v => (space === 'srgb' ? v : linToSrgb(v)));
      return rgba(toByte(c[0]), toByte(c[1]), toByte(c[2]), a);
    }
    if (fn === 'oklab') return rgba(...oklabRgb(num(p[0], 1), num(p[1], 0.4), num(p[2], 0.4)), a);
    if (fn === 'oklch') {
      const C = num(p[1], 0.4), h = num(p[2], 1) * Math.PI / 180;
      return rgba(...oklabRgb(num(p[0], 1), C * Math.cos(h), C * Math.sin(h)), a);
    }
    return null;
  }
  const WIDE = /\b(color|oklab|oklch)\(([^()]*)\)/g;
  const toPlain = css => css.replace(WIDE, (all, fn, args) => plain(fn, args) || all);

  const wide = css => css !== 'none' && toPlain(css) !== css;
  const pinned = new Map(); // element -> its own inline background-color / -image before pinning
  let selecting = false;
  function pin() {
    if (selecting || !document.body) return;
    selecting = true;
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
    for (let el = walker.currentNode; el; el = walker.nextNode()) {
      const cs = getComputedStyle(el);
      const color = cs.backgroundColor, image = cs.backgroundImage;
      const fixColor = wide(color), fixImage = wide(image);
      if (!fixColor && !fixImage) continue;
      const st = el.style;
      pinned.set(el, [st.getPropertyValue('background-color'), st.getPropertyPriority('background-color'),
        st.getPropertyValue('background-image'), st.getPropertyPriority('background-image')]);
      if (fixColor) st.setProperty('background-color', toPlain(color), 'important');
      if (fixImage) st.setProperty('background-image', toPlain(image), 'important');
    }
  }
  function unpin() {
    if (!selecting) return;
    selecting = false;
    for (const [el, [color, colorPrio, image, imagePrio]] of pinned) {
      el.style.removeProperty('background-color');
      el.style.removeProperty('background-image');
      if (color) el.style.setProperty('background-color', color, colorPrio);
      if (image) el.style.setProperty('background-image', image, imagePrio);
    }
    pinned.clear();
  }
  document.addEventListener('selectionchange', () => {
    const sel = document.getSelection();
    if (sel && !sel.isCollapsed && !editable(document.activeElement)) pin(); else unpin();
  });

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
