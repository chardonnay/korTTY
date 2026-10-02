---
title: Projects (workspaces)
---

# Projects (workspaces)

Projects save and restore your complete workspace state—all open windows, tabs, SSH connections, and terminal sessions. This lets you quickly switch between different work contexts without manually reconnecting or repositioning windows.

## Saving a Project

1. Open *File > Save Project* or press ++ctrl+s++ (++cmd+s++ on macOS).
2. Enter a **name** and optional **description** for the project.
3. Configure **Auto-Reconnect**:
   - When enabled, opening the project automatically reconnects every saved terminal and SFTP tab.
   - When disabled, opening the project restores the window geometry and the local file editor and image viewer tabs only; terminal, SFTP and remote file tabs are skipped.
4. Click *Save*.

Projects are `.kortty` files saved wherever you choose in the save dialog, for example in `~/.kortty/projects/`.

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
| **Tabs** | All terminal tabs in each window, including split-pane configurations |
| **Connections** | A reference to the saved connection of each tab, by its connection ID |
| **Dashboard** | Dashboard visibility and divider position |
| **Active Tab** | Which tab was active in each window |
| **Terminal Sessions** | The last visible screen of each terminal tab's primary pane — not its scrollback and not the cursor position; the screens of further split panes are not saved |

!!! note
    AI result tabs and tool tabs (managers opened as tabs) are not saved with projects. They remain only in the current session and are lost when you close the tab or open a project.

## Auto-Reconnect

When **Auto-Reconnect** is enabled, KorTTY automatically:

- Restores all windows with their saved geometry (position and size)
- Reconnects each SSH tab using the original connection settings
- Shows each terminal tab's saved screen dimmed above the new session, framed by a *Restored output from* row with the date the project was saved and an *End of restored output* row. The text is written into the local terminal only and is never sent to the server: it does not reach the remote shell or its command history, and it is not recorded in the session journal. Control characters and escape sequences are removed from it before it is shown.
- Restores the active tab and dashboard state

If **Auto-Reconnect** is disabled, terminal tabs are not restored: opening the project applies the saved window geometry and reopens local file editor and image viewer tabs, while terminal, SFTP and remote file tabs are skipped. Open those connections again from the Connection Manager.

## Project File Storage

A project is a plain XML file with the `.kortty` extension. Each project includes:

- Metadata (name, description, creation/modification timestamps)
- Complete window and tab state
- Connection references (by connection ID, so the connection must exist in your Connection Manager)
- Dashboard visibility and layout

The saved screen text is not part of the `.kortty` file. It is stored separately as one gzip file per terminal tab, `~/.kortty/history/<session-id>.history.gz`, which the project references by file name — a project file you share therefore carries the layout but not the screen text. korTTY only ever reads, writes or deletes plain file names inside `~/.kortty/history/`; a project whose reference points anywhere else opens without that screen text.

## Use Cases

Projects are useful for:

- **Context switching** — Save a "production systems" project, a "development" project, and a "testing" project; open the one you need
- **Team handoffs** — Share projects with colleagues to set up identical workspace layouts and connections
- **Multi-window layouts** — Save a complex setup across multiple monitor windows and restore it instantly
- **Session recovery** — Quickly restore your last known configuration if the app crashes or you accidentally close tabs
