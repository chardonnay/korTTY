# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.23.0

### On Windows

- **PowerShell and Command Prompt in the toolbar** — on Windows, the Terminal group of the icon toolbar gets **New PowerShell** and **New Command Prompt** right after **New local shell**, each with its own icon; saved layouts gain both once. With Git for Windows installed, the customization catalog also offers **New Git Bash**. See [Customizing the icon toolbar](../getting-started/main-window.md#customizing-the-icon-toolbar).
- **Local shells run under ConPTY** — local shells and the native Mosh client now start under the Windows pseudo console instead of winpty, which no longer interferes with keys typed into other applications while korTTY runs.
- **Mosh Client finds mosh-client.exe** — the Mosh Client (native) protocol now looks for `mosh-client.exe` in the folders of the `PATH`. See [Protocols](../features/connections.md#protocols).

### Toolbar and split panes

- **Toolbar groups wrap instead of shrinking** — when the window is too narrow, whole toolbar groups move to another line, so every button stays directly clickable instead of hiding behind an overflow chevron. See [Customizing the icon toolbar](../getting-started/main-window.md#customizing-the-icon-toolbar).
- **Focus ring can be switched off** — switch off **Frame the split pane the keyboard is in** in the Window settings to hide the ring around the active split pane. You then recognize the active pane by its cursor. See [Tabs](../reference/settings/window.md#tabs).

### Snippets

- **Folder analysis by file** — the folder analysis tab lists the folder's files beside the report. Pick a file, or step through the files with ◀ ▶ and the arrow keys, to see only its findings and a short description of what the file does; each row shows how many of its findings are ticked, and its checkbox ticks or clears them all. Findings keep their file even when the model numbers them per file or names the file another way. See [Analysing a folder as one project](../features/snippets.md#analysing-a-folder-as-one-project).
- **Vertical or horizontal flow diagram** — an **Orientation** control in the diagram toolbar draws the flowchart top to bottom or left to right, in the analysis panel, its zoom window and the Diagram dialog. Switching redraws the stored diagram without asking the AI again. See [Diagram appearance](../features/snippets.md#diagram-appearance).

### AI

- **"Model default" instead of "Disabled"** — the reasoning setting that sends no reasoning parameter is now called **Model default**, because the model's own default then applies; only **None** switches reasoning off. A tooltip on the dropdown explains the difference. See [Reasoning Effort Levels](../reference/settings/ai.md#reasoning-effort-levels).
- **Connection test accepts reasoning-only replies** — **Test AI Connection** and **Refresh reasoning options** now pass when a model that reasons by default, such as Qwen in LM Studio, spends the short test reply on reasoning, and a failed refresh names the endpoint's own reason.

### Fixes

- **Dismissed sessions stay dismissed** — closing the last tab on purpose now empties the session snapshot, so the restore offer no longer comes back at every start after you discard it.
- **Steady diagram tooltips** — the code-reference tooltip in the enlarged analysis diagram stays open while the mouse rests on a node instead of flashing and closing.
- **Folder analyses survive a malformed key** — an analysis whose JSON lacks the opening quote of one key is repaired instead of being discarded as "no usable analysis".
- **German label for reasoning** — the German user interface now labels this setting of AI profiles "Reasoning", as the guide does.
