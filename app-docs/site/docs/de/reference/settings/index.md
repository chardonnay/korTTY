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
| [Aussehen, Themen & Schriftart](appearance.md) | App-Design (Standard, Matrix, Holographisch, Klingon, Elegantes Dunkel), Terminal-Themen, Schriftfamilie & Größe, UI-Schriftgröße|
| [Farben](colors.md) | Farbprofil, Text/Hintergrund/Cursor/Selection-Farben, Cursor-Blinken, die 16-Farben ANSI-Palette|
| [Terminal](terminal.md) | Spalten/Zeilen, Scrollback, Kodierung, SSH Keep-Alive, SSH-Host-Key-Prüfung, Verbindungsversuche, Drag-&-Drop, Zeitstempel|
| [Fenster](window.md) | Window geometry restore, fixed geometry, dashboard state, menu bar |
| [Tastatur](keyboard.md) | Eigene Tastaturkürzel für die Befehle von korTTY: ändern, entfernen oder zurücksetzen, mit einer Prüfung auf Tasten, die die Shell braucht, und auf Konflikte |
| [Ressourcen](resources.md) | Opt-in JVM Heap/GC-Profil (Balanced / High / Maximum) für größere Workloads|
| [Protokollierung](logging.md) | Terminal log directory and retention; session journal storage, AI summaries, interval and profile |
| [Export](export.md) | PDF-Wasserzeichen und Dokumentenfußzeile für exportierte Sitzungsjournale, KI-Chats und Code-Analyseberichte |
| [Sicherung](backup.md) | Verschlüsselungstyp (ZIP-Passwort / GPG), maximale Sicherungsanzahl |
| [Aktualisierungen](updates.md) | Automatic update checking and interval |
| [Sicherheit](security.md) | Passwortabfrage für Master-Passwort, Master-Passwort ändern, temporäre SSH-Schlüssel |
| [Datenschutz](../../about/anonymous-data.md) | Einwilligung für anonyme Nutzungsstatistiken (Aptabase, EU/GDPR) |
| [Sprache](language.md) | UI-Sprachauswahl (8 integrierte) + Auto-Erkennung |
| [Übersetzung](translation.md) | Externer oder lokaler KI-Übersetzungsanbieter, Zugangsdaten, Zielsprache, Sprachdatei generieren |
| [Video](video.md) | Terminal recording / `ffmpeg` Videoexport |
| [KI](ai.md) | KI-Funktionen, Agenten-Ausführung, HTTP/CLI/embedded Profile, Prompt-Vorlagen, Logik, Bildeingabe (Vision), Kontingent, Internet-Tools |
| [SFTP-Manager](sftp.md) | SFTP-Tab Auto-Schließen, Remote-ZIP-Vorgaben, JobScheduler rsync-Binary |
| [Editor](editor.md) | Cursortyp und Farbe für Editor-Tabblätter |
| [Snippet Editor](snippet-editor/index.md) | Schriftart-, Farb- und Cursor-Überschreibungen für Snippet-Fenster |

Local-model downloads, Text/Coding role routing, embedding selection, llama.cpp runtime policy, knowledge-source synchronization, and the [AI Skills](ai-skills.md) library live in **KI > KI-Manager** rather than the global Settings window; see [Local models](../../features/local-models.md) and [RAG knowledge stores](../../features/rag.md).

!!! info "Vollständigkeit"
    Auf den Registerkartenseiten werden alle einzelnen Einstellungen aufgeführt. Die Abdeckung wird automatisch anhand der Einstellungsschlüssel der Anwendung überprüft, sodass keine Einstellung undokumentiert bleibt.

## Visuelle Referenz

Einige der Konfigurationsregisterkarten (die einzelnen Registerkartenseiten zeigen jede im Detail):

<div class="grid" markdown>

**Farben** — Terminalpalette, Cursor und ANSI-Farben
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
