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

The [icon toolbar](../../getting-started/main-window.md#customizing-the-icon-toolbar) offers a direct button for each main settings tab. Right-click the toolbar, choose **Customize toolbar…**, select **Settings**, and add the desired shortcut. It opens the existing settings dialog on that tab.

## Searching the settings { #finding-a-setting }

The search line at the top of the settings window looks through every tab at once: the labels, check boxes, buttons and section headings, including tabs you have not opened yet. Type at least two characters and the matching settings are listed under the field, each with the tab it is on; a whole tab is listed as **Page** when its name matches. Case, accents and umlaut spellings do not matter (`schriftgroesse` finds **Schriftgröße**), and every word must match, so `terminal scroll` finds **Scrollback** on the Terminal tab. Settings that are hidden in the current state, such as fields of another AI connection mode, are not offered.

Click a result, or pick it with ++up++ / ++down++ and press ++enter++: the dialog switches to that tab, scrolls the setting into view, colours it together with the controls on its row and puts the keyboard focus on its control, so you can change it straight away. The colouring fades after a few seconds or when you search again. The **✕** at the right end of the search line empties it with one click. ++cmd+f++ (++ctrl+f++ on Windows and Linux) jumps to the search line from anywhere in the dialog, and ++esc++ there first clears the search before it closes the dialog; ++enter++ in the search line never saves the settings.

![Searching the settings for "scroll"](../../assets/screenshots/settings/search.png)

![The chosen setting, coloured on its tab](../../assets/screenshots/settings/search-hit.png)

## Manual for each tab { #manual-for-each-tab }

**Manual for this page** at the right of the navigation bar opens the built-in manual at the page that documents the tab you are on, for example this reference's [Terminal](terminal.md) page while the Terminal tab is shown, and the [Anonymous data](../../about/anonymous-data.md) page for the Privacy tab. Its tooltip names the tab. The manual opens in the language of the user interface.

## Settings tabs

| Tab | What it controls |
| --- | --- |
| [Appearance, themes & font](appearance.md) | App design (the seven AtlantaFX themes and the korTTY designs), terminal themes, font family & size, UI font size |
| [Colors](colors.md) | Color profile, text/background/cursor/selection colors, cursor blink, the 16-color ANSI palette |
| [Terminal](terminal.md) | Columns/rows, scrollback, encoding, keyword highlighting, quick select, paste protection, shell integration, links, SSH keep-alive, SSH host key verification, connection retries, drag-drop, timestamps, coding-agent detection, Control API and MCP server |
| [Messaging](messaging.md) | Desktop notifications for coding agents, terminal events and korTTY AI runs; notification timing |
| [Video](video.md) | Terminal recording / `ffmpeg` video export |
| [Backup](backup.md) | Encryption type (ZIP-password / GPG), max backup count |
| [Logging](logging.md) | Terminal log directory and retention; session journal storage, AI summaries, interval and profile |
| [Export](export.md) | PDF watermark and document footer for exported session journals, AI chats and code analysis reports |
| [Updates](updates.md) | Automatic update checking and interval |
| [Window](window.md) | Toolbar icon size, window geometry restore, fixed geometry, dashboard state, tab behaviour, what korTTY restores at startup |
| [Keyboard](keyboard.md) | Your own shortcuts for korTTY's commands: change, remove or reset them, with a check for keys the shell needs and for conflicts |
| [Resources](resources.md) | Opt-in JVM heap/GC profile (Balanced / High / Maximum) for larger workloads |
| [Security](security.md) | Master-password prompt, change master password, temporary SSH keys |
| [Privacy](../../about/anonymous-data.md) | Consent for anonymous usage statistics (Aptabase, EU/GDPR) |
| [SFTP Manager](sftp.md) | SFTP tab auto-close, remote ZIP defaults, JobScheduler rsync binary |
| [Editor](editor.md) | Cursor style and color for editor tabs |
| [Snippet Editor](snippet-editor/index.md) | Font, color and cursor overrides for snippet windows |
| [Language](language.md) | UI language selection (8 built-in) + auto-detect |
| [Translation](translation.md) | External or local-AI translation provider, credentials, target language, generate language file |

AI has no tab in this window: every AI setting, from the feature switches and the terminal agent to the profiles, local models, knowledge stores and the [AI Skills](ai-skills.md) library, lives in **AI > AI Manager**; see [AI](ai.md), [Local models](../../features/local-models.md) and [RAG knowledge stores](../../features/rag.md).

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

</div>
