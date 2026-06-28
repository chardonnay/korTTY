---
title: SFTP file manager
---

# SFTP file manager

The integrated SFTP Manager provides a graphical file manager for transferring files between your local machine and remote servers via SFTP. It features a dual-panel layout, full file operations support, and seamless integration with the Snippet Editor for remote file editing.

## Opening SFTP Manager

You can open the SFTP Manager in two ways:

- **Menu:** Tools > Open SFTP Manager
- **Dashboard:** Right-click a connection > "Open SFTP Manager"

If the connection uses a temporary SSH key that has expired, you will be prompted to enter a new key before the connection can proceed.

## Interface

The SFTP Manager uses a **two-panel layout** for easy side-by-side file management:

| Left Panel (Local) | Right Panel (Remote) |
|----|---|
| Browse local files | Browse remote files |
| Upload to remote | Download to local |

### Sortable columns

Both panels display the same columns, all of which are sortable by clicking the column header:

| Column | Description |
|--------|---|
| **Name** | File or directory name |
| **Type** | Directory (📁) or file (📄) indicator |
| **Size** | File size in human-readable format (directories show —) |
| **Date** | Last modified date and time |
| **User** | Owner name (local: from filesystem; remote: from SFTP or UID) |
| **Group** | Group name (local: from filesystem; remote: from SFTP or GID) |
| **Permissions** | Unix-style permissions (e.g., `rwxr-xr-x`) |

## Default sort order

By default, files are sorted by the **Type** column in this order:

1. **Parent directory** (`..`) — always at the top
2. **Directories starting with "."** (e.g., `.git`, `.config`) — alphabetically
3. **Other directories** — alphabetically
4. **Files starting with "."** (e.g., `.bashrc`) — alphabetically
5. **All other files** — alphabetically

Click any column header to sort by that column. Clicking the Type column again toggles between ascending and descending order.

## File operations

The SFTP Manager supports a full range of file operations:

| Operation | How |
|-----------|-----|
| **Upload** | Select local file(s), click Upload (or drag and drop) |
| **Download** | Select remote file(s), click Download |
| **Delete** | Select file(s), click Delete |
| **Rename** | Select a file, click Rename |
| **Copy** | Copy files within the same panel |
| **Edit in Snippet Editor** | Select exactly one local or remote file, then use the *Edit* toolbar menu or the right-click context menu |
| **Create Directory** | Click "New Folder" in either panel |
| **Create ZIP** | Select multiple files/directories, click "Create ZIP" |
| **Set Owner/Permissions** | Select file(s), use context menu or button. Separate fields for User, Group, and octal permissions (e.g., 755) |

### Editing files with Snippet Editor

The **Edit in Snippet Editor** action is enabled only for a single selected file. It is disabled for:

- Folders
- The parent-directory entry (`..`)
- Multiple selections

Local files are read directly from the local filesystem; remote files are downloaded through the active SFTP session into the editor.

When the file opens in the Snippet Editor, the full toolbar remains available, including:

- Formatting and syntax checking
- Editor profiles and styling
- Line numbers and word wrap
- Configured AI actions

The file-mode buttons provide these save choices:

- **Overwrite file** — writes the current editor content back to the original local or remote file
- **Save as...** — writes a new local file through a file chooser, or for remote files prompts for a new file name in the same remote directory
- **Save as snippet** — stores the current content as a new Snippet Manager snippet without marking the source file as saved

![SFTP dual-panel file manager](../assets/screenshots/sftp/sftp-manager.png)

## Search

Both panels support **glob pattern search** using the `*` wildcard. For example:

- `*.log` finds all log files in the current directory
- `*.{py,sh}` finds Python and shell files (if your shell supports brace expansion)
- `backup*` finds all files starting with "backup"

Enter the pattern in the search field to quickly filter displayed files without leaving the current directory.

---

