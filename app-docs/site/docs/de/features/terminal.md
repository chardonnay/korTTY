---
title: Terminalsitzungen
---

# Terminalsitzungen

KorTTY bietet eine Terminalschnittstelle mit Registerkarten, die mehrere gleichzeitige SSH-Verbindungen, geteilte Bildschirmlayouts und interaktive Terminalverwaltungsfunktionen unterstützt. In diesem Anleitung werden Registerkartenvorgänge, die Unterstützung mehrerer Fenster, die Anpassung des Terminals und erweiterte Sitzungsfunktionen behandelt.

## Sitzungslebenszyklus

Das folgende Diagramm zeigt die Registerkarte „Terminal“ und den Sitzungslebenszyklus, einschließlich Split-Screen- und Broadcast-Modi.

![Terminal session lifecycle](../assets/diagrams/session-lifecycle.svg)

## Mit Tabs arbeiten

Verwalten Sie mehrere SSH-Sitzungen mit diesen Registerkartenoperationen:

| Aktion | Verknüpfung |
|--------|----------|
| **Neuer Tab** | ++Strg+T++ (Befehl+T unter macOS) – öffnet Quick Connect, um eine neue Sitzung zu starten |
| **Tab schließen** | ++Strg+W++ (Befehl+W unter macOS) – schließt die aktive Registerkarte mit optionaler Bestätigung |
| **Nächster Tab** | ++Strg+Tab++ |
| **Vorheriger Tab** | ++Strg+Umschalt+Tab++ |
| **Wieder verbinden** | Klicken Sie mit der rechten Maustaste auf eine Registerkarte, den Terminalbereich oder einen Servereintrag im Dashboard. Ist die Verbindung aktiv, wird sie sofort geschlossen und wieder aufgebaut; Wenn die Verbindung getrennt wird, wird sie wiederhergestellt. Das Terminalfenster bleibt geöffnet. |
| **Registerkartengruppen** | Klicken Sie mit der rechten Maustaste auf eine Registerkarte, um sie zur besseren Organisation einer benannten Gruppe zuzuweisen |

## Multi-Window-Unterstützung

Öffnen Sie zusätzliche Fenster, um Verbindungen nach Projekt oder Umgebung zu organisieren:

- **Neues Fenster**: ++Strg+Umschalt+N++ (Befehl+Umschalt+N unter macOS) öffnet ein neues KorTTY-Fenster. Jedes Fenster kann über eigene Registerkarten und Verbindungen verfügen.
- **Registerkarten zwischen Fenstern verschieben**: Ziehen Sie eine Registerkarte aus der Registerkartenleiste und legen Sie sie auf der Registerkartenleiste eines anderen KorTTY-Fensters ab, um diese Registerkarte (und ihre Sitzung, einschließlich aller geteilten Terminals) in das andere Fenster zu verschieben.
- **Registerkarten neu anordnen**: Ziehen Sie eine Registerkarte innerhalb desselben Fensters, um ihre Reihenfolge zu ändern; die Registerkarte „+“ bleibt am Ende.

## Schriftgröße und Zoom

Passen Sie die Schriftgröße des aktiven Terminals im Handumdrehen an, ohne die Verbindung erneut herzustellen:

| Verknüpfung | Aktion |
|----------|--------|
| ++alt+plus++ | Vergrößern (Schriftgröße vergrößern) |
| ++alt+minus++ | Verkleinern (Schriftgröße verringern) |
| ++alt+0++ | Zoom auf gespeicherte/Standardschriftart zurücksetzen |
| ++ctrl++ + Mausrad | Über dem Terminal hinein-/herauszoomen (Cmd + Mausrad auf macOS) |

Wenn Sie ++ctrl++ (oder ++cmd++ auf macOS) gedrückt halten und mit dem Mausrad über dem Terminal scrollen, ändert sich die Schriftgröße – Rad nach oben vergrößert, nach unten verkleinert – statt den Puffer zu scrollen. Dies ergänzt die Tastenkürzel ++alt+plus++ / ++alt+minus++ / ++alt+0++.

**Zoom zurücksetzen** stellt die Schriftgröße und -familie wieder her, die die Verbindung hatte, als Sie die Registerkarte geöffnet haben (oder die gespeicherten Einstellungen der Verbindung oder den globalen Standard). Das gleiche Zurücksetzen ist über das Terminal-Kontextmenü verfügbar: Rechtsklick → **Schriftgröße** → **Zurücksetzen**. Die Zoomstufe gilt nur für das aktuell fokussierte Terminal.

## Lokale-Shell-Registerkarten

Neben SSH und Mosh kann eine Terminal-Registerkarte auch eine **lokale Shell** beherbergen – die eigene Shell des lokalen Rechners, geöffnet über ein Pseudo-Terminal (siehe [Lokale Shell](connections.md#lokale-shell)). Einige Terminalverhalten sind lokale-Shell-bewusst:

- **++ctrl+d++ schließt die Registerkarte bei lokalen cmd.exe-/PowerShell-Sitzungen.** Diese Windows-Shells beenden sich nicht bei EOF, sodass ++ctrl+d++ dort sonst keine Wirkung hätte. Bei Bash-Familien-Shells (Git Bash/Cygwin/WSL, macOS/Linux) und SSH behält ++ctrl+d++ seine normale EOF-Bedeutung – die Shell beendet sich, und die lokale Registerkarte schließt sich daraufhin automatisch.
- **Die Schließbestätigung** verwendet eine lokale-Shell-Formulierung statt „SSH-Verbindung beenden?“, und die Fenster-Schließabfrage ist transportneutral („Aktive Sitzungen“), da ein Fenster SSH-, Mosh- und lokale-Shell-Registerkarten mischen kann.

## Geteilter Bildschirm mit Übertragung

Teilen Sie die Terminalansicht, um mehrere Verbindungen nebeneinander anzuzeigen, und senden Sie optional Eingaben an alle Bereiche gleichzeitig.

### Operationen aufteilen

- **Geteilter Bereich**: Erstellen Sie über das Kontextmenü oder Tastaturkürzel horizontale oder vertikale Teilungen innerhalb einer Registerkarte.
- **Unabhängige Sitzungen**: In jedem Bereich kann eine andere SSH-Verbindung angezeigt werden.
- **Anpassbare Fensterbereiche**: Ziehen Sie die Trennlinien, um die Fenstergrößen anzupassen.
- **Fenster verschieben**: Halten Sie ++Umschalt+alt++ (Windows/Linux) or ++Umschalt+Option++ (macOS) gedrückt und ziehen Sie einen Bereich auf einen anderen, um ihn neu anzuordnen. Ohne die Modifikatoren wird das Ziehen mit der Maus für die Textauswahl im Terminal verwendet.

### Broadcast-Modus

Wenn der **Broadcast-Modus** aktiviert ist, werden Tastatureingaben gleichzeitig an alle sichtbaren Bereiche gesendet. Dies ist nützlich, um dieselben Befehle auf mehreren Servern auszuführen.

## Endgültige Auswirkungen

Terminaleffekte können den sichtbaren Terminalstil und die Ausgabeanimation ändern. Effekte sind Java-Plugins, die über **Plugins > Terminaleffekte** verwaltet werden.

### Benutzerkontrollen

- **Aktuelles Terminal**: Verwenden Sie **Ansicht > Terminaleffekt** oder das Terminal-Kontextmenü, um einen Effekt für das aktive Terminal auszuwählen.
- **Schnellverbindung**: Wählen Sie den Effekt und die Geschwindigkeit, bevor Sie eine temporäre oder gespeicherte Verbindung öffnen.
- **Verbindungsmanager**: Speichern Sie den Effekt und die Geschwindigkeit einer gespeicherten Verbindung, damit neue Tabs sie automatisch verwenden.
- **Geschwindigkeit**: Verwenden Sie den Schieberegler für `1x` bis `10x`; Wenn das immer noch zu langsam ist, geben Sie im numerischen Geschwindigkeitsfeld einen benutzerdefinierten Wert bis zu `99x` ein.

### Plugin-Verwaltung

- Öffnen Sie **Plugins > Terminaleffekte**, um Plugins zu verwalten.
– Die Tabelle listet geladene Plugins mit aktivem Status, Namen und Beschreibung auf.
- **Deaktivieren** Sie ein Plugin, damit es installiert bleibt, aber nicht zur Aktivierung verfügbar ist.
- **Import** externer `.jar`-Plugins. KorTTY kopiert sie in `~/.kortty/plugins`.
- Plugins **Exportieren**, die über eine Quell-JAR verfügen. Der gebündelte MOTHER-Effekt ist exportierbar.

!!! Warnung
Importierte Terminaleffekt-Plugins sind vertrauenswürdiger Java-Code und werden nicht in einer Sandbox gespeichert. Importieren Sie Plugins nur aus Quellen, denen Sie vertrauen.

Eine ausführliche Dokumentation zur Plugin-Entwicklung finden Sie unter [Terminal Effect Plugins](terminal-effect-plugins.md).

## SSH-Keep-Alive

Verhindern Sie, dass Verbindungen aufgrund von Inaktivität unterbrochen werden, indem Sie SSH-Keepalive-Nachrichten konfigurieren:

1. Aktivieren Sie **SSH Keep-Alive** auf der Registerkarte **Terminal** der Verbindung oder unter **Einstellungen > Terminal**.
2. Stellen Sie das Intervall ein (5 bis 600 Sekunden, Standard: 60).
3. KorTTY sendet `SSH_MSG_IGNORE`-Heartbeat-Nachrichten im konfigurierten Intervall und aktiviert TCP-Socket-Keepalive, während die Option aktiv ist.

!!! Notiz
Wenn ein Server, eine Firewall, ein VPN oder ein NAT-Gateway inaktive Sitzungen früher als im konfigurierten Intervall schließt, kann die Verbindung trotzdem beendet werden. Überprüfen Sie in diesem Fall die serverseitige SSH-Konfiguration und die Netzwerk-Leerlauf-Timeout-Einstellungen sowie das KorTTY-Protokoll.

## Terminalprotokollierung

Protokollieren Sie die Ausgabe der Terminalsitzung automatisch zu Prüf- und Debugging-Zwecken:

1. Aktivieren Sie die Protokollierung auf der Registerkarte **Terminalprotokollierung** der Verbindung.
2. Wählen Sie ein Protokollformat:
- **Klarer Text** – Rohe Terminalausgabe.
- **XML** – Strukturiertes XML mit Zeitstempeln.
- **JSON** – Strukturiertes JSON mit Zeitstempeln.
3. Legen Sie eine **maximale Dateigröße** fest (Standard: 10 MB). Bei Überschreitung wird die Protokolldatei rotiert.
4. Protokolle werden in `~/.kortty/history/` als komprimierte Dateien gespeichert.

## Terminalaufzeichnung

Die Terminalaufzeichnung ist als ressourcenschonende Wiedergabefunktion konzipiert. KorTTY zeichnet Terminal-Bildschirmstatusänderungen und Zeitereignisse in einer JDK/GZIP-Streaming-komprimierten `.korttyrec.jsonl.gz`-Datei pro Terminal-Tab-Sitzung auf. Ältere `.korttyrec.jsonl`-Wiedergabedateien bleiben lesbar.

### Aufnahmen konfigurieren

1. Um die Aufzeichnung automatisch nach jedem App-Neustart zu aktivieren, öffnen Sie **Einstellungen > Video** und aktivieren Sie **Terminalaufzeichnung nach App-Neustart aktivieren**.
2. Um die Aufzeichnung nur für diese Sitzung zu aktivieren, öffnen Sie **Tools > Video Manager...** und wählen Sie **Terminalaufzeichnung für diese App-Sitzung aktivieren**.
3. Legen Sie den **Speicherpfad** fest. Wenn die Standardeinstellung beibehalten wird, verwendet KorTTY `~/.kortty/recordings`.
4. Wählen Sie das Standardformat und den Standard-Split-Bereich. Die KorTTY-Wiedergabe ist immer verfügbar; Der Videoexport erfordert `ffmpeg`.
5. Aktivieren oder deaktivieren Sie **Auto-Pause, wenn das Terminal im Leerlauf ist** und legen Sie den Leerlaufschwellenwert fest (Standard: 20 Sekunden).
6. Optional: Aktivieren Sie **Terminalfarben in neuen Aufnahmen erfassen**, wenn exportierte Videos Terminalfarben wiedergeben sollen.
7. Optional: Legen Sie den `ffmpeg`-Pfad fest und klicken Sie auf **Prüfen**. Wenn `ffmpeg` fehlt, bleibt der Videoexport deaktiviert, Wiedergabedateien bleiben jedoch verwendbar.
8. Klicken Sie auf **Speichern**.

### Aufnahme starten und stoppen

1. Öffnen oder fokussieren Sie eine SSH-Terminal-Registerkarte.
2. Wenn die Terminalaufzeichnung aktiviert ist, klicken Sie in der Terminalleiste auf **Aufzeichnung starten**, wählen Sie **Extras > Terminalaufzeichnung starten/stoppen** oder drücken Sie ++Strg+Umschalt+E++ (Befehl+Umschalt+E unter macOS).
3. Wenn die Registerkarte mehrere Split-Terminals enthält, wählen Sie, ob nur die aktive Teilung oder die gesamte Registerkarte aufgezeichnet werden soll.
4. Klicken Sie auf **Aufzeichnung stoppen** oder drücken Sie erneut ++Strg+Umschalt+E++, um das aktuelle Segment zu stoppen.
5. Starten und stoppen Sie so oft wie nötig auf derselben Registerkarte. KorTTY hängt alle Segmente an dieselbe Wiedergabedatei an, bis die Registerkarte geschlossen wird.

### Exportieren Sie ein Video

1. Öffnen Sie **Extras > Video-Manager...**.
2. Wählen Sie eine `.korttyrec.jsonl.gz`-Wiedergabedatei aus der Liste aus.
3. Stellen Sie sicher, dass der ffmpeg-Status besagt, dass der Videoexport aktiviert ist.
4. Klicken Sie auf **Exportieren...**.
5. In den Exportoptionen:
- Wählen Sie **Gesamte Aufzeichnung exportieren** oder geben Sie Start-/Endzeiten im Minuten- oder `MM:SS`-Format ein.
– Wählen Sie, ob Terminalfarben einbezogen werden sollen (nur verfügbar, wenn die Wiedergabe Farbdaten enthält).
- Wählen Sie das Format **WebM/VP9** oder **MKV/FFV1** und dann einen Ausgabepfad.
6. Während KorTTY Frames rendert und `ffmpeg` ausführt, zeigt der Exportfortschrittsdialog die aktuelle Phase, den Fortschrittsbalken und die geschätzte verbleibende Zeit an. Beim Export wird die aufgezeichnete Terminalgeometrie verwendet, sodass große Terminalbildschirme nicht beschnitten werden.

### Aufnahmen ansehen und verwalten

1. Öffnen Sie **Extras > Video-Manager...**.
2. Wählen Sie eine `.korttyrec.jsonl.gz`-Wiedergabedatei aus.
3. Klicken Sie auf **Anzeigen**, um die Wiederholung direkt in KorTTY abzuspielen.
4. Verwenden Sie die Replay-Viewer-Timeline zum Scrubben oder geben Sie einen **Zeitsprung**-Wert ein, z. B. `5` für Minute 5 oder `5:30` für Minute 5 und 30 Sekunden.
5. Stellen Sie **Geschwindigkeit** zwischen `1x` und `20x` ein, um die Wiedergabegeschwindigkeit zu steuern.
6. Klicken Sie auf **Umbenennen...**, um die Wiedergabedatei umzubenennen.
7. Klicken Sie nach der Bestätigung auf **Löschen**, um die ausgewählte Wiederholung zu löschen.
