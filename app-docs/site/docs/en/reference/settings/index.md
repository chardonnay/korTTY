# Settings reference

korTTY's settings live in **Configuration → Global Settings…**. They are stored
in `~/.kortty/global-settings.xml`. This reference documents **every** setting,
organized by tab.

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
| Appearance | App design (Normal, Matrix, Holographic, Klingon, Elegant Dark), theme |
| Font | Terminal font family and size |
| Colors | ANSI palette, cursor color and blink, selection colors |
| Terminal | Scrollback, emulation, keep-alive, timeout/retry, bell |
| Tab | Tab title format, close behavior, ordering |
| Window | Window geometry restore, multi-window behavior |
| Logging | Terminal logging format (plain/XML/JSON) and rotation |
| Backup | Encryption type (password / GPG), max backup count |
| Updates | Update check interval, channel, suppression |
| Security | Master-password prompt, temporary SSH keys |
| Language | UI language selection (8 built-in) |
| Translation | Dynamic translation provider, API key, target language |
| Video | `ffmpeg` path, export defaults |
| AI | AI profiles, providers, internet access, reasoning effort, token quota |
| AI Skills | Skill definitions, relevance classification, editor |

!!! info "Completeness"
    The per-tab pages enumerate every individual setting. Coverage is verified
    automatically against the application's setting keys, so no setting is left
    undocumented.

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
