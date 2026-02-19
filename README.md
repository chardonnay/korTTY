# KorTTY - SSH Client

**Version 1.7.0** — A modern SSH client with JavaFX interface, tab support, and JMX monitoring.

## Features

- **GUI-based**: Modern JavaFX interface with dark theme
- **Tab Support**: Multiple SSH connections in one window
- **Font Size Adjustment**: Zoom in/out in running terminal (Ctrl+Plus, Ctrl+Minus, Ctrl+0)
- **Split-Screen with Broadcast**: Split terminal view and broadcast input to all panes
- **Multi-Window**: Open multiple windows for different projects; drag tabs between windows to move sessions (including split terminals)
- **Encrypted Passwords**: AES-256-GCM encryption with master password
- **SSH Key Management**: Centralized management of private SSH keys with encrypted passphrases
- **Customizable Display**: Font size, colors (global or per connection)
- **Project Management**: Save and load connection sets with history
- **Import/Export**: Import connections from MTPuTTY, MobaXterm, and PuTTY Connection Manager
- **JMX Monitoring**: Monitor active connections, memory usage, etc.
- **Dashboard**: Overview of all open connections in the project
- **SFTP Manager**: File transfer between local system and remote servers (User/Group columns, sortable by type with dot-prefix order)
- **Snippet Manager**: Create, search, favorite, and organize reusable snippets (JSON/XML/YAML import/export)
- **ASCII Art Banner**: Generate FIGlet banners with multiple styles (Tools menu)
- **Window Geometry Storage**: Automatic restoration of window position and size
- **Dashboard State Storage**: Automatic restoration of dashboard state
- **Backup & Restore**: Create encrypted backups (password or GPG) and import them
- **Multilanguage Support**: Built-in languages (English, German, Italian, Spanish, Portuguese, French, Croatian, Dutch) plus **dynamic translation**: generate language files for any language via translation APIs (Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex) in Settings → Translation
- **Quick Connect**: Fast connection dialog with frequently used connections and group support
- **SSH Tunnels**: Local and remote port forwarding (SSH tunnels)
- **Jump Server**: Support for bastion hosts (SSH hopping)
- **Terminal Logging**: Automatic logging of terminal sessions
- **GPG Key Management**: Manage GPG keys for backup encryption

## User Guide

For a comprehensive step-by-step user guide covering all features, see **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)**.

## Requirements

- Java 25 or higher
- Gradle 9.x (automatically downloaded via wrapper)

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew run
```

## Pre-built Binaries (Releases)

Pre-built packages for **x86_64 (amd64)** and **arm64 (aarch64)** are available on the [GitHub Releases](https://github.com/chardonnay/korTTY/releases) page:

- **macOS**: Apple Silicon only — `-aarch64` (separate DMG/ZIP files).
- **Windows**: `-x86_64` for Intel/AMD (separate MSI/ZIP files).
- **Linux**: `-x86_64` or `-aarch64` (separate DEB/RPM/tar.gz/zip files).
- **Arch Linux**: `-x86_64` (pacman `.pkg.tar.zst`; aarch64: use the Linux tarball).

Each release provides separate packages per platform and architecture; there are no universal binaries. Use the file that matches your OS and CPU.

## JediTermFX Integration (Submodule)

JediTermFX is integrated as a git submodule under `vendor/jeditermfx`.
You do not need to clone it manually.

**Important — do not edit the submodule in this repo.** All changes to JediTermFX must be made in the [JediTermFX project](https://github.com/techsenger/jeditermfx) (or your local clone, e.g. `/Users/daniel/Cursor/JediTermFX`). The split-terminal UI (e.g. `TerminalSplitPane` with drag-and-drop, left panel factory, extra menu items) lives in JediTermFX so that other projects can use it too. After committing and pushing there (or having a local commit), update the submodule in KorTTY:

```bash
cd vendor/jeditermfx
git fetch origin
git checkout <branch-or-commit-you-want>   # e.g. main or a specific tag
cd ../..
git add vendor/jeditermfx
git commit -m "Update JediTermFX submodule"
```

This keeps KorTTY pointing at the correct JediTermFX version and avoids local modifications under `vendor/jeditermfx`.

During the build, korTTY will automatically:

1. Initialize the submodule (if missing)
2. Run `mvn -q -DskipTests install` inside `vendor/jeditermfx`
3. Resolve the SNAPSHOTs via `mavenLocal()`

This ensures you always build against the exact JediTermFX version tracked by the repository.

## Create Native Release

KorTTY can be built with `jpackage` as a native app for macOS, Windows, and Linux. The build tasks automatically detect the operating system and create the appropriate distribution. The resulting binary matches the architecture of the machine on which you run the build (e.g. x86_64 or arm64).

### Requirements

- **Java 25 or higher** (with jpackage tool)
- **Platform-specific:**
  - **macOS**: Xcode Command Line Tools (for code signing, optional)
  - **Windows**: WiX Toolset (for MSI installer, optional)
  - **Linux**: fakeroot, rpmbuild or dpkg (for DEB/RPM packages, optional)

### macOS

#### Create App (.app)

Creates a standalone macOS app:

```bash
./gradlew jpackage
```

The created app is located at:
```
build/jpackage/korTTY.app
```

**Installation:**
- Drag & drop into `/Applications`
- Or run directly from `build/jpackage/`

#### Create DMG Installer

Creates a DMG installer:

```bash
./gradlew jpackageDmg
```

The DMG file is located at:
```
build/jpackage/korTTY-1.3.0.dmg
```

**Icon:** `src/main/resources/icon/kortty_icon.icns`

### Windows

#### Create App (.exe)

Creates a standalone Windows app:

```bash
gradlew.bat jpackage
```

The app is located at:
```
build\jpackage\korTTY\
```

**Features:**
- Windows Start menu entry
- Desktop shortcut
- Installation directory selection

#### Create MSI Installer

Creates an MSI installer (requires WiX Toolset):

```bash
gradlew.bat jpackageMsi
```

The MSI file is located at:
```
build\jpackage\korTTY-1.3.0.msi
```

**Icon:** `src/main/resources/icon/kortty_icon.ico` (if available, otherwise PNG)

### Linux

#### Create App Image

Creates a standalone app image:

```bash
./gradlew jpackage
```

The app image is located at:
```
build/jpackage/korTTY/
```

**Execution:**
```bash
./build/jpackage/korTTY/bin/korTTY
```

#### Create DEB Package

Creates a Debian/Ubuntu package (requires dpkg):

```bash
./gradlew jpackageDeb
```

The DEB package is located at:
```
build/jpackage/korTTY-1.3.0.deb
```

**Installation:**
```bash
sudo dpkg -i build/jpackage/korTTY-1.3.0.deb
```

#### Create RPM Package

Creates a Red Hat/Fedora package (requires rpmbuild):

```bash
./gradlew jpackageRpm
```

The RPM package is located at:
```
build/jpackage/korTTY-1.3.0.rpm
```

**Installation:**
```bash
sudo rpm -i build/jpackage/korTTY-1.3.0.rpm
```

**Icon:** `src/main/resources/icon/kortty_icon.png`

### Technical Details

- **Launcher Class**: The app uses `de.kortty.Launcher` as entry point to bypass JavaFX runtime checks
- **Bundled JVM**: The app contains a complete JVM (approx. 150-200 MB)
- **Platform Detection**: Build tasks automatically detect the operating system
- **Dependencies**: All dependencies (JavaFX, Apache SSHD, etc.) are automatically bundled
- **Icon Management**: The KorTTY icon is always used:
  - **macOS**: `kortty_icon.icns` (fallback: `kortty_icon.png`)
  - **Windows**: `kortty_icon.ico` (fallback: `kortty_icon.png`)
  - **Linux**: `kortty_icon.png` (required)

### Troubleshooting

**macOS - App won't open:**
- Check system logs: `log show --predicate 'process == "korTTY"' --last 5m`
- Test app directly: `build/jpackage/korTTY.app/Contents/MacOS/korTTY`

**macOS - Gatekeeper warning:**
- Unsigned apps show a warning on first launch
- Right-click → "Open" bypasses the warning
- For distribution: Sign app with Developer ID (requires Apple Developer account)

**Windows - MSI build failed:**
- Install WiX Toolset: https://wixtoolset.org/
- Add WiX bin directory to PATH

**Linux - DEB/RPM build failed:**
- DEB: `sudo apt-get install fakeroot dpkg-dev`
- RPM: `sudo yum install rpm-build` or `sudo dnf install rpm-build`

## JMX Monitoring

The SSH client registers a JMX MBean under `de.kortty:type=SSHClient`.

Available attributes:
- `ActiveConnectionCount`: Number of active connections
- `UsedMemoryBytes`: Used memory
- `BufferedTextSize`: Size of buffered terminal text
- `ActiveConnectionNames`: List of active connection names
- `UptimeSeconds`: Application uptime

To use JMX monitoring, start the application with:
```bash
./gradlew run --args="-Dcom.sun.management.jmxremote"
```

Or connect with JConsole:
```bash
jconsole
```

## Font Size and Split-Screen

### Font Size Adjustment in Running Terminal

Adjust the font size of the active terminal without reconnecting:

- **Ctrl+Plus** / **Ctrl+=**: Zoom in (increase font size)
- **Ctrl+Minus**: Zoom out (decrease font size)
- **Ctrl+0**: Reset zoom to default

The zoom level applies to the currently focused terminal tab.

### Split-Screen with Broadcast

Split the terminal view to display multiple connections side by side:

- **Split Pane**: Create horizontal or vertical splits within a tab
- **Broadcast Mode**: When enabled, keyboard input is sent to all visible panes simultaneously
- **Independent Sessions**: Each pane can show a different SSH connection
- **Resizable Panes**: Drag dividers to adjust pane sizes

Useful for running the same commands on multiple servers at once.

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl+T | New Tab |
| Ctrl+Shift+N | New Window |
| Ctrl+W | Close Tab |
| Ctrl+Tab | Next Tab |
| Ctrl+Shift+Tab | Previous Tab |
| Ctrl+O | Open Project |
| Ctrl+S | Save Project |
| Ctrl+Shift+D | Toggle Dashboard |
| Ctrl+Plus | Zoom In |
| Ctrl+Minus | Zoom Out |
| Ctrl+0 | Reset Zoom |
| Ctrl+Shift+B | Create Backup |
| Ctrl+Q | Quit |
| Ctrl+K | Quick Connect |

## Configuration Files

All configuration files are stored under `~/.kortty/`:

```
~/.kortty/
├── connections.xml      # Saved connections
├── credentials.xml     # Stored credentials
├── ssh-keys.xml        # SSH key management
├── gpg-keys.xml        # GPG keys for backup encryption
├── global-settings.xml # Global application settings
├── master-password-hash # Master password hash
├── kortty.log          # Log file
├── history/            # Terminal history (compressed)
├── projects/           # Project files (.kortty)
├── i18n/               # Dynamically generated language files (messages_XX.properties)
└── ssh-keys/           # Copied SSH keys (optional)
```

## Import from Other Programs

### MTPuTTY

Export your connections from MTPuTTY as `servers.xml` and import them via:
**Connections → Import → MTPuTTY Server Files (*.xml)**

### MobaXterm

Copy the `MobaXterm.ini` file and import it via:
**Connections → Import → MobaXterm Session Files (*.ini)**

### PuTTY Connection Manager

Export your connections from PuTTY Connection Manager and import them via:
**Connections → Import → PuTTY Connection Manager Files (*.xml)**

**Note:** Passwords are not imported for security reasons. You must re-enter them after import.

## Credential Management

KorTTY provides centralized management for credentials (username/password):

### Features

- **Environment-specific**: Credentials can be stored for Production, Development, Test, or Staging
- **Server Pattern**: Automatic assignment to servers via glob patterns (e.g., `*.example.com` or `10.0.0.*`)
- **Encrypted Storage**: Passwords are encrypted with AES-256-GCM
- **Automatic Usage**: Credentials can be directly selected in connection settings

### Usage

1. **Add credentials**:
   - **Management → Manage Credentials...**
   - Click "Add"
   - Enter name, username, environment, and optionally a server pattern
   - Enter the password (will be encrypted and stored)

2. **Use credentials in connection**:
   - When creating/editing a connection
   - Select the appropriate credentials from the dropdown list
   - Username and password are automatically filled in

### Import/Export

When importing connections, you can choose:
- Whether credentials should be imported
- Whether imported credentials should be replaced by stored credentials

## SSH Key Management

KorTTY provides comprehensive management for private SSH keys:

### Features

- **Centralized Management**: Manage all SSH keys in one place
- **Encrypted Passphrases**: Key passphrases are stored encrypted
- **Key Copying**: Keys can be copied to the KorTTY directory for easy migration
- **Glob Search**: Quick search for keys with wildcard patterns (`*`)
- **Automatic Usage**: Keys can be directly selected in connection settings

### Usage

1. **Add SSH key**: 
   - **Management → Manage SSH Keys...**
   - Click "Add"
   - Select the path to your private SSH key
   - Optional: Enter the passphrase (will be encrypted and stored)

2. **Use key in connection**:
   - When creating/editing a connection
   - Select "Private Key" as authentication method
   - Select the desired key from the dropdown list
   - The key path and passphrase are automatically filled in

3. **Copy key to user directory**:
   - In SSH key management, select a key
   - Click "Copy to User Directory"
   - The key is copied to `~/.kortty/ssh-keys/` and included in backups

### Import/Export

When importing connections, you can choose:
- Whether SSH keys should be imported
- Whether imported keys should be replaced by stored keys from management

## Backup & Restore

KorTTY provides encrypted backup functionality to save and restore all your settings:

### Features

- **Encrypted Backups**: All backups are encrypted (password-protected ZIP or GPG-encrypted)
- **Complete Backup**: Includes all connections, credentials, GPG keys, SSH keys, and settings
- **Backup Import**: Restore from previously created backups
- **Automatic Rotation**: Old backups are automatically rotated when creating new ones
- **Configurable Retention**: Set maximum number of backups to keep (0 = unlimited)

### Usage

1. **Create Backup**:
   - **Edit → Create Backup...**
   - Select destination directory
   - Backup is created with encryption method configured in settings

2. **Import Backup**:
   - **Edit → Import Backup...**
   - Select backup file (.zip or .gpg)
   - Enter password if required
   - Choose whether to overwrite existing files
   - All settings are restored from the backup

### Backup Settings

Configure backup settings in **Settings → Backup**:
- **Encryption Type**: Choose between password-protected ZIP or GPG encryption
- **Credential/GPG Key**: Select the credential or GPG key for encryption
- **Maximum Backups**: Set how many old backups to keep (0 = unlimited)

## Quick Connect

KorTTY provides a quick connection dialog for fast access to your most frequently used connections:

### Features

- **Frequently Used Connections**: Shows top 10 most frequently used connections as quick-access buttons
- **Individual Connection**: Connect to a single server with full configuration options
- **Group Connection**: Open all connections in a selected group at once
- **Connection History**: Tracks usage count and last used timestamp
- **Save New Connections**: Option to save new connections directly from the dialog

### Usage

1. **Open Quick Connect**:
   - **File → Quick Connect...** or press `Ctrl+K`
   - Or use the menu: **Connections → Quick Connect...**

2. **Quick Access**:
   - Click on any frequently used connection button at the top
   - Connection opens immediately with saved credentials

3. **Individual Connection**:
   - Select "Individual Connection" tab
   - Choose from saved connections or enter new connection details
   - Configure authentication, terminal appearance, and other settings
   - Optionally save the connection for future use

4. **Group Connection**:
   - Select "Open Group" tab
   - Choose a group from the list
   - All connections in the group open in separate tabs

## GPG Key Management

KorTTY provides management for GPG keys used for backup encryption:

### Features

- **Centralized Management**: Manage all GPG keys in one place
- **System Import**: Import GPG keys directly from your system's GPG keyring
- **Manual Entry**: Add GPG keys manually with key ID and email
- **Backup Encryption**: Use GPG keys to encrypt backups instead of password-protected ZIP

### Usage

1. **Add GPG Key**:
   - **Management → Manage GPG Keys...**
   - Click "Add" to manually enter key details
   - Or click "Import from GPG" to import from your system's GPG keyring

2. **Edit/Remove Keys**:
   - Select a key from the list
   - Click "Edit" to modify key details
   - Click "Delete" to remove a key

3. **Use for Backup**:
   - Configure backup settings in **Settings → Backup**
   - Select "GPG Encryption" as encryption type
   - Choose the GPG key to use for encryption

## SSH Tunnels (Port Forwarding)

KorTTY supports SSH port forwarding for secure tunneling:

### Features

- **Local Port Forwarding**: Forward local ports to remote hosts (`-L`)
- **Remote Port Forwarding**: Forward remote ports to local hosts (`-R`)
- **Multiple Tunnels**: Configure multiple tunnels per connection
- **Enable/Disable**: Toggle tunnels on/off without deleting configuration

### Usage

1. **Configure Tunnel**:
   - When creating/editing a connection, go to "Tunnels" tab
   - Click "Add Tunnel"
   - Select tunnel type (Local or Remote)
   - Enter local host/port and remote host/port
   - Add optional description
   - Enable/disable the tunnel as needed

2. **Local Port Forwarding**:
   - Forwards a local port to a remote host:port
   - Example: `localhost:8080 → remote-server:80`
   - Access `localhost:8080` to reach `remote-server:80` through the SSH tunnel

3. **Remote Port Forwarding**:
   - Forwards a remote port to a local host:port
   - Example: `remote-server:8080 → localhost:80`
   - Access `remote-server:8080` to reach `localhost:80` through the SSH tunnel

## Jump Server (Bastion Host)

KorTTY supports SSH hopping through jump servers (bastion hosts):

### Features

- **SSH Hopping**: Connect to servers through an intermediate jump server
- **Flexible Authentication**: Use password or SSH key for jump server
- **Auto Command**: Execute commands automatically after connecting to jump server

### Usage

1. **Configure Jump Server**:
   - When creating/editing a connection, go to "Advanced" tab
   - Enable "Jump Server"
   - Enter jump server host, port, and username
   - Select authentication method (password or SSH key)
   - Optionally configure auto-command to execute after jump

2. **Connection Flow**:
   - First connects to the jump server
   - Then establishes connection to the target server through the jump server
   - All traffic is routed through the jump server

## Terminal Logging

KorTTY can automatically log terminal sessions for audit and debugging purposes:

### Features

- **Automatic Logging**: Log all terminal output to files
- **Compressed Storage**: Logs are stored compressed in `~/.kortty/history/`
- **Per-Connection Configuration**: Enable/disable logging per connection
- **Timestamped Logs**: Each session creates a timestamped log file

### Usage

1. **Enable Logging**:
   - When creating/editing a connection, go to "Advanced" tab
   - Enable "Terminal Logging"
   - Logs are automatically saved to `~/.kortty/history/`

2. **Log Files**:
   - Logs are stored as compressed files
   - Format: `{connection-name}_{timestamp}.log.gz`
   - Located in `~/.kortty/history/`

## Connection Settings

KorTTY provides advanced connection settings for better control and reliability:

### SSH Keep-Alive

Prevents SSH connections from timing out due to inactivity:

- **Enable/Disable**: Toggle SSH Keep-Alive on/off per connection
- **Configurable Interval**: Set keep-alive interval (5-600 seconds, default: 60 seconds)
- **Heartbeat Messages**: Sends SSH_MSG_IGNORE messages at regular intervals to keep connection alive

**Usage:**
- When creating/editing a connection, go to "Terminal" tab
- Enable "SSH Keep-Alive"
- Set the interval in seconds
- Configure in **Settings → Terminal** for global defaults

### Connection Timeout and Retry

Configure connection behavior for unreliable networks:

- **Connection Timeout**: Maximum time to wait for connection (default: 15 seconds)
- **Retry Count**: Number of retry attempts on connection failure (default: 4 attempts)

**Usage:**
- When creating/editing a connection, configure in "Advanced" tab
- Set connection timeout in seconds
- Set number of retry attempts

## Multilanguage Support

KorTTY supports multiple languages and automatically detects your system language.

### Built-in Languages

- English (default)
- German
- Italian
- Spanish
- Portuguese
- French
- Croatian
- Dutch

### Dynamic Translation (Additional Languages)

You can generate language files for **any language** using a translation API:

1. **Settings → Translation**
2. Choose a **Translation API** (Google Translate, DeepL, LibreTranslate, Microsoft Translator, or Yandex). *Note: Yandex uses the deprecated v1.5 API and requires a legacy API key.*
3. Enter your **API key** (optional for LibreTranslate on public instances). The key is stored encrypted with your master password.
4. Optionally set a **custom API URL** (e.g. for self-hosted LibreTranslate).
5. Click **Test API Connection** to verify the setup.
6. Select the **target language** and click **Generate Language File**. The file is saved under `~/.kortty/i18n/`.
7. The new language appears in **Settings → Language** and can be selected after restart.

**After an upgrade:** If the app version changes, generated language files are detected as outdated. In **Settings → Translation** a hint is shown and **Regenerate outdated** lets you refresh all generated languages so new or changed keys are translated again.

### Changing Language

1. **Settings → Language**
2. Select the desired language (built-in or any previously generated language).
3. Language change takes effect after application restart.

**Auto-detect:** Select "Auto-detect (System Language)" to use your system's language.

## SFTP Manager

The integrated SFTP Manager enables direct file transfers between your local system and remote servers.

### Features

- **Two-Panel View**: Local and remote files side by side
- **Columns**: Name, Type, Size, Date, **User**, **Group**, Permissions (sortable)
- **Default Sort**: By type — parent directory (`..`) first, then dot-prefix directories, then other directories, then dot-prefix files, then other files (each group alphabetically)
- **Drag & Drop**: Easy file moving
- **File Operations**: Delete, rename, copy files and directories
- **Owner/Permissions**: Set owner, group, and permissions (chmod) locally and remotely; separate User/Group fields in dialogs
- **ZIP Archiving**: Create ZIP archives from multiple files/directories
- **Search**: Glob pattern search (`*`) in both panels

### Access

- **Dashboard**: Right-click on a server → "Open SFTP Manager"
- **Menu**: **Tools → Open SFTP Manager...**

## Window and Dashboard Management

KorTTY automatically remembers:

- **Window Geometry**: Position, size, and maximized status of the main window
- **Dashboard State**: Whether the dashboard was open when closing

These features can be disabled in settings (**Settings → Window**).

## Security

- Master password is hashed with PBKDF2 (310,000 iterations)
- Connection passwords are encrypted with AES-256-GCM
- Private SSH key passphrases are also stored encrypted
- Passwords are never stored in plain text
- SSH keys can optionally be copied to user directory (included in backups)

## License

MIT License
