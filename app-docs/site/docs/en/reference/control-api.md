---
title: Control API
---

# Control API

korTTY can open a **local** control socket so that a script or a coding agent running on the same computer can list your windows, read what a terminal pane is showing and type into it. It is the machine-facing twin of what you do with the mouse and the keyboard: nothing it can do is something you could not do yourself in an open pane.

It is **off by default** and has to be switched on per installation under **Settings › Terminal › Control API**. Nothing is reachable over the network at any point, and no other user of the same computer can connect.

!!! warning "Understand what you are enabling"
    While the Control API is on, any program running under your user account on this computer can read every open pane — local shells and SSH sessions alike — and type anything into them, including pressing ++enter++. That is the same power as sitting at your keyboard. Switch it on when you want a coding agent or a script to drive korTTY, and off again when you do not.

## Switching it on

1. Open **Settings › Terminal** and scroll to **Control API**.
2. Tick **Allow a local program to read and control this korTTY**.
3. Save. The status line underneath tells you what happened right away — no restart is needed.

| Status line | Meaning |
| --- | --- |
| Status: off | The checkbox is unticked, or korTTY has shut the listener down. |
| Status: blocked by your organization | Enterprise policy denies the `control-api` feature; the checkbox is locked. |
| Status: listening on … | A listener is up and `endpoint.json` has been written. The text names the socket or the loopback port. |
| Status: could not start — … | The listener refused to start. The most common reason is that another korTTY already owns the socket; the text says which. |

Unticking the box stops the listener and deletes the socket immediately.

## Where the endpoint lives

Everything the API owns lives in its own directory, `~/.kortty/control/`, created with owner-only permissions (`0700`) **before** anything binds. The directory, not the socket file, is the real protection: a socket inode is created with the process umask, which on most systems is world-readable.

| Platform | Listener |
| --- | --- |
| Linux, macOS | A unix domain socket at `~/.kortty/control/control.sock` |
| Windows | TCP on `127.0.0.1` with an operating-system-assigned port |

A client finds the endpoint by reading `~/.kortty/control/endpoint.json` (mode `0600`):

```json
{"transport":"unix","path":"/home/you/.kortty/control/control.sock","host":null,"port":0,
 "token":"kQ7f…","pid":38211,"app_version":"…","protocol_version":1,
 "instance_id":"6f0c2a1e-…","started_at_millis":1736000000000}
```

The file is written **last**, after the listener is bound, so its existence always implies a reachable endpoint and a readable token. korTTY deletes it, and the socket, when the API is switched off and when the application exits.

The token is 32 random bytes, regenerated on every start, and is required on **both** transports. On Linux and macOS the owner-only directory is the primary control and the token is defence in depth — against a loosened umask, a shared or network-mounted home directory, or a backup that copied the directory somewhere readable. On Windows it is the only control. It is never logged, never echoed in an error message, never passed on a command line and never shown in a notification: the legitimate client reads it from the file, which is the whole point.

## Speaking the protocol

The protocol is newline-delimited JSON-RPC 2.0 — exactly one JSON object per line, UTF-8, at most 1 MiB per line. The first request on a connection must be `auth`; anything else, and the connection is closed. One failed token closes the connection too.

```
→ {"jsonrpc":"2.0","id":1,"method":"auth","params":{"token":"kQ7f…","client":"my-script"}}
← {"jsonrpc":"2.0","id":1,"result":{"api":"kortty-control","protocol_version":1,"transport":"unix","ids_survive_restart":false,"capabilities":["events","split","agent_start","pane_resolve"]}}
```

Requests on one connection are executed **one at a time**: the next line is read only after the previous answer has been queued. A client that needs a long wait and concurrent calls opens a second connection — up to eight at once.

Rather than duplicating the method list here, ask korTTY: `api.schema` returns the full machine-readable surface — every method with its parameters, result shape, errors, CLI equivalent and a worked example, plus the key vocabulary, the error table and the limits. It is generated from the same declarations the dispatcher registers, so it cannot drift from the implementation.

### What the methods do

| Group | Methods | Purpose |
| --- | --- | --- |
| `api` | `ping`, `auth`, `api.schema` | Handshake and discovery. |
| `events` | `events.subscribe`, `events.unsubscribe` | Push notifications when a coding agent changes state. |
| `window` / `tab` | `window.list`, `tab.list`, `tab.focus` | Enumerate the windows and tabs, and bring one to the front. |
| `pane` | `pane.list`, `pane.current`, `pane.get`, `pane.resolve`, `pane.focus`, `pane.read`, `pane.send_text`, `pane.run`, `pane.send_keys`, `pane.wait_output`, `pane.split`, `pane.close` | Read a pane, type into it, wait for output, split a local shell or close a split. |
| `agent` | `agent.list`, `agent.get`, `agent.explain`, `agent.prompt`, `agent.send_keys`, `agent.wait`, `agent.rename`, `agent.start` | Work with the coding agents korTTY has detected — see [Coding agents](../features/coding-agents.md). |
| `notification` | `notification.show` | Raise one desktop notification. |

`tab.create`, `tab.close` and `tab.rename` are **reserved**: they answer a definite "not implemented in this version" rather than an unknown-method error, and they are listed as reserved in `api.schema`, so a client can tell "korTTY will never do this for you" apart from "you spelled it wrong".

### Addressing a pane

| Id | Shape | Lifetime |
| --- | --- | --- |
| Window | `w1` | The window's life. It is a counter, not a position, so closing another window never renumbers it. |
| Tab | `t` + a UUID | The tab instance. |
| Pane | `p` + a short hex string | The pane's life. |
| Qualified | `w1:t9f3a…:p1a2b3c4d` | As above. |

Every parameter called `pane` also accepts a bare tab id, meaning that tab's focused pane, and the alias `@focused`, meaning the pane you are looking at. A bare **window** id where a pane is expected is refused as ambiguous rather than guessed at — a window usually holds several panes, and picking one silently is how a script types into the wrong terminal.

!!! note "No id survives a restart"
    Ids are minted per korTTY run. Every reply that carries ids also carries the `instance` of the running korTTY, and every mutating method accepts an optional `instance` parameter: when it does not match, the call fails with `stale_instance` instead of writing into whatever pane inherited the id. Re-enumerate whenever `instance` changes.

### Reading a pane

`pane.read` has three modes. `visible` returns the live screen, including the alternate buffer, right-trimmed. `recent` returns the scrollback plus the screen, read under a single buffer lock, newest `lines` rows. `detection` returns what korTTY's coding-agent detector last published for the pane — the detector is never re-run for a request.

`pane.wait_output` blocks until a regular expression or a literal string appears, polling the pane. Output that appears and scrolls away inside a single poll window can be missed in `visible` mode; the default `recent` mode also searches the scrollback and does not have that gap.

### Splitting

`pane.split` works **only** on a pane whose tab is a local shell. Anything else is refused with `unsupported`. The new shell is started without any dialog, so a script never ends up waiting on a window it cannot see; a split that would need a new connection needs a human, and is not offered.

`pane.close` refuses a tab's **last** pane. Closing it would leave an empty terminal area inside a still-open tab, which is exactly why korTTY's own "Close split" menu item is greyed out in that case. Close the tab yourself.

## What it cannot do

The threat model is a local process running as you, while the API is on. Such a process **can** enumerate your windows, read any pane, type into any pane including submitting commands, split a local shell, close a split pane, drive a detected coding agent, and raise a notification.

It **cannot**:

* reach anything before you tick the box, or at all when enterprise policy denies the feature;
* open a new tab, a new window or a connection of any kind — there is no way to reach a server that is not already open;
* split anything other than a same-server local shell;
* close a tab, close korTTY's last pane, or rename a tab;
* read or write your settings, your credentials, the vault, your SSH keys or the master password;
* reach anything over the network — the listener is a unix socket or loopback, never anything else, and there is no setting that can widen it;
* act without leaving an audit line, or without a desktop notification on its first write of the run.

!!! note "Writes are not restricted to local shells — on purpose"
    Typing is allowed into remote panes too. Restricting it would break answering a coding agent that runs over SSH, and it would not be a real boundary anyway: a caller who can reach the socket can simply run `ssh` in a local pane instead. The honest controls are the default-off switch, the policy deny, the audit line and the notification — not a filter that looks like a boundary and is not one.

## Audit and visibility

Every action the API takes writes exactly one line to korTTY's log, carrying **byte counts and shapes only** — `bytes=28 bracketed=false submitted=true`, `keys=2`, `orientation=vertical` — never terminal text. The line goes to korTTY's log and only there — the API writes nothing into a tab's session journal.

The first time a program types into a pane during a korTTY run you get one desktop notification. One, not one per keystroke: the point is that a takeover is never silent, not that it becomes noise you learn to dismiss. korTTY also logs a warning naming the endpoint whenever the listener starts.

Actions a coding agent performs through the API are logged distinguishably from the ones you trigger in the Coding Agents panel, so reading the log tells you which was which.

## Limits

| Limit | Value |
| --- | --- |
| Concurrent connections | 8; a ninth is refused and closed |
| Line size, in and out | 1 MiB |
| Result size | 512 KiB, truncated with `truncated: true` |
| Unauthenticated connection | closed after 5 s |
| Longest wait | 10 minutes; a larger request is clamped and says so |
| Event queue per subscription | 256 frames; overflow drops the oldest and reports how many |
| `pane.read` rows | 10 000 |

A client that stops reading is disconnected rather than buffered.

## Enterprise policy

An administrator can deny the Control API outright with the `control-api` feature in `kortty-policy.toml`:

```toml
[[rule]]
name = "no-local-automation"
  [rule.features]
  control-api = "deny"
```

The checkbox is then locked with the "Managed by your organization" hint, the setting is forced off both when it is loaded and when it is saved, and the listener answers `blocked_by_policy` immediately — the gate is re-checked on every request, not only when a connection is accepted, so a policy change stops service before teardown finishes. See [Policy configuration](enterprise-policy.md).

!!! note
    Writing `control-api = "allow"` also locks the checkbox — in the **on** position. A policy file that mentions a setting takes it over, whichever way it decides it. Leave the key out entirely to leave the choice with the user.

## The client

The `kortty-cli` command that ships beside korTTY speaks this protocol and handles endpoint discovery, authentication and exit codes for you. See [Control CLI](cli.md).
