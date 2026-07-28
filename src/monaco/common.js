// monaco-editor 0.56 replaced the raw "monaco-editor/esm/vs/..." deep paths with supported,
// tree-shakeable entry points: "monaco-editor/editor" for the API, "features/register.all" for
// the editor features (the successor of the old editor.all.js), "languages/definitions/<id>/
// register" per Monarch language and "languages/features/<id>/register" for the worker-backed
// CSS/HTML/JSON/TypeScript services. Importing the bare "monaco-editor" root would pull in all
// ~80 languages, so the languages stay enumerated to keep the WebView bundle small.
import * as monaco from "monaco-editor/editor";
import "monaco-editor/features/register.all";
import "monaco-editor/languages/definitions/dockerfile/register";
import "monaco-editor/languages/definitions/css/register";
import "monaco-editor/languages/definitions/go/register";
import "monaco-editor/languages/definitions/hcl/register";
import "monaco-editor/languages/definitions/html/register";
import "monaco-editor/languages/definitions/ini/register";
import "monaco-editor/languages/definitions/java/register";
import "monaco-editor/languages/definitions/javascript/register";
import "monaco-editor/languages/definitions/markdown/register";
import "monaco-editor/languages/definitions/perl/register";
import "monaco-editor/languages/definitions/powershell/register";
import "monaco-editor/languages/definitions/python/register";
import "monaco-editor/languages/definitions/ruby/register";
import "monaco-editor/languages/definitions/rust/register";
import "monaco-editor/languages/definitions/shell/register";
import "monaco-editor/languages/definitions/sql/register";
import "monaco-editor/languages/definitions/xml/register";
import "monaco-editor/languages/definitions/yaml/register";
import "monaco-editor/languages/features/css/register";
import "monaco-editor/languages/features/html/register";
import "monaco-editor/languages/features/json/register";
import "monaco-editor/languages/features/typescript/register";
import { WORKER_SOURCES } from "./generated/workerSources.js";

const workerUrls = new Map();

export { monaco };

export function notify(name, ...args) {
  const target = window.javaBridge || null;
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

export function probeWorkers() {
  for (const label of ["editorWorkerService", "json", "css", "html", "typescript"]) {
    try {
      const worker = self.MonacoEnvironment.getWorker("", label);
      setTimeout(() => worker.terminate(), 200);
    } catch (_error) {
      // getWorker already reports the failure through the bridge.
    }
  }
}

export function releaseWorkers() {
  for (const url of workerUrls.values()) URL.revokeObjectURL(url);
  workerUrls.clear();
}

export function registerExtraLanguages() {
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

export function sanitizeColor(value, fallback) {
  return typeof value === "string" && /^#[0-9a-fA-F]{6}$/.test(value.trim()) ? value.trim() : fallback;
}
