# Release Notes

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
