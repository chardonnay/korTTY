import { installEditorHost } from "./host.js";
import { installDiffHost } from "./diff-host.js";

const mode = document.documentElement.dataset.monacoMode;

if (mode === "editor") {
  installEditorHost();
} else if (mode === "diff") {
  installDiffHost();
} else {
  throw new Error(`Unsupported Monaco page mode: ${String(mode)}`);
}
