# Versionshinweise

Was hat sich in der aktuellen Version geändert? Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.24.0

### Connection-Manager

- **Neues Layout** — der Connection-Manager zeigt eine Bibliothek auf der linken Seite (alle Verbindungen, Favoriten, kürzlich verwendet, aktuell geöffnet, Ordner, Tags, Teamarbeit), die gewählten Verbindungen in der Mitte und einen Inspektor auf der rechten Seite, mit allen Details der ausgewählten Verbindung und schneller Bearbeitung ihres Namens und Tags. Die Symbolleiste verwendet Symbole im Stil der Symbolleiste, und die Liste zeigt die Adresse, das Tag und Markierungen für Jump-Server, Tunnel, Isolation und Inkognito anstelle von Emojis. Siehe [Connection-Manager](../features/connections.md#connection-manager).
- **Netzplan** — **Netzplan** zeigt die Verbindungen als Netzplan: eine Zone pro Ordner in dessen Tab-Farbe, Jump-Server-Routen und Tunnel als Linien, Doppelklick verbindet und das Ziehen eines Knotens in eine andere Zone verschiebt die Verbindung in diesen Ordner. Siehe [Liste und Netzplan](../features/connections.md#liste-und-netzplan).
- **Favoriten** — Markieren Sie Verbindungen mit dem Stern, um sie unter **Favoriten** zu finden; auch Teamwork-Verbindungen können Favoriten sein.
- **Teamwork neben lokalen Verbindungen** — die allgemeinen Listen zeigen auch Teamwork-Verbindungen, mit einem kleinen Personensymbol gekennzeichnet und schreibgeschützt. Siehe [Bibliothek](../features/connections.md#bibliothek).
- **OS-Symbole** — vergeben Sie jedem Server das Symbol seines Betriebssystems (Windows, macOS, Linux-Distributionen, ChromeOS, Unix, BSD, z/OS) oder eines Ihrer eigenen Bilder, angezeigt in der Liste, im Netzplan und im Inspektor. Siehe [OS-Symbole](../features/connections.md#os-symbole).
- **Netzplan als PDF** — **PDF** im Netzplan speichert den Netzplan als Vektor-PDF mit dem korTTY-Logo, im Quer- oder Hochformat.
- **Übersichtsexport** — **Übersicht exportieren** schreibt Ihre Server als JSON, YAML, TOML oder XML, mit den Teilen, die Sie auswählen, und niemals ein Passwort oder einen Schlüssel. Siehe [Übersicht exportieren](../features/connections.md#exportieren-einer-ubersicht).
- **Inspektor** — der Pfad von diesem Computer über den Jump-Server, Sicherheit als Chips, Nutzungsbalken für die letzten 14 Tage und eine SFTP-Schaltfläche.
- **Symbolleiste für eine leere Verbindungszeile** — während **Ansicht → Verbindungsleiste** aktiviert ist, aber noch keine Server oder Gruppen enthält, bleibt die Zeile sichtbar und bietet **Verbindung hinzufügen**, das die Anpassung im Verbindungskatalog öffnet. Siehe [Anpassung der Symbolleiste](../getting-started/main-window.md#anpassung-der-symbolleiste).

### Serielle Verbindungen

- **Serielle Ports** — das neue **Seriell**-Protokoll öffnet ein Terminal auf einem seriellen Port, wie die seriellen Sitzungen von PuTTY: eine Router- oder Switch-Konsole, ein Mikrocontroller-Board oder ein USB-zu-Seriell-Adapter, unter Windows, macOS und Linux. Wählen Sie den Port aus der Liste aus oder geben Sie ihn ein, und legen Sie Geschwindigkeit, Datenbits, Parität, Stop-Bits, Flusssteuerung, was Enter sendet, DTR/RTS und lokales Echo fest. **Break senden** findet sich im Kontextmenü des Terminals und im Menü Verbindungen und kann in den Tastatureinstellungen eine Taste zugewiesen werden. Serielle Sitzungen aus MobaXterm- und PuTTY Connection-Manager-Dateien werden ebenfalls importiert. Siehe [Serielle Verbindungen](../features/serial.md).

### Terminal

- **Zwischenablage-Protokoll von kitty (OSC 5522)** — Programme, die das Zwischenablage-Protokoll von kitty verwenden, wie Neovim und `kitten clipboard`, können unter der Einstellung OSC 52 in die Zwischenablage kopieren, und mit der neuen Einstellung **Programmen im Terminal erlauben, das Lesen der Zwischenablage anzufragen (OSC 5522)** können sie das Lesen anfragen. Bei jedem Lesezugriff werden Sie zuerst gefragt, **Ablehnen** ist die Standardeinstellung, und ein Einfügen in ein Programm, das Einfüge-Ereignisse angefragt hat, gilt als Ihre Zustimmung. Es wird nur Text ausgetauscht; der strikte Terminalmodus entfernt das Protokoll, und der Policy-Schlüssel `allow-osc5522-clipboard-read = false` verbietet das Lesen. Siehe [Programme, die die Zwischenablage lesen](../features/terminal-notifications.md#programme-zum-auslesen-der-zwischenablage-osc-5522).
- **Scrollback-Speicherlimit** — der neue **Scrollback-Speicher**-Regler in den Terminal-Einstellungen legt fest, wie viel Speicher der Scrollback eines einzelnen Fensters maximal verwenden darf, von 1 bis 50 MB (Standard: 10 MB), neben der Zeilenanzahl; welches Limit zuerst erreicht wird, entfernt die ältesten Zeilen. Eine gespeicherte Änderung wird sofort auf offene Terminals angewendet. Siehe [Terminal-Einstellungen](../reference/settings/terminal.md).
- **Scrollback-Komprimierung** — während ein Terminal keine Ausgabe hat, komprimiert korTTY die Scrollback-Zeilen, die nicht auf dem Bildschirm sind, im Hintergrund, damit derselbe Speicher mehrere Male so viele Zeilen fasst. Eine Zeile wird entpackt, sobald Sie dazu scrollen. Deaktivieren Sie es über **Scrollback im Leerlauf im Hintergrund komprimieren**. Siehe [Terminal-Einstellungen](../reference/settings/terminal.md).
- **Pixelgröße für Bildwerkzeuge über SSH** — SSH-Sitzungen, einschließlich isolierter, teilen dem Server nun die Größe des Terminals in Pixeln mit, damit Bildwerkzeuge, die das kitty-Grafikprotokoll verwenden, ihre Bilder korrekt dimensionieren, anstatt 0×0 zu sehen.

### KI

- **KI-Agent pro Verbindung deaktivieren** — für Systeme wie Produktionsserver, schalten Sie **KI-Agent für diese Verbindung deaktivieren** auf dem **KI**-Tab des Verbindungseditors ein. Jeder Agent-Lauf auf dieser Verbindung wird dann abgelehnt, aus dem Menü, dem `agent ...`-Shortcut, akzeptierten Plänen, Swarm-Mitgliedern und geplanten KI-Agent-Jobs; **Agent fragen**, **KI-Planung** und die anderen KI-Funktionen bleiben verfügbar. Siehe [Verbindungen ohne Agent](../features/ai-tools.md#verbindungen-ohne-agent).
- **Verbindungs-Skills durchsuchen** — Ein Suchfeld über der Liste der KI-Skills im Verbindungs-Editor filtert diese nach Name, Beschreibung und Tags.

### KI-Memory und KI-Skills

- **KI-Memory** — korTTY verwaltet einen Speicher, den der KI-Chat und der Agent ihren Anfragen beifügen: einen globalen Speicher und einen pro Verbindung, als einfache Markdown-Dateien, die Sie lesen und bearbeiten können. Er verfügt über ein Token-Budget pro Anfrage, Ihr eigener Text wird niemals ohne Ihre Bestätigung geändert, und die von korTTY geschriebenen Einträge können zusammengeführt, archiviert und aus dem Archiv wiederhergestellt werden (Archiv anzeigen…); der Verlauf bewahrt frühere Versionen einer Speicherdatei, die Sie wiederherstellen können. Siehe [KI-Memory](../features/ai-memory.md).
- **Referenzdateien anderer Tools** — die CLAUDE.md, AGENTS.md und ähnliche Dateien anderer KI-Tools können als schreibgeschütztes Gedächtnis hinzugefügt werden, sobald Sie deren Text gelesen und genehmigt haben; eine geänderte Datei wartet erneut auf Ihre Genehmigung. Siehe [KI-Memory](../features/ai-memory.md).
- **Lösungsoptionen** — bevor eine komplexe Anfrage ausgeführt wird, kann der Agent eine empfohlene Option, Ihre eigene Idee und eine optionale dauerhafte Regel anbieten. **Komplexität einschätzen** legt fest, wann dies geschieht: Automatisch, Immer oder Nie. Siehe [Lösungsoptionen](../features/ai-tools.md#losungsoptionen).
- **Aus Gesprächen erlernte Regeln** — im Modus **Regeln vorschlagen** schlägt korTTY eine Regel vor, wenn eine Nachricht darauf hindeutet, dass etwas für später aufbewahrt werden soll; nichts wird ohne Ihre Bestätigung gespeichert, und eine gespeicherte Regel kann 30 Sekunden lang rückgängig gemacht werden. Regeln werden in den Speicher oder in ein Skill abgelegt, und korTTY kann ein neues Skill für ein Thema erstellen, das noch keines hat. Siehe [KI-Memory](../features/ai-memory.md).
- **Als Regel merken…** — speichert eine Regel, die Sie selbst schreiben, bei Ihren eigenen Chat-Nachrichten und im Prompt-Dialog des Agents, ohne KI-Aufruf.
- **Mitgesendet** — unter jeder Chat-Antwort und jedem Agentenlauf listet eine zusammengeklappte Zeile die Speichereinträge, Skills und Referenzdateien, die übermittelt wurden, maskiert wie beim Versand, mit ihren Token-Anzahlen.
- **Geheimnis-Schutz** — korTTY verweigert es, ein Passwort, einen Schlüssel oder ein Token in einem Skill, einer erlernten Regel, dem Speicher oder einer genehmigten Referenzdatei zu speichern, und markiert Skills und Speichernotizen, die bereits eines enthalten. Der Wert wird niemals angezeigt. Siehe [Geheimnisse in Skills](../reference/settings/ai-skills.md#geheimnisse-in-ki-skills).
- **Skill-Statistik** — der **Statistik**-Dialog der KI-Skills zeigt, wie oft jedes Skill verwendet wird, seine Größe und sein Wachstum, mit Hinweisen für Skills, die überprüft werden sollten. **Kürzen...** schlägt eine kürzere Version vor, die erst nach Ihrer Bestätigung gespeichert wird. Siehe [Skill-Statistik](../reference/settings/ai-skills.md#skill-statistik).
- **Importierte Skill-Updates** — ein Update aus dem Internet überschreibt niemals ein Skill, das Sie geändert haben; der Update-Dialog bietet dann an, die Version des Anbieters als Kopie zu importieren oder diese Version zu überspringen. Siehe [Externe Skills](../reference/settings/ai-skills.md#externe-skills).
- **Unternehmensrichtlinie** — die `[rule.ai-memory]` legt fest, wie das Gedächtnis verwendet werden darf, wie Regeln erlernt werden, ob korTTY Skills erstellen darf, welche Referenzdateien gelesen werden dürfen, das Token-Budget, den Triage-Modus und die Statistiken. `[[organization-memory]]` fügt Regeln hinzu, die jeder Benutzer erhält und die nicht geändert werden können. Siehe [Unternehmensrichtlinie](../reference/enterprise-policy.md#ruleai-memory).
- **Backups** — das Gedächtnis und die Skill-Statistiken sind Teil eines Backups. Siehe [Backup & Wiederherstellung](../features/backup.md#inhalt-der-sicherungsdatei).

### Snippets

- **Snippet-Manager-Fenster blockieren das Hauptfenster nicht mehr** — die Terminals bleiben nutzbar, während ein Snippet-Manager-Fenster geöffnet ist, auch während es eine Frage stellt oder eine Vollständige Code-Analyse ausführt. **Werkzeuge → Neues Snippet-Manager-Fenster** öffnet ein weiteres, und **In neues Fenster verschieben** auf einem Tab verschiebt ein Snippet oder eine Ordneranalyse in ein eigenes Fenster, beispielsweise auf einen zweiten Bildschirm. Siehe [Öffnen des Snippet-Managers](../features/snippets.md#offnen-des-snippet-managers).

### Coding-Agents

- **Cline und Antigravity CLI** — Das Coding-Agents-Panel erkennt jetzt **Cline** und Googles **Antigravity CLI** (`agy`), den Nachfolger von Gemini CLI; die Regeln aller anderen Agents wurden gegen deren jeweils neueste Version überprüft, und die Ordnervertrauens- und Anmelde-Dialoge von Gemini CLI gelten jetzt als „wartet auf Sie“. Siehe [Was erkannt wird](../features/coding-agents.md#was-erkannt-wird).

### Korrekturen

- **Kopieren aus dem KI-Chat** — ++cmd+c++ / ++ctrl+c++ und ++cmd+a++ / ++ctrl+a++ funktionieren nun nach dem Klicken in eine Chatnachricht, und die Scrollleiste des Chats ist breiter und besser sichtbar.
- **Breite Dialoge öffnen sich in AtlantaFX-Designs in ihrer Größe** — unter einem AtlantaFX-Design wurde ein Dialog, der breiter als 600 px aufgebaut ist (wie die Einstellungen, **Ordner vergleichen** im SFTP-Manager oder der **Logdatei-Manager**), gestaucht geöffnet oder wuchs nach dem Erscheinen; er öffnet sich nun so breit wie im Normal-Design, und eine lange Meldung wird weiterhin umgebrochen.
