# KorTTY User Guide

> **Version 1.5.0** | [Back to README](../README.md)

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Getting Started](#2-getting-started)
3. [Connections](#3-connections)
4. [Terminal](#4-terminal)
5. [SSH Tunnels](#5-ssh-tunnels)
6. [Jump Server (Bastion Host)](#6-jump-server-bastion-host)
7. [SFTP Manager](#7-sftp-manager)
8. [Snippet Manager](#8-snippet-manager)
9. [ASCII Art Banner](#9-ascii-art-banner)
10. [Credential Management](#10-credential-management)
11. [SSH Key Management](#11-ssh-key-management)
12. [GPG Key Management](#12-gpg-key-management)
13. [Backup and Restore](#13-backup-and-restore)
14. [Projects](#14-projects)
15. [Settings](#15-settings)
16. [Keyboard Shortcuts](#16-keyboard-shortcuts)
17. [Configuration Files](#17-configuration-files)
18. [Troubleshooting](#18-troubleshooting)

---

## 1. Introduction

KorTTY is a modern, cross-platform SSH client built with JavaFX. It provides a tabbed terminal interface with a dark theme, advanced connection management, and tools for everyday server administration.

### Key Highlights

- **Tabbed terminals** with split-screen and broadcast input
- **Encrypted credential storage** using AES-256-GCM with a master password
- **Project workspaces** that save and restore your complete session
- **SFTP file manager** with a dual-panel view
- **Code snippet manager** with syntax highlighting, placeholder variables, and import/export
- **ASCII Art Banner** generator with 11+ FIGlet font styles
- **SSH tunnels** (local, remote, dynamic) and jump server support
- **Backup and restore** with password-protected ZIP or GPG encryption
- **8 languages**: English, German, Italian, Spanish, Portuguese, French, Croatian, Dutch
- **JMX monitoring** for active connections and memory usage

### System Requirements

| Requirement | Minimum |
|---|---|
| Java | 25 or higher |
| Gradle | 9.x (included via wrapper) |
| OS | macOS, Windows, Linux |

---

## 2. Getting Started

### 2.1 Installation

#### Build from Source

```bash
git clone https://github.com/your-org/korTTY.git
cd korTTY
./gradlew build
```

#### Run Directly

```bash
./gradlew run
```

#### Pre-built Binaries (GitHub Releases)

Ready-to-use packages are published on [GitHub Releases](https://github.com/chardonnay/korTTY/releases). Each asset name includes the architecture (e.g. `-x86_64` or `-aarch64`). Choose the file that matches your system:

- **macOS**: Apple Silicon only — use `-aarch64`.
- **Windows**: Use `-x86_64` for Intel/AMD.
- **Linux**: Use `-x86_64` for Intel/AMD, or `-aarch64` for ARM (e.g. Raspberry Pi 4, many cloud instances). Packages include `.deb`, `.rpm`, `.tar.gz`, and `.zip`.

#### Building Native Packages Locally

KorTTY can be packaged as a native application using `jpackage`. The output matches the architecture of the machine you build on (x86_64 or arm64):

| Platform | Command | Output |
|---|---|---|
| macOS (.app) | `./gradlew jpackage` | `build/jpackage/korTTY.app` |
| macOS (.dmg) | `./gradlew jpackageDmg` | `build/jpackage/korTTY-1.3.0.dmg` |
| Windows (.exe) | `gradlew.bat jpackage` | `build\jpackage\korTTY\` |
| Windows (.msi) | `gradlew.bat jpackageMsi` | `build\jpackage\korTTY-1.3.0.msi` |
| Linux (AppImage) | `./gradlew jpackage` | `build/jpackage/korTTY/` |
| Linux (.deb) | `./gradlew jpackageDeb` | `build/jpackage/korTTY-1.3.0.deb` |
| Linux (.rpm) | `./gradlew jpackageRpm` | `build/jpackage/korTTY-1.3.0.rpm` |

### 2.2 First Launch - Master Password

On the first launch, KorTTY asks you to create a **master password**. This password encrypts all stored connection passwords, SSH key passphrases, and credentials.

1. Enter a password (minimum 6 characters). The field border turns green when the length is sufficient and red when too short. A strength indicator shows the quality. If you choose a weak or common password, a warning is shown but you can still use it if you confirm.
2. Confirm the password.
3. Click **Setup**.

On subsequent launches, you are prompted to enter the master password to unlock your encrypted data. You can disable this prompt in **Settings > Security**, but stored passwords will not be accessible until you enter the master password manually.

### 2.3 Main Window Overview

```
+---------------------------------------------------------------+
| Menu Bar: File | Edit | Connections | Management | Tools |    |
|           View | Help                                         |
+---------------------------------------------------------------+
| Tab Bar: [Server-1] [Server-2] [Server-3]            [+]     |
+-------------------+-------------------------------------------+
| Dashboard         |                                           |
| (optional)        |           Terminal Area                    |
|                   |                                           |
| - Server-1 (ok)  |   user@server:~$                          |
| - Server-2 (ok)  |                                           |
| - Server-3 (err) |                                           |
+-------------------+-------------------------------------------+
| Status Bar                                                    |
+---------------------------------------------------------------+
```

- **Menu Bar** - Access all features through menus and keyboard shortcuts.
- **Tab Bar** - Each SSH session runs in its own tab. Ctrl+Tab / Ctrl+Shift+Tab to switch.
- **Dashboard** - Toggle with Ctrl+Shift+D. Shows all open connections with status indicators. Right-click a connection to reconnect, duplicate, or open SFTP.
- **Status Bar** - Shows connection status, temporary SSH key timer, and connection duration.
---

## 3. Connections

### 3.1 Quick Connect

Open with **File > Quick Connect** or press **Ctrl+K** (Cmd+K on macOS).

The Quick Connect dialog has three sections:

1. **Frequently Used Connections** - The top 10 most-used connections appear as quick-access buttons at the top. Click one to connect immediately.

2. **Individual Connection** tab:
   - Select a saved connection from the dropdown, or enter host, port, and username manually.
   - Choose an authentication method (Password, SSH Key, or Temporary SSH Key).
   - Optionally customize terminal appearance (font, size, colors).
   - Check **Save Connection** to add the connection to your library.

3. **Open Group** tab:
   - Select a group from the list.
   - All connections in that group open simultaneously in separate tabs.

### 3.2 Connection Manager

Open with **Connections > Manage Connections** or press **Ctrl+M** (Cmd+M on macOS).

The Connection Manager provides a tree view of all saved connections organized in groups (folders).

**Key actions:**

| Button | Description |
|---|---|
| **New** | Create a new connection |
| **Edit** | Edit the selected connection |
| **Delete** | Delete selected connection(s) or folder |
| **Duplicate** | Create a copy of the selected connection |
| **Create Folder** | Create a new folder (at root, or under the selected folder) |
| **Rename Folder** | Rename the selected group/folder |
| **Undo** | Undo the last move operation |
| **Import** | Import connections from file |
| **Export** | Export connections to file |

**Context menu (empty area):** Right-click in the free space of the connection tree (not on a folder or connection) to open a context menu with **Create folder** and **Create connection** (New…).

**Searching:** Type in the search field to filter connections. Wildcard patterns are supported (e.g., `prod*` matches all connections starting with "prod").

**Drag and Drop:** Drag connections between groups to reorganize them.

### 3.3 Creating / Editing a Connection

When you create or edit a connection, a dialog with multiple tabs opens:

#### Connection Tab
- **Name** - Display name for the connection.
- **Host** - Server hostname or IP address.
- **Port** - SSH port (default: 22).
- **Username** - Login username.
- **Group** - Folder/group to organize the connection (use `/` for nested groups, e.g., `Production/Web`).
- **Authentication Method:**
  - **Password** - Enter the password directly (stored encrypted).
  - **Private Key** - Select an SSH key from your key management. If the key has a passphrase (stored with the key or entered in the passphrase field), it is saved with the connection (encrypted) and used automatically when connecting.
  - **Temporary SSH Key** - Paste a time-limited key (e.g., from CyberArk) with an expiration timer.
- **Credentials** - Select stored credentials from the credential manager.
- **Connection Timeout** - Maximum wait time in seconds (default: 15).
- **Retries** - Number of reconnection attempts on failure (default: 4).

#### Terminal Settings Tab
- Override global font, colors, and terminal size for this specific connection.
- **Close Confirmation** - Ask before closing this tab.

#### SSH Tunnels Tab
- Add, edit, or remove SSH tunnels (see [SSH Tunnels](#5-ssh-tunnels)).

#### Jump Server Tab
- Configure a bastion host (see [Jump Server](#6-jump-server-bastion-host)).

#### Terminal Logging Tab
- Enable/disable automatic session logging.
- Configure log file path, maximum file size, and format (Plain Text, XML, or JSON).

#### Window Geometry Tab
- Set a fixed window size/position for this connection.

### 3.4 Import / Export Connections

KorTTY can import connections from several sources:

| Format | Menu Path |
|---|---|
| KorTTY (XML/ZIP/GPG) | Connections > Import |
| MTPuTTY (servers.xml) | Connections > Import |
| MobaXTerm (.ini) | Connections > Import |
| PuTTY Connection Manager (.xml) | Connections > Import |

**Import options** include whether to import usernames, passwords, tunnels, jump servers, and whether to replace with stored credentials or SSH keys.

**Exporting** supports KorTTY, MTPuTTY, MobaXTerm, and PuTTY CM formats. You can select individual connections and optionally encrypt the export (password ZIP or GPG).

> **Note:** Passwords are never included when importing from third-party formats for security reasons.

---

## 4. Terminal

### 4.1 Working with Tabs

- **New Tab:** Ctrl+T (Cmd+T) opens Quick Connect to start a new session.
- **Close Tab:** Ctrl+W (Cmd+W) closes the active tab (with optional confirmation).
- **Switch Tabs:** Ctrl+Tab (next) / Ctrl+Shift+Tab (previous).
- **Tab Groups:** Right-click a tab to assign it to a named group for better organization.

### 4.2 Multi-Window

Open additional windows with **File > New Window** (Ctrl+Shift+N / Cmd+Shift+N). Each window can have its own set of tabs and connections.

### 4.3 Font Size / Zoom

Adjust the font size of the active terminal without reconnecting:

| Shortcut | Action |
|---|---|
| Alt+Plus | Zoom in (increase font size) |
| Alt+Minus | Zoom out (decrease font size) |
| Alt+0 | Reset zoom to default |

The zoom level applies only to the currently focused terminal.

### 4.4 Split-Screen with Broadcast

Split the terminal view to display multiple connections side by side:

- **Split Pane:** Create horizontal or vertical splits within a tab.
- **Broadcast Mode:** When enabled, keyboard input is sent simultaneously to all visible panes. This is useful for running the same commands on multiple servers.
- **Independent Sessions:** Each pane can show a different SSH connection.
- **Resizable Panes:** Drag dividers to adjust pane sizes.

### 4.5 Terminal Logging

Automatically log terminal session output for audit and debugging:

1. Enable logging in the connection's **Terminal Logging** tab.
2. Choose a log format:
   - **Plain Text** - Raw terminal output.
   - **XML** - Structured XML with timestamps.
   - **JSON** - Structured JSON with timestamps.
3. Set a **maximum file size** (default: 10 MB). When exceeded, the log file is rotated.
4. Logs are stored in `~/.kortty/history/` as compressed files.

### 4.6 SSH Keep-Alive

Prevent connections from dropping due to inactivity:

1. Enable **SSH Keep-Alive** in the connection's Terminal tab or in **Settings > Terminal**.
2. Set the interval (5 to 600 seconds, default: 60).
3. KorTTY sends `SSH_MSG_IGNORE` heartbeat messages at the configured interval.

---

## 5. SSH Tunnels

SSH tunnels securely forward traffic between local and remote ports through an encrypted SSH connection.

### 5.1 Configuring Tunnels

1. Open a connection for editing (**Connection Manager > Edit**).
2. Go to the **SSH Tunnels** tab.
3. Click **Add Tunnel** and fill in:

| Field | Description |
|---|---|
| **Type** | Local (`-L`), Remote (`-R`), or Dynamic (`-D`) |
| **Local Host** | Local bind address (usually `localhost`) |
| **Local Port** | Local port number |
| **Remote Host** | Target host (from the SSH server's perspective) |
| **Remote Port** | Target port on the remote host |
| **Description** | Optional label |
| **Enabled** | Toggle on/off without deleting |

### 5.2 Tunnel Types

**Local Port Forwarding (-L):** Forward a local port to a remote service.
```
Your machine:8080  --->  SSH Server  --->  database-server:5432
```
Access the remote database at `localhost:8080`.

**Remote Port Forwarding (-R):** Make a local service accessible from the remote side.
```
Remote:9090  --->  SSH Server  --->  Your machine:3000
```
Access your local dev server from the remote machine at `localhost:9090`.

**Dynamic Port Forwarding (-D):** Create a SOCKS proxy.
```
Your machine:1080  --->  SSH Server  --->  (any destination)
```
Configure your browser or application to use `localhost:1080` as a SOCKS5 proxy.

---

## 6. Jump Server (Bastion Host)

A jump server (bastion host) acts as an intermediate gateway to reach servers on a private network.

### Configuration

1. Edit a connection and go to the **Jump Server** tab (or **Advanced** tab).
2. Enable **Jump Server**.
3. Enter the jump server's details:
   - **Host** and **Port**
   - **Username**
   - **Authentication** (Password or SSH Key)
4. Optionally, set an **Auto-Command** to execute after connecting to the jump server (e.g., `ssh internal-server`).

### How It Works

```
Your Machine  --->  Jump Server (bastion)  --->  Target Server
```

KorTTY first establishes an SSH connection to the jump server, then uses it to connect to the target server. All traffic is routed through the jump server.

---

## 7. SFTP Manager

The integrated SFTP Manager provides a graphical file manager for transferring files between your local machine and remote servers.

### 7.1 Opening SFTP Manager

- **Menu:** Tools > Open SFTP Manager
- **Dashboard:** Right-click a connection > "Open SFTP Manager"

If the connection uses a temporary SSH key that has expired, you will be prompted to enter a new key.

### 7.2 Interface

The SFTP Manager uses a **two-panel layout**:

| Left Panel (Local) | Right Panel (Remote) |
|---|---|
| Browse local files | Browse remote files |
| Upload to remote | Download to local |

Both panels display file name, size, date, and permissions in sortable columns.

### 7.3 File Operations

| Operation | How |
|---|---|
| **Upload** | Select local file(s), click Upload (or drag and drop) |
| **Download** | Select remote file(s), click Download |
| **Delete** | Select file(s), click Delete |
| **Rename** | Select a file, click Rename |
| **Copy** | Copy files within the same panel |
| **Create Directory** | Click "New Folder" in either panel |
| **Create ZIP** | Select multiple files/directories, click "Create ZIP" |
| **Change Permissions** | Select a remote file, click Permissions (chmod interface) |

### 7.4 Search

Both panels support **glob pattern search** using the `*` wildcard. For example, `*.log` finds all log files in the current directory.

---

## 8. Snippet Manager

The Snippet Manager lets you store, organize, and quickly insert reusable code snippets, scripts, and configuration templates.

### 8.1 Opening the Snippet Manager

- **Menu:** Tools > Snippet Manager
- **Shortcut:** Ctrl+Shift+S (Cmd+Shift+S on macOS)

### 8.2 Interface Overview

```
+-----------------------------------------------------------+
| Search: [____________]   Category: [All Categories v]     |
+-----------------------------------------------------------+
| Name        | Language | Tags      | Fav | Used           |
|-------------|----------|-----------|-----|----------------|
| deploy.sh   | Bash     | docker    | *   | 12             |
| db-backup   | SQL      | postgres  |     | 5              |
| nginx.conf  | Conf     | web,proxy | *   | 8              |
+-----------------------------------------------------------+
| Preview:                                    [ ] Word Wrap |
| #!/bin/bash                                               |
| docker-compose -f $file up -d                             |
+-----------------------------------------------------------+
| [Add] [Edit] [Delete] [Copy] [Insert Editor]             |
| [Insert Terminal] [Toggle Fav] [Import] [Export]          |
| [Variables...]                                            |
+-----------------------------------------------------------+
```

### 8.3 Creating and Editing Snippets

1. Click **Add** (or **Edit** to modify an existing snippet).
2. Fill in the fields:
   - **Name** - A descriptive name.
   - **Language** - Select the programming language (Bash, Python, Java, JavaScript, SQL, XML, JSON, YAML, and more). This enables syntax highlighting.
   - **Category** - Select an existing category or type a new one.
   - **Tags** - Comma-separated keywords for searching (e.g., `docker, deploy, backup`).
   - **Content** - The snippet code. The editor provides live syntax highlighting based on the selected language.
3. Click **Save**.

### 8.4 Organizing Snippets

- **Categories:** Group snippets into categories (e.g., "Deployment", "Database", "Monitoring"). Filter by category using the dropdown.
- **Tags:** Add multiple tags per snippet. Search by tags using the search field.
- **Favorites:** Click the star icon or use **Toggle Favorite** to mark frequently used snippets.
- **Usage Statistics:** The "Used" column tracks how many times each snippet has been inserted.

### 8.5 Searching

The search field supports **glob patterns**:

| Pattern | Matches |
|---|---|
| `deploy*` | All snippets starting with "deploy" |
| `*docker*` | All snippets containing "docker" |
| `*.sh` | All snippets ending with ".sh" |
| `db-*-backup` | e.g., "db-full-backup", "db-incremental-backup" |

Search matches against snippet name, tags, and content.

### 8.6 Multi-Selection

Hold **Ctrl** (Cmd on macOS) or **Shift** to select multiple snippets. This enables:
- **Bulk Delete** - Delete all selected snippets at once.
- **Bulk Export** - Export only the selected snippets.
- **Bulk Favorite Toggle** - Toggle favorite status for all selected.

### 8.7 Placeholder Variables

Snippets can contain placeholder variables that are replaced when you insert the snippet.

#### Built-in Variables

| Variable | Replaced With |
|---|---|
| `${date}` | Current date (e.g., `2025-01-15`) |
| `${time}` | Current time (e.g., `14:30:00`) |
| `${datetime}` | Current date and time |
| `${hostname}` | Local hostname |
| `${username}` | Current OS username |
| `${clipboard}` | Current clipboard content |
| `${cursor}` | Cursor position after insertion |

#### Custom Variables

Any `${variableName}` not in the built-in list is treated as a custom variable.

**Example snippet:**
```bash
ssh ${deploy_user}@${deploy_host} "cd ${app_dir} && git pull && systemctl restart ${service_name}"
```

When you insert this snippet:
1. KorTTY checks the **Variable Manager** for stored values.
2. Variables with stored values are filled in automatically.
3. For variables without stored values, a prompt dialog appears asking you to enter the values.
4. New values you enter are saved for future use.

### 8.8 Custom Variable Management

Click the **Variables...** button in the Snippet Manager to open the Variable Management dialog.

Here you can:
- **Add** a new variable with a name and default value.
- **Edit** an existing variable's value.
- **Delete** variables you no longer need.

Stored variable values are:
- Automatically used when inserting snippets containing matching `${variableName}` placeholders.
- Included in application backups (stored in `~/.kortty/snippet-variables.xml`).

This is especially useful when a colleague shares a snippet with variables. If you have already stored values for those variable names, the snippet is filled in automatically without prompting.

### 8.9 Import / Export

Snippets can be imported and exported in three formats:

| Format | File Extension |
|---|---|
| JSON | `.json` |
| XML | `.xml` |
| YAML | `.yaml` / `.yml` |

- **Export:** Select snippets (or export all), click **Export**, choose the format and destination.
- **Import:** Click **Import**, select a file. Imported snippets are merged with existing ones.

### 8.10 Preview Options

- **Word Wrap:** Check the "Word Wrap" checkbox above the preview area to wrap long lines. This setting is persistent and remains active across application restarts until you disable it. Word wrap is **disabled by default**.
- **Scrollbars:** The preview area displays vertical and horizontal scrollbars when content overflows.

### 8.11 Inserting Snippets

| Button | Action |
|---|---|
| **Copy to Clipboard** | Copies the snippet (with variables resolved) to the system clipboard |
| **Insert into Editor** | Inserts the snippet into the currently active file editor tab |
| **Send to Terminal** | Sends the snippet content to the active terminal session |

All three actions resolve placeholder variables before insertion.

### 8.12 Snippet Editor Settings

The Snippet Manager and Snippet Editor have their own appearance settings, separate from the global terminal settings:

1. Open **Settings** (Ctrl+Comma / Cmd+Comma).
2. Go to the **Snippet Editor** tab.
3. Configure:
   - **Font Family** and **Font Size** (set to 0 to inherit from global terminal settings)
   - **Foreground Color** and **Background Color**
   - **Cursor Style** (Block, Line, or Underscore)
   - **Cursor Color**

These settings apply to both the Snippet Manager preview and the Snippet Edit dialog.

---

## 9. ASCII Art Banner

Generate ASCII art text banners using FIGlet fonts. Useful for login banners, script headers, or decorative text.

### 9.1 Opening

**Menu:** Tools > ASCII Art Banner

### 9.2 Usage

1. Select a **Style** from the dropdown. Available styles:

   | Style | Description |
   |---|---|
   | Standard | Default FIGlet font |
   | Slant | Italic/slanted characters |
   | 3-D | Three-dimensional block letters |
   | Banner | Large banner-style letters |
   | Big | Large, clear letters |
   | Block | Solid block letters |
   | Cosmic | Space-themed style |
   | Digital | Digital/LED display style |
   | Lean | Thin, lean characters |
   | Roman | Classical Roman style |
   | Script | Cursive script style |
   | Small | Compact small letters |

2. Type your text in the **Text** input field.
3. The ASCII art preview updates in real time.
4. Click **Copy to Clipboard** to copy the result.

**Example output (Standard style):**
```
 _   _      _ _        __        __         _     _ _
| | | | ___| | | ___   \ \      / /__  _ __| | __| | |
| |_| |/ _ \ | |/ _ \   \ \ /\ / / _ \| '__| |/ _` | |
|  _  |  __/ | | (_) |   \ V  V / (_) | |  | | (_| |_|
|_| |_|\___|_|_|\___/     \_/\_/ \___/|_|  |_|\__,_(_)
```

---

## 10. Credential Management

Centrally manage username/password credentials that can be reused across connections.

### 10.1 Opening

**Menu:** Management > Manage Credentials

### 10.2 Adding Credentials

1. Click **Add**.
2. Fill in:
   - **Name** - A descriptive label (e.g., "Production Admin").
   - **Username** - The login username.
   - **Password** - Stored encrypted with AES-256-GCM.
   - **Environment** - Production, Development, Test, or Staging.
   - **Server Pattern** - Optional glob pattern for automatic matching (e.g., `*.prod.example.com` or `10.0.1.*`).
   - **Description** - Optional notes.

### 10.3 Automatic Matching

When a server pattern is set, KorTTY automatically suggests matching credentials when you create or edit a connection for a matching host.

### 10.4 Using Credentials

When editing a connection:
1. In the **Connection** tab, look for the **Credentials** dropdown.
2. Select the stored credential.
3. The username and password are filled in automatically.

---

## 11. SSH Key Management

Centrally manage your private SSH keys with encrypted passphrase storage.

### 11.1 Opening

**Menu:** Management > Manage SSH Keys

### 11.2 Adding an SSH Key

1. Click **Add**.
2. Enter a **Name** for the key.
3. Select the **path to your private key** file (e.g., `~/.ssh/id_rsa`).
4. Optionally enter the **passphrase** (will be stored encrypted).
5. Add an optional **description**.

### 11.3 Searching

Use the search field with wildcard patterns (e.g., `prod*`) to quickly find keys.

### 11.4 Copy to User Directory

Select a key and click **Copy to User Directory** to copy it to `~/.kortty/ssh-keys/`. This is useful for:
- Portability: the key travels with your KorTTY configuration.
- Backups: keys in this directory are included in KorTTY backups.

### 11.5 Using Keys in Connections

When editing a connection:
1. Set **Authentication** to "Private Key".
2. Select the key from the dropdown.
3. The key path and passphrase are automatically used for authentication.

---

## 12. GPG Key Management

Manage GPG keys used for encrypting backups and exported connections.

### 12.1 Opening

**Menu:** Management > Manage GPG Keys

### 12.2 Adding a GPG Key

**Manually:**
1. Click **Add**.
2. Enter the key's **Name**, **Key ID**, **Fingerprint**, **Email**, and optionally the **public key file path**.

**From System GPG:**
1. Click **Import from System**.
2. Select a key from your system's GPG keyring.
3. The key details are imported automatically.

### 12.3 Using GPG Keys

GPG keys are used for:
- **Backup Encryption** - In Settings > Backup, select "GPG Encryption" and choose a key.
- **Export Encryption** - When exporting connections, choose GPG encryption and select a key.

---

## 13. Backup and Restore

KorTTY creates encrypted backups of all your settings, connections, credentials, and keys.

### 13.1 What Is Included in a Backup

- `connections.xml` - All saved connections
- `credentials.xml` - Stored credentials (encrypted)
- `ssh-keys.xml` - SSH key references and encrypted passphrases
- `gpg-keys.xml` - GPG key metadata
- `global-settings.xml` - All application settings
- `master-password-hash` - Master password hash
- `snippets.xml` - All code snippets
- `snippet-variables.xml` - Custom snippet variable values
- Project files (`.kortty`)

### 13.2 Creating a Backup

1. Go to **Edit > Create Backup** (Ctrl+Shift+B / Cmd+Shift+B).
2. Select a destination directory.
3. The backup is created with the encryption method configured in Settings.

### 13.3 Importing a Backup

1. Go to **Edit > Import Backup**.
2. Select the backup file (`.zip` or `.gpg`).
3. Enter the password if prompted.
4. Choose whether to overwrite existing files.
5. Restart the application for all changes to take effect.

### 13.4 Backup Settings

Configure in **Settings > Backup**:

| Setting | Description |
|---|---|
| **Encryption Type** | Password-protected ZIP or GPG Encryption |
| **Credential / GPG Key** | Select the credential (for ZIP password) or GPG key |
| **Maximum Backups** | How many old backups to keep (0 = unlimited). Oldest backups are deleted automatically. |

### 13.5 Automatic Rotation

When the maximum backup count is set, KorTTY automatically deletes the oldest backups when new ones are created.

---

## 14. Projects

Projects save your complete workspace state: all open windows, tabs, connections, and terminal sessions.

### 14.1 Saving a Project

1. Go to **File > Save Project** (Ctrl+S / Cmd+S).
2. Enter a project name and optional description.
3. Configure **Auto-Reconnect** (whether sessions should automatically reconnect when the project is loaded).
4. The project is saved to `~/.kortty/projects/`.

### 14.2 Loading a Project

1. Go to **File > Open Project** (Ctrl+O / Cmd+O).
2. Select a project file (`.kortty`).
3. A **Project Preview** dialog shows the project's windows, tabs, and settings.
4. Click **Open** to load the project.

### 14.3 What Is Saved

- Window positions and sizes
- All open tabs and their connections
- Terminal session history
- Dashboard state (open/closed, divider position)
- Active tab selection
- Tab groups

---

## 15. Settings

Open settings with **Edit > Global Settings** (Ctrl+Comma / Cmd+Comma on macOS).

### 15.1 Font and Colors

Configure the default terminal appearance:

- **Font Family** - Choose from available monospace fonts.
- **Font Size** - Default terminal font size.
- **Text Color** - Foreground color.
- **Background** - Background color.
- **Cursor Color** - Terminal cursor color.
- **Selection Color** - Text selection highlight color.
- **ANSI Colors** - Customize all 16 ANSI colors (8 normal + 8 bright).

### 15.2 Terminal

- **Columns / Rows** - Default terminal dimensions.
- **Scrollback** - Number of lines to keep in the scroll buffer (default: 10,000).
- **Encoding** - Character encoding (default: UTF-8).
- **Bold as Bright** - Render bold text using bright ANSI colors.
- **Show Scrollbar** - Display a scrollbar in the terminal.
- **SSH Keep-Alive** - Enable heartbeat messages with configurable interval.

### 15.3 Snippet Editor

Separate appearance settings for the Snippet Manager preview and Snippet Edit dialog:

- **Font Family** and **Font Size** (0 = inherit from terminal)
- **Foreground Color** and **Background Color**
- **Cursor Style** - Block, Line, or Underscore
- **Cursor Color**

### 15.4 Backup

See [Backup Settings](#134-backup-settings).

### 15.5 Window

- **Remember Window Geometry** - Save the main window's position, size, and maximized state.
- **Remember Dashboard State** - Save whether the dashboard was visible.
- **Fixed Window Geometry** - Always open at a specific position and size (overrides "remember" setting).

### 15.6 Security

- **Change Master Password** - Re-encrypts all stored passwords with the new master password.
- **Require Master Password on Startup** - When disabled, the master password dialog is skipped, but encrypted data cannot be automatically decrypted.

### 15.7 Language

Select from 8 supported languages or use **Auto-detect** to match your system locale:

- English (default)
- German
- Italian
- Spanish
- Portuguese
- French
- Croatian
- Dutch

Language changes take effect after restarting the application.

---

## 16. Keyboard Shortcuts

> On macOS, "Ctrl" refers to the **Cmd** key. "Alt" refers to the **Option** key.

| Shortcut | Action |
|---|---|
| Ctrl+T | New Tab (Quick Connect) |
| Ctrl+Shift+N | New Window |
| Ctrl+W | Close Tab |
| Ctrl+Tab | Next Tab |
| Ctrl+Shift+Tab | Previous Tab |
| Ctrl+O | Open Project |
| Ctrl+S | Save Project |
| Ctrl+M | Manage Connections |
| Ctrl+K | Quick Connect |
| Ctrl+Shift+D | Toggle Dashboard |
| Ctrl+Shift+B | Create Backup |
| Ctrl+Shift+S | Open Snippet Manager |
| Ctrl+Comma | Global Settings |
| Alt+Plus | Zoom In |
| Alt+Minus | Zoom Out |
| Alt+0 | Reset Zoom |
| F11 | Toggle Fullscreen |
| Ctrl+C | Copy |
| Ctrl+V | Paste |
| Ctrl+Q | Quit |

---

## 17. Configuration Files

All KorTTY data is stored under `~/.kortty/`:

```
~/.kortty/
  connections.xml          # Saved SSH connections
  credentials.xml          # Stored credentials (encrypted)
  ssh-keys.xml             # SSH key management data
  gpg-keys.xml             # GPG keys for backup encryption
  global-settings.xml      # Global application settings
  snippets.xml             # Code snippets
  snippet-variables.xml    # Custom snippet variable values
  master-password-hash     # Master password hash (PBKDF2)
  kortty.log               # Application log
  history/                 # Terminal history (compressed)
  projects/                # Project files (.kortty)
  ssh-keys/                # Copied SSH keys (optional)
```

---

## 18. Troubleshooting

### macOS - App Will Not Open

- Check system logs:
  ```bash
  log show --predicate 'process == "korTTY"' --last 5m
  ```
- Run directly to see errors:
  ```bash
  /Applications/korTTY.app/Contents/MacOS/korTTY
  ```

### macOS - Gatekeeper Warning

Unsigned apps show a warning on first launch:
1. Right-click the app > **Open** (bypasses the warning).
2. Or: System Preferences > Security and Privacy > "Open Anyway".
3. For distribution, sign the app with a Developer ID.

### Windows - MSI Build Failed

Install WiX Toolset from https://wixtoolset.org/ and ensure its `bin` directory is in your PATH.

### Linux - DEB/RPM Build Failed

- **DEB:** `sudo apt-get install fakeroot dpkg-dev`
- **RPM:** `sudo dnf install rpm-build` (Fedora) or `sudo yum install rpm-build` (CentOS/RHEL)

### Connection Timeout

- Increase the **Connection Timeout** in the connection settings (default: 15 seconds).
- Enable **SSH Keep-Alive** to prevent idle disconnections.
- Increase the **Retry Count** for unreliable networks.

### Master Password Lost

If you forget your master password, encrypted data (passwords, passphrases) cannot be recovered. You will need to:
1. Delete `~/.kortty/master-password-hash`
2. Delete `~/.kortty/credentials.xml`
3. Restart KorTTY and set up a new master password.
4. Re-enter all passwords manually.

### Encoding Issues

If terminal output shows garbled characters:
1. Check the connection's encoding setting (default: UTF-8).
2. Ensure the remote server uses the same encoding.
3. Try setting `LANG=en_US.UTF-8` on the remote server.

---

## Security Overview

| Feature | Implementation |
|---|---|
| Master Password Hashing | PBKDF2 with 310,000 iterations |
| Password Encryption | AES-256-GCM |
| SSH Key Passphrases | Stored encrypted with master password |
| Backup Encryption | Password-protected ZIP or GPG |
| Credentials | Never stored in plain text |

---

*KorTTY v1.3.0*
