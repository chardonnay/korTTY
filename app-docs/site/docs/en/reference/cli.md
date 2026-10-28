---
title: Control CLI
---

# Control CLI

`kortty-cli` is the command-line client for korTTY's [Control API](control-api.md). It ships inside every korTTY package as a second launcher beside the application itself, finds the running korTTY on its own, authenticates, and turns the API's answers into something a shell script or a coding agent can act on.

It talks only to a korTTY running **on the same computer as you**, and only while that korTTY has the Control API switched on. Everything the API refuses, the CLI refuses too.

!!! note "Switch the API on first"
    Without **Settings › Terminal › Control API** ticked there is nothing to connect to, and every command exits with code 3. See [Control API](control-api.md) for what you are turning on and what it can do.

## Where the command lives

`kortty-cli` is not a script — it is a real launcher built into the packaged application image, so it starts the bundled JVM without needing Java on your `PATH`.

| Package | Path |
| --- | --- |
| macOS app bundle | `/Applications/korTTY.app/Contents/MacOS/kortty-cli` |
| Windows installer | `C:\Program Files\korTTY\kortty-cli.exe` |
| Linux deb / rpm | `/opt/kortty/bin/kortty-cli` |
| Arch (pacman) | `/usr/bin/kortty-cli` → `/usr/lib/kortty/bin/kortty-cli` |
| Flatpak | `flatpak run --command=kortty-cli io.github.chardonnay.korTTY` |
| Portable archive (Linux) | `<extracted directory>/korTTY/bin/kortty-cli` |
| Portable archive (Windows) | `<extracted directory>\korTTY\kortty-cli.exe` — at the image root, not under `bin` |
| Portable archive (macOS) | `<extracted directory>/korTTY.app/Contents/MacOS/kortty-cli` |

Only the pacman package puts it on your `PATH` for you. Everywhere else, add the directory to `PATH` or link the launcher into a directory that is already on it:

```bash
# macOS
sudo ln -s /Applications/korTTY.app/Contents/MacOS/kortty-cli /usr/local/bin/kortty-cli

# Linux deb/rpm
sudo ln -s /opt/kortty/bin/kortty-cli /usr/local/bin/kortty-cli
```

```powershell
# Windows, for the current user
[Environment]::SetEnvironmentVariable(
    'Path', $env:Path + ';C:\Program Files\korTTY', 'User')
```

The launcher is deliberately **not** called `kortty`: that name belongs to the graphical launcher (`/usr/bin/kortty` on Arch, `korTTY` elsewhere), and on macOS and Windows, whose filesystems ignore case, a second launcher called `kortty` would collide with it.

!!! note "Java distribution archives"
    The `korTTY-Java-*.zip` and `.tar` archives carry no launchers beyond the graphical one. From those, start the client as `java -cp korTTY-<version>.jar de.kortty.cli.KorttyCli <command>`.

## Shape of a command

```bash
kortty-cli <group> <command> [options]
```

The groups are `pane`, `tab`, `window`, `agent`, `events`, `notify`, `raw`, `ping` and `schema`, plus `mcp`, which is not a single call but a long-running server (see [Serving MCP clients](#serving-mcp-clients)). Inside a group, a command name is the API method after the dot with its underscores spelled as dashes — `pane.send_text` is `pane send-text`. Four methods do not follow from their name and are worth learning: `api.schema` is `schema`, `events.subscribe` is `events`, `notification.show` is `notify`, and anything without a command of its own — `events.unsubscribe`, `pane.resolve` — is reached through `raw`.

Everything a command needs is a flag. There are no positional arguments except the key names of `pane send-keys` and `agent send-keys`, and the method and JSON of `raw`.

```bash
kortty-cli ping                                   # is a korTTY listening?
kortty-cli pane list                              # every open pane
kortty-cli pane read --focused --recent --lines 200
kortty-cli pane run --pane p1a2b3c4d --command 'make test'
kortty-cli pane wait-output --pane p1a2b3c4d --contains 'BUILD SUCCESSFUL' --timeout-ms 300000
kortty-cli raw events.unsubscribe '{"subscription":"s1"}'
```

`kortty-cli schema` prints the complete machine-readable command surface — every command, its options, its result shape, its errors and a worked example — straight from the running korTTY. The parameters, results and errors come from the same declarations the server dispatches, and each entry's `cli` line is checked against this client's own parser before release, so both describe the version you are actually talking to; use it rather than guessing, especially from a script that has to keep working across versions.

## Command reference

Every flag the client accepts is one of the global flags below or belongs to one of the commands in the table after them. A flag that a command does not list is refused with exit 2 before anything is sent to korTTY.

### Global flags

| Flag | Meaning |
| --- | --- |
| `--config-dir <path>` | Looks for `control/endpoint.json` below `<path>` instead of `~/.kortty`. |
| `--timeout <ms>` | The client deadline for the whole call. The default is 5000 ms; a command that waits on korTTY gets its wait plus five seconds instead, and `events` has no deadline unless you give one. |
| `--raw` | Prints plain text instead of JSON, for the commands that carry text: `pane read` and `agent explain`. |
| `--pretty` | Indents the JSON instead of printing one compact line. |
| `-q`, `--quiet` | Prints nothing; the exit code is the only answer. |
| `--version` | Prints the client version and the protocol version, without contacting korTTY. |
| `-h`, `--help` | Prints the help for a group or a command, without contacting korTTY. |

### Commands

| Command | API method | Options and notes |
| --- | --- | --- |
| `ping` | `ping` | Checks that korTTY answers. |
| `schema [--method <name>]` | `api.schema` | Prints the machine-readable API schema, or one method of it. |
| `events [--kinds a,b] [--panes p1,p2] [--include-evidence] [--count N]` | `events.subscribe` | Streams one event per line; see [Watching events](#watching-events). |
| `window list` | `window.list` | Lists the open windows. |
| `tab list [--window <id>]` | `tab.list` | Lists the tabs of one window, or of every window. |
| `tab focus --tab <id>` | `tab.focus` | Raises the window and selects the tab. |
| `tab create --connection <id or name> [--isolation none, process or sandbox] [--incognito] [--window <id>]` | `tab.create` | Opens a saved connection in a new tab; see [Opening a connection](#opening-a-connection). |
| `tab close`, `tab rename` | reserved | Refused locally with exit 2: not implemented in this version. |
| `pane list [--tab <id>] [--window <id>] [--local-shell-only]` | `pane.list` | Lists the open panes, with their isolation and whether they are incognito. |
| `pane current` | `pane.current` | Prints the pane the user is looking at. |
| `pane get <selector>` | `pane.get` | Prints one pane. |
| `pane focus <selector> [--no-raise]` | `pane.focus` | Requests focus for a pane; `--no-raise` does not raise the window that owns it. |
| `pane read <selector> [--visible, --recent or --detection] [--lines N] [--raw]` | `pane.read` | Reads the screen (`--visible`, the default), the scrollback and the screen (`--recent`, 200 rows unless `--lines` says otherwise), or the coding-agent detection. |
| `pane send-text <selector> --text <s> or --stdin [--submit] [--bracketed auto, never or always] [--allow-shortcut-conflict]` | `pane.send_text` | Types text into the pane. `--stdin` reads the text from standard input; `--submit` presses Enter afterwards; `--bracketed` defaults to `auto`. |
| `pane run <selector> --command <s>` | `pane.run` | Types one single-line command and presses Enter. |
| `pane send-keys <selector> <key>...` | `pane.send_keys` | Sends key names such as `enter`, `tab`, `up` or `ctrl+c`; an unknown name is refused before anything is sent. |
| `pane wait-output <selector> (--regex <r> or --contains <s>) [--visible or --recent] [--lines N] [--timeout-ms N] [--poll-ms N]` | `pane.wait_output` | Waits until the output matches. It searches the recent output by default, waits 30 seconds unless `--timeout-ms` says otherwise, and polls every 150 ms unless `--poll-ms` says otherwise. |
| `pane split <selector> [--vertical or --horizontal] [--no-focus]` | `pane.split` | Splits a local-shell pane and prints the new pane. |
| `pane close <selector>` | `pane.close` | Closes a split pane; a tab's last pane is refused. |
| `agent list [--state S] [--kind K] [--tab <id>] [--window <id>]` | `agent.list` | Lists the coding agents, most urgent first. |
| `agent get <selector>` | `agent.get` | Prints one agent. |
| `agent explain <selector> [--raw]` | `agent.explain` | Explains why the agent is in its state; `--raw` prints the text only. |
| `agent wait <selector> --until blocked, done, working, idle or any [--timeout-ms N]` | `agent.wait` | Waits for the agent to reach a state; the wait is 60 seconds unless `--timeout-ms` says otherwise. |
| `agent prompt <selector> --text <s> or --stdin [--wait-until done, idle, blocked or any] [--timeout-ms N]` | `agent.prompt` | Sends a prompt. With `--wait-until` it also waits, for 60 seconds unless `--timeout-ms` says otherwise. |
| `agent send-keys <selector> <key>...` | `agent.send_keys` | Answers the agent's prompt with key names. |
| `agent rename <selector> --alias <s>` | `agent.rename` | Sets an agent's alias; a blank alias clears it. |
| `agent start (<selector> or --split-from <selector> [--vertical or --horizontal]) --kind <k> [--command <s>] [--prompt <s>] [--wait] [--until done or idle] [--timeout-ms N]` | `agent.start` | Launches a coding agent in a pane, or in a new split of one, and waits until korTTY has registered it. `--command` is split at white space into arguments and is not run through a shell. With `--wait` the default wait after the prompt is 300 seconds. |
| `notify --title <s> --body <s>` | `notification.show` | Raises one desktop notification. |
| `mcp` | MCP over stdio | Serves the tools described in [Serving MCP clients](#serving-mcp-clients). |
| `raw <method> [<json>]` or `raw <method> --stdin` | any | Calls any method. The optional JSON object is its parameters; with `--stdin` the standard input is. |

The `--kind` values of `agent list` and `agent start` are `claude-code`, `codex`, `gemini-cli`, `minimax-code`, `qwen-code`, `opencode`, `cursor-agent`, `aider`, `amp`, `goose`, `crush`, `kiro`, `continue`, `grok`, `jules`, `copilot`, `grok-build`, `antigravity` and `cline`. The `--state` values of `agent list` are `blocked`, `working`, `done`, `idle` and `unknown`.

`tab close` and `tab rename` are listed by the API as reserved; `kortty-cli` refuses them itself, so they never reach korTTY.

## Choosing a pane

Every command that addresses one pane takes **exactly one** selector, and every selector is a flag. `pane list`, `pane current` and `agent list` take none, and `agent start` accepts `--split-from` in place of a selector:

| Selector | Means |
| --- | --- |
| `--pane p1a2b3c4d` | That pane, which must be unique among the open ones |
| `--pane w1:t9f3a…:p1a2b3c4d` | The fully qualified address |
| `--tab t9f3a…` | That tab's focused pane |
| `--focused` | The pane you are looking at |
| `--current` | The pane the command is **running in** |

Giving none of them, or more than one, is a syntax error (exit 2) that names the four; a bare `p1a2b3c4d` or `@focused` written where a flag belongs is a syntax error too.

`--current` is resolved by the client, not the server: it walks its own process ancestry and asks korTTY which pane owns one of those process ids. That is why it works from a sub-shell, a `Makefile` recipe or a nested script without any setup, and why it fails loudly with a clear message where it cannot work rather than addressing the wrong pane.

!!! warning "`--current` needs a local shell"
    It matches against the operating-system process id of a pane's local shell. Inside an SSH session, inside a container, or in the Flatpak package — where local shells run on the host through `flatpak-spawn` and korTTY never sees their process ids — there is nothing to match and `--current` cannot resolve. Address the pane explicitly there.

## Opening a connection

`kortty-cli tab create --connection <id|name>` opens a saved or teamwork connection in a new tab and prints the tab. `--isolation none|process|sandbox` runs it with that [session isolation](../features/session-isolation.md) instead of the connection's own, `--incognito` opens it as an incognito session, and `--window w2` picks the window. The organization's minimum isolation is never undercut: asking for less is refused with exit 3. When signing in needs a question, the answer says `"pending": true` and korTTY asks you before it opens the tab.

```bash
kortty-cli tab create --connection web-01 --isolation sandbox --incognito
```

## Waiting

Commands that wait — `pane wait-output`, `agent wait`, `agent prompt --wait-until`, `agent start --wait` — block until the condition is met or the timeout expires, and exit 4 on a timeout. Every wait has a server-side hard cap of ten minutes; a longer request is clamped and the answer says so.

Requests on one connection run one at a time, so a script that wants to wait on one pane while doing something in another simply runs two commands concurrently — each opens its own connection. Up to eight connections may be open at once.

## Watching events

`kortty-cli events` subscribes to korTTY's coding-agent events and prints one JSON object per line, as they happen, until it is told to stop.

```bash
kortty-cli events --kinds agent.state_changed --panes p1a2b3c4d --count 1
kortty-cli events --include-evidence --timeout 60000
```

`--kinds` and `--panes` take comma-separated lists and narrow what is delivered. The kinds are `agent.added`, `agent.state_changed`, `agent.seen`, `agent.alias_changed`, `agent.removed`, `agent.evidence` and `events.overflow`. Without `--kinds`, every kind except `agent.evidence` is sent. `agent.evidence` is sent only with `--include-evidence`, and at most once a second per pane. `events.overflow` is always sent and reports how many frames were dropped when the stream fell behind. `--count N` stops after N events. Three things end the stream: `--count` is reached, the `--timeout` passes, or you interrupt it. The first two exit 0. An interrupt is **not** handled — there is deliberately no signal handler, so the process ends with the shell's 128 + SIGINT = 130, which is worth allowing for in a CI job that wraps the stream in `timeout`.

With neither `--count` nor `--timeout`, the stream has no deadline at all and runs until korTTY exits or you stop it.

## Serving MCP clients

`kortty-cli mcp` turns the client into a [Model Context Protocol](https://modelcontextprotocol.io/) server, so an AI assistant that speaks MCP can read your korTTY panes. It speaks the MCP stdio transport only: the assistant's host starts `kortty-cli mcp` as a child process and exchanges newline-delimited JSON-RPC messages with it on stdin and stdout. There is no network listener; the process reaches korTTY over the same local Control API endpoint as every other command, and declares itself as an `mcp` client so korTTY applies the [MCP rules](control-api.md#mcp-clients) to every request.

korTTY has to allow it: the Control API must be on, the separate **MCP server** switch under **Settings › Terminal › Control API** must be on, and the [enterprise policy](enterprise-policy.md) must not deny `mcp-server`. While any of them says no, every tool call returns an error that says why, and the assistant can still list the tools.

Most MCP hosts are configured with a JSON entry like this one; put the full path of `kortty-cli` from the table above in `command` if it is not on your `PATH`:

```json
{
  "mcpServers": {
    "kortty": {
      "command": "kortty-cli",
      "args": ["mcp"]
    }
  }
}
```

The server offers these tools. Each argument has the name of the API parameter it fills, and `pane` takes a pane id from `pane_list` or `@focused`:

| Tool | Calls | Offered |
| --- | --- | --- |
| `pane_list` | `pane.list` | Always |
| `pane_read` | `pane.read` | Always |
| `pane_wait_output` | `pane.wait_output` | Always |
| `tab_list` | `tab.list` | Always |
| `agent_list` | `agent.list` | Always |
| `pane_send_text` | `pane.send_text` | Only while korTTY reports **Allow write tools** as on |
| `pane_run` | `pane.run` | Only while korTTY reports **Allow write tools** as on |
| `pane_send_keys` | `pane.send_keys` | Only while korTTY reports **Allow write tools** as on |

The read tools are marked read-only and the write tools destructive, so a host that asks before destructive tools asks before each of them. korTTY asks as well, whatever the host does: every write tool call opens a [consent prompt](control-api.md#writes-by-an-mcp-client) in korTTY, and a write tool call waits up to 70 seconds for your answer before it gives up. A tool result is the API's JSON answer as text; korTTY has already masked the secrets it knows about and capped the size. A refusal, a denied or unanswered prompt, a stopped korTTY or a switched-off MCP server comes back as a tool error naming the reason, for example `method_not_allowed_for_mcp` or `mcp_write_denied`, and the assistant can show it to you. The tool list is read once per `tools/list` request, so switching the write tools on or off takes effect the next time the assistant lists the tools.

Each tool call opens its own connection, so restarting korTTY needs no restart of the assistant. Calls are handled one at a time: a long `pane_wait_output` delays the next answer until it returns. stdout carries only protocol messages; `kortty-cli mcp` writes one diagnostic line per request to stderr, naming the method or tool but never its arguments or results, and `--quiet` silences those lines. `--config-dir` works as for every other command.

!!! warning "Everything a tool returns is untrusted terminal text"
    A remote host, a log file or a web page shown in a terminal can contain text written to look like instructions to an AI assistant. The tool descriptions tell the assistant to treat results as data, but that is a request, not a guarantee, so keep the write tools off unless you need them. The MCP rules narrow what this server exposes; they are not a sandbox against an assistant that can also run shell commands as you, because any program of yours can read the token and connect as a plain `cli` client.

## Exit codes

The exit code comes from the server, not from a table the client keeps, so the two can never disagree.

| Code | Meaning | Retry? |
| --- | --- | --- |
| 0 | Success | — |
| 1 | The request was valid but could not be carried out: no such window, tab, pane, agent or connection, or a `--current` that matches no pane; a stale `instance`; not connected; the write failed; an agent that must be answered first; the last pane cannot be closed; a split the pane cannot take or that aborted; an internal error in korTTY | Sometimes — the answer's `retryable` field says |
| 2 | The request itself was wrong: a bad selector, an unknown key name, an invalid regular expression, a missing or out-of-range parameter, an empty text, an unknown command, a selector that matches more than one pane, a connection name that two connections share, or a text whose first line korTTY's own shortcut would swallow | No |
| 3 | Refused: the Control API is off, enterprise policy denies it or blocks the connection, the token was rejected, an MCP client is not allowed to make the call or its write was denied, or too many connections are open | No, until you change something; a korTTY that is not ready yet, or has too many connections open, is worth a retry shortly |
| 4 | A wait timed out | Usually |
| 130 | `kortty-cli events` was interrupted (Ctrl-C). The stream has no signal handler, so this is the shell's own 128 + SIGINT | — |

Every failure prints a one-line diagnostic on stderr in the form `kortty-cli: <message> (<code>)`, where `<code>` is the stable wire code — `not_found`, `timeout`, `blocked_by_policy` — that the exit code above was derived from. Add `--pretty` to get the whole JSON-RPC error object instead, which is what a script should branch on:

```bash
kortty-cli pane read --focused --pretty 2>err.json || code=$(jq -r .error.data.code err.json)
```

Failures the client diagnoses on its own are the exception: an unparseable command line, a korTTY that is not running, a `--current` that matches no pane and an expired client deadline never reached the server, so they carry no wire code and print a bare `kortty-cli: <message>` on either setting. Branch on the exit code for those.

## Checking the installation

`kortty-cli --version` prints the version and exits 0. `--version` and `--help` both answer without a running korTTY, which makes `--version` the right thing to put in an installation check or a CI smoke test.

```bash
kortty-cli --version || echo 'kortty-cli is not installed or not on PATH'
```

## Scripting notes

* Nothing is written to stdout except the command's result, so `kortty-cli pane read --focused` pipes cleanly.
* Diagnostics, warnings and error objects go to stderr.
* The authentication token is read from `~/.kortty/control/endpoint.json` by the client. There is no `--token` option and no environment variable, deliberately: a token on a command line ends up in the process list and in your shell history.
* korTTY logs one line per action the CLI performs, and raises one desktop notification the first time a program types into a pane. Your script is not invisible, by design.
