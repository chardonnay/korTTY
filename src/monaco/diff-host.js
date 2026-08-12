import {
  monaco,
  notify,
  probeWorkers,
  registerExtraLanguages,
  releaseWorkers,
  sanitizeColor
} from "./common.js";

let diffEditor;
let originalModel;
let modifiedModel;
let changeDecorations = [];
let changeReasons = [];
let changeReasonListenerAttached = false;
// Finding id the reviewer picked below the diff, or "" for "every change". While one is set, only its
// blocks stay decorated, so a long staged rewrite can be read one finding at a time.
let reasonFilter = "";
let pendingFilterReveal = false;
let booted = false;
let currentThemeName = "kortty-monaco-diff-theme";
let currentTheme = {};

function defineTheme(theme) {
  currentTheme = theme || {};
  const foreground = sanitizeColor(theme && theme.foreground, "#d4d4d4").replace("#", "");
  const background = sanitizeColor(theme && theme.background, "#1e1e1e").replace("#", "");
  currentThemeName = `kortty-monaco-diff-theme-${foreground}-${background}`;
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
      "diffEditor.insertedTextBackground": "#2ea04355",
      "diffEditor.removedTextBackground": "#f8514955",
      "diffEditor.insertedLineBackground": "#2ea04333",
      "diffEditor.removedLineBackground": "#f8514933"
    }
  });
  monaco.editor.setTheme(currentThemeName);
}

function optionsFrom(config) {
  return {
    automaticLayout: true,
    contextmenu: false,
    detectIndentation: false,
    enableSplitViewResizing: true,
    fontFamily: config.fontFamily || "Monospaced",
    fontSize: Math.max(8, Number(config.fontSize || 14)),
    glyphMargin: false,
    ignoreTrimWhitespace: false,
    minimap: { enabled: false },
    originalEditable: false,
    readOnly: true,
    renderSideBySide: true,
    scrollBeyondLastLine: false,
    tabSize: 4,
    wordWrap: "off"
  };
}

function boot(config) {
  if (booted) return;
  const editorElement = document.getElementById("diff-editor");
  if (!editorElement) {
    throw new Error("Monaco diff host loaded outside the diff page");
  }
  booted = true;
  registerExtraLanguages();
  defineTheme(config.theme || {});
  originalModel = createModel(config.id, "original", config.originalValue, config.originalLanguage);
  modifiedModel = createModel(config.id, "modified", config.modifiedValue, config.modifiedLanguage);
  diffEditor = monaco.editor.createDiffEditor(editorElement, {
    ...optionsFrom(config),
    theme: currentThemeName
  });
  diffEditor.setModel({ original: originalModel, modified: modifiedModel });
  window.addEventListener("resize", () => diffEditor.layout());
  probeWorkers();
  notify("onReady");
}

function createModel(id, side, value, language) {
  const uri = monaco.Uri.parse(`inmemory://kortty/${encodeURIComponent(id || "diff")}-${side}.txt`);
  return monaco.editor.createModel(value || "", language || "plaintext", uri);
}

function setValue(originalValue, modifiedValue, originalLanguage, modifiedLanguage) {
  if (!originalModel || !modifiedModel) return;
  originalModel.setValue(originalValue || "");
  modifiedModel.setValue(modifiedValue || "");
  monaco.editor.setModelLanguage(originalModel, originalLanguage || "plaintext");
  monaco.editor.setModelLanguage(modifiedModel, modifiedLanguage || "plaintext");
  clearChangeReasons();
}

function clearChangeReasons() {
  if (diffEditor) {
    changeDecorations = diffEditor.getModifiedEditor().deltaDecorations(changeDecorations, []);
  } else {
    changeDecorations = [];
  }
}

// Adds "why did this change?" hover annotations on the modified side. The change MARKING itself comes
// from Monaco's own diff computation; these decorations cover the WHOLE changed block and, on hover,
// name the finding id(s) it belongs to (e.g. "S1" or "S1 + S2", matching the list below the diff) plus
// the reason(s). Anchors (verbatim lines the model copied from the fixed code) only LOCATE a block;
// the block extent comes from Monaco, so we never rely on AI line numbers.
function setChangeReasons(reasonsJson) {
  try {
    changeReasons = JSON.parse(reasonsJson || "[]");
  } catch (_error) {
    changeReasons = [];
  }
  if (!Array.isArray(changeReasons)) changeReasons = [];
  if (!diffEditor) return;
  if (!changeReasonListenerAttached) {
    // Monaco computes the diff asynchronously after setValue, so re-apply on every diff update.
    diffEditor.onDidUpdateDiff(function () { applyReasonDecorations(); });
    changeReasonListenerAttached = true;
  }
  applyReasonDecorations();
}

// Restricts the reason decorations to one finding id. An unknown or empty id means "no filter", so a
// stale selection can never blank the whole diff annotation.
function setReasonFilter(finding) {
  const next = String(finding || "").trim();
  if (next === reasonFilter) return;
  reasonFilter = next;
  pendingFilterReveal = next !== "";
  applyReasonDecorations();
}

function matchesReasonFilter(item) {
  return reasonFilter === "" || (item && String(item.finding || "").trim() === reasonFilter);
}

function firstAnchorLine(text) {
  const raw = String(text || "");
  const line = raw.split(/\r?\n/).find((l) => l.trim().length > 0) || raw;
  return line.trim();
}

function normalizeLine(text) {
  return String(text || "").replace(/\s+/g, " ").trim();
}

// Best-effort anchor location on the modified side. The AI's anchor line is often NOT verbatim in the
// final code (re-indented, re-quoted, case-shifted), so a strict findMatches alone silently drops the
// hover for that change. Escalate: exact match -> case-insensitive match -> whitespace-normalized
// case-insensitive line scan (containment either way, with length guards against trivial matches).
function locateAnchorLine(anchorText) {
  const anchor = firstAnchorLine(anchorText);
  if (!anchor || !modifiedModel) return null;
  let matches = modifiedModel.findMatches(anchor, false, false, true, null, false, 1);
  if (matches.length === 0) {
    matches = modifiedModel.findMatches(anchor, false, false, false, null, false, 1);
  }
  if (matches.length > 0) return matches[0].range.startLineNumber;

  const needle = normalizeLine(anchor).toLowerCase();
  if (needle.length < 4) return null;
  const lineCount = modifiedModel.getLineCount();
  for (let ln = 1; ln <= lineCount; ln++) {
    const hay = normalizeLine(modifiedModel.getLineContent(ln)).toLowerCase();
    if (hay.length < 4) continue;
    if (hay === needle || hay.includes(needle) || (hay.length >= 8 && needle.includes(hay))) {
      return ln;
    }
  }
  return null;
}

function applyReasonDecorations() {
  if (!diffEditor || !modifiedModel) return;
  const modifiedEditor = diffEditor.getModifiedEditor();
  changeDecorations = modifiedEditor.deltaDecorations(changeDecorations, []);
  const reasons = Array.isArray(changeReasons) ? changeReasons : [];
  if (reasons.length === 0) return;

  // Locate each reason's anchor line on the modified side (best-effort; may stay null).
  const located = [];
  for (let r = 0; r < reasons.length; r++) {
    const change = reasons[r];
    if (!change || !change.reason) continue;
    located.push({
      idx: typeof change.idx === "number" ? change.idx : r,
      finding: String(change.finding || "").trim(),
      reason: change.reason,
      line: locateAnchorLine(change.anchor),
      start: null,
      end: null
    });
  }
  if (located.length === 0) return;

  // Monaco's own computed changed-line ranges on the modified side define the block extents.
  const lineChanges =
    (typeof diffEditor.getLineChanges === "function" ? diffEditor.getLineChanges() : null) || [];
  const blocks = lineChanges
    .filter((c) => c.modifiedEndLineNumber > 0 && c.modifiedEndLineNumber >= c.modifiedStartLineNumber)
    .map((c) => ({ start: c.modifiedStartLineNumber, end: c.modifiedEndLineNumber, groups: [] }));

  const assigned = new Set();
  for (const block of blocks) {
    for (let i = 0; i < located.length; i++) {
      const item = located[i];
      if (item.line != null && item.line >= block.start && item.line <= block.end) {
        block.groups.push(item);
        assigned.add(i);
      }
    }
  }
  // Reasons whose anchor could not be located at all: pair them with the not-yet-annotated changed
  // blocks in document order (the model reports its changes in code order), so every reason still gets
  // a hover home instead of silently disappearing from the diff.
  const unlocated = [];
  for (let i = 0; i < located.length; i++) {
    if (!assigned.has(i) && located[i].line == null) unlocated.push(located[i]);
  }
  if (unlocated.length > 0) {
    const emptyBlocks = blocks.filter((b) => b.groups.length === 0);
    for (let i = 0; i < unlocated.length; i++) {
      if (i < emptyBlocks.length) {
        emptyBlocks[i].groups.push(unlocated[i]);
      } else if (emptyBlocks.length > 0) {
        emptyBlocks[emptyBlocks.length - 1].groups.push(unlocated[i]);
      }
    }
  }

  // Resolve every reason's block first and filter only when building decorations: the range report
  // below keeps its line numbers for ALL cards, so filtering never blanks their "Lines 23-40" chips.
  const decorations = [];
  const focused = reasonFilter !== "";
  let firstFilteredLine = null;
  for (const block of blocks) {
    if (block.groups.length === 0) continue;
    for (const item of block.groups) {
      item.start = block.start;
      item.end = block.end;
    }
    const shown = block.groups.filter(matchesReasonFilter);
    if (shown.length === 0) continue;
    if (firstFilteredLine == null) firstFilteredLine = block.start;
    decorations.push(reasonDecoration(block.start, block.end, shown, focused));
  }
  // Located anchors that sit outside every detected block (context lines): single-line marker.
  for (let i = 0; i < located.length; i++) {
    const item = located[i];
    if (item.start != null || item.line == null) continue;
    item.start = item.line;
    item.end = item.line;
    if (!matchesReasonFilter(item)) continue;
    if (firstFilteredLine == null) firstFilteredLine = item.line;
    decorations.push(reasonDecoration(item.line, item.line, [item], focused));
  }
  changeDecorations = modifiedEditor.deltaDecorations([], decorations);

  // Scroll to the selection once, right after it was picked. Monaco re-runs this on every diff
  // update, and yanking the viewport back on each of those would fight the reviewer's own scrolling.
  if (pendingFilterReveal) {
    pendingFilterReveal = false;
    if (firstFilteredLine != null && typeof modifiedEditor.revealLineInCenter === "function") {
      modifiedEditor.revealLineInCenter(firstFilteredLine);
    }
  }

  // Report each reason's resolved line range (modified side) so the host can show "Lines 23-40"
  // next to the explanation cards below the diff.
  try {
    const ranges = located
      .filter((item) => item.start != null)
      .map((item) => ({ idx: item.idx, start: item.start, end: item.end }));
    notify("onChangeReasonRanges", JSON.stringify(ranges));
  } catch (_error) {
    // Range reporting is cosmetic; never let it break the decorations.
  }
}

function reasonDecoration(startLine, endLine, group, focused) {
  const ids = [];
  for (const item of group) {
    if (item.finding && ids.indexOf(item.finding) < 0) ids.push(item.finding);
  }
  const header = ids.length > 0 ? "**" + ids.join(" + ") + "**" : "";
  let message;
  if (group.length === 1) {
    // Single finding: show the id once as a header, then just the reason.
    message = (header ? header + "\n\n" : "") + group[0].reason;
  } else {
    // Multiple findings on one block: combined header (e.g. "S1 + S2") then each reason, id-prefixed.
    const body = group
      .map((item) => (item.finding ? "**" + item.finding + "**: " : "") + item.reason)
      .join("\n\n");
    message = (header ? header + "\n\n" : "") + body;
  }
  const endColumn = modifiedModel.getLineMaxColumn(endLine);
  return {
    range: new monaco.Range(startLine, 1, endLine, endColumn),
    options: {
      isWholeLine: true,
      linesDecorationsClassName: focused
        ? "kortty-change-reason-bar kortty-change-reason-bar-focus"
        : "kortty-change-reason-bar",
      // Only the picked finding gets a line background, so its places stand out from the diff's own
      // colouring of every other changed block.
      className: focused ? "kortty-change-reason-focus" : undefined,
      hoverMessage: { value: message }
    }
  };
}

function setFont(fontFamily, fontSize) {
  if (diffEditor) {
    diffEditor.updateOptions({
      fontFamily: fontFamily || "Monospaced",
      fontSize: Math.max(8, Number(fontSize || 14))
    });
  }
}

function setTheme(theme) {
  defineTheme(theme || {});
}

function dispose() {
  releaseWorkers();
  if (diffEditor) diffEditor.dispose();
  if (originalModel) originalModel.dispose();
  if (modifiedModel) modifiedModel.dispose();
  diffEditor = null;
  originalModel = null;
  modifiedModel = null;
  changeDecorations = [];
  changeReasons = [];
  changeReasonListenerAttached = false;
  reasonFilter = "";
  pendingFilterReveal = false;
}

export function installDiffHost() {
  window.korttyMonacoDiff = {
    boot,
    setValue,
    setFont,
    setTheme,
    setChangeReasons,
    setReasonFilter,
    dispose
  };
}
