# Release notes

The full, version-by-version changelog. The version this guide was built for is shown in the footer.

## v2.4.3

### Packaging and documentation

- **macOS Intel release packages** — the release workflow now builds separate signed and notarized `-x86_64` ZIP and DMG assets on GitHub's Intel macOS runner alongside the existing Apple Silicon packages, and verifies the JDK, `jpackage`, runner and launcher architectures before publishing them.
- **Complete portable Windows archives** — every Windows ZIP now contains the full self-contained `jpackage` application image with its launcher, libraries and bundled runtime. The former Windows-ARM job actually produced x86_64 binaries and is no longer published under a false ARM label; Windows on ARM uses the validated x86_64 package through emulation until a native JavaFX packaging track exists.
- **Web formatters that need no Node.js** — The standalone browser build of Prettier uses only the required Babel/Estree/TypeScript/HTML/PostCSS plugins, and the browser build of sql-formatter handles SQL; both now run in a lazy, isolated JavaFX WebView. Provider labels, line-width handling, timeout recovery and optional PATH fallbacks remain available, while the roughly 114 MiB Node.js runtime is no longer installed.
- **One Monaco payload for editor and diff** — both pages now share one mode-aware IIFE and CSS bundle while keeping all five Monaco Web Workers and the existing language services, cutting Monaco's compressed application-JAR footprint roughly in half without removing completion or diagnostics.
- **Clean target-specific native packages** — final package staging removes stale dependency versions, old formatter trees and foreign Mosh architectures; Mosh reuses the application's Bouncy Castle, JNA/pty4j retain only target natives, unused JavaFX/JDK modules and SithTermFX test dependencies are excluded, and the logo animation is stored as a compact 640×360 H.264 video.
- **Installer size gates** — release CI emits JSON/Markdown component reports, rejects incomplete app images and mislabeled Windows binaries, requires at least 15% reduction against the committed package baseline, applies the app/DMG limits, and freezes verified sizes with 2% regression tolerance. `RPM` is measured after signing.
- **One local Mermaid renderer for every diagram** — snippet diagrams, full code analysis, workflow generation, exports, and AI-chat Mermaid blocks now share the SHA-256-pinned Mermaid 11.16.0 browser renderer. Snippet flows retain SVG/PNG, clipboard, zoom, theme/background, regeneration, and node-to-code hover references without a Java/Graphviz subprocess or first-use download. Legacy saved diagram payloads are discarded without removing snippets or chats, and the retired renderer cache is deleted safely on upgrade.
- **Detailed local release-build guide** — a new guide chapter explains the exact common and platform-specific prerequisites, portable self-contained application images, ZIP/TAR archives, DMG/MSI/DEB/RPM installers, Intel Mac builds, signing, notarization, verification and troubleshooting for macOS, Windows and Linux.

### AI Code Analysis

- **One request for analysis and diagram** — opening **Full code analysis** or clicking **Re-run** now sends one combined AI request for the report and its initial Mermaid flowchart instead of sending the snippet twice. An invalid or missing Mermaid result falls back locally without a hidden retry; **Regenerate** remains an explicit diagram-only request and uses the active analysis profile.
- **Bulk selection no longer changes dependencies** — the clearly labelled **Select all improvements** control now toggles only Security, Optimization and Design suggestions. Every dependency keeps its independent selected or unselected state.
- **Pick the AI skills for an analysis** — the **Full code analysis** window now shows which AI Skills were included, as chips with an *(auto-selected)* or *(manual)* badge, and lets you change them with a **searchable** picker (filter by name, description or tags). Your changes take effect on the next **Re-run**, so one deliberate click runs one analysis with exactly the skills you chose. korTTY also auto-selects the skills relevant to the snippet before the first analysis so the set is meaningful out of the box.
- **Hardening options show a count and remember their state** — the *Hardening options* panel title now shows how many options are ticked (e.g. *Hardening options (11)*), and the window remembers whether you left the panel open or closed.
- **Add a script header on apply** — a **Script header** selector lets you prepend one of your saved *Script-Header* snippets to the code when you apply the analysis; a header on its own is inserted without an AI round-trip.
- **Export the report** — a new **Export** button saves the whole report — summary, categorized improvements, dependencies and the flow diagram — as a **PDF**, a self-contained **HTML** page, or **Markdown**, in an attractive print-friendly design that records the profile and included skills.
- **Which profile is in use** — the window now shows the name of the AI profile that produced the analysis (the default profile's real name, not just "Default profile").
- **Clearer sections** — each improvement section (Security / Optimization / Design / Dependencies) now carries a colour-coded icon, and the summary block no longer shows a selection accent bar it never needed.
- **Clearer change reviews** — in the *review changes* window, the "Why these parts changed" cards now show each finding's colour-coded category icon and the line range it affects (for example *Lines 23–40*); the on-hover explanations in the diff are more reliable — matching tolerates re-indented or case-shifted lines, and reasons whose anchor cannot be found are attached to the remaining changed blocks instead of silently disappearing.

### File browser

- **Clearer context-menu label** — right-clicking a file in the local **file browser** (View menu) now offers **Open in Snippet Editor** instead of the misleading *Load as text file* (the action already opened the snippet editor), matching the terminal context menu.

### Fixes

- **No crash when a snippet fails to save** — saving a snippet as a new entry could, if the save failed, crash with a null-pointer error while trying to show the failure message. The error is now reported cleanly.
- **"Save as new snippet" no longer shows a false "already exists" error** — after editing a snippet (for example applying analysis improvements) and saving it under a new name with **Save as new snippet**, korTTY wrongly popped up *"Snippet name already exists"* even though it had just saved the snippet correctly. The dialog now delivers its save exactly once.

## v2.4.2

### Branding

- **New app icon & logo** — the program icon (macOS `.icns`, Windows `.ico`, and the 1024² master PNG) has been redesigned: a neon `>` chevron and prompt cursor with a purple→green brain network, without the "korTTY" wordmark (it cluttered small dock/taskbar sizes). The in-app logo shown in the master-password dialog and the About box was refreshed to match, with the "AI-Driven Terminal Experience" subtitle.

### Quick Connect

- **Collapsible option sections** — the optional settings (Connection Timeout, Terminal Appearance, Terminal Effect, AI) are grouped into collapsible sections, so the dialog opens compact; Connection Timeout now opens with fixed defaults (10 s / 0 retries). The form scrolls inside the dialog when expanded sections exceed the screen, and the dialog **remembers which sections you left expanded** across restarts.
- **Connection-skills picker** — pre-select AI skills for the new connection: a glob-aware search (`*` wildcards), All/Clear toggles, and a **Save** button that persists the current selection as the default for every new connection.

### Terminal

- **Scrollback setting now works** — the **Scrollback** value under **Configuration → Global Settings → Terminal** (100–100,000 lines) is now actually applied to the terminal buffer; it was previously ignored and every pane used a fixed 10,000 lines. The value is read when a terminal is created, so it applies to newly opened tabs and split panes.
- **Local shells open in your home directory** — a new local shell (macOS/Linux) no longer starts in `/` when korTTY is launched from the Finder/Dock; it now correctly starts in your home directory.

### Snippet AI

- **Degenerate AI replies no longer wipe your code** — applying AI improvements or a security fix to a snippet could, with a weak/local model and an active AI skill, replace the whole snippet with a bare placeholder (literally `$code`) returned by the model. This is now detected and rejected — you get *"AI reply was not a valid snippet — code left unchanged"* instead of losing your code — and the AI prompt itself was hardened so a skill's instructions can no longer talk the model into returning a placeholder instead of real source.
- **Diagram dark mode covers every colour** — in the AI Code Analysis flow diagram's dark mode, all light node colours are now darkened to a matching dark tint, not just the three most common ones, so the light node text stays readable regardless of which colour a node was given.

### Miscellaneous

- **AI Manager remembers its window** — the AI Manager dialog now reopens at the size and position you last left it at, instead of resetting every time.

### Performance & footprint

- **Much lower memory use** — AI chat tabs, file editors and the snippet AI windows now release their embedded browser engines (Monaco/WebView) when they are closed, when chat messages are re-rendered after a font-size change, and when solutions or scripts are regenerated; closing a split pane now also frees its scrollback buffer and timers. Long sessions with many chats, editors and splits no longer accumulate memory.
- **Bounded memory footprint** — the packaged application now runs with a 2 GB Java heap ceiling and periodically returns unused memory to the operating system when idle.
- **Opt-in resource profile** — a new **Configuration → Global Settings → Resources** tab lets you trade that low footprint for more of your machine's resources when you need it: **High** raises the heap to about half your RAM, and **Maximum** goes to about three quarters with the low-pause Z garbage collector. The default (**Balanced**) is unchanged. See [Resources](../reference/settings/resources.md).
- **Smaller downloads and installs** — the bundled formatter runtime, Monaco resources, native dependency JARs and embedded Java runtime were trimmed and compressed, the logo video was reduced, the macOS disk image uses stronger LZMA compression, and the offline guide no longer ships developer sourcemaps or unused search components. The measured Apple-Silicon app image is about 165 MiB instead of 312 MiB, and its DMG about 129 MiB instead of 180 MiB.
- **Date and number formats** — the installed application bundles Java locale data only for the 8 supported interface languages; on operating-system locales outside this list, dates and numbers are formatted using English conventions.

## v2.4.1

### Snippet AI code analysis

- **Full code analysis** — the snippet editor's *AI Code → Full code analysis* opens a rich, non-modal window with a plain-language summary of what the script does, its external dependencies (each with a reduce/replace suggestion), improvement suggestions grouped into Security / Optimization / Design that you tick and apply, and an auto-generated flow diagram. The diagram carries the full toolbar (zoom, fit, save SVG/PNG, copy image/PlantUML, background colour, regenerate) and highlights the matching source lines when you hover a node.
- **File names in title bars** — the snippet editor and the analysis window now show the script's file name in their title bars.
- **Per-run AI profile & re-run** — the analysis and other AI-code windows let you pick a transient AI profile for the next run and re-run with it; font sizes are remembered per window.
- **AI skills picker** — relevant AI Skills are pre-selected automatically and can be pinned so they apply to every AI-code action.
- **Hardening options** — *Improve robustness*, *Custom improvement*, *Full code analysis*, and both workflow-script generators can bake a chosen set of production-quality techniques (strict mode, error traps, meaningful exit codes, logging, idempotency, dry-run, `--help`, and more) into the result. See the new [Hardening options](../reference/hardening-options.md) reference for what each option means and how it is applied.
- **Diagram Dark mode** — both diagram windows (Full code analysis and the standalone Diagram dialog) gained a **Dark mode** button with *Auto* (follow the operating-system appearance), *Light* and *Dark*. The choice is remembered and recolours the whole diagram — dark canvas, darkened node cards with light text, and light connectors — while the manual background-colour picker now colours the diagram page itself (not just its margin) and applies to exported SVG/PNG.
- **Windows no longer lock the main window** — the snippet **Diff** and **Manage variables** windows opened from the Snippet Manager no longer freeze the main KorTTY window while they are open.
- **Hardening options — All / Clear / Save** — every hardening-options panel gained **All** (tick everything), **Clear** (untick everything) and **Save** buttons. Save remembers your selection permanently, so every hardening panel then opens with your preferred options instead of the all-on default.

### Snippet Security Check

- **Explained security fixes** — the **Review security fixes** window now renders the original and corrected snippet in a side-by-side diff that highlights changed lines automatically. Hovering a changed block names the finding(s) it addresses (for example `S1`, or `S1 + S2` when a block covers two findings) and shows the reason(s), and every reason is also listed as a card below the diff so the rationale stays visible.
- **Dedicated security-check AI profile** — you can pick a separate AI profile just for security checks and KorTTY remembers it permanently. Set it in the Security Check window or under **Configuration → Global Settings → AI**; both places share the same setting, and leaving it empty reuses the default profile.
- **Security Check window improvements** — adjustable (and remembered) font size, a copy-to-clipboard button for all findings, colour-coded severity badges with most-severe-first sorting, a select-all toggle, and a **Re-run check** button that repeats the review with the selected profile.
- **Remembered diff zoom** — the font size in the AI diff / review windows is now stored globally instead of only for the current session.

### Terminal

- **Adjustable terminal background transparency** — **View → Zoom → Background Transparency** is a new slider (0–100 %) that makes the terminal background see-through to the desktop while the text stays fully opaque and sharp. The value is saved across restarts. Only the terminal area turns transparent — the title, menu and status bars stay solid. Because see-through mode uses a borderless window, turning it on or off takes effect after a restart; in that mode a lightweight custom title bar provides move, resize, minimise, maximise and close. Adjusting the level while already transparent applies live.
- **Quieter tab close** — closing a terminal tab now only asks for confirmation when there is something to lose: the tab has split panes, or a command is still running (detected from the local shell's process tree, or from the SSH shell prompt). An idle single terminal closes immediately. The per-connection *Close without confirmation* option still suppresses the prompt entirely.
- **Clearer context-menu label** — the terminal right-click action that loads a selected remote file into the snippet editor is now called **Open in Snippet Editor** (previously *Load as text file*).

## v2.4.0

### Terminal Effects

- **Ten new built-in terminal effects** — alongside MU/TH/UR 6000, a bundled effect pack now ships ten themed effects spanning cyberpunk, retro, and creepy styles: **Amber CRT '90** (90s amber-phosphor monitor with scanlines, glow, flicker, and a rolling refresh band), **Commodore Heritage** (C64 blue with loader bars), **Neon City** (glitch tears and RGB-split flickers), **Digital Rain** (faint falling matrix glyphs), **Hologram HUD** (interference bands and HUD corner brackets), **Poltergeist** (breathing vignette, static bursts, and ghostly flashes), **VHS 1987** (tracking noise, rolling distortion, and a PLAY overlay), **Synthwave Horizon** (glowing perspective grid), **Deep Space Radar** (slow radar sweep with blips), and **Typewriter Noir** (sepia paper look with per-character output pacing). Every effect respects the animation-speed setting and its description is localized in all supported languages.
- **Typewriter Noir paced output** — the **Typewriter Noir** effect types terminal output character by character for a mechanical typewriter feel; bulk output such as printing a large file bypasses the pacing so it is never slowed down.
- **Per-pane terminal effects** — terminal effects are now scoped to each individual split pane instead of the whole tab. Within one tab you can run an effect in one pane while a sibling pane shows a different effect or none, and each effect's colors and font stay confined to its own pane. Global zoom and reset still apply to the whole tab.
- **Per-pane effect menu and inheritance** — each split pane's right-click menu gains a **Terminal Effect** submenu to pick **None** or any installed effect for just that pane, plus an animation-speed slider; the choice is runtime-only and is not saved to the connection. Splitting a pane starts the new pane with the same effect and animation speed as the pane it was split from.
- **Animated effect previews in the plugin manager** — **Plugins → Terminal Effects** now shows a live animated preview of the selected effect next to the plugin list, so effects can be compared before activating them in a session. Plugins without a preview show a placeholder instead.

### AI Chat

- **Chat color profiles** — the AI chat and AI Swarm chat now have selectable color themes. Eleven built-in profiles ship: **Automatic (theme)**, which follows your active terminal theme, plus **Original**, **Paper**, **Midnight**, **Cyberpunk**, **Retrowave**, **Forest**, **Ocean**, **Terminal**, **GPT**, and **Cute**. Pick one from the color-profile dropdown in the chat toolbar or under **Settings → Appearance → Chat color profile**; the choice is saved and applied live to every open chat.
- **Full-text chat search** (++ctrl+f++, Cmd+F on macOS) — click the **Search** button in the chat toolbar or press the shortcut to open a find bar over an AI or swarm conversation. It searches the entire chat including code blocks, shows a live match count, jumps between hits with the arrow keys or Enter, and outlines and scrolls each match into view. Esc closes the bar.
- **Redesigned, fully themed chat** — the AI chat and swarm chat were redesigned so your messages sit in a right-indented rounded bubble and each AI reply is a full-width card, with code blocks, tables, the composer, and scrollbars all following the selected color profile instead of a fixed light style.

### Privacy & Analytics

- **Anonymous usage analytics (opt-in, off by default)** — korTTY can optionally share anonymous, GDPR-compliant usage statistics via Aptabase (processed on EU servers) to help prioritize features and surface crashes and frequent errors. It is strictly opt-in and off by default. Only event names, aggregate counts and flags, the app version, OS name and version, app language, and an anonymous per-launch session id are sent — hostnames, usernames, connection data, file paths, snippet/terminal/chat content, keys, passwords, and error-message text are never collected.
- **One-time consent prompt** — you are asked about sharing anonymous data exactly once: on new installs as a checkbox next to the master-password setup, and on existing installs as a one-time prompt after unlocking. Any dismissal counts as *no* and you are not asked again. Each consent surface has a **More info** button that opens the new manual chapter *Anonymous data for application optimization*.
- **Settings → Privacy tab** — a new **Privacy** tab under **Settings** lets you turn anonymous usage statistics on or off at any time and shows exactly what is and is not collected, plus the date your choice was recorded. Turning it off stops collection immediately and discards both the pending queue and any locally cached events.
- **Offline event caching** — while you are offline, anonymous events are cached locally under `~/.kortty` and sent once a connection is available; they survive app restarts, are dropped after three days, and are discarded entirely if you opt out.

### Fixes

- **Effects glow correctly when switched on for a live pane** — the MU/TH/UR effect's per-line glow now works when you enable an effect on an already-connected pane, not only when the effect was active before connecting, and it pulses steadily under fast-scrolling output instead of flickering.
- **Ctrl+D in a split pane closes only that pane** — exiting the shell (Ctrl+D or `exit`) in one pane of a split now closes just that pane and leaves the other panes open; the tab closes only when the last remaining pane's session ends.
- **Terminal effects no longer crash rendering with many tabs open** — opening several terminal tabs each with an active effect could exhaust the GPU texture pool and crash rendering. Effect overlays (including MU/TH/UR 6000) now release their full-window canvas texture while their tab is in the background and rebind automatically when the tab is shown again.
- **AI Planning recovers from truncated or malformed JSON** — the **AI Planning** tab no longer fails outright with *"AI response did not contain the required JSON object"* when a model returns an incomplete or unclosed plan, common with very small models that stop before finishing the schema. Planning now retries once with a repair prompt and usually succeeds on the second try; if it still fails, the error explains that the model returned invalid JSON twice and suggests a larger or more capable planning model.
- **Guide window no longer crashes in the background** — leaving the in-app guide (**Help → Manual**, F1) open while switching to another app could crash korTTY natively after a while, as the idle embedded browser churned in the background. korTTY now unloads the guide page after the window has been minimized or unfocused for 20 seconds and restores the same page and scroll position when you return, and the guide's intro video plays through once instead of looping endlessly.

## v2.3.3

### Fixes

- **macOS: "Quit korTTY" actually quits the app** — on the packaged macOS app, a native quit (Cmd+Q, the app menu's **Quit korTTY**, the Dock's **Quit**, or logout) left korTTY running in the background, so the process had to be killed. The packaged app deliberately keeps running after the last window closes (so the JobScheduler can run background jobs), but JavaFX only translated a native quit into "close the windows", never an actual exit. korTTY now intercepts the native quit and runs its real quit sequence. A shutdown watchdog additionally guarantees the process always terminates, the "waiting for running jobs" dialog gained a **Force quit now** button, and the menu-bar-icon cleanup no longer risks stalling shutdown.

## v2.3.2

### Fixes

- **Snippet-editor diagrams render when Graphviz is installed outside the app's PATH** — generating a **Diagram** in the snippet editor no longer fails with "Graphviz dot is required to render PlantUML diagrams" when `dot` is installed (e.g. via Homebrew) but not on the minimal PATH a desktop-launched app inherits from launchd. korTTY now locates `dot` and the Java runtime the same way it finds AI CLIs — searching the PATH plus common install directories (`/opt/homebrew/bin`, `/usr/local/bin`, …) — and passes the resolved `dot` path to the PlantUML renderer via `GRAPHVIZ_DOT` so it does not have to rediscover it.

## v2.3.1

### Fixes

- **"Load as text file" follows `cd` in local shells** — in a local-shell tab, loading a selected file with **Load as text file** after changing directory no longer fails to find the file. korTTY now reads the shell's live working directory straight from the operating system (the shell process's current directory) instead of relying only on the prompt text, which does not reveal the full path when the prompt shows just the folder name (the macOS zsh default). On macOS/Linux this resolves the selection against the directory the shell is actually in; on Windows it falls back to the previous prompt-based behavior.
- **Snippet-editor AI functions work with reasoning models and chatty responses** — AI actions in the snippet editor (**Diagram**, **Review**, **Improve**, **Assistant**, **Security**, **Alternatives**, **Describe**, **Complete**, **One-liner**) no longer fail — e.g. *"PlantUML generation failed"* — when the model wraps its JSON answer in prose or a code fence, or when a local reasoning model (LM Studio, Ollama, llama.cpp serving DeepSeek-R1/QwQ/gpt-oss) emits a `<think>…</think>` block. The response parser now strips leaked reasoning and extracts the real JSON payload robustly instead of a greedy match that broke on any stray brace.
- **Snippet-editor AI errors are now visible** — when a snippet-editor AI action fails, the real cause is written to the log and its message is shown in the status bar. Previously the exception was discarded, so a misconfigured AI profile (e.g. a cloud profile with no model selected, which reports *"Select a model…"*) made every AI function fail silently with only a generic message and nothing in the log.

## v2.3.0

### AI Swarm

- **AI Swarm tab** (**AI → AI Swarm...**, ++ctrl+alt+s++ / Cmd on macOS) — broadcast one AI-agent task to many servers at once; each server runs its own agent and the answers are combined into a single comparison table with one row per server and a literal **"Fehler"** column for deviations and errors.
- **Animated status strip** — one orb per agent above the conversation shows queued/running/awaiting-input/paused/done/failed/cancelled at a glance, flags *unusually long* runs via an adaptive threshold (`max(60 s, 2 × median of finished agents)`), and clicking an orb jumps to the agent's row. The strip scales from 1 to 50+ servers.
- **Per-agent and swarm-wide run control** — pause, resume, restart, and stop either a single agent (right-click its row) or the whole swarm (toolbar). Pausing is cooperative and stops the elapsed timers; restarts replace only that agent's answer.
- **Expandable live transcripts** — left-click an agent row to watch its live command/output transcript inline while it runs.
- **Conversation copy & export** — copy the whole swarm conversation to the clipboard or export it as plain text, Markdown, or PDF; saved swarm chats get their own **Swarm Chats** section in the AI Manager.
- **Readable result rows** — clicking a row of the combined answer table opens it in a separate **Row details** window with A−/A+ font sizing and copy-to-clipboard.
- **Targets without open terminals** — swarm runs (AI and script) now work on saved servers with no open terminal via background SSH sessions; no terminal tab is opened. Requires an unlocked master-password vault.
- **Run scripts without AI** — execute a Snippet Manager script with parameters on all swarm targets in parallel (Base64-transferred, single confirmation), with live output per server and a per-server exit-code/output result table.
- **Schedule swarm runs** — a new JobScheduler action type **AI_SWARM** with **Swarm parallelism** (1–16) and **Swarm read-only** fields; the swarm tab's **Schedule…** button prefills a job from the current targets and prompt. Results go to the journal *and* a saved swarm chat.
- **Visible composer and tab status dot** — the swarm input is a clearly framed three-line field, and the tab shows a colored activity dot (running / waiting for input / paused / finished — the green dot stays until the next run).
- **Multi-server workflow dialog reworked** — syntax-highlighted script view, a visible working animation with live elapsed time and total duration, an additional-instructions field with a de-duplicated 10-entry history, and **Save to Snippets** with a fitting pre-filled script name.

### Appearance

- **Five new app designs** — *Amber CRT* (warm amber-phosphor retro terminal), *Synthwave '84* (80s Outrun neon), *Gruvbox Retro* (cozy warm earthy), *Nord Arctic* (calm, flat arctic blue-grey) and *Dracula* (soft purple/pink) join the existing designs in *Settings → Appearance*, each with its own preview thumbnail. The existing designs are unchanged.
- **Subtle design animations toggle** — a new Appearance setting (default on) lets the glow designs breathe a small status-bar accent dot; turning it off doubles as a reduce-motion option, and the animation stops while the window is hidden.
- **More consistent design chrome** — a custom design's colors now apply deterministically across menus and dialogs, and the terminal theme's dynamic stylesheet no longer overrides the active design's chrome colors.
- **App design `Normal` renamed to `Default`** in *Settings → Appearance*. The stored value is unchanged, so existing configurations keep their selected design.
- **Previous/next buttons next to the App Design dropdown** let you step backward and forward through the designs (wrapping around at the ends) without opening the dropdown.
- **Design preview moved below the controls** into a fixed-size area, so switching designs (or back to `Default`, which has no preview) no longer draws the preview over the dropdown.

### Local Shell connections

- **Open the local machine's shell in a terminal tab (no network)** — a new **Local Shell** protocol spawns a local pseudo-terminal (PTY) via pty4j instead of connecting to a remote host. On Windows you can choose **PowerShell** (default) or **cmd.exe**; on macOS/Linux it defaults to your `$SHELL` (falling back to `/bin/zsh` or `/bin/bash`). A free-form **Custom command** field accepts any executable with arguments (e.g. `pwsh.exe`, `wsl.exe -d Ubuntu`, Git Bash), and an optional start directory can be set. Local Shell is selectable in both Quick Connect and the Connection Manager; for these connections host/port/username/authentication are not required and are disabled in the dialogs.
- **Git Bash / Cygwin / WSL presets** on Windows — each offered only when actually installed (Git Bash/Cygwin via their usual install locations / `PATH`; WSL only when `wsl.exe` is present and at least one distribution is installed). The command parser is quote-aware, so shell paths containing spaces (like `"C:\Program Files\Git\bin\bash.exe"`) launch correctly.
- **Shared connector hooks** — terminal recording/logging and the AI input/data hooks were lifted onto a shared `ObservableTtyConnector` interface, so they also work for local shells. SSH-channel-only features stay SSH-only.
- **AI Agent & Planning in local shells** — the agent's command-execution engine was decoupled from SSH behind an `AgentCommandRunner` abstraction (SSH exec channel and local process backends). The **AI Agent** and **AI Planning** now run in local shells on Windows, macOS and Linux: commands execute in the connection's shell (PowerShell via `-EncodedCommand`, `cmd.exe`, or `$SHELL`), the environment probe and system prompt are platform-aware, and the existing approval flow applies. Limitations on local shells: no `sudo`/administrator elevation on Windows, and no live working-directory tracking. The JobScheduler's headless AI-agent action stays SSH-only.

### Terminal usability

- **Ctrl + mouse-wheel zoom** — holding **Ctrl** (or **Cmd** on macOS) and scrolling the mouse wheel over the terminal now changes the font size instead of scrolling the buffer. This complements the existing Alt+Plus / Alt+Minus / Alt+0 shortcuts.
- **Ctrl+D closes a local cmd.exe/PowerShell tab** — those Windows shells do not exit on EOF, so Ctrl+D had no effect there. For bash-family shells (Git Bash/Cygwin/WSL, macOS/Linux) and SSH, Ctrl+D keeps its normal EOF meaning.

### AI chat & agent

- **Images, diagrams, and math render in AI chats** — AI answers containing an SVG document, a base64 raster image (`data:image/png;base64,…` — PNG, JPEG, GIF, BMP), a ` ```plantuml ` block, a ` ```mermaid ` block, or LaTeX math (` ```latex `/` ```tex `/` ```math ` blocks and `$$…$$` in prose) are shown as images instead of raw markup, each with a **Show code / Show image** toggle and copy button. Mermaid and MathJax are bundled (no network); PlantUML uses the local toolchain (`java` + Graphviz `dot`); SVG output is sanitized and shown with JavaScript disabled.
- **Full model reasoning in agent thinking rows** — expanding a 💭 row in the agent activity panel now shows the model's actual reasoning when the provider exposes it (Anthropic extended thinking per the profile's Reasoning effort, OpenAI-compatible `reasoning_content`, LM Studio reasoning output, `<think>` blocks from local CLI models), falling back to the decision summary otherwise.
- **Run log records the AI profile** — every agent run starts with an `AI profile: <name> (<model>)` activity row.
- **Reload uses the currently active profile** — the activity panel's reload button re-runs the command with the profile that is active now, not the one the original run was started with.
- **Agent Ask includes the terminal selection** — starting **AI → Ask AI Agent** from the right-click menu with text selected sends the selection as context, so the question is answered about the selected output or script.
- **Concrete models for cloud profiles** — the model selector is pre-filled with common model names for known cloud providers (offline, no API key needed), the refresh button merges the endpoint's live model list, clicking a model in the dropdown now reliably applies it, and the unusable **Auto** option is no longer offered for cloud endpoints (with a clearer error when no model is selected).

### Guide AI docs search

- **Ask the manual in natural language** — the built-in guide (**Help → Manual**, ++f1++) gets an **AI search** side panel: type a question in your language and get an answer generated exclusively from the bundled documentation, with clickable citations that jump the guide directly to the referenced page.
- **Uses your default AI profile; retrieval is fully offline** — no server, no extra API keys, no new dependencies. Retrieval runs locally over the bundled search index (with bilingual synonyms, German compound splitting and umlaut folding); off-topic questions are answered locally without contacting the AI endpoint at all.
- **Grounded answers** — the model is restricted to the retrieved excerpts, invented links are repaired or removed, and a native **Sources** list always shows the cited pages regardless of the model's answer.

### Workflow Script Generator

- **Two new target languages** — the agent run → **Workflow** script generator can now produce **Windows-CMD** (`.cmd` batch) and **AppleScript** (`.applescript`) in addition to Bash, Python, Perl, Ruby, PowerShell and Ansible.
- **Adjustable script font size** — each generated-script editor has **A−** / **A+** buttons and supports **Ctrl + mouse wheel** (Cmd on macOS); the chosen size is remembered across sessions.
- **Visible progress while a diagram is generated** — generating a PlantUML diagram from a script now shows the working spinner.
- **Clearer AI backend errors** — out-of-memory / resource-limit errors from the AI server (e.g. LM Studio/MLX "Resource limit exceeded", "metal::malloc") show a short, actionable hint instead of the raw stack trace; all other AI errors are collapsed to a single line.

### Fixes

- **Closing a local shell no longer freezes korTTY** — the PTY process is now destroyed before its streams are closed, releasing a terminal reader thread blocked in a pty `read()` instead of deadlocking the close on the JavaFX thread.
- **Correct wording for local shells when closing** — the close-confirmation no longer says "End SSH connection?" for a local shell, and the window-close prompt is now transport-neutral ("Active sessions").
- **No password prompt for local shells** — opening a local shell no longer shows an irrelevant password dialog (local shells use no authentication).
- **"Load as text file" works in local shells** — right-clicking a selected file name in a local-shell tab and choosing **Load as text file** no longer fails with "No active SSH connection is available". The file is read from the local filesystem — resolved against the working directory shown in the shell prompt when available, otherwise the directory the shell was started in — and opens in the snippet editor with **Overwrite local file** and **Save as...** just like the SSH/SFTP variant. The not-connected error message is now transport-neutral.
- **Local file overwrites are now atomic** — both "Overwrite local file" flows (local-shell **Load as text file** and the SFTP-manager local-file editor) used to truncate the target file in place, so a mid-write failure (disk full, process killed, power loss) could leave it truncated with no recovery. Overwrites now write to a sibling temp file and move it into place, preserve the original file's POSIX permissions, and write through symlinks to their real target instead of replacing the link itself.

## v2.2.3

### Critical fix: Monaco editors failed to load in the packaged app

- **Fixed the Monaco-based editors (snippet, file, AI, diff) opening to an empty pane in the packaged/notarized macOS app** — no caret, no typing, no paste. In the packaged app the WebView loaded its page from a `jar:` URL, and the page's Content-Security-Policy (`script-src 'self'`) then blocked the editor's own `monaco-host.js`/`.css`, because a `jar:`-origin document does not authorize its `jar:` siblings. The Monaco resources are now extracted to a temp directory and loaded from a `file:` URL, which the CSP allows. A failed editor load now also surfaces an error instead of a silently empty pane, and the editor bundle is additionally minified with a more generous boot budget.

## v2.2.2

### Critical fix: crash opening Monaco editors

- **Fixed a hard crash (no on-screen error) when opening the Snippet Manager, the Snippet editor, or the Settings AI-skill editor in packaged builds**: the bundled runtime was missing the `jdk.jsobject` module, so `netscape.javascript.JSObject` was unavailable at runtime and the JVM crashed in JNI `get_method_id` (`SIGSEGV`). `jdk.jsobject` is now bundled in the packaged runtime. This release supersedes v2.2.0 and v2.2.1, whose binaries are affected by this crash.

## v2.2.1

### Stability fixes

- **Settings / Snippet Manager crash fixed**: opening **Global Settings** or the **Snippet Manager** could abort the app. The embedded Monaco editor's JavaScript→Java bridge is now held by a strong reference for the editor's lifetime.
- **WebView lifecycle hardening**: Monaco editors are disposed when their dialog closes; late timer/load callbacks after close are ignored. The Settings *AI Skills* editor loads lazily on first use.

### Master-password login window

- **Full-bleed animated logo** in the standard app design, with the password form overlaid in a translucent card.

## v2.2.0

### Terminal engine and hyperlinks

- **SithTermFX 1.2.0** terminal engine (built from source).
- **OSC 8 clickable hyperlinks** — links emitted by programs such as `ls --hyperlink` or `eza`, restricted to a safe URI-scheme allowlist.

### Mosh (mosh4j) 2.0.2 upgrade & security hardening

- mosh4j `2.0.0 → 2.0.2` with per-direction replay/freshness protection and decompression-bomb limits; release JARs bundled in native builds.
- Bouncy Castle `1.78.1 → 1.84` (fixes CVE-2026-5598 HIGH and CVE-2026-0636 MODERATE); protobuf-java `4.28.2 → 4.35.1`.

### AI agent panel & activity

- **AI Agent Panel placement**: *At Bottom* (default), *Dock Left*, or *Dock Right*, remembered across restarts.
- **Multiple concurrent runs per split** (cap 5), per-run pause/resume, and Dashboard / tab status badges (✋ awaiting · ⚡ working · ⏸ paused · ✓ finished).

!!! note
    Older releases are recorded in the repository's `app-docs/RELEASE_NOTES.adoc` and will be migrated here in full.
