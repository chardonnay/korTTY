# Release Notes

## Unreleased

### Edit Menu and Zoom

- **Edit menu – Cut**: **Cut** (Ausschneiden) added next to Copy and Paste with shortcut **Ctrl+X** (Cmd+X on macOS). In the terminal, Cut copies the selection to the clipboard (same as Copy).
- **Zoom reset**: Reset zoom (shortcut **Alt+0** or context menu **Font size → Reset**) now restores the **font size and family that were active when the tab was opened** (or the connection’s saved settings / global default). This fixes the case where reset previously showed the wrong size after zooming.
- **Connection settings applied immediately**: When you save a connection in **Connection Manager** (e.g. change font size or family), any **open terminal tabs** using that connection update their font and appearance **immediately** without restart or reconnecting.

### Quick Connect

- **Frequently used connections**: The “Frequently used connections” section now correctly shows the **10 last used** connections, ordered by last used (most recent first). Usage is persisted for the saved connection when you connect from Quick Connect or the dashboard.

### Technical

- **Connection settings in XML**: Font size and font family (and other connection settings) are now correctly loaded from and saved to `connections.xml` (JAXB context includes `ConnectionSettings` and `WindowGeometry`).
- **Release builds - Windows ARM64**: GitHub Actions now also publishes Windows ARM64 artifacts for `v1.8.0` as `korTTY-Windows-<version>-arm64.msi` and `korTTY-Windows-<version>-arm64.zip` (ZIP contains the single `korTTY.exe`).
- **CI toolchain (Windows ARM64)**: The `windows-11-arm` release job currently uses Java 21 for compatibility; other release jobs continue with Java 25.

---

## 1.8.0

**Release date:** February 2026

### Dynamic Translation (i18n)

- **Translation tab in Settings**: New tab **Translation** under Global Settings to generate language files for any language using a translation API.
- **Supported providers**: Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex. API key is stored encrypted (optional for LibreTranslate on public instances). Optional custom API URL for self-hosted or regional endpoints.
- **Generate language file**: Select target language, click **Generate Language File**; file is saved to `~/.kortty/i18n/messages_XX.properties`. Generated languages appear in **Settings → Language** after restart.
- **Test connection**: Button **Test API Connection** to verify API key and connectivity before generating.
- **Outdated translations after upgrade**: Generated files store the app version. When the app is upgraded, **Settings → Translation** shows which language files are outdated and offers **Regenerate outdated** to regenerate all of them in one go so new or changed keys are translated again.
- **Configuration**: `~/.kortty/i18n/` holds dynamically generated language files; API provider and encrypted key in `global-settings.xml`.

### ASCII Art Banner and UI

- **Temporary SSH key option**: Option to use a temporary SSH key is only shown in Connection Manager and Quick Connect when enabled in **Settings → Security** (default: off).
- **ASCII Art dialog**: Window geometry is saved and restored; prev/next style buttons and arrow keys to change font style; banner font fixed for jfiglet; added big and lean fonts.

---

## 1.7.0

**Release date:** February 2026

### Terminal and Multi-Window

- **Tab drag between windows**: Drag tabs (including tabs with split terminals) from one KorTTY window to another to move the session. Drop anywhere in the target window. Tabs can also be reordered within the same window by dragging in the tab bar.
- **Split terminal drag-and-drop**: Reorder split panes by holding **Shift+Alt** (Shift+Option on macOS) and dragging a pane onto another; drop zones (above, below, left, right) show where the pane will be placed. Uses JediTermFX `TerminalSplitPane` with event filters and `TransferMode.ANY` for reliable drop on all platforms.
- **Reconnect via right-click**: Right-click on a terminal tab, inside the terminal area, or on a server entry in the Dashboard to trigger **Reconnect**. If a connection is active, it is closed and immediately re-established without closing the terminal window.

### Theme and UI

- **Theme management**: Apply themes at runtime; theme menu in terminal context menu (right-click). Reconnect and timestamp toggle in context menu.
- **Cursor visibility**: Fix for cursor visibility in terminal.

### SFTP Manager

- **User and Group columns**: Local and remote file tables show **User** and **Group** columns; sort by owner or group. Remote values use SFTP owner/group or UID/GID as fallback.
- **Type sort order**: Sort by type uses dot-prefix order: `..`, then dot-prefix directories, then other directories, then dot-prefix files, then other files — each group alphabetically.
- **Owner/Permissions dialog**: Separate fields for User, Group, and permissions (octal); current values pre-filled; leave a field empty to keep the current value. Dark theme applied; ClassCastException and error handling fixes.
- **Multi-file transfer fix**: Fix `WritePendingException` when downloading or uploading multiple files.
- **GUI**: Dark theme for all SFTP dialogs; improved button layout.

### JediTermFX and Build

- **TerminalSplitPane upstream**: Split-terminal UI (left panel factory, extra menu items, Shift+Alt DnD, DropZone) moved into JediTermFX so other projects can use it. KorTTY uses the submodule; local override removed.
- **Arch Linux**: Pacman (`.pkg.tar.zst`) packaging via GitHub Actions.

### Internationalisation (i18n)

- **Locale fixes**: Remaining English strings in Spanish (ES), French (FR), Italian (IT), Croatian (HR), Dutch (NL), and Portuguese (PT) replaced with correct translations. Covers dialogs, editor, Quick Connect, SFTP, errors, GPG, and status messages.

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
