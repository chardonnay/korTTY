# Einstellungsreferenz

Die Einstellungen von korTTY sind unter **Konfiguration → Globale Einstellungen…** verfügbar. Sie werden in `~/.kortty/global-settings.xml` gespeichert. Diese Referenz dokumentiert **jede** Einstellung, geordnet nach Registerkarten.

Auf jeder Registerkartenseite werden die Einstellungen als Tabelle aufgeführt:

| Spalte | Bedeutung |
| --- | --- |
| Einstellung | Die im Dialogfeld angezeigte Bezeichnung |
| Typ | Umschalten · Dropdown · Nummer · Text · Farbe · Pfad |
| Werte | Zulässige Werte/Bereich |
| Standardwert | Standardwert |
| Gespeichert als | Das Feld in `global-settings.xml` |

## Einstellungsregisterkarten

| Registerkarte | Was es steuert |
| --- | --- |
| [Appearance, themes & font](appearance.md) | App design (Default, Matrix, Holographic, Klingon, Elegant Dark), terminal themes, font family & size, UI font size |
| [Colors](colors.md) | Color profile, text/background/cursor/selection colors, cursor blink, the 16-color ANSI palette |
| [Terminal](terminal.md) | Columns/rows, scrollback, encoding, SSH keep-alive, SSH host key verification, connection retries, drag-drop, timestamps |
| [Fenster](window.md) | Window geometry restore, fixed geometry, dashboard state, menu bar |
| [Resources](resources.md) | Opt-in JVM heap/GC profile (Balanced / High / Maximum) for larger workloads |
| [Protokollierung](logging.md) | Terminal log directory and retention; session journal storage, AI summaries, interval and profile |
| [Export](export.md) | PDF watermark and document footer for exported session journals and AI chats |
| [Backup](backup.md) | Encryption type (ZIP-password / GPG), max backup count |
| [Aktualisierungen](updates.md) | Automatic update checking and interval |
| [Security](security.md) | Master-password prompt, change master password, temporary SSH keys |
| [Privacy](../../about/anonymous-data.md) | Consent for anonymous usage statistics (Aptabase, EU/GDPR) |
| [Language](language.md) | UI language selection (8 built-in) + auto-detect |
| [Translation](translation.md) | External or local-AI translation provider, credentials, target language, generate language file |
| [Video](video.md) | Terminal recording / `ffmpeg` Videoexport |
| [AI](ai.md) | AI features, agent execution, HTTP/CLI/embedded profiles, prompt presets, reasoning, image input (vision), quota, internet tools |
| [SFTP Manager](sftp.md) | SFTP tab auto-close, remote ZIP defaults, JobScheduler rsync binary |
| [Editor](editor.md) | Cursor style and color for editor tabs |
| [Snippet Editor](snippet-editor/index.md) | Schriftart-, Farb- und Cursor-Überschreibungen für Snippet-Fenster |

Local-model downloads, Text/Coding role routing, embedding selection, llama.cpp runtime policy, knowledge-source synchronization, and the [AI Skills](ai-skills.md) library live in **KI > KI-Manager** rather than the global Settings window; see [Local models](../../features/local-models.md) and [RAG knowledge stores](../../features/rag.md).

!!! info "Vollständigkeit"
    Auf den Registerkartenseiten werden alle einzelnen Einstellungen aufgeführt. Die Abdeckung wird automatisch anhand der Einstellungsschlüssel der Anwendung überprüft, sodass keine Einstellung undokumentiert bleibt.

## Visuelle Referenz

Einige der Konfigurationsregisterkarten (die einzelnen Registerkartenseiten zeigen jede im Detail):

<div class="grid" markdown>

**Colors** — terminal palette, cursor and ANSI colors
{ .grid-caption }

![Colors settings tab](../../assets/screenshots/settings/colors.png)

**Terminal** – Scrollback, Kodierung, Keep-Alive, Wiederholungsversuche
{ .grid-caption }

![Terminal settings tab](../../assets/screenshots/settings/terminal.png)

**Backup** – maximale Backups, ZIP-Passwort oder GPG-Verschlüsselung
{ .grid-caption }

![Backup settings tab](../../assets/screenshots/settings/backup.png)

**KI** – Agentenausführung, Profile, Eingabeaufforderungseinstellungen, Internet-Tools
{ .grid-caption }

![AI settings tab](../../assets/screenshots/settings/ai.png)

</div>
