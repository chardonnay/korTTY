# Main window overview

![korTTY main window](../assets/screenshots/main/main-window.png)

![Main window layout](../assets/diagrams/mainwindow-layout.svg)

A fresh korTTY window: the menu bar, the terminal area (where session tabs and the optional dashboard appear once you connect), and the status bar. The following diagram maps the same regions:

![korTTY architecture](../assets/diagrams/architecture.svg)

korTTY's main window has these regions:

- **Menu bar** — File · Edit · Connections · Security · Configuration · Tools · Plugins · View · Teamwork · AI · Help. All features are reachable here and via [keyboard shortcuts](../reference/keyboard-shortcuts.md). A live **JobScheduler status** menu appears after *Help* when a scheduled entry is active. While a [guide translation](../reference/settings/translation.md) runs, a progress indicator (bar, percentage and estimated time remaining) sits at the right end of the menu-bar row and stays visible even when the menu bar itself is hidden.
- **Tab bar** — each SSH/Mosh session runs in its own tab. ++ctrl+t++ opens Quick Connect for a new tab; ++ctrl+tab++ / ++ctrl+shift+tab++ switch tabs. With **Open tool windows as tabs** enabled ([Window settings](../reference/settings/window.md)), management tools such as Snippets, the JobScheduler or the AI Manager open here as tabs too — in the window whose menu you used — instead of as separate windows.
- **Dashboard** (toggle ++ctrl+shift+d++) — a side panel listing every open connection with status dots, protocol badges and AI-agent badges. See [Dashboard](#dashboard) below.
- **File browser** (**View → File Browser ▸ Show on Left / Show on Right**) — a dockable local file manager with navigation toolbar, path bar, filter, type icons and a folder/file/selection counter. Its side, width, hidden-file state and last directory are restored on the next launch. See [File browser](../features/file-browser.md).
- **Terminal area** — the active terminal, with optional split-screen and broadcast input.
- **Status bar** — connection state, host/IP, active protocol, temporary SSH-key timer and connection duration.

!!! tip "Terminal-only fullscreen"
    Press ++ctrl+shift+f++ (or **View → Terminal-only Fullscreen**) to show the whole korTTY window — menus, tabs and status bar included — kept at its previous window size and centered on an empty fullscreen background, so the desktop and other windows stop competing for attention. **View → Hide terminal scrollbars in fullscreen** removes the scrollbars too. A transparent terminal background becomes opaque while fullscreen is active and returns to its saved level when you leave. Press ++ctrl+shift+f++ again to restore.

## Dashboard

Toggle the dashboard with ++ctrl+shift+d++ or **View → Show Dashboard**. It slides in on the left, sizes its width to the longest entry, and follows the active App Design's colors.

The header shows the panel title with two buttons: a collapse/expand toggle (collapses everything while any node is open, expands everything otherwise) and a refresh button. Below it, connections are organized as a tree:

- **Main window** — the root node, with an active/total session count.
- **Environments** — connections whose stored credential has an environment (for example *Production* or *Test*) are clustered under an environment node; connections without one sit directly under the main window.
- **Groups** — tabs assigned to a connection group appear under their group node. Saving a changed group in the Connection Manager updates open tabs immediately.

Each connection row shows a type icon, a status dot, the server name, and a protocol badge (`ssh`, `mosh` or `local`). The status dot distinguishes three states: filled green for a healthy connection, filled red for a connection that dropped unexpectedly (including a Mosh network interruption), and a hollow outline for a session that ended normally. Terminals with AI-agent runs carry the same ✋/⚡/⏸/✓ badge as elsewhere. Hovering a row shows `user@host` and the connection state; double-click (or ++enter++) focuses the session's tab.

Right-click a connection for **Focus**, **Duplicate**, **Reconnect**, **SFTP-Client...** (connected sessions only) and **Close**. A footer keeps a running "connected of total" count, and an empty panel shows a placeholder until the first session opens.

## macOS Dock menu

On macOS, right-click (or Control-click) the korTTY icon in the Dock for quick actions without switching to the app — they apply to the current (focused) window, opening one first if none is open:

- **New Window**
- **New Tab in Current Window**
- **Manage Connections…** (Connection Manager)
- **Open Project…**
- **Manual** (the in-app guide)
- **About korTTY**

See the [menu reference](../reference/menu.md) for every menu item and the [keyboard-shortcuts reference](../reference/keyboard-shortcuts.md) for the full list of accelerators.
