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
   - When disabled, opening the project restores the windows with their position and size and the local file editor and image viewer tabs only; terminal, SFTP and remote file tabs are skipped.
4. Click *Save*.

Projects are `.kortty` files saved wherever you choose in the save dialog, for example in `~/.kortty/projects/`.

A project keeps every korTTY window that is open, not only the one you choose *Save Project* in; that window is saved first. A window that holds nothing but AI result and tool tabs, which projects do not keep, is left out, so it does not come back empty; the window you save from is always kept, for its position and size.

## Opening a Project

1. Open *File > Open Project* or press ++ctrl+o++ (++cmd+o++ on macOS).
2. Select a `.kortty` project file from the file browser.
3. The project opens right away. File and snippet editors with unsaved changes in the window ask first; then the project replaces the tabs of the window, and every further window saved in the project opens in a new window, see [Windows](#windows).

### Windows

Opening a project gives its first window to the window you open it from and opens each further window it holds in a new window. Windows that are already open keep their tabs. Projects saved by earlier versions hold only the window they were saved from and open in one window, as before.

- **Position and size** — each window comes back where it was, with its size, and maximized if it was maximized. When the screen a window was on is no longer attached, the window opens centred on the main screen with its saved size, shrunk to fit if needed. A maximized window keeps the size it had before you maximized it, so un-maximizing it later gives you that size back. Fullscreen is not saved, and the window you open the project from stays in fullscreen if it is.
- **Tabs** — the tabs come back in their saved order, sorted into their [tab groups](terminal.md#working-with-tabs) as usual.
- **Active tab** — each window selects the tab that was active when you saved. A remote file editor or image viewer tab opens only once its file has been downloaded; when it was the active tab, the window selects it as soon as it is there, unless you have chosen another tab in the meantime or the download takes longer than 10 seconds. When the active tab does not come back, for example because its connection was deleted or you cancelled its password, the window keeps the tab it shows.
- **Dashboard** — each window shows or hides the dashboard as it did when you saved.
- **Without Auto-Reconnect** the windows open as well, with their local file editor and image viewer tabs; a window whose tabs were all skipped opens empty.
- A project opens at most 32 windows; a project file with more, which korTTY writes only when that many windows were open, opens its first 32.

## Open Recent

*File → Open Recent* takes you back to what you used last without the file dialog or the Connection Manager:

- **Connections** — the saved connections you used last, up to 10, the one used last first: the same connections as the buttons at the top of Quick Connect, which count what you open from Quick Connect, the [command palette](command-palette.md), **Open Group** and this menu (a connection opened from the Connection Manager is not counted). Each entry shows the connection's name and `user@host`. Choosing one opens a tab for it that signs in like **Connect** in the Connection Manager (see [Signing in](connections.md#signing-in)), with the [server access policy](../reference/enterprise-policy.md#server-access-control) checked first, and counts as a use, so the connection moves to the top.
- **Projects** — the project files you opened or saved last, up to 10, newest first, followed by the other `.kortty` files in `~/.kortty/projects`, the one changed last first. Each entry shows the file name and its folder, with your home folder written as `~`. Choosing one opens the project like *File → Open Project…*: editors with unsaved changes ask first, the project replaces the tabs of the window, and further windows saved in it open in new windows. A project file that was moved or deleted is left out of the menu.
- **Clear List** empties the menu. It deletes no connection and no project file: a connection comes back once you use it again, a project once you open or save it.

The menu is rebuilt every time you open the *File* menu. On macOS, where korTTY keeps running after you close its last window, choosing an entry from the menu bar opens it in a new window.

korTTY keeps the paths of your recent projects, and the time you last chose **Clear List**, in its settings file `~/.kortty/global-settings.xml` (see [Configuration files](../reference/config-files.md#global-settingsxml)). They are therefore part of every [configuration backup](backup.md) and come back when you restore one. Only the paths are kept, never the content of a project, and the connections are not stored a second time: the list follows when each saved connection was last used.

## What Gets Saved

A project captures the complete state of your workspace:

| Component | Details |
|-----------|---------|
| **Windows** | Every open korTTY window with its position and size, and whether it was maximized (see [Windows](#windows)) |
| **Tabs** | All terminal tabs in each window, including their [split panes](#split-panes) with the server of each pane and the position of every divider, and the name of a [renamed tab](terminal.md#working-with-tabs), plus SFTP Manager tabs with the local and remote folders they show, and file editor and image viewer tabs |
| **Connections** | A reference to the saved connection of each tab, by the connection's internal id, so renaming a connection does not break the project |
| **Dashboard** | Whether the dashboard was shown, in each window |
| **Active Tab** | Which tab was active in each window, by the tab's session id, so it is found again after the tabs were sorted into their groups |
| **Terminal Sessions** | The last visible screen of each terminal tab's primary pane — not its scrollback and not the cursor position; the screens of further split panes are not saved |

!!! note
    AI result tabs and tool tabs (managers opened as tabs) are not saved with projects. They remain only in the current session and are lost when you close the tab or open a project. SFTP Manager tabs are not tool tabs in this sense: they are saved, see [SFTP Manager tabs](#sftp-manager-tabs).

## Auto-Reconnect

When **Auto-Reconnect** is enabled, KorTTY automatically:

- Restores all windows with their saved position and size, see [Windows](#windows)
- Reconnects each SSH tab using the original connection settings
- Gives each renamed terminal tab its name back; a tab you never renamed shows the connection's current name
- Shows each terminal tab's saved screen dimmed above the new session, framed by a *Restored output from* row with the date the project was saved and an *End of restored output* row. The text is written into the local terminal only and is never sent to the server: it does not reach the remote shell or its command history, and a session journal that starts with the connection does not record it (a journal you switch on later imports the scrollback, which then includes the restored rows). Control characters and escape sequences are removed from it before it is shown.
- Brings back each terminal tab's split panes once the tab is connected, see [Split panes](#split-panes)
- Reopens each SFTP Manager tab at the local and remote folders it showed when the project was saved
- Selects the active tab of each window and shows or hides its dashboard as saved

If **Auto-Reconnect** is disabled, terminal tabs are not restored: opening the project opens the saved windows at their position and size and reopens local file editor and image viewer tabs, while terminal, SFTP and remote file tabs are skipped. Open those connections again from the Connection Manager.

### Split panes

With **Auto-Reconnect**, a terminal tab that had split panes gets them back once its own session is connected: the panes open one after the other in the background, without a *Connecting* dialog for each, every pane in the place it had, and the dividers move to where they were when you saved the project. This happens once each time you open the project; when the tab reconnects later, its panes are not added again.

- A pane that ran on the tab's connection signs in like the tab, with the same password or SSH key. The [access reason](terminal.md#split-operations) you gave for the tab is sent for these panes as well, so a CyberArk-style server asks only once. A host key question and the prompts of a keyboard-interactive sign-in still appear when the server asks.
- A pane you had opened with **Split Right (new connection)** or **Split Down (new connection)**, and the panes split from it on the same server, open on that pane's saved connection, not on the tab's server. They sign in only with what is stored: the SSH key, or the password from the Credentials manager or the connection. Here korTTY does not ask for a password or a new temporary SSH key and does not offer to unlock the vault.
- Every pane passes your organization's [server access policy](../reference/enterprise-policy.md#server-access-control), the panes on the tab's own server included, because a saved connection may have been changed since the project was saved.
- A pane that cannot open stays closed together with the panes that were split from it, and the other panes keep their places. That happens when the pane needs a password or a new temporary SSH key, when the vault is locked, when the policy does not allow its server, when its connection was deleted or never saved (a Quick Connect session), or when the connection fails. The status bar then says how many panes of which tab were not reopened and why, for example *web-01: 2 of 4 split panes were not reopened (vault locked)*. Open them again with **Split Right** or **Split Down**.
- At most 32 panes per tab are reopened; a project file with more, which korTTY never writes itself, opens with the first 32.

Projects saved by earlier versions do not store a server for each pane: all their panes open on the tab's server, as before.

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
- Every window with its position, size and tabs, and the session id of its active tab
- Connection references (by connection id, so the connection must exist in your Connection Manager)
- Dashboard visibility of each window

The saved screen text is not part of the `.kortty` file. It is stored separately as one gzip file per terminal tab, `~/.kortty/history/<session-id>.history.gz`, which the project references by file name — a project file you share therefore carries the layout but not the screen text. korTTY only ever reads, writes or deletes plain file names inside `~/.kortty/history/`; a project whose reference points anywhere else opens without that screen text.

A project file never carries a pane's working directory or a reference to saved scrollback: korTTY removes both from every project it opens or saves, so a project someone sends you cannot choose the folder a local shell starts in or point a pane at a file.

## Use Cases

Projects are useful for:

- **Context switching** — Save a "production systems" project, a "development" project, and a "testing" project; open the one you need
- **Team handoffs** — Share projects with colleagues to set up identical workspace layouts and connections
- **Multi-window layouts** — Save a complex setup across multiple monitor windows and restore it instantly
- **Session recovery** — Quickly restore your last known configuration if the app crashes or you accidentally close tabs
