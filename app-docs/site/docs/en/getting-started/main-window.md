# Main window overview

![korTTY main window](../assets/screenshots/main/main-window.png)

![Main window layout](../assets/diagrams/mainwindow-layout.svg)

A fresh korTTY window: the menu bar, the terminal area (where session tabs and the optional dashboard appear once you connect), and the status bar. The following diagram maps the same regions:

![korTTY architecture](../assets/diagrams/architecture.svg)

korTTY's main window has these regions:

- **Menu bar** — File · Edit · Connections · Security · Configuration · Tools · Plugins · View · Teamwork · AI · Help. All features are reachable here and via [keyboard shortcuts](../reference/keyboard-shortcuts.md). A live **JobScheduler status** menu appears after *Help* when a scheduled entry is active.
- **Tab bar** — each SSH/Mosh session runs in its own tab. ++ctrl+t++ opens Quick Connect for a new tab; ++ctrl+tab++ / ++ctrl+shift+tab++ switch tabs.
- **Dashboard** (toggle ++ctrl+shift+d++) — lists all open connections with status indicators and AI-agent badges. Right-click a connection to reconnect, duplicate, open SFTP, or close.
- **Terminal area** — the active terminal, with optional split-screen and broadcast input.
- **Status bar** — connection state, host/IP, active protocol, temporary SSH-key timer and connection duration.

!!! tip "Terminal-only fullscreen"
    Press ++ctrl+shift+f++ (or **View → Terminal-only Fullscreen**) to show the whole korTTY window — menus, tabs and status bar included — kept at its previous window size and centered on an empty fullscreen background, so the desktop and other windows stop competing for attention. **View → Hide terminal scrollbars in fullscreen** removes the scrollbars too. A transparent terminal background becomes opaque while fullscreen is active and returns to its saved level when you leave. Press ++ctrl+shift+f++ again to restore.

## macOS Dock menu

On macOS, right-click (or Control-click) the korTTY icon in the Dock for quick actions without switching to the app — they apply to the current (focused) window, opening one first if none is open:

- **New Window**
- **New Tab in Current Window**
- **Manage Connections…** (Connection Manager)
- **Open Project…**
- **Manual** (the in-app guide)
- **About korTTY**

See the [menu reference](../reference/menu.md) for every menu item and the [keyboard-shortcuts reference](../reference/keyboard-shortcuts.md) for the full list of accelerators.
