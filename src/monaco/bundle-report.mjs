// Size breakdown of the bundled Monaco host script, from the esbuild metafile that
// build-monaco.mjs writes next to the generated worker sources. Run from the build workspace:
//
//   build/monaco-node/bin/node src/bundle-report.mjs            (in build/monaco-workspace)
//
// Everything listed here is parsed by the JavaFX WebView on the FX thread on every editor boot,
// so this is the number to look at before adding a language service or worker.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const metafile = JSON.parse(fs.readFileSync(path.join(root, "generated", "monaco-host.meta.json"), "utf8"));
const output = Object.values(metafile.outputs).find((o) => o.entryPoint);

const rules = [
  ["embedded worker sources (WORKER_SOURCES)", /generated\/workerSources\.js$/],
  ["editor core + features (vs/editor)", /monaco-editor\/esm\/vs\/editor\//],
  ["vs/base + vs/platform", /monaco-editor\/esm\/vs\/(base|platform)\//],
  ["language services (vs/language)", /monaco-editor\/esm\/vs\/language\//],
  ["basic-languages (tokenizers)", /monaco-editor\/esm\/vs\/basic-languages\//],
  ["languages registry (vs/languages)", /monaco-editor\/esm\/vs\/languages\//],
  ["fonts (codicon ttf data-url)", /\.ttf$/],
  ["korTTY host js", /^src\//]
];
const groups = new Map();
for (const [file, info] of Object.entries(output.inputs)) {
  const rule = rules.find(([, re]) => re.test(file));
  const name = rule ? rule[0] : "other: " + file.split("/").slice(0, 5).join("/");
  groups.set(name, (groups.get(name) || 0) + info.bytesInOutput);
}
console.log(`monaco-host.js: ${output.bytes} bytes`);
for (const [name, bytes] of [...groups.entries()].sort((a, b) => b[1] - a[1])) {
  console.log(String(bytes).padStart(10), (100 * bytes / output.bytes).toFixed(1).padStart(5) + "%", name);
}

const workerModule = fs.readFileSync(path.join(root, "generated", "workerSources.js"), "utf8");
const workers = JSON.parse(workerModule.slice(workerModule.indexOf("=") + 1).replace(/;\s*$/, ""));
console.log("--- embedded workers (minified source bytes) ---");
for (const [name, source] of Object.entries(workers).sort((a, b) => b[1].length - a[1].length)) {
  console.log(String(source.length).padStart(10), `${name}.worker`);
}
