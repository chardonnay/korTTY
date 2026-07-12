---
title: Terminal AI agent & tools
---

# Terminal AI agent & tools

korTTY's Terminal AI Agent is a controlled automation workflow that enables safe, intelligent command execution on remote servers — and, since the execution engine was decoupled behind an `AgentCommandRunner` abstraction (SSH exec-channel and local-process backends), in [local shells](connections.md#local-shell) on Windows, macOS and Linux too. Unlike naive automation, the agent probes the session state, reasons about each step, and waits for human approval before executing system-changing commands.

!!! note "SSH vs. local shells"
    In local shells, commands run in the connection's shell (PowerShell via `-EncodedCommand`, `cmd.exe`, or `$SHELL`) and the environment probe and system prompt are platform-aware. Local-shell limitations: no `sudo`/administrator elevation on Windows, and no live working-directory tracking (the agent uses the connection's start directory). The JobScheduler's headless AI-agent action stays SSH-only.


![AI agent execution loop](../assets/diagrams/ai-agent-execution-loop.svg)

## Command variants

The Terminal AI Agent is triggered via shortcut commands at the shell prompt. When enabled in **Settings > AI**, KorTTY intercepts these commands locally instead of sending them to the server:

```bash
agent <goal>
agent-ask <question>
agent-plan <task>
agent -plan <task>
```

The base command name is configurable in **Settings > AI**. If you rename `agent`, KorTTY automatically derives the matching `-ask` and `-plan` commands. The same settings page can:

- Make the command name case-insensitive
- Disable the per-run setup dialog (uses the configured default profile when disabled)

!!! note
    KorTTY intercepts these shortcuts locally before they reach the remote shell. User-entered agent commands remain available in the shell history.

### Command purposes

- **`agent <goal>`** — Execute safe SSH commands to accomplish a goal. The agent inspects the session, plans non-interactive commands, requests approval when needed, and writes the final answer back to the terminal.
- **`agent-ask <question>`** — Get a non-executing answer about the current session context without running any commands. When started from the terminal right-click menu (**AI → Ask AI Agent**) with text selected, the selection is sent along as context, so the question is answered about the selected output or script.
- **`agent-plan <task>` / `agent -plan <task>`** — Enter planning mode first. The agent asks clarifying questions, proposes approaches, generates a final plan, and runs implementation only after you click **Implement**.

### Examples

```bash
agent show the 10 largest XML files in this directory
agent update groesste_xml.pl so the -r flag searches subdirectories recursively
agent check why nginx failed to start and suggest the safest fix
agent-ask what user and directory am I currently using?
agent-plan migrate this host from package X to package Y
```

## TAB completion and prompt history

At the shell prompt, TAB completion is enhanced for agent commands:

- Type the agent command name (e.g., `agent`) then press ++tab++ to show command variants (`agent`, `agent-ask`, `agent-plan`).
- Type the command + a space (e.g., `agent `) then press ++tab++ to show recent agent-prompt history. Each row displays the prompt and the date/time it was last run, de-duplicated by prompt text (newest first).
- Prompts longer than 60 characters are shortened with an ellipsis for readability; the full prompt is still inserted when selected.
- The history popup is resizable — drag the grip in the bottom-right corner — and remembers its size across restarts. It shows a vertical scrollbar when the history exceeds the popup height.
- In the history popup, click a row's ✕ button (or press ++del++ on keyboards with a forward-delete key) to remove a single prompt, or use **Clear all** (two-step confirm) to wipe the entire history. Deletions are saved immediately.
- Outside this context, ++tab++ remains normal shell completion.

History size is configurable in **Settings > AI** (default 20, range 5–100).

## How the AI Agent works

The Terminal AI Agent follows a strict, safe execution loop:

1. **Probe the session** — KorTTY probes the active SSH session with a non-interactive command and records compact context: current user, host, operating system, active terminal working directory, sudo availability, disk path, and recent command state.
2. **Send context to the model** — KorTTY sends the user task, probe snapshot, previous command results, active AI Skills, and optionally web-tool availability to the selected AI profile.
3. **Model returns a JSON decision** — The model must return a strict JSON response: run commands, ask for confirmation, finish, or block.
4. **Validate the decision** — KorTTY validates the JSON schema and command constraints. Invalid responses are repaired once; unsafe or unsupported decisions are rejected.
5. **Execute approved commands** — KorTTY runs approved commands through the active backend: SSH exec channels for SSH sessions, or a fresh local process for local shells. Each command starts in the tracked active terminal directory (the connection's start directory for local shells). A `cd` inside one command does not persist to the next.
6. **Iterate or conclude** — Command output is added to the activity panel and to the next model turn until the task completes, is blocked, cancelled, or the turn limit is reached (8 turns maximum).

### Suitable tasks

- Inspecting files, directories, package state, logs, service state, and system configuration
- Creating or modifying scripts and configuration files when the task asks for it
- Running tests, syntax checks, linters, or read-only diagnostic commands
- Summarizing command output and explaining findings
- Planning multi-step operational changes before implementation

### Not suitable for

- Interactive full-screen programs such as `vim`, `nano`, `top`, `less`, `ssh`, or commands that wait for prompts
- Long-running daemons or commands without clear completion
- Secret exfiltration or blind destructive commands
- Web research for local files unless the user explicitly asks for external/current information

!!! tip
    For local file review tasks, name the file in your prompt. The agent should then inspect it with SSH commands such as `sed -n`, `cat`, `file`, or language-specific syntax checks. When an internet-enabled profile is active, KorTTY still keeps web tools away from local file planning unless your task clearly asks for current or external information.

## Activity panel

Terminal-targeted agent runs use an inline activity panel at the bottom of the current terminal split.

### Panel features

- **Run tabs** — Multiple concurrent runs appear as closable tabs. Click a tab to select it; only the selected tab's run is controlled by run-control keys and buttons. Up to 5 concurrent runs per split.
- **Controls** — Each run has reload, pause/resume, cancel buttons, and per-row copy/snippet actions. The reload button re-runs the command with the **currently active** AI profile, so switching profiles between runs takes effect on the rerun.
- **Details** — The panel shows the user prompt in a two-line scrollable field, agent messages, read/run actions, task timing, reported token usage, semantic activity markers, and collapsible details.
- **AI profile row** — Every run log starts with an `AI profile: <name> (<model>)` entry, so the protocol records which profile and model produced the run.
- **Model reasoning** — Expanding a 💭 thinking row shows the model's full reasoning when the provider exposes it (Anthropic extended thinking when the profile's Reasoning effort is enabled, OpenAI-compatible `reasoning_content`, LM Studio reasoning output, or `<think>` blocks from local CLI models). Models without exposed reasoning keep the short decision summary.
- **Status bar** — When collapsed, the panel shows a compact status bar with the run prompt, state, pause/cancel buttons, and expand button. A spinner shows while the agent is working; a bold ✋ marker signals when user input is required.
- **Keep collapsed** — Use **Keep collapsed** to make the panel start minimized and stay collapsed when new activity or input prompts arrive. You can still expand manually.
- **Resizing** — Drag the resize handle to change panel height. Enable **Remember size** to persist height and font size across application restarts.
- **Parallel splits** — Different splits can run their own agent tasks in parallel; each split has its own activity panel.
- **Font controls** — Use **A−** and **A+** to change the activity font size.
- **Export** — Save the current run or all panel-history runs as Markdown, plain text, YAML, XML, JSON, PDF, or Asciidoctor. Exports include the AI profile, model/LLM, reasoning status, run timestamps, total runtime, per-activity runtimes, token usage, and detail text.
- **Expand all** — Keep activity details open instead of compacting them. This option is saved globally.

### Panel placement

Use **View → AI Agent Panel** to choose where the activity panel appears:

- **At Bottom** (default) — The activity panel appears below the terminal split where the run was started.
- **Dock Left / Dock Right** — The panel appears as a resizable side panel docked to the main window (like the file browser). In side mode there is one outer tab per terminal of the active tab, with runs stacked vertically. Dragging the divider resizes the dock; placement and width are remembered across restarts. Switching to another terminal tab swaps the dock to that tab's terminals.

### Status indicators

A per-terminal AI-agent status icon appears in the Dashboard and prefixed to the terminal tab title, aggregated across that terminal's runs and refreshed about once a second:

- **✋** — Awaiting user input (approval or sudo password)
- **⚡** — Working (agent is actively planning or running commands)
- **⏸** — Paused (run is parked at a safe checkpoint)
- **✓** — Finished (run completed successfully)

### Activity icons

Activity rows use semantic emoji icons (static, non-blinking):

| Icon | Meaning |
|------|---------|
| 💾 | Request or input: write/create file |
| 📖 | Action: read file/directory |
| ▶️ | Action: run/execute command |
| 📁 | Action: directory operation |
| 📦 | Context: package manager |
| ⚙️ | Context: service or system |
| 🌐 | Action: network operation |
| 🔍 | Action: inspect/analyze |
| 💭 | State: thinking/reasoning |
| 💬 | Output: message or result |
| ✋ | Required: awaiting user input (sudo/approval) |
| ❌ | State: error or failed activity |
| 🚫 | State: cancelled activity |

Red styling is reserved for error and failed states.

## Safety and failure handling

KorTTY enforces multiple guardrails around agent execution:

### Command limits

- **Per-turn limit** — A maximum of 3 commands per turn prevents runaway automation.
- **Non-interactive only** — The agent rejects interactive commands (like `vim`, `less`, `su`) that would hang in a non-interactive SSH exec channel.
- **Mutating-command detection** — Commands that change the system (`chmod`, `rm`, `mv`, `mkdir`, etc.) are flagged for confirmation unless the agent has auto-approval bypass.

### Privilege handling

- **Sudo detection** — The agent detects whether the current user has sudo access and whether a password is required.
- **Passwordless sudo** — When `sudo -n` (no-password sudo) is available, the agent uses it without prompting.
- **Password prompts** — When a password is required, the agent requests it via an activity-panel password input field (masked, not echoed to the terminal).
- **Password retries** — Up to 3 wrong-password retries are allowed before the run cancels.
- **No `sudo -S`** — KorTTY does not allow `sudo -S` (stdin password) or other interactive password methods in automated runs.
- **Session caching** — Sudo passwords can be cached for the current session so you don't re-enter them for every command in the same run.

### Approval gates

- **Mutating commands** — By default, the agent asks for approval before running commands that modify the system (unless auto-approval is enabled in settings).
- **Confirmation dialog** — The approval dialog shows the planned commands and their purposes. You can approve once, approve all remaining commands in the run, or cancel.
- **Sudo preflight** — After accepting a planning report, if the plan requires sudo and your SSH session requires a sudo password, KorTTY performs the preflight check immediately after acceptance so execution is not interrupted later.

### Directory tracking

- **Active directory** — Terminal shortcut runs use KorTTY's tracked current remote directory. Commands and generated files are executed relative to that directory.
- **Directory loss** — If a tracked directory no longer exists, KorTTY retries the probe from the SSH default directory and reports the issue.

### Typing during execution

- **Not locked** — While a terminal-targeted run is active, normal typing is still allowed. You can keep typing in the shell prompt and launch new `agent` commands (they open new tabs).
- **Run-control keys only** — Only run-control keys are intercepted:
  - ++esc++ or ++ctrl+c++ — Cancel the selected run's tab
  - ++ctrl+r++ — Toggle the selected run's thinking details

### Web tools

- **Explicit failures** — Web-search failures, HTTP errors, authentication errors, empty results, and timeouts are surfaced as explicit tool errors instead of the model making up facts.

### JSON schema repair

- **One repair attempt** — If the AI response does not match the required JSON schema, KorTTY asks for a repair. If repair also fails, the run is blocked with an explanation.

## Generate Workflow Script

After a finished agent run completes successfully, a **Workflow** button converts the run into a single self-contained, reproducible script in a chosen language (Bash, Python, Perl, Ruby, PowerShell, Ansible playbook, **Windows-CMD** batch, or **AppleScript**) with robust error handling, detailed comments, and a deterministic metadata header (script name, creator, date/time).

For fleet-wide tasks, the [AI Swarm](ai-swarm.md#generate-multi-server-workflow) tab has its own **Generate multi-server workflow** dialog that additionally handles host lists and multi-server hardening options, shows the generated script with syntax highlighting and a live elapsed counter, keeps a history of additional instructions, and saves to Snippets with a pre-filled name.

### Script generation capabilities

- **Auto-load matching AI Skills** — Skills like language-quality guidelines for the target language are automatically included.
- **Hardening options** — A collapsible panel of production-quality techniques (strict mode, error traps, meaningful exit codes, logging, idempotency, dry-run, `--help`, and more) that are baked into the generated script. All are on by default; untick any you don't want. See [Hardening options](../reference/hardening-options.md) for what each one means.
- **Eight target languages** — Bash, Python, Perl, Ruby, PowerShell, Ansible, plus **Windows-CMD** (`.cmd` batch — `@echo off`, `REM` headers, `errorlevel` checks) and **AppleScript** (`.applescript` — `osascript` shebang, `--` comments, `try`/`on error`).
- **Multiple language variants** — Generate several language variants and suggestions as inline tabs within the workflow dialog.
- **Adjustable font size** — Each generated-script editor has **A−** / **A+** buttons and supports ++ctrl++ + mouse wheel (Cmd on macOS); the chosen size is remembered across sessions.
- **Header templates** — Use reusable headers from the fixed non-deletable **Script-Header** snippet category.
- **Mermaid diagram** — Optionally include a Mermaid flowchart depicting the script logic. A working spinner is shown while the diagram is generated.
- **Snippet Manager** — Save the generated script into the Snippet Manager with a short auto-generated name and correct file extension. Scripts are de-duplicated by full name including extension.
- **Workflow tagging** — The snippet is tagged as `workflow` for easy filtering.
- **OS detection** — The **System** (OS) column is auto-set from the agent's probed OS (any Linux distro → Linux).
- **No internet access** — Internet access is forced OFF during generation regardless of the profile's internet mode.
- **Resizable dialog** — The workflow dialog remembers its size and position for future use.

## Terminal-targeted behavior

### Current working directory

The agent tracks the current remote directory for the active SSH session. Agent commands run relative to that directory, so generated files are created where you're currently working on the server.

### Input requirements

When user input is required (sudo password or command approval):

- **Auto-expand** — The activity panel auto-expands and selects the run that needs input.
- **Masked input** — Sudo password input is masked in the activity panel.
- **Submit** — Press ++enter++ or click the submit button to send the password.
- **Up to 3 retries** — Wrong passwords can be retried up to 3 times before the run cancels.
- **Bold badge** — The run tab shows a bold ✋ "input required" badge while waiting.

### Terminal output

Final terminal-agent answers are written back to the terminal area so the shell transcript contains the answer, not only the activity panel state.

### Transcripts

Dedicated agent and planning tabs can copy and save their transcript for later review or sharing.

## Running commands

To start an agent task:

1. **At a shell prompt** in an active SSH terminal, type your agent command:
   ```bash
   agent show the 10 largest files in this directory
   ```

2. **The agent probes the session** — It immediately collects the current user, OS, working directory, and sudo status.

3. **For planning mode**, the agent asks clarifying questions and proposes approaches before you approve:
   - Answer the questions or propose your own approach
   - Review the final plan
   - Click **Implement** to start execution

4. **The activity panel** appears at the bottom of the split showing:
   - The user prompt
   - Each decision made (which commands to run)
   - Real-time command output as it runs
   - Any approval or password requests

5. **Approvals** — When the agent needs approval to run system-changing commands, click **Approve once** (just this set) or **Approve always** (all remaining sets in the run), or click **Cancel**.

6. **Sudo password** — When prompted, enter your sudo password. It can optionally be cached for the session.

7. **The run completes** when the task succeeds, is blocked by an error, or is cancelled by the user.

8. **After completion**, a **Workflow** button appears if you'd like to convert the successful run into a reusable script.

## Keyboard shortcuts

| Shortcut | Action |
|----------|--------|
| ++tab++ at agent command | Show `agent`, `agent-ask`, `agent-plan` variants |
| ++tab++ after `agent ` | Show recent agent-prompt history |
| ++esc++ or ++ctrl+c++ during run | Cancel the selected run's tab |
| ++ctrl+r++ during run | Toggle thinking details for the selected run |
| Activity panel ⏸ button | Pause the selected run at a safe checkpoint |
| Activity panel ▶️ button | Resume a paused run |

## Planning mode

Planning mode (`agent-plan` or `agent -plan`) offers an advisory workflow before execution:

1. **Clarifying questions** — The agent asks follow-up questions to understand your requirements.
2. **Propose options** — Based on your answers, the agent suggests one or more approaches with feasibility, risks, and prerequisites.
3. **Final plan** — After you select an approach, the agent generates a detailed final plan with steps, success criteria, and risks.
4. **Implement** — Only after you review and click **Implement** does execution begin.
5. **Sudo preflight** — If the plan requires sudo and a password is needed, KorTTY checks the password immediately so execution is not interrupted later.

## Settings and configuration

Configure the Terminal AI Agent in **Settings > AI**:

| Setting | Effect |
|---------|--------|
| **AI Agent enabled** | Enable or disable the agent feature globally |
| **Agent command name** | The base command (default: `agent`); other variants are derived automatically |
| **Case-insensitive** | Make command matching case-insensitive |
| **Execution target** | Choose whether agent runs open in dedicated tabs or inline at the terminal split |
| **Per-run setup dialog** | Show a setup dialog before each run (disabled uses the default profile) |
| **Input history size** | Number of recent prompts to remember (5–100, default 20) |
| **Default profile** | The AI profile used when the setup dialog is disabled |
| **Activity panel placement** | Choose **At Bottom** or **Dock Left/Right** |
| **Keep collapsed preference** | Start the panel minimized and keep it collapsed during execution |

## AI Skills

AI Skills are reusable local instruction blocks that the agent can use. For agent runs, enable or disable skills globally, or let KorTTY automatically match only relevant skills to the task. Skills can cover:

- Operational policies and standards
- Security best practices
- Language-specific coding guidelines
- System administration conventions

See the **AI Skills** section in the main AI documentation for setup and management details.

## Internet access

The agent can optionally use web tools when the task clearly requires current or external information:

- **Disabled** (default) — No web tools or MCP integrations are sent with agent requests.
- **KorTTY Tavily Tool** — Direct web search via Tavily API.
- **LM Studio MCP modes** — Integrate Tavily, Bright Data, Brave Search, SearXNG, or LM Studio Toolpack through LM Studio's native MCP support.

Configure internet access per AI profile in **Settings > AI > Internet access**.

!!! warning
    **Web tools are withheld from local file/script review tasks** unless your prompt clearly asks for current or external information. Inspecting a local file should use SSH commands like `sed`, `cat`, `find`, or language-specific tools, not web search.
