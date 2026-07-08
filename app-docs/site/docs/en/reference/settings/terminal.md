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

## Notes

!!! note "Scrollback"
    Controls how many lines of output each terminal pane keeps in its scrollback buffer. The value is read when a terminal is created, so a change applies to newly opened tabs and split panes — already-open terminals keep their current buffer size. Larger values use more memory per pane.

!!! note "SSH Keep-Alive"
    When enabled, korTTY sends periodic keep-alive packets to prevent SSH sessions from timing out during idle periods. The interval setting controls how often (in seconds) these packets are sent. The spinner range is 5–600 seconds; the interval is disabled if SSH Keep-Alive is toggled off.

!!! note "Drag-and-Drop File Copy"
    When enabled, you can drop files or folders from your file manager (Finder on macOS, Explorer on Windows) directly into the terminal window. The files will be copied to the remote SSH server via SFTP.

!!! note "Command Timestamps"
    When enabled, a sidebar appears on the left side of the terminal displaying the date and time each command was entered, useful for audit trails and session logging.

!!! note "Connection Retries"
    When enabled, failed SSH connections are automatically retried. Disabling this prevents automatic reconnection attempts for failed connections.
