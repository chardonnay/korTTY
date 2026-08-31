---
title: Terminalaufzeichnung
---

# Terminalaufzeichnung

KorTTY zeichnet Terminalsitzungen als kompakte Wiedergabedateien mit optionaler Farberfassung und Videoexport auf. Sitzungsaufzeichnungen erfassen Änderungen des Bildschirmzustands und Zeitereignisse ohne kontinuierliche Pixelerfassung, wodurch sie effizient und portabel sind.

## Aufnahmeformat und Speicherung

Die Terminalaufzeichnung speichert Sitzungsdaten in komprimierten JSONL-Wiedergabedateien, eine pro Terminal-Tab-Sitzung:

- **Wiedergabedateiformat**: `.korttyrec.jsonl.gz` (JDK/GZIP-Streaming-komprimiert)
- **Legacy-Format**: `.korttyrec.jsonl`-Dateien bleiben lesbar (unkomprimiert)
- **Standardspeicher**: `~/.kortty/recordings`
- **Inhalt**: Änderungen des Bildschirmstatus, Zeitereignisse und optionale Farbläufe

!!! note
    Aufnahmen erfassen nicht kontinuierlich Pixel und werden nicht über das Netzwerk gestreamt. Die Dateigröße hängt von der Aktivitätsstufe ab und davon, ob die Farberfassung aktiviert ist.

## Konfiguration

### Aufnahme aktivieren

Um die Aufzeichnung nach jedem App-Neustart verfügbar zu machen:

1. Öffnen Sie **Einstellungen > Video**
2. Aktivieren Sie **Terminalaufzeichnung nach App-Neustart aktivieren**
3. Klicken Sie auf **Speichern**

So aktivieren Sie die Aufnahme nur, bis KorTTY beendet wird:

1. Öffnen Sie **Tools > Video-Manager**
2. Wählen Sie **Terminalaufzeichnung für diese App-Sitzung aktivieren**
3. Klicken Sie auf **Speichern**

### Speicher und Verhalten konfigurieren

Legen Sie unter **Tools > Video Manager** die folgenden Optionen fest:

| Option | Beschreibung |
|--------|-------------|
| **Speicherpfad** | Verzeichnis, in dem Wiedergabedateien gespeichert werden (Standard: `~/.kortty/recordings`) |
| **Standardformat** | KorTTY-Wiedergabeformat für neue Aufnahmen |
| **Standardmäßiger geteilter Bereich** | Nur aktive Teilung oder gesamte Registerkarte aufzeichnen |
| **Automatische Pause im Leerlauf** | Aufzeichnung pausieren, wenn Terminal inaktiv ist |
| **Leerlaufschwelle** | Sekunden Inaktivität vor der Pause (Standard: 20) |
| **Farben erfassen** | Terminal-Läufe pro Zelle in neue Aufzeichnungen einbeziehen |
| **ffmpeg-Pfad** | Pfad zur lokalen `ffmpeg`-Binärdatei für den Videoexport |

!!! tip
    Aktivieren Sie die Farberfassung *vor* der Aufnahme, wenn Sie möchten, dass exportierte Videos die Terminalfarben genau wiedergeben. Vorhandene Aufnahmen ohne Farbdaten können weiterhin exportiert werden, die Farben bleiben jedoch nicht erhalten.

### ffmpeg-Konfiguration

Der Videoexport erfordert eine lokale `ffmpeg`-Installation:

1. Geben Sie unter **Tools > Video Manager** den vollständigen Pfad zu `ffmpeg` ein (oder lassen Sie das Feld leer, um die automatische Erkennung zu ermöglichen).
2. Klicken Sie auf **Prüfen**, um die Verfügbarkeit zu überprüfen
3. Wenn `ffmpeg` nicht gefunden wird, bleibt der Videoexport deaktiviert, aber die Wiedergabewiedergabe und andere Funktionen funktionieren normal

Wenn sich `ffmpeg` unter macOS und Linux in Ihrem PATH befindet, erkennt KorTTY es automatisch. Unter Windows müssen Sie möglicherweise den vollständigen Pfad angeben.

## Eine Sitzung aufzeichnen

### Aufnahme starten

1. Öffnen oder fokussieren Sie eine SSH-Terminal-Registerkarte
2. Wenn die Aufzeichnung aktiviert ist, klicken Sie in der Terminalleiste auf **Aufzeichnung starten** oder:
   - Wählen Sie **Extras > Terminalaufzeichnung starten/stoppen**, oder
   - Press ++ctrl+shift+e++ (++cmd+shift+e++ auf macOS)
3. Wenn die Registerkarte geteilte Anschlüsse enthält, wählen Sie:
   - **Aktive Teilung** – Nur den fokussierten Terminalbereich aufzeichnen
   - **Gesamte Registerkarte** – alle sichtbaren Teilungen aufzeichnen

Das Steuerelement in der Terminalleiste ist ausgeblendet, bis die Aufzeichnung aktiviert oder der Menüpunkt/die Verknüpfung verwendet wird.

### Stoppen Sie die Aufnahme

- Klicken Sie in der Terminalleiste auf **Aufnahme beenden**, oder
- Press ++ctrl+shift+e++ (++cmd+shift+e++ auf macOS)

### Mehrere Segmente

Starten und stoppen Sie die Aufnahme so oft wie nötig auf derselben Registerkarte. Alle Segmente werden an dieselbe Wiedergabedatei angehängt, bis die Registerkarte geschlossen wird. Bei jedem Aufnahmestart/-stopp wird ein neues Segment in der Datei erstellt.

## Videos exportieren

Für exportierte Videos muss `ffmpeg` verfügbar und konfiguriert sein.

### Exportoptionen

1. Öffnen Sie **Tools > Video-Manager**
2. Wählen Sie eine `.korttyrec.jsonl.gz`-Wiedergabedatei aus
3. Stellen Sie sicher, dass der **ffmpeg-Status** besagt, dass der Videoexport aktiviert ist
4. Klicken Sie auf **Exportieren**
5. Im Exportdialog:
   - Wählen Sie **Gesamte Aufzeichnung exportieren** oder geben Sie einen benutzerdefinierten Zeitraum an
   - Geben Sie für benutzerdefinierte Bereiche die Start-/Endzeiten als `MM` (Minuten) oder `MM:SS` (Minuten und Sekunden) ein.
   - Werte, die über die Wiedergabedauer hinausgehen, werden abgelehnt
   - Wählen Sie, ob **Terminalfarben einbezogen werden** (nur verfügbar, wenn die Wiedergabe Farbdaten enthält)
   - Wählen Sie das Ausgabeformat: **WebM/VP9** oder **MKV/FFV1**
   - Wählen Sie den Ausgabeort

### Exportfortschritt

Während des Exports wird in einem Fortschrittsdialog Folgendes angezeigt:

- Aktuelle Phase (Frame-Rendering, Videokodierung)
- Fortschrittsbalken
- Geschätzte verbleibende Zeit

Beim Export wird die aufgezeichnete Terminalgeometrie verwendet, um das Zuschneiden breiter Terminalbildschirme zu vermeiden. Wenn die Geometrie nicht verfügbar ist, berechnet KorTTY eine Legacy-Fallback-Größe.

## Aufnahmen ansehen und verwalten

### Sehen Sie sich eine Wiederholung an

1. Öffnen Sie **Tools > Video-Manager**
2. Wählen Sie eine `.korttyrec.jsonl.gz`-Wiedergabedatei aus
3. Klicken Sie auf **Anzeigen**, um die Wiedergabe im integrierten Viewer zu öffnen
4. Verwenden Sie die Timeline-Steuerelemente:
   - **Timeline-Schieberegler** – Ziehen Sie ihn, um zu einer beliebigen Position zu springen
   - **Zeitsprung** – Geben Sie `5` für Minute 5 und `5:30` für 5 Minuten 30 Sekunden ein
   - **Geschwindigkeit** – Stellen Sie die Wiedergabegeschwindigkeit zwischen 1x und 20x ein

### Eine Wiederholung umbenennen

1. Wählen Sie unter **Tools > Video-Manager** eine Wiedergabe aus.
2. Klicken Sie auf **Umbenennen**
3. Geben Sie einen neuen Dateinamen ein
4. Die Datei wird im Speicherordner umbenannt

### Eine Wiederholung löschen

1. Wählen Sie unter **Tools > Video-Manager** eine Wiedergabe aus.
2. Klicken Sie auf **Löschen**
3. Bestätigen Sie den Löschvorgang

## Aufzeichnung von Ereignissen und JSONL-Format

Wiedergabedateien enthalten zeitgestempelte Ereignisse im JSONL-Format (ein Ereignis pro Zeile, gzip-komprimiert). Das dekomprimierte JSONL kann Folgendes enthalten:

| Veranstaltung | Beschreibung |
|-------|-------------|
| `recording_start` | Aufnahmesitzung hat begonnen |
| `screen` | Änderung des Bildschirmstatus (kann `styleRuns` mit Vordergrund-/Hintergrundwerten umfassen) |
| `auto_pause` | Aufnahme wegen Inaktivität pausiert |
| `auto_resume` | Aufnahme nach Inaktivität fortgesetzt |
| `recording_stop` | Aufnahme manuell gestoppt |
| `session_closed` | Terminal-Tab geschlossen |

Jedes Bildschirmereignis umfasst Terminalinhalte, Abmessungen und optionale Farbstilläufe.

## Tipps und Best Practices

- **Große Terminalfenster**: Die Terminalgeometrie wird aufgezeichnet, sodass Breitbildschirme ohne Beschneiden korrekt exportiert werden
- **Farbgenauigkeit**: Aktivieren Sie die Farberfassung vor der Aufnahme, wenn Sie Videos mit Terminalfarben exportieren möchten
- **Leerlaufpause**: Die automatische Pause hilft, die Dateigröße bei langen Leerlaufzeiten zu reduzieren; Aktivieren Sie es, es sei denn, Sie müssen das genaue Timing beibehalten
- **Split-Scope-Auswahl**: Wählen Sie Ihren Aufzeichnungsbereich (aktive Aufteilung vs. gesamte Registerkarte) jedes Mal, wenn Sie mit der Aufzeichnung beginnen, passend zu Ihrem Arbeitsablauf
- **Legacy-Dateien**: Alte `.korttyrec.jsonl`-Wiedergabedateien (unkomprimiert) sind im Video-Manager weiterhin lesbar