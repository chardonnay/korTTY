# Versionshinweise

Was hat sich in der aktuellen Version geändert? Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.23.0

### Connection-Manager

- **Neues Layout** — der Connection-Manager zeigt eine Bibliothek auf der linken Seite (alle Verbindungen, Favoriten, kürzlich verwendet, aktuell geöffnet, Ordner, Tags, Teamarbeit), die gewählten Verbindungen in der Mitte und einen Inspektor auf der rechten Seite, mit allen Details der ausgewählten Verbindung und schneller Bearbeitung ihres Namens und Tags. Die Symbolleiste verwendet Symbole im Stil der Symbolleiste, und die Liste zeigt die Adresse, das Tag und Markierungen für Jump-Server, Tunnel, Isolation und Inkognito anstelle von Emojis. Siehe [Connection-Manager](../features/connections.md#connection-manager).
- **Netzplan** — **Netzplan** zeigt die Verbindungen als Netzplan: eine Zone pro Ordner in dessen Tab-Farbe, Jump-Server-Routen und Tunnel als Linien, Doppelklick verbindet und das Ziehen eines Knotens in eine andere Zone verschiebt die Verbindung in diesen Ordner. Siehe [Liste und Netzplan](../features/connections.md#liste-und-netzplan).
- **Favoriten** — Markieren Sie Verbindungen mit dem Stern, um sie unter **Favoriten** zu finden; auch Teamwork-Verbindungen können Favoriten sein.

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

### Korrekturen

- **Verworfene Sitzungen bleiben verworfen** — das absichtliche Schließen des letzten Tabs leert nun den Sitzungsschnappschuss, sodass die Wiederherstellungsaufforderung nach dem Verwerfen nicht mehr bei jedem Start wieder erscheint.
- **Stabile Diagramm-Tooltips** — der Code-Referenz-Tooltip im vergrößerten Analyse-Diagramm bleibt geöffnet, solange sich die Maus über einem Knoten befindet, anstatt kurz aufzubleiben und zu schließen.
- **Ordneranalysen bleiben bei einem fehlerhaften Schlüssel erhalten** — eine Analyse, bei der im JSON das öffnende Anführungszeichen eines Schlüssels fehlt, wird repariert, anstatt als „keine verwendbare Analyse“ verworfen zu werden.
- **Deutsches Label für Reasoning** — die deutsche Benutzeroberfläche bezeichnet diese Einstellung der KI-Profile nun als „Reasoning“, wie es auch die Anleitung tut.
