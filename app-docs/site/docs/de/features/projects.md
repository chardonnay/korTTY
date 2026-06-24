---
title: Projekte (Arbeitsbereiche)
---

# Projekte (Arbeitsbereiche)

Projekte speichern und stellen Ihren gesamten Arbeitsbereichsstatus wieder her – alle geöffneten Fenster, Registerkarten, SSH-Verbindungen und Terminalsitzungen. Dadurch können Sie schnell zwischen verschiedenen Arbeitskontexten wechseln, ohne Fenster manuell neu zu verbinden oder neu zu positionieren.

## Ein Projekt speichern

1. Öffnen Sie *Datei > Projekt speichern* oder drücken Sie ++Strg+s++ (++cmd+s++ unter macOS).
2. Geben Sie einen **Namen** und optional eine **Beschreibung** für das Projekt ein.
3. Konfigurieren Sie **Auto-Reconnect**:
– Wenn diese Option aktiviert ist, werden beim Öffnen des Projekts alle gespeicherten SSH-Sitzungen automatisch wieder verbunden.
– Bei Deaktivierung werden Fenster und Registerkarten wiederhergestellt, Sie müssen die Verbindung jedoch manuell wiederherstellen.
4. Klicken Sie auf *Speichern*.

Projekte werden als `.kortty`-Dateien in `~/.kortty/projects/` gespeichert.

## Ein Projekt öffnen

1. Öffnen Sie *Datei > Projekt öffnen* oder drücken Sie ++Strg+o++ (++cmd+o++ unter macOS).
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
| **Verbindungen** | Die gespeicherten Verbindungsnamen für jede Registerkarte |
| **Dashboard** | Sichtbarkeit des Armaturenbretts und Position der Trennwand |
| **Aktiver Tab** | Welche Registerkarte war in jedem Fenster aktiv |
| **Terminalsitzungen** | Sitzungsstatus einschließlich Cursorposition und Scrollback (sofern von der Sitzung unterstützt) |

!!! Notiz
AI-Ergebnisregisterkarten werden nicht mit Projekten gespeichert. Sie bleiben nur in der aktuellen Sitzung bestehen und gehen verloren, wenn Sie die Registerkarte schließen oder ein Projekt öffnen.

## Automatische Wiederverbindung

Wenn **Auto-Reconnect** aktiviert ist, führt KorTTY automatisch Folgendes durch:

- Stellt alle Fenster mit ihrer gespeicherten Geometrie (Position und Größe) wieder her.
– Verbindet jede SSH-Registerkarte erneut mit den ursprünglichen Verbindungseinstellungen
- Stellt den aktiven Tab- und Dashboard-Status wieder her

Wenn **Auto-Reconnect** deaktiviert ist, werden Fenster und Registerkarten wiederhergestellt, Sie müssen jedoch jede Registerkarte manuell neu verbinden, indem Sie darauf klicken oder *Reconnect* aus dem Kontextmenü verwenden.

## Projektdateispeicherung

Projekte werden in `~/.kortty/projects/` als komprimierte `.kortty`-Dateien gespeichert. Jedes Projekt beinhaltet:

- Metadaten (Name, Beschreibung, Erstellungs-/Änderungszeitstempel)
- Vollständiger Fenster- und Tab-Status
- Verbindungsreferenzen (nach Namen)
- Sichtbarkeit und Layout des Dashboards

## Anwendungsfälle

Projekte sind nützlich für:

- **Kontextwechsel** – Speichern Sie ein „Produktionssystem“-Projekt, ein „Entwicklungs“-Projekt und ein „Test“-Projekt; Öffnen Sie das, das Sie benötigen
- **Teamübergaben** – Teilen Sie Projekte mit Kollegen, um identische Arbeitsbereichslayouts und -verbindungen einzurichten
- **Mehrfenster-Layouts** – Speichern Sie ein komplexes Setup über mehrere Monitorfenster hinweg und stellen Sie es sofort wieder her
- **Sitzungswiederherstellung** – Stellen Sie schnell Ihre letzte bekannte Konfiguration wieder her, wenn die App abstürzt oder Sie Tabs versehentlich schließen
