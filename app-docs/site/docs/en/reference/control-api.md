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

Two more switches under the status line belong to [MCP clients](#mcp-clients), such as [`kortty-cli mcp`](cli.md#serving-mcp-clients), and both are off by default:

| Switch | What it does |
| --- | --- |
| **MCP server** | Serves clients that declare themselves MCP clients, with the read-only method list, masked output and capped reads described below. |
| **Allow write tools** | Also offers such clients `pane.send_text`, `pane.run` and `pane.send_keys`. korTTY still asks you before every write. It can only be changed while **MCP server** is ticked. |

Both switches are greyed out while the Control API checkbox is unticked, with a hint that says so; they keep their values and take effect again when you tick the Control API. When the enterprise policy denies `mcp-server` or `control-api`, both are unticked and locked with the "Managed by your organization" hint. Saving with either switch off also drops every "allow for this pane in this session" answer, so switching the write tools on again later asks afresh.

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

Rather than duplicating the method list here, ask korTTY: `api.schema` returns the full machine-readable surface — every method with its parameters, result shape, errors, CLI equivalent and a worked example, plus the key vocabulary, the error table and the limits. The parameters, results, errors and limits are generated from the same declarations the dispatcher registers, so they cannot drift from the implementation; each `cli` line is a command the shipped `kortty-cli` is tested to accept for that very method.

### What the methods do

| Group | Methods | Purpose |
| --- | --- | --- |
| `api` | `ping`, `auth`, `api.schema` | Handshake and discovery. |
| `events` | `events.subscribe`, `events.unsubscribe` | Push notifications when a coding agent changes state. |
| `window` / `tab` | `window.list`, `tab.list`, `tab.focus` | Enumerate the windows and tabs, and bring one to the front. |
| `pane` | `pane.list`, `pane.current`, `pane.get`, `pane.resolve`, `pane.focus`, `pane.read`, `pane.send_text`, `pane.run`, `pane.send_keys`, `pane.wait_output`, `pane.split`, `pane.close` | Read a pane, type into it, wait for output, split a local shell or close a split. |
| `agent` | `agent.list`, `agent.get`, `agent.explain`, `agent.prompt`, `agent.send_keys`, `agent.wait`, `agent.rename`, `agent.start` | Work with the coding agents korTTY has detected — see [Coding agents](../features/coding-agents.md). |
| `notification` | `notification.show` | Raise one desktop notification. |

The title `tab.list` reports for a terminal tab is its name without the coding-agent glyph, the group prefix or the `(DISCONNECT)` suffix: the name you gave it with [Rename Tab](../features/terminal.md#working-with-tabs), otherwise the [title its shell set](../features/terminal.md#title-from-the-shell), otherwise the connection's name. The shell's title is chosen by the server, so a client that needs to know which host a tab is connected to should not rely on the title alone. Renaming stays with you in the user interface.

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

### Typing into a pane

`pane.send_text`, `pane.run` and the single-character keys of `pane.send_keys` are encoded in the pane's [character encoding](../features/connections.md#character-encoding), so they arrive as the bytes the same keystrokes would produce: an SSH pane set to ISO-8859-1 receives `é` as the single byte `E9`, not as UTF-8. A character that encoding cannot represent is sent as `?`. The `agent.*` methods always send UTF-8, which is what the coding agents read.

When `pane.send_text` wraps its text in bracketed paste — `bracketed` is `always`, or `auto` found several lines and a pane that enabled it — korTTY first removes any bracketed-paste markers from the text itself (`ESC[200~`, `ESC[201~` and their 8-bit forms, which start with the single `CSI` byte `9B` — in a Windows-1252 pane that is the character `›`), so the text cannot end the paste early and have its remaining lines run as typed commands. An unbracketed write, which includes every `pane.run`, keeps such markers. `agent.prompt` and the prompt of `agent.start` always remove them, bracketed or not.

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

## MCP clients

A client can tell korTTY what kind of client it is with the optional `client_kind` parameter of `auth`: `cli`, the default, for `kortty-cli` and every script, or `mcp` for an MCP server that passes korTTY on to an AI assistant. korTTY ships such a server itself: [`kortty-cli mcp`](cli.md#serving-mcp-clients). Any other value is refused with `invalid_params`. An `mcp` client is served only while three things allow it: the Control API itself, the separate **MCP server** switch, which is off by default, and the [enterprise policy](enterprise-policy.md) key `mcp-server`. When one of them says no, the handshake fails with `mcp_server_disabled` or `blocked_by_policy`, and the check is repeated before every request, so switching the MCP server off stops a connection that is already open at its next call.

An `mcp` client gets a fixed, fail-closed list of methods:

| Access | Methods |
| --- | --- |
| Always | `ping`, `api.schema`, `window.list`, `tab.list`, `pane.list`, `pane.current`, `pane.get`, `pane.read`, `pane.wait_output`, `agent.list`, `agent.get` |
| Only with **Allow write tools**, also off by default | `pane.send_text`, `pane.run`, `pane.send_keys` |
| Never | everything else: events, focusing, splitting and closing, notifications, every `agent.*` method that acts, the reserved verbs, and any method a later korTTY adds until it is reviewed for MCP clients |

A refused method answers `method_not_allowed_for_mcp`, with `data.reason` set to `write_tools_disabled` or `not_exposed`. The method list in the `auth` reply and the `api.schema` document show an `mcp` client only the methods it may call, and the `auth` reply says in `mcp_write_tools` whether the write tools are on.

### What an MCP client reads

Everything an `mcp` client reads is masked, always, because its model usually runs in the cloud: korTTY treats it like a cloud AI profile, with no opt-out. The screen and scrollback of `pane.read`, the matched line and screen of `pane.wait_output`, the evidence line and the process command line of `agent.list`, `agent.get` and the agent inside a pane description, and the tab and window titles all lose the connection's password, the organisation's replacement rules from the [enterprise policy](enterprise-policy.md) and well-known token formats such as cloud access keys, each replaced by `***`. A command line is masked too, because it can carry an API key.

`pane.read` and `pane.wait_output` give an `mcp` client at most 2000 rows and 64,000 characters, the newest ones kept, and set `truncated` when anything was left out; a `cli` client keeps the protocol's own limits. `pane.read` adds `masked_count`, the number of secrets masked in what it returned. `pane.wait_output` searches the masked text, so a pattern cannot confirm a guessed password by whether it matched. A `cli` client reads the raw text exactly as before.

### Writes by an MCP client

When **Allow write tools** is on, korTTY still asks you before every write an `mcp` client wants to make. `pane.send_text`, `pane.run` and `pane.send_keys` open a prompt in korTTY that shows the client's name, marked as reported by the client because nothing proves it, the target pane with its tab title and host, whether the write submits a line, and the exact text or key names. Control characters and invisible characters are shown as symbols, such as `␊` for a line break, `␛` for Escape and `<U+202E>` for a direction override, so nothing reaches the pane that you could not see.

| Answer | What it allows |
| --- | --- |
| **Deny** (the default button) | Nothing; the call answers `mcp_write_denied` with `data.reason` `denied` |
| **Allow once** | This one write |
| **Allow for this pane in this session** | This write, and every later `pane.send_text` into the same pane from the same MCP server process without another question, as long as the text submits nothing |

The third answer is offered only for `pane.send_text` whose text contains no line break and no other control character and does not set `submit`. `pane.run`, `pane.send_keys` and any text that submits a line always ask, because one click must never let an assistant run whatever it wants afterwards. The session is the `kortty-cli mcp` process: it sends a random `mcp_session` id with every connection, so the answer lasts until the assistant's host stops that process or korTTY quits. A client that sends no `mcp_session` keeps the answer for one connection only.

A prompt that is not answered within 60 seconds counts as Deny (`data.reason` `timeout`), and so does a korTTY that cannot show one (`no_prompt`). Only one prompt is open at a time; a second write waits for the first, within the same 60 seconds. The call waits on its own connection while you decide, never on korTTY's user interface.

korTTY refuses some writes without asking, with `mcp_write_refused` and a `data.reason`, because it would not type into such a pane on its own either:

| `data.reason` | The pane |
| --- | --- |
| `paste_pacing` | is still sending a paste line by line |
| `alternate_screen` | shows a full-screen program such as `vim` or `less` |
| `foreign_session` | is suspected to run as another user or host, after `su` or a nested `ssh` |
| `broadcast` | is in a tab with broadcast mode on |
| `multi_exec` | takes part in multi-exec |
| `coding_agent` | shows a detected coding agent, or a korTTY agent run drives it |

These checks run again right before the text is typed, so a pane that opens `vim` while you read the prompt is still refused. Every decision, including each refusal and each write a session answer allowed, is written to korTTY's log as an `mcp.consent` line with the method, the decision, the reason, the number of characters and the client name, but never the text.

!!! warning "A narrower surface, not a sandbox"
    `client_kind` is declared by the client, not proven. The list limits what an MCP server exposes to an AI assistant; it does not protect korTTY from an assistant that can also run shell commands as you, because any program of yours can read the token and connect as `cli`. Treat every MCP client as a cloud model and everything it reads from a terminal as untrusted text.

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
