# Main window overview

![korTTY main window](../assets/screenshots/main/main-window.png)

![Main window layout](../assets/diagrams/mainwindow-layout.svg)

A fresh korTTY window: the customizable icon toolbar, the terminal area (where session tabs and the optional dashboard appear once you connect), and the status bar. The following diagram maps the same regions:

![korTTY architecture](../assets/diagrams/architecture.svg)

korTTY's main window has these regions:

- **Icon toolbar** — direct actions grouped into Terminal, Tools and Settings above the menu bar, with a separate row for your saved server and group buttons. See [Customizing the icon toolbar](#customizing-the-icon-toolbar).
- **Menu bar** — visible by default in each new window. Right-click either icon-toolbar row and choose **Show/hide menu bar** to show or hide it for this window; its check mark reflects the current state. You can also use ++ctrl+shift+l++ (++cmd+shift+l++ on macOS). Visibility is a session choice and is not saved. The macOS system menu bar remains available. The in-window menus are File · Edit · Connections · Configuration · Tools · AI · Teamwork · Plugins · View · Help. All features are reachable here and via [keyboard shortcuts](../reference/keyboard-shortcuts.md), and the [command palette](../features/command-palette.md) (++ctrl+shift+p++, ++cmd+shift+p++ on macOS) finds and runs any menu command by name, also while the menu bar is hidden, runs the terminal's right-click commands such as **Clear Buffer**, switches to any open tab, the ones you used last first, connects to any saved connection and runs any snippet in the terminal. A live **JobScheduler status** menu appears after *Help* when a scheduled entry is active. While a [guide translation](../reference/settings/translation.md) runs, a progress indicator (bar, percentage and estimated time remaining) sits at the right end of the menu-bar row and stays visible even when the menu bar itself is hidden.
- **Tab bar** — each SSH/Mosh session runs in its own tab, named after its connection, or after the [title its shell sets](../features/terminal.md#title-from-the-shell) or a name you give it with **Rename Tab**. ++ctrl+t++ opens Quick Connect for a new tab; ++ctrl+tab++ / ++ctrl+shift+tab++ switch tabs, in tab bar order or, if you switch it on in the [Window settings](../reference/settings/window.md#tabs), in the order you last used them, and ++ctrl+1++ to ++ctrl+8++ jump to the first to eighth tab and ++ctrl+9++ to the last one (++cmd++ on macOS). A colored dot in front of a tab's title, and a frame of the same color around its terminal, show the [tab color](../features/connections.md#tab-color) of its connection, or of its credential's [environment](../features/security.md#environments-and-tab-colors); the frame can be switched off in the [Window settings](../reference/settings/window.md#tabs). An amber fork icon after it marks a tab whose typing goes to other panes, through [multi-exec](../features/terminal.md#multi-exec) or [broadcast mode](../features/terminal.md#broadcast-mode); its tooltip says which. With **Open tool windows as tabs** enabled ([Window settings](../reference/settings/window.md)), management tools such as Snippets, the JobScheduler or the AI Manager open here as tabs too — in the window whose menu you used — instead of as separate windows. The [Snippet Manager](../features/snippets.md) keeps the snippets you edit as tabs of its own inside its tab.
- **Dashboard** (toggle ++ctrl+shift+d++) — a side panel listing every open connection with status dots, protocol badges and AI-agent badges. See [Dashboard](#dashboard) below.
- **File browser** (**View → File Browser ▸ Show on Left / Show on Right**) — a dockable local file manager with navigation toolbar, path bar, filter, type icons and a folder/file/selection counter. Its side, width, hidden-file state and last directory are restored on the next launch. See [File browser](../features/file-browser.md).
- **Live journal panel** (**View → Live Journal**, ++ctrl+alt+l++) — the active tab's running [session journal](../features/session-journal.md#the-live-journal-panel) as its full journal page, docked and updating in real time; side and width are restored on the next launch.
- **Coding Agents panel** (**View → Coding Agents**, ++ctrl+alt+g++) — docks beside the terminal area on the left or right and lists every coding agent detected in any korTTY window. It shows and hides itself with agent detection unless **Automatically show and hide the coding-agent sidebar** is switched off, and it keeps its last side and width. See [Coding agents](../features/coding-agents.md#the-coding-agents-panel).
- **Terminal area** — the active terminal, with optional split-screen and broadcast input.
- **Status bar** — connection state, host/IP, active protocol, temporary SSH-key timer and connection duration. While panes take part in [multi-exec](../features/terminal.md#multi-exec), an amber chip in every window counts them, with the tabs and windows they are in, and its **Stop** link ends multi-exec everywhere.

!!! tip "Terminal-only fullscreen"
    Press ++ctrl+shift+f++ (or **View → Terminal-only Fullscreen**) to show the whole korTTY window — menus, tabs and status bar included — kept at its previous window size and centered on an empty fullscreen background, so the desktop and other windows stop competing for attention. **View → Hide terminal scrollbars in fullscreen** removes the scrollbars too. A transparent terminal background becomes opaque while fullscreen is active and returns to its saved level when you leave. Press ++ctrl+shift+f++ again to restore.

## Customizing the icon toolbar

The icon toolbar is visible by default, including when upgrading a configuration that has no toolbar settings yet. Its original vector icons use theme-colored outlines with small colored details, so they remain recognizable in light and dark designs. **View → Action toolbar** and **View → Connection toolbar** independently show or hide the two rows. The same switches and **Show/hide menu bar** are available when you right-click either row. If the menu bar is hidden, right-click the status bar to restore either toolbar. Both visibility preferences and button placement are shared across windows and survive restarts. Older configurations keep their previous visibility for both rows until you change them separately.

The first row groups **New Tab** and **New local shell** under Terminal; Connection Manager, SFTP, Snippet Manager, Session Journals, Logfile Manager, AI Manager, JobScheduler and Video Manager under Tools; and Font, Colors, Keyboard, Logs, Window, Terminal and AI shortcuts under Settings, in that order. The Global Settings button is available in the customization catalog but is not included in the default layout. **New Tab** opens Quick Connect. **New local shell** immediately starts a tab with the existing platform-default shell preset. Tooltips name each action and its current keyboard shortcut, where one exists. Buttons that do not fit remain reachable through the toolbar overflow menu. Set **Toolbar icon size (px)** from 16 to 64 in [Global Settings → Window](../reference/settings/window.md); the default is 24, and saving updates both rows and the customization catalog in every window.

When an older saved toolbar layout is loaded for the first time, korTTY automatically adds the Logfile Manager button after Session Journals in Tools, or at the end of Tools if Session Journals is absent. Existing buttons, separators, their order and the visibility of both rows are preserved. A Logfile Manager button you already placed stays where it is, without adding a duplicate. After this migration, removing the button through customization is remembered. In the same way, the Connection Manager button is added once at the start of Tools, or as a new Tools group at the end if the layout has none; a Connection Manager button you already placed stays where it is, and removing it afterwards is remembered as well.

![Icon toolbar with demo connection buttons](../assets/screenshots/main/icon-toolbar.png)

![Toolbar context menu with independent toolbar and menu-bar visibility switches](../assets/screenshots/main/icon-toolbar-context-menu.png)

Right-click the toolbar and choose **Customize toolbar…**. Both rows appear as editable destinations, including a row you have hidden, and a catalog opens underneath them, filtered by the name you type in its search field, with room for at least two complete button rows, including wrapped labels. Catalog buttons wrap at spaces or hyphens and widen for words that would otherwise be cut off. Finishing or cancelling customization restores each row’s saved visibility. Choose **Actions**, **Settings** or **Connections** in its category list. Click a catalog button to append it to its default section, or drag it to the desired position. The Settings category offers one shortcut for each main tab of Global Settings. **Add connection** opens the Connections category, populated from the Connection Manager. **Separator** adds a movable divider.

![Inline icon toolbar customization](../assets/screenshots/main/icon-toolbar-customize.png)

Drag an existing button to move it. Click or right-click it for **Remove button**, **Move left**, **Move right** and **Move to section**. With an editable button focused, ++alt+left++ / ++alt+right++ moves it and ++delete++ or ++backspace++ removes it. Actions can move among the three task sections; saved server and group buttons stay in the connection row. In this mode, clicking a destination button never starts its action or a connection.

**Finish** saves the edited layout. **Cancel** discards it. **Default** restores the standard layout in the editing preview, so it can still be cancelled. A deliberately empty configuration stays empty and offers **Customize toolbar…**. The connection row is hidden when empty during normal use and always available while customizing.

Server buttons show the saved connection name and connect through the existing sign-in flow. Group buttons show the full group path and open only its direct members, one tab each; they do not include subgroups. Existing policy restrictions remain enforced. As with Quick Connect's group action, password-authenticated group members without a stored password are skipped with a message. Renaming a group in the Connection Manager updates toolbar references. Deleted or unavailable targets remain disabled and can be removed or replaced while customizing. Toolbar settings store references, never copies of passwords or keys.

## Dashboard

Toggle the dashboard with ++ctrl+shift+d++ or **View → Show Dashboard**. It slides in on the left, sizes its width to the longest entry, and follows the active App Design's colors.

The header shows the panel title with two buttons: a collapse/expand toggle (collapses everything while any node is open, expands everything otherwise) and a refresh button. Below it, connections are organized as a tree:

- **Main window** — the root node, with an active/total session count.
- **Environments** — connections whose stored credential has an environment (for example *Production* or *Test*) are clustered under an environment node; connections without one sit directly under the main window.
- **Groups** — tabs assigned to a connection group appear under their group node. Saving a changed group in the Connection Manager updates open tabs immediately.

Each connection row shows a type icon, a status dot, the server name, and a protocol badge (`ssh`, `mosh` or `local`). The status dot distinguishes three states: filled green for a healthy connection, filled red for a connection that dropped unexpectedly (including a Mosh network interruption), and a hollow outline for a session that ended normally. Terminals with AI-agent runs carry the same ✋/⚡/⏸/✓ badge as elsewhere. Hovering a row shows `user@host` and the connection state; double-click (or ++enter++) focuses the session's tab.

Right-click a connection for **Focus**, **Duplicate**, **Reconnect**, **SFTP-Client...** (connected sessions only) and **Close**. **Close** asks first, like the tab's close button, when the session has split panes or a command is still running. **Multi-exec: Include All Panes of This Tab** on a connection row, and **Multi-exec: Include This Pane** on the row of one pane of a split tab, let those panes take part in [multi-exec](../features/terminal.md#multi-exec) from any window, without switching to them; the check mark shows whether they take part, and an amber fork icon marks their rows. A footer keeps a running "connected of total" count, and an empty panel shows a placeholder until the first session opens.

## macOS Dock menu

On macOS, right-click (or Control-click) the korTTY icon in the Dock for quick actions without switching to the app — they apply to the current (focused) window, opening one first if none is open:

- **New Window**
- **New Tab in Current Window**
- **Manage Connections…** (Connection Manager)
- **Open Project…**
- **Manual** (the in-app guide)
- **About korTTY**

See the [menu reference](../reference/menu.md) for every menu item and the [keyboard-shortcuts reference](../reference/keyboard-shortcuts.md) for the full list of accelerators.
