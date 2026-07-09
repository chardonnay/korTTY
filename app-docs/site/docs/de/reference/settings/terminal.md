---
title: Terminal
---

# Terminal

Konfigurieren Sie die Anzeige- und Verhaltenseinstellungen des Terminals, einschließlich Abmessungen, Scrollback, Zeichenkodierung und SSH-Verbindungsverwaltung. Öffnen über **Konfiguration → Globale Einstellungen → Terminal**; in `~/.kortty/global-settings.xml` gespeichert.

![Terminal settings tab](../../assets/screenshots/settings/terminal.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Spalten: | Nummer | 40–500 | 80 | `terminalColumns` |
| Zeilen: | Nummer | 10–200 | 24 | `terminalRows` |
| Zurückscrollen: | Nummer | 100–100.000 | 10.000 | `scrollbackLines` |
| Kodierung: | Dropdown | UTF-8, ISO-8859-1, ISO-8859-15, Windows-1252 | UTF-8 | `encoding` |
| Fett wie helle Farbe | umschalten | — | Auf | `boldAsBright` |
| Bildlaufleiste im Terminal anzeigen | umschalten | — | Auf | `showTerminalScrollbar` |
| Befehlszeitstempel anzeigen | umschalten | — | Aus | `commandTimestampsEnabled` |
| Dateikopie per Drag-and-Drop ins Terminal zulassen | umschalten | — | Auf | `terminalDragDropEnabled` |
| Auswahl automatisch in die Zwischenablage kopieren | umschalten | — | Auf | `terminalCopyOnSelectEnabled` |
| Aktive Terminalfenster ohne Bestätigung schließen | umschalten | — | Aus | `closeActiveTerminalWindowsWithoutConfirmation` |
| SSH-Keep-Alive aktivieren | umschalten | — | Auf | `sshKeepAliveEnabled` |
| Intervall (Sekunden): | Nummer | 5–600 | 60 | `sshKeepAliveInterval` |
| Verbindungswiederholungen aktivieren | umschalten | — | Auf | `connectionRetriesEnabled` |

## Notizen

!!! Hinweis „Scrollback“
    Legt fest, wie viele Ausgabezeilen jeder Terminal-Bereich in seinem Scrollback-Puffer behält. Der Wert wird beim Erstellen eines Terminals gelesen; eine Änderung gilt daher für neu geöffnete Tabs und geteilte Bereiche – bereits offene Terminals behalten ihre aktuelle Puffergröße. Größere Werte verbrauchen mehr Speicher pro Bereich.

!!! Hinweis „SSH Keep-Alive“
    Wenn korTTY aktiviert ist, sendet es regelmäßig Keep-Alive-Pakete, um zu verhindern, dass SSH-Sitzungen während Leerlaufzeiten ablaufen. Die Intervalleinstellung steuert, wie oft (in Sekunden) diese Pakete gesendet werden. Der Spinnerbereich beträgt 5–600 Sekunden; Das Intervall ist deaktiviert, wenn SSH Keep-Alive ausgeschaltet ist.

!!! Hinweis „Dateikopie per Drag-and-Drop“
    Wenn diese Option aktiviert ist, können Sie Dateien oder Ordner aus Ihrem Dateimanager (Finder unter macOS, Explorer unter Windows) direkt im Terminalfenster ablegen. Die Dateien werden über SFTP auf den Remote-SSH-Server kopiert.

!!! Hinweis „Befehlszeitstempel“
    Wenn diese Option aktiviert ist, wird auf der linken Seite des Terminals eine Seitenleiste angezeigt, in der das Datum und die Uhrzeit der Eingabe jedes Befehls angezeigt werden. Dies ist nützlich für Audit-Trails und Sitzungsprotokollierung.

!!! Hinweis „Verbindungswiederholungen“
    Wenn diese Option aktiviert ist, werden fehlgeschlagene SSH-Verbindungen automatisch wiederholt. Wenn Sie dies deaktivieren, werden automatische Wiederverbindungsversuche bei fehlgeschlagenen Verbindungen verhindert.
