# Menu reference

Every item in korTTY's menu bar, with its shortcut (where defined) and what it
does. The menu bar can be hidden with ++ctrl+shift+l++ and shown again by
right-clicking the terminal or status bar.

## File

| Item | Shortcut | Description |
| --- | --- | --- |
| New Window | ++ctrl+shift+n++ | Open an additional, independent main window |
| New Tab | ++ctrl+t++ | Open Quick Connect in a new terminal tab |
| Open Project… | ++ctrl+o++ | Restore a saved project (windows, tabs, layout) |
| Save Project… | ++ctrl+s++ | Save the current session as a project (`.kortty`) |
| Quick Connect… | ++ctrl+k++ | Connect to a host without saving it first |
| Manage Connections… | | Open the Connection Manager |
| Import Connections… | | Import from MTPuTTY / MobaXterm / PuTTY CM |
| Export Connections… | | Export saved connections |
| Close Tab | ++ctrl+w++ | Close the active terminal tab |
| Close All Tabs | | Close every tab in the current window |
| Close Window | | Close the current window |
| Quit | ++ctrl+q++ | Exit korTTY |

## Edit

| Item | Shortcut | Description |
| --- | --- | --- |
| Cut | ++ctrl+x++ | Cut (disabled for terminal tabs) |
| Copy | ++ctrl+c++ | Copy the terminal selection |
| Paste | ++ctrl+v++ | Paste into the terminal |
| Select All | | Select all terminal content |
| Clear Terminal | | Clear the active terminal |
| Create Backup… | ++ctrl+shift+b++ | Create an encrypted backup (ZIP password or GPG) |
| Import Backup… | | Restore from a backup file |

## Connections

| Item | Description |
| --- | --- |
| Quick Connect… | Connect to a host without saving |
| Manage Connections… | Open the Connection Manager (tree, search, edit) |
| Import… | Import connections from other clients |
| Export… | Export connections |
| SFTP-Client… | Open the dual-panel SFTP file manager |

## Security

| Item | Description |
| --- | --- |
| Credentials… | Manage stored credentials (encrypted) |
| GPG-Keys… | Manage GPG keys used for backup encryption |
| SSH-Keys… | Manage SSH keys and passphrases |

## Configuration

| Item | Description |
| --- | --- |
| Global Settings… | Open the global Settings dialog (all tabs) |

See the [Settings reference](settings/index.md) for every individual setting.

## Tools

![Tools menu](../assets/screenshots/main/menu-tools.png)

| Item | Description |
| --- | --- |
| Snippet Manager… | Create, edit, organize, send and export command snippets |
| JobScheduler… | Schedule background command / snippet / AI-agent / AI-swarm / SFTP / Rsync jobs |
| Video Manager… | Manage terminal recordings and export to WebM/MKV via `ffmpeg` |
| Start/Stop Terminal Recording | Toggle recording of the active terminal (++ctrl+shift+e++) |
| ASCII Art… | FIGlet banner generator with multiple font styles |

## AI

![AI menu](../assets/screenshots/main/menu-ai.png)

| Item | Description |
| --- | --- |
| AI Manager… | Manage AI profiles and saved chats |
| AI Agent… | Open the terminal AI agent |
| AI Planning… | Open the AI planning workflow |
| AI Swarm… | Broadcast one AI task to many servers and compare the answers (++ctrl+alt+s++) |

**AI Manager** lists your AI profiles (each with connection mode, model,
reasoning effort, internet access and token budget) and your saved chats:

![AI Manager](../assets/screenshots/ai/ai-manager.png)

## Plugins

| Item | Description |
| --- | --- |
| Terminal Effects… | Enable/disable, configure, import/export terminal-effect plugins |

## View

| Item | Shortcut | Description |
| --- | --- | --- |
| Show Dashboard | ++ctrl+shift+d++ | Toggle the connections dashboard |
| Show Menu Bar | ++ctrl+shift+l++ | Toggle the menu bar |
| Show Command Timestamps | | Toggle inline command timestamps |
| Zoom In | ++alt+plus++ | Increase terminal font size |
| Zoom Out | ++alt+minus++ | Decrease terminal font size |
| Reset Zoom | ++alt+0++ | Reset terminal font size |
| Background Transparency | | Slider (0–100 %) that makes the terminal background see-through to the desktop while the text stays sharp; the value is saved across restarts. Switching it on or off needs a restart, so the status bar shows a hint when you cross that threshold. Shown in the in-window menu bar only. |
| Fullscreen | ++f11++ | Toggle window fullscreen |
| Terminal-only Fullscreen | ++f12++ | Hide all chrome, show only the terminal |
| Hide terminal scrollbars in fullscreen | | Also hide scrollbars while in fullscreen |
| AI Agent Panel ▸ At Bottom / Dock Left / Dock Right | | Choose where the AI agent activity panel lives |

## Teamwork

| Item | Description |
| --- | --- |
| Teamwork Settings… | Configure shared connection sources and synchronization |

## Help

| Item | Shortcut | Description |
| --- | --- | --- |
| Manual | ++f1++ | Open this documentation inside korTTY |
| About korTTY | | Version and project information |

## macOS Dock & menu bar

On macOS the packaged app keeps running in the background (so the JobScheduler
can run scheduled jobs) even after the last window is closed. korTTY therefore
adds two extra entry points so it stays reachable — and quittable — with no
window open:

- **Dock icon menu** — right-click korTTY's Dock icon for quick actions: **New
  Window**, **New Tab**, **Manage Connections…**, **Open Project…**, **Manual**,
  **About korTTY**, and **Quit**.
- **Menu-bar (status) icon** — a system-tray icon with **New Window** and
  **Quit**; clicking the icon opens a new window.

Both provide a reliable **Quit** even when every window is closed.
