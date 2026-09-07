// Cursor-style code completion for the snippet editor: one CompletionItemProvider (the Shift+TAB /
// Ctrl+Space list), one InlineCompletionsProvider (ghost text) and the bridge protocol that feeds
// both from Java. Monaco owns the whole UI (widget, filtering, TAB/Enter/Esc, snippet placeholders);
// Java only supplies candidates through window.korttyMonaco.pushCompletions/pushGhostCompletions and
// hears back through Bridge.onCompletionRequested/onCompletionListClosed/onCompletionAccepted.
//
// Protocol (all payloads are JSON strings):
//   JS -> Java  onCompletionRequested(id, { id, text, caretOffset, lineNumber, column, language,
//                                           trigger, hasLanguageService })
//   Java -> JS  pushCompletions({ id, source: "local"|"ai", items: [{ label, insertText, filterText?,
//                                 sortText?, kind?, snippet?, replaceStart?, detail?, documentation? }] })
//   Java -> JS  pushGhostCompletions({ id, caretOffset, textLength, suggestions: [string|{insertText}] })
//   JS -> Java  onCompletionListClosed(id)
//   JS -> Java  onCompletionAccepted({ requestId, source: "ai"|"ghost", start, insertedText, valueLength })
//
// The selector is { scheme: "inmemory" } (score 10) so the provider sits in the same group as Monaco's
// own language services for javascript/typescript/json/css/html instead of being shadowed by them.
import { monaco, notify } from "./common.js";

const REQUEST_TIMEOUT_MS = 1500;
// A list with no local rows waits this long for a pending AI answer before it reports "No suggestions".
const AI_WAIT_MS = 35000;
const FOCUS_WAIT_MS = 2000;
const ACCEPTED_COMMAND_ID = "kortty.completion.accepted";
const OPEN_ACTION_ID = "kortty.completion.open";
const SUGGEST_CONTROLLER_ID = "editor.contrib.suggestController";
const INLINE_CONTROLLER_ID = "editor.contrib.inlineCompletionsController";
const LANGUAGE_SELECTOR = { scheme: "inmemory" };
// Languages with a Monaco worker-backed language service registered in common.js.
const LANGUAGE_SERVICE_IDS = new Set(["javascript", "typescript", "json", "css", "html", "less", "scss"]);
const KEEP_WHITESPACE = 1; // CompletionItemInsertTextRule.KeepWhitespace
const INSERT_AS_SNIPPET = 4; // CompletionItemInsertTextRule.InsertAsSnippet
// The suggest widget renders labels with supportIcons, so "$(name)" would become a codicon and
// vanish (bash idioms such as "$(command)"); a leading backslash makes Monaco print it literally.
const CODICON_PATTERN = /\$\(([A-Za-z0-9-]+(?:~[A-Za-z]+)?)\)/g;
// The JavaFX WebView stops painting a label run after a color emoji (verified with U+2728, also with
// the VS15 text selector), so the AI sparkle is shown as its text-presentation sibling U+2726.
const SPARKLES_PATTERN = /\u2728\uFE0E?/g;

function displayLabel(label) {
  return label.replace(CODICON_PATTERN, "\\$($1)").replace(SPARKLES_PATTERN, "\u2726");
}

export const COMPLETION_OPTIONS = {
  quickSuggestions: false,
  suggestOnTriggerCharacters: false,
  wordBasedSuggestions: "off",
  tabCompletion: "off",
  acceptSuggestionOnEnter: "on",
  acceptSuggestionOnCommitCharacter: false,
  snippetSuggestions: "inline",
  suggest: {
    preview: false,
    showIcons: true,
    showStatusBar: false,
    showInlineDetails: true,
    matchOnWordStartOnly: false,
    filterGraceful: true,
    insertMode: "insert",
    selectionMode: "always"
  },
  inlineSuggest: {
    enabled: true,
    mode: "subwordSmart",
    showToolbar: "onHover",
    suppressSuggestions: false,
    keepOnBlur: false
  }
};

let editor = null;
let model = null;
let installed = false;
let disposables = [];
let acceptedCommandRegistered = false;
let nextRequestId = 0;
let session = null;
let pendingTrigger = null;
let ghost = null;
let suppressClose = false;
let focusWaiter = null;
let focusTimer = null;
let closedCount = 0;
let acceptedCount = 0;
let lastAccepted = null;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function parsePayload(json) {
  if (json && typeof json === "object") return json;
  if (typeof json !== "string" || !json) return null;
  try {
    const value = JSON.parse(json);
    return value && typeof value === "object" ? value : null;
  } catch (error) {
    console.error("Invalid completion payload", error);
    return null;
  }
}

function kindFor(rawKind, isAi) {
  const kinds = monaco.languages.CompletionItemKind;
  if (isAi) return kinds.Event;
  switch (String(rawKind || "").toLowerCase()) {
    case "array":
    case "hash":
    case "variable":
      return kinds.Variable;
    case "function":
      return kinds.Function;
    case "idiom":
    case "snippet":
      return kinds.Snippet;
    case "ai":
      return kinds.Event;
    default:
      return kinds.Text;
  }
}

function suggestController() {
  if (!editor || typeof editor.getContribution !== "function") return null;
  try {
    return editor.getContribution(SUGGEST_CONTROLLER_ID);
  } catch (_error) {
    return null;
  }
}

function clearSession() {
  if (!session) return;
  const waiters = session.waiters.splice(0);
  session = null;
  for (const waiter of waiters) waiter();
}

function closeSession() {
  if (!session || suppressClose) return;
  const id = session.id;
  clearSession();
  closedCount += 1;
  notify("onCompletionListClosed", id);
}

function openSession(position, context) {
  const id = ++nextRequestId;
  const caretOffset = model.getOffsetAt(position);
  const language = model.getLanguageId();
  const trigger = pendingTrigger || (context && context.triggerKind === 2 ? "incomplete" : "editor");
  pendingTrigger = null;
  session = {
    id,
    caretOffset,
    lineNumber: position.lineNumber,
    column: position.column,
    trigger,
    resolved: false,
    timedOut: false,
    aiPending: false,
    local: [],
    ai: [],
    waiters: [],
    provideCount: 0
  };
  notify("onCompletionRequested", id, JSON.stringify({
    id,
    text: model.getValue(),
    caretOffset,
    lineNumber: position.lineNumber,
    column: position.column,
    language,
    trigger,
    hasLanguageService: LANGUAGE_SERVICE_IDS.has(language)
  }));
  return session;
}

function resolveSession() {
  if (!session || session.resolved) return;
  session.resolved = true;
  const waiters = session.waiters.splice(0);
  for (const waiter of waiters) waiter();
}

function rangeFor(replaceStart, position) {
  const caretOffset = model.getOffsetAt(position);
  const requested = Number(replaceStart);
  let start = Number.isFinite(requested) ? requested : session.caretOffset;
  start = clamp(Math.trunc(start), 0, caretOffset);
  let startPosition = model.getPositionAt(start);
  if (startPosition.lineNumber !== position.lineNumber) {
    start = caretOffset;
    startPosition = position;
  }
  return {
    start,
    range: new monaco.Range(startPosition.lineNumber, startPosition.column, position.lineNumber, position.column)
  };
}

function toCompletionItem(raw, source, position) {
  if (!raw || typeof raw !== "object") return null;
  const insertText = typeof raw.insertText === "string" ? raw.insertText : typeof raw.label === "string" ? raw.label : "";
  if (!insertText) return null;
  const isAi = source === "ai" || String(raw.kind || "").toLowerCase() === "ai";
  const snippet = raw.snippet === true && !isAi;
  const label = typeof raw.label === "string" && raw.label ? raw.label : insertText.split(/\r?\n/)[0];
  const { start, range } = rangeFor(raw.replaceStart, position);
  const item = {
    label: displayLabel(label),
    kind: kindFor(raw.kind, isAi),
    insertText,
    insertTextRules: snippet ? INSERT_AS_SNIPPET | KEEP_WHITESPACE : KEEP_WHITESPACE,
    range
  };
  if (typeof raw.filterText === "string" && raw.filterText) item.filterText = raw.filterText;
  if (typeof raw.sortText === "string" && raw.sortText) item.sortText = raw.sortText;
  if (typeof raw.detail === "string" && raw.detail) item.detail = raw.detail;
  if (typeof raw.documentation === "string" && raw.documentation) item.documentation = raw.documentation;
  if (isAi) {
    item.command = {
      id: ACCEPTED_COMMAND_ID,
      title: "",
      arguments: [{ requestId: session.id, source: "ai", start }]
    };
  }
  return item;
}

function buildItems(position) {
  if (!session || !model || !position) return [];
  const items = [];
  for (const source of ["local", "ai"]) {
    for (const raw of session[source]) {
      const item = toCompletionItem(raw, source, position);
      if (item) items.push(item);
    }
  }
  return items;
}

function provideCompletionItems(textModel, position, context, token) {
  if (!installed || !editor || !model || textModel !== model) {
    return { suggestions: [] };
  }
  if (session && session.resolved) {
    return { suggestions: buildItems(position) };
  }
  if (!session) {
    openSession(position, context);
    if (session.resolved) {
      return { suggestions: buildItems(position) };
    }
  }
  const current = session;
  current.provideCount += 1;
  return new Promise((resolve) => {
    let done = false;
    let timer = null;
    let cancellation = null;
    const finish = (cancelled) => {
      if (done) return;
      done = true;
      if (timer !== null) clearTimeout(timer);
      if (cancellation) cancellation.dispose();
      const index = current.waiters.indexOf(finish);
      if (index >= 0) current.waiters.splice(index, 1);
      if (cancelled || session !== current) {
        resolve({ suggestions: [] });
        return;
      }
      // Ranges are relative to the position Monaco handed to this call: it accounts for text typed
      // while the request was pending itself (columnDelta in SuggestController.getOverwriteInfo).
      resolve({ suggestions: buildItems(position) });
    };
    current.waiters.push(finish);
    const onTimeout = () => {
      if (session === current && !current.resolved && current.aiPending && !current.aiWaitStarted) {
        // Nothing local matched but an AI answer is on its way: keep Monaco's loading state
        // instead of flashing "No suggestions" until the AI rows land or the request ends.
        current.aiWaitStarted = true;
        timer = setTimeout(onTimeout, AI_WAIT_MS);
        return;
      }
      if (session === current && !current.resolved) {
        current.timedOut = true;
        resolveSession();
      } else {
        finish(false);
      }
    };
    timer = setTimeout(onTimeout, REQUEST_TIMEOUT_MS);
    if (token && typeof token.onCancellationRequested === "function") {
      cancellation = token.onCancellationRequested(() => finish(true));
    }
  });
}

function refreshList() {
  if (!editor || !session) return;
  const controller = suggestController();
  const suggestModel = controller && controller.model;
  if (suggestModel && typeof suggestModel.trigger === "function") {
    suggestModel.trigger({ auto: false, retrigger: true });
    return;
  }
  suppressClose = true;
  try {
    editor.trigger("kortty", "hideSuggestWidget", null);
  } finally {
    suppressClose = false;
  }
  editor.trigger("kortty", "editor.action.triggerSuggest", {});
}

function pushCompletions(json) {
  const payload = parsePayload(json);
  if (!payload || !session || Number(payload.id) !== session.id) return;
  const items = Array.isArray(payload.items) ? payload.items : Array.isArray(payload.candidates) ? payload.candidates : [];
  const source = payload.source === "ai" ? "ai" : "local";
  if (source === "ai") {
    session.ai = session.ai.concat(items);
    // The AI request has answered (rows, nothing usable, failure or cancel): nothing else is pending.
    session.aiPending = false;
  } else {
    session.local = items;
    session.aiPending = payload.aiPending === true;
  }
  if (!session.resolved) {
    // An empty local list with an AI answer pending stays unresolved so the widget keeps loading.
    if (source === "ai" || items.length > 0 || !session.aiPending) resolveSession();
    return;
  }
  if (items.length > 0) refreshList();
}

function reportAccepted(payload) {
  if (!editor || !model || !payload || typeof payload !== "object") return;
  const valueLength = model.getValueLength();
  const start = clamp(Math.trunc(Number(payload.start) || 0), 0, valueLength);
  const position = editor.getPosition() || model.getPositionAt(valueLength);
  const startPosition = model.getPositionAt(start);
  const insertedText = startPosition.isBeforeOrEqual(position)
    ? model.getValueInRange(monaco.Range.fromPositions(startPosition, position))
    : "";
  const accepted = {
    requestId: Number(payload.requestId) || 0,
    source: payload.source === "ghost" ? "ghost" : "ai",
    start,
    insertedText,
    valueLength
  };
  if (accepted.source === "ghost") ghost = null;
  acceptedCount += 1;
  lastAccepted = accepted;
  notify("onCompletionAccepted", JSON.stringify(accepted));
}

function registerAcceptedCommand() {
  if (acceptedCommandRegistered) return;
  acceptedCommandRegistered = true;
  monaco.editor.registerCommand(ACCEPTED_COMMAND_ID, (_accessor, payload) => reportAccepted(payload));
}

function shouldOpenList(position) {
  const line = model.getLineContent(position.lineNumber);
  const before = line.slice(0, position.column - 1);
  const after = line.slice(position.column - 1);
  return /\S/.test(before) && after.trim().length === 0;
}

function runOpenAction() {
  if (!editor || !model) return;
  const position = editor.getPosition();
  const selection = editor.getSelection();
  if (position && selection && selection.isEmpty() && shouldOpenList(position)) {
    pendingTrigger = "shiftTab";
    try {
      editor.trigger("kortty", "editor.action.triggerSuggest", {});
    } finally {
      pendingTrigger = null;
    }
    return;
  }
  editor.trigger("keyboard", "outdent", null);
}

function provideInlineCompletions(textModel, position) {
  if (!installed || !editor || !model || textModel !== model || !ghost || session) {
    return { items: [] };
  }
  if (position.lineNumber !== ghost.lineNumber || position.column < ghost.column) {
    return { items: [] };
  }
  const typed = model.getValueInRange(new monaco.Range(ghost.lineNumber, ghost.column, ghost.lineNumber, position.column));
  if (model.getValueLength() !== ghost.textLength + typed.length) {
    return { items: [] };
  }
  const range = new monaco.Range(ghost.lineNumber, ghost.column, ghost.lineNumber, position.column);
  const items = [];
  for (const text of ghost.items) {
    if (text.length <= typed.length || !text.startsWith(typed)) continue;
    items.push({
      insertText: text,
      range,
      command: {
        id: ACCEPTED_COMMAND_ID,
        title: "",
        arguments: [{ requestId: ghost.id, source: "ghost", start: ghost.offset }]
      }
    });
  }
  return { items, enableForwardStability: true };
}

function pushGhostCompletions(json) {
  const payload = parsePayload(json);
  if (!payload || !installed || !editor || !model) return;
  if (session) return;
  const caretOffset = Number(payload.caretOffset);
  const textLength = Number(payload.textLength);
  if (!Number.isFinite(caretOffset) || !Number.isFinite(textLength) || textLength !== model.getValueLength()) return;
  const position = editor.getPosition();
  if (!position || model.getOffsetAt(position) !== caretOffset) return;
  const raw = Array.isArray(payload.suggestions) ? payload.suggestions : Array.isArray(payload.items) ? payload.items : [];
  const restOfLine = model.getLineContent(position.lineNumber).slice(position.column - 1);
  const seen = new Set();
  const texts = [];
  for (const entry of raw) {
    let text = typeof entry === "string" ? entry
      : entry && typeof entry.insertText === "string" ? entry.insertText
        : entry && typeof entry.text === "string" ? entry.text : "";
    // Monaco only accepts multi-line inline completions whose range ends at the end of a line.
    if (restOfLine.length > 0) text = text.split(/\r?\n/)[0];
    if (!text || seen.has(text)) continue;
    seen.add(text);
    texts.push(text);
  }
  if (texts.length === 0) {
    clearGhost();
    return;
  }
  ghost = {
    id: Number(payload.id) || 0,
    lineNumber: position.lineNumber,
    column: position.column,
    offset: caretOffset,
    textLength,
    items: texts
  };
  editor.trigger("kortty", "editor.action.inlineSuggest.trigger", null);
}

function clearGhost() {
  ghost = null;
  if (editor && installed) editor.trigger("kortty", "editor.action.inlineSuggest.hide", null);
}

function disposeFocusWaiter() {
  if (focusWaiter) {
    focusWaiter.dispose();
    focusWaiter = null;
  }
  if (focusTimer !== null) {
    clearTimeout(focusTimer);
    focusTimer = null;
  }
}

function triggerCompletionList() {
  if (!installed || !editor) return;
  disposeFocusWaiter();
  const open = () => {
    pendingTrigger = "menu";
    try {
      editor.trigger("kortty", "editor.action.triggerSuggest", {});
    } finally {
      pendingTrigger = null;
    }
  };
  editor.focus();
  if (editor.hasTextFocus()) {
    open();
    return;
  }
  // The JavaFX menu popup hands focus back after its ActionEvent; a suggest session started before
  // the editor text has focus is cancelled by Monaco's blur handling, so wait for the focus once.
  focusWaiter = editor.onDidFocusEditorText(() => {
    disposeFocusWaiter();
    open();
  });
  focusTimer = setTimeout(disposeFocusWaiter, FOCUS_WAIT_MS);
}

function isGhostVisible() {
  if (!editor || !installed) return false;
  try {
    const controller = editor.getContribution(INLINE_CONTROLLER_ID);
    const inlineModel = controller && controller.model && typeof controller.model.get === "function"
      ? controller.model.get()
      : null;
    const ghostText = inlineModel && inlineModel.primaryGhostText && typeof inlineModel.primaryGhostText.get === "function"
      ? inlineModel.primaryGhostText.get()
      : null;
    if (ghostText) {
      return typeof ghostText.isEmpty === "function" ? !ghostText.isEmpty() : true;
    }
  } catch (_error) {
    // fall through to the DOM probe
  }
  const domNode = editor.getDomNode();
  return !!(domNode && domNode.querySelector(".ghost-text-decoration, .ghost-text"));
}

function completionDebugState() {
  return JSON.stringify({
    enabled: installed,
    listActive: !!session,
    requestId: session ? session.id : -1,
    resolved: !!(session && session.resolved),
    aiPending: !!(session && session.aiPending),
    trigger: session ? session.trigger : null,
    local: session ? session.local.length : 0,
    ai: session ? session.ai.length : 0,
    ghostCached: ghost ? ghost.items.length : 0,
    ghostVisible: isGhostVisible(),
    closedCount,
    acceptedCount,
    lastAccepted
  });
}

function triggerEditorCommand(id) {
  if (!editor || typeof id !== "string" || !id) return;
  editor.trigger("kortty", id, null);
}

function setCompletionEnabled(enabled) {
  if (enabled) {
    if (editor && model) installCompletion({ editor, model });
  } else {
    disposeCompletion();
  }
}

function subscribeListClosed() {
  const controller = suggestController();
  const suggestModel = controller && controller.model;
  if (suggestModel && typeof suggestModel.onDidCancel === "function") {
    disposables.push(suggestModel.onDidCancel((event) => {
      if (!event || !event.retrigger) closeSession();
    }));
    return;
  }
  // Fallback for a Monaco whose SuggestModel no longer exposes onDidCancel: watch the widget's
  // "visible" class. A retrigger keeps the widget visible, a cancel hides it.
  console.warn("Monaco SuggestModel.onDidCancel unavailable; falling back to a DOM observer for list closing");
  const domNode = editor.getDomNode();
  if (!domNode || typeof MutationObserver !== "function") return;
  let wasVisible = false;
  const observer = new MutationObserver(() => {
    const visible = !!domNode.querySelector(".suggest-widget.visible");
    if (wasVisible && !visible) closeSession();
    wasVisible = visible;
  });
  observer.observe(domNode, { subtree: true, attributes: true, attributeFilter: ["class"] });
  disposables.push({ dispose: () => observer.disconnect() });
}

/** Records the editor pair without enabling completion (lets setCompletionEnabled install lazily). */
export function bindCompletion({ editor: nextEditor, model: nextModel }) {
  editor = nextEditor || null;
  model = nextModel || null;
}

export function installCompletion({ editor: nextEditor, model: nextModel }) {
  bindCompletion({ editor: nextEditor, model: nextModel });
  if (installed || !editor || !model) return;
  installed = true;
  editor.updateOptions(COMPLETION_OPTIONS);
  registerAcceptedCommand();
  disposables.push(monaco.languages.registerCompletionItemProvider(LANGUAGE_SELECTOR, { provideCompletionItems }));
  disposables.push(monaco.languages.registerInlineCompletionsProvider(LANGUAGE_SELECTOR, {
    provideInlineCompletions,
    // Esc on a ghost (editor.action.inlineSuggest.hide) ends the shown item as Rejected: forget the
    // cache, or the next keystroke that matches would bring the dismissed ghost straight back. Only
    // Java's next push shows one again. (Monaco 0.56 still calls the deprecated handleRejection
    // for the same case; handleEndOfLifetime is the current hook and covers it.)
    handleEndOfLifetime(_completions, _item, reason) {
      if (reason && reason.kind === monaco.languages.InlineCompletionEndOfLifeReasonKind.Rejected) ghost = null;
    },
    // Required in Monaco 0.56 (called unguarded); there is no freeInlineCompletions any more.
    disposeInlineCompletions(_completions, _reason) {}
  }));
  disposables.push(editor.addAction({
    id: OPEN_ACTION_ID,
    label: "Open code completions",
    keybindings: [monaco.KeyMod.Shift | monaco.KeyCode.Tab],
    precondition: "editorTextFocus && !editorReadonly && !suggestWidgetVisible && !inlineSuggestionVisible"
      + " && !inSnippetMode && !editorHasSelection && !editorTabMovesFocus",
    run: runOpenAction
  }));
  subscribeListClosed();
}

export function disposeCompletion() {
  disposeFocusWaiter();
  // An open list is closed first, while the providers and the cancel listener are still in place:
  // hiding the widget cancels the session without a retrigger, which reaches the listener and
  // reports the close to Java. Disposing the providers first does not do that in Monaco 0.56 — the
  // word-based "*" provider keeps the model's provider set non-empty, so SuggestModel merely
  // retriggers (onDidCancel with retrigger: true, which the listener ignores) and Java would never
  // hear that its list is gone.
  if (session && editor && installed) {
    try {
      editor.trigger("kortty", "hideSuggestWidget", null);
    } catch (error) {
      console.error("Completion teardown could not hide the suggest widget", error);
    }
  }
  const pending = disposables.splice(0);
  installed = false;
  for (const disposable of pending) {
    try {
      disposable.dispose();
    } catch (error) {
      console.error("Completion teardown failed", error);
    }
  }
  // A session that outlived the hide (the widget was not showing yet, so the command was a no-op)
  // is still reported closed: Java's list state must not stay stuck on a request that can never
  // resolve.
  closeSession();
  ghost = null;
  pendingTrigger = null;
}

export const completionApi = {
  pushCompletions,
  pushGhostCompletions,
  clearGhost,
  triggerCompletionList,
  setCompletionEnabled,
  triggerEditorCommand,
  completionDebugState
};
