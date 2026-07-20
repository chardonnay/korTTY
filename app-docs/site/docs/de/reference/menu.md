# Menüreferenz

Jedes Element in der Menüleiste von korTTY mit seiner Verknüpfung (sofern definiert) und seiner Funktion. Die Menüleiste kann mit ++Strg+Umschalt+L++ ausgeblendet und durch einen Rechtsklick auf das Terminal oder die Statusleiste wieder eingeblendet werden.

## Datei

| Element | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Neues Fenster | ++Strg+Umschalt+N++ | Öffnet ein zusätzliches, unabhängiges Hauptfenster |
| Neue Registerkarte | ++Strg+T++ | Öffnen Sie Quick Connect in einer neuen Terminal-Registerkarte |
| Projekt öffnen… | ++Strg+O++ | Ein gespeichertes Projekt wiederherstellen (Fenster, Registerkarten, Layout) |
| Projekt speichern… | ++Strg+S++ | Aktuelle Sitzung als Projekt speichern (`.kortty`) |
| Schnellverbindung… | ++Strg+K++ | Verbindung zu einem Host herstellen, ohne ihn vorher zu speichern |
| Verbindungen verwalten… | | Öffnen Sie den Verbindungsmanager |
| Verbindungen importieren… | | Import aus MTPuTTY / MobaXterm / PuTTY CM |
| Verbindungen exportieren… | | Gespeicherte Verbindungen exportieren |
| Tab schließen | ++Strg+W++ | Aktives Terminal-Tab schließen |
| Alle Tabs schließen | | Alle Tabs im aktuellen Fenster schließen |
| Fenster schließen | | Aktuelles Fenster schließen |
| Beenden | ++Strg+Q++ | Beenden korTTY |

## Bearbeiten

| Element | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Ausschneiden | ++Strg+x++ | Ausschneiden (deaktiviert für Anschlusslaschen) |
| Kopieren | ++Strg+C++ | Kopieren der Terminalauswahl |
| Einfügen | ++Strg+V++ | Einfügen in das Terminal |
| Alle auswählen | | Alle Terminalinhalte auswählen |
| Terminal löschen | | Aktives Terminal löschen |
| Backup erstellen… | ++Strg+Umschalt+B++ | Erstellen Sie ein verschlüsseltes Backup (ZIP-Passwort oder GPG) |
| Sicherung importieren… | | Wiederherstellung aus einer Sicherungsdatei |

## Verbindungen

| Artikel | Beschreibung |
| --- | --- |
| Quick Connect… | Verbindung zu einem Host herstellen, ohne | zu speichern
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
| Snippet Manager… | Erstellen, Bearbeiten, Organisieren, Senden und Exportieren von Befehlsausschnitten |
| JobScheduler… | Hintergrundbefehl/Snippet/AI-Agent/AI-Swarm/SFTP/Rsync-Jobs planen |
| Video Manager… | Terminalaufzeichnungen verwalten und über `ffmpeg` | nach WebM/MKV exportieren
| Terminalaufzeichnung starten/stoppen | Aufzeichnung des aktiven Terminals umschalten (++Strg+Umschalt+E++) |
| ASCII Art… | FIGlet-Bannergenerator mit mehreren Schriftarten |

## AI

![AI menu](../assets/screenshots/main/menu-ai.png)

| Artikel | Beschreibung |
| --- | --- |
| AI Manager… | Verwalten Sie AI-Profile, integrierte GGUF-Modelle, Text-/Codierungsrollen, RAG-Wissensspeicher und gespeicherte Chats |
| Gespeicherte Chats… | Öffnen Sie die gespeicherten AI-Chat-Konversationen direkt in einem eigenen Fenster; ein erneuter Aufruf bringt das vorhandene Fenster in den Vordergrund |
| AI Agent… | Öffnen Sie den Terminal AI Agent |
| KI-Planung… | Öffnen Sie den KI-Planungsworkflow |
| KI-Schwarm… | Übertragen Sie eine KI-Aufgabe an viele Server und vergleichen Sie die Antworten (++Strg+Alt+S++) |

**AI Manager** listet Profile auf (Verbindungsmodus, Modell, Eingabeaufforderungsvoreinstellung, Argumentation, Internetzugang und Token-Budget), sucht/lädt/importiert lokale GGUF-Modelle, weist Text-/Codierungs-/Einbettungsrollen zu, verwaltet Wissensspeicherquellen und öffnet gespeicherte Chats. Der geöffnete primäre Abschnitt bleibt durch eine fette Akzentunterstreichung gekennzeichnet, nachdem Sie den Fokus auf seine Tabellen, Felder oder Schaltflächen verschoben haben:

![AI Manager with Local Models selected and persistently underlined](../assets/screenshots/ai/ai-manager.png)

## Plugins

| Artikel | Beschreibung |
| --- | --- |
| Terminal-Effekte… | Terminal-Effekt-Plugins aktivieren/deaktivieren, konfigurieren, importieren/exportieren |

## Ansicht

| Element | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Dashboard anzeigen | ++Strg+Umschalt+D++ | Verbindungs-Dashboard umschalten |
| Menüleiste anzeigen | ++Strg+Umschalt+L++ | Menüleiste umschalten |
| Befehlszeitstempel anzeigen | | Inline-Befehlszeitstempel umschalten |
| Vergrößern | ++Alt+Plus++ | Terminal-Schriftgröße erhöhen |
| Verkleinern | ++Alt+Minus++ | Terminal-Schriftgröße verringern |
| Zoom zurücksetzen | ++alt+0++ | Terminal-Schriftgröße zurücksetzen |
| Hintergrundtransparenz | | Schieberegler (0–100 %), der den Terminalhintergrund auf dem Desktop durchscheinen lässt, während der Text scharf bleibt; Jeder geteilte Bereich erbt den Wert. Der Wert bleibt über Neustarts hinweg gespeichert; Der Vollbildmodus macht den Hintergrund des Terminals vorübergehend undurchsichtig und stellt den Wert wieder her, wenn Sie ihn verlassen. Das Ein- und Ausschalten erfordert einen Neustart. Die Statusleiste zeigt daher einen Hinweis an, wenn Sie diesen Schwellenwert überschreiten. Wird nur in der Menüleiste im Fenster angezeigt. |
| Vollbild | ++f11++ | Fenster-Vollbild umschalten |
| Nur korTTY Applikationsfenster | ++f12++ | Zeigt das gesamte korTTY-Fenster an – einschließlich Menüs, Registerkarten und Statusleiste – in der vorherigen Fenstergröße und zentriert auf einem leeren Vollbildhintergrund, wodurch der Desktop und andere Fenster ausgeblendet werden |
| Terminal-Bildlaufleisten im Vollbildmodus ausblenden | | Bildlaufleisten auch im Vollbildmodus ausblenden |
| AI-Agent-Panel ▸ Unten / Links andocken / Rechts andocken | | Wählen Sie, wo sich das AI-Agent-Aktivitätspanel befindet |

## Teamarbeit

| Artikel | Beschreibung |
| --- | --- |
| Teamwork-Einstellungen… | Gemeinsam genutzte Verbindungsquellen und Synchronisierung konfigurieren |

## Hilfe

| Element | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Anleitung | ++f1++ | Öffnen Sie diese Dokumentation in korTTY |
| Über korTTY | | Versions- und Projektinformationen |

## macOS Dock & Menüleiste

Unter macOS läuft die gepackte App weiterhin im Hintergrund (damit der JobScheduler geplante Jobs ausführen kann), auch nachdem das letzte Fenster geschlossen wurde. korTTY fügt daher zwei zusätzliche Einstiegspunkte hinzu, damit es erreichbar – und beendet – bleibt, auch wenn kein Fenster geöffnet ist:

- **Dock-Symbolmenü** – Klicken Sie mit der rechten Maustaste auf das Dock-Symbol von korTTY, um schnelle Aktionen durchzuführen: **Neues Fenster**, **Neuer Tab**, **Verbindungen verwalten…**, **Projekt öffnen…**, **Anleitung**, **Über korTTY** und **Beenden**.
- **Menüleisten-(Status-)Symbol** – ein Taskleistensymbol mit **Neues Fenster** und **Beenden**; Durch Klicken auf das Symbol wird ein neues Fenster geöffnet.

Beide bieten ein zuverlässiges **Beenden**, selbst wenn jedes Fenster geschlossen ist.
