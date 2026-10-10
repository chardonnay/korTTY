---
title: File browser
---

# File browser

The File Browser is a dockable sidebar that browses your **local** filesystem next to the terminal. It is laid out like the terminal's remote files sidebar: a **Local Files** title bar with the actions, the current folder as clickable path segments, a filter, and a listing of one folder with **Name**, **Size** and **Modified** columns, type-aware icons and a status footer. (For browsing a **remote** server over SFTP, use the [SFTP file manager](sftp.md) instead.)

## Opening and docking

Toggle the panel from the menu bar:

| Menu item | Shortcut |
|-----------|----------|
| **View → File Browser → Show on Left** | ++shift+cmd+b++ / ++shift+ctrl+b++ |
| **View → File Browser → Show on Right** | ++shift+cmd+r++ / ++shift+ctrl+r++ |

Selecting the item again, or the **✕** button in the panel's title bar, hides the panel. A draggable divider resizes it (280–900 px, 380 px by default); the window keeps that width instead of squeezing the panel when the terminal needs room. The panel's **position, width, Show Hidden Files setting and last directory are remembered across restarts**.

## Navigation

The listing shows one folder at a time. **Double-click** a folder (or select it and press ++enter++) to change into it; the **..** row at the top goes up to the parent folder. The title bar's buttons:

| Control | Action |
|---------|--------|
| Back / Forward | Return to a previously visited directory |
| Up | Show the parent folder (may go above your home directory) |
| Home | Show your home directory |
| Refresh | Reload the folder shown, keeping the selection |
| New folder / New file | Create an item in the folder shown |
| Sort | Choose the sort key (Name, Size, Date modified) and direction (Ascending / Descending); a click on the **Name**, **Size** or **Modified** column header does the same, and a second click reverses the direction. Hidden entries (names starting with a dot) always stay at the top, then folders before files |
| Show hidden files | Toggle dotfiles on or off |
| ✕ | Hide the panel |

Below the title bar, the **path** shows the folder as segments, starting at `~` inside your home directory or at the filesystem root outside it; click a segment to go to that folder. Click beside the segments, or press ++cmd+l++ / ++ctrl+l++, to type a path instead: ++enter++ goes there, ++escape++ returns to the segments. A leading `~` expands to your home directory; absolute paths outside home are allowed. The **filter** field below it narrows the listing to entries whose name contains the typed text (case-insensitive).

Directories load in the background, so a large or network-mounted folder no longer freezes the window; a brief loading indicator appears while a directory is read.

## Keyboard shortcuts

When the listing has focus:

| Shortcut | Action |
|----------|--------|
| ++enter++ | Open a file, or change into a folder |
| ++f2++ | Rename the selected item inline |
| ++backspace++ | Go up to the parent directory |
| ++cmd+r++ / ++ctrl+r++ | Refresh |
| ++delete++ or ++cmd+backspace++ | Delete the selection (choose Trash or permanent) |
| ++cmd+c++ / ++ctrl+c++, ++cmd+v++ / ++ctrl+v++ | Copy and paste files |
| ++cmd+f++ / ++ctrl+f++ | Jump to the filter field |
| ++cmd+l++ / ++ctrl+l++ | Type a path to go to |

## File operations

Right-click an entry for the full menu:

- **Open** — open the file with the operating system's default application, or change into the selected folder.
- **Open in Snippet Editor** — load a text file (up to 10 MB) into the [Snippet Editor](snippets.md).
- **Rename** — rename inline; a name clash is resolved by appending ` (2)`, ` (3)`, …
- **Copy** / **Cut** / **Paste** — move or copy within the browser.
- **Copy Path** — copy the item's absolute path to the clipboard.
- **Delete** — a confirmation prompt offers **Move to Trash** or **Delete Permanently**. Moving to the Trash is reversible; permanent deletion cannot be undone. On a system without a Trash, only permanent deletion is offered. The deletion runs in the background so a large folder does not freeze the window.
- **New Folder** / **New File** — create an item.
- **Set owner / group / permissions** — change ownership and POSIX permissions (where the filesystem supports it).
- **Archive** — pack the selection into a `ZIP`, `TAR` or `TAR.GZ` archive.
- **Details** — show type, size, path, modification time and permissions.

Each row shows a **type-aware icon** (folder, code, image, archive, document or executable), with a badge for symbolic links, its size (`<DIR>` for folders) and when it was last modified. A name too long for the column ends in `...`; point at it to see the whole name in a tooltip, or select the entry (with a click or the arrow keys) to see it right below the row. The footer reports how many folders and files the folder shown holds and how many entries are selected.

## Drag and drop

Drag files **out** of the browser onto another application to copy them, and drag files **into** the browser to copy or move them into a folder. Dropping onto a folder row targets that folder; dropping elsewhere targets the folder shown. Name clashes are resolved with a `(2)` suffix rather than overwriting.
