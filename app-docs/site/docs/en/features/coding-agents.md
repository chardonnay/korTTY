---
title: Coding agents
---

# Coding agents

KorTTY recognises when a terminal-based coding agent — **Claude Code**, **Codex** or **Gemini CLI** — is running inside one of your [local shell tabs](terminal.md#local-shell-tabs) and keeps track of what it is currently doing: working, waiting for your answer, or sitting idle at its prompt. The analysis happens entirely inside korTTY on your own machine. This page explains what is detected, how the detection works, how to switch it off, and how to adjust or extend the screen rules when an agent changes its user interface.

## What it detects

Detection covers the three agents below, started from a **Local Shell** tab — directly, through a package-manager wrapper such as `npx`, or as a `node`, `bun` or `deno` script. SSH and Mosh tabs are not analysed, because the agent runs on the remote machine and korTTY cannot see its process.

| Agent | Recognised executables |
|-------|------------------------|
| Claude Code | `claude`, `claude-code` |
| Codex | `codex` |
| Gemini CLI | `gemini` |

Every split pane is tracked on its own, so a tab with two panes can show one agent working while the other waits for a permission decision. A pane that runs no agent, or whose agent has exited, simply has no detection result.

## States

A detected agent is always in exactly one of five states. The list is ordered by urgency: when several panes are summarised, the most urgent state wins.

| State | Meaning |
|-------|---------|
| **BLOCKED** | The agent is waiting for you — a permission dialog (*Do you want to proceed?*), a question picker, or a `(y/n)` prompt is on screen. |
| **DONE** | The agent has finished a task and shows an explicit completion screen. Reserved for agents that render such a screen and for the upcoming dashboard; the bundled rules do not report it yet. |
| **WORKING** | The agent is thinking, running a tool or streaming output — typically recognisable by its spinner line and the *esc to interrupt* hint. |
| **IDLE** | The agent is running and its input box is empty, waiting for your next instruction. |
| **UNKNOWN** | The agent process is present but the screen matches none of the rules and the rule file asks for a strict result instead of a fallback. |

## How detection works

KorTTY only believes an agent is present when two independent observations agree — this *double evidence* keeps a stray `claude` in a shell script or a quoted prompt fragment from producing false alarms:

1. **Process tree.** The pane's local shell has a known process ID. KorTTY walks that process's descendants and looks for the newest live one whose executable name — or, for `node`, `bun` and `deno`, the script it runs — belongs to one of the supported agents.
2. **Screen rules.** The text currently visible in the pane (never the scrollback), together with the window title the agent sets and whether it switched to the alternate screen, is matched against the rule file of that agent. The first rule match confirms the process; from then on the rules decide the state.

The screen is re-evaluated about 200 ms after the first change in a burst of output — further changes inside that window are coalesced — so a long stream of output triggers one evaluation per 200 ms window instead of one per line, and a short burst exactly one. A slow heartbeat additionally notices when the agent exits without printing anything, when the tab disconnects, or when you switch detection off. Rules are evaluated by priority; the first rule whose conditions all hold determines the state. If the agent process is confirmed but no rule matches, the rule file's fallback state (IDLE for the bundled files) is reported.

!!! note "Privacy"
    Screen text is analysed only inside korTTY, in memory, on this computer. Nothing is sent to any service, written to disk, or handed to the AI assistant — detection is plain pattern matching against local rule files. If you prefer not to have local shell screens inspected at all, switch the feature off under **Settings → Terminal → Coding agents**.

## Enabling detection

Detection is on by default and is controlled by a single toggle in **Settings → Terminal**, section **Coding agents**. Changing it takes effect immediately for all open tabs — no restart or reconnect is needed.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Detect coding agents (Claude Code, Codex, Gemini CLI) in local shell tabs | toggle | — | On | `codingAgentDetectionEnabled` |

When the toggle is off, korTTY neither walks the process tree nor reads the screen of any pane.

## Custom rules

Each agent's screen rules live in a small JSON file. KorTTY ships one bundled file per agent and lets you replace it with your own copy under your configuration directory:

```text
~/.kortty/coding-agents/claude-code.json
~/.kortty/coding-agents/codex.json
~/.kortty/coding-agents/gemini-cli.json
```

An override replaces the bundled file for that agent **as a whole** — there is no merging, so start from the bundled file and edit it. The file must be a regular file (symbolic links are not followed), and its `kind` must match the file name. An invalid file — malformed JSON, an unknown key, a bad regular expression, a duplicate rule id, or a rule without any condition — is ignored with a warning in the log and the bundled rules stay in effect, so a typo can never silently disable detection. Rule files are read at startup; restart korTTY after editing one.

### Rule file schema

The schema is strict: every key not listed here is an error, so misspelt keys are reported instead of being ignored.

```json
{
  "kind": "CLAUDE_CODE | CODEX | GEMINI_CLI",
  "version": 1,
  "comment": "optional free text, e.g. the agent version the rules were written against",
  "fallbackState": "IDLE | WORKING | BLOCKED | DONE | UNKNOWN (optional, default IDLE)",
  "rules": [
    {
      "id": "unique-rule-id",
      "state": "WORKING | BLOCKED | DONE | IDLE",
      "priority": 100,
      "region": { "bottomNonEmptyLines": 8 },
      "anyLineRegex": ["Java regex, matched per line"],
      "regex": "Java regex, matched against the whole region",
      "contains": ["literal text that must all be present"],
      "notContains": ["literal text that vetoes the rule"],
      "title": "Java regex matched against the window title",
      "alternateScreen": false
    }
  ]
}
```

| Field | Required | Meaning |
|-------|----------|---------|
| `kind` | yes | The agent this file describes; must match the file name (`claude-code`, `codex`, `gemini-cli`). |
| `version` | yes | Schema version; currently always `1`. |
| `comment` | no | Free text for your own notes, for example which agent version the rules were verified against. |
| `fallbackState` | no | State reported while the agent process is present but no rule matches. Defaults to `IDLE`; `UNKNOWN` makes detection stricter. |
| `rules` | yes | The list of rules, evaluated by descending `priority`; ties keep file order. May be empty. |
| `rules[].id` | yes | Unique within the file; letters, digits, `.`, `_` and `-`. Shown in diagnostics so you can see which rule fired. |
| `rules[].state` | yes | The state this rule reports: `WORKING`, `BLOCKED`, `DONE` or `IDLE`. |
| `rules[].priority` | yes | Higher values are checked first. |
| `rules[].region` | no | `{ "bottomNonEmptyLines": N }` restricts matching to the bottom N non-empty screen lines; `0` or absent means the whole screen. |
| `rules[].anyLineRegex` | no | Regular expressions tried against each line of the region; the first matching line becomes the rule's evidence. |
| `rules[].regex` | no | One regular expression tried against the region joined with newlines (multi-line, dot matches newline). |
| `rules[].contains` | no | Literal substrings that must **all** occur in the region. |
| `rules[].notContains` | no | Literal substrings whose presence anywhere in the region vetoes the rule. |
| `rules[].title` | no | Regular expression that must match the window title set by the agent; never matches while no title has been received. |
| `rules[].alternateScreen` | no | When present, the pane's alternate-screen flag must equal this value. |

A rule must declare at least one of `anyLineRegex`, `regex`, `contains`, `title` or `alternateScreen`. All conditions of a rule must hold for it to match.

Regular expressions use **Java syntax** and are compiled with Unicode case folding; put `(?i)` at the start of a pattern to make it case-insensitive. Remember that JSON requires backslashes to be doubled, so `\s` is written as `"\\s"`. Patterns in `anyLineRegex` are searched within a line (they need not match the whole line), and `^` and `$` anchor to the line. Most agents keep their status line and prompt at the bottom of the screen, so a small `bottomNonEmptyLines` region keeps rules fast and prevents older output from matching.

### Example: bundled Claude Code rules

```json
{
  "kind": "CLAUDE_CODE",
  "version": 1,
  "comment": "Permission dialogs (Bash, Edit, Write, MCP) and the AskUserQuestion picker are BLOCKED; a tool call showing 'Running…' or the spinner status line with 'esc to interrupt' is WORKING; the empty input prompt with '? for shortcuts' is IDLE. The onboarding menus (theme picker, login method) intentionally match no rule. Claude Code does not use the alternate screen, so no rule depends on that flag. Re-verify against live sessions after an agent update.",
  "fallbackState": "IDLE",
  "rules": [
    {
      "id": "permission-prompt",
      "state": "BLOCKED",
      "priority": 1000,
      "region": { "bottomNonEmptyLines": 20 },
      "anyLineRegex": [
        "Do you want to (proceed|make this edit|create|run|allow)",
        "Do you want to allow this connection\\?",
        "Would you like to proceed\\?"
      ],
      "regex": "^\\s*[│┃]?\\s*❯?\\s*1\\.\\s*Yes\\b",
      "notContains": ["esc to interrupt"]
    },
    {
      "id": "question-picker",
      "state": "BLOCKED",
      "priority": 950,
      "region": { "bottomNonEmptyLines": 20 },
      "anyLineRegex": ["(?i)\\bEsc to cancel\\b"],
      "regex": "(?i)Enter to (select|confirm)|Arrow keys to navigate|↑/?↓ to navigate|Review your answers",
      "notContains": ["esc to interrupt", "Enter to set as default"]
    },
    {
      "id": "working-tool-running",
      "state": "WORKING",
      "priority": 910,
      "region": { "bottomNonEmptyLines": 12 },
      "anyLineRegex": ["^\\s*⎿\\s+Running…"]
    },
    {
      "id": "working-spinner",
      "state": "WORKING",
      "priority": 900,
      "region": { "bottomNonEmptyLines": 8 },
      "anyLineRegex": [
        "(?i)esc to interrupt",
        "^\\s*[✻✳✶✽✢·\\*]\\s+\\S.*…"
      ]
    },
    {
      "id": "idle-prompt",
      "state": "IDLE",
      "priority": 100,
      "region": { "bottomNonEmptyLines": 6 },
      "anyLineRegex": ["^\\s*[│┃]?\\s*[❯>]\\s*$", "\\?\\s+for shortcuts"],
      "notContains": ["esc to interrupt"]
    }
  ]
}
```

The two BLOCKED rules combine a per-line pattern with a whole-region `regex`, so a permission dialog is only reported when both the question and its *1. Yes* option are on screen, and a question picker only together with its navigation footer — the onboarding menus of a fresh installation show a similar list but neither footer, and must not count as blocked. The `notContains` veto on the BLOCKED and IDLE rules matters too: while Claude Code is working, its status line stays on screen together with older dialog text, and the veto keeps the higher-priority prompt rules from firing on stale lines.

### Keeping rules in sync with the agents

Agents change their interfaces often, and a rule that matched last month may silently stop matching after an update. KorTTY's bundled rules are therefore pinned by **screen fixtures**: for every agent, the repository keeps captured terminal screens with the state and rule they must produce, and the test suite fails as soon as a bundled rule no longer matches its fixture or a rule has no fixture at all. When you notice a wrong or missing state, the most useful report is the visible screen text of the pane at that moment, the agent and its version, and the state you expected — it becomes a new fixture and a rule fix in the next release. Until then, a local override file lets you correct the rule for yourself immediately.

## Limitations and troubleshooting

- **Flatpak.** In the Flatpak package the local shell runs on the host through `flatpak-spawn`, so the process ID korTTY holds belongs to the sandbox-side helper and the agent's process tree is not visible. No agent is detected in Flatpak local shells.
- **Remote clients as the shell command.** A local shell tab whose configured shell command is itself `ssh` or `mosh` runs the agent on another machine; the process tree ends at the client and no agent is detected. The same applies to any SSH or Mosh tab.
- **Unusual wrappers.** An agent started through a launcher that korTTY does not recognise — a custom shell script that `exec`s into a differently named binary, a container, or a terminal multiplexer running outside the tab — is not identified, because the executable name in the process tree does not map to a known agent.
- **Wrong or flickering state.** The agent's screen has changed, or a dialog is rendered in a way the bundled rules do not cover. Copy the bundled rule file to `~/.kortty/coding-agents/<agent>.json`, adjust the pattern, restart korTTY, and report the screen text so the bundled rules can be fixed.
- **No state at all although the agent is running.** Check that the toggle in **Settings → Terminal → Coding agents** is on, that the tab is a Local Shell tab, and look for a *coding-agents* warning in the log — an invalid override file falls back to the bundled rules, but a bundled rule file that never matches your agent version leaves the agent unconfirmed.

## Coming next

This release lays the foundation: detection runs and its results are available inside korTTY. The next stage builds the visible part on top of it — state badges next to the tabs and in the dashboard, a panel listing every running agent with its state, and desktop notifications when an agent becomes BLOCKED or finishes while its tab is not in front.
