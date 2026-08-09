# Referenz zu den Einstellungen

Die Einstellungen von korTTY sind unter **Konfiguration → Globale Einstellungen…** verfügbar. Sie werden in `~/.kortty/global-settings.xml` gespeichert. Diese Referenz dokumentiert **jede** Einstellung, geordnet nach Registerkarten.

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
| [Aussehen, Themen und Schriftart](appearance.md) | App-Design (Standard, Matrix, Holografisch, Klingonisch, Elegant Dark), Terminal-Themen, Schriftfamilie und -größe |
| [Farben](colors.md) | Farbprofil, Text-/Hintergrund-/Cursor-/Auswahlfarben, Cursor-Blinken, die 16-Farben-ANSI-Palette |
| [Terminal](terminal.md) | Spalten/Zeilen, Scrollback, Kodierung, SSH-Keep-Alive, Überprüfung des SSH-Hostschlüssels, Verbindungswiederholungen, Drag-Drop, Zeitstempel |
| [Fenster](window.md) | Wiederherstellung der Fenstergeometrie, feste Geometrie, Dashboard-Status, Menüleiste |
| [Ressourcen](resources.md) | Opt-in-JVM-Heap/GC-Profil (Ausgeglichen/Hoch/Maximum) für größere Arbeitslasten |
| [Protokollierung](logging.md) | Verzeichnis, Aufbewahrung und Format des Terminalprotokolls |
| [Sicherung](backup.md) | Verschlüsselungstyp (ZIP-Passwort / GPG), maximale Backup-Anzahl |
| [Aktualisierungen](updates.md) | Automatische Update-Überprüfung und -Intervall |
| [Sicherheit](security.md) | Master-Passwort-Abfrage, Master-Passwort ändern, temporäre SSH-Schlüssel |
| [Privatsphäre](../../about/anonymous-data.md) | Opt-in für anonyme Nutzungsstatistiken (Aptabase, EU/DSGVO) |
| [Sprache](language.md) | Auswahl der UI-Sprache (8 integriert) + automatische Erkennung |
| [Übersetzung](translation.md) | Externer oder lokaler KI-Übersetzungsanbieter, Anmeldeinformationen, Zielsprache, Sprachdatei generieren |
| [Video](video.md) | Terminalaufzeichnung / `ffmpeg` Videoexport |
| [KI](ai.md) | KI-Funktionen, Agentenausführung, HTTP/CLI/eingebettete Profile, Eingabeaufforderungsvoreinstellungen, Reasoning, Quote, Internet-Tools |
| [SFTP-Manager](sftp.md) | Automatisches Schließen der SFTP-Registerkarte, Remote-ZIP-Standardeinstellungen, JobScheduler rsync-Binärdatei |
| [Editor](editor.md) | Cursorstil und -farbe für Editor-Registerkarten |
| [Snippet-Editor](snippet-editor.md) | Schriftart-, Farb- und Cursor-Überschreibungen für Snippet-Fenster |

Lokale Modell-Downloads, Text-/Coding-Rollenrouting, Einbettungsauswahl, llama.cpp-Laufzeitrichtlinie, Wissensquellensynchronisierung und das [KI-Fähigkeiten](ai-skills.md) Bibliothek live in **AI > AI Manager** und nicht im globalen Einstellungsfenster; sehen [Lokale Modelle](../../features/local-models.md) Und [RAG-Wissensspeicher](../../features/rag.md).

!!! info "Vollständigkeit"
    Auf den Registerkartenseiten werden alle einzelnen Einstellungen aufgeführt. Die Abdeckung wird automatisch anhand der Einstellungsschlüssel der Anwendung überprüft, sodass keine Einstellung undokumentiert bleibt.

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

**KI** – Agentenausführung, Profile, Eingabeaufforderungseinstellungen, Internet-Tools
{ .grid-caption }

![AI settings tab](../../assets/screenshots/settings/ai.png)

</div>
