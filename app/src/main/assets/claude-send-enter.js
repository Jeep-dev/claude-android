(() => {
  if (window.__claudeStandaloneSendEnter) return;
  window.__claudeStandaloneSendEnter = true;

  let composing = false;
  let lastCompositionEnd = 0;
  let lastSend = 0;
  let lastModifiedEnter = 0;
  const editorSelector = 'div.ProseMirror[contenteditable="true"]';
  const buttonSelector = 'button[aria-label="Send message"], button[aria-label="Send Message"], button[data-testid="send-button"]';

  function markEditor(editor) {
    if (editor?.matches?.(editorSelector) && editor.getAttribute('enterkeyhint') !== 'send') {
      editor.setAttribute('enterkeyhint', 'send');
    }
  }
  // Do not observe the entire document: Claude streams frequent DOM mutations.
  document.querySelectorAll(editorSelector).forEach(markEditor);
  document.addEventListener('focusin', e => markEditor(e.target), true);

  document.addEventListener('compositionstart', e => {
    if (e.target?.matches?.(editorSelector)) composing = true;
  }, true);
  document.addEventListener('compositionend', e => {
    if (e.target?.matches?.(editorSelector)) {
      composing = false;
      lastCompositionEnd = Date.now();
    }
  }, true);

  function sendIfReady(e) {
    const editor = document.activeElement;
    if (!editor?.matches?.(editorSelector) || !editor.textContent?.trim()) return;
    if (composing || e.isComposing || Date.now() - lastCompositionEnd < 180) return;
    if (e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) return;
    const candidates = [...document.querySelectorAll(buttonSelector)].filter(button =>
      !button.disabled && button.getAttribute('aria-disabled') !== 'true' &&
      button.getClientRects().length > 0);
    // Never guess at an unrelated button when the page changes its layout.
    if (candidates.length !== 1) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (Date.now() - lastSend < 600) return;
    lastSend = Date.now();
    candidates[0].click();
  }

  document.addEventListener('keydown', e => {
    if (e.key !== 'Enter' || e.keyCode === 229 || e.repeat) return;
    if (e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) {
      lastModifiedEnter = Date.now();
      return;
    }
    sendIfReady(e);
  }, true);
  // Some Android soft keyboards emit beforeinput instead of Enter keydown.
  document.addEventListener('beforeinput', e => {
    if (Date.now() - lastModifiedEnter < 250) return;
    if (e.inputType === 'insertParagraph' || e.inputType === 'insertLineBreak') sendIfReady(e);
  }, true);
})();
