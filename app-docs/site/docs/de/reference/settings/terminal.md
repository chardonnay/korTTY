---
title: Terminal
---

# Terminal

Konfigurieren Sie die Anzeige- und Verhaltenseinstellungen des Terminals, einschließlich Abmessungen, Scrollback, Zeichenkodierung und SSH-Verbindungsverwaltung. Öffnen über **Konfiguration → Globale Einstellungen → Terminal**; in `~/.kortty/global-settings.xml` gespeichert.

![Terminal settings tab](../../assets/screenshots/settings/terminal.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Spalten: | Nummer | 40–500 | 80 | `terminalColumns` |
| Zeilen: | Nummer | 10–200 | 24 | `terminalRows` |
| Scrollback: | Nummer | 100–100.000 | 10.000 | `scrollbackLines` |
| Kodierung: | Dropdown | UTF-8, ISO-8859-1, ISO-8859-15, Windows-1252 | UTF-8 | `encoding` |
| Fett als helle Farbe | umschalten | — | Ein | `boldAsBright` |
| Bildlaufleiste im Terminal anzeigen | umschalten | – | Ein | `showTerminalScrollbar` |
| Befehlszeitstempel anzeigen | umschalten | – | Aus | `commandTimestampsEnabled` |
| Dateikopie per Drag-and-Drop in das Terminal zulassen | umschalten | – | Ein | `terminalDragDropEnabled` |
| Auswahl automatisch in die Zwischenablage kopieren | umschalten | – | Ein | `terminalCopyOnSelectEnabled` |
| Aktive Terminalfenster ohne Bestätigung schließen | umschalten | – | Aus | `closeActiveTerminalWindowsWithoutConfirmation` |
| SSH Keep-Alive aktivieren | umschalten | – | Ein | `sshKeepAliveEnabled` |
| Intervall (Sekunden): | Nummer | 5–600 | 60 | `sshKeepAliveInterval` |
| Verbindungswiederholungen aktivieren | umschalten | – | Ein | `connectionRetriesEnabled` |
| Verlorene Verbindungen automatisch wiederherstellen | umschalten | – | Ein | `autoReconnectEnabled` |
| Hostschlüsselüberprüfung für alle Verbindungen deaktivieren | umschalten | – | Aus | `hostKeyCheckDisabledForAllConnections` |

## Hinweise

!!! note "Scrollback"
    Steuert, wie viele Ausgabezeilen jeder Terminalbereich in seinem Scrollback-Puffer behält. Der Wert wird beim Erstellen eines Terminals gelesen, daher gilt eine Änderung für neu geöffnete Registerkarten und geteilte Bereiche – bereits geöffnete Terminals behalten ihre aktuelle Puffergröße. Größere Werte verbrauchen mehr Speicher pro Bereich.

!!! note "SSH Keep-Alive"
    Wenn korTTY aktiviert ist, sendet es regelmäßig Keep-Alive-Pakete, um zu verhindern, dass SSH-Sitzungen während Leerlaufzeiten ablaufen. Die Intervalleinstellung steuert, wie oft (in Sekunden) diese Pakete gesendet werden. Der Spinnerbereich beträgt 5–600 Sekunden; Das Intervall ist deaktiviert, wenn SSH Keep-Alive ausgeschaltet ist.

!!! warning "Hostschlüsselüberprüfung für alle Verbindungen deaktivieren"
    Dies ist die globale Hostschlüsseleinstellung mit der niedrigsten Priorität: Sie lockert die Überprüfung auf „Accept-New“ für jede Verbindung, die nicht ihre eigene oder die Überschreibung ihrer Gruppe festlegt. Accept-new blockiert weiterhin einen geänderten Schlüssel auf einem bereits gepinnten Host, und der eigene Schlüssel eines Jump-Servers wird immer strikt überprüft – aber durch Deaktivieren der Erstverwendungsüberprüfung wird der Schutz vor einem Man-in-the-Middle bei der allerersten Verbindung aufgehoben. Standardmäßig deaktiviert. Im Verbindungsmanager werden verbindungs- und gruppenspezifische Außerkraftsetzungen festgelegt. siehe [Sicherheit → Lockere Host-Schlüssel-Überprüfung](../../features/security.md#lockere-uberprufung-des-hostschlussels).

!!! note "Drag-and-Drop-Datei kopieren"
    Wenn diese Option aktiviert ist, können Sie Dateien oder Ordner aus Ihrem Dateimanager (Finder unter macOS, Explorer unter Windows) direkt im Terminalfenster ablegen. Die Dateien werden über SFTP auf den Remote-SSH-Server kopiert.

!!! note "Befehlszeitstempel"
    Wenn diese Option aktiviert ist, wird auf der linken Seite des Terminals eine Seitenleiste angezeigt, in der das Datum und die Uhrzeit der Eingabe jedes Befehls angezeigt werden. Dies ist nützlich für Audit-Trails und Sitzungsprotokollierung.

!!! note "Verbindungswiederholungsversuche"
    Wenn diese Option aktiviert ist, werden fehlgeschlagene SSH-Verbindungen automatisch wiederholt. Wenn Sie dies deaktivieren, werden automatische Wiederverbindungsversuche bei fehlgeschlagenen Verbindungen verhindert.

    Wiederholungsversuche decken nur Fehler ab, die durch einen weiteren Versuch behoben werden könnten. Ein geänderter Hostschlüssel, eine mit einem Jump-Server konfigurierte Mosh-Verbindung oder eine fehlende Mosh-Laufzeit wird unabhängig von dieser Einstellung sofort abgelehnt.

!!! note "Verlorene Verbindungen automatisch wiederherstellen"
    Wenn aktiviert und eine **bestehende** SSH-Verbindung abbricht (Netzwerkausfall, Server weg), verbindet sich der Tab selbstständig neu, mit wachsenden Wartezeiten – 3, 5, 10, 20, 30, dann alle 60 Sekunden – und die rote Statusleiste zählt zum nächsten Versuch herunter. Ein Doppelklick auf die Leiste verbindet weiterhin sofort, und ein erfolgreiches Wiederverbinden oder das Schließen des Tabs beendet die automatischen Versuche. Fehlgeschlagene Anmeldungen und andere dauerhafte Fehler (Authentifizierung, Hostschlüssel, Konfiguration) werden nie automatisch wiederholt, und eine Verbindung, die nie zustande kam, wird von dieser Einstellung ebenfalls nicht wiederholt – dafür ist *Verbindungswiederholungen aktivieren* zuständig. Siehe [Terminal-Sitzungen → Verbindungsverlust](../../features/terminal.md).
