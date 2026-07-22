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

Erneutes Auswählen des Punktes blendet die Leiste wieder aus. Ein ziehbarer Trenner ändert ihre Breite (160–420 px). **Position, Breite, der Zustand „Versteckte Dateien" und das zuletzt geöffnete Verzeichnis bleiben über Neustarts erhalten.**

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

- **Öffnen** – die Datei mit der Standardanwendung des Betriebssystems öffnen.
- **Im Snippet-Editor öffnen** – eine Textdatei (bis 10 MB) in den [Snippet-Editor](snippets.md) laden.
- **Umbenennen** – direkt umbenennen; ein Namenskonflikt wird durch Anhängen von ` (2)`, ` (3)`, … aufgelöst.
- **Kopieren** / **Ausschneiden** / **Einfügen** – innerhalb des Browsers verschieben oder kopieren.
- **Pfad kopieren** – den absoluten Pfad des Objekts in die Zwischenablage kopieren.
- **Löschen** – eine Bestätigungsaufforderung bietet **In den Papierkorb verschieben** oder **Endgültig löschen**. Das Verschieben in den Papierkorb ist rückgängig zu machen; Die dauerhafte Löschung kann nicht rückgängig gemacht werden. Auf einem System ohne Papierkorb wird nur die dauerhafte Löschung angeboten. Der Löschvorgang läuft im Hintergrund, sodass das Fenster bei einem großen Ordner nicht einfriert.
- **Neuer Ordner** / **Neue Datei** – ein Objekt anlegen.
- **Eigentümer / Gruppe / Berechtigungen setzen** – Eigentümer und POSIX-Berechtigungen ändern (sofern das Dateisystem dies unterstützt).
- **Archiv** – die Auswahl in ein `ZIP`-, `TAR`- oder `TAR.GZ`-Archiv packen.
- **Details** – Typ, Größe, Pfad, Änderungszeit und Berechtigungen anzeigen.

Jede Zeile zeigt ein **typabhängiges Symbol** (Ordner, Code, Bild, Archiv, Dokument oder ausführbare Datei) mit einer Markierung für symbolische Verknüpfungen. Die Fußzeile nennt die Anzahl der Ordner und Dateien im aktuellen Verzeichnis sowie die Anzahl der ausgewählten Einträge.

## Ziehen und Ablegen

Ziehen Sie Dateien aus dem Browser **hinaus** auf eine andere Anwendung, um sie zu kopieren, und Dateien in den Browser **hinein**, um sie in einen Ordner zu kopieren oder zu verschieben. Ablegen auf einem Ordner zielt auf diesen Ordner; Ablegen an anderer Stelle zielt auf das aktuelle Verzeichnis. Namenskonflikte werden mit einem `(2)`-Suffix aufgelöst, statt zu überschreiben.
