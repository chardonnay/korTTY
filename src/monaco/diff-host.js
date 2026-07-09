import * as monaco from "monaco-editor/esm/vs/editor/editor.api.js";
import "monaco-editor/esm/vs/editor/editor.all.js";
import "monaco-editor/esm/vs/basic-languages/dockerfile/dockerfile.contribution.js";
import "monaco-editor/esm/vs/basic-languages/css/css.contribution.js";
import "monaco-editor/esm/vs/basic-languages/go/go.contribution.js";
import "monaco-editor/esm/vs/basic-languages/hcl/hcl.contribution.js";
import "monaco-editor/esm/vs/basic-languages/html/html.contribution.js";
import "monaco-editor/esm/vs/basic-languages/ini/ini.contribution.js";
import "monaco-editor/esm/vs/basic-languages/java/java.contribution.js";
import "monaco-editor/esm/vs/basic-languages/javascript/javascript.contribution.js";
import "monaco-editor/esm/vs/basic-languages/markdown/markdown.contribution.js";
import "monaco-editor/esm/vs/basic-languages/perl/perl.contribution.js";
import "monaco-editor/esm/vs/basic-languages/powershell/powershell.contribution.js";
import "monaco-editor/esm/vs/basic-languages/python/python.contribution.js";
import "monaco-editor/esm/vs/basic-languages/ruby/ruby.contribution.js";
import "monaco-editor/esm/vs/basic-languages/rust/rust.contribution.js";
import "monaco-editor/esm/vs/basic-languages/shell/shell.contribution.js";
import "monaco-editor/esm/vs/basic-languages/sql/sql.contribution.js";
import "monaco-editor/esm/vs/basic-languages/xml/xml.contribution.js";
import "monaco-editor/esm/vs/basic-languages/yaml/yaml.contribution.js";
import "monaco-editor/esm/vs/language/css/monaco.contribution.js";
import "monaco-editor/esm/vs/language/html/monaco.contribution.js";
import "monaco-editor/esm/vs/language/json/monaco.contribution.js";
import "monaco-editor/esm/vs/language/typescript/monaco.contribution.js";
import { WORKER_SOURCES } from "./generated/workerSources.js";

let diffEditor;
let originalModel;
let modifiedModel;
let changeDecorations = [];
let changeReasons = [];
let changeReasonListenerAttached = false;
let booted = false;
let currentThemeName = "kortty-monaco-diff-theme";
let currentTheme = {};
const workerUrls = new Map();

function bridge() {
  return window.javaBridge || null;
}

function notify(name, ...args) {
  const target = bridge();
  if (target && typeof target[name] === "function") {
    try {
      target[name](...args);
    } catch (error) {
      console.error(`Bridge call failed: ${name}`, error);
    }
  }
}

function workerSourceFor(label) {
  if (label === "json") return WORKER_SOURCES.json;
  if (label === "css" || label === "less" || label === "scss") return WORKER_SOURCES.css;
  if (label === "html" || label === "handlebars" || label === "razor") return WORKER_SOURCES.html;
  if (label === "typescript" || label === "javascript") return WORKER_SOURCES.ts;
  return WORKER_SOURCES.editor;
}

function workerUrlFor(label) {
  const key = label || "editorWorkerService";
  if (workerUrls.has(key)) {
    return workerUrls.get(key);
  }
  const source = workerSourceFor(key);
  const blob = new Blob([source], { type: "text/javascript" });
  const url = URL.createObjectURL(blob);
  workerUrls.set(key, url);
  return url;
}

self.MonacoEnvironment = {
  getWorker(_moduleId, label) {
    try {
      const worker = new Worker(workerUrlFor(label));
      worker.onerror = (event) => notify("onWorkerFailed", label || "editorWorkerService", event.message || "Worker error");
      notify("onWorkerReady", label || "editorWorkerService");
      return worker;
    } catch (error) {
      notify("onWorkerFailed", label || "editorWorkerService", String(error && error.message ? error.message : error));
      throw error;
    }
  }
};

function registerExtraLanguages() {
  registerMonarch("toml", {
    tokenizer: {
      root: [
        [/#.*$/, "comment"],
        [/\[[^\]]+\]/, "keyword"],
        [/[A-Za-z0-9_.-]+(?=\s*=)/, "key"],
        [/"([^"\\]|\\.)*"|'([^'\\]|\\.)*'/, "string"],
        [/\b(true|false)\b/, "keyword"],
        [/\b-?\d+(\.\d+)?\b/, "number"]
      ]
    }
  });
  registerMonarch("jinja2", {
    tokenizer: {
      root: [
        [/\{#[\s\S]*?#\}/, "comment"],
        [/\{\{[\s\S]*?\}\}/, "variable"],
        [/\{%[\s\S]*?%\}/, "keyword"],
        [/"([^"\\]|\\.)*"|'([^'\\]|\\.)*'/, "string"]
      ]
    }
  });
  registerMonarch("puppet", {
    tokenizer: {
      root: [
        [/#.*$/, "comment"],
        [/"([^"\\]|\\.)*"|'([^'\\]|\\.)*'/, "string"],
        [/\b(class|define|node|include|require|contain|if|elsif|else|unless|case|true|false|undef|default)\b/, "keyword"],
        [/\$[a-zA-Z_][a-zA-Z0-9_:]*/, "variable"],
        [/=>|->|~>|\+>|<\||\|>/, "operator"]
      ]
    }
  });
  registerMonarch("cfengine3", {
    tokenizer: {
      root: [
        [/#.*$/, "comment"],
        [/"([^"\\]|\\.)*"|'([^'\\]|\\.)*'/, "string"],
        [/\b(bundle|body|promise|agent|common|server|classes|commands|files|methods|packages|processes|reports|vars|defaults)\b/, "keyword"],
        [/\$\([^)]+\)|\$\{[^}]+\}|@\([^)]+\)|@\{[^}]+\}/, "variable"],
        [/[a-zA-Z_]+:/, "keyword"],
        [/=>|->/, "operator"]
      ]
    }
  });
}

function registerMonarch(id, language) {
  if (!monaco.languages.getLanguages().some((entry) => entry.id === id)) {
    monaco.languages.register({ id });
  }
  monaco.languages.setMonarchTokensProvider(id, language);
}

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

function sanitizeColor(value, fallback) {
  return typeof value === "string" && /^#[0-9a-fA-F]{6}$/.test(value.trim()) ? value.trim() : fallback;
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
  booted = true;
  registerExtraLanguages();
  defineTheme(config.theme || {});
  originalModel = createModel(config.id, "original", config.originalValue, config.originalLanguage);
  modifiedModel = createModel(config.id, "modified", config.modifiedValue, config.modifiedLanguage);
  diffEditor = monaco.editor.createDiffEditor(document.getElementById("diff-editor"), {
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

function probeWorkers() {
  for (const label of ["editorWorkerService", "json", "css", "html", "typescript"]) {
    try {
      const worker = self.MonacoEnvironment.getWorker("", label);
      setTimeout(() => worker.terminate(), 200);
    } catch (_error) {
      // getWorker already reports the failure through the bridge.
    }
  }
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

  const decorations = [];
  for (const block of blocks) {
    if (block.groups.length === 0) continue;
    for (const item of block.groups) {
      item.start = block.start;
      item.end = block.end;
    }
    decorations.push(reasonDecoration(block.start, block.end, block.groups));
  }
  // Located anchors that sit outside every detected block (context lines): single-line marker.
  for (let i = 0; i < located.length; i++) {
    const item = located[i];
    if (item.start != null || item.line == null) continue;
    item.start = item.line;
    item.end = item.line;
    decorations.push(reasonDecoration(item.line, item.line, [item]));
  }
  changeDecorations = modifiedEditor.deltaDecorations([], decorations);

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

function reasonDecoration(startLine, endLine, group) {
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
      linesDecorationsClassName: "kortty-change-reason-bar",
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
  for (const url of workerUrls.values()) URL.revokeObjectURL(url);
  workerUrls.clear();
  if (diffEditor) diffEditor.dispose();
  if (originalModel) originalModel.dispose();
  if (modifiedModel) modifiedModel.dispose();
  diffEditor = null;
  originalModel = null;
  modifiedModel = null;
  changeDecorations = [];
  changeReasons = [];
  changeReasonListenerAttached = false;
}

window.korttyMonacoDiff = {
  boot,
  setValue,
  setFont,
  setTheme,
  setChangeReasons,
  dispose
};
