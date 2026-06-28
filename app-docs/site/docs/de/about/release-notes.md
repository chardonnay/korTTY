# Versionshinweise

Das vollständige Versions-Änderungsprotokoll. Die Version, für die diese Anleitung erstellt wurde, ist
in der Fußzeile angezeigt.

## Unveröffentlicht

### Lokale-Shell-Verbindungen

- **Die Shell des lokalen Rechners in einer Terminal-Registerkarte öffnen (ohne
  Netzwerk)** – ein neues **Lokale Shell**-Protokoll startet ein lokales
  Pseudo-Terminal (PTY) über pty4j, statt sich mit einem Remote-Host zu
  verbinden. Unter Windows können Sie **PowerShell** (Standard) oder **cmd.exe**
  wählen; unter macOS/Linux wird standardmäßig Ihre `$SHELL` verwendet (Fallback
  auf `/bin/zsh` oder `/bin/bash`). Ein freies Feld **Benutzerdefinierter Befehl**
  akzeptiert jede ausführbare Datei mit Argumenten (z. B. `pwsh.exe`,
  `wsl.exe -d Ubuntu`, Git Bash), und ein optionales Startverzeichnis kann gesetzt
  werden. Die lokale Shell ist sowohl in Quick Connect als auch im
  Verbindungsmanager auswählbar; für diese Verbindungen werden
  Host/Port/Benutzername/Authentifizierung nicht benötigt und sind in den
  Dialogen deaktiviert.
- **Git Bash- / Cygwin- / WSL-Voreinstellungen** unter Windows – jeweils nur
  angeboten, wenn tatsächlich installiert (Git Bash/Cygwin über ihre üblichen
  Installationspfade / `PATH`; WSL nur, wenn `wsl.exe` vorhanden und mindestens
  eine Distribution installiert ist). Der Befehls-Parser beachtet
  Anführungszeichen, sodass Shell-Pfade mit Leerzeichen (wie
  `"C:\Program Files\Git\bin\bash.exe"`) korrekt gestartet werden.
- **Gemeinsame Connector-Hooks** – Terminalaufzeichnung/-protokollierung und die
  KI-Eingabe-/Daten-Hooks wurden auf eine gemeinsame
  `ObservableTtyConnector`-Schnittstelle gehoben, sodass sie auch für lokale
  Shells funktionieren. Funktionen, die nur über den SSH-Kanal verfügbar sind,
  bleiben SSH-exklusiv.
- **KI-Agent & -Planung in lokalen Shells** – die Befehlsausführungs-Engine des
  Agenten wurde hinter einer `AgentCommandRunner`-Abstraktion von SSH entkoppelt
  (SSH-Exec-Kanal- und lokale-Prozess-Backends). Der **KI-Agent** und die
  **KI-Planung** laufen nun in lokalen Shells unter Windows, macOS und Linux:
  Befehle werden in der Shell der Verbindung ausgeführt (PowerShell über
  `-EncodedCommand`, `cmd.exe` oder `$SHELL`), die Umgebungs-Probe und der
  System-Prompt sind plattformbewusst, und der bestehende Genehmigungsablauf gilt.
  Einschränkungen bei lokalen Shells: keine `sudo`-/Administrator-Erhöhung unter
  Windows und keine Live-Arbeitsverzeichnis-Verfolgung. Die headless
  KI-Agent-Aktion des JobSchedulers bleibt SSH-exklusiv.

### Terminal-Bedienbarkeit

- **Strg + Mausrad-Zoom** – wenn Sie **Strg** (oder **Cmd** auf macOS) gedrückt
  halten und mit dem Mausrad über dem Terminal scrollen, ändert sich nun die
  Schriftgröße, statt den Puffer zu scrollen. Dies ergänzt die bestehenden
  Tastenkürzel Alt+Plus / Alt+Minus / Alt+0.
- **Strg+D schließt eine lokale cmd.exe-/PowerShell-Registerkarte** – diese
  Windows-Shells beenden sich nicht bei EOF, sodass Strg+D dort keine Wirkung
  hatte. Bei Bash-Familien-Shells (Git Bash/Cygwin/WSL, macOS/Linux) und SSH
  behält Strg+D seine normale EOF-Bedeutung.

### Workflow-Skript-Generator

- **Zwei neue Zielsprachen** – der Skript-Generator der Agentenausführung →
  **Workflow** kann nun **Windows-CMD** (`.cmd`-Batch) und **AppleScript**
  (`.applescript`) zusätzlich zu Bash, Python, Perl, Ruby, PowerShell und Ansible
  erzeugen.
- **Anpassbare Skript-Schriftgröße** – jeder Editor für generierte Skripte verfügt
  über **A−**- / **A+**-Schaltflächen und unterstützt **Strg + Mausrad** (Cmd auf
  macOS); die gewählte Größe wird über Sitzungen hinweg gespeichert.
- **Sichtbarer Fortschritt während der Diagrammgenerierung** – beim Generieren
  eines PlantUML-Diagramms aus einem Skript wird nun der Arbeits-Spinner
  angezeigt.
- **Klarere KI-Backend-Fehler** – Speicher-/Ressourcenlimit-Fehler des KI-Servers
  (z. B. LM Studio/MLX „Resource limit exceeded“, „metal::malloc“) zeigen einen
  kurzen, umsetzbaren Hinweis statt des rohen Stacktraces; alle anderen KI-Fehler
  werden auf eine einzige Zeile reduziert.

### Fixes

- **Das Schließen einer lokalen Shell friert korTTY nicht mehr ein** – der
  PTY-Prozess wird nun zerstört, bevor seine Streams geschlossen werden, sodass
  ein in einem pty-`read()` blockierter Terminal-Lesethread freigegeben wird,
  statt das Schließen auf dem JavaFX-Thread zu blockieren.
- **Korrekte Formulierung für lokale Shells beim Schließen** – die
  Schließbestätigung sagt für eine lokale Shell nicht mehr „SSH-Verbindung
  beenden?“, und die Fenster-Schließabfrage ist nun transportneutral („Aktive
  Sitzungen“).
- **Keine Passwortabfrage für lokale Shells** – das Öffnen einer lokalen Shell
  zeigt keinen irrelevanten Passwortdialog mehr (lokale Shells verwenden keine
  Authentifizierung).

## v2.2.3

### Kritischer Fix: Monaco-Editoren luden im gepackten App nicht

- **Behoben: die Monaco-Editoren (Snippet, Datei, KI, Diff) öffneten sich im
  gepackten/notarisierten macOS-App als leere Fläche** – kein Cursor, keine
  Eingabe, kein Einfügen. Im gepackten App lud die WebView ihre Seite aus einer
  `jar:`-URL, und die Content-Security-Policy der Seite (`script-src 'self'`)
  blockierte daraufhin die zugehörigen `monaco-host.js`/`.css`, weil ein Dokument
  mit `jar:`-Origin seine `jar:`-Geschwister nicht autorisiert. Die
  Monaco-Ressourcen werden jetzt in ein temporäres Verzeichnis entpackt und über
  eine `file:`-URL geladen, die die CSP erlaubt. Ein fehlgeschlagener
  Editor-Ladevorgang meldet jetzt außerdem einen Fehler statt einer stummen
  leeren Fläche, und das Editor-Bundle ist zusätzlich minifiziert mit
  großzügigerem Boot-Budget.

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
