---
title: File browser
---

# File browser

The File Browser is a dockable sidebar that browses your **local** filesystem next to the terminal. It shows a directory tree with a navigation toolbar, a path bar, a filter, type-aware icons and a status footer. (For browsing a **remote** server over SFTP, use the [SFTP file manager](sftp.md) instead.)

## Opening and docking

Toggle the panel from the menu bar:

| Menu item | Shortcut |
|-----------|----------|
| **View → File Browser → Show on Left** | ++shift+cmd+b++ / ++shift+ctrl+b++ |
| **View → File Browser → Show on Right** | ++shift+cmd+r++ / ++shift+ctrl+r++ |

Selecting the item again hides the panel. A draggable divider resizes it (160–420 px). The panel's **position, width, "show hidden" state and last directory are remembered across restarts**.

## Navigation

The toolbar and path bar drive where the tree is rooted:

| Control | Action |
|---------|--------|
| Back / Forward | Return to a previously visited directory |
| Up | Root the tree at the parent directory (may go above your home directory) |
| Home | Root the tree at your home directory |
| Refresh | Reload the current directory, keeping expanded folders open |
| New folder / New file | Create an item in the selected (or current) directory |
| Sort | Choose the sort key (Name, Size, Date modified) and direction (Ascending / Descending) |
| Show hidden files | Toggle dotfiles on or off |

The **path bar** accepts a typed path and roots the tree there. A leading `~` expands to your home directory; absolute paths outside home are allowed. The **filter** field below it narrows the listing to entries whose name contains the typed text (case-insensitive).

Directories load in the background, so a large or network-mounted folder no longer freezes the window; a brief loading indicator appears while a directory is read.

## Keyboard shortcuts

When the tree has focus:

| Shortcut | Action |
|----------|--------|
| ++enter++ | Open a file, or expand/collapse a folder |
| ++f2++ | Rename the selected item inline |
| ++backspace++ | Go up to the parent directory |
| ++cmd+r++ / ++ctrl+r++ | Refresh |
| ++delete++ or ++cmd+backspace++ | Move the selection to the Trash |
| ++cmd+c++ / ++ctrl+c++, ++cmd+v++ / ++ctrl+v++ | Copy and paste files |
| ++cmd+f++ / ++ctrl+f++ | Jump to the filter field |

## File operations

Right-click an entry for the full menu:

- **Open** — open the file with the operating system's default application.
- **Open in Snippet Editor** — load a text file (up to 10 MB) into the [Snippet Editor](snippets.md).
- **Rename** — rename inline; a name clash is resolved by appending ` (2)`, ` (3)`, …
- **Copy** / **Cut** / **Paste** — move or copy within the browser.
- **Copy Path** — copy the item's absolute path to the clipboard.
- **Delete** — move the selection to the system Trash after a confirmation prompt (nothing is deleted permanently). If the operating system does not support Trash, the action reports that instead of deleting.
- **New Folder** / **New File** — create an item.
- **Set owner / group / permissions** — change ownership and POSIX permissions (where the filesystem supports it).
- **Archive** — pack the selection into a `ZIP`, `TAR` or `TAR.GZ` archive.
- **Details** — show type, size, path, modification time and permissions.

Each row shows a **type-aware icon** (folder, code, image, archive, document or executable), with a badge for symbolic links. The footer reports how many folders and files the current directory holds and how many entries are selected.

## Drag and drop

Drag files **out** of the browser onto another application to copy them, and drag files **into** the browser to copy or move them into a folder. Dropping onto a folder targets that folder; dropping elsewhere targets the current directory. Name clashes are resolved with a `(2)` suffix rather than overwriting.
