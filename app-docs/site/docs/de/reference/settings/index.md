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

Das [Symbolleiste](../../getting-started/main-window.md#anpassung-der-symbolleiste) Bietet einen direkten Button für jeden Haupt-Einstellungs-Tab. Klicken Sie mit der rechten Maustaste auf die Symbolleiste, wählen Sie **Icon-Leiste anpassen…**, wählen Sie **Einstellungen** und fügen Sie die gewünschte Tastenkombination hinzu. Es öffnet den vorhandenen Einstellungsdialog auf diesem Tab.

## Einstellungen durchsuchen { #finding-a-setting }

Die Suchzeile oben im Einstellungsfenster durchsucht alle Tabs auf einmal: die Beschriftungen, Kontrollkästchen, Schaltflächen und Abschnittsüberschriften, einschließlich Tabs, die Sie noch nicht geöffnet haben. Geben Sie mindestens zwei Zeichen ein, und die passenden Einstellungen werden unter dem Feld aufgelistet, jeweils mit dem Tab, auf dem sie sich befinden; ein ganzer Tab wird als **Seite** aufgelistet, wenn sein Name übereinstimmt. Groß-/Kleinschreibung, Akzente und Umlautschreibungen sind egal (`schriftgroesse` findet **Schriftgröße**), und jedes Wort muss übereinstimmen, daher findet `terminal scroll` **Zurückscrollen** auf dem Terminal-Tab. Einstellungen, die im aktuellen Zustand ausgeblendet sind, wie z. B. Felder eines anderen KI-Verbindungsmodus, werden nicht angeboten.

Klicken Sie auf ein Ergebnis oder wählen Sie es mit ++up++ / ++down++ aus und drücken Sie ++enter++: Der Dialog wechselt zu diesem Tab, scrollt die Einstellung in den sichtbaren Bereich, hebt sie zusammen mit den Bedienelementen in ihrer Zeile farblich hervor und setzt den Tastaturfokus auf ihr Bedienelement, sodass Sie es sofort ändern können. Die Hervorhebung verblasst nach wenigen Sekunden oder wenn Sie erneut suchen. Das **✕** am rechten Ende der Suchzeile leert diese mit einem Klick. ++cmd+f++ (++ctrl+f++ unter Windows und Linux) springt von überall im Dialog zur Suchzeile, und ++esc++ dort löscht zunächst die Suche, bevor der Dialog geschlossen wird; ++enter++ in der Suchzeile speichert die Einstellungen nie.

![Searching the settings for "scroll"](../../assets/screenshots/settings/search.png)

![The chosen setting, coloured on its tab](../../assets/screenshots/settings/search-hit.png)

## Anleitung für jeden Tab { #manual-for-each-tab }

**Anleitung zu dieser Seite** rechts in der Navigationsleiste öffnet die integrierte Anleitung auf der Seite, die den aktuell angezeigten Tab dokumentiert – zum Beispiel die [Terminal](terminal.md)-Seite dieser Referenz, wenn der Terminal-Tab angezeigt wird, und die [Anonyme Daten](../../about/anonymous-data.md)-Seite für den Datenschutz-Tab. Der Tooltip benennt den Tab. Die Anleitung öffnet sich in der Sprache der Benutzeroberfläche.

## Einstellungsregisterkarten

| Registerkarte | Was es steuert |
| --- | --- |
| [Aussehen, Themen & Schrift](appearance.md) | (die sieben AtlantaFX-Themen und die korTTY-Designs), Terminal-Themen, Schriftfamilie & Größe, UI-Schriftgröße|
| [Farben](colors.md) | Farbprofil, Text/Hintergrund/Cursor/Selection-Farben, Cursor-Blinken, die 16-Farben ANSI-Palette|
| [Terminal](terminal.md) | Spalten/Zeilen, Scrollback, Kodierung, Schlüsselwort-Hervorhebung, Schnell-Auswahl, Einfüge-Schutz, Shell-Integration, Links, SSH Keep-Alive, SSH-Host-Key-Prüfung, Verbindungsversuche, Drag-Drop, Zeitstempel, Coding-Agent-Erkennung, Steuerungs-API und MCP-Server|
| [Messaging](messaging.md) | Desktop-Benachrichtigungen für Coding-Agenten, Terminalereignisse und korTTY KI-Läufe; Benachrichtigungstiming |
| [Video](video.md) | Terminal recording / `ffmpeg` Videoexport |
| [Sicherung](backup.md) | Verschlüsselungstyp (ZIP-Passwort / GPG), maximale Sicherungsanzahl |
| [Protokollierung](logging.md) | Terminal log directory and retention; session journal storage, AI summaries, interval and profile |
| [Export](export.md) | PDF-Wasserzeichen und Dokumentenfußzeile für exportierte Sitzungsjournale, KI-Chats und Code-Analyseberichte |
| [Aktualisierungen](updates.md) | Automatic update checking and interval |
| [Fenster](window.md) | Symbolleisten-Icon-Größe, Fenster-Geometrie-Wiederherstellung, feste Geometrie, Dashboard-Zustand, Tab-Verhalten, was korTTY beim Start wiederherstellt|
| [Tastatur](keyboard.md) | Eigene Tastaturkürzel für die Befehle von korTTY: ändern, entfernen oder zurücksetzen, mit einer Prüfung auf Tasten, die die Shell braucht, und auf Konflikte |
| [Ressourcen](resources.md) | Opt-in JVM Heap/GC-Profil (Balanced / High / Maximum) für größere Workloads|
| [Sicherheit](security.md) | Passwortabfrage für Master-Passwort, Master-Passwort ändern, temporäre SSH-Schlüssel |
| [Datenschutz](../../about/anonymous-data.md) | Einwilligung für anonyme Nutzungsstatistiken (Aptabase, EU/GDPR) |
| [SFTP-Manager](sftp.md) | SFTP-Tab Auto-Schließen, Remote-ZIP-Vorgaben, JobScheduler rsync-Binary |
| [Editor](editor.md) | Cursortyp und Farbe für Editor-Tabblätter |
| [Snippet Editor](snippet-editor/index.md) | Schriftart-, Farb- und Cursor-Überschreibungen für Snippet-Fenster |
| [Sprache](language.md) | UI-Sprachauswahl (8 integrierte) + Auto-Erkennung |
| [Übersetzung](translation.md) | Externer oder lokaler KI-Übersetzungsanbieter, Zugangsdaten, Zielsprache, Sprachdatei generieren |

KI hat in diesem Fenster keinen Tab: jede KI-Einstellung, von den Funktionsschaltern und dem Terminal-Agent bis zu den Profilen, lokalen Modellen, Wissensspeichern und der [KI-Skills](ai-skills.md)-Bibliothek, befindet sich in **KI > KI-Manager**; siehe [KI](ai.md), [Lokale Modelle](../../features/local-models.md) und [RAG-Wissensspeicher](../../features/rag.md).

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

</div>
