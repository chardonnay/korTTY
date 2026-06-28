---
title: Terminal recording
---

# Terminal recording

KorTTY records terminal sessions as lightweight replay files with optional color capture and video export. Session recordings capture screen-state changes and timing events without continuous pixel capture, making them efficient and portable.

## Recording format and storage

Terminal recording saves session data into compressed JSONL replay files, one per terminal tab session:

- **Replay file format**: `.korttyrec.jsonl.gz` (JDK/GZIP streaming-compressed)
- **Legacy format**: `.korttyrec.jsonl` files remain readable (uncompressed)
- **Default storage**: `~/.kortty/recordings`
- **Content**: Screen-state changes, timing events, and optional color runs

!!! note
    Recordings do not continuously capture pixels and do not stream over the network. File size depends on activity level and whether color capture is enabled.

## Configuration

### Enable recording

To make recording available after every app restart:

1. Open **Settings > Video**
2. Enable **Enable terminal recording after app restart**
3. Click **Save**

To enable recording only until KorTTY quits:

1. Open **Tools > Video Manager**
2. Select **Enable terminal recording for this app session**
3. Click **Save**

### Configure storage and behavior

In **Tools > Video Manager**, set these options:

| Option | Description |
|--------|-------------|
| **Storage path** | Directory where replay files are saved (default: `~/.kortty/recordings`) |
| **Default format** | KorTTY replay format for new recordings |
| **Default split scope** | Record active split only or entire tab |
| **Auto-pause on idle** | Pause recording when terminal is inactive |
| **Idle threshold** | Seconds of inactivity before pause (default: 20) |
| **Capture colors** | Include per-cell terminal style runs in new recordings |
| **ffmpeg path** | Path to local `ffmpeg` binary for video export |

!!! tip
    Enable color capture *before* recording if you want exported videos to reproduce terminal colors accurately. Existing recordings without color data can still export, but colors won't be preserved.

### ffmpeg configuration

Video export requires a local `ffmpeg` installation:

1. In **Tools > Video Manager**, enter the full path to `ffmpeg` (or leave blank to auto-detect)
2. Click **Check** to verify availability
3. If `ffmpeg` is not found, video export remains disabled but replay viewing and other features work normally

On macOS and Linux, if `ffmpeg` is in your PATH, KorTTY auto-detects it. On Windows, you may need to provide the full path.

## Recording a session

### Start recording

1. Open or focus an SSH terminal tab
2. If recording is enabled, click **Start recording** in the terminal bar, or:
   - Choose **Tools > Start/Stop Terminal Recording**, or
   - Press ++ctrl+shift+e++ (++cmd+shift+e++ on macOS)
3. If the tab contains split terminals, choose:
   - **Active split** — record only the focused terminal pane
   - **Entire tab** — record all visible splits

The terminal bar control is hidden until recording is enabled or the menu item/shortcut is used.

### Stop recording

- Click **Stop recording** in the terminal bar, or
- Press ++ctrl+shift+e++ (++cmd+shift+e++ on macOS)

### Multiple segments

Start and stop recording as often as needed within the same tab. All segments append to the same replay file until the tab closes. Each recording start/stop creates a new segment in the file.

## Exporting videos

Exported videos require `ffmpeg` to be available and configured.

### Export options

1. Open **Tools > Video Manager**
2. Select a `.korttyrec.jsonl.gz` replay file
3. Verify that **ffmpeg status** says video export is enabled
4. Click **Export**
5. In the export dialog:
   - Choose **Export entire recording** or specify a custom time range
   - For custom ranges, enter start/end times as `MM` (minutes) or `MM:SS` (minutes and seconds)
   - Values past the replay duration are rejected
   - Choose whether to **Include terminal colors** (available only if the replay contains color data)
   - Select output format: **WebM/VP9** or **MKV/FFV1**
   - Choose output location

### Export progress

During export, a progress dialog shows:

- Current phase (frame rendering, video encoding)
- Progress bar
- Estimated remaining time

Export uses the recorded terminal geometry to avoid cropping wide terminal screens. If the geometry is not available, KorTTY computes a legacy fallback size.

## Viewing and managing recordings

### View a replay

1. Open **Tools > Video Manager**
2. Select a `.korttyrec.jsonl.gz` replay file
3. Click **View** to open the replay in the built-in viewer
4. Use the timeline controls:
   - **Timeline slider** — Drag to jump to any position
   - **Time jump** — Enter `5` for minute 5, `5:30` for 5 minutes 30 seconds
   - **Speed** — Set playback speed between 1x and 20x

### Rename a replay

1. Select a replay in **Tools > Video Manager**
2. Click **Rename**
3. Enter a new file name
4. The file is renamed in the storage folder

### Delete a replay

1. Select a replay in **Tools > Video Manager**
2. Click **Delete**
3. Confirm the deletion

## Recording events and JSONL format

Replay files contain timestamped events in JSONL format (one event per line, gzip-compressed). The decompressed JSONL may contain:

| Event | Description |
|-------|-------------|
| `recording_start` | Recording session began |
| `screen` | Screen-state change (may include `styleRuns` with foreground/background values) |
| `auto_pause` | Recording paused due to inactivity |
| `auto_resume` | Recording resumed after inactivity |
| `recording_stop` | Recording stopped manually |
| `session_closed` | Terminal tab closed |

Each screen event includes terminal content, dimensions, and optional color style runs.

## Tips and best practices

- **Large terminal windows**: Terminal geometry is recorded, so wide screens export correctly without cropping
- **Color accuracy**: Enable color capture before recording if you plan to export video with terminal colors
- **Idle pause**: Auto-pause helps reduce file size during long idle periods; enable it unless you need to preserve exact timing
- **Split-scope selection**: Choose your recording scope (active split vs. entire tab) each time you start recording to match your workflow
- **Legacy files**: Old `.korttyrec.jsonl` (uncompressed) replay files are still readable in the Video Manager