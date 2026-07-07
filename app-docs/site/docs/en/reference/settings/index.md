# Settings reference

korTTY's settings live in **Configuration → Global Settings…**. They are stored in `~/.kortty/global-settings.xml`. This reference documents **every** setting, organized by tab.

Each per-tab page lists settings as a table:

| Column | Meaning |
| --- | --- |
| Setting | The label shown in the dialog |
| Type | toggle · dropdown · number · text · color · path |
| Values | Allowed values / range |
| Default | Out-of-the-box value |
| Stored as | The field in `global-settings.xml` |

## Settings tabs

| Tab | What it controls |
| --- | --- |
| [Appearance, themes & font](appearance.md) | App design (Default, Matrix, Holographic, Klingon, Elegant Dark), terminal themes, font family & size |
| [Colors](colors.md) | Color profile, text/background/cursor/selection colors, cursor blink, the 16-color ANSI palette |
| [Terminal](terminal.md) | Columns/rows, scrollback, encoding, SSH keep-alive, connection retries, drag-drop, timestamps |
| [Window](window.md) | Window geometry restore, fixed geometry, dashboard state, menu bar |
| [Logging](logging.md) | Terminal log directory, retention and format |
| [Backup](backup.md) | Encryption type (ZIP-password / GPG), max backup count |
| [Updates](updates.md) | Automatic update checking and interval |
| [Security](security.md) | Master-password prompt, change master password, temporary SSH keys |
| [Privacy](../../about/anonymous-data.md) | Opt-in for anonymous usage statistics (Aptabase, EU/GDPR) |
| [Language](language.md) | UI language selection (8 built-in) + auto-detect |
| [Translation](translation.md) | Dynamic-translation provider, API key, target language, generate language file |
| [Video](video.md) | Terminal recording / `ffmpeg` video export |
| [AI](ai.md) | AI features, agent execution, profiles, reasoning effort, token quota, internet tools |
| [AI Skills](ai-skills.md) | Skill definitions, auto-detection, import/export |

The settings dialog also exposes **SFTP Manager**, **Editor** and **Snippet Editor** tabs, which set file-manager and Monaco-editor defaults (font, colors, cursor) for those tools.

!!! info "Completeness"
    The per-tab pages enumerate every individual setting. Coverage is verified automatically against the application's setting keys, so no setting is left undocumented.

## Visual reference

A few of the configuration tabs (the per-tab pages show each in detail):

<div class="grid" markdown>

**Colors** — terminal palette, cursor and ANSI colors
{ .grid-caption }

![Colors settings tab](../../assets/screenshots/settings/colors.png)

**Terminal** — scrollback, encoding, keep-alive, retries
{ .grid-caption }

![Terminal settings tab](../../assets/screenshots/settings/terminal.png)

**Backup** — max backups, ZIP-password or GPG encryption
{ .grid-caption }

![Backup settings tab](../../assets/screenshots/settings/backup.png)

**AI** — agent execution, profiles, internet tools
{ .grid-caption }

![AI settings tab](../../assets/screenshots/settings/ai.png)

</div>
