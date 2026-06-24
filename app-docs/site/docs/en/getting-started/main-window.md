# Main window overview

![korTTY architecture](../assets/diagrams/architecture.svg)

korTTY's main window has these regions:

- **Menu bar** — File · Edit · Connections · Security · Configuration · Tools ·
  Plugins · View · Teamwork · AI · Help. All features are reachable here and via
  [keyboard shortcuts](../reference/keyboard-shortcuts.md). A live **JobScheduler
  status** menu appears after *Help* when a scheduled entry is active.
- **Tab bar** — each SSH/Mosh session runs in its own tab. ++ctrl+t++ opens Quick
  Connect for a new tab; ++ctrl+tab++ / ++ctrl+shift+tab++ switch tabs.
- **Dashboard** (toggle ++ctrl+shift+d++) — lists all open connections with status
  indicators and AI-agent badges. Right-click a connection to reconnect,
  duplicate, open SFTP, or close.
- **Terminal area** — the active terminal, with optional split-screen and
  broadcast input.
- **Status bar** — connection state, host/IP, active protocol, temporary SSH-key
  timer and connection duration.

!!! tip "Terminal-only fullscreen"
    Press ++f12++ (or **View → Terminal-only Fullscreen**) to hide all window
    chrome and show only the terminal. **View → Hide terminal scrollbars in
    fullscreen** removes the scrollbars too. Press ++f12++ again to restore.

See the [menu reference](../reference/menu.md) for every menu item and the
[keyboard-shortcuts reference](../reference/keyboard-shortcuts.md) for the full
list of accelerators.
