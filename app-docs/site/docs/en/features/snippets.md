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
- **AI Code** — Complete code, review errors, improve selections, check security, or generate diagrams.
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

### Editor context menu AI actions

- **AI Assistant…** — Opens an instruction dialog for the current cursor position. KorTTY sends the full snippet, cursor offset, line, column, and your instruction to the configured AI profile. The result is shown as a before/after preview.
- **Review errors and improvements** — Generates an informational report without changing content.
- **Improve…** — Rewrites only the selected code region.
- **Security Check** — Generates a security report. Select findings to fix; KorTTY applies them with a before/after preview.
- **Diagram** — Generates and saves a persisted PlantUML logical-structure diagram for the snippet.

!!! warning
    Snippet AI actions send the current snippet content, selection or cursor metadata, prompt instructions, and optionally enabled AI Skills to the configured default AI profile. Snippet AI actions do not enable internet tools, even when the selected profile has internet access. Auto-completion can send the snippet repeatedly while active, so disable it for sensitive snippets unless you trust the configured endpoint.

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
- **Dialog features:** Rendered image, scaling without distortion, zoom/fit, SVG/PNG export, and clipboard copy.
- **Dependency errors:** If local rendering is unavailable, KorTTY shows the error so Java/Graphviz can be fixed.

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
