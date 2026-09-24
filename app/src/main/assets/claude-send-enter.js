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

  const stopLike = button => stopWords.test(labelOf(button));

  // The composer box: the nearest ancestor of the editor that holds its Send (or, while
  // streaming, Stop) button. Stops once another visible editor appears.
  function composerBox(editor) {
    let node = editor.parentElement;
    for (let depth = 0; node && depth < maxDepth; depth++, node = node.parentElement) {
      if (node === document.body || node === document.documentElement) return null;
      if ([...node.querySelectorAll(editorSelector)].filter(visible).length > 1) return null;
      const buttons = [...node.querySelectorAll('button, [role="button"]')].filter(visible);
      if (buttons.some(button => sendLike(button) || stopLike(button))) return { node, buttons };
    }
    return null;
  }

  // Exactly one visible send button in the composer box. Never guess: nothing when it is
  // ambiguous or when Stop is showing (Claude is streaming).
  function locateSend(editor) {
    const box = composerBox(editor);
    if (!box || box.buttons.some(stopLike)) return null;
    const sends = box.buttons.filter(sendLike);
    return sends.length === 1 ? sends[0] : null;
  }

  function ready(button) {
    return !button.disabled && button.getAttribute('aria-disabled') !== 'true';
  }

  function markEditor(editor) {
    if (isEditor(editor) && editor.getAttribute('enterkeyhint') !== 'send' && locateSend(editor)) {
      editor.setAttribute('enterkeyhint', 'send');
    }
  }
  // Claude focuses its composer by script on page load, on navigation and when the app
  // returns from the background, which pops up the soft keyboard. The composer may only take
  // focus when the user touched it (or its buttons), typed on a hardware keyboard, or the
  // keyboard is already up for another field.
  let lastPointer = { time: 0, target: null };
  let lastKey = 0;
  window.addEventListener('pointerdown', e => { lastPointer = { time: Date.now(), target: e.target }; }, true);
  window.addEventListener('keydown', () => { lastKey = Date.now(); }, true);

  const editable = el => !!el && (el.isContentEditable || el.tagName === 'TEXTAREA' || el.tagName === 'INPUT');

  function composerEditor(el) {
    if (!isEditor(el)) return false;
    const box = composerBox(el);
    // An edit box with only Cancel/Save is not the composer; it may focus itself.
    return !!box && box.buttons.some(button => sendLike(button) || /\bstop\b|停止/i.test(labelOf(button)));
  }

  function userWantsFocus(editor, previous) {
    if (editable(previous)) return true;
    if (Date.now() - lastKey < 1000) return true;
    const target = lastPointer.target;
    if (!target || Date.now() - lastPointer.time > 2000) return false;
    if (editor.contains(target)) return true;
    const box = composerBox(editor);
    return !!box && box.node.contains(target);
  }

  const nativeFocus = HTMLElement.prototype.focus;
  HTMLElement.prototype.focus = function (options) {
    if (document.activeElement !== this && composerEditor(this)
        && !userWantsFocus(this, document.activeElement)) return;
    return nativeFocus.call(this, options);
  };

  // Do not observe the entire document: Claude streams frequent DOM mutations.
  if (document.activeElement) markEditor(document.activeElement);
  window.addEventListener('focusin', e => {
    const el = e.target;
    // Focus that bypassed focus() (autofocus, window regaining focus): drop it before the
    // keyboard is shown.
    if (composerEditor(el) && !userWantsFocus(el, e.relatedTarget)) {
      el.blur();
      return;
    }
    markEditor(el);
  }, true);

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
