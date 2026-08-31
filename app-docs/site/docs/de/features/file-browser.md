---
title: Dateibrowser
---

# Dateibrowser

Der Dateibrowser ist eine andockbare Seitenleiste, die neben dem Terminal das **lokale** Dateisystem durchsucht. Sie zeigt einen Verzeichnisbaum mit Navigationsleiste, Pfadleiste, Filter, typabhängigen Symbolen und einer Statuszeile. (Zum Durchsuchen eines **entfernten** Servers über SFTP verwenden Sie den [SFTP-Dateimanager](sftp.md).)

## Öffnen und Andocken

Blenden Sie die Leiste über die Menüleiste ein:

| Menüpunkt | Tastenkürzel |
|-----------|----------|
| **Ansicht → Dateibrowser → Links anzeigen** | ++shift+cmd+b++ / ++shift+ctrl+b++ |
| **Ansicht → Dateibrowser → Rechts anzeigen** | ++shift+cmd+r++ / ++shift+ctrl+r++ |

Selecting the item again hides the panel. A draggable divider resizes it (160–420 px). The panel's **position, width, "show hidden" state and last directory are remembered across restarts**.

## Navigation

Werkzeugleiste und Pfadleiste bestimmen, wo der Baum verwurzelt ist:

| Bedienelement | Aktion |
|---------|--------|
| Zurück / Vor | Zu einem zuvor besuchten Verzeichnis zurückkehren |
| Hoch | Den Baum im übergeordneten Verzeichnis verwurzeln (auch oberhalb Ihres Home-Verzeichnisses) |
| Home | Den Baum im Home-Verzeichnis verwurzeln |
| Aktualisieren | Das aktuelle Verzeichnis neu laden; aufgeklappte Ordner bleiben offen |
| Neuer Ordner / Neue Datei | Ein Objekt im ausgewählten (oder aktuellen) Verzeichnis anlegen |
| Sortieren | Sortierschlüssel (Name, Größe, Änderungsdatum) und Richtung (aufsteigend / absteigend) wählen |
| Versteckte Dateien | Punktdateien ein- oder ausblenden |

Die **Pfadleiste** nimmt einen eingegebenen Pfad an und verwurzelt den Baum dort. Ein führendes `~` wird zum Home-Verzeichnis erweitert; absolute Pfade außerhalb von Home sind erlaubt. Das **Filterfeld** darunter beschränkt die Anzeige auf Einträge, deren Name den eingegebenen Text enthält (ohne Beachtung der Groß-/Kleinschreibung).

Verzeichnisse werden im Hintergrund geladen, sodass ein großes oder über das Netzwerk eingebundenes Verzeichnis das Fenster nicht mehr einfriert; während des Ladens erscheint kurz eine Ladeanzeige.

## Tastenkürzel

Wenn der Baum den Fokus hat:

| Tastenkürzel | Aktion |
|----------|--------|
| ++enter++ | Öffnen Sie eine Datei oder erweitern/reduzieren Sie einen Ordner |
| ++f2++ | Benennen Sie das ausgewählte Element inline um |
| ++backspace++ | Gehen Sie zum übergeordneten Verzeichnis |
| ++cmd+r++ / ++ctrl+r++ | Aktualisieren |
| ++delete++ oder ++cmd+backspace++ | Löschen Sie die Auswahl (wählen Sie „Papierkorb“ oder „Permanent“). |
| ++cmd+c++ / ++ctrl+c++, ++cmd+v++ / ++ctrl+v++ | Dateien kopieren und einfügen |
| ++cmd+f++ / ++ctrl+f++ | Springen Sie zum Filterfeld |

## Dateioperationen

Rechtsklick auf einen Eintrag öffnet das vollständige Menü:

- **Öffnen** – Öffnen Sie die Datei mit der Standardanwendung des Betriebssystems.
- **Im Snippet-Editor öffnen** – Laden Sie eine Textdatei (bis zu 10 MB) in den [Snippet-Editor](snippets.md).
- **Umbenennen** – Inline umbenennen; Ein Namenskonflikt wird durch Anhängen von ` (2)`, ` (3)`, … gelöst.
- **Kopieren** / **Ausschneiden** / **Einfügen** – Verschieben oder Kopieren im Browser.
- **Pfad kopieren** – Kopieren Sie den absoluten Pfad des Elements in die Zwischenablage.
- **Löschen** – eine Bestätigungsaufforderung bietet **In den Papierkorb verschieben** oder **Endgültig löschen**. Das Verschieben in den Papierkorb ist rückgängig zu machen; Die dauerhafte Löschung kann nicht rückgängig gemacht werden. Auf einem System ohne Papierkorb wird nur die dauerhafte Löschung angeboten. Der Löschvorgang läuft im Hintergrund, sodass das Fenster bei einem großen Ordner nicht einfriert.
- **Neuer Ordner** / **Neue Datei** – ein Element erstellen.
- **Eigentümer/Gruppe/Berechtigungen festlegen** – Eigentümer und POSIX-Berechtigungen ändern (sofern das Dateisystem dies unterstützt).
- **Archiv** – Packen Sie die Auswahl in ein `ZIP`-, `TAR`- oder `TAR.GZ`-Archiv.
- **Details** – Typ, Größe, Pfad, Änderungszeit und Berechtigungen anzeigen.

Jede Zeile zeigt ein **typabhängiges Symbol** (Ordner, Code, Bild, Archiv, Dokument oder ausführbare Datei) mit einer Markierung für symbolische Verknüpfungen. Die Fußzeile nennt die Anzahl der Ordner und Dateien im aktuellen Verzeichnis sowie die Anzahl der ausgewählten Einträge.

## Ziehen und Ablegen

Ziehen Sie Dateien aus dem Browser **hinaus** auf eine andere Anwendung, um sie zu kopieren, und Dateien in den Browser **hinein**, um sie in einen Ordner zu kopieren oder zu verschieben. Ablegen auf einem Ordner zielt auf diesen Ordner; Ablegen an anderer Stelle zielt auf das aktuelle Verzeichnis. Namenskonflikte werden mit einem `(2)`-Suffix aufgelöst, statt zu überschreiben.
