(() => {
  if (window.__claudePage) return;
  window.__claudePage = true;

  // Keyboard behaviour on claude.ai:
  // 1. Claude moving the cursor into a text field by script (after switching chats, after
  //    sending) opens no keyboard: focus() on a text field is ignored unless that field was
  //    just touched or a text field already has focus.
  // 2. Enter sends (clicks the message box's Send button); Shift+Enter makes a new line.
  //    Enter that confirms an input-method candidate only confirms it.
  // 3. In an empty message box with nothing to send (Send disabled), Enter presses Tab
  //    instead: Tab takes Claude's suggested prompt, which a phone keyboard has no key for.
  //    With only an attachment, Send is enabled and Enter sends.
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
    const buttons = sendButtons(field);
    const send = buttons.find(b => !b.disabled && b.getClientRects().length);
    // Something to send (text, or only an attachment): Enter sends it.
    if (send) {
      event.preventDefault();
      event.stopImmediatePropagation();
      send.click();
      return;
    }
    // Nothing typed and nothing to send: Enter presses Tab (takes the suggested prompt).
    if (empty(field)) {
      event.preventDefault();
      event.stopImmediatePropagation();
      pressTab(field);
      return;
    }
    if (!buttons.length) return; // No Send button here (e.g. Claude is replying): a new line.
    // Text typed but Send disabled (e.g. an upload in progress): no new line either.
    event.preventDefault();
    event.stopImmediatePropagation();
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

  // Files Claude makes in the page (blob: or data: links with a download name, e.g. the ZIP
  // and Markdown export) cannot go through the system download manager. They are read here
  // and handed to the app in chunks, which saves them to Downloads.
  const saver = window.ClaudeDownload;
  if (saver && window.FileReader && window.Blob) {
    const blobs = new Map();
    const createURL = URL.createObjectURL;
    URL.createObjectURL = function (object) {
      const url = createURL.call(URL, object);
      if (object instanceof Blob) blobs.set(url, object);
      return url;
    };
    const revokeURL = URL.revokeObjectURL;
    URL.revokeObjectURL = function (url) {
      setTimeout(() => blobs.delete(url), 60000); // a download may still be reading it
      return revokeURL.call(URL, url);
    };
    const CHUNK = 512 * 1024;
    async function send(blob, name) {
      const id = Date.now().toString(36) + Math.random().toString(36).slice(2);
      saver.postMessage(JSON.stringify({ t: 'begin', id, name, type: blob.type || '' }));
      for (let at = 0; at < blob.size; at += CHUNK) {
        const bytes = new Uint8Array(await blob.slice(at, at + CHUNK).arrayBuffer());
        let text = '';
        for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        saver.postMessage(JSON.stringify({ t: 'data', id, b64: btoa(text) }));
      }
      saver.postMessage(JSON.stringify({ t: 'end', id, name }));
    }
    // True when the link is a page-made file that is now being saved. A link to a file on the
    // web keeps its normal download; the app only learns the name the page gave it.
    function save(link) {
      const href = link.href || '';
      if (!link.hasAttribute('download')) return false;
      if (/^https:/.test(href)) {
        const given = link.getAttribute('download');
        if (given) saver.postMessage(JSON.stringify({ t: 'name', url: href, name: given }));
        return false;
      }
      if (!/^(blob|data):/.test(href)) return false;
      const name = link.getAttribute('download') || 'download';
      const known = blobs.get(href);
      (known ? Promise.resolve(known) : fetch(href).then(r => r.blob()))
        .then(blob => send(blob, name))
        .catch(() => saver.postMessage(JSON.stringify({ t: 'end', id: 'failed', name })));
      return true;
    }
    const clickLink = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (save(this)) return;
      return clickLink.call(this);
    };
    document.addEventListener('click', event => {
      const link = event.target instanceof Element && event.target.closest('a[download]');
      if (link && save(link)) event.preventDefault();
    }, true);
  }

  // Selecting text in the WebView draws the backgrounds Claude writes as CSS color(srgb …)
  // (main panel, header, sidebars) black; rgb() backgrounds stay correct. From page load on,
  // each such background is shown as the identical rgb() colour instead, so nothing changes
  // on screen or during a selection. Elements are rechecked when they appear or change class,
  // and all of them when Claude changes theme.
  if (window.getComputedStyle && window.MutationObserver && document.body) {
    const SRGB = /^color\(srgb\s+([\d.e+-]+)\s+([\d.e+-]+)\s+([\d.e+-]+)(?:\s*\/\s*([\d.e+-]+%?))?\s*\)$/;
    const toRgb = color => {
      const m = SRGB.exec(color || '');
      if (!m) return null;
      const channel = v => Math.round(Math.min(1, Math.max(0, parseFloat(v))) * 255);
      let alpha = m[4] == null ? 1 : m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4]);
      alpha = Math.min(1, Math.max(0, alpha));
      const rgb = channel(m[1]) + ', ' + channel(m[2]) + ', ' + channel(m[3]);
      return alpha === 1 ? 'rgb(' + rgb + ')' : 'rgba(' + rgb + ', ' + alpha + ')';
    };
    const pinned = new Map(); // element -> the page's own inline background-color, priority
    const unpin = el => {
      const own = pinned.get(el);
      if (!own) return;
      pinned.delete(el);
      if (own[0]) el.style.setProperty('background-color', own[0], own[1]);
      else el.style.removeProperty('background-color');
    };
    const pin = el => {
      unpin(el);
      if (!el.style) return;
      const rgb = toRgb(getComputedStyle(el).backgroundColor);
      if (!rgb) return;
      pinned.set(el, [el.style.getPropertyValue('background-color'),
        el.style.getPropertyPriority('background-color')]);
      el.style.setProperty('background-color', rgb, 'important');
    };
    const queue = new Set();
    let frame = 0;
    const later = window.requestAnimationFrame || (fn => setTimeout(fn, 16));
    const run = () => {
      frame = 0;
      const items = [...queue];
      queue.clear();
      for (const { el, deep } of items) {
        if (!el.isConnected) { pinned.delete(el); continue; }
        pin(el);
        if (deep) for (const child of el.querySelectorAll('*')) pin(child);
      }
    };
    const check = (el, deep) => {
      queue.add({ el, deep });
      if (!frame) frame = later(run);
    };
    const all = () => {
      for (const el of [...pinned.keys()]) unpin(el);
      check(document.documentElement, true);
    };
    new MutationObserver(records => {
      for (const r of records) {
        if (r.type === 'childList') {
          for (const node of r.addedNodes) if (node.nodeType === 1) check(node, true);
        } else if (r.target === document.documentElement || r.target === document.body) {
          return all(); // theme switch
        } else {
          check(r.target, false);
        }
      }
    }).observe(document.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'data-mode', 'data-theme'] });
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', all);
    all();
  }

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
