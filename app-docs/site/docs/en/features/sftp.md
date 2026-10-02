---
title: SFTP file manager
---

# SFTP file manager

The integrated SFTP Manager provides a graphical file manager for transferring files between your local machine and remote servers via SFTP. It features a dual-panel layout, full file operations support, and seamless integration with the Snippet Editor for remote file editing.

## Opening SFTP Manager

You can open the SFTP Manager in two ways:

- **Menu:** *Connections → SFTP-Client...* (++ctrl+shift+u++, on macOS ++cmd+shift+u++). With a connected terminal tab in front, it opens for that tab's server; otherwise you pick a connection first.
- **Dashboard:** right-click a connected session > **SFTP-Client...**

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
| **Size** | File size in human-readable format (directories show —); sorting uses the exact byte count, with folders first |
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

Click any column header to sort by that column. Clicking the Type column again toggles between ascending and descending order. The parent directory (`..`) stays at the top whichever column and direction you sort by.

## Browsing the server

Remote folders are read in the background, so the window stays responsive even for a folder with tens of thousands of entries. While a folder loads, a **Loading…** indicator covers the remote list. The path field and the list switch to the new folder only once it has been read; if it cannot be read (it does not exist, or you lack the rights), an error is shown and the folder you were in stays.

Type a path into the remote **Path** field and press ++enter++ to go there. `~` and `~/…` stand for your login directory on the server, and a path without a leading `/` is taken relative to the folder shown; `..` and `.` are resolved, so `../logs` opens the sibling folder `logs`.

## Lost connection

When the server or the network ends the session, the status bar shows **Disconnected from** *host* with a **Reconnect** button, and the remote actions are disabled. If the connection drops without the server saying so, the first remote action you try (Refresh, Upload, Download and so on) brings up the same state instead of doing nothing.

**Reconnect** opens a new session with the same credentials and lists the folder you were in. The server's host key is checked as on the first connect: an unchanged key connects without a prompt, a changed key is still blocked.

## File operations

The SFTP Manager supports a full range of file operations:

| Operation | How |
|-----------|-----|
| **Upload** | Select local file(s), click Upload (or drag and drop) |
| **Download** | Select remote file(s), click Download |
| **Delete** | Select file(s), click Delete |
| **Rename** | Select a file, click Rename |
| **Copy** | Copy files within the same panel (a local copy runs in the background). A remote folder copied where a folder of that name exists merges into it; copying an item onto itself or into one of its own subfolders is refused with an error |
| **Edit in Snippet Editor** | Select exactly one local or remote file, then use the *Edit* toolbar menu or the right-click context menu |
| **Create Directory** | Click "New Folder" in either panel |
| **Create ZIP** | Select multiple files/directories, click "Create ZIP" |
| **Set Owner/Permissions** | Select file(s), use context menu or button. Separate fields for User, Group, and octal permissions (e.g., 755) |

### Uploading files and folders

Upload copies the selected local files and folders into the remote folder that was shown when you clicked **Upload**; browsing elsewhere while it runs does not change the target. Upload stays disabled until the first remote folder has been listed, because the server does not expand `~` itself.

- **Uploading a folder again merges** into the existing remote folder: files with the same name are replaced, other remote files are kept.
- **Files are streamed**, so their size is not limited by memory; files larger than 2 GB upload like any other.
- If the connection drops during an upload or download, the rest of the batch stops with one **Disconnected** state rather than an error per file.

### Permissions

The permissions field of **Set Owner/Permissions** and of the remote archive dialog accepts exactly three octal digits, such as `755` or `644`; leave it empty (or unchanged) to keep the current mode. Anything else keeps the dialog open with an error, so no other text ever reaches `chmod`.

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

