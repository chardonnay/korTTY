---
title: Window
---

# Window

This tab configures window geometry behavior, dashboard state retention, menu bar visibility, the frame around the terminal of a colored connection, and whether terminal tabs take the title the shell sets. Open via **Configuration → Global Settings → Window**; stored in `~/.kortty/global-settings.xml`.

![Window settings tab](../../assets/screenshots/settings/window.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Remember window geometry | toggle | — | On | `rememberWindowGeometry` |
| Remember dashboard state | toggle | — | On | `rememberDashboardState` |
| Open tool windows as tabs | toggle | — | Off | `openToolWindowsAsTabs` |
| Frame the terminal in its connection's tab color | toggle | — | On | `connectionColorBorderEnabled` |
| Name terminal tabs after the title the shell sets | toggle | — | On | `tabTitleFromShellEnabled` |
| Use fixed window geometry | toggle | — | Off | `useFixedWindowGeometry` |
| Width: | number | 400–4000 | — | `fixedWindowGeometry.width` |
| Height: | number | 300–3000 | — | `fixedWindowGeometry.height` |
| X Position: | number | 0–5000 | — | `fixedWindowGeometry.x` |
| Y Position: | number | 0–3000 | — | `fixedWindowGeometry.y` |

With **Remember window geometry** enabled, KorTTY stores the position and size of every user-resizable application window and named dialog separately. Reopening a window restores the geometry chosen for that window type; if its previous monitor is no longer connected, KorTTY moves it back onto an available screen. On macOS, a main window's saved bounds are reapplied after its native unified title bar is ready so the system cannot shift the restored position while opening it. A changed UI font scale keeps the remembered position but lets the window calculate a fresh size so translated or enlarged labels still fit. Short-lived confirmations and progress notices keep their content-derived size.

!!! note
    When **Use fixed window geometry** is enabled, it takes precedence over **Remember window geometry** for main terminal windows. Dialogs continue to use their own remembered geometry.

!!! note
    The **Remember dashboard state** setting preserves whether the dashboard panel was open or closed the last time you closed the application, and restores that state on the next launch.

!!! note
    With **Open tool windows as tabs** enabled, management tools (Snippets, JobScheduler, AI Manager, Saved Chats, Session Journals, Credential/GPG/SSH key management, Video Manager, Teamwork settings, Terminal Effects) open as tabs in the main window instead of separate windows. The tab opens in the window whose menu you used, so with several main windows open each window collects its own tool tabs. Reopening a tool focuses its existing tab. The Snippet Manager is one tab per main window and opens the snippets you edit as tabs inside itself; a snippet editor opened from elsewhere (for example the SFTP Manager, the file browser or the terminal) and the session journal viewer open a new main-window tab each time, but a snippet that is already open in an editor is brought to the front instead of opening twice. The Full code analysis is a side panel inside the snippet editor, not a tab of its own. The setting takes effect the next time a tool is opened.

    A tool hosted as a tab has no separate window geometry. Its available size follows the main window and its saved main-window geometry.

## Tabs

**Frame the terminal in its connection's tab color** draws a 3-pixel frame around the terminal of every tab whose connection has a [tab color](../../features/connections.md#tab-color), in addition to the colored dot on the tab, so a production server stands out right where you type. The frame surrounds the whole tab content — all split panes and the status bars below them — and connections without a tab color never get one. Switch it off to keep only the dot. The change applies to the open tabs of every window as soon as you save.

!!! note
    The frame takes 3 pixels on each side of the terminal. Turning it on or off therefore resizes the open terminals of every colored connection, and giving a connection a tab color or removing it resizes that connection's terminals: the remote side receives the new size, and full-screen programs such as `vim`, `htop` or `less` redraw.

**Name terminal tabs after the title the shell sets** lets a terminal tab show the title that the shell or another program in it sets with the OSC 0 or OSC 2 escape sequence, such as `user@host: directory`, in place of the connection's name; a tab with split panes shows the title of its focused pane. A name you gave a tab with [Rename Tab](../../features/terminal.md#working-with-tabs) still comes first. The server decides this title, so it is cleaned of control and bidi characters, capped at 80 characters and never changes the tab color; pointing at such a tab shows the connection it belongs to. Switch it off to keep the connection's names on every tab. The change applies to the open tabs of every window as soon as you save. See [Title from the shell](../../features/terminal.md#title-from-the-shell).
