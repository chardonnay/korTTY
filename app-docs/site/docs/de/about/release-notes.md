# Versionshinweise

Das vollständige Versions-Änderungsprotokoll. Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.4.2

### Branding

- **Neues App-Icon & Logo** – das Programmicon (macOS `.icns`, Windows `.ico`, sowie die 1024²-Master-PNG) wurde neu gestaltet: ein Neon-`>`-Chevron mit Prompt-Cursor und ein lila→grünes neuronales Netz, ohne den Schriftzug „korTTY" (der bei kleinen Dock-/Taskleisten-Größen unruhig wirkte). Das In-App-Logo im Master-Passwort-Dialog und im Info-Fenster wurde passend aktualisiert, mit dem Untertitel „AI-Driven Terminal Experience".

### Quick Connect

- **Einklappbare Options-Sektionen** – die optionalen Einstellungen (Verbindungstimeout, Terminal-Darstellung, Terminal-Effekt, KI) sind in einklappbare Sektionen gruppiert, sodass der Dialog kompakt öffnet; Verbindungstimeout startet jetzt mit festen Standardwerten (10 s / 0 Wiederholungen). Das Formular scrollt innerhalb des Dialogs, wenn aufgeklappte Sektionen über den Bildschirm hinauswachsen, und der Dialog **merkt sich über Neustarts hinweg, welche Sektionen Sie aufgeklappt gelassen haben**.
- **Verbindungs-Skills-Auswahl** – KI-Skills für die neue Verbindung vorauswählen: Suche mit `*`-Platzhaltern, Alle/Leeren-Schalter und eine **Speichern**-Schaltfläche, die die aktuelle Auswahl dauerhaft als Standard für jede neue Verbindung übernimmt.

### Terminal

- **Scrollback-Einstellung funktioniert jetzt** – der Wert **Scrollback** unter **Konfiguration → Globale Einstellungen → Terminal** (100–100.000 Zeilen) wird jetzt tatsächlich auf den Terminal-Puffer angewendet; er wurde zuvor ignoriert, und jeder Bereich nutzte fest 10.000 Zeilen. Der Wert wird beim Erstellen eines Terminals gelesen und gilt daher für neu geöffnete Tabs und geteilte Bereiche.
- **Lokale Shells starten im Home-Verzeichnis** – eine neue lokale Shell (macOS/Linux) startet nicht mehr in `/`, wenn korTTY über Finder/Dock gestartet wird; sie startet jetzt korrekt im Home-Verzeichnis.

### Snippet-KI

- **Entartete KI-Antworten löschen keinen Code mehr** – beim Anwenden von KI-Verbesserungen oder eines Sicherheitsfixes auf ein Snippet konnte mit einem schwachen/lokalen Modell und einem aktiven KI-Skill das gesamte Snippet durch einen bloßen Platzhalter (buchstäblich `$code`) ersetzt werden, den das Modell zurückgab. Das wird jetzt erkannt und abgelehnt – Sie erhalten *„KI-Antwort war kein gültiges Snippet – Code unverändert gelassen"* statt Ihren Code zu verlieren – und der KI-Prompt selbst wurde gehärtet, damit die Anweisungen eines Skills das Modell nicht mehr dazu bringen können, einen Platzhalter statt echten Quellcode zurückzugeben.
- **Diagramm-Dunkelmodus erfasst jede Farbe** – im Flussdiagramm der KI-Codeanalyse werden im Dunkelmodus jetzt alle hellen Knotenfarben auf einen passenden dunklen Farbton abgedunkelt, nicht nur die drei häufigsten, sodass der helle Knotentext unabhängig von der gewählten Farbe lesbar bleibt.

### Sonstiges

- **KI-Manager merkt sich sein Fenster** – der KI-Manager-Dialog öffnet jetzt wieder in der Größe und Position, in der Sie ihn zuletzt verlassen haben, statt jedes Mal zurückgesetzt zu werden.

### Leistung und Speicherbedarf

- **Viel geringerer Speicherverbrauch** – KI-Chat-Registerkarten, Dateieditoren und die Snippet-KI-Fenster geben jetzt ihre eingebetteten Browser-Engines (Monaco/WebView) frei, wenn sie geschlossen werden, wenn Chat-Nachrichten nach einer Änderung der Schriftgröße neu gerendert werden und wenn Lösungen oder Skripte neu generiert werden; das Schließen eines geteilten Bereichs gibt jetzt auch dessen Scrollback-Puffer und Timer frei. Lange Sitzungen mit vielen Chats, Editoren und geteilten Bereichen sammeln keinen Speicher mehr an.
- **Begrenzter Speicherbedarf** – die paketierte Anwendung läuft jetzt mit einer Java-Heap-Obergrenze von 2 GB und gibt ungenutzten Speicher im Leerlauf regelmäßig an das Betriebssystem zurück.
- **Opt-in-Ressourcenprofil** – über einen neuen Reiter **Konfiguration → Globale Einstellungen → Ressourcen** können Sie diesen geringen Speicherbedarf bei Bedarf gegen mehr Ressourcen Ihres Rechners eintauschen: **Hoch** erhöht den Heap auf etwa die Hälfte Ihres RAM, **Maximal** auf etwa drei Viertel mit dem pausenarmen Z-Garbage-Collector. Die Voreinstellung (**Ausbalanciert**) bleibt unverändert. Siehe [Ressourcen](../reference/settings/resources.md).
- **Kleinere Downloads und Installationen** – die mitgelieferte Formatter-Laufzeit und die eingebettete Java-Laufzeit wurden verschlankt und komprimiert, das macOS-Disk-Image nutzt jetzt eine stärkere (LZMA-)Kompression, und das Offline-Anleitung enthält keine Entwickler-Sourcemaps und ungenutzten Suchkomponenten mehr. Installationen schrumpfen um rund 150 MB, Downloads um 25–40 %.
- **Datums- und Zahlenformate** – die installierte Anwendung bündelt Java-Gebietsschemadaten nur für die 8 unterstützten Oberflächensprachen; bei Betriebssystem-Gebietsschemata außerhalb dieser Liste werden Datums- und Zahlenangaben nach englischen Konventionen formatiert.

## v2.4.1

### Snippet-KI-Codeanalyse

- **Vollständige Code-Analyse** – Die Option *AI-Code → Vollständige Code-Analyse* des Snippet-Editors öffnet ein umfangreiches, nicht modales Fenster mit einer Zusammenfassung der Funktionsweise des Skripts in Klartext, seinen externen Abhängigkeiten (jeweils mit einem Reduzierungs-/Ersetzungsvorschlag), Verbesserungsvorschlägen gruppiert in Sicherheit/Optimierung/Design, die Sie ankreuzen und anwenden, und einem automatisch generierten Flussdiagramm. Das Diagramm verfügt über die vollständige Symbolleiste (Zoom, Anpassen, SVG/PNG speichern, Bild/PlantUML kopieren, Hintergrundfarbe, Regenerieren) und hebt die passenden Quellzeilen hervor, wenn Sie mit der Maus über einen Knoten fahren.
- **Dateinamen in Titelleisten** – Der Snippet-Editor und das Analysefenster zeigen jetzt den Dateinamen des Skripts in ihren Titelleisten an.
- **KI-Profil pro Lauf und erneuter Lauf** – in den Analyse- und anderen AI-Code-Fenstern können Sie ein vorübergehendes KI-Profil für den nächsten Lauf auswählen und damit erneut laufen; Schriftgrößen werden pro Fenster gespeichert.
- **KI-Skills-Auswahl** – relevante KI-Skills werden automatisch vorab ausgewählt und können angeheftet werden, sodass sie für jede AI-Code-Aktion gelten.
- **Härtungsoptionen** – *Verbesserung der Robustheit*, *Benutzerdefinierte Verbesserung*, *Vollständige Codeanalyse*, und beide Workflow-Skriptgeneratoren können einen ausgewählten Satz von Techniken in Produktionsqualität (strenger Modus, Fehlerfallen, sinnvolle Exit-Codes, Protokollierung, Idempotenz, Probelauf, `--help` und mehr) in das Ergebnis integrieren. In der neuen [Hardening options](../reference/hardening-options.md)-Referenz] erfahren Sie, was die einzelnen Optionen bedeuten und wie sie angewendet werden.
- **Diagramm-Dunkelmodus** – beide Diagrammfenster (vollständige Codeanalyse und das eigenständige Diagrammdialogfeld) haben eine **Dunkelmodus**-Schaltfläche mit *Auto* (dem Erscheinungsbild des Betriebssystems folgend), *Hell* und *Dunkel* erhalten. Die Auswahl wird gespeichert und färbt das gesamte Diagramm neu ein – dunkle Leinwand, abgedunkelte Knotenkarten mit hellem Text und helle Anschlüsse –, während der manuelle Hintergrundfarbwähler jetzt die Diagrammseite selbst (nicht nur ihren Rand) einfärbt und auf exportiertes SVG/PNG anwendet.
- **Windows sperrt das Hauptfenster nicht mehr** – die Snippet-Fenster **Diff** und **Variablen verwalten**, die über den Snippet-Manager geöffnet werden, frieren das Hauptfenster von KorTTY nicht mehr ein, während sie geöffnet sind.
- **Härtungsoptionen – Alle / Löschen / Speichern** – jedes Härtungsoptionsfeld verfügt über die Schaltflächen **Alle** (alles ankreuzen), **Löschen** (alles abhaken) und **Speichern**. Speichern speichert Ihre Auswahl dauerhaft, sodass jedes Härtungsfenster dann mit Ihren bevorzugten Optionen anstelle der vollständigen Standardoptionen geöffnet wird.

### Snippet-Sicherheitsüberprüfung

- **Erläuterte Sicherheitsfixes** – Das Fenster **Sicherheitsfixes überprüfen** stellt jetzt den ursprünglichen und den korrigierten Snippet in einem Parallelvergleich dar, der geänderte Zeilen automatisch hervorhebt. Wenn Sie den Mauszeiger über einen geänderten Block bewegen, benennen Sie den/die Befund(e), auf den/die er sich bezieht (zum Beispiel `S1` oder `S1 + S2`, wenn ein Block zwei Befunde abdeckt) und zeigen Sie den/die Grund(e) an. Jeder Grund wird auch als Karte unter dem Diff aufgeführt, sodass die Begründung sichtbar bleibt.
- **Spezielles KI-Profil für Sicherheitsüberprüfungen** – Sie können ein separates KI-Profil nur für Sicherheitsüberprüfungen auswählen und KorTTY speichert es dauerhaft. Legen Sie es im Fenster „Sicherheitsüberprüfung“ oder unter **Konfiguration → Globale Einstellungen → AI** fest; Beide Orte haben dieselbe Einstellung, und wenn Sie diese leer lassen, wird das Standardprofil wiederverwendet.
- **Verbesserungen des Sicherheitsüberprüfungsfensters** – anpassbare (und gespeicherte) Schriftgröße, eine Schaltfläche zum Kopieren in die Zwischenablage für alle Ergebnisse, farbcodierte Schweregrad-Badges mit der Sortierung „Schweregrad zuerst“, Umschalter „Alles auswählen“ und eine Schaltfläche **Überprüfung erneut ausführen**, die die Überprüfung mit dem ausgewählten Profil wiederholt.
- **Diff-Zoom gespeichert** – Die Schriftgröße in den AI-Differenz-/Überprüfungsfenstern wird jetzt global gespeichert und nicht nur für die aktuelle Sitzung.

### Terminal

- **Anpassbare Terminal-Hintergrundtransparenz** – **Ansicht → Zoom → Hintergrundtransparenz** ist ein neuer Schieberegler (0–100 %), der den Terminal-Hintergrund auf dem Desktop durchscheinen lässt, während der Text völlig undurchsichtig und scharf bleibt. Der Wert wird über Neustarts hinweg gespeichert. Nur der Terminalbereich wird transparent – ​​Titel, Menü und Statusleiste bleiben einfarbig. Da der Durchsichtmodus ein randloses Fenster verwendet, wird das Ein- oder Ausschalten erst nach einem Neustart wirksam. In diesem Modus ermöglicht eine schlanke benutzerdefinierte Titelleiste das Verschieben, Ändern der Größe, Minimieren, Maximieren und Schließen. Die Anpassung des Pegels bei bereits transparentem Zustand gilt live.
- **Leiseres Schließen eines Tabs** – Beim Schließen eines Terminal-Tabs wird jetzt nur noch nach einer Bestätigung gefragt, wenn etwas verloren geht: Der Tab hat geteilte Bereiche oder ein Befehl wird noch ausgeführt (erkannt aus der Prozessstruktur der lokalen Shell oder aus der SSH-Shell-Eingabeaufforderung). Ein inaktives einzelnes Terminal wird sofort geschlossen. Die verbindungsspezifische Option *Ohne Bestätigung schließen* unterdrückt die Eingabeaufforderung weiterhin vollständig.
- **Eindeutigere Beschriftung im Kontextmenü** – die Terminal-Rechtsklick-Aktion, die eine ausgewählte Remote-Datei in den Snippet-Editor lädt, heißt jetzt **Im Snippet-Editor öffnen** (vorher *Als Textdatei laden*).

## v2.4.0

### Endgültige Auswirkungen

- **Zehn neue integrierte Terminaleffekte** – neben MU/TH/UR 6000 enthält ein gebündeltes Effektpaket jetzt zehn thematische Effekte, die Cyberpunk-, Retro- und Grusel-Stile umfassen: **Amber CRT '90** (Bernstein-Phosphor-Monitor der 90er Jahre mit Scanlines, Glühen, Flimmern und einem rollenden Aktualisierungsband), **Commodore Heritage** (C64 blau mit Ladebalken), **Neon City** (Glitch Tears und RGB-Split-Flimmern), **Digital Rain** (schwach fallende Matrixglyphen), **Hologram HUD** (Interferenzbänder und HUD-Eckklammern), **Poltergeist** (atmende Vignette, statische Ausbrüche und geisterhafte Blitze), **VHS 1987** (Verfolgungsgeräusch, rollende Verzerrung und eine PLAY-Überlagerung), **Synthwave Horizon** (leuchtendes perspektivisches Raster), **Deep Space Radar** (langsamer Radardurchlauf mit Blips) und **Typewriter Noir** (Sepia). Papieroptik mit zeichenweiser Ausgabegeschwindigkeit). Jeder Effekt berücksichtigt die Einstellung der Animationsgeschwindigkeit und seine Beschreibung ist in allen unterstützten Sprachen lokalisiert.
- **Getaktete Ausgabe der Typewriter Noir** – der Effekt **Typewriter Noir** gibt die Terminalausgabe Zeichen für Zeichen aus, um das Gefühl einer mechanischen Schreibmaschine zu erzeugen; Bei Massenausgaben wie dem Drucken einer großen Datei wird die Geschwindigkeit umgangen, sodass sie nie verlangsamt wird.
- **Terminaleffekte pro Bereich** – Terminaleffekte beziehen sich jetzt auf jeden einzelnen geteilten Bereich statt auf die gesamte Registerkarte. Innerhalb einer Registerkarte können Sie einen Effekt in einem Bereich ausführen, während ein gleichgeordneter Bereich einen anderen oder keinen Effekt anzeigt und die Farben und Schriftarten jedes Effekts auf seinen eigenen Bereich beschränkt bleiben. Globales Zoomen und Zurücksetzen gelten weiterhin für die gesamte Registerkarte.
- **Fensterspezifisches Effektmenü und Vererbung** – das Rechtsklick-Menü jedes geteilten Fensters erhält ein Untermenü **Terminaleffekt**, in dem Sie **Keine** oder einen beliebigen installierten Effekt nur für dieses Fenster auswählen können, sowie einen Schieberegler für die Animationsgeschwindigkeit; Die Auswahl gilt nur zur Laufzeit und wird nicht in der Verbindung gespeichert. Beim Teilen eines Bereichs wird der neue Bereich mit dem gleichen Effekt und der gleichen Animationsgeschwindigkeit wie der Bereich gestartet, von dem er geteilt wurde.
- **Animierte Effektvorschauen im Plugin-Manager** – **Plugins → Terminaleffekte** zeigt jetzt eine animierte Live-Vorschau des ausgewählten Effekts neben der Plugin-Liste an, sodass Effekte verglichen werden können, bevor sie in einer Sitzung aktiviert werden. Plugins ohne Vorschau zeigen stattdessen einen Platzhalter an.

### KI-Chat

- **Chat-Farbprofile** – der AI-Chat und der AI-Swarm-Chat verfügen jetzt über auswählbare Farbthemen. Elf integrierte Profile werden mitgeliefert: **Automatic (Theme)**, das Ihrem aktiven Terminal-Theme folgt, plus **Original**, **Paper**, **Midnight**, **Cyberpunk**, **Retrowave**, **Forest**, **Ocean**, **Terminal**, **GPT** und **Cute**. Wählen Sie eines aus der Dropdown-Liste „Farbprofil“ in der Chat-Symbolleiste oder unter **Einstellungen → Erscheinungsbild → Chat-Farbprofil**; Die Auswahl wird gespeichert und live auf jeden offenen Chat angewendet.
- **Volltext-Chat-Suche** (++Strg+F++, Befehl+F auf macOS) – klicken Sie auf die Schaltfläche **Suchen** in der Chat-Symbolleiste oder drücken Sie die Tastenkombination, um eine Suchleiste über einer KI- oder Schwarmkonversation zu öffnen. Es durchsucht den gesamten Chat einschließlich Codeblöcken, zeigt die Anzahl der Live-Matches an, springt mit den Pfeiltasten oder der Eingabetaste zwischen Treffern und skizziert und scrollt jedes Match in die Ansicht. Esc schließt die Leiste.
- **Neu gestalteter, vollständig themenbezogener Chat** – der KI-Chat und der Schwarm-Chat wurden neu gestaltet, sodass Ihre Nachrichten in einer nach rechts eingerückten abgerundeten Blase angezeigt werden und jede KI-Antwort eine Karte in voller Breite darstellt, wobei Codeblöcke, Tabellen, der Komponist und Bildlaufleisten alle dem ausgewählten Farbprofil folgen, anstatt einem festen Lichtstil.

### Datenschutz und Analyse

- **Anonyme Nutzungsanalyse (Opt-in, standardmäßig deaktiviert)** – korTTY kann optional anonyme, DSGVO-konforme Nutzungsstatistiken über Aptabase teilen (verarbeitet auf EU-Servern), um Funktionen zu priorisieren und Abstürze und häufige Fehler aufzudecken. Es ist standardmäßig eine strikte Opt-in- und Deaktivierungsfunktion. Es werden nur Ereignisnamen, aggregierte Anzahlen und Flags, die App-Version, der Name und die Version des Betriebssystems, die App-Sprache und eine anonyme Sitzungs-ID pro Start gesendet – Hostnamen, Benutzernamen, Verbindungsdaten, Dateipfade, Snippet-/Terminal-/Chat-Inhalte, Schlüssel, Passwörter und Fehlermeldungstexte werden niemals erfasst.
- **Einmalige Einwilligungsaufforderung** – Sie werden genau einmal gefragt, ob Sie anonyme Daten weitergeben möchten: bei Neuinstallationen als Kontrollkästchen neben der Einrichtung des Master-Passworts und bei bestehenden Installationen als einmalige Aufforderung nach dem Entsperren. Jede Entlassung gilt als *nein* und Sie werden nicht erneut gefragt. Jede Einwilligungsoberfläche verfügt über eine Schaltfläche **Weitere Informationen**, die das neue Anleitungkapitel *Anonyme Daten zur Anwendungsoptimierung* öffnet.
- **Einstellungen → Registerkarte „Datenschutz“** – eine neue Registerkarte **Datenschutz** unter **Einstellungen** ermöglicht es Ihnen, anonyme Nutzungsstatistiken jederzeit ein- oder auszuschalten und zeigt genau an, was erfasst wird und was nicht, sowie das Datum, an dem Ihre Wahl aufgezeichnet wurde. Wenn Sie es deaktivieren, wird die Erfassung sofort gestoppt und sowohl die ausstehende Warteschlange als auch alle lokal zwischengespeicherten Ereignisse verworfen.
- **Offline-Ereignis-Caching** – während Sie offline sind, werden anonyme Ereignisse lokal unter `~/.kortty` zwischengespeichert und gesendet, sobald eine Verbindung verfügbar ist; Sie überleben App-Neustarts, werden nach drei Tagen gelöscht und werden vollständig verworfen, wenn Sie sich abmelden.

### Korrekturen

- **Effekte leuchten korrekt, wenn sie für ein Live-Fenster aktiviert sind** – das zeilenweise Leuchten des MU/TH/UR-Effekts funktioniert jetzt, wenn Sie einen Effekt in einem bereits verbundenen Fenster aktivieren, nicht nur, wenn der Effekt vor der Verbindung aktiv war, und es pulsiert bei der Ausgabe mit schnellem Scrollen gleichmäßig, anstatt zu flackern.
- **Strg+D in einem geteilten Bereich schließt nur diesen Bereich** – das Verlassen der Shell (Strg+D oder `exit`) in einem Bereich einer Teilung schließt jetzt nur diesen Bereich und lässt die anderen Bereiche geöffnet; Die Registerkarte wird erst geschlossen, wenn die Sitzung des letzten verbleibenden Bereichs endet.
- **Terminaleffekte stürzen das Rendern nicht mehr ab, wenn viele Tabs geöffnet sind** – Das Öffnen mehrerer Terminal-Tabs mit jeweils einem aktiven Effekt könnte den GPU-Texturpool erschöpfen und das Rendern abstürzen lassen. Effekt-Overlays (einschließlich MU/TH/UR 6000) geben jetzt ihre Leinwandtextur für das gesamte Fenster frei, während sich ihre Registerkarte im Hintergrund befindet, und binden sich automatisch neu, wenn die Registerkarte erneut angezeigt wird.
- **AI Planning stellt abgeschnittenes oder fehlerhaftes JSON wieder her** – die Registerkarte **AI Planning** schlägt nicht mehr vollständig mit der Meldung *„AI-Antwort enthielt nicht das erforderliche JSON-Objekt“* fehl, wenn ein Modell einen unvollständigen oder nicht geschlossenen Plan zurückgibt, was häufig bei sehr kleinen Modellen vorkommt, die vor Abschluss des Schemas anhalten. Planning versucht es jetzt einmal mit einer Reparaturaufforderung erneut und ist normalerweise beim zweiten Versuch erfolgreich. Wenn es immer noch fehlschlägt, erklärt der Fehler, dass das Modell zweimal ungültiges JSON zurückgegeben hat, und schlägt ein größeres oder leistungsfähigeres Planungsmodell vor.
- **Anleitungsfenster stürzt nicht mehr im Hintergrund ab** – Wenn Sie die In-App-Anleitung (**Hilfe → Anleitung**, F1) geöffnet lassen, während Sie zu einer anderen App wechseln, kann korTTY nach einer Weile nativ abstürzen, da der inaktive eingebettete Browser im Hintergrund läuft. korTTY entlädt jetzt die Guide-Seite, nachdem das Fenster 20 Sekunden lang minimiert oder unfokussiert war, und stellt beim Zurückkehren dieselbe Seite und Bildlaufposition wieder her, und das Einführungsvideo des Guides wird einmal abgespielt, anstatt sich endlos zu wiederholen.

## v2.3.3

### Korrekturen

- **macOS: „Quit korTTY“ beendet tatsächlich die App** – in der gepackten macOS-App wurde korTTY durch ein natives Beenden (Cmd+Q, **Quit korTTY** im App-Menü, **Quit** des Docks oder Abmelden) im Hintergrund ausgeführt, sodass der Prozess abgebrochen werden musste. Die gepackte App läuft absichtlich weiter, nachdem das letzte Fenster geschlossen wurde (damit der JobScheduler Hintergrundjobs ausführen kann), aber JavaFX hat ein natives Beenden nur in „Fenster schließen“ übersetzt, nie in ein tatsächliches Beenden. korTTY fängt nun das native Quit ab und führt seine eigentliche Quit-Sequenz aus. Ein Shutdown-Watchdog garantiert außerdem, dass der Prozess immer beendet wird, das Dialogfeld „Warten auf laufende Jobs“ hat eine Schaltfläche „Jetzt beenden erzwingen“ erhalten und die Bereinigung der Menüleistensymbole birgt nicht mehr das Risiko, dass das Herunterfahren verzögert wird.

## v2.3.2

### Korrekturen

- **Snippet-Editor-Diagramme werden gerendert, wenn Graphviz außerhalb des PATH der App installiert ist** – das Generieren eines **Diagramms** im Snippet-Editor schlägt nicht mehr mit „Graphviz dot ist erforderlich, um PlantUML-Diagramme zu rendern“ fehl, wenn `dot` installiert ist (z. B. über Homebrew), aber nicht auf dem minimalen PATH, den eine auf dem Desktop gestartete App von launchd erbt. korTTY findet `dot` und die Java-Laufzeit jetzt auf die gleiche Weise, wie es AI-CLIs findet – durchsucht den PATH sowie allgemeine Installationsverzeichnisse (`/opt/homebrew/bin`, `/usr/local/bin`, …) – und übergibt den aufgelösten `dot`-Pfad über `GRAPHVIZ_DOT` an den PlantUML-Renderer, sodass dieser ihn nicht erneut ermitteln muss.

## v2.3.1

### Korrekturen

- **„Als Textdatei laden“ folgt `cd` in lokalen Shells** – in einer lokalen Shell-Registerkarte schlägt das Laden einer ausgewählten Datei mit **Als Textdatei laden** nach einem Verzeichniswechsel nicht mehr fehl, die Datei zu finden. korTTY liest jetzt das Live-Arbeitsverzeichnis der Shell direkt vom Betriebssystem (dem aktuellen Verzeichnis des Shell-Prozesses), anstatt sich nur auf den Eingabeaufforderungstext zu verlassen, der nicht den vollständigen Pfad preisgibt, wenn die Eingabeaufforderung nur den Ordnernamen anzeigt (die macOS-zsh-Standardeinstellung). Unter macOS/Linux wird die Auswahl anhand des Verzeichnisses aufgelöst, in dem sich die Shell tatsächlich befindet. Unter Windows greift es auf das vorherige auf Eingabeaufforderungen basierende Verhalten zurück.
- **KI-Funktionen des Snippet-Editors arbeiten mit Argumentationsmodellen und gesprächigen Antworten** – KI-Aktionen im Snippet-Editor (**Diagramm**, **Überprüfung**, **Verbessern**, **Assistent**, **Sicherheit**, **Alternativen**, **Beschreiben**, **Vollständig**, **Einzeiler**) schlagen nicht mehr fehl – ​​z. B. *„PlantUML-Generierung fehlgeschlagen“* – wenn das Modell seine JSON-Antwort in Prosa oder einen Code-Fence verpackt oder wenn ein lokales Argumentationsmodell (LM Studio, Ollama, llama.cpp, das DeepSeek-R1/QwQ/gpt-oss bereitstellt) einen `<think>…</think>`-Block ausgibt. Der Antwortparser entfernt jetzt durchgesickerte Argumente und extrahiert die echte JSON-Nutzlast robust anstelle eines gierigen Matchs, das bei einer verirrten Klammer fehlschlug.
- **Snippet-Editor-KI-Fehler sind jetzt sichtbar** – wenn eine Snippet-Editor-KI-Aktion fehlschlägt, wird die eigentliche Ursache in das Protokoll geschrieben und die entsprechende Meldung wird in der Statusleiste angezeigt. Zuvor wurde die Ausnahme verworfen, sodass ein falsch konfiguriertes KI-Profil (z. B. ein Cloud-Profil ohne ausgewähltes Modell, das *„Wählen Sie ein Modell…“* meldet) dazu führte, dass jede KI-Funktion stillschweigend fehlschlug und nur eine generische Meldung und nichts im Protokoll angezeigt wurde.

## v2.3.0

### KI-Schwarm

- **Registerkarte „AI Swarm“** (**AI → AI Swarm...**, ++ctrl+alt+s++ / Cmd auf macOS) – sendet eine AI-Agent-Aufgabe gleichzeitig an viele Server; Jeder Server führt seinen eigenen Agenten aus und die Antworten werden in einer einzigen Vergleichstabelle mit einer Zeile pro Server und einer wörtlichen Spalte „Fehler“ für Abweichungen und Fehler zusammengefasst.
- **Animierter Statusstreifen** – eine Kugel pro Agent über der Konversation zeigt auf einen Blick an, ob sie in der Warteschlange steht/läuft/auf Eingaben wartet/angehalten/erledigt/fehlgeschlagen/abgebrochen ist, markiert *ungewöhnlich lange* Ausführungen über einen adaptiven Schwellenwert (`max(60 s, 2 × median of finished agents)`) und durch Klicken auf eine Kugel springt man zur Zeile des Agenten. Der Strip skaliert von 1 bis 50+ Servern.
- **Pro-Agent- und Schwarm-weite Laufsteuerung** – Anhalten, Fortsetzen, Neustarten und Stoppen entweder eines einzelnen Agenten (Rechtsklick auf seine Zeile) oder des gesamten Schwarms (Symbolleiste). Das Pausieren ist kooperativ und stoppt die abgelaufenen Timer. Neustarts ersetzen nur die Antwort dieses Agenten.
- **Erweiterbare Live-Transkripte** – Klicken Sie mit der linken Maustaste auf eine Agentenzeile, um deren Live-Befehls-/Ausgabetranskript während der Ausführung inline anzusehen.
- **Konversation kopieren und exportieren** – Kopieren Sie die gesamte Schwarmkonversation in die Zwischenablage oder exportieren Sie sie als einfachen Text, Markdown oder PDF; Gespeicherte Schwarm-Chats erhalten im AI Manager einen eigenen Bereich **Schwarm-Chats**.
- **Lesbare Ergebniszeilen** – Wenn Sie auf eine Zeile der kombinierten Antworttabelle klicken, wird diese in einem separaten Fenster mit **Zeilendetails** mit der Schriftgröße A−/A+ und der Option zum Kopieren in die Zwischenablage geöffnet.
- **Ziele ohne offene Terminals** – Schwarmläufe (KI und Skript) funktionieren jetzt über Hintergrund-SSH-Sitzungen auf gespeicherten Servern ohne offene Terminals; Es wird kein Terminal-Tab geöffnet. Erfordert einen entsperrten Master-Passwort-Tresor.
- **Skripte ohne KI ausführen** – Führen Sie ein Snippet-Manager-Skript mit Parametern auf allen Schwarmzielen parallel aus (Base64-übertragen, einzelne Bestätigung), mit Live-Ausgabe pro Server und einer Exit-Code-/Ausgabeergebnistabelle pro Server.
- **Schedule Swarm Runs** – ein neuer JobScheduler-Aktionstyp **AI_SWARM** mit **Swarm-Parallelität** (1–16) und **Swarm-schreibgeschützten** Feldern; Über die Schaltfläche **Planen…** auf der Registerkarte „Schwarm“ wird ein Job anhand der aktuellen Ziele und Eingabeaufforderungen vorab ausgefüllt. Die Ergebnisse gehen in das Journal *und* in einen gespeicherten Schwarm-Chat.
- **Sichtbarer Composer- und Tab-Statuspunkt** – die Schwarmeingabe ist ein klar umrahmtes dreizeiliges Feld, und der Tab zeigt einen farbigen Aktivitätspunkt (wird ausgeführt / wartet auf Eingabe / pausiert / beendet – der grüne Punkt bleibt bis zur nächsten Ausführung bestehen).
- **Multi-Server-Workflow-Dialog überarbeitet** – syntaxhervorgehobene Skriptansicht, eine sichtbare Arbeitsanimation mit verstrichener Live-Zeit und Gesamtdauer, ein Feld für zusätzliche Anweisungen mit einem deduplizierten 10-Eintrags-Verlauf und **In Snippets speichern** mit einem passenden vorab ausgefüllten Skriptnamen.

### Aussehen

- **Fünf neue App-Designs** – *Amber CRT* (warmes Bernstein-Phosphor-Retro-Terminal), *Synthwave '84* (80er-Jahre-Outrun-Neon), *Gruvbox Retro* (gemütlich warm erdig), *Nord Arctic* (ruhiges, flaches arktisches Blaugrau) und *Dracula* (sanftes Lila/Rosa) ergänzen die bestehenden Designs unter *Einstellungen → Aussehen*, jedes mit seinem eigenen Vorschaubild. Die bestehenden Designs bleiben unverändert.
- **Dezente Designanimationen umschaltbar** – eine neue Darstellungseinstellung (standardmäßig aktiviert) lässt die leuchtenden Designs einen kleinen Akzentpunkt in der Statusleiste einhauchen; Das Ausschalten dient gleichzeitig als Option zur Bewegungsreduzierung und die Animation stoppt, während das Fenster ausgeblendet ist.
- **Konsistenteres Design-Chrom** – die Farben eines benutzerdefinierten Designs gelten jetzt deterministisch für alle Menüs und Dialoge, und das dynamische Stylesheet des Terminal-Designs überschreibt nicht mehr die Chromfarben des aktiven Designs.
- **App-Design `Normal` in *Einstellungen → Erscheinungsbild* in `Default`** umbenannt. Der gespeicherte Wert bleibt unverändert, sodass vorhandene Konfigurationen ihr ausgewähltes Design behalten.
- Mit den Schaltflächen „Zurück/Weiter“ neben dem Dropdown-Menü „App-Design“ können Sie durch die Designs vor- und zurückblättern (an den Enden umschließen), ohne das Dropdown-Menü zu öffnen.
- **Die Designvorschau wurde unter die Steuerelemente verschoben** in einen Bereich mit fester Größe, sodass beim Wechseln der Designs (oder zurück zu `Default`, wo es keine Vorschau gibt) die Vorschau nicht mehr über dem Dropdown-Menü angezeigt wird.

### Lokale Shell-Verbindungen

- **Öffnen Sie die Shell des lokalen Computers in einer Terminal-Registerkarte (kein Netzwerk)** – ein neues **Lokale Shell**-Protokoll erzeugt ein lokales Pseudo-Terminal (PTY) über pty4j, anstatt eine Verbindung zu einem Remote-Host herzustellen. Unter Windows können Sie **PowerShell** (Standard) oder **cmd.exe** wählen; Unter macOS/Linux wird standardmäßig Ihr `$SHELL` verwendet (wobei auf `/bin/zsh` oder `/bin/bash` zurückgegriffen wird). Ein Freiformfeld **Benutzerdefinierter Befehl** akzeptiert jede ausführbare Datei mit Argumenten (z. B. `pwsh.exe`, `wsl.exe -d Ubuntu`, Git Bash), und ein optionales Startverzeichnis kann festgelegt werden. Die lokale Shell kann sowohl in Quick Connect als auch im Verbindungsmanager ausgewählt werden. Für diese Verbindungen sind Host/Port/Benutzername/Authentifizierung nicht erforderlich und in den Dialogen deaktiviert.
- **Git Bash/Cygwin/WSL-Voreinstellungen** unter Windows – werden jeweils nur angeboten, wenn sie tatsächlich installiert sind (Git Bash/Cygwin über ihre üblichen Installationsorte / `PATH`; WSL nur, wenn `wsl.exe` vorhanden und mindestens eine Distribution installiert ist). Der Befehlsparser erkennt Anführungszeichen, sodass Shell-Pfade mit Leerzeichen (wie `"C:\Program Files\Git\bin\bash.exe"`) korrekt gestartet werden.
- **Gemeinsame Connector-Hooks** – Terminalaufzeichnung/-protokollierung und die AI-Eingabe-/Daten-Hooks wurden auf eine gemeinsam genutzte `ObservableTtyConnector`-Schnittstelle übertragen, sodass sie auch für lokale Shells funktionieren. Nur-SSH-Kanal-Funktionen bleiben nur SSH-Kanal.
- **AI Agent & Planning in lokalen Shells** – die Befehlsausführungs-Engine des Agenten wurde hinter einer `AgentCommandRunner`-Abstraktion (SSH-Ausführungskanal und lokale Prozess-Backends) von SSH entkoppelt. Der **AI Agent** und **AI Planning** werden jetzt in lokalen Shells unter Windows, macOS und Linux ausgeführt: Befehle werden in der Shell der Verbindung ausgeführt (PowerShell über `-EncodedCommand`, `cmd.exe` oder `$SHELL`), die Umgebungsprüfung und die Systemeingabeaufforderung sind plattformorientiert und es gilt der bestehende Genehmigungsablauf. Einschränkungen für lokale Shells: keine `sudo`/Administrator-Erhöhung unter Windows und keine Live-Nachverfolgung des Arbeitsverzeichnisses. Die kopflose KI-Agent-Aktion des JobScheduler bleibt nur SSH.

### Terminal-Benutzerfreundlichkeit

- **Strg + Mausrad-Zoom** – Halten Sie **Strg** (oder **Befehl** unter macOS) gedrückt und scrollen Sie mit dem Mausrad über das Terminal, um jetzt die Schriftgröße zu ändern, anstatt durch den Puffer zu scrollen. Dies ergänzt die vorhandenen Tastenkombinationen Alt+Plus / Alt+Minus / Alt+0.
- **Strg+D schließt eine lokale cmd.exe/PowerShell-Registerkarte** – diese Windows-Shells werden bei EOF nicht beendet, daher hatte Strg+D dort keine Auswirkung. Bei Shells der Bash-Familie (Git Bash/Cygwin/WSL, macOS/Linux) und SSH behält Strg+D seine normale EOF-Bedeutung.

### KI-Chat und Agent

- **Bilder, Diagramme und Mathematik werden in KI-Chats gerendert** – KI-Antworten, die ein SVG-Dokument, ein Base64-Rasterbild (`data:image/png;base64,…` – PNG, JPEG, GIF, BMP), einen ` ```plantuml `-Block, einen ` ```mermaid `-Block oder LaTeX-Mathematik enthalten (` ```latex `/` ```tex `/` ```math `-Blöcke und `$$…$$` in Prosa) werden als Bilder statt als rohes Markup angezeigt, jeweils mit einer Umschalt- und Kopierschaltfläche **Code anzeigen/Bild anzeigen**. Mermaid und MathJax sind gebündelt (kein Netzwerk); PlantUML verwendet die lokale Toolchain (`java` + Graphviz `dot`); Die SVG-Ausgabe wird bereinigt und mit deaktiviertem JavaScript angezeigt.
- **Vollständige Modellbegründung in Agenten-Denkzeilen** – Durch Erweitern einer 💭-Zeile im Agentenaktivitätsbereich wird jetzt die tatsächliche Begründung des Modells angezeigt, wenn der Anbieter sie verfügbar macht (Anthropisches erweitertes Denken gemäß dem Begründungsaufwand des Profils, OpenAI-kompatibles `reasoning_content`, LM Studio-Begründungsausgabe, `<think>`-Blöcke von lokalen CLI-Modellen), andernfalls wird auf die Entscheidungszusammenfassung zurückgegriffen.
- **Das Laufprotokoll zeichnet das AI-Profil auf** – jeder Agentenlauf beginnt mit einer `AI profile: <name> (<model>)`-Aktivitätszeile.
- **Beim Neuladen wird das aktuell aktive Profil verwendet** – die Neuladen-Schaltfläche des Aktivitätsbereichs führt den Befehl erneut mit dem Profil aus, das jetzt aktiv ist, nicht mit dem, mit dem die ursprüngliche Ausführung gestartet wurde.
- **Agent Ask umfasst die Terminalauswahl** – Wenn Sie **AI → Ask AI Agent** über das Kontextmenü mit ausgewähltem Text starten, wird die Auswahl als Kontext gesendet, sodass die Frage zur ausgewählten Ausgabe oder zum ausgewählten Skript beantwortet wird.
- **Konkrete Modelle für Cloud-Profile** – die Modellauswahl ist vorab mit gängigen Modellnamen für bekannte Cloud-Anbieter gefüllt (offline, kein API-Schlüssel erforderlich), die Schaltfläche „Aktualisieren“ führt die Live-Modellliste des Endpunkts zusammen, das Klicken auf ein Modell in der Dropdown-Liste wendet es jetzt zuverlässig an und die unbrauchbare Option „Auto“ wird für Cloud-Endpunkte nicht mehr angeboten (mit einem deutlicheren Fehler, wenn kein Modell ausgewählt ist).

### Anleitung zur Suche nach AI-Dokumenten

- **Fragen Sie die Anleitung in natürlicher Sprache** – der integrierte Anleitung (**Hilfe → Anleitung**, ++f1++) verfügt über ein Seitenfeld **KI-Suche**: Geben Sie eine Frage in Ihrer Sprache ein und Sie erhalten eine Antwort, die ausschließlich aus der gebündelten Dokumentation generiert wird, mit anklickbaren Zitaten, die den Anleitung direkt zur referenzierten Seite weiterleiten.
- **Verwendet Ihr Standard-KI-Profil; Der Abruf erfolgt vollständig offline** – kein Server, keine zusätzlichen API-Schlüssel, keine neuen Abhängigkeiten. Die Recherche erfolgt lokal über den gebündelten Suchindex (mit zweisprachigen Synonymen, deutschem Kompositumsplitting und Umlautfaltung); Off-Topic-Fragen werden lokal beantwortet, ohne dass der KI-Endpunkt überhaupt kontaktiert werden muss.
- **Fundierte Antworten** – das Modell ist auf die abgerufenen Auszüge beschränkt, erfundene Links werden repariert oder entfernt und eine native **Quellenliste** zeigt immer die zitierten Seiten an, unabhängig von der Antwort des Modells.

### Workflow-Skriptgenerator

- **Zwei neue Zielsprachen** – der Agentenlauf → **Workflow**-Skriptgenerator kann jetzt zusätzlich zu Bash, Python, Perl, Ruby, PowerShell und Ansible auch **Windows-CMD** (`.cmd`-Batch) und **AppleScript** (`.applescript`) erzeugen.
- **Anpassbare Skriptschriftgröße** – jeder Editor für generierte Skripte verfügt über **A−** / **A+**-Tasten und unterstützt **Strg + Mausrad** (Befehlstaste unter macOS); Die gewählte Größe wird sitzungsübergreifend gespeichert.
- **Sichtbarer Fortschritt beim Generieren eines Diagramms** – Beim Generieren eines PlantUML-Diagramms aus einem Skript wird jetzt der funktionierende Spinner angezeigt.
- **Deutlichere AI-Backend-Fehler** – Fehler wegen unzureichendem Arbeitsspeicher/Ressourcenlimit vom AI-Server (z. B. LM Studio/MLX „Ressourcenlimit überschritten“, „metal::malloc“) zeigen einen kurzen, umsetzbaren Hinweis anstelle des rohen Stack-Trace; Alle anderen KI-Fehler werden in einer einzigen Zeile zusammengefasst.

### Korrekturen

- **Das Schließen einer lokalen Shell friert korTTY nicht mehr ein** – der PTY-Prozess wird jetzt zerstört, bevor seine Streams geschlossen werden, wodurch ein in einem pty `read()` blockierter Terminal-Reader-Thread freigegeben wird, anstatt das Schließen im JavaFX-Thread zu blockieren.
- **Korrekter Wortlaut für lokale Shells beim Schließen** – die Schließbestätigung sagt nicht mehr „SSH-Verbindung beenden?“ für eine lokale Shell, und die Eingabeaufforderung zum Schließen des Fensters ist jetzt transportneutral („Aktive Sitzungen“).
- **Keine Passwortabfrage für lokale Shells** – Beim Öffnen einer lokalen Shell wird kein irrelevantes Passwortdialogfeld mehr angezeigt (lokale Shells verwenden keine Authentifizierung).
- **„Als Textdatei laden“ funktioniert in lokalen Shells** – wenn Sie mit der rechten Maustaste auf einen ausgewählten Dateinamen in einer lokalen Shell-Registerkarte klicken und **Als Textdatei laden** auswählen, schlägt die Meldung „Keine aktive SSH-Verbindung ist verfügbar“ fehl. Die Datei wird aus dem lokalen Dateisystem gelesen – aufgelöst anhand des Arbeitsverzeichnisses, das in der Shell-Eingabeaufforderung angezeigt wird, sofern verfügbar, andernfalls dem Verzeichnis, in dem die Shell gestartet wurde – und wird im Snippet-Editor mit **Lokale Datei überschreiben** und **Speichern unter...** geöffnet, genau wie die SSH/SFTP-Variante. Die Fehlermeldung „Nicht verbunden“ ist jetzt transportneutral.
- **Lokale Dateiüberschreibungen sind jetzt atomar** – beide „Lokale Datei überschreiben“-Abläufe (lokale Shell **Als Textdatei laden** und der lokale Dateieditor des SFTP-Managers) werden verwendet, um die Zieldatei an Ort und Stelle zu kürzen, sodass ein Fehler während des Schreibvorgangs (Festplatte voll, Prozessabbruch, Stromausfall) dazu führen kann, dass sie ohne Wiederherstellung abgeschnitten wird. Überschreiber schreiben jetzt in eine gleichgeordnete temporäre Datei und verschieben sie an ihren Platz, behalten die POSIX-Berechtigungen der Originaldatei bei und schreiben über symbolische Links zu ihrem eigentlichen Ziel, anstatt den Link selbst zu ersetzen.

## v2.2.3

### Kritischer Fix: Die Monaco-Editoren konnten die gepackte App nicht laden

- **Behoben, dass die auf Monaco basierenden Editoren (Snippet, Datei, AI, Diff) in der gepackten/notariell beglaubigten macOS-App ein leeres Fenster öffneten** – kein Caret, kein Tippen, kein Einfügen. In der gepackten App hat WebView seine Seite von einer `jar:`-URL geladen, und die Content-Security-Policy (`script-src 'self'`) der Seite hat dann die eigene `monaco-host.js`/`.css` des Herausgebers blockiert, da ein `jar:`-Ursprungsdokument seine `jar:`-Geschwister nicht autorisiert. Die Monaco-Ressourcen werden nun in ein temporäres Verzeichnis extrahiert und von einer `file:`-URL geladen, was der CSP zulässt. Ein fehlgeschlagener Editor-Ladevorgang zeigt jetzt auch einen Fehler anstelle eines stillschweigend leeren Bereichs an, und das Editor-Bundle wird zusätzlich durch ein großzügigeres Boot-Budget verkleinert.

## v2.2.2

### Kritischer Fix: Absturz beim Öffnen der Monaco-Editoren

– **Ein schwerer Absturz (kein Bildschirmfehler) beim Öffnen des Snippet-Managers, des Snippet-Editors oder des AI-Skill-Editors für Einstellungen in gepackten Builds wurde behoben**: In der gebündelten Laufzeit fehlte das `jdk.jsobject`-Modul, sodass `netscape.javascript.JSObject` zur Laufzeit nicht verfügbar war und die JVM in JNI `get_method_id` (`SIGSEGV`) abstürzte. `jdk.jsobject` ist jetzt in der Paketlaufzeit gebündelt. Diese Version ersetzt v2.2.0 und v2.2.1, deren Binärdateien von diesem Absturz betroffen sind.

## v2.2.1

### Stabilitätskorrekturen

- **Absturz der Einstellungen/Snippet-Manager behoben**: Das Öffnen der **Globalen Einstellungen** oder des **Snippet-Managers** konnte zum Abbruch der App führen. Die JavaScript→Java-Brücke des eingebetteten Monaco-Editors wird jetzt für die gesamte Lebensdauer des Editors von einer starken Referenz gehalten.
- **Verstärkung des WebView-Lebenszyklus**: Monaco-Editoren werden entsorgt, wenn ihr Dialog geschlossen wird; Späte Timer-/Laderückrufe nach dem Schließen werden ignoriert. Der Einstellungen-Editor *AI Skills* lädt bei der ersten Verwendung langsam.

### Anmeldefenster mit Master-Passwort

- **Vollflächiges animiertes Logo** im Standard-App-Design, mit dem Passwortformular auf einer durchsichtigen Karte überlagert.

## v2.2.0

### Terminal-Engine und Hyperlinks

- Terminal-Engine **SithTermFX 1.2.0** (aus dem Quellcode erstellt).
- **Anklickbare OSC 8-Hyperlinks** – von Programmen wie `ls --hyperlink` oder `eza` ausgegebene Links, beschränkt auf eine sichere URI-Schema-Zulassungsliste.

### Mosh (mosh4j) 2.0.2 Upgrade und Sicherheitshärtung

- mosh4j `2.0.0 → 2.0.2` mit richtungsabhängigem Wiedergabe-/Aktivitätsschutz und Dekompressionsbombenbegrenzungen; Geben Sie JARs frei, die in nativen Builds gebündelt sind.
- Hüpfburg `1.78.1 → 1.84` (behebt CVE-2026-5598 HIGH und CVE-2026-0636 MODERATE); protobuf-java `4.28.2 → 4.35.1`.

### KI-Agenten-Panel und -Aktivität

- **Platzierung des AI Agent Panels**: *Unten* (Standard), *Links andocken* oder *Rechts andocken*, wird bei jedem Neustart gespeichert.
- **Mehrere gleichzeitige Läufe pro Split** (Kapitel 5), Pause/Fortsetzung pro Lauf und Dashboard-/Tab-Statusabzeichen (✋ wartend · ⚡ in Arbeit · ⏸ pausiert · ✓ beendet).

!!! Notiz
    Ältere Releases sind im `app-docs/RELEASE_NOTES.adoc` des Repositorys erfasst und werden hierher vollständig migriert.
