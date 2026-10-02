---
title: Projects (workspaces)
---

# Projects (workspaces)

Projects save and restore your complete workspace state—all open windows, tabs, SSH connections, and terminal sessions. This lets you quickly switch between different work contexts without manually reconnecting or repositioning windows.

## Saving a Project

1. Open *File > Save Project* or press ++ctrl+s++ (++cmd+s++ on macOS).
2. Enter a **name** and optional **description** for the project.
3. Configure **Auto-Reconnect**:
   - When enabled, opening the project will automatically reconnect all saved SSH sessions.
   - When disabled, windows and tabs are restored but you must manually reconnect.
4. Click *Save*.

Projects are stored as `.kortty` files in `~/.kortty/projects/`.

## Opening a Project

1. Open *File > Open Project* or press ++ctrl+o++ (++cmd+o++ on macOS).
2. Select a `.kortty` project file from the file browser.
3. The **Project Preview** dialog appears, showing:
   - Number of windows to be restored
   - Tabs and connections in each window
   - Project metadata (name, description, last modified)
4. Click *Open* to load the project.

## What Gets Saved

A project captures the complete state of your workspace:

| Component | Details |
|-----------|---------|
| **Windows** | All open KorTTY windows and their positions/sizes |
| **Tabs** | All terminal tabs in each window, including split-pane configurations, plus SFTP Manager tabs with the local and remote folders they show, and image viewer tabs |
| **Connections** | A reference to the saved connection of each tab, by the connection's internal id, so renaming a connection does not break the project |
| **Dashboard** | Dashboard visibility and divider position |
| **Active Tab** | Which tab was active in each window |
| **Terminal Sessions** | Session state including cursor position and scrollback (if supported by the session) |

!!! note
    AI result tabs and tool tabs (managers opened as tabs) are not saved with projects. They remain only in the current session and are lost when you close the tab or open a project. SFTP Manager tabs are not tool tabs in this sense: they are saved, see [SFTP Manager tabs](#sftp-manager-tabs).

## Auto-Reconnect

When **Auto-Reconnect** is enabled, KorTTY automatically:

- Restores all windows with their saved geometry (position and size)
- Reconnects each SSH tab using the original connection settings
- Reopens each SFTP Manager tab at the local and remote folders it showed when the project was saved
- Restores the active tab and dashboard state

If **Auto-Reconnect** is disabled, windows and tabs are restored but you must manually reconnect each tab by clicking it or using *Reconnect* from the context menu.

### SFTP Manager tabs

With **Auto-Reconnect**, each saved SFTP Manager tab connects to its connection again and opens the local and remote folders it showed when you saved the project.

- A local folder that no longer exists opens your home folder instead.
- A remote folder that no longer exists opens your login directory on the server, and the status bar says **Remote folder** *path* **no longer exists; showing the home folder**. A remote folder that exists but cannot be read, for example for lack of rights, shows the usual error and opens the login directory as well.
- Projects saved by earlier versions referred to SFTP Manager tabs by the connection's name. Such a tab is still restored when exactly one connection has that name; when several connections share the name, the tab is skipped and the log says why. Save the project again to store the reference by id.
- A restored remote image viewer tab opens its own SFTP connection, with the same SSH key and jump server as the SFTP Manager, and that connection closes when you close the tab.

## Project File Storage

Projects are stored in `~/.kortty/projects/` as compressed `.kortty` files. Each project includes:

- Metadata (name, description, creation/modification timestamps)
- Complete window and tab state
- Connection references (by connection id)
- Dashboard visibility and layout

## Use Cases

Projects are useful for:

- **Context switching** — Save a "production systems" project, a "development" project, and a "testing" project; open the one you need
- **Team handoffs** — Share projects with colleagues to set up identical workspace layouts and connections
- **Multi-window layouts** — Save a complex setup across multiple monitor windows and restore it instantly
- **Session recovery** — Quickly restore your last known configuration if the app crashes or you accidentally close tabs
