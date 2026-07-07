# Menüreferenz

Jedes Element in der Menüleiste von korTTY mit seiner Verknüpfung (sofern definiert) und seiner Funktion. Die Menüleiste kann mit ++Strg+Umschalt+L++ ausgeblendet und durch einen Rechtsklick auf das Terminal oder die Statusleiste wieder eingeblendet werden.

## Datei

| Artikel | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Neues Fenster | ++Strg+Umschalt+N++ | Öffnen Sie ein zusätzliches, unabhängiges Hauptfenster |
| Neuer Tab | ++Strg+T++ | Öffnen Sie Quick Connect in einem neuen Terminal-Tab |
| Projekt öffnen… | ++Strg+O++ | Ein gespeichertes Projekt wiederherstellen (Fenster, Registerkarten, Layout) |
| Projekt speichern… | ++Strg+S++ | Speichern Sie die aktuelle Sitzung als Projekt (`.kortty`) |
| Schnellverbindung… | ++Strg+K++ | Mit einem Host verbinden, ohne ihn vorher zu speichern |
| Verbindungen verwalten… | | Öffnen Sie den Verbindungsmanager |
| Verbindungen importieren… | | Import aus MTPuTTY / MobaXterm / PuTTY CM |
| Verbindungen exportieren… | | Gespeicherte Verbindungen exportieren |
| Tab schließen | ++Strg+W++ | Schließen Sie die aktive Terminal-Registerkarte |
| Alle Tabs schließen | | Alle Registerkarten im aktuellen Fenster schließen |
| Fenster schließen | | Aktuelles Fenster schließen |
| Beenden | ++Strg+Q++ | Beenden Sie korTTY |

## Bearbeiten

| Artikel | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Schneiden | ++Strg+x++ | Ausschneiden (für Anschlusslaschen deaktiviert) |
| Kopieren | ++Strg+C++ | Kopieren Sie die Terminalauswahl |
| Einfügen | ++Strg+V++ | In das Terminal einfügen |
| Alle auswählen | | Alle Terminalinhalte auswählen |
| Terminal löschen | | Löschen Sie das aktive Terminal |
| Backup erstellen… | ++Strg+Umschalt+B++ | Erstellen Sie ein verschlüsseltes Backup (ZIP-Passwort oder GPG) |
| Backup importieren… | | Aus einer Sicherungsdatei wiederherstellen |

## Verbindungen

| Artikel | Beschreibung |
| --- | --- |
| Schnellverbindung… | Ohne Speichern eine Verbindung zu einem Host herstellen |
| Verbindungen verwalten… | Öffnen Sie den Verbindungsmanager (Baum, Suchen, Bearbeiten) |
| Importieren… | Verbindungen von anderen Clients importieren |
| Exportieren… | Verbindungen exportieren |
| SFTP-Client… | Öffnen Sie den Dual-Panel-SFTP-Dateimanager |

## Sicherheit

| Artikel | Beschreibung |
| --- | --- |
| Anmeldeinformationen… | Gespeicherte Zugangsdaten verwalten (verschlüsselt) |
| GPG-Schlüssel… | GPG-Schlüssel verwalten, die für die Backup-Verschlüsselung verwendet werden |
| SSH-Schlüssel… | SSH-Schlüssel und Passphrasen verwalten |

## Konfiguration

| Artikel | Beschreibung |
| --- | --- |
| Globale Einstellungen… | Öffnen Sie den globalen Einstellungsdialog (alle Registerkarten) |

Sehen Sie sich die [Einstellungsreferenz](settings/index.md)] für jede einzelne Einstellung an.

## Werkzeuge

![Tools menu](../assets/screenshots/main/menu-tools.png)

| Artikel | Beschreibung |
| --- | --- |
| Snippet-Manager… | Befehlsschnipsel erstellen, bearbeiten, organisieren, senden und exportieren |
| JobScheduler… | Planen Sie Hintergrundbefehle/Snippets/AI-Agent/AI-Swarm/SFTP/Rsync-Jobs |
| Videomanager… | Verwalten Sie Terminalaufzeichnungen und exportieren Sie sie über `ffmpeg` | nach WebM/MKV
| Terminalaufzeichnung starten/stoppen | Aufzeichnung des aktiven Terminals umschalten (++Strg+Umschalt+E++) |
| ASCII-Kunst… | FIGlet-Bannergenerator mit mehreren Schriftarten |

## KI

![AI menu](../assets/screenshots/main/menu-ai.png)

| Artikel | Beschreibung |
| --- | --- |
| KI-Manager… | KI-Profile und gespeicherte Chats verwalten |
| KI-Agent… | Öffnen Sie den Terminal AI Agent |
| KI-Planung… | Öffnen Sie den KI-Planungsworkflow |
| KI-Schwarm… | Übertragen Sie eine KI-Aufgabe an viele Server und vergleichen Sie die Antworten (++Strg+Alt+S++) |

**AI Manager** listet Ihre KI-Profile (jeweils mit Verbindungsmodus, Modell, Argumentationsaufwand, Internetzugang und Token-Budget) und Ihre gespeicherten Chats auf:

![AI Manager](../assets/screenshots/ai/ai-manager.png)

## Plugins

| Artikel | Beschreibung |
| --- | --- |
| Endgültige Auswirkungen… | Terminaleffekt-Plugins aktivieren/deaktivieren, konfigurieren, importieren/exportieren |

## Sicht

| Artikel | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Dashboard anzeigen | ++Strg+Umschalt+D++ | Schalten Sie das Verbindungs-Dashboard um |
| Menüleiste anzeigen | ++Strg+Umschalt+L++ | Schalten Sie die Menüleiste um |
| Befehlszeitstempel anzeigen | | Inline-Befehlszeitstempel umschalten |
| Vergrößern | ++alt+plus++ | Terminal-Schriftgröße erhöhen |
| Verkleinern | ++alt+minus++ | Terminal-Schriftgröße verringern |
| Zoom zurücksetzen | ++alt+0++ | Terminal-Schriftgröße zurücksetzen |
| Hintergrundtransparenz | | Schieberegler (0–100 %), der den Hintergrund des Terminals auf dem Desktop durchscheinen lässt, während der Text scharf bleibt; Der Wert bleibt über Neustarts hinweg gespeichert. Das Ein- und Ausschalten erfordert einen Neustart. Die Statusleiste zeigt daher einen Hinweis an, wenn Sie diesen Schwellenwert überschreiten. Wird nur in der Menüleiste im Fenster angezeigt. |
| Vollbild | ++f11++ | Fenster-Vollbild umschalten |
| Nur Terminal-Vollbild | ++f12++ | Alles Chrome ausblenden, nur das Terminal anzeigen |
| Terminal-Bildlaufleisten im Vollbildmodus ausblenden | | Bildlaufleisten auch im Vollbildmodus ausblenden |
| AI Agent-Panel ▸ Unten / Links andocken / Rechts andocken | | Wählen Sie aus, wo sich das AI-Agent-Aktivitätsfenster befindet |

## Teamarbeit

| Artikel | Beschreibung |
| --- | --- |
| Teamwork-Einstellungen… | Gemeinsam genutzte Verbindungsquellen und Synchronisierung konfigurieren |

## Helfen

| Artikel | Verknüpfung | Beschreibung |
| --- | --- | --- |
| Anleitung | ++f1++ | Öffnen Sie diese Dokumentation in korTTY |
| Über korTTY | | Versions- und Projektinformationen |

## macOS Dock und Menüleiste

Unter macOS läuft die gepackte App weiterhin im Hintergrund (damit der JobScheduler geplante Jobs ausführen kann), auch nachdem das letzte Fenster geschlossen wurde. korTTY fügt daher zwei zusätzliche Einstiegspunkte hinzu, damit es erreichbar – und beendet – bleibt, auch wenn kein Fenster geöffnet ist:

- **Dock-Symbolmenü** – Klicken Sie mit der rechten Maustaste auf das Dock-Symbol von korTTY, um schnelle Aktionen auszuführen: **Neues Fenster**, **Neuer Tab**, **Verbindungen verwalten…**, **Projekt öffnen…**, **Anleitung**, **Über korTTY** und **Beenden**.
- **Symbol in der Menüleiste (Status)** – ein Symbol in der Taskleiste mit **Neues Fenster** und **Beenden**; Durch Klicken auf das Symbol wird ein neues Fenster geöffnet.

Beide bieten ein zuverlässiges **Beenden**, selbst wenn jedes Fenster geschlossen ist.
