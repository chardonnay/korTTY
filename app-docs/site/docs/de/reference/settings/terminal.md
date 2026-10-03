---
title: Terminal
---

# Terminal

Konfigurieren Sie die Anzeige- und Verhaltenseinstellungen des Terminals, einschließlich Abmessungen, Scrollback, Zeichenkodierung, Links und SSH-Verbindungsverwaltung. Öffnen über **Konfiguration → Globale Einstellungen → Terminal**; in `~/.kortty/global-settings.xml` gespeichert.

![Terminal settings tab](../../assets/screenshots/settings/terminal.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Spalten: | Nummer | 40–500 | 80 | `terminalColumns` |
| Zeilen: | Nummer | 10–200 | 24 | `terminalRows` |
| Scrollback: | Nummer | 100–100.000 | 10.000 | `scrollbackLines` |
| Kodierung: | Dropdown | UTF-8, ISO-8859-1, ISO-8859-15, Windows-1252 | UTF-8 | `encoding` |
| Fett wie helle Farbe | umschalten | — | Ein | `boldAsBright` |
| Bildlaufleiste im Terminal anzeigen | umschalten | – | Ein | `showTerminalScrollbar` |
| Befehlszeitstempel anzeigen | umschalten | – | Aus | `commandTimestampsEnabled` |
| Dateikopie per Drag-and-Drop in das Terminal zulassen | umschalten | – | Ein | `terminalDragDropEnabled` |
| Auswahl automatisch in die Zwischenablage kopieren | umschalten | – | Ein | `terminalCopyOnSelectEnabled` |
| Aktive Terminalfenster ohne Bestätigung schließen | umschalten | – | Aus | `closeActiveTerminalWindowsWithoutConfirmation` |
| Web-Adressen, E-Mail-Adressen und Dateipfade im Terminaltext erkennen | umschalten | – | Ein | `terminalLinkDetectionEnabled` |
| SSH Keep-Alive aktivieren | umschalten | – | Ein | `sshKeepAliveEnabled` |
| Intervall (Sekunden): | Nummer | 5–600 | 60 | `sshKeepAliveInterval` |
| Verbindungswiederholungen aktivieren | umschalten | – | Ein | `connectionRetriesEnabled` |
| Verlorene Verbindungen automatisch wiederherstellen | umschalten | – | Ein | `autoReconnectEnabled` |
| Hostschlüsselüberprüfung für alle Verbindungen deaktivieren | umschalten | – | Aus | `hostKeyCheckDisabledForAllConnections` |
| Coding-Agents (Claude Code, Codex, Gemini CLI) in lokalen Shell-Tabs erkennen | umschalten | – | Ein | `codingAgentDetectionEnabled` |
| Desktop-Benachrichtigung, wenn ein Coding-Agent eine Entscheidung braucht oder fertig wird, während Sie seinen Bereich nicht ansehen | umschalten | – | Ein | `codingAgentNotificationsEnabled` |
| Anzahl der auf eine Entscheidung wartenden Agents am App-Symbol anzeigen | umschalten | – | Ein | `codingAgentAppBadgeEnabled` |
| Einem lokalen Programm erlauben, dieses korTTY zu lesen und zu steuern | umschalten | – | **Aus** | `controlApiEnabled` |

## Hinweise

!!! note "Kodierung"
    Die Zeichenkodierung, mit der korTTY die Ausgabe einer SSH-Sitzung dekodiert und das kodiert, was Sie eingeben oder einfügen. Sie gilt für jede SSH-Verbindung, die auf dem Tab *Verbindung* des Verbindungseditors keine eigene **Zeichenkodierung** auswählt (siehe [Zeichenkodierung](../../features/connections.md#zeichenkodierung)). Lokale Shells verwenden UTF-8, sofern ihre Verbindung keine Kodierung festlegt, und Mosh nutzt stets UTF-8, weil mosh-server und mosh-client dies erfordern. Wählen Sie die Kodierung aus, die die Programme auf dem Server tatsächlich schreiben, in der Regel das, was `locale` dort meldet. Eine Änderung tritt beim nächsten Verbindungsaufbau oder bei einer erneuten Verbindung eines Tabs in Kraft; offene Tabs behalten ihre Kodierung. Zeichen, die die gewählte Kodierung nicht darstellen kann, werden als `?` übermittelt.

    Frühere Versionen ignorierten diese Einstellung, sodass ein damals gewählter Wert nicht automatisch angewendet wird: SSH-Sitzungen bleiben UTF-8, und ein Hinweis unter dem Dropdown sagt dies, bis Sie die Einstellungen nach dem Öffnen der Terminal-Seite speichern. Wählen Sie **UTF-8** vor dem Speichern, wenn Sie den alten Wert nicht übernehmen möchten.

!!! note "Fett als helle Farbe anzeigen"
    Diese Einstellung gilt derzeit nur für Terminalaufnahmen: mit [Terminalfarben in Aufnahmen erfassen](video.md) aktiviert, wird fetter Text einer der 8 normalen ANSI-Farben in seiner hellen Variante gespeichert. Das Live-Terminal zeichnet fetten Text in jeder Hinsicht immer noch in seiner normalen Farbe aus.

!!! note "Zurückscrollen"
    Steuert, wie viele Ausgabezeilen jeder Terminalbereich in seinem Scrollback-Puffer behält. Der Wert wird beim Erstellen eines Terminals gelesen, daher gilt eine Änderung für neu geöffnete Registerkarten und geteilte Bereiche – bereits geöffnete Terminals behalten ihre aktuelle Puffergröße. Größere Werte verbrauchen mehr Speicher pro Bereich.

!!! note "Markierung automatisch in Zwischenablage kopieren"
    Wenn aktiviert, wird der von Ihnen im Terminal ausgewählte Text sofort in die Zwischenablage kopiert. Unter Linux wird er zudem zur X11-Hauptauswahl, sodass ein Mittelklick ihn in anderen Anwendungen wie xterm oder gedit einfügt. Mit dem internen Zwischenablage-Modus der Unternehmensrichtlinie [interner Zwischenablage-Modus](../enterprise-policy.md#interner-zwischenablagemodus) bleibt die Auswahl innerhalb von korTTY auf jeder Plattform.

!!! note "Links"
    Wenn **Web-Adressen, E-Mail-Adressen und Dateipfade im Terminaltext erkennen** eingeschaltet ist, öffnet ++cmd++ + Klick (macOS) bzw. ++ctrl++ + Klick (Windows, Linux) eine Webadresse, eine E-Mail-Adresse oder einen Dateipfad, die ein Programm als Klartext ausgegeben hat: eine Webadresse in Ihrem Standardbrowser, eine E-Mail-Adresse als neue Mail in Ihrem Mailprogramm und in SSH- und lokalen Shell-Tabs einen Dateipfad als Text im Snippet-Editor. Ein einfacher Klick wählt weiterhin nur Text aus. Eine Änderung gilt sofort für die offenen Terminals. Links, die ein Programm selbst mit OSC 8 auszeichnet, öffnen sich in jedem Fall mit demselben Klick. Siehe [Links in der Terminalausgabe](../../features/terminal.md#links-in-der-terminalausgabe).

!!! note "SSH-Keep-Alive"
    Wenn korTTY aktiviert ist, sendet es regelmäßig Keep-Alive-Pakete, um zu verhindern, dass SSH-Sitzungen während Leerlaufzeiten ablaufen. Die Intervalleinstellung steuert, wie oft (in Sekunden) diese Pakete gesendet werden. Der Spinnerbereich beträgt 5–600 Sekunden; Das Intervall ist deaktiviert, wenn SSH Keep-Alive ausgeschaltet ist.

!!! warning "Hostschlüsselüberprüfung für alle Verbindungen deaktivieren"
    Dies ist die globale Einstellung mit der niedrigsten Priorität für den Host-Schlüssel: Sie entspannt die Prüfung und akzeptiert für jede Verbindung, die keine eigene oder die eigene Gruppeneinstellung überschreibt. Accept-new blockiert weiterhin geänderte Schlüssel bei einem Host, der bereits festgelegt wurde, und der Schlüssel eines Jump-Server ist stets streng überprüft — jedoch entfernt die Deaktivierung der ersten-Nutzung-Prüfung die Sicherheit vor einem Man-in-the-Middle bei der ersten Verbindung. Standardmäßig deaktiviert. Per-Verbindung- und per-Gruppen-Überwachungen werden im Connection-Manager konfiguriert; siehe [Sicherheit → Lockerung der Host-Schlüssel-Prüfung](../../features/security.md#lockere-uberprufung-des-hostschlussels).

!!! note "Drag-and-Drop-Datei kopieren"
    Wenn diese Option aktiviert ist, können Sie Dateien oder Ordner aus Ihrem Dateimanager (Finder unter macOS, Explorer unter Windows) direkt im Terminalfenster ablegen. Die Dateien werden über SFTP auf den Remote-SSH-Server kopiert.

!!! note "Befehlszeitstempel"
    Wenn diese Option aktiviert ist, wird auf der linken Seite des Terminals eine Seitenleiste angezeigt, in der das Datum und die Uhrzeit der Eingabe jedes Befehls angezeigt werden. Dies ist nützlich für Audit-Trails und Sitzungsprotokollierung.

    Jede Markierung bleibt auf ihrer Befehlszeile, wenn der Scrollback voll ist und die ältesten Zeilen verworfen werden, und eine Markierung, deren Zeile den Scrollback verlassen hat, verschwindet ebenfalls. **Puffer löschen** im Rechtsklick-Menü des Terminals sowie ein `clear`, der den Scrollback ebenfalls leert, entfernt alle Markierungen; der nächste Befehl erhält eine neue. Das Öffnen und Beenden eines Vollbildprogramms wie `vim` oder `less` verschiebt die Markierungen nicht. Der Tag und Monat über jeder Markierung sowie das vollständige Datum im Hover-Popup folgen der korTTY UI-Sprache (zum Beispiel `02.10.` auf Deutsch, `10/02` auf Englisch), ebenso wie die verstrichene Zeit im Popup.

!!! note "Verbindungswiederholungsversuche"
    Wenn diese Option aktiviert ist, werden fehlgeschlagene SSH-Verbindungen automatisch wiederholt. Wenn Sie dies deaktivieren, werden automatische Wiederverbindungsversuche bei fehlgeschlagenen Verbindungen verhindert.

    Wiederholungen decken nur Fehler ab, die durch einen weiteren Versuch behoben werden könnten. Ein geänderter Host-Key, eine verweigerte Anmeldung, eine SSH-Schlüsseldatei, die fehlt oder nicht gelesen werden kann, ein Sprungserver, dessen gespeichertes Passwort nicht verwendet werden kann oder dessen Einrichtung unvollständig ist, eine Mosh-Verbindung, die mit einem Sprungserver konfiguriert ist, oder ein fehlender Mosh-Laufzeitumgebung werden sofort abgelehnt, unabhängig von dieser Einstellung.

!!! note "Verlorene Verbindungen automatisch wiederherstellen"
    Wenn aktiviert und eine **bestehende** SSH-Verbindung verloren geht (Netzwerkabbruch, Server ist weg), reconnectt der Tab automatisch mit zunehmenden Verzögerungen — 3, 5, 10, 20, 30, dann alle 60 Sekunden — und die rote Statusleiste zählt ab zur nächsten Versuch. Ein Doppelklick auf die Leiste führt zu einem sofortigen Wiederherstellen, und ein erfolgreicher Wiederherstellung oder das Schließen des Tabs beendet die automatischen Versuche. Fehlerhafte Anmeldungen und andere dauerhafte Fehlschläge (Authentifizierung, Host-Schlüssel, Konfiguration) werden nie automatisch erneut versucht, und eine Verbindung, die nie erfolgreich hergestellt wurde, wird ebenfalls nicht erneut versucht — das ist der Inhalt von *Verbindungswiederholungen aktivieren*. Siehe [Terminal-Sitzungen → Verbindungsverlust](../../features/terminal.md#verbindungsverlust-und-automatische-wiederherstellung-der-verbindung).

!!! warning "Einem lokalen Programm erlauben, dieses korTTY zu lesen und zu steuern"
    Das ist die Steuerungs-API und die einzige Einstellung dieses Reiters, die standardmäßig aus ist. Solange sie an ist, kann jedes Programm unter Ihrem Benutzerkonto auf diesem Rechner Ihre Fenster auflisten, jeden offenen Bereich lesen – lokale Shells wie SSH-Sitzungen – und darin tippen, einschließlich ++enter++. Über das Netzwerk ist nichts erreichbar und kein anderer Benutzer des Rechners kann sich verbinden, aber weiter reicht die Grenze nicht: innerhalb Ihres eigenen Kontos ist es dieselbe Macht, als säße jemand an Ihrer Tastatur. Eine Statuszeile unter dem Kontrollkästchen meldet, was der Listener tatsächlich tut, denn Kontrollkästchen und Listener können berechtigt auseinanderliegen – die Unternehmensrichtlinie kann die Funktion verbieten, und ein Start kann scheitern, weil bereits ein anderes korTTY den Socket besitzt. Jede Aktion wird nur mit Byte-Anzahlen protokolliert, nie mit Terminaltext, und beim ersten Tippen eines Programms in einen Bereich erhalten Sie eine Desktop-Benachrichtigung. Siehe [Steuerungs-API](../control-api.md) für das Sicherheitsmodell und [Steuerungs-CLI](../cli.md) für den Client `kortty-cli`, der sie spricht.

!!! note "Coding-Agents erkennen"
    Wenn aktiviert, beobachtet korTTY jede lokale Shell-Abteilung auf laufende Claude Code, Codex oder Gemini CLI-Instanzen und überwacht, ob sie arbeitet, auf eine Frage blockiert ist, abgeschlossen oder inaktiv ist. Die Anzeige wird lokal analysiert und nichts verlässt den Computer; die Änderung wird sofort auf offene Tabs übertragen. Die beiden Schalter unten steuern die Desktop-Benachrichtigung für einen Agenten, der eine Entscheidung benötigt oder abgeschlossen wird, während Sie nicht auf dessen Abteilung sehen, sowie die Anzahl der wartenden Agenten auf der App-Icon (oder im Fenstertitel, wenn kein Icon-Abzeichen existiert); beide lesen den Einstellung live, sodass eine Änderung sofort wirkt. Siehe [Coding-Agents](../../features/coding-agents.md#app-symbol-badge-und-benachrichtigungen).
