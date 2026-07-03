# Versionshinweise

Das vollständige Versions-Änderungsprotokoll. Die Version, für die diese Anleitung erstellt wurde, ist
in der Fußzeile angezeigt.

## Unveröffentlicht

### Korrekturen

- **„Als Textdatei laden“ funktioniert in lokalen Shells** – ein Rechtsklick auf
einen markierten Dateinamen in einem Local-Shell-Tab mit **Als Textdatei
laden** schlägt nicht mehr mit „Es ist keine aktive SSH-Verbindung verfügbar“
fehl. Die Datei wird vom lokalen Dateisystem gelesen – aufgelöst gegen das im
Shell-Prompt angezeigte Arbeitsverzeichnis, sonst gegen das Verzeichnis, in dem
die Shell gestartet wurde – und öffnet sich im Snippet-Editor mit
**Lokale Datei überschreiben** und **Speichern unter...** – genau wie in der
SSH/SFTP-Variante. Die Nicht-verbunden-Fehlermeldung ist jetzt
transportneutral formuliert.

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
*Synthwave '84* (80er-Outrun-Neon), *Gruvbox Retro* (gemütlich warm-erdig),
*Nord Arctic* (ruhiges, flaches arktisches Blaugrau) und *Dracula* (sanftes
Violett/Pink) ergänzen die bestehenden Designs in *Einstellungen →
Erscheinungsbild*, jeweils mit eigenem Vorschaubild. Die bestehenden Designs
sind unverändert.
- **Schalter für dezente Design-Animationen** – eine neue
Erscheinungsbild-Einstellung (standardmäßig eingeschaltet) lässt bei den
Glow-Designs einen kleinen Akzentpunkt in der Statusleiste „atmen“; das
Ausschalten dient zugleich als Reduce-Motion-Option, und die Animation stoppt,
solange das Fenster verborgen ist.
- **Konsistentere Design-Darstellung** – die Farben eines eigenen Designs werden
jetzt zuverlässig auf Menüs und Dialoge angewendet, und das dynamische
Stylesheet des Terminal-Themes überschreibt nicht mehr die Farben des aktiven
Designs.
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
Dropdown-Liste wendet es jetzt zuverlässig an und die unbrauchbare Option **Auto** ist nicht mehr vorhanden
Wird für Cloud-Endpunkte angeboten (mit einem deutlicheren Fehler, wenn kein Modell ausgewählt ist).

### KI-Suche in der Anleitung

- **Die Anleitung in natürlicher Sprache fragen** – die integrierte Anleitung
(**Hilfe → Anleitung**, ++f1++) erhält ein **KI-Suche**-Seitenpanel: Eine Frage
in der eigenen Sprache eingeben und eine Antwort erhalten, die ausschließlich
aus der mitgelieferten Dokumentation erzeugt wird – mit klickbaren
Quellenangaben, die das Handbuch direkt zur referenzierten Seite springen
lassen.
- **Nutzt das Standard-KI-Profil; die Suche läuft vollständig offline** – kein
Server, keine zusätzlichen API-Schlüssel, keine neuen Abhängigkeiten. Die Suche
läuft lokal über den mitgelieferten Suchindex (mit zweisprachigen Synonymen,
deutscher Komposita-Zerlegung und Umlaut-Normalisierung); themenfremde Fragen
werden lokal beantwortet, ohne den KI-Endpunkt überhaupt zu kontaktieren.
- **Belegte Antworten** – das Modell ist auf die gefundenen Textauszüge
beschränkt, erfundene Links werden repariert oder entfernt, und eine native
**Quellen**-Liste zeigt unabhängig von der Modellantwort immer die zitierten
Seiten.

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

- **AI Agent Panel-Platzierung**: *Unten* (Standard), *Links andocken* oder *Andocken
Richtig*, über alle Neustarts hinweg im Gedächtnis geblieben.
- **Mehrere gleichzeitige Läufe pro Split** (Kapitel 5), Pause/Fortsetzung pro Lauf und
Dashboard-/Tab-Statusabzeichen (✋ wartend · ⚡ in Arbeit · ⏸ pausiert · ✓ fertig).

!!! Notiz
Ältere Versionen werden im `app-docs/RELEASE_NOTES.adoc` des Repositorys erfasst
und wird vollständig hierher migriert.
