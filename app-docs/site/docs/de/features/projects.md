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
| **Registerkarten** | Alle Terminal-Registerkarten in jedem Fenster, einschließlich Split-Pane-Konfigurationen |
| **Verbindungen** | Ein Verweis auf die gespeicherte Verbindung jedes Tabs, anhand seiner Verbindungs-ID |
| **Dashboard** | Sichtbarkeit des Armaturenbretts und Position der Trennwand |
| **Aktiver Tab** | Welche Registerkarte war in jedem Fenster aktiv |
| **Terminal-Sitzungen** | Der letzte sichtbare Bildschirm des primären Bereichs jedes Terminal-Tabs — nicht dessen Scrollback und nicht die Cursorposition; die Bildschirme weiterer geteilter Bereiche werden nicht gespeichert |

!!! note
    KI-Ergebnisregisterkarten werden nicht mit Projekten gespeichert. Sie bleiben nur in der aktuellen Sitzung bestehen und gehen verloren, wenn Sie die Registerkarte schließen oder ein Projekt öffnen.

## Automatische Wiederverbindung

Wenn **Auto-Reconnect** aktiviert ist, führt KorTTY automatisch Folgendes durch:

- Stellt alle Fenster mit ihrer gespeicherten Geometrie (Position und Größe) wieder her.
- Verbindet jede SSH-Registerkarte erneut mit den ursprünglichen Verbindungseinstellungen
- Zeigt jeden Tab des Terminals mit dem gespeicherten Bildschirm, der über die neue Sitzung abgedunkelt ist, eingerahmt von einer *Wiederhergestellten Ausgabe aus* Zeile mit dem Datum, an dem das Projekt gespeichert wurde, und einer *Ende der wiederhergestellten Ausgabe* Zeile. Der Text wird nur in das lokale Terminal geschrieben und niemals an den Server gesendet: Er erreicht die Remote-Shell oder deren Befehlsverlauf nicht, und ein Sitzungsjournal, das mit der Verbindung beginnt, zeichnet ihn nicht auf (ein Journal, das Sie später aktivieren, importiert den Scrollback, der dann die wiederhergestellten Zeilen enthält). Steuerzeichen und Escape-Sequenzen werden daraus entfernt, bevor er angezeigt wird.
- Stellt den aktiven Tab- und Dashboard-Status wieder her

Wenn **Automatisches Wiederverbinden** deaktiviert ist, werden Terminal-Tabs nicht wiederhergestellt: Das Öffnen des Projekts wendet die gespeicherte Fenstergeometrie an und öffnet erneut lokale Datei-Editor- und Bildbetrachter-Tabs, während Terminal-, SFTP- und Remote-Datei-Tabs übersprungen werden. Öffnen Sie diese Verbindungen erneut im Connection-Manager.

## Projektdateispeicherung

Ein Projekt ist eine einfache XML-Datei mit der `.kortty`-Erweiterung. Jedes Projekt enthält:

- Metadaten (Name, Beschreibung, Erstellungs-/Änderungszeitstempel)
- Vollständiger Fenster- und Tab-Status
- Verweise auf Verbindungen (nach Verbindungs-ID, daher muss die Verbindung im Connection-Manager existieren)
- Sichtbarkeit und Layout des Dashboards

Der gespeicherte Bildschirminhalt ist nicht Teil der `.kortty`-Datei. Er wird separat als eine gzip-Datei pro Terminal-Tab, `~/.kortty/history/<session-id>.history.gz`, gespeichert, die das Projekt per Dateiname referenziert – ein Projektdokument, das Sie teilen, trägt daher das Layout, jedoch nicht den Bildschirminhalt. korTTY liest, schreibt oder löscht ausschließlich reine Dateinamen innerhalb von `~/.kortty/history/`; ein Projekt, dessen Referenz auf einen anderen Ort zeigt, öffnet sich ohne diesen Bildschirminhalt.

## Anwendungsfälle

Projekte sind nützlich für:

- **Kontextwechsel** – Speichern Sie ein „Produktionssystem“-Projekt, ein „Entwicklungs“-Projekt und ein „Test“-Projekt; Öffnen Sie das, das Sie benötigen
- **Teamübergaben** – Teilen Sie Projekte mit Kollegen, um identische Arbeitsbereichslayouts und -verbindungen einzurichten
- **Layouts mit mehreren Fenstern** – Speichern Sie ein komplexes Setup über mehrere Monitorfenster hinweg und stellen Sie es sofort wieder her
- **Sitzungswiederherstellung** – Stellen Sie schnell Ihre letzte bekannte Konfiguration wieder her, wenn die App abstürzt oder Sie Tabs versehentlich schließen
