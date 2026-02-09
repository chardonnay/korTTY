# Release Notes

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

### Credentials

- **External password provider**: Credentials can use a stored password or an external command (e.g. Enpass CLI, 1Password CLI). The command is stored encrypted.

### Build and Release

- **GitHub Actions**: Release workflow builds macOS (.dmg, .app), Windows (.msi, .exe), and Linux (.deb, .rpm, tarball) on release publish. Checkout uses release tag and submodules; artifacts are attached to the GitHub release.
- **Architectures**: Packages are published per CPU architecture:
  - **macOS**: `-aarch64` for Apple Silicon and `-x86_64` for Intel (separate DMG/ZIP files).
  - **Windows**: `-x86_64` for Intel/AMD (separate MSI/ZIP files).
  - **Linux**: `-x86_64` or `-aarch64` (separate DEB/RPM/tar.gz/zip files).
- **Architectures**: Pre-built packages are provided for **x86_64 (amd64)** and **arm64 (aarch64)** where applicable:
  - **macOS**: Apple Silicon (arm64) only — `.dmg` and `.zip` with `-aarch64` in the filename.
  - **Windows**: x86_64 and arm64 — separate `.msi` and `.zip` files with `-x86_64` or `-aarch64` in the filename.
  - **Linux**: x86_64 and arm64 — separate `.deb`, `.rpm`, and `.tar.gz` files with `-x86_64` or `-aarch64` in the filename. Choose the package that matches your CPU architecture.

### Other

- **i18n**: All new editor and UI strings available in 8 languages (EN, DE, ES, FR, IT, HR, NL, PT).

---

For full feature list and documentation see README.md and docs/USER_GUIDE.md.
