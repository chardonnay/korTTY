# Release notes

The full, version-by-version changelog. The version this guide was built for is
shown in the footer.

## Unreleased

### AI Swarm

- **AI Swarm tab** (**AI → AI Swarm...**, ++ctrl+alt+s++ / Cmd on macOS) — broadcast
  one AI-agent task to many servers at once; each server runs its own agent and
  the answers are combined into a single comparison table with one row per
  server and a literal **"Fehler"** column for deviations and errors.
- **Animated status strip** — one orb per agent above the conversation shows
  queued/running/awaiting-input/paused/done/failed/cancelled at a glance,
  flags *unusually long* runs via an adaptive threshold
  (`max(60 s, 2 × median of finished agents)`), and clicking an orb jumps to the
  agent's row. The strip scales from 1 to 50+ servers.
- **Per-agent and swarm-wide run control** — pause, resume, restart, and stop
  either a single agent (right-click its row) or the whole swarm (toolbar).
  Pausing is cooperative and stops the elapsed timers; restarts replace only
  that agent's answer.
- **Expandable live transcripts** — left-click an agent row to watch its live
  command/output transcript inline while it runs.
- **Conversation copy & export** — copy the whole swarm conversation to the
  clipboard or export it as plain text, Markdown, or PDF; saved swarm chats get
  their own **Swarm Chats** section in the AI Manager.
- **Readable result rows** — clicking a row of the combined answer table opens
  it in a separate **Row details** window with A−/A+ font sizing and
  copy-to-clipboard.
- **Targets without open terminals** — swarm runs (AI and script) now work on
  saved servers with no open terminal via background SSH sessions; no terminal
  tab is opened. Requires an unlocked master-password vault.
- **Run scripts without AI** — execute a Snippet Manager script with parameters
  on all swarm targets in parallel (Base64-transferred, single confirmation),
  with live output per server and a per-server exit-code/output result table.
- **Schedule swarm runs** — a new JobScheduler action type **AI_SWARM** with
  **Swarm parallelism** (1–16) and **Swarm read-only** fields; the swarm tab's
  **Schedule…** button prefills a job from the current targets and prompt.
  Results go to the journal *and* a saved swarm chat.
- **Visible composer and tab status dot** — the swarm input is a clearly framed
  three-line field, and the tab shows a colored activity dot (running / waiting
  for input / paused / finished — the green dot stays until the next run).
- **Multi-server workflow dialog reworked** — syntax-highlighted script view, a
  visible working animation with live elapsed time and total duration, an
  additional-instructions field with a de-duplicated 10-entry history, and
  **Save to Snippets** with a fitting pre-filled script name.

### Appearance

- **App design `Normal` renamed to `Default`** in *Settings → Appearance*. The stored value is unchanged, so existing configurations keep their selected design.
- **Previous/next buttons next to the App Design dropdown** let you step backward and forward through the designs (wrapping around at the ends) without opening the dropdown.
- **Design preview moved below the controls** into a fixed-size area, so switching designs (or back to `Default`, which has no preview) no longer draws the preview over the dropdown.

### Local Shell connections

- **Open the local machine's shell in a terminal tab (no network)** — a new
  **Local Shell** protocol spawns a local pseudo-terminal (PTY) via pty4j instead
  of connecting to a remote host. On Windows you can choose **PowerShell**
  (default) or **cmd.exe**; on macOS/Linux it defaults to your `$SHELL` (falling
  back to `/bin/zsh` or `/bin/bash`). A free-form **Custom command** field accepts
  any executable with arguments (e.g. `pwsh.exe`, `wsl.exe -d Ubuntu`, Git Bash),
  and an optional start directory can be set. Local Shell is selectable in both
  Quick Connect and the Connection Manager; for these connections
  host/port/username/authentication are not required and are disabled in the
  dialogs.
- **Git Bash / Cygwin / WSL presets** on Windows — each offered only when actually
  installed (Git Bash/Cygwin via their usual install locations / `PATH`; WSL only
  when `wsl.exe` is present and at least one distribution is installed). The
  command parser is quote-aware, so shell paths containing spaces (like
  `"C:\Program Files\Git\bin\bash.exe"`) launch correctly.
- **Shared connector hooks** — terminal recording/logging and the AI input/data
  hooks were lifted onto a shared `ObservableTtyConnector` interface, so they also
  work for local shells. SSH-channel-only features stay SSH-only.
- **AI Agent & Planning in local shells** — the agent's command-execution engine
  was decoupled from SSH behind an `AgentCommandRunner` abstraction (SSH exec
  channel and local process backends). The **AI Agent** and **AI Planning** now
  run in local shells on Windows, macOS and Linux: commands execute in the
  connection's shell (PowerShell via `-EncodedCommand`, `cmd.exe`, or `$SHELL`),
  the environment probe and system prompt are platform-aware, and the existing
  approval flow applies. Limitations on local shells: no `sudo`/administrator
  elevation on Windows, and no live working-directory tracking. The JobScheduler's
  headless AI-agent action stays SSH-only.

### Terminal usability

- **Ctrl + mouse-wheel zoom** — holding **Ctrl** (or **Cmd** on macOS) and
  scrolling the mouse wheel over the terminal now changes the font size instead of
  scrolling the buffer. This complements the existing Alt+Plus / Alt+Minus / Alt+0
  shortcuts.
- **Ctrl+D closes a local cmd.exe/PowerShell tab** — those Windows shells do not
  exit on EOF, so Ctrl+D had no effect there. For bash-family shells
  (Git Bash/Cygwin/WSL, macOS/Linux) and SSH, Ctrl+D keeps its normal EOF meaning.

### AI chat & agent

- **Images, diagrams, and math render in AI chats** — AI answers containing an
  SVG document, a base64 raster image (`data:image/png;base64,…` — PNG, JPEG,
  GIF, BMP), a ` ```plantuml ` block, a ` ```mermaid ` block, or LaTeX math
  (` ```latex `/` ```tex `/` ```math ` blocks and `$$…$$` in prose) are shown as
  images instead of raw markup, each with a **Show code / Show image** toggle and
  copy button. Mermaid and MathJax are bundled (no network); PlantUML uses the
  local toolchain (`java` + Graphviz `dot`); SVG output is sanitized and shown
  with JavaScript disabled.
- **Full model reasoning in agent thinking rows** — expanding a 💭 row in the
  agent activity panel now shows the model's actual reasoning when the provider
  exposes it (Anthropic extended thinking per the profile's Reasoning effort,
  OpenAI-compatible `reasoning_content`, LM Studio reasoning output, `<think>`
  blocks from local CLI models), falling back to the decision summary otherwise.
- **Run log records the AI profile** — every agent run starts with an
  `AI profile: <name> (<model>)` activity row.
- **Reload uses the currently active profile** — the activity panel's reload
  button re-runs the command with the profile that is active now, not the one the
  original run was started with.
- **Agent Ask includes the terminal selection** — starting **AI → Ask AI Agent**
  from the right-click menu with text selected sends the selection as context, so
  the question is answered about the selected output or script.
- **Concrete models for cloud profiles** — the model selector is pre-filled with
  common model names for known cloud providers (offline, no API key needed), the
  refresh button merges the endpoint's live model list, clicking a model in the
  dropdown now reliably applies it, and the unusable **Auto** option is no longer
  offered for cloud endpoints (with a clearer error when no model is selected).

### Workflow Script Generator

- **Two new target languages** — the agent run → **Workflow** script generator can
  now produce **Windows-CMD** (`.cmd` batch) and **AppleScript** (`.applescript`)
  in addition to Bash, Python, Perl, Ruby, PowerShell and Ansible.
- **Adjustable script font size** — each generated-script editor has **A−** /
  **A+** buttons and supports **Ctrl + mouse wheel** (Cmd on macOS); the chosen
  size is remembered across sessions.
- **Visible progress while a diagram is generated** — generating a PlantUML
  diagram from a script now shows the working spinner.
- **Clearer AI backend errors** — out-of-memory / resource-limit errors from the
  AI server (e.g. LM Studio/MLX "Resource limit exceeded", "metal::malloc") show a
  short, actionable hint instead of the raw stack trace; all other AI errors are
  collapsed to a single line.

### Fixes

- **Closing a local shell no longer freezes korTTY** — the PTY process is now
  destroyed before its streams are closed, releasing a terminal reader thread
  blocked in a pty `read()` instead of deadlocking the close on the JavaFX thread.
- **Correct wording for local shells when closing** — the close-confirmation no
  longer says "End SSH connection?" for a local shell, and the window-close prompt
  is now transport-neutral ("Active sessions").
- **No password prompt for local shells** — opening a local shell no longer shows
  an irrelevant password dialog (local shells use no authentication).

## v2.2.3

### Critical fix: Monaco editors failed to load in the packaged app

- **Fixed the Monaco-based editors (snippet, file, AI, diff) opening to an empty
  pane in the packaged/notarized macOS app** — no caret, no typing, no paste. In
  the packaged app the WebView loaded its page from a `jar:` URL, and the page's
  Content-Security-Policy (`script-src 'self'`) then blocked the editor's own
  `monaco-host.js`/`.css`, because a `jar:`-origin document does not authorize its
  `jar:` siblings. The Monaco resources are now extracted to a temp directory and
  loaded from a `file:` URL, which the CSP allows. A failed editor load now also
  surfaces an error instead of a silently empty pane, and the editor bundle is
  additionally minified with a more generous boot budget.

## v2.2.2

### Critical fix: crash opening Monaco editors

- **Fixed a hard crash (no on-screen error) when opening the Snippet Manager, the
  Snippet editor, or the Settings AI-skill editor in packaged builds**: the
  bundled runtime was missing the `jdk.jsobject` module, so
  `netscape.javascript.JSObject` was unavailable at runtime and the JVM crashed
  in JNI `get_method_id` (`SIGSEGV`). `jdk.jsobject` is now bundled in the
  packaged runtime. This release supersedes v2.2.0 and v2.2.1, whose binaries are
  affected by this crash.

## v2.2.1

### Stability fixes

- **Settings / Snippet Manager crash fixed**: opening **Global Settings** or the
  **Snippet Manager** could abort the app. The embedded Monaco editor's
  JavaScript→Java bridge is now held by a strong reference for the editor's
  lifetime.
- **WebView lifecycle hardening**: Monaco editors are disposed when their dialog
  closes; late timer/load callbacks after close are ignored. The Settings *AI
  Skills* editor loads lazily on first use.

### Master-password login window

- **Full-bleed animated logo** in the standard app design, with the password form
  overlaid in a translucent card.

## v2.2.0

### Terminal engine and hyperlinks

- **SithTermFX 1.2.0** terminal engine (built from source).
- **OSC 8 clickable hyperlinks** — links emitted by programs such as
  `ls --hyperlink` or `eza`, restricted to a safe URI-scheme allowlist.

### Mosh (mosh4j) 2.0.2 upgrade & security hardening

- mosh4j `2.0.0 → 2.0.2` with per-direction replay/freshness protection and
  decompression-bomb limits; release JARs bundled in native builds.
- Bouncy Castle `1.78.1 → 1.84` (fixes CVE-2026-5598 HIGH and CVE-2026-0636
  MODERATE); protobuf-java `4.28.2 → 4.35.1`.

### AI agent panel & activity

- **AI Agent Panel placement**: *At Bottom* (default), *Dock Left*, or *Dock
  Right*, remembered across restarts.
- **Multiple concurrent runs per split** (cap 5), per-run pause/resume, and
  Dashboard / tab status badges (✋ awaiting · ⚡ working · ⏸ paused · ✓ finished).

!!! note
    Older releases are recorded in the repository's `app-docs/RELEASE_NOTES.adoc`
    and will be migrated here in full.
