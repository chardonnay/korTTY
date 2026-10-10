---
title: Dateibrowser
---

# Dateibrowser

Der Dateibrowser ist eine andockbare Seitenleiste, die Ihr **lokales** Dateisystem neben dem Terminal durchsucht. Er ist wie die Seitenleiste für Remote-Dateien des Terminals aufgebaut: eine **Lokale Dateien**-Titelleiste mit den Aktionen, der aktuelle Ordner als klickbare Pfadsegmente, ein Filter und eine Auflistung eines Ordners mit den Spalten **Name**, **Größe** und **Geändert**, typabhängige Symbole und eine Statusleiste. (Zum Durchsuchen eines **Remote**-Servers über SFTP verwenden Sie stattdessen den [SFTP-Dateimanager](sftp.md).)

## Öffnen und Andocken

Blenden Sie die Leiste über die Menüleiste ein:

| Menüpunkt | Tastenkürzel |
|-----------|----------|
| **Ansicht → Dateibrowser → Links anzeigen** | ++shift+cmd+b++ / ++shift+ctrl+b++ |
| **Ansicht → Dateibrowser → Rechts anzeigen** | ++shift+cmd+r++ / ++shift+ctrl+r++ |

Wird der Eintrag erneut ausgewählt oder der **✕**-Button in der Titelleiste des Panels gedrückt, wird das Panel ausgeblendet. Ein verschiebbarer Trenner passt die Größe an (280–900 px, standardmäßig 380 px); das Fenster behält diese Breite bei, anstatt das Panel zusammenzudrücken, wenn das Terminal Platz braucht. **Position, Breite, Einstellung „Versteckte Dateien anzeigen" und letztes Verzeichnis des Panels bleiben auch nach Neustarts erhalten.**

## Navigation

Die Liste zeigt jeweils nur einen Ordner an. **Doppelklicken** Sie auf einen Ordner (oder wählen Sie ihn aus und drücken Sie ++enter++), um in ihn zu wechseln; die **..**-Zeile oben führt in den übergeordneten Ordner. Die Schaltflächen in der Titelleiste:

| Bedienelement | Aktion |
|---------|--------|
| Zurück / Vor | Zu einem zuvor besuchten Verzeichnis zurückkehren |
| Hoch | Übergeordneten Ordner anzeigen (kann über das Home-Verzeichnis hinaufgehen) |
| Home | Zeigt Ihr Home-Verzeichnis |
| Aktualisieren | Lädt den angezeigten Ordner neu und behält die Auswahl bei |
| Neuer Ordner / Neue Datei | Erstellen Sie ein Element im angezeigten Ordner |
| Sortieren | Wählen Sie den Sortierschlüssel (Name, Größe, Änderungsdatum) und die Richtung (Aufsteigend / Absteigend); ein Klick auf die Spaltenüberschrift **Name**, **Größe** oder **Geändert** bewirkt dasselbe, und ein zweiter Klick kehrt die Richtung um. Versteckte Einträge (Namen, die mit einem Punkt beginnen) bleiben immer oben, danach Ordner vor Dateien |
| Versteckte Dateien | Punktdateien ein- oder ausblenden |
| ✕ | Panel ausblenden |

Unter der Titelleiste zeigt der **Pfad** den Ordner als Segmente, beginnend bei `~` innerhalb Ihres Home-Verzeichnisses oder bei der Dateisystemwurzel außerhalb davon; klicken Sie auf ein Segment, um in diesen Ordner zu wechseln. Klicken Sie neben die Segmente oder drücken Sie ++cmd+l++ / ++ctrl+l++, um stattdessen einen Pfad einzugeben: ++enter++ wechselt dorthin, ++escape++ kehrt zu den Segmenten zurück. Ein führendes `~` wird zu Ihrem Home-Verzeichnis expandiert; absolute Pfade außerhalb des Home-Verzeichnisses sind zulässig. Das **Filter**-Feld darunter schränkt die Liste auf Einträge ein, deren Name den eingegebenen Text enthält (Groß-/Kleinschreibung wird nicht unterschieden).

Verzeichnisse werden im Hintergrund geladen, sodass ein großes oder über das Netzwerk eingebundenes Verzeichnis das Fenster nicht mehr einfriert; während des Ladens erscheint kurz eine Ladeanzeige.

## Tastenkürzel

Wenn die Liste den Fokus hat:

| Tastenkürzel | Aktion |
|----------|--------|
| ++enter++ | Eine Datei öffnen oder in einen Ordner wechseln |
| ++f2++ | Benennen Sie das ausgewählte Element inline um |
| ++backspace++ | Gehen Sie zum übergeordneten Verzeichnis |
| ++cmd+r++ / ++ctrl+r++ | Aktualisieren |
| ++delete++ oder ++cmd+backspace++ | Löschen Sie die Auswahl (wählen Sie „Papierkorb“ oder „Permanent“). |
| ++cmd+c++ / ++ctrl+c++, ++cmd+v++ / ++ctrl+v++ | Dateien kopieren und einfügen |
| ++cmd+f++ / ++ctrl+f++ | Springen Sie zum Filterfeld |
| ++cmd+l++ / ++ctrl+l++ | Geben Sie einen Pfad ein, um dorthin zu navigieren |

## Dateioperationen

Rechtsklick auf einen Eintrag öffnet das vollständige Menü:

- **Öffnen** — die Datei mit der Standardanwendung des Betriebssystems öffnen oder in den gewählten Ordner wechseln.
- **Im Snippet-Editor öffnen** – Laden Sie eine Textdatei (bis zu 10 MB) in den [Snippet-Editor](snippets.md).
- **Umbenennen** – Inline umbenennen; Ein Namenskonflikt wird durch Anhängen von ` (2)`, ` (3)`, … gelöst.
- **Kopieren** / **Ausschneiden** / **Einfügen** – Verschieben oder Kopieren im Browser.
- **Pfad kopieren** – Kopieren Sie den absoluten Pfad des Elements in die Zwischenablage.
- **Löschen** – eine Bestätigungsaufforderung bietet **In den Papierkorb verschieben** oder **Endgültig löschen**. Das Verschieben in den Papierkorb ist rückgängig zu machen; Die dauerhafte Löschung kann nicht rückgängig gemacht werden. Auf einem System ohne Papierkorb wird nur die dauerhafte Löschung angeboten. Der Löschvorgang läuft im Hintergrund, sodass das Fenster bei einem großen Ordner nicht einfriert.
- **Neuer Ordner** / **Neue Datei** – ein Element erstellen.
- **Eigentümer/Gruppe/Berechtigungen festlegen** – Eigentümer und POSIX-Berechtigungen ändern (sofern das Dateisystem dies unterstützt).
- **Archiv** – Packen Sie die Auswahl in ein `ZIP`-, `TAR`- oder `TAR.GZ`-Archiv.
- **Details** – Typ, Größe, Pfad, Änderungszeit und Berechtigungen anzeigen.

Jede Zeile zeigt ein **typabhängiges Symbol** (Ordner, Code, Bild, Archiv, Dokument oder ausführbare Datei), ein Abzeichen für symbolische Links, die Größe (`<DIR>` für Ordner) und den Zeitpunkt der letzten Änderung. Ein Name, der für die Spalte zu lang ist, endet in `...`; bewegen Sie den Zeiger darauf, um den vollständigen Namen in einem Tooltip anzuzeigen, oder wählen Sie den Eintrag aus (mit einem Klick oder den Pfeiltasten), um ihn direkt unter der Zeile zu sehen. Die Fußzeile meldet, wie viele Ordner und Dateien der angezeigte Ordner enthält und wie viele Einträge ausgewählt sind.

## Ziehen und Ablegen

Ziehen Sie Dateien **aus** dem Browser in eine andere Anwendung, um sie zu kopieren, und ziehen Sie Dateien **in** den Browser, um sie in einen Ordner zu kopieren oder zu verschieben. Ein Ablegen auf eine Ordnerzeile macht diesen Ordner zum Ziel; ein Ablegen an anderer Stelle macht den angezeigten Ordner zum Ziel. Namenskonflikte werden mit einem `(2)`-Zusatz aufgelöst, statt die Datei zu überschreiben.
