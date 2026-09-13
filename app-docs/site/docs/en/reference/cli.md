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
| Portable archive | `<extracted directory>/bin/kortty-cli` |

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
kortty-cli <group> <command> [selector] [options]
```

Groups mirror the API: `pane`, `tab`, `window`, `agent`, `events`, `notification`, plus `ping` and `schema`. A command name is the API method with its underscores spelled as dashes — `pane.send_text` is `pane send-text`.

```bash
kortty-cli ping                                   # is a korTTY listening?
kortty-cli pane list                              # every open pane
kortty-cli pane read @focused --mode recent --lines 200
kortty-cli pane run p1a2b3c4d --command 'make test'
kortty-cli pane wait-output p1a2b3c4d --contains 'BUILD SUCCESSFUL' --timeout-ms 300000
```

`kortty-cli schema` prints the complete machine-readable command surface — every command, its options, its result shape, its errors and a worked example — straight from the running korTTY. It is generated from the same declarations the server dispatches, so it is authoritative in a way a manual page cannot be; use it rather than guessing, especially from a script that has to keep working across versions.

## Choosing a pane

Anywhere a pane is expected you may write:

| Selector | Means |
| --- | --- |
| `p1a2b3c4d` | That pane, which must be unique among the open ones |
| `w1:t9f3a…:p1a2b3c4d` | The fully qualified address |
| `t9f3a…` | That tab's focused pane |
| `@focused` | The pane you are looking at |
| `--current` | The pane the command is **running in** |

`--current` is resolved by the client, not the server: it walks its own process ancestry and asks korTTY which pane owns one of those process ids. That is why it works from a sub-shell, a `Makefile` recipe or a nested script without any setup, and why it fails loudly with a clear message where it cannot work rather than addressing the wrong pane.

!!! warning "`--current` needs a local shell"
    It matches against the operating-system process id of a pane's local shell. Inside an SSH session, inside a container, or in the Flatpak package — where local shells run on the host through `flatpak-spawn` and korTTY never sees their process ids — there is nothing to match and `--current` cannot resolve. Address the pane explicitly there.

## Waiting

Commands that wait — `pane wait-output`, `agent wait`, `agent prompt --wait-until`, `agent start --wait` — block until the condition is met or the timeout expires, and exit 4 on a timeout. Every wait has a server-side hard cap of ten minutes; a longer request is clamped and the answer says so.

Requests on one connection run one at a time, so a script that wants to wait on one pane while doing something in another simply runs two commands concurrently — each opens its own connection. Up to eight connections may be open at once.

## Exit codes

The exit code comes from the server, not from a table the client keeps, so the two can never disagree.

| Code | Meaning | Retry? |
| --- | --- | --- |
| 0 | Success | — |
| 1 | The request was valid but could not be carried out: no such pane, tab or agent; not connected; the write failed; the last pane cannot be closed | Sometimes — the answer's `retryable` field says |
| 2 | The request itself was wrong: a bad selector, an unknown key name, an invalid regular expression, a missing or out-of-range parameter, an unknown command | No |
| 3 | Refused: the Control API is off, enterprise policy denies it, the token was rejected, korTTY is not ready yet, or too many connections are open | No, until you change something |
| 4 | A wait timed out | Usually |

Every failure also prints a JSON error object on stderr carrying a stable `code` string, so a script can branch on the cause rather than on the message text.

## Checking the installation

`kortty-cli --version` prints the version and exits 0. It is the only command that does **not** need a running korTTY, which makes it the right thing to put in an installation check or a CI smoke test.

```bash
kortty-cli --version || echo 'kortty-cli is not installed or not on PATH'
```

## Scripting notes

* Nothing is written to stdout except the command's result, so `kortty-cli pane read @focused` pipes cleanly.
* Diagnostics, warnings and error objects go to stderr.
* The authentication token is read from `~/.kortty/control/endpoint.json` by the client. There is no `--token` option and no environment variable, deliberately: a token on a command line ends up in the process list and in your shell history.
* korTTY logs one line per action the CLI performs, and raises one desktop notification the first time a program types into a pane. Your script is not invisible, by design.
