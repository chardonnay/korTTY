# Versionshinweise

Das vollständige Versions-Änderungsprotokoll. Die Version, für die diese Anleitung erstellt wurde, ist
in der Fußzeile angezeigt.

## Unveröffentlicht

### Aussehen

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
Abkürzungen.
- **Strg+D schließt eine lokale cmd.exe/PowerShell-Registerkarte** – diese Windows-Shells tun dies nicht
Exit auf EOF, daher hatte Strg+D dort keine Auswirkung. Für Shells der Bash-Familie
(Git Bash/Cygwin/WSL, macOS/Linux) und SSH behält Strg+D seine normale EOF-Bedeutung.

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
