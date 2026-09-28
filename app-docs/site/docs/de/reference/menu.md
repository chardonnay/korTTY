# Menüreferenz

Jedes Element in der Menüleiste von korTTY, mit seiner Tastenkombination (falls definiert) und ihrer Funktion. Die Menüleiste kann mit ++ctrl+shift+l++ ausgeblendet und wieder angezeigt werden, indem Sie mit der rechten Maustaste auf das Terminal oder die Statusleiste klicken.

## Datei

| Element | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Neuer Tab | ++ctrl+t++ | Öffnen Sie die Schnellverbindung in einem neuen Terminal-Tab |
| Tab schließen | ++ctrl+w++ | Schließen Sie die aktive Terminal-Registerkarte |
| Alle Tabs schließen | | Alle Tabs im aktuellen Fenster schließen |
| Neues Fenster | ++ctrl+shift+n++ | Öffnen Sie ein zusätzliches, unabhängiges Hauptfenster |
| Fenster schließen | ++ctrl+shift+w++ | Schließen Sie das aktuelle Fenster |
| Projekt öffnen… | ++ctrl+o++ | Ein gespeichertes Projekt wiederherstellen (Fenster, Registerkarten, Layout) |
| Projekt speichern… | ++ctrl+s++ | Speichern Sie die aktuelle Sitzung als Projekt (`.kortty`) |
| Backup erstellen… | ++ctrl+shift+b++ | Erstellen Sie ein verschlüsseltes Backup (ZIP-Passwort oder GPG) |
| Sicherung importieren… | | Wiederherstellung aus einer Sicherungsdatei |
| Aufhören | ++ctrl+q++ | Beenden Sie korTTY |

Verbindungs-Einträge (Schnellverbindung, Verbindungen verwalten/importieren/exportieren) befinden sich im [Verbindungen](#verbindungen)-Menü.

## Bearbeiten

| Element | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Schneiden | ++ctrl+x++ | Ausschneiden (deaktiviert für Anschlusslaschen) |
| Kopie | ++ctrl+c++ | Kopieren Sie die Terminalauswahl |
| Paste | ++ctrl+v++ | In das Terminal einfügen |
| Finden… | ++ctrl+f++ | Durchsuchen Sie die aktive Registerkarte (Terminal-Scrollback oder geöffneter Editor). |

## Verbindungen

| Artikel | Beschreibung |
| --- | --- |
| Schnellverbindung… | Stellen Sie eine Verbindung zu einem Host her, ohne zu speichern |
| Verbindungen verwalten… | Öffnen Sie den Verbindungsmanager (Struktur, Suche, Bearbeiten) |
| Importieren… | Verbindungen von anderen Clients importieren |
| Export… | Exportverbindungen |
| SFTP-Client… | Öffnen Sie den Dual-Panel-SFTP-Dateimanager |

## Sicherheit

| Artikel | Beschreibung |
| --- | --- |
| Anmeldeinformationen… | Gespeicherte Anmeldeinformationen verwalten (verschlüsselt) |
| GPG-Schlüssel… | GPG-Schlüssel verwalten, die für die Backup-Verschlüsselung verwendet werden |
| SSH-Schlüssel… | SSH-Schlüssel und Passphrasen verwalten |

## Konfiguration

| Artikel | Beschreibung |
| --- | --- |
| Globale Einstellungen… | Öffnen Sie den globalen Einstellungsdialog (alle Registerkarten) |
| System-Ruhezustand verhindern | Aktivieren Sie unter macOS und Windows die aktivitätsbasierte Verhinderung des System-Ruhezustands. Der Computer bleibt nur dann wach, wenn ein Terminal angeschlossen ist, ein aktivierter Scheduler-Job in Zukunft ausgeführt wird oder ausgeführt wird oder eine KI-Anfrage ausgeführt wird. Wenn keine dieser Aktivitäten erfolgt, bleibt der Systemschlaf auch dann verfügbar, wenn das Element überprüft wird. Der Display-Ruhezustand ist davon nicht betroffen. Unter Linux ist das Element sichtbar, aber deaktiviert. |

Für jede einzelne Einstellung siehe die [Settings-Referenz ](settings/index.md).

## Tools

![Tools menu](../assets/screenshots/main/menu-tools.png)

| Artikel | Beschreibung |
| --- | --- |
| Snippet-Manager… | Erstellen, bearbeiten, organisieren, senden und exportieren Sie Befehls-Snippets in einem Arbeitsbereich: die Bibliothek und die Snippets, die Sie als Tabs bearbeiten, jeweils mit ihrer gespeicherten Vollständigen Code-Analyse |
| JobScheduler… | Hintergrundbefehl/Snippet/AI-Agent/AI-Swarm/SFTP/Rsync-Jobs planen |
| Videomanager… | Verwalten Sie Terminalaufzeichnungen und exportieren Sie sie über WebM/MKV `ffmpeg` |
| Terminalaufzeichnung starten/stoppen | Aufzeichnung des aktiven Terminals umschalten (++ctrl+shift+e++) |
| Sitzungsjournale… | Manage [session journals](../features/session-journal.md): Suchen, Öffnen, Umbenennen, Beschreiben, Exportieren und Löschen sowie Festlegen der Journaloptionen (++ctrl+alt+j++) |
| Sitzungsjournal starten/stoppen | Toggle the session journal of the active terminal tab; starting mid-session imports the existing scrollback (++ctrl+alt+t++) |
| Journal-Screenshot hinzufügen | Erstellen Sie einen Schnappschuss des aktiven Terminals in das laufende Sitzungsjournal (++ctrl+alt+c++) |
| ASCII-Art… | Zwei Tabs in einem Dialog: **Text-Banner** rendert Text als FIGlet-Banner in mehreren Schriftstilen, **KI-Bild** lässt ein KI-Profil ein Thema als ASCII-Art zeichnen |

Die drei Session-Journal-Elemente bleiben sichtbar, werden jedoch deaktiviert, wenn eine [Unternehmensrichtlinie](../features/session-journal.md#unternehmensrichtlinie) die Session-Journal-Funktion verweigert.

## AI

![AI menu](../assets/screenshots/main/menu-ai.png)

| Artikel | Beschreibung |
| --- | --- |
| KI-Manager… | Manage AI profiles, integrated GGUF models, RAG knowledge stores, the AI Skills library, and Text/Coding roles |
| Gespeicherte Chats… | Öffnen Sie die gespeicherten KI-Chatgespräche direkt in ihrem eigenen Fenster; ein erneutes Aufrufen bringt das vorhandene Fenster nach vorne |
| AI Agent… | Öffnen Sie den Terminal AI Agent |
| KI-Planung… | Öffnen Sie den KI-Planungsworkflow |
| KI-Schwarm… | Senden Sie eine KI-Aufgabe an viele Server und vergleichen Sie die Antworten (++ctrl+alt+s++) |

**KI-Manager** wird als modales Fenster geöffnet, sodass es sichtbar bleiben kann, während Sie das Hauptfenster verwenden. Durch einen erneuten Aufruf wird derselbe Manager für dieses Hauptfenster wiederhergestellt und fokussiert, anstatt ein Duplikat zu erstellen. Seine fünf Abschnitte sind **Profile** (Verbindungsmodus, Modell, Eingabeaufforderungsvoreinstellung, Reasoning, Internetzugang und Token-Budget), **Lokale Modelle** (Suchen/Herunterladen/Importieren von GGUF-Modellen), **Wissensspeicher** (RAG-Quellen), **KI-Skills** (die Fertigkeitsbibliothek, aus dem globalen Einstellungsdialog hierher verschoben) und **Lokale KI** (Text-/Codierungs-/Einbettungsrollen und die lokale Laufzeit). Der geöffnete primäre Abschnitt bleibt durch eine fette Akzentunterstreichung gekennzeichnet, nachdem Sie den Fokus auf seine Tabellen, Felder oder Schaltflächen verschoben haben:

![KI-Manager with Local Models selected and persistently underlined](../assets/screenshots/ai/ai-manager.png)

## Plugins

| Artikel | Beschreibung |
| --- | --- |
| Terminal-Effekte… | Terminal-Effekt-Plugins aktivieren/deaktivieren, konfigurieren, importieren/exportieren |

## Ansicht

| Element | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Dashboard anzeigen | ++ctrl+shift+d++ | Schalten Sie das Verbindungs-Dashboard um |
| Show Command Timestamps | ++ctrl+shift+t++ | Schalten Sie die Inline-Befehlszeitstempel um |
| Menüleiste anzeigen | ++ctrl+shift+l++ | Schalten Sie die Menüleiste um |
| File Browser ▸ Show on Left | ++ctrl+shift+k++ | Docken Sie das Lokal an [Dateibrowser](../features/file-browser.md) links vom Terminal; Wenn Sie die aktive Seite deaktivieren, wird sie ausgeblendet |
| Dateibrowser ▸ Rechts anzeigen | ++ctrl+shift+r++ | Docken Sie das Lokal an [Dateibrowser](../features/file-browser.md) rechts vom Terminal; Wenn Sie die aktive Seite deaktivieren, wird sie ausgeblendet |
| Vergrößern | ++alt+plus++ | Erhöhen Sie die Schriftgröße des Terminals |
| Herauszoomen | ++alt+minus++ | Verringern Sie die Schriftgröße des Terminals |
| Zoom zurücksetzen | ++alt+0++ | Setzen Sie die Schriftgröße des Terminals zurück |
| Hintergrundtransparenz | | Schieberegler (0–100 %), der den Terminalhintergrund auf dem Desktop durchscheinen lässt, während der Text scharf bleibt; Jeder geteilte Bereich erbt den Wert. Der Wert bleibt über Neustarts hinweg gespeichert; Der Vollbildmodus macht den Hintergrund des Terminals vorübergehend undurchsichtig und stellt den Wert wieder her, wenn Sie ihn verlassen. Das Ein- und Ausschalten erfordert einen Neustart. Die Statusleiste zeigt daher einen Hinweis an, wenn Sie diesen Schwellenwert überschreiten. Wird nur in der Menüleiste im Fenster angezeigt. |
| Vollbild | ++f12++ | Fenster-Vollbild umschalten |
| Nur Terminal-Vollbild | ++ctrl+shift+f++ | Zeigt das gesamte korTTY-Fenster an – einschließlich Menüs, Registerkarten und Statusleiste – in der vorherigen Fenstergröße und zentriert auf einem leeren Vollbildhintergrund, wodurch der Desktop und andere Fenster ausgeblendet werden |
| Terminal-Bildlaufleisten im Vollbildmodus ausblenden | | Bildlaufleisten auch im Vollbildmodus ausblenden |
| AI-Agent-Panel ▸ Unten / Links andocken / Rechts andocken | | Wählen Sie, wo sich das AI-Agent-Aktivitätspanel befindet |
| Live-Journal ▸ Links andocken / Rechts andocken | | Docken Sie das [Live-Journal-Bereich](../features/session-journal.md#das-live-journal-panel) neben dem Terminal; die Auswahl der aktiven Seite versteckt es. |
| Live-Journal ▸ Anzeigen/Ausblenden | ++ctrl+alt+l++ | Schalten Sie das Live-Journal-Panel auf seiner zuletzt verwendeten Seite (standardmäßig rechts) |
| Coding-Agents ▸ Links andocken / Rechts andocken | | Andocken Sie das [Coding-Agents-Panel](../features/coding-agents.md#das-coding-agents-panel) neben dem Terminal; die Auswahl der aktiven Seite blendet es aus. |
| Coding-Agents ▸ Ein-/Ausblenden | ++ctrl+alt+g++ | Blenden Sie das Coding-Agents-Panel auf seiner zuletzt verwendeten Seite ein oder aus (standardmäßig rechts) |
| Coding-Agents ▸ Nächster wartender Agent | ++ctrl+alt+n++ | Holen Sie den nächsten Coding-Agent, der auf eine Entscheidung wartet, über Fenster hinweg nach vorne |

## Teamarbeit

| Artikel | Beschreibung |
| --- | --- |
| Teamwork-Einstellungen… | Gemeinsam genutzte Verbindungsquellen und Synchronisierung konfigurieren |

## Hilfe

| Element | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Anleitung | ++f1++ | Öffnen Sie diese Dokumentation innerhalb von korTTY |
| Über korTTY | | Versions- und Projektinformationen |

Die Anleitung verfügt über eigene Textgrößen-Schaltflächen oben links im Fenster: `A-`, der aktuelle Prozentsatz, und `A+`. Durch Anklicken des Prozentsatzes wird er zurückgesetzt. Die gleichen drei Aktionen haben Tastenkombinationen: ++cmd+plus++, ++cmd+minus++, ++cmd+0++. korTTY merkt sich die Größe, und die Größe deckt das gesamte Fenster ab – die Seite und, wenn sie geöffnet ist, das KI-Suche-Panel daneben. Siehe [Textgröße der Anleitung](settings/appearance.md#textgroe-der-anleitung).

Screenshots und Diagramme werden durch Klicken vergrößert: Das Bild wird über der Seite in der größtmöglichen Größe geöffnet, die das Fenster zulässt, mit den Schaltflächen **−** / ***+** und einem Prozentsatz, der es auf die angepasste Größe zurücksetzt. Zoomen Sie weiter, um eine einzelne Einstellungszeile zu lesen, ziehen Sie das Bild zum Schwenken und schließen Sie es mit *×**, ++esc++ oder einem Klick neben dem Bild. ++ctrl++ und der Radzoom ebenfalls. Dasselbe funktioniert auch im Online-Ratgeber.

## macOS Dock & Menüleiste

Unter macOS läuft die gepackte App weiterhin im Hintergrund (damit der JobScheduler geplante Jobs ausführen kann), auch nachdem das letzte Fenster geschlossen wurde. korTTY fügt daher zwei zusätzliche Einstiegspunkte hinzu, damit es erreichbar – und beendet – bleibt, auch wenn kein Fenster geöffnet ist:

- **Dock-Symbolmenü** – Klicken Sie mit der rechten Maustaste auf das Dock-Symbol von korTTY, um schnelle Aktionen durchzuführen: **Neues Fenster**, **Neuer Tab**, **Verbindungen verwalten…**, **Projekt öffnen…**, **Anleitung**, **Über korTTY** und **Beenden**.
- **Menüleisten-(Status-)Symbol** – ein Taskleistensymbol mit **Neues Fenster** und **Beenden**; Durch Klicken auf das Symbol wird ein neues Fenster geöffnet.

Beide bieten ein zuverlässiges **Beenden**, selbst wenn jedes Fenster geschlossen ist.
