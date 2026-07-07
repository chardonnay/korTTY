# Connections

korTTY manages SSH, Mosh and **local-shell** connections through three entry points: **Quick Connect**, the **Connection Manager**, and saved **Projects**.

![Connection flow](../assets/diagrams/connection-flow.svg)

## Quick Connect

Open with ++ctrl+k++ (or **Connections → Quick Connect…**). Enter host, port, username and authentication, and connect without saving. Frequently used connections appear as quick buttons; a live search filters them.

## Connection Manager

**Connections → Manage Connections…** opens a searchable tree of saved connections (optionally grouped). From here you create, edit, duplicate, delete, import and export connections.

## Creating / editing a connection

The connection editor has these tabs:

| Tab | Contents |
| --- | --- |
| Connection | Host, port, username, protocol (SSH / Mosh / Local Shell), authentication (password / key / keyboard-interactive). For **Local Shell** connections host, port, username and authentication are not required and are disabled. |
| Terminal Settings | Per-connection colors, font, ANSI/TrueColor handling, terminal effect |
| SSH Tunnels | Local / remote / dynamic port forwarding |
| Jump Server | Bastion-host chaining |
| Terminal Logging | Per-connection log format and destination |
| Window Geometry | Saved size/position for this connection |

## Protocols

=== "SSH"
    Standard SSH via Apache MINA SSHD. Supports password, public-key and keyboard-interactive authentication, keep-alive, and OSC 8 clickable hyperlinks.

=== "Mosh"
    Roaming, latency-friendly Mosh transport (mosh4j). The Mosh backend is bundled in native builds; existing connections need no migration.

=== "Local Shell"
    Opens the **local machine's** shell in a terminal tab (no network) via a pty4j-backed pseudo-terminal. Host, port, username and authentication are not required. See [Local Shell](#local-shell) below.

## Local Shell

A **Local Shell** connection spawns a local pseudo-terminal (PTY) on your own machine instead of connecting to a remote host. It is selectable in both **Quick Connect** and the **Connection Manager**; for these connections host, port, username and authentication are not required (and are disabled in the dialogs), and no password prompt is shown.

### Choosing a shell

| Platform | Options |
| --- | --- |
| Windows | **PowerShell** (default) or **cmd.exe**. **Git Bash**, **Cygwin** and **WSL** are also offered as presets — but only when actually installed (Git Bash/Cygwin are detected via their usual install locations / `PATH`; WSL appears only when `wsl.exe` is present and at least one distribution is installed). |
| macOS / Linux | Defaults to your `$SHELL` (falling back to `/bin/zsh` or `/bin/bash`). |

A free-form **Custom command** field accepts any executable with arguments (e.g. `pwsh.exe`, `wsl.exe -d Ubuntu`, a Git Bash path), and an optional **start directory** can be set. The command parser is quote-aware, so shell paths containing spaces — like `"C:\Program Files\Git\bin\bash.exe"` — launch correctly.

### Terminal features in local shells

Terminal logging and recording, plus the AI input/data hooks, work for local shells via a shared `ObservableTtyConnector` interface. Features that depend on an SSH channel stay SSH-only.

!!! note "AI Agent in local shells"
    The **AI Agent** and **AI Planning** also run in local shells on Windows, macOS and Linux — see [AI assistant](ai-assistant.md#ai-agent-and-ai-planning).

## Tunnels & jump servers

- **SSH tunnels** — forward ports through the connection: **local** (`-L`), **remote** (`-R`) or **dynamic / SOCKS** (`-D`).
- **Jump server (bastion)** — route the connection through one or more intermediate hosts.

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

## Import from other clients

**Connections → Import…** reads connection files from **MTPuTTY**, **MobaXterm** and **PuTTY Connection Manager**, with group filtering and credential handling.

!!! note "More to come"
    This page is part of the scaffolded guide. The full feature library — SFTP, snippets, JobScheduler, AI assistant & tools, terminal recording, security, and the complete settings tables — is being filled in next.
