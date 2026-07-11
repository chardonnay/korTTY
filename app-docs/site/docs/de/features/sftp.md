---
title: SFTP-Dateimanager
---

# SFTP-Dateimanager

Der integrierte SFTP-Manager bietet einen grafischen Dateimanager zum Übertragen von Dateien zwischen Ihrem lokalen Computer und Remote-Servern über SFTP. Es verfügt über ein Dual-Panel-Layout, vollständige Unterstützung für Dateivorgänge und eine nahtlose Integration mit dem Snippet-Editor für die Fernbearbeitung von Dateien.

## SFTP-Manager öffnen

Sie können den SFTP-Manager auf zwei Arten öffnen:

- **Menü:** Extras > SFTP-Manager öffnen
- **Dashboard:** Klicken Sie mit der rechten Maustaste auf eine Verbindung > „SFTP-Manager öffnen“"

Wenn die Verbindung einen temporären SSH-Schlüssel verwendet, der abgelaufen ist, werden Sie aufgefordert, einen neuen Schlüssel einzugeben, bevor die Verbindung fortgesetzt werden kann.

## Schnittstelle

Der SFTP-Manager verwendet ein **Zwei-Panel-Layout** für eine einfache Dateiverwaltung nebeneinander:

| Linker Bereich (Lokal) | Rechtes Panel (Remote) |
|----|---|
| Lokale Dateien durchsuchen | Remote-Dateien durchsuchen |
| Auf Remote hochladen | Auf lokal herunterladen |

### Sortierbare Spalten

In beiden Panels werden die gleichen Spalten angezeigt, die alle durch Klicken auf die Spaltenüberschrift sortiert werden können:

| Spalte | Beschreibung |
|--------|---|
| **Name** | Datei- oder Verzeichnisname |
| **Typ** | Verzeichnis- (📁) oder Datei- (📄) Indikator |
| **Größe** | Dateigröße im für Menschen lesbaren Format (Verzeichnisse zeigen —) |
| **Datum** | Datum und Uhrzeit der letzten Änderung |
| **Benutzer** | Besitzername (lokal: vom Dateisystem; entfernt: von SFTP oder UID) |
| **Gruppe** | Gruppenname (lokal: vom Dateisystem; entfernt: von SFTP oder GID) |
| **Berechtigungen** | Berechtigungen im Unix-Stil (z. B. `rwxr-xr-x`) |

## Standardsortierreihenfolge

Standardmäßig werden Dateien in der folgenden Reihenfolge nach der Spalte **Typ** sortiert:

1. **Übergeordnetes Verzeichnis** (`..`) – immer oben
2. **Verzeichnisse, die mit „.“** beginnen (z. B. `.git`, `.config`) – alphabetisch
3. **Andere Verzeichnisse** – alphabetisch
4. **Dateien, die mit „.“** beginnen (z. B. `.bashrc`) – alphabetisch
5. **Alle anderen Dateien** – alphabetisch

Klicken Sie auf eine beliebige Spaltenüberschrift, um nach dieser Spalte zu sortieren. Durch erneutes Klicken auf die Spalte „Typ“ wird zwischen aufsteigender und absteigender Reihenfolge umgeschaltet.

## Dateioperationen

Der SFTP-Manager unterstützt eine ganze Reihe von Dateioperationen:

| Betrieb | Wie |
|-----------|-----|
| **Hochladen** | Wählen Sie die lokale(n) Datei(en) aus und klicken Sie auf Hochladen (oder Drag-and-Drop) |
| **Herunterladen** | Wählen Sie die Remote-Datei(en) aus und klicken Sie auf Herunterladen |
| **Löschen** | Wählen Sie die Datei(en) aus und klicken Sie auf Löschen |
| **Umbenennen** | Wählen Sie eine Datei aus und klicken Sie auf Umbenennen |
| **Kopieren** | Dateien innerhalb desselben Panels kopieren |
| **Im Snippet-Editor bearbeiten** | Wählen Sie genau eine lokale oder Remote-Datei aus und verwenden Sie dann das Symbolleistenmenü *Bearbeiten* oder das Kontextmenü mit der rechten Maustaste |
| **Verzeichnis erstellen** | Klicken Sie in einem der beiden Fenster auf „Neuer Ordner“ |
| **ZIP-Datei erstellen** | Wählen Sie mehrere Dateien/Verzeichnisse aus und klicken Sie auf „ZIP erstellen“ |
| **Besitzer/Berechtigungen festlegen** | Datei(en) auswählen, Kontextmenü oder Schaltfläche verwenden. Separate Felder für Benutzer-, Gruppen- und Oktalberechtigungen (z. B. 755) |

### Bearbeiten von Dateien mit dem Snippet-Editor

Die Aktion **Im Snippet-Editor bearbeiten** ist nur für eine einzelne ausgewählte Datei aktiviert. Es ist deaktiviert für:

- Ordner
- Der Eintrag im übergeordneten Verzeichnis (`..`)
- Mehrfachauswahl

Lokale Dateien werden direkt aus dem lokalen Dateisystem gelesen; Remote-Dateien werden über die aktive SFTP-Sitzung in den Editor heruntergeladen.

Wenn die Datei im Snippet-Editor geöffnet wird, bleibt die vollständige Symbolleiste verfügbar, einschließlich:

- Formatierung und Syntaxprüfung
- Editorprofile und Styling
- Zeilennummern und Zeilenumbruch
- Konfigurierte KI-Aktionen

Die Dateimodus-Schaltflächen bieten folgende Speicheroptionen:

- **Datei überschreiben** – schreibt den aktuellen Editorinhalt zurück in die ursprüngliche lokale oder Remote-Datei
- **Speichern unter...** – schreibt eine neue lokale Datei über eine Dateiauswahl oder fordert für Remote-Dateien zur Eingabe eines neuen Dateinamens im selben Remote-Verzeichnis auf
- **Als Snippet speichern** – speichert den aktuellen Inhalt als neues Snippet Manager-Snippet, ohne die Quelldatei als gespeichert zu markieren

![SFTP dual-panel file manager](../assets/screenshots/sftp/sftp-manager.png)

## Suchen

Beide Panels unterstützen die **Glob-Mustersuche** mit dem Platzhalter `*`. Zum Beispiel:

- `*.log` findet alle Protokolldateien im aktuellen Verzeichnis
- `*.{py,sh}` findet Python- und Shell-Dateien (wenn Ihre Shell die Klammererweiterung unterstützt)
- `backup*` findet alle Dateien, die mit „backup"“ beginnen

Geben Sie das Muster in das Suchfeld ein, um angezeigte Dateien schnell zu filtern, ohne das aktuelle Verzeichnis zu verlassen.

---

