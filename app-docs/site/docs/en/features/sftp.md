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

## Reopening with a project

SFTP Manager tabs are saved with a [project](projects.md#sftp-manager-tabs). Opening the project with **Auto-Reconnect** reconnects each tab at the local and remote folders it was in when you saved. A folder that no longer exists opens the home folder instead; on the remote side the status bar says so.

## File operations

Each panel has its own group of toolbar buttons below the lists (local on the left, remote on the right) and a right-click context menu:

| Operation | How |
|-----------|-----|
| **Upload** | Select local files or folders and click **Upload**, or drag them onto the remote panel; see [Drag and drop](#drag-and-drop) |
| **Download** | Select remote files or folders and click **Download**, or drag them onto the local panel |
| **Rename** | Select one entry and press ++f2++, or choose **Rename** in the context menu. An existing entry of the new name is never replaced; you get an error instead |
| **New Folder** | Click the folder button next to **Refresh**, or choose **New Folder** in the context menu, in either panel. The folder is created in the folder shown; a name that already exists is an error |
| **Delete** | Select entries and click **Delete**, choose **Delete** in the context menu, or press ++delete++ (++ctrl+backspace++, on macOS ++cmd+backspace++). You confirm before anything is deleted |
| **Copy** | **Copy to...** in the context menu copies within the same side: locally into a folder you pick (in the background), remotely into a folder path you type. A remote folder copied where a folder of that name exists merges into it; copying an item onto itself or into one of its own subfolders is refused with an error |
| **Edit in Snippet Editor** | Select exactly one local or remote file, then use the *Edit* toolbar menu or the right-click context menu |
| **Archive** | **Archive** in the remote toolbar, or **Archive...** in either context menu, packs the selection as ZIP, TAR.BZ2 or 7z, depending on the tools available on that side. A local archive can have a password; an archive on the server cannot, because `zip` and `7z` would need the password on the server's command line, where other users of the server can read it |
| **Extract Here** | Right-click one archive on the server (`.zip`, `.tar`, `.tar.gz`/`.tgz`, `.tar.bz2`, `.tar.xz` or `.7z`) and choose **Extract Here...**. It is unpacked on the server into a new folder next to it; see [Extracting archives on the server](#extracting-archives-on-the-server) |
| **Set Owner/Permissions** | Select entries, then click **Rights** or choose **Set Owner/Permissions...** in the context menu. Separate fields for User, Group, and octal permissions (e.g., 755) |

A name for **Rename** or **New Folder** must be a single entry in the folder shown: it may not be empty, `.` or `..`, or contain `/` or `\`. The dialog stays open with an error until the name is usable.

### Extracting archives on the server

**Extract Here...** in the remote context menu unpacks the selected archive on the server itself, so nothing is downloaded or uploaded. After you confirm, a progress window shows each step, and **Cancel** stops the extraction and removes what was already unpacked.

- **Always a new folder** — the files land in a new folder next to the archive, named after it without the extension (`site-1.2.tar.gz` becomes `site-1.2`). If that name is taken, korTTY uses `site-1.2 (1)`, `site-1.2 (2)` and so on; an existing file or folder is never overwritten or merged into.
- **Checked before anything is written** — korTTY first lists the archive and refuses it as a whole when an entry would land outside the new folder: an absolute path, `..` that climbs above the folder, a drive letter such as `C:`, a name with a line break, a device file, or a link whose target lies outside. Nothing is written in that case.
- **Checked again after unpacking** — the archive is unpacked into a hidden staging folder (`.kortty-extract.` plus random characters) that only you can open, without taking over the owners stored in the archive. korTTY then checks every symbolic link in it; if one points outside the folder, the staging folder is deleted and you get an error. Only a clean result is renamed to the new folder, so it appears complete or not at all.
- **Tools on the server** — ZIP needs `unzip`, the tar formats `tar`, and 7z `7z` or `7za`. If the tool is missing, the error names it. With a tar other than GNU tar (for example BusyBox), archives with hard links are refused, because their targets cannot be checked.
- **No passwords** — password-protected archives are refused with a message: the tools would need the password on the server's command line, where other users of the server can read it.

### Drag and drop

You can drag entries between the two panels, from your desktop into the SFTP Manager, and in small amounts from the server to your desktop. While you drag, the panel or the folder row that would take the drop is highlighted; dropping on a folder row puts the entries into that folder, dropping anywhere else into the folder shown.

| Drag | Result |
|------|--------|
| Local rows onto the remote panel | Uploaded, as with **Upload**: a file of the same name on the server is replaced, a folder merges |
| Files from Finder, Explorer or the file browser sidebar onto the remote panel | Uploaded the same way |
| Remote rows onto the local panel | Downloaded in the background, as with **Download**: folders included, and a local file of the same name is replaced |
| Files from Finder, Explorer or the file browser sidebar onto the local panel | Copied in the background; a name that is already there gets a number, as in `report (2).txt`, and a file dropped onto the folder it is in is left alone |
| Local rows onto the desktop or another program | Offered as the files themselves |
| Remote rows onto the desktop or another program | Only for at most 20 files with at most 16 MB together, and no folders |

A drag into the same panel does nothing; use **Copy to...** or **Rename** there.

!!! note "Dragging from the server to the desktop"
    The desktop can only take files that already exist when the drag starts. korTTY therefore downloads the dragged remote files into a private temporary folder first, which can hold the window for up to 5 seconds. Folders, more than 20 files, more than 16 MB or a download that takes longer can only be dropped inside the window; the status bar says so, and you drop them on the local panel instead. A link on the server counts as the file it points to, so a link to a large file is not offered to the desktop either. The temporary copies are deleted when you start the next drag or close the tab.

### Keys

These keys work in both panels:

| Key | Action |
|-----|--------|
| ++f2++ (in the list) | Rename the selected entry |
| ++delete++ or ++ctrl+backspace++ (++cmd+backspace++ on macOS, in the list) | Delete the selection, after confirmation |
| ++enter++ (in the **Path** field) | Go to the typed folder |

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

An organization can restrict editing server files with the `load-into-snippet-editor` key of its [enterprise policy](../reference/enterprise-policy.md). With `read-only`, remote files still open in the Snippet Editor and can be saved as a snippet, but **Overwrite file** and **Save as...** are locked and their tooltip names the policy. With `deny`, **Edit in Snippet Editor** and **Open image** are disabled for remote files. Local files in the left panel are on your own computer and stay editable either way.

![SFTP dual-panel file manager](../assets/screenshots/sftp/sftp-manager.png)

## Search

The search field above each list filters the folder shown as you type, without leaving it; the parent entry `..` stays visible. korTTY evaluates the wildcards itself, the same way for local and remote names. A pattern with a wildcard has to match the whole name, ignoring case:

| Wildcard | Matches | Example |
|----------|---------|---------|
| `*` | Any number of characters | `*.log` finds all log files, `backup*` all names starting with "backup" |
| `?` | Exactly one character | `data?.csv` finds `data1.csv`, not `data10.csv` |
| `[abc]`, `[a-z]` | One of the listed characters; `[!abc]` or `[^abc]` one that is not listed | `[ab]*` finds names starting with a or b |
| `{py,sh}` | One of the alternatives | `*.{py,sh}` finds Python and shell files |

Text without a wildcard matches anywhere in the name, ignoring case: `rep` finds `Report.txt`. A pattern that cannot be read, such as an unclosed `[`, is searched for as plain text.

### Searching subfolders on the server

Check **Include subfolders** next to the remote search field and press ++enter++ to search the remote folder shown and every folder below it, with the same wildcards and the same case-insensitive matching. The remote list switches to the results: each hit shows its path below the searched folder, and the line above the list counts the hits while the search runs and says how it ended, for example *120 results* or *5000 results, stopped at limit*. Double-click a hit, or select it and press ++enter++, to open its folder with the hit selected. **Back to folder** returns to the folder listing, and ++esc++ in the search field stops a running search (a second ++esc++ leaves the results).

| Limit | Value |
|-------|-------|
| Folder levels below the searched folder | 10 |
| Results | 5000 |
| Time | 60 seconds |

When the server lets korTTY run commands and has `find`, the search runs there and only the matching paths travel over the connection; otherwise korTTY walks the folders over SFTP, which is slower and stops after reading 200,000 entries. Symbolic links are listed but never followed, so a link that points back up the tree cannot make the search loop. **Stay on this file system** (on by default) keeps `find` out of other mounted file systems such as network shares and `/proc`; the walk over SFTP cannot tell file systems apart. Folders you cannot read are skipped. The search only reads names on the server, so it stays available when an enterprise policy blocks file transfers.

---

