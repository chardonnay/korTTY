---
title: Snippet manager
---

# Snippet manager

The Snippet Manager lets you store, organize, and quickly insert reusable code snippets, scripts, and configuration templates. Manage snippets across multiple languages with syntax highlighting, advanced search, AI-assisted editing, placeholder variables, and flexible export options.

## Overview

The Snippet Manager includes the following features:

- **System (OS) column** — A sortable operating-system column for each snippet (Any, Linux, macOS, Windows). Auto-set when a snippet is created via *Generate Workflow Script*.
- **Sortable columns** — All columns (Name, Language, Category, System, Tags) are sortable.
- **Script-Header category** — A fixed, non-deletable category containing reusable header templates for workflow-script generation.

## Opening the Snippet Manager

- **Menu:** Tools → Snippet Manager
- **Shortcut:** ++ctrl+shift+s++ (++cmd+shift+s++ on macOS)

## Creating and editing snippets

1. Click **Add** (or **Edit** to modify an existing snippet).
2. Fill in the fields:
   - **Name** — A descriptive name.
   - **Language** — Select the programming language (Bash, Python, Java, JavaScript, SQL, XML, JSON, YAML, and more). Enables syntax highlighting.
   - **Category** — Select an existing category or type a new one. The fixed non-deletable *Script-Header* category contains reusable header templates for generated workflow scripts.
   - **System** — Optionally select a target operating system (Any, Linux, macOS, Windows). Auto-set when created via *Generate Workflow Script* based on the agent's probed OS; you can manually override it for any snippet.
   - **Tags** — Comma-separated keywords for searching (e.g., `docker, deploy, backup`).
   - **Description** — Optional free-text description of the snippet.
   - **Content** — The snippet code. The editor provides live syntax highlighting based on the selected language.
3. Click **OK**. If the snippet content changed, KorTTY saves the edited snippet while closing the dialog. When editing an existing entry, **Save as new snippet** stores the current content as a new snippet with a new ID and leaves the original unchanged.

### Editor toolbar and features

The snippet editor toolbar provides:

- **Format Code** — Format the content using local formatters or AI-assisted formatting.
- **Check Syntax** — Validate the syntax (local or AI-assisted).
- **AI Text** — Correct spelling, translate, or generate technical descriptions.
- **AI Code** — Complete code, run a full code analysis, improve a selection (readability, robustness, performance, or a custom instruction), check security, or generate diagrams.
- **One-liner** — Export as a terminal one-liner.
- **Editor zoom** — Adjust text size with ++ctrl+plus++ and ++ctrl+minus++.
- **Editor profiles** — Switch between built-in IntelliJ-inspired profiles and custom color schemes.
- **Background brightness** — Adjust editor background.
- **Word Wrap** — Toggle line wrapping.
- **Line numbers** — Toggle line-number display.

When the editor opens from the SFTP Manager for a local or remote file, the same toolbar remains available and the dialog uses file-mode save buttons.

### Column ruler and line-width formatting

Above the content field, the column ruler keeps the current caret column fixed at the left as `Column N` and shows a live marker at the matching editor position. Moving the mouse over the live marker shows `Position N`. Click the ruler to set a maximum line-length marker (columns 20–240). Right-click that marker to format the content to the selected width or remove the marker.

Line-width formatting works locally only for formatters that support configurable width:

- Prettier-backed web formats (JavaScript, TypeScript, HTML, CSS, JSON)
- Python (Black)
- Perl (Perl::Tidy)

For languages without local line-width support, KorTTY asks whether to use AI-assisted formatting. Both local and AI-assisted formatting show a before/after preview before applying changes.

### Format Code

**Format Code** uses KorTTY's shared local formatter service:

- **Built-in formatters:** JSON, XML, YAML/YML, TOML, INI/properties, Groovy
- **Bundled formatters:** Java (google-java-format), Bash/shell (shfmt), Web/JS/TS/HTML/CSS (Prettier), SQL (sql-formatter), Perl (Perl::Tidy)
- **Fallback:** Optional PATH fallbacks for developer setups when a bundled formatter is missing

If local formatting is unavailable and the configured AI profile provides snippet AI capability, KorTTY asks whether to use AI assistance. AI formatting is applied only after the before/after preview is accepted.

### Editor profiles

Switch between:

- **Current custom colors** — User-defined palette
- **10 built-in IntelliJ-inspired profiles** — Predefined color schemes
- **User-created profiles** — Custom profiles you create

Profiles store foreground/background colors, syntax colors, cursor color, and cursor style.

![Snippet editor workflow](../assets/diagrams/snippet-editor-workflow.svg)

## AI-assisted editor functions

If AI is configured, the snippet editor offers additional actions:

### AI suggestions

- **AI suggestion** — Generates a file name, description, and matching language from the current code content.
- **Correct spelling** — On the description field; sends only description text to the AI.

### AI Text menu

- **Correct spelling in selection** — Fix typos in selected text.
- **Translate selection…** — Translate selected text to another language.
- **Technical description** — Generate documentation for selected code or the whole snippet.

### Optional additional instructions

If enabled in *Settings → AI*, the editor shows a shared instructions field sent with spelling correction, translation, and technical description requests.

### Last AI change toggle

The ↺ button switches between the original code and the last AI-generated editor change.

### AI Code completions

- **AI Complete** — Requests code completion at the current cursor position and shows it as a non-editing ghost suggestion. Click to insert.
- **Auto AI Complete** — Requests completions automatically after you pause at a cursor position. Off by default; only active for the current editor session.

### AI Code actions

The **AI Code** menu groups the actions that read or rewrite the code itself:

- **AI Complete** / **Auto AI Complete** — Code completion at the cursor (see [AI Code completions](#ai-code-completions) above).
- **Full code analysis** — Opens a rich analysis window: a plain-language summary of what the script does, its external dependencies, categorized improvement suggestions you can tick and apply, and an auto-generated flow diagram. See [Full code analysis](#full-code-analysis) below.
- **Improve readability / robustness / performance** — Rewrites the **selected** code region toward one goal without unrelated changes. *Improve robustness* additionally offers [Hardening options](../reference/hardening-options.md) before it runs.
- **Custom improvement…** — Rewrites the selected code region following a free-text instruction you type, with the same [Hardening options](../reference/hardening-options.md).
- **Security Check** — Generates a security report. Select findings to fix; KorTTY applies them with a before/after preview that highlights what changed and why. See [Security Check](#security-check) below.
- **Diagram** — Generates and saves a persisted PlantUML logical-structure diagram for the snippet.

The editor context menu also offers **AI Assistant…**, which opens an instruction dialog for the current cursor position: KorTTY sends the full snippet, cursor offset, line, column, and your instruction to the configured AI profile and shows the result as a before/after preview.

All improvement actions rewrite the selected region only, so **select a code region first** — otherwise KorTTY prompts you to. The rewrite is always shown as a before/after preview (the *Review AI change* window) before anything is applied.

!!! warning
    Snippet AI actions send the current snippet content, selection or cursor metadata, prompt instructions, and optionally enabled AI Skills to the configured default AI profile (or, for Security Check, the dedicated security-check profile). Snippet AI actions do not enable internet tools, even when the selected profile has internet access. Auto-completion can send the snippet repeatedly while active, so disable it for sensitive snippets unless you trust the configured endpoint.

#### Full code analysis

**Full code analysis** opens a dedicated window that examines the whole snippet at once and offers concrete improvements you can apply. The window is **non-modal** — you can keep editing the snippet while it stays open — and its title bar shows the script's file name so you can tell several analyses apart. The snippet editor's own title bar likewise shows the name of the file you are editing.

A toolbar runs along the top of the window, the report and flow diagram fill the two panes below it, and a script-header selector plus a collapsible hardening panel sit in the footer.

**Toolbar:**

- **Profile in use** — The name of the AI profile the analysis ran with is shown on the left (for the default profile its *actual* name is shown, e.g. *Profile: LM Studio* — not just "Default profile"), so you can always tell which model produced the report.
- **AI skills** — When [AI Skills](../reference/settings/ai-skills.md) are configured, a row shows which skills were included and lets you change them; see **AI skills for this analysis** below.
- **Re-run** — A transient AI-profile picker plus a **Re-run** button repeat the analysis with the chosen profile *and* your current AI-skill selection. The picker resets to the default when the window is reopened.
- **Select all** — Tick every improvement and dependency at once.
- **A− / A+** — Adjust the reading font size (remembered across sessions).
- **Copy** — Copy the summary, improvements and dependencies to the clipboard as plain text.
- **Export** — Save the whole report (including the diagram) as a file; see **Export the report** below.

**Left — analysis and improvements:**

- **Summary** — A short, plain-language description of what the script does. It is a description, not a pickable item, so it is shown as a plain block without a selection accent.
- **Improvements** — Suggestions grouped into **Security**, **Optimization** and **Design** sections. Each section title carries a colour-coded icon and a count, and each suggestion has a severity badge, an explanation, and a concrete recommendation. Tick the ones you want; use **Select all** to tick everything at once. Empty sections are hidden.
- **Dependencies** — External programs, scripts or services the snippet relies on, each with its *Purpose* and a *Reduce/replace* suggestion. Tick a dependency to have its suggestion applied too.

**Right — flow diagram:**

- An **auto-generated flow diagram** of the script's logic renders while a spinner is shown, then fills the pane. It carries the full diagram toolbar: zoom **−** / **Fit** / **+**, **Save SVG** / **Save PNG**, **Copy image** / **Copy PlantUML**, a **Dark mode** control and a **Background** colour picker (both remembered), and **Regenerate**. See [Diagram appearance](#diagram-appearance) below.
- **Hover code references** — Moving the mouse over a diagram node shows the matching lines from the snippet, so you can trace each step back to the code — the same behaviour as the standalone [Diagram](#plantuml-diagrams) window.

**AI skills for this analysis:**

When [AI Skills](../reference/settings/ai-skills.md) are configured, a row at the top of the window shows exactly **which skills were included** in the analysis, as chips, with an **(auto-selected)** or **(manual)** badge:

- **Auto-selected** — korTTY pre-selects the skills relevant to the snippet by matching each skill's tags, name and description against the snippet's language and content, and includes them in the analysis. This is why the badge reads *(auto-selected)* on the first run.
- **Manual** — Click **Select…** to open a **searchable picker**: type in the search field to filter your saved skills by name, description or tags, then tick or untick the skills you want. As soon as you change the set, the badge switches to *(manual)* and korTTY keeps your choice instead of auto-selecting.

Changing the skills does **not** re-analyse immediately — the new set is applied on the next **Re-run**, so one deliberate click produces one analysis with exactly the skills you chose (and no surprise flurry of AI calls). Skills you include here are sent regardless of each skill's configured *target*. The row appears only when at least one AI Skill is enabled.

**Hardening options:**

At the bottom, a collapsible **Hardening options** panel lets you attach production-quality techniques (strict mode, error traps, meaningful exit codes, logging, idempotency, `--dry-run`, `--help`, and more) to the fixes that get applied. The panel title shows a live **count** of how many options are currently ticked — for example *Hardening options (11)* — and korTTY **remembers whether you left the panel open or closed** and restores that state the next time the window opens. See [Hardening options](../reference/hardening-options.md) for what each option means and how it is applied.

**Script header:**

A **Script header** selector lets you prepend one of your saved *Script-Header* snippets (from the fixed [Script-Header category](#creating-and-editing-snippets)) to the code when you apply the analysis. Pick a header — or leave it on *No header* (the default) — and its content, with variables substituted, is inserted at the top of the snippet, after an existing shebang / lead line, as part of the same change.

**Apply selected:**

When you click **Apply selected**, korTTY sends the ticked improvements and dependency suggestions (plus any hardening options) to the AI in one request and shows the result in an *Apply improvements — review changes* window: the original and rewritten script side by side, with changed lines highlighted and per-change reasons, exactly like the Security-Check review below. Any chosen **Script header** is prepended to the result before it is shown. Apply the change to update the editor. A header on its own — with no improvements, dependencies or hardening ticked — is inserted directly, without an AI round-trip, and still shown as a before/after preview first.

**Export the report:**

The **Export** button saves the full report — summary, categorized improvements, dependencies and the flow diagram — as a self-contained file in an attractive, print-friendly design. The export header records the script name, the AI profile used, the date, and the AI skills that were included:

- **PDF** — A paginated document with the diagram embedded as an image.
- **HTML** — A single self-contained web page (the diagram is embedded inline) that opens in any browser.
- **Markdown** — A `.md` file, with the diagram saved next to it as a PNG.

#### Security Check

The **Security Check** report window lists each finding with a colour-coded severity badge (findings are sorted most-severe first). From this window you can:

- Adjust the reading font size with **A−** / **A+** (remembered across sessions).
- Copy all findings to the clipboard.
- **Select all** findings at once, then apply the selected fixes.
- Choose a dedicated **Security profile** — the AI profile used for security checks. The choice is remembered permanently and is also available in **Configuration → Global Settings → AI**; leave it on *Use default profile* to reuse the default. Changing it takes effect immediately.
- **Re-run check** to repeat the review with the newly selected profile.

When you apply fixes, the **Review security fixes** window shows the original and corrected code side by side. Changed lines are highlighted automatically and carry a marker in the margin. Hover anywhere in a changed block to see which finding(s) it addresses — for example `S1`, or `S1 + S2` when one block covers two findings — together with the reason for the change. Hover matching tolerates re-indented or case-shifted lines, and a reason whose anchor line cannot be found at all is attached to the remaining changed blocks in order, so explanations no longer go missing from the diff. The same explanations are also listed as cards below the diff: each card carries the finding's badge and colour-coded category icon (the same icons as the analysis sections) plus the line range it affects on the corrected side (for example *Lines 23–40*), so the reasoning stays visible even when a marker cannot be placed. The preview font size can be zoomed and is remembered across sessions. The same review window (and its explanation cards) is used when applying **Full code analysis** improvements.

### AI profile, re-run and zoom

The AI-code report windows (Full code analysis, Security Check, the technical-description and alternative-solution dialogs, and the change-review diff) share a small toolbar:

- **AI profile** — Pick a different AI profile for the **next** run of that window. The choice is transient: it resets to the default profile when the window is reopened. (Security Check keeps its own permanently remembered *Security profile* instead.)
- **Re-run** — Repeat the request with the currently selected profile.
- **A− / A+** — Adjust the reading or preview font size; the chosen size is remembered across sessions, separately per window type.
- **Copy** — Copy the report or content to the clipboard.

### AI skills

When [AI Skills](../reference/settings/ai-skills.md) are configured, the snippet editor shows an **AI skills** picker. Skills relevant to the snippet's language are pre-selected automatically, and any skill you tick here is applied to **every** AI-code action (completion, analysis, improvement, security check, diagram) regardless of the skill's configured target. The picker appears only when at least one AI Skill is enabled.

The **Full code analysis** window surfaces this same selection as a row of chips — labelled *(auto-selected)* or *(manual)* — and lets you refine it just for that analysis through a searchable picker. Changes made there apply on the next **Re-run**. See [Full code analysis](#full-code-analysis).

### Text correction and translation

For selection-based text correction and translation, KorTTY only rewrites editable comment text, string literals, and user-facing text segments. It does not rewrite logical code structure.

### Technical descriptions

- If text is selected, the AI describes only that region.
- If nothing is selected, the AI describes the whole snippet.

The description dialog lets you:

- Copy the generated description
- Format it with the comment syntax of the current snippet language
- Insert it into the snippet above the selected code or at the top

### Alternative solutions

Right-click a selected code region and choose **Alternative solution** to:

- Request multiple alternative implementations (up to the configured limit)
- Add a 3-line field for additional instructions
- Reload and regenerate new alternatives
- Zoom an individual preview to the full dialog area
- Apply exactly the originally selected code when ready

### PlantUML diagrams

PlantUML diagrams are stored with the snippet. If the snippet content changes after diagram generation, KorTTY marks the diagram as possibly outdated and offers regeneration.

- **Rendering:** Local only — KorTTY uses a checksum-verified PlantUML JAR and Graphviz `dot`; no remote server is used.
- **Dialog features:** Rendered image, scaling without distortion, zoom/fit, SVG/PNG export, clipboard copy, and the shared [Diagram appearance](#diagram-appearance) controls.
- **Dependency errors:** If local rendering is unavailable, KorTTY shows the error so Java/Graphviz can be fixed.

### Diagram appearance

Both diagram windows — the standalone **Diagram** dialog and the **Full code analysis** flow diagram — share two appearance controls, and each remembers its setting across sessions:

- **Dark mode** — A **Dark mode** button with three choices:
    - **Auto** — follows the operating system's light/dark appearance. When you switch the OS to dark mode the diagram follows on the next render (and when the window regains focus).
    - **Light** — always light.
    - **Dark** — always dark.

    A manual choice is permanent until you change it. Dark mode recolours the **whole** diagram — a dark canvas, darkened node cards with light text, and light connectors and labels — not just the page margin.
- **Background** — A colour picker for the page/canvas colour in light mode. It applies to the diagram itself and to any exported SVG/PNG. The picker is disabled while dark mode is active, because dark mode drives the appearance.

## Placeholder variables

Snippets can contain placeholder variables that are replaced when you insert the snippet.

### Built-in variables

These variables are automatically replaced:

| Variable | Replacement |
|----------|-------------|
| `${date}` | Current date in `YYYY-MM-DD` format |
| `${time}` | Current time in `HH:MM:SS` format |
| `${datetime}` | Current date and time in `YYYY-MM-DD HH:MM:SS` format |
| `${hostname}` | Local machine hostname |
| `${username}` | Current system username |
| `${clipboard}` | Current clipboard content |
| `${cursor}` | Cursor position (removed from text; position returned) |

### Custom variables

Any `${variableName}` not in the built-in list is treated as a custom variable. When you insert the snippet:

- KorTTY checks the Variable Manager for stored values
- Variables without stored values prompt for input

## Sending snippets to the terminal

The Snippet Manager can send a selected snippet directly to the active terminal.

### Send to Terminal

- Keeps existing behavior
- Supported script languages are embedded as a terminal one-liner where possible
- Other snippets use the existing fallback path

### Send to Terminal with Parameters

- Opens a dialog for missing `${...}` placeholder variables and script arguments
- Script arguments are entered one per line; empty lines are ignored
- If you confirm without script arguments, the result is the same as **Send to Terminal**, but missing placeholder variables can still be filled in

### Script arguments

Supported for Bash/shell, Python, Perl, and Ruby snippets:

- Arguments are passed individually and shell-quoted
- Not appended as raw shell text
- If arguments are entered for unsupported languages, KorTTY shows an information message and sends nothing

### Terminal display

For embedded/base64 one-liners, the terminal shows the `KorTTY snippet: ...` label instead of echoing the full generated command.

## Import and export

Snippets can be imported and exported in multiple formats.

### Data format exports

Use **Export** to save selected snippets, or all snippets when nothing is selected. Use **Import** to merge snippets from a file.

| Format | Extension | Use case |
|--------|-----------|----------|
| JSON | `.json` | Data interchange, programmatic access |
| XML | `.xml` | Structured data, tool integration |
| YAML | `.yaml` | Human-readable, configuration-friendly |

### Script-focused exports

For script-specific exports, choose:

#### Plain text script files

- Opens a target-folder chooser
- Writes one file per snippet
- Filename comes from the snippet's **Name** column, including extension
- Unsafe path characters are sanitized
- Duplicate names receive a suffix such as `script (2).sh`

#### ZIP script archive

- Writes one ZIP containing one script file per snippet
- Keep the extension from the **Name** column or force one extension for all files
- Supported forced extensions: `.sh`, `.py`, `.pl`, `.rb`, `.ps1`, `.sql`, `.txt`, or custom

#### ZIP encryption options

- **Unencrypted** — Standard ZIP archive
- **AES password-protected** — Password-encrypted with AES-256
- **GPG-encrypted** — Creates a `.zip.gpg` file; requires local `gpg` command and a usable public key

!!! tip
    Select two snippets, export them as plain text and confirm the created files use the names from the **Name** column. Then export the same selection as a ZIP with a forced `.txt` extension and verify all ZIP entries use `.txt`. For password export, confirm the ZIP requires the password before extraction. For GPG export, decrypt the `.zip.gpg` with your local GPG setup and inspect the ZIP entries.
