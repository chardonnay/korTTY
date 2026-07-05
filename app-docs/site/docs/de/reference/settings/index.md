# Referenz zu den Einstellungen

Die Einstellungen von korTTY sind unter **Konfiguration → Globale Einstellungen…** verfügbar. Sie werden gespeichert
in `~/.kortty/global-settings.xml`. Diese Referenz dokumentiert **jede** Einstellung,
Nach Registerkarten geordnet.

Auf jeder Registerkartenseite werden die Einstellungen als Tabelle aufgeführt:

| Spalte | Bedeutung |
| --- | --- |
| Einstellung | Die im Dialogfeld angezeigte Bezeichnung |
| Geben Sie | ein umschalten · Dropdown · Nummer · Text · Farbe · Pfad |
| Werte | Zulässige Werte/Bereich |
| Standard | Standardwert |
| Gespeichert als | Das Feld in `global-settings.xml` |

## Registerkarten „Einstellungen“.

| Tab | Was es steuert |
| --- | --- |
| [Erscheinungsbild, Themen und Schriftart](appearance.md) | App-Design (Standard, Matrix, Holografisch, Klingonisch, Elegant Dark), Terminal-Themen, Schriftfamilie und -größe |
| [Farben](colors.md) | Farbprofil, Text-/Hintergrund-/Cursor-/Auswahlfarben, Cursor-Blinken, die 16-Farben-ANSI-Palette |
| [Terminal](terminal.md) | Spalten/Zeilen, Scrollback, Kodierung, SSH-Keep-Alive, Verbindungswiederholungen, Drag-Drop, Zeitstempel |
| [Fenster](window.md) | Wiederherstellung der Fenstergeometrie, feste Geometrie, Dashboard-Status, Menüleiste |
| [Logging](logging.md) | Terminalprotokollverzeichnis, Aufbewahrung und Format |
| [Sicherung](backup.md) | Verschlüsselungstyp (ZIP-Passwort / GPG), maximale Backup-Anzahl |
| [Updates](updates.md) | Automatische Aktualisierungsprüfung und -intervall |
| [Sicherheit](security.md) | Master-Passwort-Abfrage, Master-Passwort ändern, temporäre SSH-Schlüssel |
| [Datenschutz](../../about/anonymous-data.md) | Opt-in für anonyme Nutzungsstatistiken (Aptabase, EU/DSGVO) |
| [Sprache](language.md) | Auswahl der UI-Sprache (8 integriert) + automatische Erkennung |
| [Übersetzung](translation.md) | Anbieter für dynamische Übersetzung, API-Schlüssel, Zielsprache, Sprachdatei generieren |
| [Video](video.md) | Terminalaufzeichnung / `ffmpeg`-Videoexport |
| [AI](ai.md) | KI-Funktionen, Agentenausführung, Profile, Argumentationsaufwand, Token-Quote, Internet-Tools |
| [KI-Fähigkeiten](ai-skills.md) | Fähigkeitsdefinitionen, automatische Erkennung, Import/Export |

Im Einstellungsdialog werden auch die Registerkarten **SFTP-Manager**, **Editor** und **Snippet-Editor** angezeigt, die die Standardeinstellungen für Dateimanager und Monaco-Editor (Schriftart, Farben, Cursor) für diese Tools festlegen.

!!! Info „Vollständigkeit“
Auf den Registerkartenseiten werden alle einzelnen Einstellungen aufgeführt. Die Abdeckung ist überprüft
automatisch mit den Einstellungsschlüsseln der Anwendung verknüpft, sodass keine Einstellung übrig bleibt
undokumentiert.

## Visuelle Referenz

Einige der Konfigurationsregisterkarten (die einzelnen Registerkartenseiten zeigen jede im Detail):

<div class="grid" markdown>

**Farben** – Terminalpalette, Cursor und ANSI-Farben
{ .grid-caption }

![Colors settings tab](../../assets/screenshots/settings/colors.png)

**Terminal** – Scrollback, Kodierung, Keep-Alive, Wiederholungsversuche
{ .grid-caption }

![Terminal settings tab](../../assets/screenshots/settings/terminal.png)

**Backup** – maximale Backups, ZIP-Passwort oder GPG-Verschlüsselung
{ .grid-caption }

![Backup settings tab](../../assets/screenshots/settings/backup.png)

**KI** – Agentenausführung, Profile, Internet-Tools
{ .grid-caption }

![AI settings tab](../../assets/screenshots/settings/ai.png)

</div>
