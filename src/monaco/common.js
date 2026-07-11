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
