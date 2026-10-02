---
title: SFTP-Dateimanager
---

#  Manager für SFTP-Dateien

Der integrierte SFTP-Manager bietet einen grafischen Dateimanager zum Übertragen von Dateien zwischen Ihrem lokalen Computer und Remote-Servern über SFTP. Es verfügt über ein Dual-Panel-Layout, vollständige Unterstützung für Dateivorgänge und eine nahtlose Integration mit dem Snippet-Editor für die Fernbearbeitung von Dateien.

## SFTP-Manager öffnen

Sie können den SFTP-Manager auf zwei Arten öffnen:

- **Menü:** *Verbindungen → SFTP-Client...* (++ctrl+shift+u++, unter macOS ++cmd+shift+u++). Mit einem verbundenen Terminal-Tab vor sich öffnet es für den Server dieses Tabs; andernfalls wählen Sie zuerst eine Verbindung.
- **Dashboard:** Rechtsklick auf eine verbundene Sitzung > **SFTP-Client...**

Wenn die Verbindung einen temporären SSH-Schlüssel verwendet, der abgelaufen ist, werden Sie aufgefordert, einen neuen Schlüssel einzugeben, bevor die Verbindung fortgesetzt werden kann.

## Schnittstelle

Der SFTP-Manager verwendet ein **Zwei-Panel-Layout** für eine einfache Dateiverwaltung nebeneinander:

| Linker Bereich (lokale) | Rechter Bereich (fern) |
|----|---|
| Lokale Dateien durchstöbern | Ferndateien durchstöbern |
| Auf Remote-System hochladen | Auf lokales System herunterladen |

###  Sortierbare Spalten

Beide Paneele zeigen die gleichen Spalten an, von denen alle durch Klicken auf den Spaltenkopf sortierbar sind:

| Spalte | Beschreibung |
|--------|---|
| **Name** | Datei oder Verzeichnisaname |
| **Typ** | Verzeichnis (📁) oder Datei (📄) Hinweis |
| **Größe** | Dateigröße im menschenlesbaren Format (Verzeichnisse zeigen —); die Sortierung verwendet die exakte Byteanzahl, mit Ordnern zuerst |
| **Datum** | Letzte Änderungsdatum und -zeit |
| **Benutzer** | Eigentümername (lokale Umgebung: aus der Dateisystem; remote: aus SFTP oder UID) |
| **Gruppe** | Gruppenname (lokal: vom Dateisystem; remote: von SFTP oder GID) |
| **Berechtigungen** | Berechtigungen im Unix-Stil (z. B. `rwxr-xr-x`) |

## Standardsortierreihenfolge

Standardmäßig werden Dateien in der folgenden Reihenfolge nach der Spalte **Typ** sortiert:

1. **Übergeordnetes Verzeichnis** (`..`) – immer oben
2. **Verzeichnisse, die mit „.“** beginnen (z. B. `.git`, `.config`) – alphabetisch
3. **Andere Verzeichnisse** – alphabetisch
4. **Dateien, die mit „.“** beginnen (z. B. `.bashrc`) – alphabetisch
5. **Alle anderen Dateien** – alphabetisch

Klicken Sie auf eine beliebige Spaltenüberschrift, um nach dieser Spalte zu sortieren. Durch erneutes Klicken der Typ-Spalte wird zwischen aufsteigender und absteigender Reihenfolge umgeschaltet. Das übergeordnete Verzeichnis (`..`) bleibt immer oben, unabhängig von Spalte und Richtung der Sortierung.

## Durchsuchen des Servers

Remote-Ordner werden im Hintergrund gelesen, sodass das Fenster auch bei Ordnern mit Zehntausenden von Einträgen reaktionsfähig bleibt. Während ein Ordner geladen wird, überdeckt ein **Wird geladen…**-Indikator die Remote-Liste. Das Pfadfeld und die Liste wechseln erst in den neuen Ordner, wenn er gelesen wurde; falls das Lesen fehlschlägt (Ordner existiert nicht oder Sie haben keine Rechte), wird ein Fehler angezeigt und der Ordner, in dem Sie sich befanden, bleibt bestehen.

Geben Sie einen Pfad in das Remote-**Pfad**-Feld ein und drücken ++enter++, um dorthin zu gelangen. `~` und `~/…` stehen für Ihr Login-Verzeichnis auf dem Server, und ein Pfad ohne führendes `/` wird relativ zum angezeigten Ordner interpretiert; `..` und `.` werden aufgelöst, sodass `../logs` den Geschwisterordner `logs` öffnet.

## Verbindung verloren

Wenn der Server oder das Netzwerk die Sitzung beendet, zeigt die Statusleiste **Getrennt von** *host* mit einer Schaltfläche **Neu verbinden**, und die Remote-Aktionen sind deaktiviert. Wenn die Verbindung ohne Mitteilung des Servers abbricht, führt die erste Remote-Aktion, die Sie versuchen (Refresh, Upload, Download usw.), wieder denselben Zustand aus, anstatt nichts zu tun.

**Neu verbinden** öffnet eine neue Sitzung mit denselben Anmeldeinformationen und listet den Ordner auf, in dem Sie sich befanden. Der Hostschlüssel des Servers wird wie bei der ersten Verbindung geprüft: ein unveränderter Schlüssel verbindet ohne Aufforderung, ein geänderter Schlüssel bleibt blockiert.

## Wieder öffnen mit einem Projekt

SFTP-Manager-Tabs werden mit einem [Projekt](projects.md#sftp-manager-tabs) gespeichert. Das Öffnen des Projekts mit **Automatisches Wiederverbinden** verbindet jeden Tab erneut mit den lokalen und entfernten Ordnern, in denen er sich zum Zeitpunkt des Speicherns befand. Ein Ordner, der nicht mehr existiert, öffnet stattdessen den Home-Ordner; auf der entfernten Seite sagt die Statusleiste dies an.

## Dateioperationen

Jedes Panel hat seine eigene Gruppe von Toolbar-Buttons unter den Listen (lokal links, remote rechts) und ein Rechtsklick-Kontextmenü:

| Betrieb | Wie |
|-----------|-----|
| **Hochladen** | Wählen Sie lokale Dateien oder Ordner aus und klicken Sie auf **Hochladen**, oder ziehen Sie sie in das Remote-Panel; siehe [Ziehen und Ablegen](#ziehen-und-ablegen) |
| **Herunterladen** | Wählen Sie Remote-Dateien oder Ordner aus und klicken Sie auf **Herunterladen**, oder ziehen Sie sie in das lokale Panel |
| **Umbenennen** | Wählen Sie einen Eintrag aus und drücken Sie ++f2++, oder wählen Sie **Umbenennen** im Kontextmenü. Ein vorhandener Eintrag mit dem neuen Namen wird niemals ersetzt; stattdessen erhalten Sie einen Fehler |
| **Neuer Ordner** | Klicken Sie auf die Ordner-Schaltfläche neben **Aktualisieren**, oder wählen Sie **Neuer Ordner** im Kontextmenü, in beiden Panels. Der Ordner wird im angezeigten Verzeichnis erstellt; ein bereits vorhandener Name ist ein Fehler |
| **Löschen** | Wählen Sie Einträge aus und klicken Sie auf **Löschen**, wählen Sie **Löschen** im Kontextmenü, oder drücken Sie ++delete++ (++ctrl+backspace++, auf macOS ++cmd+backspace++). Sie bestätigen vor dem Löschen von etwas |
| **Kopieren** | **Kopieren nach…** im Kontextmenü kopiert innerhalb derselben Seite: lokal in ein von Ihnen ausgewähltes Verzeichnis (im Hintergrund), remote in einen von Ihnen eingegebenen Pfad. Ein Remote-Verzeichnis, das an einer Stelle kopiert wird, an der bereits ein Verzeichnis mit diesem Namen existiert, wird in dieses zusammengeführt; das Kopieren eines Elements auf sich selbst oder in einen seiner eigenen Unterordner wird mit einem Fehler abgelehnt. |
| **Im Snippet-Editor bearbeiten** | Wählen Sie genau eine lokale oder Remote-Datei aus und verwenden Sie dann das Symbolleistenmenü *Bearbeiten* oder das Kontextmenü mit der rechten Maustaste |
| **Archivieren** | **Archivieren** in der Remote-Werkzeugleiste, oder **Archivieren...** im jeweiligen Kontextmenü, packt die Auswahl als ZIP, TAR.BZ2 oder 7z, je nach verfügbaren Tools auf dieser Seite |
| **Besitzer/Berechtigungen setzen** | Wählen Sie Einträge aus, klicken dann auf **Rechte** oder wählen **Besitzer/Berechtigungen setzen...** im Kontextmenü. Separate Felder für Benutzer, Gruppe und oktale Berechtigungen (z.B., 755) |

Ein Name für **Umbenennen** oder **Neuer Ordner** muss ein einzelnes Element im angezeigten Ordner sein: er darf nicht leer, `.` oder `..` sein, und darf `/` oder `\` nicht enthalten. Das Dialogfeld bleibt mit einem Fehler offen, bis der Name nutzbar ist.

### Ziehen und Ablegen

Sie können Einträge zwischen den beiden Panels ziehen, von Ihrem Desktop in den SFTP-Manager und in kleinen Mengen vom Server auf Ihren Desktop. Während Sie ziehen, wird das Panel oder die Ordnerzeile, in die der Drop erfolgen würde, hervorgehoben; ein Drop auf eine Ordnerzeile legt die Einträge in diesen Ordner, ein Drop an einer anderen Stelle in den angezeigten Ordner.

| Ziehen | Ergebnis |
|------|--------|
| Lokale Zeilen auf das entfernte Panel | Hochgeladen, wie bei **Hochladen**: eine Datei mit demselben Namen auf dem Server wird ersetzt, ein Ordner wird zusammengeführt |
| Dateien aus Finder, Explorer oder der Dateibrowser-Seitenleiste auf das Remote-Panel | hochgeladen – dieselbe Methode |
| Remote-Zeilen auf das lokale Panel | Im Hintergrund heruntergeladen, wie bei **Herunterladen**: Ordner eingeschlossen, und eine lokale Datei mit demselben Namen wird ersetzt |
| Dateien aus Finder, Explorer oder der Dateibrowser-Seitenleiste auf das lokale Panel | Kopiert im Hintergrund; ein bereits vorhandener Name bekommt eine Nummer, wie in `report (2).txt`, und eine Datei, die auf den Ordner fallen gelassen wird, bleibt unverändert |
| Lokale Zeilen auf den Desktop oder ein anderes Programm | Als die Dateien selbst angeboten |
| Remote Zeilen auf den Desktop oder ein anderes Programm | Nur für maximal 20 Dateien mit insgesamt höchstens 16 MB, keine Ordner |

Ein Drag in dasselbe Panel bewirkt nichts; nutzen Sie dort **Kopieren nach...** oder **Umbenennen**.

!!! note "Ziehen vom Server zum Desktop"
    Der Desktop kann nur Dateien übernehmen, die bereits existieren, wenn der Drag beginnt. KorTTY lädt daher die gezogenen Remote-Dateien zunächst in einen privaten temporären Ordner, was das Fenster bis zu 5 Sekunden blockieren kann. Ordner, mehr als 20 Dateien, mehr als 16 MB oder ein Download, der länger dauert, können nur innerhalb des Fensters fallen gelassen werden; die Statusleiste weist darauf hin, und Sie legen sie stattdessen auf das lokale Panel. Ein Link auf dem Server zählt als die Datei, auf die er zeigt, sodass ein Link zu einer großen Datei ebenfalls nicht dem Desktop angeboten wird. Die temporären Kopien werden gelöscht, wenn Sie den nächsten Drag starten oder den Tab schließen.

### Tasten

Diese Tasten funktionieren in beiden Panels:

| Taste | Aktion |
|-----|--------|
| ++f2++ (in der Liste) | Den ausgewählten Eintrag umbenennen |
| ++delete++ oder ++ctrl+backspace++ (++cmd+backspace++ auf macOS, in der Liste) | Löschen der Auswahl, nach Bestätigung |
| ++enter++ (im **Pfad** Feld) | Gehe zum eingegebenen Ordner |

### Dateien und Ordner hochladen

**Hochladen** kopiert die ausgewählten lokalen Dateien und Ordner in den entfernten Ordner, der angezeigt wurde, als Sie **Hochladen** geklickt haben; das Durchsuchen anderer Orte während es läuft ändert das Ziel nicht. **Hochladen** bleibt deaktiviert, bis der erste entfernte Ordner aufgelistet wurde, weil der Server `~` selbst nicht erweitert.

- **Das erneute Hochladen eines Ordners führt zu einer Zusammenführung** in den bestehenden entfernten Ordner: Dateien mit demselben Namen werden ersetzt, andere entfernte Dateien bleiben erhalten.
- **Dateien werden gestreamt**, sodass ihre Größe nicht durch den Speicher begrenzt ist; Dateien größer als 2 GB werden wie jede andere hochgeladen.
- Wenn die Verbindung während eines Uploads oder Downloads abbricht, stoppt der Rest des Batches mit einem **Getrennt**-Status statt eines Fehlers pro Datei.

### Berechtigungen

Das Berechtigungsfeld von **Besitzer/Berechtigungen setzen** und des Remote-Archivdialogs akzeptiert genau drei Oktalziffern, wie `755` oder `644`; lassen Sie es leer (oder unverändert), um den aktuellen Modus beizubehalten. Alles andere lässt das Dialogfeld mit einem Fehler offen, sodass kein anderer Text jemals `chmod` erreicht.

### Bearbeiten von Dateien mit dem Snippet-Editor

Die Aktion **Im Snippet-Editor bearbeiten** ist nur für eine einzelne ausgewählte Datei aktiviert. Es ist deaktiviert für:

- Ordner
- Der Eintrag im übergeordneten Verzeichnis (`..`)
- Mehrfachauswahl

Lokale Dateien werden direkt aus dem lokalen Dateisystem gelesen; Remote-Dateien werden über die aktive SFTP-Sitzung in den Editor heruntergeladen.

Wenn die Datei im Snippet-Editor geöffnet wird, bleibt die vollständige Symbolleiste verfügbar, einschließlich:

- Formatierung und Syntaxprüfung
- Editor-Profile und Styling
- Zeilennummern und Zeilenumbruch
- Konfigurierte KI-Aktionen

Die Dateimodus-Schaltflächen bieten folgende Speicheroptionen:

- **Datei überschreiben** – schreibt den aktuellen Editorinhalt zurück in die ursprüngliche lokale oder Remote-Datei
- **Speichern unter...** – schreibt eine neue lokale Datei über eine Dateiauswahl oder fordert für Remote-Dateien zur Eingabe eines neuen Dateinamens im selben Remote-Verzeichnis auf
- **Als Snippet speichern** – speichert den aktuellen Inhalt als neues Snippet-Manager-Snippet, ohne die Quelldatei als gespeichert zu markieren

![SFTP dual-panel file manager](../assets/screenshots/sftp/sftp-manager.png)

## Suche

Das Suchfeld über jeder Liste filtert den angezeigten Ordner während der Eingabe, ohne ihn zu verlassen; der übergeordnete Eintrag `..` bleibt sichtbar. korTTY bewertet die Platzhalter selbst, gleichartig für lokale und entfernte Namen. Ein Muster mit einem Platzhalter muss den gesamten Namen entsprechen, ohne Groß-/Kleinschreibung:

| Platzhalter | Trifft auf | Beispiel |
|----------|---------|---------|
| `*` | Beliebig viele Zeichen | `*.log` findet alle Logdateien, `backup*` alle Namen beginnend mit "backup" |
| `?` | Genau ein Zeichen | `data?.csv` findet `data1.csv`, nicht `data10.csv` |
| `[abc]`, `[a-z]` | Eines der aufgeführten Zeichen; `[!abc]` oder `[^abc]` eines, das nicht aufgeführt ist | `[ab]*` findet Namen beginnend mit a oder b |
| `{py,sh}` | Eine der Alternativen | `*.{py,sh}` findet Python- und Shell-Dateien |

Text ohne Platzhalter passt überall im Namen, Groß-/Kleinschreibung ignorierend: `rep` findet `Report.txt`. Ein Muster, das nicht gelesen werden kann, wie ein nicht geschlossenes `[`, wird als einfacher Text gesucht.

---

