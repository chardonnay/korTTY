import fs from "node:fs/promises";
import { pathToFileURL } from "node:url";

const [inputFile, outputFile, esbuildModule] = process.argv.slice(2);

if (!inputFile || !outputFile || !esbuildModule) {
  throw new Error("Input bundle, output bundle and esbuild module arguments are required");
}

const { transform } = await import(pathToFileURL(esbuildModule));
const source = await fs.readFile(inputFile, "utf8");
const result = await transform(source, {
  platform: "browser",
  target: ["safari15"],
  minify: true,
  legalComments: "eof",
  sourcefile: "mermaid.upstream.min.js"
});

await fs.writeFile(outputFile, result.code, "utf8");
