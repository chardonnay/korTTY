---
title: Projekte (Arbeitsbereiche)
---

# Projekte (Arbeitsbereiche)

Projekte speichern und stellen Ihren gesamten Arbeitsbereichsstatus wieder her – alle geöffneten Fenster, Registerkarten, SSH-Verbindungen und Terminalsitzungen. Dadurch können Sie schnell zwischen verschiedenen Arbeitskontexten wechseln, ohne Fenster manuell neu zu verbinden oder neu zu positionieren.

## Ein Projekt speichern

1. Öffnen Sie *Datei > Projekt speichern* oder drücken Sie ++ctrl+s++ (++cmd+s++ unter macOS).
2. Geben Sie einen **Namen** und optional eine **Beschreibung** für das Projekt ein.
3. Konfigurieren Sie **Auto-Reconnect**:
   - Wenn aktiviert, verbindet sich beim Öffnen des Projekts automatisch jeder gespeicherte Terminal- und SFTP-Tab.
   - Wenn deaktiviert, stellt das Öffnen des Projekts nur die Fenstergeometrie sowie die Tabs für den lokalen Dateieditor und Bildbetrachter wieder her; Terminal-, SFTP- und Remote-Datei-Tabs werden übersprungen.
4. Klicken Sie auf *Speichern*.

Projekte sind `.kortty`-Dateien, die Sie an beliebiger Stelle im Speicherdialog speichern können, zum Beispiel in `~/.kortty/projects/`.

## Ein Projekt öffnen

1. Öffnen Sie *Datei > Projekt öffnen* oder drücken Sie ++ctrl+o++ (++cmd+o++ unter macOS).
2. Wählen Sie im Dateibrowser eine `.kortty`-Projektdatei aus.
3. Das Dialogfeld **Projektvorschau** wird angezeigt und zeigt Folgendes:
   - Anzahl der wiederherzustellenden Fenster
   - Registerkarten und Verbindungen in jedem Fenster
   - Projektmetadaten (Name, Beschreibung, letzte Änderung)
4. Klicken Sie auf *Öffnen*, um das Projekt zu laden.

## Was gespeichert wird

Ein Projekt erfasst den vollständigen Zustand Ihres Arbeitsbereichs:

| Komponente | Einzelheiten |
|-----------|---------|
| **Windows** | Alle geöffneten KorTTY-Fenster und ihre Positionen/Größen |
| **Tabs** | Alle Terminal-Tabs in jedem Fenster, einschließlich Split-Pane-Konfigurationen, sowie SFTP-Manager-Tabs mit den lokalen und entfernten Ordnern, die sie anzeigen, sowie Tabs des Dateieditors und Bildbetrachters |
| **Verbindungen** | Ein Verweis auf die gespeicherte Verbindung jedes Tabs, anhand der internen ID der Verbindung, sodass das Umbenennen einer Verbindung das Projekt nicht bricht |
| **Dashboard** | Sichtbarkeit des Armaturenbretts und Position der Trennwand |
| **Aktiver Tab** | Welche Registerkarte war in jedem Fenster aktiv |
| **Terminal-Sitzungen** | Der letzte sichtbare Bildschirm des primären Bereichs jedes Terminal-Tabs — nicht dessen Scrollback und nicht die Cursorposition; die Bildschirme weiterer geteilter Bereiche werden nicht gespeichert |

!!! note
    KI-Ergebnis-Tabs und Werkzeug-Tabs (Manager als Tabs geöffnet) werden nicht mit Projekten gespeichert. Sie bleiben nur in der aktuellen Sitzung und gehen verloren, wenn Sie den Tab schließen oder ein Projekt öffnen. SFTP-Manager-Tabs sind in diesem Sinne keine Werkzeug-Tabs: sie werden gespeichert, siehe [SFTP-Manager-Tabs](#sftp-manager-tabs).

## Automatische Wiederverbindung

Wenn **Auto-Reconnect** aktiviert ist, führt KorTTY automatisch Folgendes durch:

- Stellt alle Fenster mit ihrer gespeicherten Geometrie (Position und Größe) wieder her.
- Verbindet jede SSH-Registerkarte erneut mit den ursprünglichen Verbindungseinstellungen
- Zeigt jeden Tab des Terminals mit dem gespeicherten Bildschirm, der über die neue Sitzung abgedunkelt ist, eingerahmt von einer *Wiederhergestellten Ausgabe aus* Zeile mit dem Datum, an dem das Projekt gespeichert wurde, und einer *Ende der wiederhergestellten Ausgabe* Zeile. Der Text wird nur in das lokale Terminal geschrieben und niemals an den Server gesendet: Er erreicht die Remote-Shell oder deren Befehlsverlauf nicht, und ein Sitzungsjournal, das mit der Verbindung beginnt, zeichnet ihn nicht auf (ein Journal, das Sie später aktivieren, importiert den Scrollback, der dann die wiederhergestellten Zeilen enthält). Steuerzeichen und Escape-Sequenzen werden daraus entfernt, bevor er angezeigt wird.
- Öffnet jeden SFTP-Manager-Tab erneut in den lokalen und entfernten Ordnern, die er beim Speichern des Projekts zeigte
- Stellt den aktiven Tab- und Dashboard-Status wieder her

Wenn **Automatisches Wiederverbinden** deaktiviert ist, werden Terminal-Tabs nicht wiederhergestellt: Das Öffnen des Projekts wendet die gespeicherte Fenstergeometrie an und öffnet erneut lokale Datei-Editor- und Bildbetrachter-Tabs, während Terminal-, SFTP- und Remote-Datei-Tabs übersprungen werden. Öffnen Sie diese Verbindungen erneut im Connection-Manager.

### SFTP-Manager-Tabs

Mit **Automatisches Wiederverbinden** verbindet sich jeder gespeicherte SFTP-Manager-Tab erneut mit seiner Verbindung und öffnet die lokalen und entfernten Ordner, die er beim Speichern des Projekts angezeigt hat. Ohne **Automatisches Wiederverbinden** werden SFTP-Manager-Tabs nicht wieder geöffnet.

- Ein lokaler Ordner, der nicht mehr existiert, öffnet stattdessen Ihren Home-Ordner.
- Ein Remote-Ordner, der nicht mehr existiert, öffnet Ihr Login-Verzeichnis auf dem Server und die Statusleiste zeigt **Remote-Ordner** *Pfad* **existiert nicht mehr; zeigt das Home-Verzeichnis**. Ein Remote-Ordner, der existiert, aber nicht gelesen werden kann, zum Beispiel wegen fehlender Rechte, zeigt den üblichen Fehler und öffnet ebenfalls das Login-Verzeichnis.
- Projekte, die von früheren Versionen gespeichert wurden, bezogen sich auf SFTP-Manager-Tabs anhand des Namens der Verbindung. Solch ein Tab wird wiederhergestellt, wenn genau eine Verbindung diesen Namen hat; wenn mehrere Verbindungen denselben Namen teilen, wird der Tab übersprungen und das Log erklärt warum. Speichern Sie das Projekt erneut, um die Referenz nach ID zu speichern.
- Ein Remote-Bildbetrachter-Tab wird mit der Verbindung gespeichert, von der er geöffnet wurde, auch wenn mehrere SFTP-Manager-Tabs offen sind oder sein SFTP-Manager-Tab bereits geschlossen ist. Beim Wiederherstellen öffnet er seine eigene SFTP-Verbindung, mit demselben SSH-Schlüssel und derselben Sprungserver-Verbindung wie der SFTP-Manager, und diese Verbindung schließt sich, wenn Sie den Tab schließen.

### Datei-Editor-Tabs

Ein gespeicherter Tab des Datei-Editors öffnet seine Datei erneut: eine lokale Datei, wenn sie noch existiert, und – mit **Automatisches Wiederverbinden** – eine entfernte Datei über eine eigene SFTP-Verbindung, die geschlossen wird, wenn Sie den Tab schließen. Das Projekt speichert, welche Datei geöffnet war, nicht ihre ungespeicherten Änderungen.

Ein Datei-Editor-Tab mit ungespeicherten Änderungen zeigt `*` nach seinem Namen, und jede Art des Schließens fragt zuerst **Speichern**, **Verwerfen** oder **Abbrechen**: die Schaltfläche zum Schließen des Tabs, die Schaltfläche **Schließen** im Editor oder ++ctrl+w++ (++cmd+w++ auf macOS) im Editor, *Datei > Tab schließen*, *Datei > Alle Tabs schließen*, das Öffnen eines Projekts, das Schließen des Fensters und das Beenden von korTTY. **Abbrechen** lässt den Tab offen; wenn die Frage aus *Alle Tabs schließen*, dem Öffnen eines Projekts oder dem Schließen des Fensters kam, wird nichts geschlossen. Wenn das Speichern fehlschlägt, zum Beispiel weil die SFTP-Verbindung fehlt, zeigt korTTY den Fehler an und lässt den Tab mit Ihren Änderungen offen. Wenn mehrere Tabs ungespeicherte Änderungen haben, wählt korTTY jeden Tab aus, bevor es danach fragt.

## Projektdateispeicherung

Ein Projekt ist eine einfache XML-Datei mit der `.kortty`-Erweiterung. Jedes Projekt enthält:

- Metadaten (Name, Beschreibung, Erstellungs-/Änderungszeitstempel)
- Vollständiger Fenster- und Tab-Status
- Verbindungsreferenzen (nach Verbindungs-ID, daher muss die Verbindung im Connection-Manager existieren)
- Sichtbarkeit und Layout des Dashboards

Der gespeicherte Bildschirminhalt ist nicht Teil der `.kortty`-Datei. Er wird separat als eine gzip-Datei pro Terminal-Tab, `~/.kortty/history/<session-id>.history.gz`, gespeichert, die das Projekt per Dateiname referenziert – ein Projektdokument, das Sie teilen, trägt daher das Layout, jedoch nicht den Bildschirminhalt. korTTY liest, schreibt oder löscht ausschließlich reine Dateinamen innerhalb von `~/.kortty/history/`; ein Projekt, dessen Referenz auf einen anderen Ort zeigt, öffnet sich ohne diesen Bildschirminhalt.

## Anwendungsfälle

Projekte sind nützlich für:

- **Kontextwechsel** – Speichern Sie ein „Produktionssystem“-Projekt, ein „Entwicklungs“-Projekt und ein „Test“-Projekt; Öffnen Sie das, das Sie benötigen
- **Teamübergaben** – Teilen Sie Projekte mit Kollegen, um identische Arbeitsbereichslayouts und -verbindungen einzurichten
- **Layouts mit mehreren Fenstern** – Speichern Sie ein komplexes Setup über mehrere Monitorfenster hinweg und stellen Sie es sofort wieder her
- **Sitzungswiederherstellung** – Stellen Sie schnell Ihre letzte bekannte Konfiguration wieder her, wenn die App abstürzt oder Sie Tabs versehentlich schließen
