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

| Item | Shortcut | Description |
| --- | --- | --- |
| ASCII Art… | | FIGlet banner generator (11+ font styles) |
| Snippets… | | Snippet Manager (create, edit, organize, send, export) |
| AI Agent | ++ctrl+alt+a++ | Open the terminal AI agent |
| AI Manager | ++ctrl+shift+y++ | Manage AI profiles and saved chats |
| AI Planning | ++ctrl+alt+p++ | Open the AI planning workflow |
| Toggle Recording | ++ctrl+shift+e++ | Start/stop terminal recording |
| Video Manager | | Manage recordings and export to WebM/MKV via `ffmpeg` |

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
| Anleitung (Guide) | ++f1++ | Open this documentation inside korTTY |
| About korTTY | | Version and project information |
