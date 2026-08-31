---
title: Terminalsitzungen
---

# Terminalsitzungen

KorTTY bietet eine Terminalschnittstelle mit Registerkarten, die mehrere gleichzeitige SSH-Verbindungen, geteilte Bildschirmlayouts und interaktive Terminalverwaltungsfunktionen unterstützt. In dieser Anleitung werden Registerkartenvorgänge, die Unterstützung mehrerer Fenster, die Anpassung des Terminals und erweiterte Sitzungsfunktionen behandelt.

## Sitzungslebenszyklus

Das folgende Diagramm zeigt die Registerkarte „Terminal“ und den Sitzungslebenszyklus, einschließlich Split-Screen- und Broadcast-Modi.

![Terminal session lifecycle](../assets/diagrams/session-lifecycle.svg)

## Arbeiten mit Tabs

Verwalten Sie mehrere SSH-Sitzungen mit diesen Registerkartenoperationen:

| Aktion | Verknüpfung |
|--------|----------|
| **New Tab** | ++ctrl+t++ (Cmd+T on macOS) — opens Schnellverbindung to start a new session |
| **Close Tab** | ++ctrl+w++ (Befehl+W unter macOS) – schließt die aktive Registerkarte. Sie werden nur dann zur Bestätigung aufgefordert, wenn etwas verloren geht: Die Registerkarte hat geteilte Bereiche oder ein Befehl wird noch ausgeführt (eine lokale Shell mit einem laufenden untergeordneten Prozess oder eine SSH-Sitzung, die nicht zur Eingabeaufforderung gelangt). Ein inaktives einzelnes Terminal wird sofort geschlossen. Die verbindungsspezifische Einstellung *Ohne Bestätigung schließen* unterdrückt die Eingabeaufforderung vollständig. |
| **Nächster Tab** | ++ctrl+Tab++ |
| **Previous Tab** | ++ctrl+shift+Tab++ |
| **Erneut verbinden** | Klicken Sie mit der rechten Maustaste auf eine Registerkarte, den Terminalbereich oder einen Servereintrag im Dashboard. Ist die Verbindung aktiv, wird sie sofort geschlossen und wieder aufgebaut; Wenn die Verbindung getrennt wird, wird sie wiederhergestellt. Das Terminalfenster bleibt geöffnet. |
| **Registerkartengruppen** | Klicken Sie mit der rechten Maustaste auf eine Registerkarte, um sie zur besseren Organisation einer benannten Gruppe zuzuweisen. |

## Sicher verbinden

Interactive SSH terminals share host-key trust with SFTP and the SSH bootstrap used by Mosh. The first connection to a normalized host and port shows the key algorithm and OpenSSH SHA-256 fingerprint with **No** selected by default. After you verify and accept it, exact matches connect silently; a changed key is hard-blocked with no automatic retry. See [SSH host-key verification](connections.md#ssh-hostschlusseluberprufung).

Beim Öffnen einer Verbindung mit demselben Server oder einer neu ausgewählten Verbindung in einem Split wird ein Fortschrittsdialog angezeigt, während der SSH-Handshake auf einem Worker ausgeführt wird. Die Schnittstelle reagiert weiterhin sowohl auf die Host-Tasten-Bestätigung als auch auf Eingabeaufforderungen zur interaktiven Tastaturauthentifizierung.

Some failures are refused outright rather than retried, because repeating the attempt cannot change the outcome — a changed host key, a Mosh connection configured with a jump server, or a missing Mosh runtime. The terminal clears and shows the reason immediately instead of working through the retry count. See [Jump server](jump-server.md) for the Mosh restriction.

Der angeheftete SithTermFX-Build von KorTTY enthält auch eine überprüfte Korrektur der Begrenzung der unteren Zeile: Beim Bewegen über einen Hyperlink oder die letzte sichtbare Terminalzeile wird `TerminalTextBuffer` nicht mehr nach der nicht vorhandenen Zeile bei `line == height` gefragt.

## Verbindungsverlust und automatische Wiederherstellung der Verbindung

When an **established** SSH connection is lost — network drop, VPN cut, server gone — the tab does **not** close. It switches to a red disconnected state instead: the tab title gets a `(DISCONNECT)` suffix, the tab turns dark red, a red status bar shows the time the connection was lost, and the terminal cursor stops blinking so a dead session no longer looks alive. Only a normal remote logout (typing `exit`, or ++ctrl+d++ at the prompt) closes the tab.

KorTTY bemerkt einen stillen Transporttod innerhalb von etwa zehn Sekunden: Alle paar Sekunden sendet es eine SSH-Liveness-Prüfung (eine globale Anfrage, die der Server beantworten muss, dieselbe Technik wie `ServerAliveInterval` von OpenSSH) und behandelt zwei aufeinanderfolgende unbeantwortete Prüfungen als verlorene Verbindung. Das Probe aktiviert sich erst, nachdem der Server einmal geantwortet hat, sodass Server, die nie auf solche Anfragen antworten, ihre Sitzungen unberührt lassen. Dies ist unabhängig vom Keep-Alive-Heartbeat [SSH ](#ssh-keep-alive), der inaktive Verbindungen offen hält, eine unterbrochene Verbindung jedoch nicht erkennt.

Um die Sitzung wieder in derselben Registerkarte aufzunehmen, doppelklicken Sie auf die rote Statusleiste oder die rote Registerkarte oder verwenden Sie **Erneut verbinden** im Registerkarten-, Terminal- oder Dashboard-Kontextmenü. In einer geteilten Registerkarte werden die Bereiche, deren Verbindung unterbrochen wurde, einzeln geschlossen. Im letzten verbleibenden Bereich bleibt die Registerkarte geöffnet und es wird das Angebot zur erneuten Verbindung angezeigt.

Wenn **Verlorene Verbindungen automatisch wiederherstellen** aktiviert ist (**Einstellungen → Terminal**, standardmäßig aktiviert), stellt die Registerkarte die Verbindung von selbst wieder her: Versuche beginnen nach 3 Sekunden und werden nach 5, 10, 20 und 30 Sekunden unterbrochen, bis zu einem Versuch pro Minute, und die rote Statusleiste zählt bis zum nächsten Versuch herunter. Eine erfolgreiche erneute Verbindung, eine manuelle erneute Verbindung oder das Schließen der Registerkarte beendet die automatischen Versuche. Permanente Fehler – Authentifizierung, Host-Schlüssel-Überprüfung, Konfigurationsverweigerungen – stoppen sie ebenfalls, sodass niemals ein falsches Passwort gegen den Server gehämmert wird. Während a [session journal](session-journal.md) is running, its red decision bar takes precedence and no automatic attempt starts — the journal asks whether to reconnect and continue or to end with its closing summary. See [Settings → Terminal](../reference/settings/terminal.md) for the setting.

## Multi-Window-Unterstützung

Öffnen Sie zusätzliche Fenster, um Verbindungen nach Projekt oder Umgebung zu organisieren:

- **New Window**: ++ctrl+shift+n++ (Cmd+Shift+N on macOS) opens a new KorTTY window. Each window can have its own set of tabs and connections.
- **Registerkarten zwischen Fenstern verschieben**: Ziehen Sie eine Registerkarte aus der Registerkartenleiste und legen Sie sie auf der Registerkartenleiste eines anderen KorTTY-Fensters ab, um diese Registerkarte (und ihre Sitzung, einschließlich aller geteilten Terminals) in das andere Fenster zu verschieben.
- **Tabs neu anordnen**: Ziehen Sie einen Tab innerhalb desselben Fensters, um seine Reihenfolge zu ändern; die Registerkarte „+“ bleibt am Ende.

## Schriftgröße und Zoom

Passen Sie die Schriftgröße des aktiven Terminals im Handumdrehen an, ohne die Verbindung erneut herzustellen:

| Verknüpfung | Aktion |
|----------|--------|
| ++alt+plus++ | Zoom in (increase font size) |
| ++alt+minus++ | Verkleinern (Schriftgröße verringern) |
| ++alt+0++ | Reset zoom to saved/default font |
| ++ctrl++ + Mausrad | Vergrößern/verkleinern Sie das Terminal (Befehlstaste + Rad unter macOS) |

Wenn Sie ++ctrl++ (oder ++cmd++ unter macOS) gedrückt halten und mit dem Mausrad über das Terminal scrollen, ändert sich die Schriftgröße – Rad nach oben vergrößert, Rad nach unten verkleinert – anstatt durch den Puffer zu scrollen. Dies ergänzt die Tastenkombinationen ++alt+plus++ / ++alt+minus++ / ++alt+0++.

**Zoom zurücksetzen** stellt die Schriftgröße und -familie wieder her, die die Verbindung hatte, als Sie die Registerkarte geöffnet haben (oder die gespeicherten Einstellungen der Verbindung oder den globalen Standard). Das gleiche Zurücksetzen ist über das Terminal-Kontextmenü verfügbar: Rechtsklick → **Schriftgröße** → **Zurücksetzen**. Die Zoomstufe gilt nur für das aktuell fokussierte Terminal.

## Hintergrundtransparenz

**Ansicht → Zoom → Hintergrundtransparenz** ist ein Schieberegler (0–100 %), der den Terminalhintergrund auf dem Desktop durchscheinen lässt, während der Text völlig undurchsichtig und scharf bleibt. Bei 0 % ist der Hintergrund einfarbig; Höhere Werte lassen mehr vom Desktop durchscheinen. Der Wert wird über Neustarts hinweg gespeichert und wiederhergestellt.

Only the terminal area becomes transparent — the title bar, menu bar, status bar and any tab without a terminal stay solid, so the window never turns into a see-through hole.

Horizontal, vertical and nested split terminals inherit the active transparency level, including panes added after transparency was enabled. Entering fullscreen with ++f12++ or terminal-only fullscreen with ++ctrl+shift+f++ temporarily renders the terminal area opaque without changing the saved value; leaving fullscreen restores that value to every pane.

Da ein durchsichtiges Fenster einen anderen Fensterstil verwendet, den das Betriebssystem beim Öffnen des Fensters korrigiert, wird **das Ein- oder Ausschalten der Transparenz (Überschreiten von 0 %) erst nach einem Neustart vollständig wirksam**; Die Statusleiste zeigt einen Hinweis an, wenn Sie diesen Schwellenwert überschreiten. Das Anpassen des Pegels bereits im transparenten Modus wird live angewendet. Im transparenten Modus verwendet das Fenster eine schlanke benutzerdefinierte Titelleiste (Ziehen zum Verschieben, Schaltflächen zum Minimieren/Maximieren/Schließen, Doppelklick auf den Streifen zum Maximieren, Ziehen an den Rändern zum Ändern der Größe).

Der Schieberegler befindet sich nur in der Menüleiste im Fenster (die native macOS-Menüleiste kann keinen Schieberegler hosten).

## Lokale Shell-Registerkarten

Besides SSH and Mosh, a terminal tab can host a **Local Shell** — the local machine's own shell, opened via a pseudo-terminal (see [Local Shell](connections.md#lokale-shell)). A few terminal behaviors are local-shell aware:

- **++ctrl+d++ closes the tab for local cmd.exe/PowerShell sessions.** Those Windows shells do not exit on EOF, so ++ctrl+d++ would otherwise have no effect. For bash-family shells (Git Bash/Cygwin/WSL, macOS/Linux) and SSH, ++ctrl+d++ keeps its normal EOF meaning — the shell exits and the local tab then auto-closes.
- **Bestätigung schließen** verwendet den Wortlaut „Local-Shell“ anstelle von „SSH-Verbindung beenden?“ und die Eingabeaufforderung zum Schließen des Fensters ist transportneutral („Aktive Sitzungen“), da ein Fenster SSH-, Mosh- und Local-Shell-Registerkarten mischen kann.
- **Das aktuelle Verzeichnis folgt der interaktiven Shell.** Unter macOS und Linux aktualisiert korTTY es vom lokalen Shell-Prozess; Native PowerShell- und cmd-Eingabeaufforderungen stellen absolute Windows-Pfade bereit. Nach `cd`, `pushd`, `popd` oder `Set-Location` löst **Im Snippet-Editor öffnen** einen ausgewählten Dateinamen in das aktuelle Verzeichnis und nicht in das Startverzeichnis der Registerkarte auf. Wenn das Verzeichnis nicht sicher bestimmt oder zugeordnet werden kann, stoppt korTTY mit einem Fehler, anstatt eine gleichnamige Datei aus dem falschen Verzeichnis zu öffnen.
- **After an identity switch, Open in Snippet Editor is greyed out.** When the session no longer runs as the identity the tab was opened with — after `su`, an inner `ssh`, or a shell-opening `sudo` — the context-menu entry is disabled, in SSH tabs as well as local-shell tabs: the tab's tracked directories and file access still belong to the original login and would resolve the wrong path. The entry re-enables on its own once the prompt shows the original user again (typically after `exit`). A local-shell tab whose configured shell command is itself a remote client such as `ssh` or `mosh` keeps the entry disabled for the whole tab. If the load is triggered anyway, korTTY stops with an error instead of resolving the wrong path.
- **Zwischenablagetext bleibt in Agentenverknüpfungen erhalten.** Eingegebener und eingefügter Text durchläuft denselben Terminal-Eingabefilter, einschließlich Einfügen in Klammern und geteilter UTF-8-Eingabe, sodass ein eingefügter Dateiname Teil der `agent ...`-Anfrage bleibt und Enter ihn genau einmal versendet.

## Sitzungsjournal

Jede Terminal-Registerkarte kann eine behalten [Sitzungsjournal](session-journal.md): Serverausgaben und eingegebene Befehle werden in ein Capture-Log aufgenommen, eine KI komprimiert sie zu einer lesbaren Zeitleiste und Screenshots und Notizen können über die Journalleiste oder das Rechtsklickmenü des Terminals hinzugefügt werden. Journale starten automatisch für Verbindungen, die sie aktivieren, oder rückwirkend für eine laufende Sitzung über **Tools > Sitzungsjournal starten/stoppen** – der vorhandene Scrollback wird importiert. Siehe [Sitzungsjournal](session-journal.md).

## Split-Screen mit Übertragung

Teilen Sie die Terminalansicht, um mehrere Verbindungen nebeneinander anzuzeigen, und senden Sie optional Eingaben an alle Bereiche gleichzeitig.

### Vorgänge aufteilen

- **Geteilter Bereich**: Erstellen Sie über das Kontextmenü oder Tastaturkürzel horizontale oder vertikale Teilungen innerhalb einer Registerkarte.
- **Unabhängige Sitzungen**: In jedem Bereich kann eine andere SSH-Verbindung angezeigt werden.
- **Anpassbare Fensterbereiche**: Ziehen Sie die Trennlinien, um die Fenstergrößen anzupassen.
- **Zugriffsgrund einmal pro Registerkarte abgefragt**: Wenn ein Server nach einem Grund für die Verbindung fragt, wie es ein Jump-Host im CyberArk-Stil tut, fragt ein Split nicht erneut. korTTY sendet den Grund, der beim Öffnen des Tabs angegeben wurde, da ein Server, der danach fragt, eine Sitzung schließt, die mit nichts antwortet. Bei einer Aufteilung auf einen anderen Server oder bei einem Server, der etwas anderes fragt, wird ebenfalls einmal gefragt, und ein neuer Tab beginnt immer mit der Frage. Lehnt der Server die Begründung ab, etwa weil eine Ticketnummer inzwischen abgelaufen ist, verwirft korTTY diese und fragt beim nächsten Versuch erneut nach.
- **Move Panes**: Hold ++shift+alt++ (Windows/Linux) or ++shift+option++ (macOS) and drag a pane onto another to reorder. Without the modifiers, mouse drag is used for text selection in the terminal.

### Broadcast-Modus

Wenn der **Broadcast-Modus** aktiviert ist, werden Tastatureingaben gleichzeitig an alle sichtbaren Bereiche gesendet. Dies ist nützlich, um dieselben Befehle auf mehreren Servern auszuführen.

## Terminale Auswirkungen

Terminaleffekte können den sichtbaren Terminalstil und die Ausgabeanimation ändern. Effekte sind Java-Plugins, die über **Plugins > Terminaleffekte** verwaltet werden.

### Benutzerkontrollen

- **Aktuelles Terminal**: Verwenden Sie **Ansicht > Terminaleffekt** oder das Terminal-Kontextmenü, um einen Effekt für das aktive Terminal auszuwählen.
- **Schnellverbindung**: Wählen Sie den Effekt und die Geschwindigkeit, bevor Sie eine temporäre oder gespeicherte Verbindung öffnen.
- **Verbindungsmanager**: Speichern Sie den Effekt und die Geschwindigkeit einer gespeicherten Verbindung, damit neue Tabs sie automatisch verwenden.
- **Geschwindigkeit**: Verwenden Sie den Schieberegler für `1x` bis `10x`; Wenn das immer noch zu langsam ist, geben Sie im numerischen Geschwindigkeitsfeld einen benutzerdefinierten Wert bis zu `99x` ein.

### Plugin-Verwaltung

- Öffnen Sie **Plugins > Terminaleffekte**, um Plugins zu verwalten.
- Die Tabelle listet geladene Plugins mit aktivem Status, Namen und Beschreibung auf.
- **Deaktivieren** Sie ein Plugin, damit es installiert bleibt, aber nicht für die Aktivierung verfügbar ist.
- **Externe `.jar`-Plugins importieren**. KorTTY kopiert sie in `~/.kortty/plugins`.
- **Exportieren** Plugins, die über eine Quell-JAR verfügen. Der gebündelte MOTHER-Effekt ist exportierbar.

!!! warning
    Importierte Terminaleffekt-Plugins sind vertrauenswürdiger Java-Code und werden nicht in einer Sandbox gespeichert. Importieren Sie Plugins nur aus Quellen, denen Sie vertrauen.

Eine ausführliche Dokumentation zur Plugin-Entwicklung finden Sie unter [Terminaleffekt-Plugins](terminal-effect-plugins.md).

## SSH-Keep-Alive

Verhindern Sie, dass Verbindungen aufgrund von Inaktivität unterbrochen werden, indem Sie SSH-Keepalive-Nachrichten konfigurieren:

1. Aktivieren Sie **SSH Keep-Alive** auf der Registerkarte **Terminal** der Verbindung oder unter **Einstellungen > Terminal**.
2. Stellen Sie das Intervall ein (5 bis 600 Sekunden, Standard: 60).
3. KorTTY sendet `SSH_MSG_IGNORE`-Heartbeat-Nachrichten im konfigurierten Intervall und aktiviert TCP-Socket-Keepalive, während die Option aktiv ist.

!!! note
    Wenn ein Server, eine Firewall, ein VPN oder ein NAT-Gateway inaktive Sitzungen früher als im konfigurierten Intervall schließt, kann die Verbindung trotzdem beendet werden. Überprüfen Sie in diesem Fall die serverseitige SSH-Konfiguration und die Netzwerk-Leerlauf-Timeout-Einstellungen sowie das KorTTY-Protokoll.

## Terminalprotokollierung

Schreibt die Terminalausgabe einer Verbindung zur Prüfung und zum Debuggen in eine Datei. Dies ist unabhängig vom [Session Journal](session-journal.md): Es handelt sich um ein einfaches Transkript ohne Zusammenfassungen, Markierungen oder Screenshots, und beide können gleichzeitig ausgeführt werden.

Konfigurieren Sie es an einer beliebigen Stelle:

- **Verbindungsmanager > Verbindung bearbeiten > Protokollierung** für eine gespeicherte Verbindung.
- **Schnellverbindung > Terminalprotokoll** für eine einmalige Sitzung oder zum Ändern der Einstellung für die Verbindung, die Sie gerade öffnen möchten.

1. Protokollierung aktivieren.
2. Wählen Sie einen **Protokollordner**. Bleibt es leer, verwendet KorTTY `~/.kortty/terminal-logs`. Sie wählen den Ordner aus; Die Dateinamen sind KorTTYs.
3. Wählen Sie ein Protokollformat:
   - **Einfacher Text** – Eine zeitgestempelte Zeile pro Ausgabezeile.
   - **XML** – Strukturiertes XML mit Zeitstempeln.
   - **JSON** – Strukturiertes JSON mit Zeitstempeln.
4. Optionally adjust the **maximum file size** (default: 10 MB) and the **retention period** (default: 30 days), and turn off **Start a new file every day** or **Compress closed files (gzip)** — both are on by default. Schnellverbindung's Terminal log section covers enable, folder, format and compression; size limit, retention and daily rotation keep their configured or default values.

### Dateinamen

Every file is named `<date>-<time>-<server>_<number>`, for example `2026-08-04-14-30-12-web01_1.log.gz`. The date leads so a folder listing sorts chronologically, and the trailing number distinguishes connections that are open at the same time — two tabs on the same server get `_1` and `_2` and never write into one another's file.

### Rotation, Komprimierung und Retention

By default a new file is started **every day**, and always again whenever the maximum size is reached (those parts are numbered `.p2`, `.p3`, …); daily rotation can be turned off to roll only by size. Nothing is ever overwritten or deleted by rotation.

Geschlossene Dateien werden standardmäßig komprimiert. Die aktuell geschriebene Datei bleibt immer unkomprimiert, sodass sie bei einem Absturz nicht abgeschnitten werden kann. Deaktivieren Sie **Geschlossene Dateien komprimieren (gzip)**, um fertige Dateien stattdessen als einfachen Text beizubehalten. Eine Verbindung, die keine Ausgabe erzeugt, erstellt überhaupt keine Datei.

Files older than the retention period are deleted automatically when a connection starts and after each daily rollover. Set the retention to `0` to keep everything. Only KorTTY's own log files are ever removed — anything else in the folder is left alone, so it is safe to point the setting at a folder you also use for other things.

### Was vor dem Schreiben entfernt wird

Erfasste Zeilen durchlaufen die gleiche Schwärzung wie das [Session Journal](session-journal.md) im Erfassungsthread, bevor irgendetwas gepuffert oder geschrieben wird: das eigene Passwort der Verbindung und alle Ersetzungsregeln, die in der Richtlinie Ihrer Organisation definiert sind. Das Geheimnis gelangt nie in die Datei, sodass hinterher nichts bereinigt werden muss.

Protokolldateien und ein Protokollordner, den KorTTY selbst erstellt hat, sind auf Besitzerrechte eingestellt, sofern das Dateisystem dies unterstützt. Ein Ordner, den Sie selbst ausgewählt haben, behält die von Ihnen erteilten Berechtigungen.

!!! warning "Redaction deckt nur das ab, was KorTTY weiß"
    A password KorTTY stores for the connection is redacted. A secret you type into a command yourself, or one a program prints, is not — KorTTY has no way to recognise it. Treat the log folder as sensitive, and use policy replacement rules for patterns that recur.

## Terminalaufzeichnung

Die Terminalaufzeichnung ist als ressourcenschonende Wiedergabefunktion konzipiert. KorTTY zeichnet Terminal-Bildschirmstatusänderungen und Zeitereignisse in einer JDK/GZIP-Streaming-komprimierten `.korttyrec.jsonl.gz`-Datei pro Terminal-Tab-Sitzung auf. Ältere `.korttyrec.jsonl`-Wiedergabedateien bleiben lesbar.

### Aufzeichnungen konfigurieren

1. Um die Aufzeichnung automatisch nach jedem App-Neustart zu aktivieren, öffnen Sie **Einstellungen > Video** und aktivieren Sie **Terminalaufzeichnung nach App-Neustart aktivieren**.
2. Um die Aufzeichnung nur für diese Sitzung zu aktivieren, öffnen Sie **Tools > Video Manager...** und wählen Sie **Terminalaufzeichnung für diese App-Sitzung aktivieren**.
3. Legen Sie den **Speicherpfad** fest. Wenn die Standardeinstellung beibehalten wird, verwendet KorTTY `~/.kortty/recordings`.
4. Wählen Sie das Standardformat und den Standard-Split-Bereich. Die KorTTY-Wiedergabe ist immer verfügbar; Für den Videoexport ist `ffmpeg` erforderlich.
5. Aktivieren oder deaktivieren Sie **Auto-Pause, wenn das Terminal im Leerlauf ist** und legen Sie den Leerlaufschwellenwert fest (Standard: 20 Sekunden).
6. Optional: Aktivieren Sie **Terminalfarben in neuen Aufnahmen erfassen**, wenn exportierte Videos Terminalfarben wiedergeben sollen.
7. Optional: Legen Sie den `ffmpeg`-Pfad fest und klicken Sie auf **Prüfen**. Wenn `ffmpeg` fehlt, bleibt der Videoexport deaktiviert, Wiedergabedateien bleiben jedoch verwendbar.
8. Klicken Sie auf **Speichern**.

### Aufnahme starten und stoppen

1. Öffnen oder fokussieren Sie eine SSH-Terminal-Registerkarte.
2. Wenn die Terminalaufzeichnung aktiviert ist, klicken Sie in der Terminalleiste auf **Aufzeichnung starten**, wählen Sie **Extras > Terminalaufzeichnung starten/stoppen** oder drücken Sie ++ctrl+shift+e++ (Befehl+Umschalt+E unter macOS).
3. Wenn die Registerkarte mehrere geteilte Terminals enthält, wählen Sie aus, ob nur die aktive Teilung oder die gesamte Registerkarte aufgezeichnet werden soll.
4. Klicken Sie auf **Aufzeichnung stoppen** oder drücken Sie erneut ++ctrl+shift+e++, um das aktuelle Segment zu stoppen.
5. Starten und stoppen Sie so oft wie nötig auf derselben Registerkarte. KorTTY hängt alle Segmente an dieselbe Wiedergabedatei an, bis die Registerkarte geschlossen wird.

### Exportieren Sie ein Video

1. Öffnen Sie **Tools > Video-Manager...**.
2. Wählen Sie eine `.korttyrec.jsonl.gz`-Wiedergabedatei aus der Liste aus.
3. Stellen Sie sicher, dass der ffmpeg-Status besagt, dass der Videoexport aktiviert ist.
4. Klicken Sie auf **Exportieren...**.
5. In den Exportoptionen:
   - Wählen Sie **Gesamte Aufzeichnung exportieren** oder geben Sie Start-/Endzeiten im Minuten- oder `MM:SS`-Format ein.
   - Wählen Sie, ob Terminalfarben einbezogen werden sollen (nur verfügbar, wenn die Wiedergabe Farbdaten enthält).
   - Wählen Sie das Format **WebM/VP9** oder **MKV/FFV1** und dann einen Ausgabepfad.
6. Während KorTTY Frames rendert und `ffmpeg` ausführt, zeigt der Exportfortschrittsdialog die aktuelle Phase, den Fortschrittsbalken und die geschätzte verbleibende Zeit an. Beim Export wird die aufgezeichnete Terminalgeometrie verwendet, sodass große Terminalbildschirme nicht beschnitten werden.

### Aufnahmen ansehen und verwalten

1. Öffnen Sie **Tools > Video-Manager...**.
2. Wählen Sie eine `.korttyrec.jsonl.gz`-Wiedergabedatei aus.
3. Klicken Sie auf **Anzeigen**, um die Wiederholung direkt in KorTTY abzuspielen.
4. Verwenden Sie die Replay-Viewer-Timeline zum Scrubben oder geben Sie einen **Zeitsprung**-Wert ein, z. B. `5` für Minute 5 oder `5:30` für Minute 5 und 30 Sekunden.
5. Stellen Sie **Geschwindigkeit** zwischen `1x` und `20x` ein, um die Wiedergabegeschwindigkeit zu steuern.
6. Klicken Sie auf **Umbenennen...**, um die Wiedergabedatei umzubenennen.
7. Klicken Sie auf **Löschen**, um die ausgewählte Wiederholung nach der Bestätigung zu löschen.
