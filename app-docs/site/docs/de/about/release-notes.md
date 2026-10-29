# Versionshinweise

Was hat sich in der aktuellen Version geändert? Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.23.0

### Connection-Manager

- **Neues Layout** — der Connection-Manager zeigt eine Bibliothek auf der linken Seite (alle Verbindungen, Favoriten, kürzlich verwendet, aktuell geöffnet, Ordner, Tags, Teamarbeit), die gewählten Verbindungen in der Mitte und einen Inspektor auf der rechten Seite, mit allen Details der ausgewählten Verbindung und schneller Bearbeitung ihres Namens und Tags. Die Symbolleiste verwendet Symbole im Stil der Symbolleiste, und die Liste zeigt die Adresse, das Tag und Markierungen für Jump-Server, Tunnel, Isolation und Inkognito anstelle von Emojis. Siehe [Connection-Manager](../features/connections.md#connection-manager).
- **Netzplan** — **Netzplan** zeigt die Verbindungen als Netzplan: eine Zone pro Ordner in dessen Tab-Farbe, Jump-Server-Routen und Tunnel als Linien, Doppelklick verbindet und das Ziehen eines Knotens in eine andere Zone verschiebt die Verbindung in diesen Ordner. Siehe [Liste und Netzplan](../features/connections.md#liste-und-netzplan).
- **Favoriten** — Markieren Sie Verbindungen mit dem Stern, um sie unter **Favoriten** zu finden; auch Teamwork-Verbindungen können Favoriten sein.
- **Teamwork neben lokalen Verbindungen** — die allgemeinen Listen zeigen auch Teamwork-Verbindungen, mit einem kleinen Personensymbol gekennzeichnet und schreibgeschützt. Siehe [Bibliothek](../features/connections.md#bibliothek).
- **OS-Symbole** — vergeben Sie jedem Server das Symbol seines Betriebssystems (Windows, macOS, Linux-Distributionen, ChromeOS, Unix, BSD, z/OS) oder eines Ihrer eigenen Bilder, angezeigt in der Liste, im Netzplan und im Inspektor. Siehe [OS-Symbole](../features/connections.md#os-symbole).
- **Netzplan als PDF** — **PDF** im Netzplan speichert den Netzplan als Vektor-PDF mit dem korTTY-Logo, im Quer- oder Hochformat.
- **Übersichtsexport** — **Übersicht exportieren** schreibt Ihre Server als JSON, YAML, TOML oder XML, mit den Teilen, die Sie auswählen, und niemals ein Passwort oder einen Schlüssel. Siehe [Übersicht exportieren](../features/connections.md#exportieren-einer-ubersicht).
- **Inspektor** — der Pfad von diesem Computer über den Jump-Server, Sicherheit als Chips, Nutzungsbalken für die letzten 14 Tage und eine SFTP-Schaltfläche.

### Serielle Verbindungen

- **Serielle Ports** — das neue **Seriell**-Protokoll öffnet ein Terminal auf einem seriellen Port, wie die seriellen Sitzungen von PuTTY: eine Router- oder Switch-Konsole, ein Mikrocontroller-Board oder ein USB-zu-Seriell-Adapter, unter Windows, macOS und Linux. Wählen Sie den Port aus der Liste aus oder geben Sie ihn ein, und legen Sie Geschwindigkeit, Datenbits, Parität, Stop-Bits, Flusssteuerung, was Enter sendet, DTR/RTS und lokales Echo fest. **Break senden** findet sich im Kontextmenü des Terminals und im Menü Verbindungen und kann in den Tastatureinstellungen eine Taste zugewiesen werden. Serielle Sitzungen aus MobaXterm- und PuTTY Connection-Manager-Dateien werden ebenfalls importiert. Siehe [Serielle Verbindungen](../features/serial.md).

### Unter Windows

- **PowerShell und Eingabeaufforderung in der Symbolleiste** — unter Windows werden in der Terminalgruppe der Symbolleiste **Neue PowerShell** und **Neue Eingabeaufforderung** direkt nach **Neue lokale Shell** eingefügt, jeweils mit eigenem Symbol; gespeicherte Layouts übernehmen beide ebenfalls. Bei installiertem Git for Windows bietet der Anpassungskatalog zudem **Neue Git Bash**. Siehe [Anpassen der Symbolleiste](../getting-started/main-window.md#anpassung-der-symbolleiste).
- **Lokale Shells werden unter ConPTY ausgeführt** — lokale Shells und der native Mosh-Client starten nun unter der Windows-Pseudo-Konsole anstelle von winpty, was nicht mehr die Tastatureingaben in anderen Anwendungen stört, während korTTY läuft.
- **Mosh-Client findet mosh-client.exe** — das Mosh-Client-Protokoll (nativ) sucht nun nach `mosh-client.exe` in den Ordnern der `PATH`. Siehe [Protokolle](../features/connections.md#protokolle).

### Symbolleiste und Split-Fenster

- **Symbolleistengruppen werden umgebrochen, anstatt verkleinert zu werden** — wenn das Fenster zu schmal ist, werden ganze Symbolleistengruppen in eine neue Zeile verschoben, sodass jede Schaltfläche direkt anklickbar bleibt, anstatt sich hinter einem Überlauf-Pfeil zu verstecken. Siehe [Anpassen der Symbolleiste](../getting-started/main-window.md#anpassung-der-symbolleiste).
- **Fokusring kann ausgeschaltet werden** — schalten Sie **Aktives Split-Terminal umrahmen** in den Fenstereinstellungen aus, um den Ring um das aktive Split-Terminal auszublenden. Sie erkennen dann das aktive Terminal durch seinen Cursor. Siehe [Tabs](../reference/settings/window.md#tabs).

### Snippets

- **Ordneranalyse nach Datei** — der Tab „Ordneranalyse“ listet die Dateien des Ordners neben dem Bericht auf. Wählen Sie eine Datei oder navigieren Sie durch die Dateien mit ◀ ▶ und den Pfeiltasten, um nur deren Ergebnisse und eine kurze Beschreibung der Funktion der Datei anzuzeigen; jede Zeile zeigt, wie viele ihrer Ergebnisse markiert sind, und ihr Kontrollkästchen markiert oder entfernt sie alle. Die Ergebnisse bleiben auch dann einer Datei zugeordnet, wenn das Modell sie pro Datei durchnumeriert oder die Datei auf eine andere Weise benennt. Siehe [Analysieren eines Ordners als einzelnes Projekt](../features/snippets.md#ordner-als-ein-projekt-analysieren).
- **Vertikales oder horizontales Flussdiagramm** — eine **Ausrichtung**-Steuerung in der Diagramm-Symbolleiste zeichnet das Flussdiagramm von oben nach unten oder von links nach rechts, im Analysebereich, dessen Zoom-Fenster sowie im Diagramm-Dialog. Das Umschalten zeichnet das gespeicherte Diagramm neu, ohne die KI erneut zu befragen. Siehe [Diagrammdarstellung](../features/snippets.md#diagrammdarstellung).

### KI

- **"Modell-Standard" statt "Deaktiviert"** — die Einstellung, die keinen Reasoning-Parameter sendet, heißt nun **Modell-Standard**, da dann der Standardwert des Modells gilt; nur **Keine** schaltet das Reasoning aus. Ein Tooltip im Dropdown-Menü erläutert den Unterschied. Siehe [Reasoning-Stufen](../reference/settings/ai.md#einstellungen-fur-das-denkverhalten).
- **Verbindungstest akzeptiert reine Reasoning-Antworten** — **KI-Verbindung testen** und **Reasoning-Optionen aktualisieren** werden nun bestanden, wenn ein Modell, das standardmäßig mit Reasoning arbeitet, wie Qwen in LM Studio, die kurze Testantwort für das Reasoning nutzt, und ein fehlgeschlagener Aktualisierungsvorgang den Grund des Endpunkts selbst angibt.

### Terminal

- **Zwischenablage-Protokoll von kitty (OSC 5522)** — Programme, die das Zwischenablage-Protokoll von kitty verwenden, wie Neovim und `kitten clipboard`, können unter der Einstellung OSC 52 in die Zwischenablage kopieren, und mit der neuen Einstellung **Programmen im Terminal erlauben, das Lesen der Zwischenablage anzufragen (OSC 5522)** können sie das Lesen anfragen. Bei jedem Lesezugriff werden Sie zuerst gefragt, **Ablehnen** ist die Standardeinstellung, und ein Einfügen in ein Programm, das Einfüge-Ereignisse angefragt hat, gilt als Ihre Zustimmung. Es wird nur Text ausgetauscht; der strikte Terminalmodus entfernt das Protokoll, und der Policy-Schlüssel `allow-osc5522-clipboard-read = false` verbietet das Lesen. Siehe [Programme, die die Zwischenablage lesen](../features/terminal-notifications.md#programme-zum-auslesen-der-zwischenablage-osc-5522).

- **Scrollback-Speicherlimit** — der neue **Scrollback-Speicher**-Regler in den Terminal-Einstellungen legt fest, wie viel Speicher der Scrollback eines einzelnen Fensters maximal verwenden darf, von 1 bis 50 MB (Standard: 10 MB), neben der Zeilenanzahl; welches Limit zuerst erreicht wird, entfernt die ältesten Zeilen. Eine gespeicherte Änderung wird sofort auf offene Terminals angewendet. Siehe [Terminal-Einstellungen](../reference/settings/terminal.md).
- **Scrollback-Komprimierung** — während ein Terminal keine Ausgabe hat, komprimiert korTTY die Scrollback-Zeilen, die nicht auf dem Bildschirm sind, im Hintergrund, damit derselbe Speicher mehrere Male so viele Zeilen fasst. Eine Zeile wird entpackt, sobald Sie dazu scrollen. Deaktivieren Sie es über **Scrollback im Leerlauf im Hintergrund komprimieren**. Siehe [Terminal-Einstellungen](../reference/settings/terminal.md).

### Korrekturen

- **Verworfene Sitzungen bleiben verworfen** — das absichtliche Schließen des letzten Tabs leert nun den Sitzungsschnappschuss, sodass die Wiederherstellungsaufforderung nach dem Verwerfen nicht mehr bei jedem Start wieder erscheint.
- **Stabile Diagramm-Tooltips** — der Code-Referenz-Tooltip im vergrößerten Analyse-Diagramm bleibt geöffnet, solange sich die Maus über einem Knoten befindet, anstatt kurz aufzubleiben und zu schließen.
- **Ordneranalysen bleiben bei einem fehlerhaften Schlüssel erhalten** — eine Analyse, bei der im JSON das öffnende Anführungszeichen eines Schlüssels fehlt, wird repariert, anstatt als „keine verwendbare Analyse“ verworfen zu werden.
- **Deutsches Label für Reasoning** — die deutsche Benutzeroberfläche bezeichnet diese Einstellung der KI-Profile nun als „Reasoning“, wie es auch die Anleitung tut.
- **Breite Dialoge öffnen sich in AtlantaFX-Designs in ihrer Größe** — unter einem AtlantaFX-Design wurde ein Dialog, der breiter als 600 px aufgebaut ist (wie die Einstellungen, **Ordner vergleichen** im SFTP-Manager oder der **Logdatei-Manager**), gestaucht geöffnet oder wuchs nach dem Erscheinen; er öffnet sich nun so breit wie im Normal-Design, und eine lange Meldung wird weiterhin umgebrochen.
