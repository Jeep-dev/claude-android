(() => {
  if (window.top !== window || window.__claudeStandaloneSendEnter) return;
  window.__claudeStandaloneSendEnter = true;

  let lastSend = 0;
  let lastModifiedEnter = 0;
  // Chat uses a ProseMirror contenteditable; other Claude composers (e.g. Code) may use a textarea.
  const editorSelector = '[contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], textarea';
  const sendWords = /\b(send|submit)\b|发送|提交/i;
  const stopWords = /\b(stop|cancel)\b|停止|取消/i;
  const maxDepth = 8;

  const visible = el => el.getClientRects().length > 0;
  const labelOf = button => [button.getAttribute('aria-label'), button.getAttribute('title'),
    button.getAttribute('data-testid')].filter(Boolean).join(' ') || (button.textContent || '').trim();

  function isEditor(el) {
    return !!el?.matches?.(editorSelector) && !el.readOnly && !el.disabled
      && !el.closest?.('dialog, [role="dialog"], [role="alertdialog"]');
  }

  function textOf(editor) {
    return editor.tagName === 'TEXTAREA' ? editor.value : editor.textContent;
  }

  function sendLike(button) {
    const label = labelOf(button);
    if (stopWords.test(label)) return false;
    if (sendWords.test(label)) return true;
    // Icon-only submit button inside the composer.
    return !label && button.getAttribute('type') === 'submit';
  }

  // Walk up from the editor to the nearest container holding exactly one visible send
  // button. Never guess: stop at ambiguity, at a Stop button, or once another editor appears.
  function locateSend(editor) {
    let node = editor.parentElement;
    for (let depth = 0; node && depth < maxDepth; depth++, node = node.parentElement) {
      if (node === document.body || node === document.documentElement) return null;
      if ([...node.querySelectorAll(editorSelector)].filter(visible).length > 1) return null;
      const buttons = [...node.querySelectorAll('button, [role="button"]')].filter(visible);
      // While Claude is streaming, Stop replaces Send: leave Enter alone.
      if (buttons.some(button => stopWords.test(labelOf(button)))) return null;
      const sends = buttons.filter(sendLike);
      if (sends.length) return sends.length === 1 ? sends[0] : null;
    }
    return null;
  }

  function ready(button) {
    return !button.disabled && button.getAttribute('aria-disabled') !== 'true';
  }

  function markEditor(editor) {
    if (isEditor(editor) && editor.getAttribute('enterkeyhint') !== 'send' && locateSend(editor)) {
      editor.setAttribute('enterkeyhint', 'send');
    }
  }
  // Do not observe the entire document: Claude streams frequent DOM mutations.
  if (document.activeElement) markEditor(document.activeElement);
  window.addEventListener('focusin', e => markEditor(e.target), true);

  function sendIfReady(e) {
    const editor = document.activeElement;
    if (!isEditor(editor) || !textOf(editor)?.trim()) return;
    // Enter that confirms a CJK IME candidate arrives while still composing.
    if (e.isComposing || e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) return;
    const button = locateSend(editor);
    if (!button || !ready(button)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (Date.now() - lastSend < 600) return;
    lastSend = Date.now();
    button.click();
  }

  // Window capture runs before the page's own document/editor handlers.
  window.addEventListener('keydown', e => {
    if (e.key !== 'Enter' || e.keyCode === 229 || e.repeat) return;
    if (e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) {
      lastModifiedEnter = Date.now();
      return;
    }
    sendIfReady(e);
  }, true);
  // Some Android soft keyboards emit beforeinput instead of Enter keydown.
  window.addEventListener('beforeinput', e => {
    if (Date.now() - lastModifiedEnter < 250) return;
    if (e.inputType === 'insertParagraph' || e.inputType === 'insertLineBreak') sendIfReady(e);
  }, true);
})();
