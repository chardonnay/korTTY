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
| **Tabs** | All terminal tabs in each window, including split-pane configurations and the name of a [renamed tab](terminal.md#working-with-tabs), plus SFTP Manager tabs with the local and remote folders they show, and file editor and image viewer tabs |
| **Connections** | A reference to the saved connection of each tab, by the connection's internal id, so renaming a connection does not break the project |
| **Dashboard** | Dashboard visibility and divider position |
| **Active Tab** | Which tab was active in each window |
| **Terminal Sessions** | The last visible screen of each terminal tab's primary pane — not its scrollback and not the cursor position; the screens of further split panes are not saved |

!!! note
    AI result tabs and tool tabs (managers opened as tabs) are not saved with projects. They remain only in the current session and are lost when you close the tab or open a project. SFTP Manager tabs are not tool tabs in this sense: they are saved, see [SFTP Manager tabs](#sftp-manager-tabs).

## Auto-Reconnect

When **Auto-Reconnect** is enabled, KorTTY automatically:

- Restores all windows with their saved geometry (position and size)
- Reconnects each SSH tab using the original connection settings
- Gives each renamed terminal tab its name back; a tab you never renamed shows the connection's current name
- Shows each terminal tab's saved screen dimmed above the new session, framed by a *Restored output from* row with the date the project was saved and an *End of restored output* row. The text is written into the local terminal only and is never sent to the server: it does not reach the remote shell or its command history, and a session journal that starts with the connection does not record it (a journal you switch on later imports the scrollback, which then includes the restored rows). Control characters and escape sequences are removed from it before it is shown.
- Reopens each SFTP Manager tab at the local and remote folders it showed when the project was saved
- Restores the active tab and dashboard state

If **Auto-Reconnect** is disabled, terminal tabs are not restored: opening the project applies the saved window geometry and reopens local file editor and image viewer tabs, while terminal, SFTP and remote file tabs are skipped. Open those connections again from the Connection Manager.

### SFTP Manager tabs

With **Auto-Reconnect**, each saved SFTP Manager tab connects to its connection again and opens the local and remote folders it showed when you saved the project. Without **Auto-Reconnect**, SFTP Manager tabs are not reopened.

- A local folder that no longer exists opens your home folder instead.
- A remote folder that no longer exists opens your login directory on the server, and the status bar says **Remote folder** *path* **no longer exists; showing the home folder**. A remote folder that exists but cannot be read, for example for lack of rights, shows the usual error and opens the login directory as well.
- Projects saved by earlier versions referred to SFTP Manager tabs by the connection's name. Such a tab is still restored when exactly one connection has that name; when several connections share the name, the tab is skipped and the log says why. Save the project again to store the reference by id.
- A remote image viewer tab is saved with the connection it was opened from, even when several SFTP Manager tabs are open or its SFTP Manager tab is already closed. When restored, it opens its own SFTP connection, with the same SSH key and jump server as the SFTP Manager, and that connection closes when you close the tab.

### File editor tabs

A saved file editor tab opens its file again: a local file when it still exists, and — with **Auto-Reconnect** — a remote file over an SFTP connection of its own, which closes when you close the tab. The project stores which file was open, not its unsaved changes.

A file editor tab with unsaved changes shows `*` after its name, and every way of closing it asks **Save**, **Discard** or **Cancel** first: the tab's close button, the editor's **Close** button or ++ctrl+w++ (++cmd+w++ on macOS) in the editor, *File > Close Tab*, *File > Close All Tabs*, opening a project, closing the window and quitting korTTY. **Cancel** keeps the tab open; when the question came from *Close All Tabs*, opening a project or closing the window, nothing is closed. If saving fails, for example because the SFTP connection is gone, korTTY shows the error and keeps the tab open with your changes. When several tabs have unsaved changes, korTTY selects each one before it asks about it.

## Project File Storage

A project is a plain XML file with the `.kortty` extension. Each project includes:

- Metadata (name, description, creation/modification timestamps)
- Complete window and tab state
- Connection references (by connection id, so the connection must exist in your Connection Manager)
- Dashboard visibility and layout

The saved screen text is not part of the `.kortty` file. It is stored separately as one gzip file per terminal tab, `~/.kortty/history/<session-id>.history.gz`, which the project references by file name — a project file you share therefore carries the layout but not the screen text. korTTY only ever reads, writes or deletes plain file names inside `~/.kortty/history/`; a project whose reference points anywhere else opens without that screen text.

## Use Cases

Projects are useful for:

- **Context switching** — Save a "production systems" project, a "development" project, and a "testing" project; open the one you need
- **Team handoffs** — Share projects with colleagues to set up identical workspace layouts and connections
- **Multi-window layouts** — Save a complex setup across multiple monitor windows and restore it instantly
- **Session recovery** — Quickly restore your last known configuration if the app crashes or you accidentally close tabs
