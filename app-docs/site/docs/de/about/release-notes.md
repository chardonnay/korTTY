# Versionshinweise

Das vollständige Versions-Änderungsprotokoll. Die Version, für die diese Anleitung erstellt wurde, ist
in der Fußzeile angezeigt.

## Unveröffentlicht

### Snippet-Sicherheitsüberprüfung

- **Erläuterte Sicherheitsfixes** – das Fenster **Sicherheitsfixes überprüfen** wird jetzt angezeigt
das Original und der korrigierte Snippet in einem Side-by-Side-Diff, der hervorhebt
geänderte Zeilen automatisch. Wenn Sie mit der Maus über einen geänderten Block fahren, benennen Sie die Ergebnisse
Adressen (zum Beispiel `S1` oder `S1 + S2`, wenn ein Block zwei Befunde umfasst) und
zeigt den/die Grund(e) an, und jeder Grund wird auch als Karte unter dem Diff aufgeführt
Die Begründung bleibt sichtbar.
- **Spezielles KI-Profil für Sicherheitsüberprüfungen** – Sie können ein separates KI-Profil auswählen
nur für Sicherheitskontrollen und KorTTY merkt sich es dauerhaft. Stellen Sie es ein
Fenster „Sicherheitsüberprüfung“ oder unter **Konfiguration → Globale Einstellungen → AI**; beide
Orte haben dieselbe Einstellung und wenn sie leer bleibt, wird das Standardprofil wiederverwendet.
- **Verbesserungen des Sicherheitsüberprüfungsfensters** – anpassbare (und gespeicherte) Schriftgröße,
eine Schaltfläche zum Kopieren in die Zwischenablage für alle Ergebnisse, farbcodierte Schweregrad-Abzeichen mit
Sortierung nach dem „Schweresten“ zuerst, ein „Alle auswählen“-Schalter und eine Schaltfläche **Prüfung erneut ausführen**
Dadurch wird die Überprüfung mit dem ausgewählten Profil wiederholt.
- **Diff-Zoom gespeichert** – die Schriftgröße in den AI-Diff-/Überprüfungsfenstern ist jetzt
global statt nur für die aktuelle Sitzung gespeichert.

### Terminal

- **Anpassbare Terminal-Hintergrundtransparenz** – eine neue **Ansicht → Zoom → Hintergrund
Der Schieberegler „Transparenz**“ (0–100 %) sorgt dafür, dass der Hintergrund des Terminals durchsichtig ist
auf dem Desktop, während der Text vollständig undurchsichtig und scharf bleibt. Der Wert wird über gespeichert
startet neu. Nur der Terminalbereich wird transparent – ​​Titel, Menü und Status
Stäbe bleiben solide. Da der Durchsichtmodus ein randloses Fenster verwendet, wird er aktiviert
oder off wird erst nach einem Neustart wirksam; in diesem Modus eine schlanke benutzerdefinierte Titelleiste
Bietet Verschieben, Größenänderung, Minimieren, Maximieren und Schließen. Währenddessen den Pegel anpassen
bereits transparent gilt live.
- **Leiseres Schließen eines Tabs** – Beim Schließen eines Terminal-Tabs wird jetzt nur noch nach einer Bestätigung gefragt, wann
Es gibt etwas zu verlieren: Die Registerkarte hat geteilte Bereiche oder ein Befehl wird noch ausgeführt
(erkannt aus der Prozessstruktur der lokalen Shell oder aus der SSH-Shell-Eingabeaufforderung). Ein
Ein einzelnes inaktives Terminal wird sofort geschlossen. Die Pro-Verbindung *Schließen ohne
Mit der Option „Bestätigung*“ wird die Eingabeaufforderung weiterhin vollständig unterdrückt.
- **Klarere Kontextmenübezeichnung** – die Terminal-Rechtsklick-Aktion, die a lädt
Die ausgewählte Remote-Datei im Snippet-Editor heißt jetzt **In Snippet öffnen
Editor** (bisher *Als Textdatei laden*).

## v2.4.0

### Endgültige Auswirkungen

- **Zehn neue integrierte Terminaleffekte** – neben MU/TH/UR 6000, ein Bundle
Das Effektpaket enthält jetzt zehn thematische Effekte aus den Bereichen Cyberpunk, Retro und Grusel
Stile: **Amber CRT '90** (Bernstein-Phosphor-Monitor der 90er Jahre mit Scanlines, Glow,
Flackern und ein rollendes Bildwiederholband), **Commodore Heritage** (C64 blau mit
Ladebalken), **Neon City** (Glitch Tears und RGB-Split-Flimmern), **Digital
Regen** (schwach fallende Matrixglyphen), **Hologramm-HUD** (Interferenzbänder und
HUD-Eckklammern), **Poltergeist** (atmende Vignette, statische Ausbrüche und
gespenstische Blitze), **VHS 1987** (Spurgeräusch, Rollverzerrung und ein PLAY
Overlay), **Synthwave Horizon** (leuchtendes perspektivisches Raster), **Deep Space
Radar** (langsamer Radardurchlauf mit Blips) und **Typewriter Noir** (Sepiapapier).
Blick mit zeichenweiser Ausgabegeschwindigkeit). Jeder Effekt respektiert die
Die Einstellung der Animationsgeschwindigkeit und ihre Beschreibung sind in allen unterstützten Sprachen lokalisiert
Sprachen.
- **Getaktete Ausgabe mit Typewriter Noir** – die Effekttypen **Typewriter Noir**
Terminalausgabe Zeichen für Zeichen für das Gefühl einer mechanischen Schreibmaschine; Schüttgut
Bei der Ausgabe wie dem Drucken einer großen Datei wird die Geschwindigkeit umgangen, sodass sie nie verlangsamt wird
runter.
- **Terminaleffekte pro Bereich** – Terminaleffekte sind jetzt auf jeden Bereich beschränkt
Einzelner geteilter Bereich anstelle der gesamten Registerkarte. Innerhalb einer Registerkarte können Sie eine ausführen
Effekt in einem Bereich, während ein Geschwisterbereich einen anderen oder keinen Effekt zeigt, und
Die Farben und Schriftarten jedes Effekts bleiben auf einen eigenen Bereich beschränkt. Globaler Zoom und
Zurücksetzen gilt weiterhin für die gesamte Registerkarte.
- **Fensterspezifisches Effektmenü und Vererbung** – das Rechtsklick-Menü jedes geteilten Fensters
erhält ein Untermenü „Terminaleffekt“, in dem Sie „Keine“ oder einen beliebigen installierten Effekt auswählen können
nur für diesen Bereich, plus einen Schieberegler für die Animationsgeschwindigkeit; Die Auswahl erfolgt nur zur Laufzeit
und wird nicht in der Verbindung gespeichert. Durch das Teilen eines Bereichs wird der neue Bereich mit gestartet
den gleichen Effekt und die gleiche Animationsgeschwindigkeit wie der Bereich, von dem es getrennt wurde.
- **Animierte Effektvorschauen im Plugin-Manager** – **Plugins → Terminal
Effekte** zeigt jetzt eine animierte Live-Vorschau des ausgewählten Effekts neben dem an
Plugin-Liste, sodass Effekte verglichen werden können, bevor sie in einer Sitzung aktiviert werden.
Plugins ohne Vorschau zeigen stattdessen einen Platzhalter an.

### KI-Chat

- **Chat-Farbprofile** – der AI-Chat und der AI-Swarm-Chat sind jetzt auswählbar
Farbthemen. Elf integrierte Profile enthalten: **Automatisch (Thema)**, das
folgt Ihrem aktiven Terminal-Thema, plus **Original**, **Paper**, **Midnight**,
**Cyberpunk**, **Retrowave**, **Forest**, **Ocean**, **Terminal**, **GPT** und
**Niedlich**. Wählen Sie eines aus der Dropdown-Liste „Farbprofil“ in der Chat-Symbolleiste oder darunter aus
**Einstellungen → Erscheinungsbild → Chat-Farbprofil**; Die Auswahl wird gespeichert und angewendet
Live zu jedem offenen Chat.
- **Volltext-Chat-Suche** (++Strg+F++, Befehl+F unter macOS) – klicken Sie auf **Suchen**
Klicken Sie auf die Schaltfläche in der Chat-Symbolleiste oder drücken Sie die Verknüpfung, um eine Suchleiste über einer KI zu öffnen
oder Schwarmgespräch. Es durchsucht den gesamten Chat, einschließlich Codeblöcken und Shows
eine Live-Match-Zählung, Sprünge zwischen Treffern mit den Pfeiltasten oder Enter und
Umreißt und scrollt jedes Spiel in die Ansicht. Esc schließt die Leiste.
- **Neu gestalteter, vollständig themenbezogener Chat** – der KI-Chat und der Schwarm-Chat wurden neu gestaltet
Ihre Nachrichten befinden sich also in einer nach rechts eingerückten, abgerundeten Blase und jede KI-Antwort ist eine
Karte in voller Breite, mit Codeblöcken, Tabellen, dem Komponisten und Bildlaufleisten
Folgen Sie dem ausgewählten Farbprofil anstelle eines festen Lichtstils.

### Datenschutz und Analyse

- **Anonyme Nutzungsanalyse (opt-in, standardmäßig deaktiviert)** – korTTY kann optional
Teilen Sie anonyme, DSGVO-konforme Nutzungsstatistiken über Aptabase (verarbeitet auf EU-Ebene).
Servern), um Funktionen zu priorisieren und Oberflächenabstürze und häufige Fehler zu beheben.
Es ist standardmäßig eine strikte Opt-in- und Deaktivierungsfunktion. Nur Ereignisnamen, aggregierte Anzahl
und Flags, die App-Version, Betriebssystemname und -version, App-Sprache und eine anonyme
Pro Start werden Sitzungs-IDs gesendet – Hostnamen, Benutzernamen, Verbindungsdaten, Datei
Pfade, Snippet-/Terminal-/Chat-Inhalte, Schlüssel, Passwörter und Fehlermeldungstext
werden nie gesammelt.
- **Einmalige Einwilligungsaufforderung** – Sie werden nach der Weitergabe anonymer Daten gefragt
genau einmal: bei Neuinstallationen als Kontrollkästchen neben der Master-Passwort-Einrichtung,
und bei bestehenden Installationen als einmalige Eingabeaufforderung nach dem Entsperren. Irgendeine Entlassung
gilt als *nein* und Sie werden nicht erneut gefragt. Jede Zustimmungsoberfläche verfügt über ein **Mehr
Info**-Button, der das neue Anleitungkapitel öffnet *Anonyme Daten zur Bewerbung
Optimierung*.
- **Einstellungen → Registerkarte „Datenschutz“** – eine neue Registerkarte **Datenschutz** unter **Einstellungen** ermöglicht Ihnen
Sie können die anonyme Nutzungsstatistik jederzeit ein- oder ausschalten und genau sehen, was passiert
und wird nicht erfasst, zuzüglich des Datums, an dem Ihre Wahl erfasst wurde. Ausschalten
stoppt die Sammlung sofort und verwirft sowohl die ausstehende Warteschlange als auch alle anderen
lokal zwischengespeicherte Ereignisse.
- **Offline-Ereignis-Caching** – während Sie offline sind, werden anonyme Ereignisse zwischengespeichert
lokal unter `~/.kortty` und versendet, sobald eine Verbindung verfügbar ist; sie überleben
Die App wird neu gestartet, nach drei Tagen gelöscht und vollständig verworfen, wenn Sie dies tun
Opt-out.

### Korrekturen

- **Effekte leuchten korrekt, wenn sie für ein Live-Fenster eingeschaltet sind** – MU/TH/UR
Das zeilenweise Leuchten des Effekts funktioniert jetzt, wenn Sie einen Effekt auf einem aktivieren
Bereich „bereits verbunden“, nicht nur, wenn der Effekt vor der Verbindung aktiv war,
und es pulsiert bei schnell scrollender Ausgabe gleichmäßig, anstatt zu flackern.
- **Strg+D in einem geteilten Bereich schließt nur diesen Bereich** – Verlassen der Shell (Strg+D oder
`exit`) in einem Bereich einer Aufteilung schließt nun nur diesen Bereich und verlässt den anderen
Scheiben offen; Die Registerkarte wird erst geschlossen, wenn die Sitzung des letzten verbleibenden Bereichs endet.
- **Terminaleffekte stürzen beim Rendern nicht mehr ab, wenn viele Registerkarten geöffnet sind** – Öffnen
Mehrere Terminal-Tabs mit jeweils aktivem Effekt könnten die GPU-Textur erschöpfen
Pool- und Crash-Rendering. Effekt-Overlays (einschließlich MU/TH/UR 6000) sind jetzt verfügbar
ihre Leinwandtextur im gesamten Fenster, während sich ihre Registerkarte im Hintergrund befindet und
automatisch neu binden, wenn die Registerkarte erneut angezeigt wird.
- **AI Planning stellt abgeschnittenes oder fehlerhaftes JSON wieder her** – das **AI Planning**
Die Registerkarte schlägt nicht mehr vollständig mit der Fehlermeldung * „AI-Antwort enthielt nicht das Erforderliche“ fehl
JSON-Objekt"*, wenn ein Modell einen unvollständigen oder nicht geschlossenen Plan zurückgibt, häufig bei
Sehr kleine Modelle, die vor Abschluss des Schemas anhalten. Die Planung wird jetzt erneut durchgeführt
einmal mit einer Reparaturaufforderung und meist beim zweiten Versuch erfolgreich; wenn es noch
schlägt fehl, der Fehler erklärt, dass das Modell zweimal ungültiges JSON zurückgegeben hat und
schlägt ein größeres oder leistungsfähigeres Planungsmodell vor.
- **Guide-Fenster stürzt nicht mehr im Hintergrund ab** – der In-App-Guide bleibt erhalten
(**Hilfe → Anleitung**, F1) beim Öffnen zu einer anderen App kann korTTY zum Absturz bringen
nach einer Weile automatisch, da der eingebettete Browser im Leerlauf im Hintergrund lief.
korTTY entlädt nun die Leitseite, nachdem das Fenster minimiert wurde oder
20 Sekunden lang unfokussiert und stellt danach die gleiche Seite und Bildlaufposition wieder her
Sie kehren zurück und das Einführungsvideo der Anleitungs wird einmal abgespielt, anstatt in einer Schleife
endlos.

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
in die Zwischenablage kopieren oder als einfachen Text, Markdown oder PDF exportieren; Gespeicherte Schwarm-Chats erhalten
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
Mit der Schaltfläche **Planen…** wird ein Job anhand der aktuellen Ziele und Eingabeaufforderungen vorab ausgefüllt.
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
Arbeit für lokale Shells. Nur-SSH-Kanal-Funktionen bleiben nur SSH-Kanal.
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
Dropdown-Liste wendet es jetzt zuverlässig an und die unbrauchbare Option **Auto** ist nicht mehr verfügbar
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
