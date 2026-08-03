---
title: Session journal
---

# Session journal

The session journal documents a terminal session as a readable timeline: every server output line and every command you type is written to a capture log, an AI periodically condenses the activity into short journal entries, and the result is rendered as a self-contained HTML page with connection details, color-coded excerpts, screenshots and your own notes. Journals are managed like saved chats — in their own manager window with search, editing and export.

![Session journal flow](../assets/diagrams/session-journal-flow.svg)

## Storage and formats

Each journal is one self-contained directory under `~/.kortty/journals` (configurable in **Settings > Logging > Session Journal**):

| File | Purpose |
|------|---------|
| `journal.xml` | The curated journal document: metadata, AI summaries, markers, notes, screenshot references |
| `session-log.xml` / `.json` / `.yaml` | The append-only capture log — timestamped server output and typed input lines with sequence ids |
| `session-log-2.xml.gz`, … | Rotated log parts; closed parts are gzip-compressed automatically, the journal never deletes history |
| `journal.html` | The generated timeline page, regenerated automatically after every change |
| `screenshots/*.png` | Screenshots you attached during the session |

The capture-log format is selectable in the journal manager's **Options** dialog: **XML** (default), **JSON** (JSON Lines) or **YAML**. All formats carry the same fields, and every entry is exactly one line, so a crash never corrupts more than the last line. The active log part stays uncompressed for live reads; rotation (default 25 MB per part) and session end compress finished parts to `.gz`.

## Enabling the journal

### Automatically for a connection

1. Open **Connections > Manage Connections** and edit a connection
2. On the **Journal** tab, enable **Enable session journal for this connection**
3. Optionally adjust **Capture typed input lines**, **Generate AI summaries** and the per-connection **Summary interval**

Every future connection of this server then starts its journal automatically. The journal survives reconnects — one journal per tab lifetime, with a reconnect marker in the log.

### Retroactively for a running session

Use **Tools > Start/Stop Session Journal** (++ctrl+alt+t++), the tab context menu (**Session Journal > Start journal**) or the journal bar's **Start journal** button. The existing scrollback is imported into the journal as seed entries first, so the timeline covers what already happened, then live capture attaches.

### The journal bar

While a journal is available, a bar below the terminal shows its state (**Journal active since HH:MM**) and offers **Stop journal**, **Screenshot** and **Note**:

- **Screenshot** (++ctrl+alt+c++, also in the terminal's right-click menu) snapshots the terminal — in a split layout, the right-click menu captures exactly the pane under the cursor — and files it into the journal timeline.
- **Note** opens a small input for a free-text remark that appears as its own timeline entry at the current position.

## AI summaries

While the journal runs, the AI summarizer periodically reads the newest capture-log lines and appends a compact journal entry (title, summary and a suggested marker: info, important or error). Defaults and limits:

| Option | Where | Default |
|--------|-------|---------|
| Summary interval | **Settings > Logging > Session Journal** (global) or per connection | 5 minutes |
| Max. terminal lines per AI evaluation | Journal manager **Options** | 100 |
| Token budget for context fill | Journal manager **Options**, visible when max lines is 0 | 130000 |
| Split backlog into multiple prompts (chunking) | Journal manager **Options** | off |
| AI profile for summaries | Journal manager **Options** or **Settings > Logging > Session Journal** | Default profile |

Summaries use your **default AI profile** unless you pick a dedicated journal profile — either in the journal manager's **Options** dialog or under **Settings > Logging > Session Journal**. The Text/Coding role profiles are deliberately not used for the journal.

Setting **max lines to 0** switches to context filling: the summarizer packs as many of the newest lines as fit into the configured token budget. With **chunking** enabled the whole backlog is processed instead of only the newest window — consecutive windows of the configured size, one AI prompt each.

!!! warning
    Chunking can take very long on large sessions and is not recommended for everyday use — it is intended for power users with capable hardware and a powerful LLM.

When the session ends, the summarizer writes a closing **session summary** entry (what was accomplished, which errors occurred). Optionally — **Let the AI title the journal when the session ends** in the Options dialog — a final AI call names the journal, unless you renamed it manually.

!!! note
    The journal works without AI: if no AI profile is available, AI features are disabled, or summaries are switched off, the timeline records raw activity entries instead. AI summary prompts never use internet-access tools; the terminal excerpt goes only to the configured AI profile.

## Password protection

Typed input is captured only as complete submitted lines, and several layers keep passwords out of the journal:

- When the server output ends in a password prompt (`password:`, `[sudo] password for …`, `passphrase`, `PIN`, and localized variants), the next submitted input line is suppressed and logged as a redacted placeholder — the typed text is never buffered or written.
- The connection's own stored password is additionally replaced by `***` wherever it would appear in captured text.
- **Capture typed input lines** can be disabled per connection entirely; commands then only appear as the server's echo in the output stream.

!!! warning
    The prompt detection is a heuristic — a remote terminal cannot reliably know when the server disabled echo. Exotic or full-screen password prompts may not be recognized, and secrets pasted into visible commands (other than the connection's own credentials) are captured like any other text. Treat journals of sensitive sessions accordingly.

## The journal page

`journal.html` is fully self-contained (no external resources) and works in the built-in viewer, in any browser, and inside the exported bundle:

- A sticky header shows who was connected to which server, start time, duration, and counts for entries, commands, errors and screenshots, plus the journal description. Live journals show a **live** badge.
- The timeline groups entries by day; each entry carries its time, a colored marker dot/badge (red = error, amber = important, blue = info), the AI title and summary, and color-coded input (green) and output (blue) excerpts.
- Clicking an entry slides a log panel in from the bottom with the exact capture-log range behind that entry. The panel has its own scrollbar, a search field with a match counter and ▲/▼ navigation (++enter++ / ++shift+enter++ also cycle matches, ++esc++ closes), and colors input and output lines differently.
- Screenshot entries show thumbnails; clicking one opens a full-size lightbox.
- The page renders dark by default, follows the system light/dark preference and has its own theme toggle.

## Managing journals

**Tools > Session Journals…** (++ctrl+alt+j++) opens the journal manager: all journals in a table sorted by start time (newest first) with duration, connection, server, title and entry count. Running journals are marked and cannot be renamed or deleted while live.

- The filter field matches title, connection, host, user and description; enabling **Search contents** additionally scans the journal entries and capture logs of every journal in the background.
- **Open** (or double-click) opens the journal viewer; **Rename** changes the title; **Delete** asks for confirmation and then permanently removes the journal folder including the log and all screenshots.
- The **Description** area below the table stores a free-text description per journal; it appears on the journal page and in every export and is included in the content search.
- **Options** holds the global capture and AI settings described above.

### The viewer and editing

The viewer shows the journal page in an embedded browser and refreshes automatically while the journal is still being written. **Open in browser** hands the page to your system browser. **Edit** splits the view: an entry table with a marker choice (**None / Info / Important / Error**) and a notes field lets you categorize entries — for example flag failures or highlight important findings. Saving regenerates the page at the edited entry's position; a marker you set manually is never overwritten by the AI.

## Exporting

The **Export** menu in the manager and the viewer offers three formats:

| Format | Content |
|--------|---------|
| **PDF** | The simple journal: header with connection details and statistics, day-grouped entries with marker badges, input/output excerpts, notes — and, if selected, downscaled embedded screenshots |
| **Markdown** | The same simple journal as a `.md` file; screenshots are copied into a sibling `<name>-files/` folder |
| **HTML bundle (complete)** | A zip archive of the whole journal — `journal.html`, `journal.xml`, the decompressed capture logs and all screenshots — laid out so the page works immediately after unzipping |

PDF and Markdown ask whether screenshots should be included.

## Enterprise policy

Administrators can deny the feature (`session-journal` under `[rule.features]`), or mandate its behavior via `[rule.session-journal]`: force a journal for every connection, fix the log format, AI line window or storage directory, forbid renaming or deleting journals, prescribe a naming template and enforce the closing AI title. See [Enterprise policy](../reference/enterprise-policy.md) for the keys.
