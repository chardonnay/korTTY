# Release Notes

## Unreleased

### SFTP Manager

- **User and Group columns**: Local and remote file tables show **User** and **Group** columns; you can sort by owner or group. Local values come from the filesystem; remote values use SFTP owner/group or UID/GID as fallback.
- **Type sort order**: Default sort by type uses a fixed order: parent directory (`..`) first, then directories starting with ".", then other directories, then files starting with ".", then other files — each group sorted alphabetically.
- **Owner/Permissions dialog**: Separate fields for User, Group, and permissions (octal); current values are pre-filled; leave a field empty to keep the current value. Same behaviour for local and remote.

---

## 1.7.0

**Release date:** February 2026

### Terminal and UI

- **Reconnect via right-click**: Right-click on a terminal tab, inside the terminal area, or on a server entry in the Dashboard to trigger **Reconnect**. If a connection is active, it is closed and immediately re-established without closing the terminal window. If the connection is already disconnected, it is re-established. The terminal remains open in all cases.

---

## 1.6.0

**Release date:** February 2026

### Teamwork

- **Shallow clone update**: Git teamwork sources no longer use `pull --rebase` on shallow clones; the updater uses `fetch` + `reset --hard origin/<branch>` so rewritten or non-linear history works reliably.
- **Connection edit validation**: Teamwork connections require the password authentication radio to be selected when using stored credentials; validation no longer passes if the user switched to key auth but left a credential selected.
- **Recycle bin JAXB fix**: Teamwork recycle bin service uses `@XmlAccessorType(FIELD)` and full model list so JAXB context builds correctly; app no longer exits on startup with IllegalAnnotationsException.

### Terminal and UI

- **Disconnect keeps tab open**: When the server disconnects or the session ends, the terminal tab stays open and is shown in red with "(DISCONNECT)" instead of auto-closing; double-click to reconnect.
- **Connection Manager**: Export button is enabled when a folder is selected; you can export a folder (all connections in that folder and subfolders) via the Export button or right-click context menu.

### Build and tests

- **JUnit**: Explicit version `1.10.1` for `junit-platform-launcher` so test runtime resolves correctly.
- **Tests**: `SharedFileTeamworkAdapterTest` for `toPath()` (UNC tests run only on Windows via `@EnabledOnOs(WINDOWS)`; recycle bin JAXB load test).

### Startup and i18n

- **Startup error handling**: Application start catches `Throwable` and shows error dialog safely so the real exception is logged and the app exits cleanly.
- **Teamwork auth scope**: English and locale strings for teamwork auth use an explicit scope (e.g. "for all team connections") instead of the vague "for all teamwork".
- **French**: Restore label and confirm text use "Restaurer les connexions supprimées".
- **Croatian**: Consistent Unicode escapes in `messages_hr.properties` and corrected adjective "pohranjenu" (feminine accusative) for "vjerodajnicu" in `connEdit.teamworkAuthRequired`.

---

## 1.5.0

**Release date:** February 2025

### Terminal

- **Command timestamps always recorded**: Timestamps are now recorded for every command (Enter and prompt detection) for the whole session, regardless of whether the timestamp gutter is visible. If you enable "Command Timestamps" later, all past prompts of the session are shown with their times.
- **Timestamp gutter**: Two-line layout (date + duration above, time below). Hover over a timestamp to see full date, time, and elapsed duration in a popup.

### Credentials and SSH Keys

- **SSH key passphrase handling**: Decryption failures for stored key passphrases are no longer ignored; an error is logged and authentication fails with a clear message. When the master password is changed in Settings, SSH key passphrases are re-encrypted. Alerts and translations added for passphrase decrypt errors.
- **No password dialog for key auth**: When using public key authentication, the password dialog is no longer shown (Connection Manager, Quick Connect, duplicate tab, reconnect, project restore).

### macOS

- **Option key**: Option (Alt) no longer sends Escape; special characters like Option+7 (|) work as expected in the terminal.
- **Zoom**: Window zoom is triggered only with Cmd (Meta), not with Option.

### UI

- **About dialog**: "Developed by Daniel Mengel" added in all supported languages.
- **Passphrase/password dialogs**: Use masked input for passphrase and password fields.
- **Connection Manager**: Context menu on empty area to create folder or connection; Create Folder button. Temp SSH key and passphrase correctly passed when opening saved connections; NPE and FX thread fixes for connection creation.

### Bug fixes

- Temp SSH key not pre-filled or passed when selecting saved connection; NPE when storeTemporaryKey() returns null; password prompts rejected when using temporary SSH keys. createSameServerConnection() runs UI and showAndWait on FX thread.

### Other

- **i18n**: New and updated strings in 8 languages (EN, DE, ES, FR, IT, HR, NL, PT).

---

## 1.4.0

**Release date:** 2025

### Editor

- **Code formatter**: Format code via external CLI tools (Python, Perl, Ruby, Shell, JSON, XML, YAML, TOML, HTML/CSS/JS, Java, Go, Rust, SQL, Terraform). Toolbar button and shortcut Ctrl+Shift+F. If the formatter is not installed, an info dialog shows the install command (e.g. brew install shfmt).
- **Linters**: Extended linter support for Ansible (ansible-lint), Puppet (puppet parser validate), CFEngine3 (cf-promises), and Jinja2 (j2lint / djlint).
- **Whitespace visualization**: Toggle to show invisible characters (spaces, tabs, line endings, BOM). Toolbar button and shortcut Ctrl+Shift+I. Context menu: Whitespace.
- **Line ending conversion**: Detect and convert between LF (Unix/macOS) and CRLF (Windows). Status bar shows current format; click to convert. Context menu: Line Endings submenu.
- **Context menu**: Right-click in editor opens Cut, Copy, Paste, Delete, Find / Search and Replace, Select All, Whitespace toggle, and Line Endings submenu.

### Terminal

- **Command timestamp gutter**: Optional sidebar with date/time for each command. Toggle via menu, shortcut, or terminal context menu.
- **Split terminal auto-close**: Split pane closes automatically when the SSH session ends (e.g. Ctrl+D).
- **Context menu**: Single open menu; closes when clicking elsewhere.

### Connection Manager

- **Context menu on empty area**: Right-click in the free space of the connection tree to create a folder or a new connection (same as **New…**).
- **Create Folder button**: Button next to **Rename Folder** to create a new folder (at root level or under the selected folder).

### Credentials

- **External password provider**: Credentials can use a stored password or an external command (e.g. Enpass CLI, 1Password CLI). The command is stored encrypted.

### Build and Release

- **GitHub Actions**: Release workflow builds macOS (.dmg, .app), Windows (.msi, .exe), and Linux (.deb, .rpm, tarball) on release publish. Checkout uses release tag and submodules; artifacts are attached to the GitHub release.
- **Architectures**: Packages are published per CPU architecture:
  - **macOS**: Apple Silicon (arm64) only — `.dmg` and `.zip` with `-aarch64` in the filename.
  - **Windows**: `-x86_64` for Intel/AMD (separate MSI/ZIP files).
  - **Linux**: `-x86_64` or `-aarch64` (separate DEB/RPM/tar.gz/zip files).

### Bug fixes

- **SSH key passphrase not applied (Connection Manager / Quick Connect)**: When creating or editing a connection and selecting a saved private SSH key that has a passphrase, the passphrase is now stored with the connection (encrypted) and used when connecting. Previously, the passphrase was not carried over and the user was prompted for it. The same fix applies when using a saved connection with key auth in Quick Connect.

### Other

- **i18n**: All new editor and UI strings available in 8 languages (EN, DE, ES, FR, IT, HR, NL, PT).

---

For full feature list and documentation see README.md and docs/USER_GUIDE.md.
