---
title: Terminal sessions
---

# Terminal sessions

KorTTY provides a tabbed terminal interface with support for multiple simultaneous SSH connections, split-screen layouts, and interactive terminal management features. This guide covers tab operations, multi-window support, terminal customization, and advanced session features.

## Session lifecycle

The following diagram shows the terminal tab and session lifecycle, including split-screen and broadcast modes.

![Terminal session lifecycle](../assets/diagrams/session-lifecycle.svg)

## Working with tabs

Manage multiple SSH sessions with these tab operations:

| Action | Shortcut |
|--------|----------|
| **New Tab** | ++ctrl+t++ (Cmd+T on macOS) — opens Quick Connect to start a new session |
| **Close Tab** | ++ctrl+w++ (Cmd+W on macOS) — closes the active tab. You are only asked to confirm when there is something to lose: the tab has split panes, or a command is still running (a local shell with a running child process, or an SSH session that is not at its prompt). An idle single terminal closes immediately. The per-connection *Close without confirmation* setting suppresses the prompt entirely. |
| **Next Tab** | ++ctrl+Tab++ |
| **Previous Tab** | ++ctrl+shift+Tab++ |
| **Reconnect** | Right-click a tab, the terminal area, or a server entry in the Dashboard. If the connection is active, it is closed and re-established immediately; if disconnected, it is re-established. The terminal window stays open. |
| **Tab Groups** | Right-click a tab to assign it to a named group for better organization |

## Multi-window support

Open additional windows to organize connections by project or environment:

- **New Window**: ++ctrl+shift+n++ (Cmd+Shift+N on macOS) opens a new KorTTY window. Each window can have its own set of tabs and connections.
- **Move tabs between windows**: Drag a tab from the tab bar and drop it onto another KorTTY window's tab bar to move that tab (and its session, including any split terminals) into the other window.
- **Reorder tabs**: Drag a tab within the same window to change its order; the "+" tab stays at the end.

## Font size and zoom

Adjust the font size of the active terminal on the fly without reconnecting:

| Shortcut | Action |
|----------|--------|
| ++alt+plus++ | Zoom in (increase font size) |
| ++alt+minus++ | Zoom out (decrease font size) |
| ++alt+0++ | Reset zoom to saved/default font |
| ++ctrl++ + mouse wheel | Zoom in/out over the terminal (Cmd + wheel on macOS) |

Holding ++ctrl++ (or ++cmd++ on macOS) and scrolling the mouse wheel over the terminal changes the font size — wheel up enlarges, wheel down shrinks — instead of scrolling the buffer. This complements the ++alt+plus++ / ++alt+minus++ / ++alt+0++ shortcuts.

**Reset zoom** restores the font size and family to what the connection had when you opened the tab (or the connection's saved settings, or the global default). The same reset is available via the terminal context menu: right-click → **Font size** → **Reset**. The zoom level applies only to the currently focused terminal.

## Background transparency

**View → Zoom → Background Transparency** is a slider (0–100 %) that makes the terminal background see-through to the desktop while the text stays fully opaque and sharp. At 0 % the background is solid; higher values let more of the desktop show through. The value is saved and restored across restarts.

Only the terminal area becomes transparent — the title bar, menu bar, status bar and any tab without a terminal stay solid, so the window never turns into a see-through hole.

Horizontal, vertical and nested split terminals inherit the active transparency level, including panes added after transparency was enabled. Entering fullscreen with ++f11++ or terminal-only fullscreen with ++f12++ temporarily renders the terminal area opaque without changing the saved value; leaving fullscreen restores that value to every pane.

Because a see-through window uses a different window style that the operating system fixes when the window opens, **switching transparency on or off (crossing 0 %) only takes full effect after a restart**; the status bar shows a hint when you cross that threshold. Adjusting the level while already in transparent mode applies live. In transparent mode the window uses a lightweight custom title bar (drag to move, buttons to minimise/maximise/close, double-click the strip to maximise, drag the edges to resize).

The slider lives in the in-window menu bar only (the native macOS menu bar cannot host a slider).

## Local shell tabs

Besides SSH and Mosh, a terminal tab can host a **Local Shell** — the local machine's own shell, opened via a pseudo-terminal (see [Local Shell](connections.md#local-shell)). A few terminal behaviors are local-shell aware:

- **++ctrl+d++ closes the tab for local cmd.exe/PowerShell sessions.** Those Windows shells do not exit on EOF, so ++ctrl+d++ would otherwise have no effect. For bash-family shells (Git Bash/Cygwin/WSL, macOS/Linux) and SSH, ++ctrl+d++ keeps its normal EOF meaning — the shell exits and the local tab then auto-closes.
- **Close confirmation** uses local-shell wording rather than "End SSH connection?", and the window-close prompt is transport-neutral ("Active sessions"), since one window can mix SSH, Mosh and local-shell tabs.

## Split-screen with broadcast

Split the terminal view to display multiple connections side by side, and optionally send input to all panes at once.

### Split operations

- **Split Pane**: Create horizontal or vertical splits within a tab via the context menu or keyboard shortcuts.
- **Independent Sessions**: Each pane can show a different SSH connection.
- **Resizable Panes**: Drag dividers to adjust pane sizes.
- **Move Panes**: Hold ++shift+alt++ (Windows/Linux) or ++shift+option++ (macOS) and drag a pane onto another to reorder. Without the modifiers, mouse drag is used for text selection in the terminal.

### Broadcast mode

When **Broadcast Mode** is enabled, keyboard input is sent simultaneously to all visible panes. This is useful for running the same commands on multiple servers.

## Terminal effects

Terminal effects can change the visible terminal style and output animation. Effects are Java plugins managed from **Plugins > Terminal Effects**.

### User controls

- **Current terminal**: Use **View > Terminal Effect** or the terminal context menu to choose an effect for the active terminal.
- **Quick Connect**: Choose the effect and speed before opening a temporary or saved connection.
- **Connection Manager**: Store the effect and speed on a saved connection so new tabs use it automatically.
- **Speed**: Use the slider for `1x` through `10x`; if that is still too slow, type a custom value up to `99x` in the numeric speed field.

### Plugin management

- Open **Plugins > Terminal Effects** to manage plugins.
- The table lists loaded plugins with active state, name, and description.
- **Disable** a plugin to keep it installed but unavailable for activation.
- **Import** external `.jar` plugins. KorTTY copies them into `~/.kortty/plugins`.
- **Export** plugins that have a source JAR. The bundled MOTHER effect is exportable.

!!! warning
    Imported terminal-effect plugins are trusted Java code and are not sandboxed. Only import plugins from sources you trust.

For detailed plugin development documentation, see [Terminal effect plugins](terminal-effect-plugins.md).

## SSH keep-alive

Prevent connections from dropping due to inactivity by configuring SSH keep-alive messages:

1. Enable **SSH Keep-Alive** in the connection's **Terminal** tab or in **Settings > Terminal**.
2. Set the interval (5 to 600 seconds, default: 60).
3. KorTTY sends `SSH_MSG_IGNORE` heartbeat messages at the configured interval and enables TCP socket keepalive while the option is active.

!!! note
    If a server, firewall, VPN, or NAT gateway closes idle sessions sooner than the configured interval, the connection can still end. In that case, check the server-side SSH configuration and network idle-timeout settings as well as the KorTTY log.

## Terminal logging

Automatically log terminal session output for audit and debugging purposes:

1. Enable logging in the connection's **Terminal Logging** tab.
2. Choose a log format:
   - **Plain Text** - Raw terminal output.
   - **XML** - Structured XML with timestamps.
   - **JSON** - Structured JSON with timestamps.
3. Set a **maximum file size** (default: 10 MB). When exceeded, the log file is rotated.
4. Logs are stored in `~/.kortty/history/` as compressed files.

## Terminal recording

Terminal recording is designed as a low-resource replay feature. KorTTY records terminal screen-state changes and timing events into one JDK/GZIP streaming-compressed `.korttyrec.jsonl.gz` file per terminal tab session. Legacy `.korttyrec.jsonl` replay files remain readable.

### Configure recordings

1. To enable recording automatically after every app restart, open **Settings > Video** and enable **Enable terminal recording after app restart**.
2. To enable recording only for this session, open **Tools > Video Manager...** and select **Enable terminal recording for this app session**.
3. Set the **Storage path**. If left at the default, KorTTY uses `~/.kortty/recordings`.
4. Choose the default format and default split scope. KorTTY replay is always available; video export requires `ffmpeg`.
5. Enable or disable **Auto-pause when the terminal is idle** and set the idle threshold (default: 20 seconds).
6. Optional: enable **Capture terminal colors in new recordings** if exported videos should reproduce terminal colors.
7. Optional: set the `ffmpeg` path and click **Check**. If `ffmpeg` is missing, video export stays disabled but replay files remain usable.
8. Click **Save**.

### Start and stop recording

1. Open or focus an SSH terminal tab.
2. If terminal recording is enabled, click **Start recording** in the terminal bar, choose **Tools > Start/Stop Terminal Recording**, or press ++ctrl+shift+e++ (Cmd+Shift+E on macOS).
3. If the tab contains multiple split terminals, choose whether to record only the active split or the whole tab.
4. Click **Stop recording** or press ++ctrl+shift+e++ again to stop the current segment.
5. Start and stop as often as needed in the same tab. KorTTY appends all segments to the same replay file until the tab closes.

### Export a video

1. Open **Tools > Video Manager...**.
2. Select a `.korttyrec.jsonl.gz` replay file from the list.
3. Verify that the ffmpeg status says video export is enabled.
4. Click **Export...**.
5. In the export options:
   - Choose **Export entire recording** or enter start/end times with minute or `MM:SS` format.
   - Choose whether to include terminal colors (available only if the replay contains color data).
   - Choose **WebM/VP9** or **MKV/FFV1** format, then select an output path.
6. While KorTTY renders frames and runs `ffmpeg`, the export progress dialog shows the current phase, progress bar, and estimated remaining time. Export uses the recorded terminal geometry so large terminal screens are not cropped.

### View and manage recordings

1. Open **Tools > Video Manager...**.
2. Select a `.korttyrec.jsonl.gz` replay file.
3. Click **View** to play the replay directly inside KorTTY.
4. Use the replay viewer timeline to scrub, or enter a **Time jump** value such as `5` for minute 5 or `5:30` for minute 5 and 30 seconds.
5. Set **Speed** between `1x` and `20x` to control playback speed.
6. Click **Rename...** to rename the replay file.
7. Click **Delete** to delete the selected replay after confirmation.
