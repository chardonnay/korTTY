---
title: Window
---

# Window

This tab configures window geometry behavior, dashboard state retention, and menu bar visibility. Open via **Configuration → Global Settings → Window**; stored in `~/.kortty/global-settings.xml`.

![Window settings tab](../../assets/screenshots/settings/window.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Remember window geometry | toggle | — | On | `rememberWindowGeometry` |
| Remember dashboard state | toggle | — | On | `rememberDashboardState` |
| Open tool windows as tabs | toggle | — | Off | `openToolWindowsAsTabs` |
| Use fixed window geometry | toggle | — | Off | `useFixedWindowGeometry` |
| Width: | number | 400–4000 | — | `fixedWindowGeometry.width` |
| Height: | number | 300–3000 | — | `fixedWindowGeometry.height` |
| X Position: | number | 0–5000 | — | `fixedWindowGeometry.x` |
| Y Position: | number | 0–3000 | — | `fixedWindowGeometry.y` |

!!! note
    When **Use fixed window geometry** is enabled, it takes precedence over **Remember window geometry**. The window will always open at the specified position and size, ignoring any previously saved geometry.

!!! note
    The **Remember dashboard state** setting preserves whether the dashboard panel was open or closed the last time you closed the application, and restores that state on the next launch.

!!! note
    With **Open tool windows as tabs** enabled, management tools (Snippets, JobScheduler, AI Manager, Saved Chats, Session Journals, Credential/GPG/SSH key management, Video Manager, Teamwork settings, Terminal Effects) open as tabs in the main window instead of separate windows. The tab opens in the window whose menu you used, so with several main windows open each window collects its own tool tabs. Reopening a tool focuses its existing tab; the snippet editor, the AI code analysis and the session journal viewer open a new tab each time. The setting takes effect the next time a tool is opened.
