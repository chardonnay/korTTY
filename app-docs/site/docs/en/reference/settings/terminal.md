---
title: Terminal
---

# Terminal

Configure terminal display and behavior settings, including dimensions, scrollback, character encoding, keyword highlighting, links, and SSH connection management. Open via **Configuration → Global Settings → Terminal**; stored in `~/.kortty/global-settings.xml`.

![Terminal settings tab](../../assets/screenshots/settings/terminal.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Columns: | number | 40–500 | 80 | `terminalColumns` |
| Rows: | number | 10–200 | 24 | `terminalRows` |
| Scrollback: | number | 100–100,000 | 10,000 | `scrollbackLines` |
| Encoding: | dropdown | UTF-8, ISO-8859-1, ISO-8859-15, Windows-1252 | UTF-8 | `encoding` |
| Bold as bright color | toggle | — | On | `boldAsBright` |
| Show scrollbar in terminal | toggle | — | On | `showTerminalScrollbar` |
| Show command timestamps | toggle | — | Off | `commandTimestampsEnabled` |
| Allow drag-and-drop file copy into terminal | toggle | — | On | `terminalDragDropEnabled` |
| Copy selection to clipboard automatically | toggle | — | On | `terminalCopyOnSelectEnabled` |
| Close active terminal windows without confirmation | toggle | — | Off | `closeActiveTerminalWindowsWithoutConfirmation` |
| Highlight keywords in terminal output | toggle | — | On | `terminalHighlightingEnabled` |
| Also highlight in full-screen programs (vim, less, htop) | toggle | — | Off | `terminalHighlightAlternateScreen` |
| Default rule set: | dropdown | None, Errors and warnings, Network addresses, Network devices, or a set of your own | None | `defaultHighlightRuleSetId` |
| Edit Rules… | button | opens the rule-set editor | — | `highlightRuleSets` |
| Detect web addresses, e-mail addresses and file paths in terminal text | toggle | — | On | `terminalLinkDetectionEnabled` |
| Enable SSH Keep-Alive | toggle | — | On | `sshKeepAliveEnabled` |
| Interval (seconds): | number | 5–600 | 60 | `sshKeepAliveInterval` |
| Enable connection retries | toggle | — | On | `connectionRetriesEnabled` |
| Automatically reconnect lost connections | toggle | — | On | `autoReconnectEnabled` |
| Disable host key verification for all connections | toggle | — | Off | `hostKeyCheckDisabledForAllConnections` |
| Detect coding agents (Claude Code, Codex, Gemini CLI) in local shell tabs | toggle | — | On | `codingAgentDetectionEnabled` |
| Desktop notification when a coding agent needs a decision or finishes while you are not looking at its pane | toggle | — | On | `codingAgentNotificationsEnabled` |
| Show the number of agents waiting for a decision on the app icon | toggle | — | On | `codingAgentAppBadgeEnabled` |
| Allow a local program to read and control this korTTY | toggle | — | **Off** | `controlApiEnabled` |

## Notes

!!! note "Encoding"
    The character encoding korTTY decodes the output of an SSH session with and encodes what you type and paste in. It applies to every SSH connection that does not choose its own **Character encoding** on the *Connection* tab of the connection editor (see [Character encoding](../../features/connections.md#character-encoding)). Local shells use UTF-8 unless their connection sets an encoding, and Mosh always uses UTF-8 because mosh-server and mosh-client require it. Pick the encoding the programs on the server actually write, usually what `locale` reports there. A change applies the next time a tab connects or reconnects; open tabs keep their encoding. Characters the chosen encoding cannot represent are sent as `?`.

    Earlier versions ignored this setting, so a value chosen back then is not applied on its own: SSH sessions stay UTF-8, and a note under the dropdown says so, until you save the settings after opening the Terminal page. Choose **UTF-8** before saving if you do not want the old value.

!!! note "Bold as bright color"
    This setting currently applies to terminal recordings only: with [Capture terminal colors in recordings](video.md) on, bold text in one of the 8 normal ANSI colors is stored in its bright variant. The live terminal draws bold text in its normal color either way.

!!! note "Scrollback"
    Controls how many lines of output each terminal pane keeps in its scrollback buffer. The value is read when a terminal is created, so a change applies to newly opened tabs and split panes — already-open terminals keep their current buffer size. Larger values use more memory per pane.

!!! note "Copy selection to clipboard automatically"
    When enabled, text you select in a terminal is copied to the clipboard as soon as you select it. On Linux it also becomes the X11 primary selection, so a middle-click pastes it in other applications such as xterm or gedit. With the enterprise policy's [internal clipboard mode](../enterprise-policy.md#internal-clipboard-mode) the selection stays inside korTTY on every platform.

!!! note "Keyword highlighting"
    **Highlight keywords in terminal output** is the master switch of [keyword highlighting](../../features/highlighting.md). While it is off, no pane is highlighted, whatever was chosen in a menu, with ++ctrl+shift+h++ (++cmd+shift+h++ on macOS), for a connection or as the default rule set, and the highlighting menus are greyed out. It is on by default, but nothing is highlighted until a rule set is chosen.

    **Default rule set** is the set every terminal pane shows unless its connection has a rule set of its own (see [Rule set per connection](../../features/highlighting.md#rule-set-per-connection)) or you choose another one for that pane. It is **None** by default, so highlighting stays off until you opt in here, for a connection in the connection editor, in *View → Highlighting*, in a pane's context menu or with the shortcut. A set chosen for a pane in one of those places stays on top of the default until the pane is closed, and **None** chosen there keeps that pane plain whatever the default is. If the stored default names a rule set that no longer exists, the dropdown shows it as missing and panes show no highlighting.

    **Also highlight in full-screen programs** extends highlighting to programs that use the terminal's alternate screen, such as `vim`, `less` and `htop`. It is off by default because these programs redraw their screen constantly and bring their own colors, so highlights can flicker there and fight the program's colors. Switching it off leaves the highlights a running program already shows until it redraws them.

    All three apply to open terminals as soon as you save.

    **Edit Rules…** opens the [rule-set editor](../../features/highlighting.md#your-own-rule-sets), where you create your own rule sets and look at the built-in ones. It stays available while the master switch is off, so you can prepare sets before switching highlighting on. The editor saves its changes when you confirm it, whether or not you then save the settings dialog, and afterwards the **Default rule set** dropdown lists your sets as they are now. If you delete the set the dropdown shows, it falls back to **None**.

!!! note "Links"
    With **Detect web addresses, e-mail addresses and file paths in terminal text** on, ++cmd++ + click (macOS) or ++ctrl++ + click (Windows, Linux) opens a web address, an e-mail address or a file path that a program printed as plain text: a web address in your default browser, an e-mail address as a new mail in your mail program, and in SSH and local-shell tabs a file path as text in the Snippet Editor. A plain click still only selects text. A change applies to the open terminals at once. Links that a program marks up itself with OSC 8 open with the same click either way. See [Links in terminal output](../../features/terminal.md#links-in-terminal-output).

!!! note "SSH Keep-Alive"
    When enabled, korTTY sends periodic keep-alive packets to prevent SSH sessions from timing out during idle periods. The interval setting controls how often (in seconds) these packets are sent. The spinner range is 5–600 seconds; the interval is disabled if SSH Keep-Alive is toggled off.

!!! warning "Disable host key verification for all connections"
    This is the global, lowest-precedence host-key setting: it relaxes verification to accept-new for every connection that does not set its own or its group's override. Accept-new still hard-blocks a changed key on a host already pinned, and a jump server's own key is always verified strictly — but disabling first-use verification removes protection against a man-in-the-middle on the very first connection. Off by default. Per-connection and per-group overrides are set in the Connection Manager; see [Security → Relaxing host-key verification](../../features/security.md#relaxing-host-key-verification).

!!! note "Drag-and-Drop File Copy"
    When enabled, you can drop files or folders from your file manager (Finder on macOS, Explorer on Windows) directly into the terminal window. The files will be copied to the remote SSH server via SFTP.

!!! note "Command Timestamps"
    When enabled, a sidebar appears on the left side of the terminal displaying the date and time each command was entered, useful for audit trails and session logging.

    Each mark stays on its command line when the scrollback is full and the oldest lines are dropped, and a mark whose line has left the scrollback disappears with it. **Clear Buffer** in the terminal's right-click menu, and a `clear` that also empties the scrollback, remove all marks; the next command gets a fresh one. Opening and quitting a full-screen program such as `vim` or `less` does not move the marks. The day and month above each mark and the full date in the hover popup follow the korTTY UI language (for example `02.10.` in German, `10/02` in English), as does the elapsed time in the popup.

!!! note "Connection Retries"
    When enabled, failed SSH connections are automatically retried. Disabling this prevents automatic reconnection attempts for failed connections.

    Retries only cover failures that a further attempt could resolve. A changed host key, a refused login, an SSH key file that is missing or cannot be read, a jump server whose stored password cannot be used or whose setup is incomplete, a Mosh connection configured with a jump server, or a missing Mosh runtime is refused immediately regardless of this setting.

!!! note "Automatically reconnect lost connections"
    When enabled and an **established** SSH connection is lost (network drop, server gone), the tab reconnects on its own with increasing delays — 3, 5, 10, 20, 30, then every 60 seconds — and the red status bar counts down to the next attempt. A double-click on the bar still reconnects immediately, and a successful reconnect or closing the tab stops the automatic attempts. Failed logins and other permanent failures (authentication, host key, configuration) are never retried automatically, and a connection that never got established is not retried by this setting either — that is what *Enable connection retries* covers. See [Terminal sessions → Connection loss](../../features/terminal.md#connection-loss-and-automatic-reconnect).

!!! warning "Allow a local program to read and control this korTTY"
    This is the Control API, and it is the only setting in this tab that is off by default. While it is on, any program running under your user account on this computer can list your windows, read every open pane — local shells and SSH sessions alike — and type into them, including pressing ++enter++. Nothing is reachable over the network and no other user of the computer can connect, but that is the extent of the boundary: within your own account it is the same power as sitting at your keyboard. A status line under the checkbox reports what the listener is actually doing, because the checkbox and the listener can legitimately disagree — enterprise policy can deny the feature, and a start can fail because another korTTY already owns the socket. Every action is logged with byte counts only, never terminal text, and the first time a program types into a pane you get one desktop notification. See [Control API](../control-api.md) for the security model and [Control CLI](../cli.md) for the `kortty-cli` client that speaks it.

!!! note "Detect coding agents"
    When enabled, korTTY watches every local shell pane for a running Claude Code, Codex or Gemini CLI and tracks whether it is working, blocked on a question, done or idle. The screen is analysed locally and nothing leaves the computer; the change applies immediately to open tabs. The two toggles below it control the desktop notification for an agent that needs a decision or finishes while you are not looking at its pane, and the count of waiting agents on the app icon (or in the window title where no icon badge exists); both read the setting live, so a change applies at once. See [Coding agents](../../features/coding-agents.md#app-icon-badge-and-notifications).
