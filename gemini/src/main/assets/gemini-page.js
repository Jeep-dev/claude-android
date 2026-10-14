(() => {
  if (window.__geminiPage) return;
  window.__geminiPage = true;

  // Keyboard behaviour on Gemini:
  // 1. Gemini moving the cursor into a text field by script (after switching chats, after
  //    sending) opens no keyboard: focus() on a text field is ignored unless that field was
  //    just touched or a text field already has focus.
  // 2. Enter sends (clicks the message box's Send button); Shift+Enter makes a new line.
  //    Enter that confirms an input-method candidate only confirms it.
  // 3. In an empty box (Gemini shows no Send button then) Enter does nothing: no stray new
  //    line. With text but the Stop button in place of Send (Gemini replying) Enter is a new line.
  // 4. After sending, the keyboard closes.
  const EDITABLE = 'textarea, input, [contenteditable]:not([contenteditable="false"])';
  // Gemini's Send button sits in <gem-icon-button class="send-button submit">; while it replies the
  // same wrapper holds the Stop button and has class "stop". Its label follows the UI language.
  const SEND = '.send-button:not(.stop) button, button.send-button:not(.stop), '
    + 'button[aria-label*="send" i], button[aria-label*="送信"], button[aria-label*="发送"], '
    + 'button[aria-label*="傳送"], button[aria-label*="送出"]';
  const RECENT_MS = 1000;
  let touched = null;
  let touchedAt = 0;

  const editable = el => !!(el && el.matches && el.matches(EDITABLE)
    && !(el.tagName === 'INPUT' && !/^(text|search|url|email|tel|password)?$/i.test(el.type || '')));

  // Gemini marks its Send button unavailable with aria-disabled rather than disabled.
  const disabled = button => button.disabled || button.getAttribute('aria-disabled') === 'true';

  // The Send buttons next to a text field: the first ancestor that holds any decides.
  function sendButtons(field) {
    for (let area = field.parentElement, i = 0; area && i < 8; area = area.parentElement, i++) {
      const buttons = Array.from(area.querySelectorAll(SEND));
      if (buttons.length) return buttons;
    }
    return [];
  }

  // Nothing typed (Quill keeps an empty <p><br></p>).
  const empty = field => !(field.tagName === 'TEXTAREA' ? field.value : field.textContent || '').trim();

  // Gemini's message box (Quill inside <rich-textarea>).
  const messageBox = field => !!(field.closest && field.closest('rich-textarea')) || sendButtons(field).length > 0;

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
    if (!messageBox(field)) return;
    const buttons = sendButtons(field);
    const send = buttons.find(b => !disabled(b) && b.getClientRects().length);
    // Text typed and nothing in the way: Enter sends it.
    if (send) {
      event.preventDefault();
      event.stopImmediatePropagation();
      send.click();
      return;
    }
    // Gemini replying (Stop in place of Send) with text typed: a new line.
    if (!empty(field) && !buttons.length) return;
    // Empty box, or Send not available yet: no new line either.
    event.preventDefault();
    event.stopImmediatePropagation();
  }, true);

  // Sending by Enter or by tapping Send: close the keyboard once Gemini has taken the text.
  document.addEventListener('click', event => {
    const button = event.target instanceof Element && event.target.closest(SEND);
    if (!button) return;
    touched = button; // A touch on Send does not let Gemini reopen the keyboard.
    setTimeout(closeKeyboard, 0);
    setTimeout(closeKeyboard, 300);
  }, true);

  // Show a Send key on the keyboard for message boxes.
  document.addEventListener('focusin', event => {
    const field = event.target;
    if (editable(field) && field.tagName !== 'INPUT' && messageBox(field)) {
      field.setAttribute('enterkeyhint', 'send');
    }
  }, true);

  // Files Gemini makes in the page (blob: or data: links with a download name, e.g. generated
  // images) cannot go through the system download manager. They are read here and handed to
  // the app in chunks, which saves them to Downloads.
  const saver = window.GeminiDownload;
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

  // The status bar takes the colour at the top of the page, so it follows Gemini's theme.
  const bar = window.GeminiStatusBar;
  if (bar && window.getComputedStyle && window.MutationObserver) {
    // [r, g, b, alpha] of a CSS colour; null when fully transparent.
    const rgba = css => {
      let m = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:[\s,/]+([\d.]+%?))?/.exec(css);
      let c = m && [+m[1], +m[2], +m[3]];
      if (!m) {
        m = /^color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+%?))?/.exec(css);
        c = m && [m[1] * 255, m[2] * 255, m[3] * 255];
      }
      if (!m) return null;
      const a = m[4] === undefined ? 1 : m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4]);
      return a > 0 ? [...c, Math.min(1, a)] : null;
    };
    // The colour seen at the top of the page: the backgrounds stacked there, down to the first
    // opaque one, blended. The sidebar's dimming backdrop (translucent black) then darkens the
    // page colour instead of turning the status bar black.
    const top = () => {
      const layers = [];
      const stack = document.elementsFromPoint ? document.elementsFromPoint(window.innerWidth / 2, 1) : [];
      for (const el of [...stack, document.body, document.documentElement]) {
        const c = el && rgba(getComputedStyle(el).backgroundColor);
        if (!c) continue;
        layers.push(c);
        if (c[3] >= 1) break;
      }
      if (!layers.length) return null;
      let out = [255, 255, 255];
      for (const [r, g, b, a] of layers.reverse()) out = [r * a + out[0] * (1 - a), g * a + out[1] * (1 - a), b * a + out[2] * (1 - a)];
      return out.map(v => Math.max(0, Math.min(255, Math.round(v))));
    };
    let last = null;
    const update = () => {
      const c = top();
      if (!c) return;
      const value = (c[0] << 16) | (c[1] << 8) | c[2];
      if (value !== last) { last = value; bar.setColor(value); }
    };
    // Gemini switches theme through classes on <html>/<body>; its colours may fade in.
    // Bursts of attribute changes make one check, not one forced style recalculation each.
    let pending = 0;
    const soon = () => {
      if (pending) return;
      pending = setTimeout(() => { pending = 0; update(); setTimeout(update, 400); }, 100);
    };
    new MutationObserver(soon).observe(document.documentElement, { attributes: true });
    if (document.body) new MutationObserver(soon).observe(document.body, { attributes: true });
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', soon);
    // Opening or closing the sidebar or a dialog changes no <html>/<body> attribute.
    document.addEventListener('click', soon, true);
    document.addEventListener('transitionend', soon, true);
    window.addEventListener('popstate', soon);
    soon();
    setTimeout(update, 1500); // the app's top bar may render after the page has loaded
  }
})();
