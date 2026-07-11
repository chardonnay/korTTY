import { build } from "esbuild";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const outDir = process.argv[2];

if (!outDir) {
  throw new Error("Output directory argument is required");
}

const workerEntries = {
  editor: "workers/editor.worker.js",
  json: "workers/json.worker.js",
  css: "workers/css.worker.js",
  html: "workers/html.worker.js",
  ts: "workers/ts.worker.js"
};

await fs.rm(outDir, { recursive: true, force: true });
await fs.mkdir(outDir, { recursive: true });
await fs.mkdir(path.join(root, "generated", "workers"), { recursive: true });

const workerSources = {};
for (const [name, relativeEntry] of Object.entries(workerEntries)) {
  // Workers are only read back below and embedded as blob sources into the shared host bundle;
  // the standalone files are never fetched at runtime, so they must not land in outDir
  // (everything there ships inside the app jar).
  const outfile = path.join(root, "generated", "workers", `${name}.worker.js`);
  await build({
    entryPoints: [path.join(root, relativeEntry)],
    bundle: true,
    outfile,
    format: "iife",
    platform: "browser",
    target: ["safari15"],
    // Minify: the unminified Monaco bundle is ~27 MB and the packaged-app WebView cannot parse
    // it within MonacoEditorPane's boot-ready timeout, so the editor never initializes
    // (window.korttyMonaco stays undefined → no caret/typing/paste). Minified it is ~1/4 the
    // size and parses well inside the window. Monaco is designed to ship minified.
    minify: true,
    legalComments: "none",
    logLevel: "silent"
  });
  workerSources[name] = await fs.readFile(outfile, "utf8");
}

const generatedWorkerModule = path.join(root, "generated", "workerSources.js");
await fs.writeFile(
  generatedWorkerModule,
  `export const WORKER_SOURCES = ${JSON.stringify(workerSources)};\n`,
  "utf8"
);

await build({
  entryPoints: [path.join(root, "monaco-host.js")],
  bundle: true,
  outfile: path.join(outDir, "monaco-host.js"),
  format: "iife",
  platform: "browser",
  target: ["safari15"],
  loader: {
    ".ttf": "dataurl"
  },
  // Minify: see the worker build above — the unminified host bundle exceeds the WebView boot-ready
  // timeout in the packaged app. This one graph contains both page modes while Monaco and its CSS
  // are emitted only once.
  minify: true,
  legalComments: "none",
  logLevel: "info"
});
