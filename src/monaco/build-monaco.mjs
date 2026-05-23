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
await fs.mkdir(path.join(root, "generated"), { recursive: true });

const workerSources = {};
for (const [name, relativeEntry] of Object.entries(workerEntries)) {
  const outfile = path.join(outDir, `${name}.worker.js`);
  await build({
    entryPoints: [path.join(root, relativeEntry)],
    bundle: true,
    outfile,
    format: "iife",
    platform: "browser",
    target: ["safari15"],
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
  entryPoints: [path.join(root, "host.js")],
  bundle: true,
  outfile: path.join(outDir, "monaco-host.js"),
  format: "iife",
  platform: "browser",
  target: ["safari15"],
  loader: {
    ".ttf": "dataurl"
  },
  logLevel: "info"
});

await build({
  entryPoints: [path.join(root, "diff-host.js")],
  bundle: true,
  outfile: path.join(outDir, "monaco-diff-host.js"),
  format: "iife",
  platform: "browser",
  target: ["safari15"],
  loader: {
    ".ttf": "dataurl"
  },
  logLevel: "info"
});
