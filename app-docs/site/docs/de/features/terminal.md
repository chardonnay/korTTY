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
| **Neuer Tab** | ++ctrl+t++ (Befehl+T unter macOS) – öffnet Quick Connect, um eine neue Sitzung zu starten |
| **Tab schließen** | ++ctrl+w++ (Befehl+W unter macOS) – schließt die aktive Registerkarte. Sie werden nur dann um eine Bestätigung gebeten, wenn etwas verloren geht: Die Registerkarte hat geteilte Bereiche oder ein Befehl wird noch ausgeführt (eine lokale Shell mit einem laufenden untergeordneten Prozess oder eine SSH-Sitzung, die nicht zur Eingabeaufforderung gelangt). Ein inaktives einzelnes Terminal wird sofort geschlossen. Die verbindungsspezifische Einstellung *Ohne Bestätigung schließen* unterdrückt die Eingabeaufforderung vollständig. |
| **Nächster Tab** | ++ctrl+Tab++ |
| **Vorheriger Tab** | ++ctrl+shift+Tab++ |
| **Erneut verbinden** | Klicken Sie mit der rechten Maustaste auf eine Registerkarte, den Terminalbereich oder einen Servereintrag im Dashboard. Ist die Verbindung aktiv, wird sie sofort geschlossen und wieder aufgebaut; Wenn die Verbindung getrennt wird, wird sie wiederhergestellt. Das Terminalfenster bleibt geöffnet. |
| **Registerkartengruppen** | Klicken Sie mit der rechten Maustaste auf eine Registerkarte, um sie zur besseren Organisation einer benannten Gruppe zuzuweisen. |

## Sicher verbinden

Interaktive SSH-Terminals teilen das Host-Key-Vertrauen mit SFTP und dem von Mosh verwendeten SSH-Bootstrap. Bei der ersten Verbindung zu einem normalisierten Host und Port werden der Schlüsselalgorithmus und der OpenSSH SHA-256-Fingerabdruck angezeigt, wobei **Nein** standardmäßig ausgewählt ist. Nachdem Sie es überprüft und akzeptiert haben, werden exakte Übereinstimmungen automatisch hergestellt. Ein geänderter Schlüssel wird ohne automatischen Wiederholungsversuch fest blockiert. Siehe [SSH-Hostschlüsselüberprüfung](connections.md#ssh-host-key-verification).

Beim Öffnen einer Verbindung mit demselben Server oder einer neu ausgewählten Verbindung in einem Split wird ein Fortschrittsdialog angezeigt, während der SSH-Handshake auf einem Worker ausgeführt wird. Die Schnittstelle reagiert weiterhin sowohl auf die Host-Tasten-Bestätigung als auch auf Eingabeaufforderungen zur interaktiven Tastaturauthentifizierung.

Einige Fehler werden direkt abgelehnt und nicht erneut versucht, da eine Wiederholung des Versuchs das Ergebnis nicht ändern kann – ein geänderter Hostschlüssel, eine mit einem Jump-Server konfigurierte Mosh-Verbindung oder eine fehlende Mosh-Laufzeit. Das Terminal löscht den Vorgang und zeigt sofort den Grund an, anstatt die Anzahl der Wiederholungsversuche durchzugehen. Informationen zur Mosh-Einschränkung finden Sie unter [Jump server](jump-server.md).

Der angeheftete SithTermFX-Build von KorTTY enthält auch eine überarbeitete Korrektur der Begrenzung der unteren Zeile: Beim Bewegen über einen Hyperlink oder die letzte sichtbare Terminalzeile wird `TerminalTextBuffer` nicht mehr nach der nicht vorhandenen Zeile bei `line == height` gefragt.

## Multi-Window-Unterstützung

Öffnen Sie zusätzliche Fenster, um Verbindungen nach Projekt oder Umgebung zu organisieren:

- **Neues Fenster**: ++ctrl+shift+n++ (Befehl+Umschalt+N unter macOS) öffnet ein neues KorTTY-Fenster. Jedes Fenster kann über eigene Registerkarten und Verbindungen verfügen.
- **Registerkarten zwischen Fenstern verschieben**: Ziehen Sie eine Registerkarte aus der Registerkartenleiste und legen Sie sie auf der Registerkartenleiste eines anderen KorTTY-Fensters ab, um diese Registerkarte (und ihre Sitzung, einschließlich aller geteilten Terminals) in das andere Fenster zu verschieben.
- **Tabs neu anordnen**: Ziehen Sie einen Tab innerhalb desselben Fensters, um seine Reihenfolge zu ändern; die Registerkarte „+“ bleibt am Ende.

## Schriftgröße und Zoom

Passen Sie die Schriftgröße des aktiven Terminals im Handumdrehen an, ohne die Verbindung erneut herzustellen:

| Verknüpfung | Aktion |
|----------|--------|
| ++alt+plus++ | Vergrößern (Schriftgröße vergrößern) |
| ++alt+minus++ | Verkleinern (Schriftgröße verringern) |
| ++alt+0++ | Zoom auf gespeicherte/Standardschriftart zurücksetzen |
| ++ctrl++ + Mausrad | Vergrößern/verkleinern Sie das Terminal (Befehlstaste + Rad unter macOS) |

Wenn Sie ++ctrl++ (oder ++cmd++ unter macOS) gedrückt halten und mit dem Mausrad über das Terminal scrollen, ändert sich die Schriftgröße – Rad nach oben vergrößert, Rad nach unten verkleinert – anstatt durch den Puffer zu scrollen. Dies ergänzt die Tastenkombinationen ++alt+plus++ / ++alt+minus++ / ++alt+0++.

**Zoom zurücksetzen** stellt die Schriftgröße und -familie wieder her, die die Verbindung hatte, als Sie die Registerkarte geöffnet haben (oder die gespeicherten Einstellungen der Verbindung oder den globalen Standard). Das gleiche Zurücksetzen ist über das Terminal-Kontextmenü verfügbar: Rechtsklick → **Schriftgröße** → **Zurücksetzen**. Die Zoomstufe gilt nur für das aktuell fokussierte Terminal.

## Hintergrundtransparenz

**Ansicht → Zoom → Hintergrundtransparenz** ist ein Schieberegler (0–100 %), der den Terminalhintergrund auf dem Desktop durchscheinen lässt, während der Text völlig undurchsichtig und scharf bleibt. Bei 0 % ist der Hintergrund einfarbig; Höhere Werte lassen mehr vom Desktop durchscheinen. Der Wert wird über Neustarts hinweg gespeichert und wiederhergestellt.

Nur der Terminalbereich wird transparent – ​​die Titelleiste, die Menüleiste, die Statusleiste und alle Registerkarten ohne Terminal bleiben solide, sodass das Fenster nie zu einem durchsichtigen Loch wird.

Horizontale, vertikale und verschachtelte geteilte Terminals erben die aktive Transparenzstufe, einschließlich der nach der Transparenzaktivierung hinzugefügten Bereiche. Wenn Sie mit ++f12++ den Vollbildmodus oder mit ++ctrl+shift+f++ den reinen Terminal-Vollbildmodus aufrufen, wird der Terminalbereich vorübergehend undurchsichtig, ohne dass sich der gespeicherte Wert ändert. Wenn Sie den Vollbildmodus verlassen, wird dieser Wert in jedem Bereich wiederhergestellt.

Da ein durchsichtiges Fenster einen anderen Fensterstil verwendet, den das Betriebssystem beim Öffnen des Fensters korrigiert, wird **das Ein- oder Ausschalten der Transparenz (Überschreiten von 0 %) erst nach einem Neustart vollständig wirksam**; Die Statusleiste zeigt einen Hinweis an, wenn Sie diesen Schwellenwert überschreiten. Das Anpassen des Pegels bereits im transparenten Modus wird live angewendet. Im transparenten Modus verwendet das Fenster eine schlanke benutzerdefinierte Titelleiste (Ziehen zum Verschieben, Schaltflächen zum Minimieren/Maximieren/Schließen, Doppelklick auf den Streifen zum Maximieren, Ziehen an den Rändern zum Ändern der Größe).

Der Schieberegler befindet sich nur in der Menüleiste im Fenster (die native macOS-Menüleiste kann keinen Schieberegler hosten).

## Lokale Shell-Registerkarten

Neben SSH und Mosh kann eine Terminal-Registerkarte eine **Lokale Shell** hosten – die eigene Shell des lokalen Computers, die über ein Pseudo-Terminal geöffnet wird (siehe [Lokale Shell](connections.md#local-shell)). Einige Terminalverhalten sind lokal-Shell-bewusst:

- **++ctrl+d++ schließt die Registerkarte für lokale cmd.exe/PowerShell-Sitzungen.** Diese Windows-Shells werden bei EOF nicht beendet, sodass ++ctrl+d++ andernfalls keine Auswirkung hätte. Für Shells der Bash-Familie (Git Bash/Cygwin/WSL, macOS/Linux) und SSH behält ++ctrl+d++ seine normale EOF-Bedeutung – die Shell wird beendet und die lokale Registerkarte wird dann automatisch geschlossen.
- **Bestätigung schließen** verwendet den Wortlaut „Local-Shell“ anstelle von „SSH-Verbindung beenden?“ und die Eingabeaufforderung zum Schließen des Fensters ist transportneutral („Aktive Sitzungen“), da ein Fenster SSH-, Mosh- und Local-Shell-Registerkarten mischen kann.
- **Das aktuelle Verzeichnis folgt der interaktiven Shell.** Unter macOS und Linux aktualisiert korTTY es vom lokalen Shell-Prozess; Native PowerShell- und cmd-Eingabeaufforderungen stellen absolute Windows-Pfade bereit. Nach `cd`, `pushd`, `popd` oder `Set-Location` löst **Im Snippet-Editor öffnen** einen ausgewählten Dateinamen in das aktuelle Verzeichnis und nicht in das Startverzeichnis der Registerkarte auf. Wenn das Verzeichnis nicht sicher bestimmt oder zugeordnet werden kann, stoppt korTTY mit einem Fehler, anstatt eine gleichnamige Datei aus dem falschen Verzeichnis zu öffnen.
- **Zwischenablagetext bleibt in Agentenverknüpfungen erhalten.** Eingegebener und eingefügter Text durchläuft denselben Terminal-Eingabefilter, einschließlich Einfügen in Klammern und geteilter UTF-8-Eingabe, sodass ein eingefügter Dateiname Teil der `agent ...`-Anfrage bleibt und Enter ihn genau einmal versendet.

## Split-Screen mit Übertragung

Teilen Sie die Terminalansicht, um mehrere Verbindungen nebeneinander anzuzeigen, und senden Sie optional Eingaben an alle Bereiche gleichzeitig.

### Vorgänge aufteilen

- **Geteilter Bereich**: Erstellen Sie über das Kontextmenü oder Tastaturkürzel horizontale oder vertikale Teilungen innerhalb einer Registerkarte.
- **Unabhängige Sitzungen**: In jedem Bereich kann eine andere SSH-Verbindung angezeigt werden.
- **Anpassbare Fensterbereiche**: Ziehen Sie die Trennlinien, um die Fenstergrößen anzupassen.
- **Fenster verschieben**: Halten Sie ++shift+alt++ (Windows/Linux) oder ++shift+option++ (macOS) gedrückt und ziehen Sie ein Fenster auf ein anderes, um es neu anzuordnen. Ohne die Modifikatoren wird das Ziehen mit der Maus für die Textauswahl im Terminal verwendet.

### Broadcast-Modus

Wenn der **Broadcast-Modus** aktiviert ist, werden Tastatureingaben gleichzeitig an alle sichtbaren Bereiche gesendet. Dies ist nützlich, um dieselben Befehle auf mehreren Servern auszuführen.

## Terminale Auswirkungen

Terminaleffekte können den sichtbaren Terminalstil und die Ausgabeanimation ändern. Effekte sind Java-Plugins, die über **Plugins > Terminaleffekte** verwaltet werden.

### Benutzerkontrollen

- **Aktuelles Terminal**: Verwenden Sie **Ansicht > Terminaleffekt** oder das Terminal-Kontextmenü, um einen Effekt für das aktive Terminal auszuwählen.
- **Quick Connect**: Wählen Sie den Effekt und die Geschwindigkeit, bevor Sie eine temporäre oder gespeicherte Verbindung öffnen.
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

Protokollieren Sie die Ausgabe der Terminalsitzung automatisch zu Prüf- und Debugging-Zwecken:

1. Aktivieren Sie die Protokollierung auf der Registerkarte **Terminalprotokollierung** der Verbindung.
2. Wählen Sie ein Protokollformat:
   - **Einfacher Text** – Rohe Terminalausgabe.
   - **XML** – Strukturiertes XML mit Zeitstempeln.
   - **JSON** – Strukturiertes JSON mit Zeitstempeln.
3. Legen Sie eine **maximale Dateigröße** fest (Standard: 10 MB). Bei Überschreitung wird die Protokolldatei rotiert.
4. Protokolle werden in `~/.kortty/history/` als komprimierte Dateien gespeichert.

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
