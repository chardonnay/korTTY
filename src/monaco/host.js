import {
  monaco,
  notify,
  probeWorkers,
  registerExtraLanguages,
  releaseWorkers,
  sanitizeColor
} from "./common.js";

let editor;
let model;
let booted = false;
let suppressChange = false;
let currentThemeName = "kortty-monaco-theme";
let currentTheme = {};
let currentCursorColor = "#ff0000";
let currentRulerColumn = 0;
const UNKNOWN_CARET_X = -1000000000;

function defineTheme(theme) {
  currentTheme = theme || {};
  const foreground = sanitizeColor(theme && theme.foreground, "#d4d4d4").replace("#", "");
  const background = sanitizeColor(theme && theme.background, "#1e1e1e").replace("#", "");
  currentThemeName = `kortty-monaco-theme-${foreground}-${background}`;
  monaco.editor.defineTheme(currentThemeName, {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "", foreground },
      { token: "comment", foreground: "888888", fontStyle: "italic" },
      { token: "string", foreground: "88c06a" },
      { token: "number", foreground: "6ca0dc" },
      { token: "keyword", foreground: "c586c0", fontStyle: "bold" },
      { token: "variable", foreground: "d19a66" },
      { token: "key", foreground: "d16969", fontStyle: "bold" }
    ],
    colors: {
      "editor.foreground": `#${foreground}`,
      "editor.background": `#${background}`,
      "editorLineNumber.foreground": "#858585",
      "editorCursor.foreground": currentCursorColor
    }
  });
  monaco.editor.setTheme(currentThemeName);
}

function emitTextChanged() {
  if (!model || suppressChange) return;
  notify("onTextChanged", model.getValue(), canUndo(), canRedo());
}

function emitSelectionChanged() {
  if (!editor || !model) return;
  const selection = editor.getSelection();
  const position = editor.getPosition();
  if (!selection || !position) return;
  const start = model.getOffsetAt(selection.getStartPosition());
  const end = model.getOffsetAt(selection.getEndPosition());
  const caret = model.getOffsetAt(position);
  const caretColumn = Math.max(1, Number(position.column) || 1);
  notify("onSelectionChanged", Math.min(start, end), Math.max(start, end), caret, caretColumn, caretXForPosition(position));
}

function caretXForPosition(position) {
  if (!editor || !position || typeof editor.getScrolledVisiblePosition !== "function") {
    return UNKNOWN_CARET_X;
  }
  const visiblePosition = editor.getScrolledVisiblePosition(position);
  const left = Number(visiblePosition && visiblePosition.left);
  return Number.isFinite(left) ? left : UNKNOWN_CARET_X;
}

function emitLayoutChanged() {
  if (!editor) return;
  const layout = editor.getLayoutInfo();
  const fontInfo = editor.getOption(monaco.editor.EditorOption.fontInfo);
  notify(
    "onLayoutChanged",
    Math.max(0, Number(layout && layout.contentLeft) || 0),
    Math.max(1, Number(fontInfo && (fontInfo.typicalHalfwidthCharacterWidth || fontInfo.spaceWidth)) || 8),
    Math.max(0, Number(editor.getScrollLeft ? editor.getScrollLeft() : 0) || 0)
  );
  emitSelectionChanged();
}

function snapshot() {
  if (!editor || !model) {
    return JSON.stringify({
      value: "",
      selectionStart: 0,
      selectionEnd: 0,
      caret: 0,
      canUndo: false,
      canRedo: false
    });
  }
  const selection = editor.getSelection();
  const position = editor.getPosition();
  const start = selection ? model.getOffsetAt(selection.getStartPosition()) : 0;
  const end = selection ? model.getOffsetAt(selection.getEndPosition()) : start;
  const caret = position ? model.getOffsetAt(position) : end;
  return JSON.stringify({
    value: model.getValue(),
    selectionStart: Math.min(start, end),
    selectionEnd: Math.max(start, end),
    caret,
    caretColumn: position ? Math.max(1, Number(position.column) || 1) : 1,
    caretX: position ? caretXForPosition(position) : UNKNOWN_CARET_X,
    canUndo: canUndo(),
    canRedo: canRedo()
  });
}

function canUndo() {
  return !!(model && typeof model.canUndo === "function" && model.canUndo());
}

function canRedo() {
  return !!(model && typeof model.canRedo === "function" && model.canRedo());
}

function optionsFrom(config) {
  return {
    automaticLayout: true,
    colorDecorators: false,
    contextmenu: false,
    detectIndentation: false,
    fontFamily: config.fontFamily || "Monospaced",
    fontSize: Math.max(8, Number(config.fontSize || 14)),
    glyphMargin: false,
    lineNumbers: config.lineNumbers === false ? "off" : "on",
    minimap: { enabled: false },
    readOnly: !!config.readOnly,
    rulers: Number(config.rulerColumn || 0) > 0 ? [Number(config.rulerColumn)] : [],
    scrollBeyondLastLine: false,
    tabSize: 4,
    wordWrap: config.wrapText ? "on" : "off"
  };
}

function boot(config) {
  if (booted) return;
  const editorElement = document.getElementById("editor");
  if (!editorElement) {
    throw new Error("Monaco editor host loaded outside the editor page");
  }
  booted = true;
  registerExtraLanguages();
  defineTheme(config.theme || {});
  const uri = monaco.Uri.parse(`inmemory://kortty/${encodeURIComponent(config.id || "editor")}.txt`);
  model = monaco.editor.createModel(config.value || "", config.language || "plaintext", uri);
  currentRulerColumn = Math.max(0, Number(config.rulerColumn || 0));
  editor = monaco.editor.create(editorElement, {
    ...optionsFrom(config),
    model,
    theme: currentThemeName
  });
  setCursor(config.cursorStyle || "BLOCK", config.cursorColor || "#ff0000");
  model.onDidChangeContent(emitTextChanged);
  editor.onDidChangeCursorSelection(emitSelectionChanged);
  editor.onDidChangeCursorPosition(emitSelectionChanged);
  editor.onDidLayoutChange(emitLayoutChanged);
  editor.onDidScrollChange(emitLayoutChanged);
  window.addEventListener("resize", () => editor.layout());
  probeWorkers();
  notify("onReady");
  emitTextChanged();
  emitSelectionChanged();
  emitLayoutChanged();
}

function setValue(value) {
  if (!model) return;
  suppressChange = true;
  try {
    model.setValue(value || "");
  } finally {
    suppressChange = false;
  }
  emitTextChanged();
  emitSelectionChanged();
}

function replaceRange(start, end, replacement) {
  if (!model || !editor) return;
  const safeStart = Math.max(0, Math.min(Number(start), model.getValueLength()));
  const safeEnd = Math.max(safeStart, Math.min(Number(end), model.getValueLength()));
  const range = new monaco.Range(
    model.getPositionAt(safeStart).lineNumber,
    model.getPositionAt(safeStart).column,
    model.getPositionAt(safeEnd).lineNumber,
    model.getPositionAt(safeEnd).column
  );
  editor.executeEdits("kortty", [{ range, text: replacement || "", forceMoveMarkers: true }]);
  const nextOffset = safeStart + (replacement || "").length;
  selectRange(nextOffset, nextOffset);
}

function selectRange(anchor, caret) {
  if (!model || !editor) return;
  const length = model.getValueLength();
  const safeAnchor = Math.max(0, Math.min(Number(anchor), length));
  const safeCaret = Math.max(0, Math.min(Number(caret), length));
  const anchorPosition = model.getPositionAt(safeAnchor);
  const caretPosition = model.getPositionAt(safeCaret);
  editor.setSelection(new monaco.Selection(
    anchorPosition.lineNumber,
    anchorPosition.column,
    caretPosition.lineNumber,
    caretPosition.column
  ));
  editor.revealPositionInCenterIfOutsideViewport(caretPosition);
  emitSelectionChanged();
}

function revealCaret() {
  if (editor) editor.revealPositionInCenterIfOutsideViewport(editor.getPosition());
}

function setLanguage(language) {
  if (model) monaco.editor.setModelLanguage(model, language || "plaintext");
}

function setReadOnly(readOnly) {
  if (editor) editor.updateOptions({ readOnly: !!readOnly });
}

function setWrapText(wrapText) {
  if (editor) {
    editor.updateOptions({ wordWrap: wrapText ? "on" : "off" });
    emitLayoutChanged();
  }
}

function setLineNumbers(lineNumbers) {
  if (editor) {
    editor.updateOptions({ lineNumbers: lineNumbers ? "on" : "off" });
    emitLayoutChanged();
  }
}

function setFont(fontFamily, fontSize) {
  if (editor) {
    editor.updateOptions({
      fontFamily: fontFamily || "Monospaced",
      fontSize: Math.max(8, Number(fontSize || 14))
    });
    emitLayoutChanged();
  }
}

function setTheme(theme) {
  defineTheme(theme || {});
}

function setCursor(cursorStyle, cursorColor) {
  if (!editor) return;
  const style = String(cursorStyle || "BLOCK").toUpperCase();
  editor.updateOptions({
    cursorBlinking: "blink",
    cursorStyle: style === "LINE" ? "line" : style === "UNDERSCORE" ? "underline" : "block",
    cursorWidth: style === "LINE" ? 2 : 4
  });
  currentCursorColor = sanitizeColor(cursorColor, "#ff0000");
  defineTheme(currentTheme);
}

function setRulerColumn(column) {
  currentRulerColumn = Math.max(0, Number(column || 0));
  if (editor) {
    editor.updateOptions({
      rulers: currentRulerColumn > 0 ? [currentRulerColumn] : [],
      wordWrapColumn: currentRulerColumn > 0 ? currentRulerColumn : 80
    });
    emitLayoutChanged();
  }
}

function runCommand(command) {
  if (editor) editor.trigger("kortty", command, null);
}

function cut() {
  runCommand("editor.action.clipboardCutAction");
}

function copy() {
  runCommand("editor.action.clipboardCopyAction");
}

function paste() {
  runCommand("editor.action.clipboardPasteAction");
}

function undo() {
  runCommand("undo");
  emitTextChanged();
}

function redo() {
  runCommand("redo");
  emitTextChanged();
}

function forgetHistory() {
  if (!model) return;
  const value = model.getValue();
  setValue(value);
}

function dispose() {
  releaseWorkers();
  if (editor) editor.dispose();
  if (model) model.dispose();
  editor = null;
  model = null;
}

export function installEditorHost() {
  window.korttyMonaco = {
    boot,
    snapshot,
    setValue,
    replaceRange,
    selectRange,
    revealCaret,
    setLanguage,
    setReadOnly,
    setWrapText,
    setLineNumbers,
    setFont,
    setTheme,
    setCursor,
    setRulerColumn,
    cut,
    copy,
    paste,
    undo,
    redo,
    forgetHistory,
    dispose
  };
}
