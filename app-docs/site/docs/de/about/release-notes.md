# Versionshinweise

Das vollständige Versions-Änderungsprotokoll. Die Version, für die diese Anleitung erstellt wurde, ist
in der Fußzeile angezeigt.

## Unveröffentlicht

## v2.4.0

### Terminaleffekte

- **Zehn neue integrierte Terminaleffekte** – neben MU/TH/UR 6000 bringt ein
  mitgeliefertes Effektpaket jetzt zehn thematische Effekte von Cyberpunk über
  Retro bis Grusel: **Amber CRT '90** (bernsteinfarbener 90er-Phosphormonitor mit
  Scanlines, Nachleuchten, Flackern und laufendem Bildwechselband), **Commodore
  Heritage** (C64-Blau mit Loader-Balken), **Neon City** (Glitch-Risse und
  RGB-Versatz-Flackern), **Digital Rain** (dezent herabfallende Matrix-Zeichen),
  **Hologram HUD** (Interferenzbänder und HUD-Eckklammern), **Poltergeist**
  (atmende Vignette, Rauschsalven und geisterhafte Blitze), **VHS 1987**
  (Tracking-Rauschen, laufende Verzerrung und PLAY-Einblendung), **Synthwave
  Horizon** (leuchtendes Perspektivraster), **Deep Space Radar** (langsamer
  Radarstrahl mit Blips) und **Typewriter Noir** (Sepia-Papier-Look mit
  Zeichentakt bei der Ausgabe). Jeder Effekt berücksichtigt die Einstellung der
  Animationsgeschwindigkeit, und seine Beschreibung ist in allen unterstützten
  Sprachen lokalisiert.
- **Zeichenweise Ausgabe bei Typewriter Noir** – der Effekt **Typewriter Noir**
  gibt die Terminalausgabe Zeichen für Zeichen aus – für ein mechanisches
  Schreibmaschinengefühl; große Ausgaben wie das Anzeigen einer großen Datei
  umgehen den Takt und werden dadurch nicht verlangsamt.
- **Terminaleffekte pro Bereich (Split)** – Terminaleffekte gelten jetzt für
  jeden einzelnen geteilten Bereich statt für den gesamten Tab. Innerhalb eines
  Tabs können Sie in einem Bereich einen Effekt laufen lassen, während ein
  Nachbarbereich einen anderen oder gar keinen zeigt; Farben und Schriftart eines
  Effekts bleiben auf den eigenen Bereich beschränkt. Zoom und Zurücksetzen
  gelten weiterhin für den ganzen Tab.
- **Effektmenü und Vererbung pro Bereich** – das Kontextmenü jedes geteilten
  Bereichs erhält ein Untermenü **Terminal-Effekt**, um für genau diesen Bereich
  keinen oder einen installierten Effekt zu wählen, samt Regler für die
  Animationsgeschwindigkeit; die Auswahl gilt nur zur Laufzeit und wird nicht in
  der Verbindung gespeichert. Beim Teilen eines Bereichs übernimmt der neue
  Bereich Effekt und Animationsgeschwindigkeit des Ausgangsbereichs.
- **Animierte Effektvorschauen im Plugin-Manager** – **Plugins → Terminal-Effekte**
  zeigt jetzt neben der Plugin-Liste eine animierte Live-Vorschau des
  ausgewählten Effekts, sodass Effekte vor dem Aktivieren in einer Sitzung
  verglichen werden können. Plugins ohne Vorschau zeigen stattdessen einen
  Platzhalter.

### KI-Chat

- **Chat-Farbprofile** – der KI-Chat und der KI-Schwarm-Chat haben jetzt wählbare
  Farbthemen. Elf integrierte Profile sind dabei: **Automatisch (Theme)**, das
  dem aktiven Terminal-Theme folgt, sowie **Original**, **Paper**, **Midnight**,
  **Cyberpunk**, **Retrowave**, **Forest**, **Ocean**, **Terminal**, **GPT** und
  **Cute**. Wählen Sie eines im Farbprofil-Dropdown der Chat-Symbolleiste oder
  unter **Einstellungen → Erscheinungsbild → Chat-Farbprofil**; die Auswahl wird
  gespeichert und sofort auf jeden offenen Chat angewendet.
- **Volltextsuche im Chat** (++ctrl+f++, Cmd+F auf macOS) – klicken Sie auf die
  Schaltfläche **Suchen** in der Chat-Symbolleiste oder drücken Sie das
  Tastenkürzel, um eine Suchleiste über einer KI- oder Schwarm-Unterhaltung zu
  öffnen. Sie durchsucht den gesamten Chat einschließlich Codeblöcken, zeigt eine
  Live-Trefferzahl, springt mit den Pfeiltasten oder der Eingabetaste zwischen den
  Treffern und hebt jeden Treffer hervor und scrollt ihn ins Bild. Esc schließt
  die Leiste.
- **Neu gestalteter, durchgehend gestylter Chat** – der KI-Chat und der
  Schwarm-Chat wurden neu gestaltet: Ihre Nachrichten sitzen in einer rechts
  eingerückten, abgerundeten Sprechblase, jede KI-Antwort ist eine Karte über die
  volle Breite, und Codeblöcke, Tabellen, das Eingabefeld und die Bildlaufleisten
  folgen dem gewählten Farbprofil statt einem festen hellen Stil.

### Datenschutz & Nutzungsstatistik

- **Anonyme Nutzungsstatistik (freiwillig, standardmäßig aus)** – korTTY kann
  optional anonyme, DSGVO-konforme Nutzungsstatistiken über Aptabase (Verarbeitung
  auf EU-Servern) teilen, um Funktionen zu priorisieren und Abstürze und häufige
  Fehler sichtbar zu machen. Das ist strikt freiwillig und standardmäßig
  deaktiviert. Übertragen werden nur Ereignisnamen, aggregierte Zählwerte und
  Kennzeichen, die App-Version, Name und Version des Betriebssystems, die
  App-Sprache und eine anonyme Sitzungs-ID pro Start – Hostnamen, Benutzernamen,
  Verbindungsdaten, Dateipfade, Snippet-, Terminal- und Chat-Inhalte, Schlüssel,
  Passwörter und Fehlermeldungstexte werden niemals erfasst.
- **Einmalige Zustimmungsabfrage** – Sie werden genau einmal gefragt, ob Sie
  anonyme Daten teilen möchten: bei Neuinstallationen als Kontrollkästchen neben
  der Master-Passwort-Einrichtung, bei bestehenden Installationen als einmalige
  Abfrage nach dem Entsperren. Jedes Wegklicken zählt als *Nein*, und Sie werden
  nicht erneut gefragt. Jede Zustimmungsstelle hat eine Schaltfläche **Mehr Infos**,
  die das neue Handbuchkapitel *Anonyme Daten zur Applikationsoptimierung* öffnet.
- **Einstellungen → Datenschutz** – ein neuer Tab **Datenschutz** unter
  **Einstellungen** erlaubt es, die anonyme Nutzungsstatistik jederzeit ein- oder
  auszuschalten, und zeigt genau, was erfasst wird und was nicht, sowie das Datum
  Ihrer Entscheidung. Beim Ausschalten stoppt die Erfassung sofort, und sowohl die
  wartende Warteschlange als auch lokal zwischengespeicherte Ereignisse werden
  verworfen.
- **Offline-Zwischenspeicherung von Ereignissen** – wenn Sie offline sind, werden
  anonyme Ereignisse lokal unter `~/.kortty` zwischengespeichert und gesendet,
  sobald eine Verbindung verfügbar ist; sie überstehen Neustarts der App, werden
  nach drei Tagen verworfen und beim Widerruf vollständig gelöscht.

### Korrekturen

- **Effekte leuchten korrekt, wenn sie in einem laufenden Bereich aktiviert
  werden** – das zeilenweise Nachleuchten des MU/TH/UR-Effekts funktioniert jetzt
  auch, wenn Sie einen Effekt in einem bereits verbundenen Bereich einschalten,
  nicht nur, wenn der Effekt schon vor dem Verbinden aktiv war; außerdem pulsiert
  es bei schnell scrollender Ausgabe gleichmäßig statt zu flackern.
- **Strg+D in einem geteilten Bereich schließt nur diesen Bereich** – das Beenden
  der Shell (Strg+D oder `exit`) in einem Bereich eines Splits schließt jetzt nur
  diesen Bereich und lässt die übrigen offen; der Tab schließt sich erst, wenn die
  Sitzung des letzten verbleibenden Bereichs endet.
- **Terminaleffekte lassen das Rendering bei vielen offenen Tabs nicht mehr
  abstürzen** – mehrere Terminal-Tabs mit je einem aktiven Effekt konnten den
  GPU-Texturpool erschöpfen und das Rendering zum Absturz bringen. Effekt-Overlays
  (auch MU/TH/UR 6000) geben ihre fensterfüllende Leinwand-Textur jetzt frei,
  während ihr Tab im Hintergrund ist, und binden sie automatisch neu, sobald der
  Tab wieder angezeigt wird.
- **KI-Planung verkraftet abgeschnittenes oder fehlerhaftes JSON** – der Tab
  **KI-Planung** scheitert nicht mehr mit *„Die KI-Antwort enthielt nicht das
  erforderliche JSON-Objekt“*, wenn ein Modell einen unvollständigen oder nicht
  geschlossenen Plan zurückgibt – häufig bei sehr kleinen Modellen, die abbrechen,
  bevor das Schema fertig ist. Die Planung wiederholt den Versuch nun einmal mit
  einer Reparatur-Aufforderung und ist meist beim zweiten Mal erfolgreich; schlägt
  sie weiterhin fehl, erklärt die Meldung, dass das Modell zweimal ungültiges JSON
  lieferte, und empfiehlt ein größeres oder leistungsfähigeres Planungsmodell.
- **Das Anleitungsfenster stürzt im Hintergrund nicht mehr ab** – die geöffnete
  In-App-Anleitung (**Hilfe → Anleitung**, F1) offen zu lassen, während man zu
  einer anderen App wechselt, konnte korTTY nach einer Weile nativ zum Absturz
  bringen, weil der untätige eingebettete Browser im Hintergrund weiterarbeitete.
  korTTY entlädt die Anleitungsseite jetzt, nachdem das Fenster 20 Sekunden lang
  minimiert oder ohne Fokus war, und stellt beim Zurückkehren dieselbe Seite und
  Scrollposition wieder her; außerdem läuft das Intro-Video der Anleitung nur noch
  einmal statt in Endlosschleife.

## v2.3.3

### Korrekturen

- **macOS: „Quit korTTY“ beendet tatsächlich die App** – auf der paketierten macOS-App, a
natives Beenden (Befehl+Q, **Beenden von korTTY** im App-Menü, **Beenden** des Docks oder
logout) ließ korTTY im Hintergrund laufen, sodass der Prozess abgebrochen werden musste.
Die gepackte App läuft absichtlich weiter, nachdem das letzte Fenster geschlossen wurde (so
der JobScheduler kann Hintergrundjobs ausführen), aber JavaFX hat nur einen nativen Job übersetzt
Beenden Sie mit „Schließen Sie die Fenster“, niemals einen tatsächlichen Ausgang. korTTY fängt jetzt das ab
native quit und führt seine eigentliche Quit-Sequenz aus. Zusätzlich ein Shutdown-Watchdog
garantiert, dass der Prozess immer beendet wird, der Dialog „Warten auf laufende Jobs“.
Ich habe die Schaltfläche **Jetzt Beenden erzwingen** erhalten und die Bereinigung der Menüleistensymbole entfällt
Es besteht die Gefahr, dass das Herunterfahren verzögert wird.

## v2.3.2

### Korrekturen

- **Snippet-Editor-Diagramme werden gerendert, wenn Graphviz außerhalb der App installiert ist
PFAD** – Das Generieren eines **Diagramms** im Snippet-Editor schlägt nicht mehr fehl
„Zum Rendern von PlantUML-Diagrammen ist Graphviz Dot erforderlich“, wenn `dot` installiert ist
(z. B. über Homebrew), aber nicht auf dem minimalen PATH, den eine auf dem Desktop gestartete App erbt
von launchd. korTTY lokalisiert jetzt `dot` und die Java-Laufzeitumgebung auf die gleiche Weise
findet AI-CLIs – durchsucht den PATH und allgemeine Installationsverzeichnisse
(`/opt/homebrew/bin`, `/usr/local/bin`, …) – und übergibt den aufgelösten `dot`-Pfad
über `GRAPHVIZ_DOT` an den PlantUML-Renderer gesendet, sodass er es nicht erneut ermitteln muss.

## v2.3.1

### Korrekturen

- **„Als Textdatei laden“ folgt `cd` in lokalen Shells** – in einer Registerkarte einer lokalen Shell,
Laden einer ausgewählten Datei mit **Als Textdatei laden** nach Änderung der Verzeichnis-Nr
Die Datei wird nicht mehr gefunden. korTTY liest jetzt die Live-Funktion der Shell
Verzeichnis direkt vom Betriebssystem (das aktuelle Verzeichnis des Shell-Prozesses).
Verzeichnis), anstatt sich nur auf den Eingabeaufforderungstext zu verlassen, der nichts preisgibt
den vollständigen Pfad, wenn in der Eingabeaufforderung nur der Ordnername angezeigt wird (das macOS zsh
Standard). Unter macOS/Linux wird dadurch die Auswahl anhand des Verzeichnisses aufgelöst
Shell ist tatsächlich drin; Unter Windows wird auf die vorherige Eingabeaufforderung zurückgegriffen
Verhalten.
- **Die KI-Funktionen des Snippet-Editors arbeiten mit Argumentationsmodellen und sind gesprächig
Antworten** – KI-Aktionen im Snippet-Editor (**Diagramm**, **Rezension**,
**Verbessern**, **Assistent**, **Sicherheit**, **Alternativen**, **Beschreiben**,
**Vollständig**, **Einzeiler**) schlagen nicht mehr fehl – ​​z.B. *"PlantUML-Generierung
failed“* – wenn das Modell seine JSON-Antwort in Prosa oder einen Code-Fence verpackt, oder
Wenn ein lokales Argumentationsmodell (LM Studio, Ollama, llama.cpp) verwendet wird
DeepSeek-R1/QwQ/gpt-oss) gibt einen `<think>…</think>`-Block aus. Der Antwortparser
Entfernt jetzt durchgesickerte Argumente und extrahiert stattdessen robust die echte JSON-Nutzlast
eines gierigen Streichholzes, das bei jeder verirrten Zahnspange zerbrach.
- **Fehler der Snippet-Editor-KI sind jetzt sichtbar** – wenn eine Snippet-Editor-KI-Aktion ausgeführt wird
Schlägt ein Fehler fehl, wird die eigentliche Ursache in das Protokoll geschrieben und die entsprechende Meldung im angezeigt
Statusleiste. Zuvor wurde die Ausnahme verworfen, also eine falsch konfigurierte KI
Profil (z. B. ein Cloud-Profil ohne ausgewähltes Modell, das *„Wählen Sie ein
Modell…"*) führte dazu, dass jede KI-Funktion stillschweigend mit nur einer generischen Nachricht fehlschlug und
nichts im Protokoll.

## v2.3.0

### KI-Schwarm

- **Registerkarte „AI Swarm“** (**AI → AI Swarm...**, ++ctrl+alt+s++ / Cmd auf macOS) – Übertragung
eine KI-Agent-Aufgabe für viele Server gleichzeitig; Jeder Server führt seinen eigenen Agenten aus und
Die Antworten werden in einer einzigen Vergleichstabelle mit jeweils einer Zeile zusammengefasst
Server und eine wörtliche Spalte **"Fehler"** für Abweichungen und Fehler.
- **Animierter Statusstreifen** – eine Kugel pro Agent über den Konversationsshows
in der Warteschlange/wird ausgeführt/wartet auf Eingaben/angehalten/erledigt/fehlgeschlagen/abgebrochen auf einen Blick,
Flags *ungewöhnlich lange* Läufe über einen adaptiven Schwellenwert
(`max(60 s, 2 × median of finished agents)`) und das Klicken auf eine Kugel springt zum
Reihe des Agenten. Der Strip skaliert von 1 bis 50+ Servern.
- **Pro-Agent und schwarmweite Laufsteuerung** – Anhalten, Fortsetzen, Neustarten und Stoppen
entweder ein einzelner Agent (klicken Sie mit der rechten Maustaste auf seine Zeile) oder der gesamte Schwarm (Symbolleiste).
Das Pausieren ist kooperativ und stoppt die abgelaufenen Timer. Neustarts ersetzen nur
Die Antwort dieses Agenten.
- **Erweiterbare Live-Transkripte** – Klicken Sie mit der linken Maustaste auf eine Agentenzeile, um sie live anzusehen
Befehls-/Ausgabetranskript inline während der Ausführung.
- **Konversation kopieren und exportieren** – Kopieren Sie die gesamte Schwarmkonversation in die
in die Zwischenablage kopieren oder als einfachen Text, Markdown oder PDF exportieren; Gespeicherte Schwarmchats erhalten
ihren eigenen **Schwarm-Chats**-Bereich im AI Manager.
- **Lesbare Ergebniszeilen** – Durch Klicken auf eine Zeile der kombinierten Antworttabelle wird geöffnet
es in einem separaten **Zeilendetails**-Fenster mit der Schriftgröße A−/A+ und
In die Zwischenablage kopieren.
- **Ziele ohne offene Terminals** – Schwarmläufe (KI und Skript) funktionieren jetzt
gespeicherte Server ohne geöffnetes Terminal über Hintergrund-SSH-Sitzungen; kein Terminal
Die Registerkarte wird geöffnet. Erfordert einen entsperrten Master-Passwort-Tresor.
- **Skripte ohne KI ausführen** – Führen Sie ein Snippet Manager-Skript mit Parametern aus
auf allen Schwarmzielen parallel (Base64-übertragen, einmalige Bestätigung),
mit Live-Ausgabe pro Server und einer Exit-Code-/Ausgabeergebnistabelle pro Server.
- **Schedule Swarm Runs** – ein neuer JobScheduler-Aktionstyp **AI_SWARM** mit
**Schwarm-Parallelität** (1–16) und **Schwarm-Lesefelder**; die Schwarm-Registerkarte
Mit der Schaltfläche „Planen…“ wird ein Job anhand der aktuellen Ziele und Eingabeaufforderungen vorab ausgefüllt.
Die Ergebnisse gehen in das Journal *und* in einen gespeicherten Schwarm-Chat.
- **Sichtbarer Composer- und Tab-Statuspunkt** – die Schwarmeingabe ist klar umrahmt
dreizeiliges Feld und auf der Registerkarte wird ein farbiger Aktivitätspunkt angezeigt (läuft/wartet).
für Eingabe / Pause / Fertig – der grüne Punkt bleibt bis zum nächsten Durchlauf bestehen).
- **Multi-Server-Workflow-Dialog überarbeitet** – Syntax-hervorgehobene Skriptansicht, a
sichtbare Arbeitsanimation mit Live-Ablaufzeit und Gesamtdauer, an
Feld „Zusatzanweisungen“ mit einem deduplizierten Verlauf mit 10 Einträgen und
**In Snippets speichern** mit einem passenden vorab ausgefüllten Skriptnamen.

### Aussehen

- **Fünf neue App-Designs** – *Amber CRT* (warmes Bernstein-Phosphor-Retro-Terminal),
*Synthwave '84* (80er Outrun Neon), *Gruvbox Retro* (gemütlich warm erdig),
*Nord Arctic* (ruhiges, flaches arktisches Blaugrau) und *Dracula* (sanftes Lila/Rosa)
Fügen Sie die vorhandenen Designs unter *Einstellungen → Erscheinungsbild* hinzu, jedes mit seinem eigenen
Vorschau-Miniaturansicht. Die bestehenden Designs bleiben unverändert.
- **Subtile Designanimationen umschaltbar** – eine neue Darstellungseinstellung (standardmäßig aktiviert)
lässt die leuchtenden Designs einen kleinen Akzentpunkt in der Statusleiste einhauchen; es ausschalten
dient gleichzeitig als Option zur Bewegungsreduzierung und die Animation stoppt, während das Fenster geöffnet ist
ist versteckt.
- **Konsistenteres Design-Chrom** – es gelten jetzt die Farben eines benutzerdefinierten Designs
deterministisch über Menüs und Dialoge hinweg sowie die Dynamik des Terminalthemas
Das Stylesheet überschreibt nicht mehr die Chromfarben des aktiven Designs.
- **App-Design `Normal` in *Einstellungen → Erscheinungsbild* in `Default`** umbenannt. Der gespeicherte Wert bleibt unverändert, sodass vorhandene Konfigurationen ihr ausgewähltes Design behalten.
- Mit den Schaltflächen „Zurück/Weiter“ neben dem Dropdown-Menü „App-Design“ können Sie durch die Designs vor- und zurückblättern (an den Enden umschließen), ohne das Dropdown-Menü zu öffnen.
- **Die Designvorschau wurde unter die Steuerelemente verschoben** in einen Bereich mit fester Größe, sodass beim Wechseln der Designs (oder zurück zu `Default`, wo es keine Vorschau gibt) die Vorschau nicht mehr über dem Dropdown-Menü angezeigt wird.

### Lokale Shell-Verbindungen

- **Öffnen Sie die Shell des lokalen Computers in einem Terminal-Tab (kein Netzwerk)** – ein neues
Das **Local Shell**-Protokoll erzeugt stattdessen ein lokales Pseudo-Terminal (PTY) über pty4j
eine Verbindung zu einem Remote-Host herzustellen. Unter Windows können Sie **PowerShell** wählen.
(Standard) oder **cmd.exe**; Unter macOS/Linux wird standardmäßig Ihr `$SHELL` (fallend) verwendet
zurück zu `/bin/zsh` oder `/bin/bash`). Ein Freiformfeld **Benutzerdefinierter Befehl** akzeptiert
jede ausführbare Datei mit Argumenten (z. B. `pwsh.exe`, `wsl.exe -d Ubuntu`, Git Bash),
und ein optionales Startverzeichnis kann festgelegt werden. In beiden Fällen ist die lokale Shell wählbar
Quick Connect und der Verbindungsmanager; für diese Verbindungen
Host/Port/Benutzername/Authentifizierung sind nicht erforderlich und im deaktiviert
Dialoge.
- **Git Bash / Cygwin / WSL-Voreinstellungen** unter Windows – jeweils nur angeboten, wenn sie tatsächlich vorhanden sind
installiert (Git Bash/Cygwin über ihre üblichen Installationsorte / `PATH`; nur WSL).
wenn `wsl.exe` vorhanden ist und mindestens eine Distribution installiert ist). Der
Der Befehlsparser erkennt Anführungszeichen, daher können Shell-Pfade, die Leerzeichen enthalten (wie
`"C:\Program Files\Git\bin\bash.exe"`) korrekt starten.
- **Gemeinsame Connector-Hooks** – Terminalaufzeichnung/-protokollierung und die KI-Eingabe/-Daten
Haken wurden auf eine gemeinsame `ObservableTtyConnector`-Schnittstelle gehoben, also auch
Arbeit für lokale Muscheln. Nur-SSH-Kanal-Funktionen bleiben nur SSH-Kanal.
- **AI Agent & Planning in lokalen Shells** – die Befehlsausführungs-Engine des Agenten
wurde hinter einer `AgentCommandRunner`-Abstraktion (SSH exec.) von SSH entkoppelt
Kanal- und lokale Prozess-Backends). Der **KI-Agent** und **KI-Planung** jetzt
In lokalen Shells unter Windows, macOS und Linux ausführen: Befehle werden im ausgeführt
Verbindungs-Shell (PowerShell über `-EncodedCommand`, `cmd.exe` oder `$SHELL`),
Die Umgebungsprobe und die Systemeingabeaufforderung sind plattformorientiert und vorhanden
Es gilt der Genehmigungsablauf. Einschränkungen für lokale Shells: kein `sudo`/Administrator
Erhöhung unter Windows und keine Live-Nachverfolgung des Arbeitsverzeichnisses. Der JobScheduler
Die Aktion des kopflosen KI-Agenten erfolgt weiterhin nur über SSH.

### Terminal-Benutzerfreundlichkeit

- **Strg + Mausrad-Zoom** – **Strg** (oder **Befehl** unter macOS) gedrückt halten und
Wenn Sie mit dem Mausrad über das Terminal scrollen, ändert sich jetzt die Schriftgröße statt
Scrollen durch den Puffer. Dies ergänzt das bestehende Alt+Plus / Alt+Minus / Alt+0
Verknüpfungen.
- **Strg+D schließt eine lokale cmd.exe/PowerShell-Registerkarte** – diese Windows-Shells tun dies nicht
Exit auf EOF, daher hatte Strg+D dort keine Auswirkung. Für Shells der Bash-Familie
(Git Bash/Cygwin/WSL, macOS/Linux) und SSH behält Strg+D seine normale EOF-Bedeutung.

### KI-Chat und Agent

- **Bilder, Diagramme und Mathematik werden in KI-Chats gerendert** – KI-Antworten mit einem
SVG-Dokument, ein Base64-Rasterbild (`data:image/png;base64,…` – PNG, JPEG,
GIF, BMP), ein ` ```plantuml `-Block, ein ` ```mermaid `-Block oder LaTeX-Mathematik
(` ```latex `/` ```tex `/` ```math ` Blöcke und `$$…$$` in Prosa) werden als angezeigt
Bilder anstelle von Roh-Markup, jeweils mit einem Umschalter **Code anzeigen/Bild anzeigen** und
Schaltfläche „Kopieren“. Mermaid und MathJax sind gebündelt (kein Netzwerk); PlantUML verwendet die
lokale Toolchain (`java` + Graphviz `dot`); Die SVG-Ausgabe wird bereinigt und angezeigt
mit deaktiviertem JavaScript.
- **Vollständige Modellbegründung in Agent Thinking-Zeilen** – Erweitern einer 💭-Zeile in der
Das Agentenaktivitätsfeld zeigt jetzt die tatsächliche Argumentation des Modells beim Anbieter an
legt es offen (Anthropisches erweitertes Denken gemäß der Argumentationsbemühungen des Profils,
OpenAI-kompatibler `reasoning_content`, LM Studio Reasoning-Ausgabe, `<think>`
Blöcke aus lokalen CLI-Modellen), andernfalls wird auf die Entscheidungszusammenfassung zurückgegriffen.
- **Das Laufprotokoll zeichnet das AI-Profil auf** – jeder Agentenlauf beginnt mit einem
`AI profile: <name> (<model>)`-Aktivitätszeile.
- **Beim Neuladen wird das aktuell aktive Profil verwendet** – das Neuladen des Aktivitätsbereichs
Mit der Schaltfläche wird der Befehl mit dem Profil erneut ausgeführt, das jetzt aktiv ist, nicht mit dem Profil, das gerade aktiv ist
Der ursprüngliche Lauf wurde mit gestartet.
- **Agent Ask umfasst die Terminalauswahl** – Starten von **AI → Ask AI Agent**
aus dem Rechtsklick-Menü mit ausgewähltem Text sendet die Auswahl als Kontext, also
Die Frage wird bezüglich der ausgewählten Ausgabe oder des ausgewählten Skripts beantwortet.
- **Konkrete Modelle für Wolkenprofile** – die Modellauswahl ist vorab gefüllt
gängige Modellnamen für bekannte Cloud-Anbieter (offline, kein API-Schlüssel erforderlich), die
Klicken Sie auf die Schaltfläche „Aktualisieren“, um die Live-Modellliste des Endpunkts zusammenzuführen
Dropdown-Liste wendet es jetzt zuverlässig an und die unbrauchbare Option **Auto** ist nicht mehr vorhanden
Wird für Cloud-Endpunkte angeboten (mit einem deutlicheren Fehler, wenn kein Modell ausgewählt ist).

### Anleitung zur Suche nach AI-Dokumenten

- **Fragen Sie die Anleitung in natürlicher Sprache** – die integrierte Anleitung (**Hilfe → Anleitung**,
++f1++) erhält ein Seitenfeld **KI-Suche**: Geben Sie eine Frage in Ihrer Sprache ein und
Erhalten Sie eine Antwort, die ausschließlich aus der mitgelieferten Dokumentation generiert wird, mit
Anklickbare Zitate, die den Anleitung direkt zur verwiesenen Seite weiterleiten.
- **Verwendet Ihr Standard-KI-Profil; Der Abruf erfolgt vollständig offline** – kein Server, nein
zusätzliche API-Schlüssel, keine neuen Abhängigkeiten. Der Abruf erfolgt lokal über das Bundle
Suchindex (mit zweisprachigen Synonymen, deutscher Kompositumsteilung und Umlaut).
falten); Off-Topic-Fragen werden vor Ort beantwortet, ohne dass die KI kontaktiert werden muss
Endpunkt überhaupt.
- **Fundierte Antworten** – das Modell ist auf die abgerufenen Auszüge beschränkt,
erfundene Links werden repariert oder entfernt, und es gibt immer eine native **Quellen**-Liste
zeigt die zitierten Seiten unabhängig von der Antwort des Modells an.

### Workflow-Skriptgenerator

- **Zwei neue Zielsprachen** – der Agent kann den → **Workflow**-Skriptgenerator ausführen
Jetzt **Windows-CMD** (`.cmd`-Batch) und **AppleScript** (`.applescript`) erstellen
zusätzlich zu Bash, Python, Perl, Ruby, PowerShell und Ansible.
- **Anpassbare Skriptschriftgröße** – jeder Editor für generierte Skripte verfügt über **A−** /
**A+**-Tasten und unterstützt **Strg + Mausrad** (Cmd unter macOS); der Auserwählte
Die Größe wird sitzungsübergreifend gespeichert.
- **Sichtbarer Fortschritt während der Generierung eines Diagramms** – Generieren einer PlantUML
Das Diagramm aus einem Skript zeigt jetzt den funktionierenden Spinner.
- **Deutlichere AI-Backend-Fehler** – Fehler aufgrund von unzureichendem Arbeitsspeicher/Ressourcenlimit
AI-Server (z. B. LM Studio/MLX „Ressourcenlimit überschritten“, „metal::malloc“) zeigt a
kurzer, umsetzbarer Hinweis anstelle des rohen Stack-Trace; Alle anderen KI-Fehler sind es
auf eine einzige Zeile reduziert.

### Korrekturen

- **Das Schließen einer lokalen Shell friert korTTY nicht mehr ein** – der PTY-Prozess ist jetzt
zerstört, bevor seine Streams geschlossen werden, wodurch ein Terminal-Reader-Thread freigegeben wird
in einem pty `read()` blockiert, anstatt das Schließen des JavaFX-Threads zu blockieren.
- **Korrekter Wortlaut für lokale Shells beim Schließen** – die Schließbestätigungs-Nr
sagt länger: „SSH-Verbindung beenden?“ für eine lokale Shell und die Eingabeaufforderung zum Schließen des Fensters
ist nun transportneutral („Aktive Sitzungen“).
- **Keine Passwortabfrage für lokale Shells** – Das Öffnen einer lokalen Shell wird nicht mehr angezeigt
ein irrelevanter Passwortdialog (lokale Shells verwenden keine Authentifizierung).
- **„Als Textdatei laden“ funktioniert in lokalen Shells** – Rechtsklick auf eine ausgewählte Datei
Name in einer lokalen Shell-Registerkarte und die Auswahl von **Als Textdatei laden** schlägt nicht mehr fehl
mit „Es ist keine aktive SSH-Verbindung verfügbar“. Die Datei wird lokal gelesen
Dateisystem – wird anhand des in der Shell-Eingabeaufforderung angezeigten Arbeitsverzeichnisses aufgelöst
wenn verfügbar, andernfalls das Verzeichnis, in dem die Shell gestartet wurde – und geöffnet wird
im Snippet-Editor mit **Lokale Datei überschreiben** und **Speichern unter...** einfach
wie die SSH/SFTP-Variante. Die Fehlermeldung „Nicht verbunden“ lautet jetzt
transportneutral.
- **Überschreibungen lokaler Dateien sind jetzt atomar** – beide „Lokale Datei überschreiben“-Abläufe
(local-Shell **Als Textdatei laden** und der lokale Dateieditor des SFTP-Managers)
Wird verwendet, um die Zieldatei an Ort und Stelle zu kürzen, so dass ein Fehler während des Schreibvorgangs auftritt (Festplatte voll,
Prozess abgebrochen, Stromausfall) könnte dazu führen, dass es ohne Wiederherstellung abgeschnitten wird.
Überschreibt jetzt, schreibt in eine temporäre Geschwisterdatei und verschiebt sie an ihren Platz, behält sie bei
die POSIX-Berechtigungen der Originaldatei und schreiben Sie über symbolische Links darauf
echtes Ziel, anstatt den Link selbst zu ersetzen.

## v2.2.3

### Kritischer Fix: Die Monaco-Editoren konnten die gepackte App nicht laden

- **Behoben, dass die auf Monaco basierenden Editoren (Snippet, Datei, AI, Diff) leer geöffnet wurden
Bereich in der gepackten/beglaubigten macOS-App** – kein Einfügezeichen, kein Eintippen, kein Einfügen. In
In der gepackten App hat WebView seine Seite von einer `jar:`-URL und den Seiten geladen
Content-Security-Policy (`script-src 'self'`) blockierte dann die eigene des Herausgebers
`monaco-host.js`/`.css`, da ein Dokument mit `jar:`-Ursprung dies nicht autorisiert
`jar:` Geschwister. Die Monaco-Ressourcen werden jetzt in ein temporäres Verzeichnis extrahiert und
wird von einer `file:`-URL geladen, was der CSP zulässt. Ein fehlgeschlagener Editor-Ladevorgang ist jetzt ebenfalls aufgetreten
zeigt einen Fehler anstelle eines stillschweigend leeren Bereichs an, und das Editor-Bundle ist es auch
zusätzlich minimiert durch ein großzügigeres Boot-Budget.

## v2.2.2

### Kritischer Fix: Absturz beim Öffnen der Monaco-Editoren

- **Ein schwerer Absturz (kein Bildschirmfehler) beim Öffnen des Snippet-Managers wurde behoben
Snippet-Editor oder der AI-Skill-Editor für Einstellungen in Paket-Builds**: der
In der gebündelten Laufzeit fehlte also das `jdk.jsobject`-Modul
`netscape.javascript.JSObject` war zur Laufzeit nicht verfügbar und die JVM stürzte ab
in JNI `get_method_id` (`SIGSEGV`). `jdk.jsobject` ist jetzt im Paket enthalten
verpackte Laufzeit. Diese Version ersetzt v2.2.0 und v2.2.1, deren Binärdateien sind
von diesem Absturz betroffen.

## v2.2.1

### Stabilitätskorrekturen

- **Absturz des Einstellungen-/Snippet-Managers behoben**: Öffnen der **Globalen Einstellungen** oder des
**Snippet Manager** könnte die App abbrechen. Der eingebettete Monaco-Editor
Die JavaScript→Java-Brücke ist nun eine starke Referenz für den Herausgeber
Lebensdauer.
- **Verstärkung des WebView-Lebenszyklus**: Monaco-Editoren werden bei ihrem Dialog entsorgt
schließt; Späte Timer-/Laderückrufe nach dem Schließen werden ignoriert. Die Einstellungen *AI
Der Skills*-Editor lädt bei der ersten Verwendung langsam.

### Anmeldefenster mit Master-Passwort

- **Vollflächiges animiertes Logo** im Standard-App-Design, mit Passwortformular
überzogen mit einer durchscheinenden Karte.

## v2.2.0

### Terminal-Engine und Hyperlinks

- Terminal-Engine **SithTermFX 1.2.0** (aus dem Quellcode erstellt).
- **Anklickbare OSC 8-Hyperlinks** – Links, die von Programmen wie ausgegeben werden
`ls --hyperlink` oder `eza`, beschränkt auf eine sichere URI-Schema-Zulassungsliste.

### Mosh (mosh4j) 2.0.2 Upgrade und Sicherheitshärtung

- mosh4j `2.0.0 → 2.0.2` mit Wiedergabe-/Aktivitätsschutz pro Richtung und
Grenzwerte für Dekompressionsbomben; Geben Sie JARs frei, die in nativen Builds gebündelt sind.
- Hüpfburg `1.78.1 → 1.84` (behebt CVE-2026-5598 HIGH und CVE-2026-0636).
MÄSSIG); protobuf-java `4.28.2 → 4.35.1`.

### KI-Agenten-Panel und -Aktivität

- **Platzierung des AI Agent Panels**: *Unten* (Standard), *Links andocken* oder *Andocken
Richtig*, über alle Neustarts hinweg im Gedächtnis geblieben.
- **Mehrere gleichzeitige Läufe pro Split** (Kapitel 5), Pause/Fortsetzung pro Lauf und
Dashboard-/Tab-Statusabzeichen (✋ wartend · ⚡ in Arbeit · ⏸ pausiert · ✓ fertig).

!!! Notiz
Ältere Versionen werden im `app-docs/RELEASE_NOTES.adoc` des Repositorys erfasst
und wird vollständig hierher migriert.
