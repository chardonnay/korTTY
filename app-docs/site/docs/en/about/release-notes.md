# Release notes

The full, version-by-version changelog. The version this guide was built for is
shown in the footer.

## Unreleased

### Fixes

- **Snippet-editor diagrams render when Graphviz is installed outside the app's
  PATH** — generating a **Diagram** in the snippet editor no longer fails with
  "Graphviz dot is required to render PlantUML diagrams" when `dot` is installed
  (e.g. via Homebrew) but not on the minimal PATH a desktop-launched app inherits
  from launchd. korTTY now locates `dot` and the Java runtime the same way it
  finds AI CLIs — searching the PATH plus common install directories
  (`/opt/homebrew/bin`, `/usr/local/bin`, …) — and passes the resolved `dot` path
  to the PlantUML renderer via `GRAPHVIZ_DOT` so it does not have to rediscover it.

## v2.3.1

### Fixes

- **"Load as text file" follows `cd` in local shells** — in a local-shell tab,
  loading a selected file with **Load as text file** after changing directory no
  longer fails to find the file. korTTY now reads the shell's live working
  directory straight from the operating system (the shell process's current
  directory) instead of relying only on the prompt text, which does not reveal
  the full path when the prompt shows just the folder name (the macOS zsh
  default). On macOS/Linux this resolves the selection against the directory the
  shell is actually in; on Windows it falls back to the previous prompt-based
  behavior.
- **Snippet-editor AI functions work with reasoning models and chatty
  responses** — AI actions in the snippet editor (**Diagram**, **Review**,
  **Improve**, **Assistant**, **Security**, **Alternatives**, **Describe**,
  **Complete**, **One-liner**) no longer fail — e.g. *"PlantUML generation
  failed"* — when the model wraps its JSON answer in prose or a code fence, or
  when a local reasoning model (LM Studio, Ollama, llama.cpp serving
  DeepSeek-R1/QwQ/gpt-oss) emits a `<think>…</think>` block. The response parser
  now strips leaked reasoning and extracts the real JSON payload robustly instead
  of a greedy match that broke on any stray brace.
- **Snippet-editor AI errors are now visible** — when a snippet-editor AI action
  fails, the real cause is written to the log and its message is shown in the
  status bar. Previously the exception was discarded, so a misconfigured AI
  profile (e.g. a cloud profile with no model selected, which reports *"Select a
  model…"*) made every AI function fail silently with only a generic message and
  nothing in the log.

## v2.3.0

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

- **Five new app designs** — *Amber CRT* (warm amber-phosphor retro terminal),
  *Synthwave '84* (80s Outrun neon), *Gruvbox Retro* (cozy warm earthy),
  *Nord Arctic* (calm, flat arctic blue-grey) and *Dracula* (soft purple/pink)
  join the existing designs in *Settings → Appearance*, each with its own
  preview thumbnail. The existing designs are unchanged.
- **Subtle design animations toggle** — a new Appearance setting (default on)
  lets the glow designs breathe a small status-bar accent dot; turning it off
  doubles as a reduce-motion option, and the animation stops while the window
  is hidden.
- **More consistent design chrome** — a custom design's colors now apply
  deterministically across menus and dialogs, and the terminal theme's dynamic
  stylesheet no longer overrides the active design's chrome colors.
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

### Guide AI docs search

- **Ask the manual in natural language** — the built-in guide (**Help → Manual**,
  ++f1++) gets an **AI search** side panel: type a question in your language and
  get an answer generated exclusively from the bundled documentation, with
  clickable citations that jump the guide directly to the referenced page.
- **Uses your default AI profile; retrieval is fully offline** — no server, no
  extra API keys, no new dependencies. Retrieval runs locally over the bundled
  search index (with bilingual synonyms, German compound splitting and umlaut
  folding); off-topic questions are answered locally without contacting the AI
  endpoint at all.
- **Grounded answers** — the model is restricted to the retrieved excerpts,
  invented links are repaired or removed, and a native **Sources** list always
  shows the cited pages regardless of the model's answer.

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
- **"Load as text file" works in local shells** — right-clicking a selected file
  name in a local-shell tab and choosing **Load as text file** no longer fails
  with "No active SSH connection is available". The file is read from the local
  filesystem — resolved against the working directory shown in the shell prompt
  when available, otherwise the directory the shell was started in — and opens
  in the snippet editor with **Overwrite local file** and **Save as...** just
  like the SSH/SFTP variant. The not-connected error message is now
  transport-neutral.
- **Local file overwrites are now atomic** — both "Overwrite local file" flows
  (local-shell **Load as text file** and the SFTP-manager local-file editor)
  used to truncate the target file in place, so a mid-write failure (disk full,
  process killed, power loss) could leave it truncated with no recovery.
  Overwrites now write to a sibling temp file and move it into place, preserve
  the original file's POSIX permissions, and write through symlinks to their
  real target instead of replacing the link itself.

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
