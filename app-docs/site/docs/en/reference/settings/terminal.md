---
title: Terminal
---

# Terminal

Configure terminal display and behavior settings, including dimensions, scrollback, character encoding, and SSH connection management. Open via **Configuration → Global Settings → Terminal**; stored in `~/.kortty/global-settings.xml`.

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
| Enable SSH Keep-Alive | toggle | — | On | `sshKeepAliveEnabled` |
| Interval (seconds): | number | 5–600 | 60 | `sshKeepAliveInterval` |
| Enable connection retries | toggle | — | On | `connectionRetriesEnabled` |
| Automatically reconnect lost connections | toggle | — | On | `autoReconnectEnabled` |
| Disable host key verification for all connections | toggle | — | Off | `hostKeyCheckDisabledForAllConnections` |

## Notes

!!! note "Scrollback"
    Controls how many lines of output each terminal pane keeps in its scrollback buffer. The value is read when a terminal is created, so a change applies to newly opened tabs and split panes — already-open terminals keep their current buffer size. Larger values use more memory per pane.

!!! note "SSH Keep-Alive"
    When enabled, korTTY sends periodic keep-alive packets to prevent SSH sessions from timing out during idle periods. The interval setting controls how often (in seconds) these packets are sent. The spinner range is 5–600 seconds; the interval is disabled if SSH Keep-Alive is toggled off.

!!! warning "Disable host key verification for all connections"
    This is the global, lowest-precedence host-key setting: it relaxes verification to accept-new for every connection that does not set its own or its group's override. Accept-new still hard-blocks a changed key on a host already pinned, and a jump server's own key is always verified strictly — but disabling first-use verification removes protection against a man-in-the-middle on the very first connection. Off by default. Per-connection and per-group overrides are set in the Connection Manager; see [Security → Relaxing host-key verification](../../features/security.md#relaxing-host-key-verification).

!!! note "Drag-and-Drop File Copy"
    When enabled, you can drop files or folders from your file manager (Finder on macOS, Explorer on Windows) directly into the terminal window. The files will be copied to the remote SSH server via SFTP.

!!! note "Command Timestamps"
    When enabled, a sidebar appears on the left side of the terminal displaying the date and time each command was entered, useful for audit trails and session logging.

!!! note "Connection Retries"
    When enabled, failed SSH connections are automatically retried. Disabling this prevents automatic reconnection attempts for failed connections.

    Retries only cover failures that a further attempt could resolve. A changed host key, a Mosh connection configured with a jump server, or a missing Mosh runtime is refused immediately regardless of this setting.

!!! note "Automatically reconnect lost connections"
    When enabled and an **established** SSH connection is lost (network drop, server gone), the tab reconnects on its own with increasing delays — 3, 5, 10, 20, 30, then every 60 seconds — and the red status bar counts down to the next attempt. A double-click on the bar still reconnects immediately, and a successful reconnect or closing the tab stops the automatic attempts. Failed logins and other permanent failures (authentication, host key, configuration) are never retried automatically, and a connection that never got established is not retried by this setting either — that is what *Enable connection retries* covers. See [Terminal sessions → Connection loss](../../features/terminal.md#connection-loss-and-automatic-reconnect).
