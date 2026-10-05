---
title: Session isolation
---

# Session isolation

korTTY can run a terminal session apart from korTTY itself and from the other tabs. You choose how far per connection, per folder of the Connection Manager or for everything in **Configuration → Global Settings → Security**, and the tab shows what the session actually got.

## Isolation levels

| Level | What it does |
| --- | --- |
| None | The session runs as before. |
| Own process | The session runs in a process of its own. |
| Own process + sandbox | The process also runs inside the operating system's sandbox: it cannot read or write korTTY's configuration folder `~/.kortty` (connections, credentials, `master.key`, logs, journals), `~/.ssh`, `~/.gnupg` or the keychains, and it may write only to its working folder, the temporary folder and a folder of its own that is deleted when the session ends. |

What each connection type can get in this version:

| Connection type | None | Own process | Own process + sandbox |
| --- | --- | --- | --- |
| Local shell | yes | yes (a local shell is always a process of its own) | yes |
| Mosh (native `mosh-client`) | yes | yes | yes: `mosh-client` runs in the sandbox; the short SSH start of `mosh-server` stays in korTTY |
| SSH | yes | yes, in a session worker (see below) | yes |
| Mosh (built-in client) | yes | not yet | not yet |

A connection that asks for more than its type can get runs with the strongest level it can get, and the connection editor says so under **Isolation:**. If your organization demands a level that the connection type cannot get, the session is not opened at all.

## SSH sessions in a session worker

An isolated SSH session runs in a **session worker**, a small process of its own that korTTY starts for this one connection. The worker connects to the server (through the jump server, if there is one), authenticates and keeps the encrypted connection; korTTY talks to it over a connection on this computer (`127.0.0.1`) that only accepts a one-time token korTTY created for it and whose key korTTY pins. The worker passes every channel korTTY opens on to the server, so the terminal, SFTP on the terminal's session, file drops, the AI agent's commands and `-L` and `-D` tunnels work as with a direct session.

- **Your private key stays in korTTY.** The worker never receives a key: when the server asks for a signature, the worker asks korTTY, which signs with the key it loaded as usual. The worker gets the password of a password login and of the jump server, but never the vault or the master password.
- **Host keys and prompts as before.** korTTY checks the server's host key against its known hosts with the usual dialogs and answers keyboard-interactive prompts, including the access reason, with the usual dialogs.
- **With a sandbox** the worker can neither read nor write `~/.kortty`, `~/.ssh`, `~/.gnupg` or the keychains, and writes only to a folder of its own. On macOS it may also connect only to this computer and to the port of the server and of the jump server; on Linux bubblewrap does not limit the network, which the shield's tooltip says.
- **Remote tunnels (`-R`) are not available** in an isolated SSH session yet; they are reported as refused in the status bar. Use the level **None** for a connection that needs them.
- **If the worker crashes**, the tab says *Session process crashed (exit N)* with the worker's last message and offers to reconnect; korTTY's log has its last output. A connection lost on the network is reported as *Connection lost*, as for a direct session.

**Tools → Session Processes...** lists every session that runs in a worker: its connection, process id, isolation, memory, CPU share and how long it has been running, refreshed every two seconds. A row is marked while its worker keeps a CPU core busy, and **End Process** ends the selected worker; its tab then offers to reconnect. Workers run with a lower priority, so a busy one does not slow down korTTY's window.

!!! note "The sandbox on each operating system"
    On macOS korTTY uses `sandbox-exec` with a profile it generates for each session. On Linux it uses bubblewrap (`bwrap`), which has to be installed; it needs unprivileged user namespaces, which some containers do not allow. On Windows, and when korTTY runs in Flatpak (itself a sandbox that does not allow another one inside), no sandbox is available yet. Before the first sandboxed session korTTY runs a self-test: a secret file in its configuration folder must be unreadable from inside the sandbox while a harmless command still runs. Only a sandbox that passes counts as available.

In a sandboxed local shell the history file (`HISTFILE`) and `TMPDIR` point to the session's own folder, so the shell's history does not survive the session, and the variable `KORTTY_SANDBOX` names the sandbox in use.

## Choosing a level

- **For one connection:** Connection Manager → edit the connection → **Terminal Settings** tab → **Session isolation** (below **Terminal behavior**) → **Isolation:**. **Use the default** follows the connection's folder or Settings and names which one applies.
- **For a folder:** right-click the folder in the Connection Manager → **Session Isolation** → **Use the Default**, **None**, **Own process** or **Own process + sandbox**. Folders below inherit the level, and so do the connections in them that set none of their own. The level moves along when the folder is renamed and is removed when the folder is deleted.
- **For everything else:** **Configuration → Global Settings → Security** → **Session isolation** → **Default isolation:**. The section also shows whether this computer has a working sandbox.

A connection from a shared teamwork file can only make isolation stricter than your folder or Settings, never less strict.

A session journal notes the isolation of the session each time it connects (for example *isolation: sandboxed (sandbox-exec)*), and the control API's `pane.list` and `pane.get` report each pane's `isolation` (`none`, `process`, `sandboxed` or `degraded`) and whether its tab is `incognito`.

## What the tab shows

![Tabs with a filled shield and a spy, an outline shield, and no marker](../assets/screenshots/main/session-isolation-tabs.png)

| Tab icon | Meaning |
| --- | --- |
| no shield | The session is not isolated. |
| Shield outline (blue) | Own process. |
| Filled shield (green) | Own process inside a verified sandbox; the tooltip names the sandbox (`sandbox-exec` or `bubblewrap`). |
| Shield with an exclamation mark (amber) | A sandbox was asked for but is not active, for example because `bwrap` is missing; the session runs in its own process, and the tooltip says why. |
| Spy (violet) | An incognito session (see below). |

With split panes the shield shows the least isolated pane, except that a missing sandbox always shows the warning sign; the tab's tooltip then counts how many panes run in a sandbox, in their own process, without the requested sandbox and without isolation. Screen readers read the same texts.

## Strict terminal mode

**Strict terminal mode:** in the same section drops escape sequences a hostile server could misuse before the terminal sees them: clipboard access (OSC 52), links that are not `http` or `https` links (OSC 8), window titles over 256 characters or with control characters, any OSC sequence over 4 KiB, and device control strings, application program commands, privacy messages and start-of-string sequences. Everything else, including colours, shell-integration marks and the working directory a shell reports, passes unchanged. **Automatic (on in a sandbox)** is the default; **On** and **Off** set it for the connection. A teamwork connection can switch it on but not off.

## Incognito sessions

An incognito session leaves no trace in korTTY: no terminal log, no session journal, no recording, no entry in **File → Recently Closed**, nothing of its screen in a saved project, and no place in the session that korTTY restores after a restart. Its tab shows a spy. Incognito works with every isolation level, with or without a sandbox.

- **Every session of a connection:** tick **Incognito session** in the **Session isolation** section of the connection editor. A connection from a shared teamwork file cannot be made incognito.
- **One session:** **File → New Incognito Session…** opens Quick Connect; the tab it opens is incognito whatever the connection says.

!!! warning "What incognito does not cover"
    Incognito concerns what korTTY writes down. The server, a remote shell's own history and anything a program in the session writes are not affected. If your organization enforces a session journal for every session, an incognito session is journaled too, and an organization can forbid incognito sessions altogether.

## Organization policy

An enterprise policy can demand a minimum isolation level with `[rule.isolation] minimum` and forbid incognito sessions with `incognito-sessions = "deny"` in `[rule.features]`; see [Enterprise policy](../reference/enterprise-policy.md). The levels below the minimum are then greyed out everywhere, and a session that cannot get the minimum is refused with a message instead of being opened with less.
