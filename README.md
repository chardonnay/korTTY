# KorTTY - SSH Client

A modern SSH client with JavaFX interface, tab support, and JMX monitoring.

## Features

- **GUI-based**: Modern JavaFX interface with dark theme
- **Tab Support**: Multiple SSH connections in one window
- **Multi-Window**: Open multiple windows for different projects
- **Encrypted Passwords**: AES-256-GCM encryption with master password
- **SSH Key Management**: Centralized management of private SSH keys with encrypted passphrases
- **Customizable Display**: Font size, colors (global or per connection)
- **Project Management**: Save and load connection sets with history
- **Import/Export**: Import connections from MTPuTTY, MobaXterm, and PuTTY Connection Manager
- **JMX Monitoring**: Monitor active connections, memory usage, etc.
- **Dashboard**: Overview of all open connections in the project
- **SFTP Manager**: File transfer between local system and remote servers
- **Window Geometry Storage**: Automatic restoration of window position and size
- **Dashboard State Storage**: Automatic restoration of dashboard state
- **Backup & Restore**: Create encrypted backups (password or GPG) and import them
- **Multilanguage Support**: Available in English, German, Italian, Spanish, Portuguese, French, Croatian, and Dutch

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

## Create Native Release

KorTTY can be built with `jpackage` as a native app for macOS, Windows, and Linux. The build tasks automatically detect the operating system and create the appropriate distribution.

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
build/jpackage/korTTY-1.1.0.dmg
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
build\jpackage\korTTY-1.1.0.msi
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
build/jpackage/korTTY-1.1.0.deb
```

**Installation:**
```bash
sudo dpkg -i build/jpackage/korTTY-1.1.0.deb
```

#### Create RPM Package

Creates a Red Hat/Fedora package (requires rpmbuild):

```bash
./gradlew jpackageRpm
```

The RPM package is located at:
```
build/jpackage/korTTY-1.1.0.rpm
```

**Installation:**
```bash
sudo rpm -i build/jpackage/korTTY-1.1.0.rpm
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

## Multilanguage Support

KorTTY supports multiple languages and automatically detects your system language:

### Supported Languages

- English (default)
- German
- Italian
- Spanish
- Portuguese
- French
- Croatian
- Dutch

### Usage

1. **Change Language**:
   - **Settings → Language**
   - Select desired language from dropdown
   - Language change takes effect after application restart

2. **Auto-Detect**:
   - Select "Auto-detect (System Language)" to use your system's language
   - The application will automatically detect and use your system language

## SFTP Manager

The integrated SFTP Manager enables direct file transfers:

### Features

- **Two-Panel View**: Local and remote files side by side
- **Drag & Drop**: Easy file moving
- **File Operations**: Delete, rename, copy files and directories
- **Permissions**: Adjust file permissions (chmod) with checkbox interface
- **ZIP Archiving**: Create ZIP archives from multiple files/directories
- **Search**: Glob pattern search (`*`) in both panels
- **Sorting**: Sortable table columns (Name, Size, Date)

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
