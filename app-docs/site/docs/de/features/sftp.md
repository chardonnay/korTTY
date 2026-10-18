---
title: SFTP-Dateimanager
---

#  Manager für SFTP-Dateien

Der integrierte SFTP-Manager bietet einen grafischen Dateimanager zum Übertragen von Dateien zwischen Ihrem lokalen Computer und Remote-Servern über SFTP. Es verfügt über ein Dual-Panel-Layout, vollständige Unterstützung für Dateivorgänge und eine nahtlose Integration mit dem Snippet-Editor für die Fernbearbeitung von Dateien.

## SFTP-Manager öffnen

Sie können den SFTP-Manager auf zwei Arten öffnen:

- **Menü:** *Verbindungen → SFTP-Client...* (++ctrl+shift+u++, unter macOS ++cmd+shift+u++). Ist ein verbundener Terminal-Tab im Vordergrund, öffnet er sich auf dem fokussierten Bereich dieses Tabs, wie bei **SFTP hier öffnen**; andernfalls wählen Sie zuerst eine Verbindung aus.
- **Aus einem Terminal:** *Verbindungen → SFTP hier öffnen*, **SFTP hier öffnen** im Rechtsklickmenü eines Terminal-Tabs oder **SFTP hier öffnen** im Rechtsklickmenü eines Bereichs. Auch die Befehlspalette findet den Befehl. Siehe [Aus einem Terminal-Tab öffnen](#aus-einem-terminal-tab-offnen).
- **Dashboard:** Rechtsklick auf eine verbundene Sitzung > **SFTP-Client...**

Wenn die Verbindung einen temporären SSH-Schlüssel verwendet, der abgelaufen ist, werden Sie aufgefordert, einen neuen Schlüssel einzugeben, bevor die Verbindung fortgesetzt werden kann.

## Aus einem Terminal-Tab öffnen

**SFTP hier öffnen** öffnet den SFTP-Manager über die SSH-Sitzung, die der Terminalbereich bereits hat, sodass keine zweite Anmeldung nötig ist: keine zweite Passwort- oder MFA-Abfrage, kein zweiter Sprung über einen Jump-Server und keine erneute Abfrage des CyberArk-Zugriffsgrunds. Verwendet wird der Bereich, in dem Sie tippen (oder der Bereich, den Sie mit der rechten Maustaste angeklickt haben), und in einem geteilten Tab, dessen Bereich eine andere Verbindung ausführt, öffnet er sich als Benutzer und Server dieses Bereichs, nicht als die des Tabs.

Die Serverseite startet in dem Ordner, in dem sich die Shell befindet: dem Ordner, den die Shell meldet (OSC 7 oder der korTTY-Agent-Hook), sonst dem im Prompt angezeigten Ordner, sonst dem Ordner Ihres letzten `cd`. `~` und relative Ordner werden relativ zu Ihrem Home-Ordner aufgelöst. Existiert dieser Ordner nicht mehr, wird der Anmeldeordner angezeigt, und die Statusleiste weist darauf hin. In zwei Fällen startet er absichtlich im Anmeldeordner, mit einem Hinweis in der Statusleiste: bei einem Bereich, in dem eine andere Sitzung aktiv ist (nach `su`, `sudo -i` oder einem verschachtelten `ssh`), weil dessen Ordner einem anderen Benutzer oder Host gehört, und bei einer Verbindung mit einem Shell-Startbefehl, der vor dem ersten Prompt den Benutzer wechseln kann.

Jeder Bereich erhält einen eigenen SFTP-Tab, sodass sich zwei Bereiche desselben Servers nebeneinander durchsuchen lassen; wählen Sie für denselben Bereich erneut **SFTP hier öffnen**, wird dessen Tab ausgewählt. Einen Tab, der über *Verbindungen → SFTP-Client...* mit einer ausgewählten Verbindung geöffnet wurde, gibt es weiterhin einmal pro Verbindung, und die beiden Arten ersetzen einander nie.

Ein solcher Tab kopiert höchstens zwei Dateien gleichzeitig, auch wenn *Einstellungen → SFTP-Manager* mehr erlaubt, damit das Terminal auf derselben Sitzung reaktionsfähig bleibt. Das Schließen des Tabs schließt nie das Terminal. Endet die Sitzung des Terminals, wird der Bereich geschlossen oder läuft darin inzwischen ein anderer Benutzer oder Host, zeigt der Tab **Getrennt** mit **Neu verbinden**, das die Sitzung des Bereichs wieder verwendet, sobald sie zurück ist, und **Eigene Anmeldung**, das den Tab schließt und einen Tab mit eigener Anmeldung für die Verbindung des Bereichs öffnet.

Hat der Bereich keine SSH-Sitzung, die er teilen kann (ein Mosh- oder lokaler Shell-Tab), oder verweigert der Server oder ein Proxy SFTP darüber, öffnet korTTY stattdessen wie bisher einen SFTP-Tab mit eigener Anmeldung für die Verbindung des Bereichs. Ein gespeichertes [Projekt](projects.md#sftp-manager-tabs) stellt einen solchen Tab als gewöhnlichen SFTP-Tab mit eigener Anmeldung wieder her, in den Ordnern, in denen er sich befand.

Für einen schnellen Blick ohne Tab zu öffnen, **Ansicht → Seitenleiste Remote-Dateien** dockt eine Dateiliste neben die Terminalfenster an, die den Ordner auflistet, in dem sich die Shell des fokussierten SSH-Panes befindet, über die Sitzung dieses Panels und folgt der Shell, während Sie `cd`. Die Seitenleiste tippt niemals in das Terminal; ihre Uploads, Downloads und Drops gehen über die [Transferliste](#ubertragungsliste) nach einer Bestätigung, die den Serverordner nennt. Siehe [Seitenleiste Remote-Dateien](terminal.md#seitenleiste-remote-dateien).

## Schnittstelle

Der SFTP-Manager verwendet ein **Zwei-Panel-Layout** für eine einfache Dateiverwaltung nebeneinander:

| Linker Bereich (lokale) | Rechter Bereich (fern) |
|----|---|
| Lokale Dateien durchstöbern | Ferndateien durchstöbern |
| Auf Remote-System hochladen | Auf lokales System herunterladen |

![SFTP Manager with the server panel active: hidden entries first, a row of buttons under each list](../assets/screenshots/sftp/sftp-manager.png)

### Aktives Panel

Das Panel, in dem Sie arbeiten, ist das **aktive Panel**: Durch Klicken irgendwo im Panel oder durch Bewegen des Tastaturfokus hinein wird es in der Akzentfarbe eingerahmt, der Punkt vor seinem Titel farbig markiert und die ausgewählten Zeilen in der Akzentfarbe dargestellt. Das andere Panel behält seine Auswahl, wird gedämpft angezeigt, sodass Sie auf einen Blick sehen können, welche Liste Ihre letzte Klick- und Tastenkombination wie ++delete++ und ++f2++ beeinflusst.

Jedes Panel hat seine eigene Schaltflächenreihe unter seiner Liste, und jede Schaltfläche wirkt auf das Panel, in dem sie sich befindet: **Aktualisieren**, **Neuer Ordner**, **Löschen**, **Rechte**, **Archiv** und **Bearbeiten** in beiden Reihen, **Hochladen** am rechten Ende der lokalen Reihe und **Herunterladen** am linken Ende der Serverreihe, wo sich die beiden Panels treffen.

### Versteckte Dateien

Der Augenknopf neben dem Home-Button jedes Panels zeigt oder verbirgt die versteckten Einträge dieses Panels: Namen, die mit einem Punkt beginnen, wie `.git` oder `.bashrc`, und auf der lokalen Seite auch Dateien und Ordner, die das Dateisystem als versteckt markiert (das versteckte Attribut unter Windows). ++cmd+shift+period++ (++ctrl+shift+period++ auf Windows und Linux) führt dasselbe für die Liste aus, die den Tastaturfokus hat, wie im macOS Finder. Jedes Panel merkt sich seine Auswahl für den nächsten SFTP-Tab; bis Sie sie verbergen, werden versteckte Einträge angezeigt. Der übergeordnete Eintrag `..` bleibt immer erhalten, und das Suchfeld filtert den Rest.

### Pfadleiste und Statuszeile

Der Pfad jedes Panels wird als anklickbare Ordner angezeigt; klicken Sie auf einen, um dorthin zu gelangen. Um stattdessen einen Pfad einzugeben, klicken Sie auf den leeren Teil der Leiste oder drücken Sie ++cmd+l++ (++ctrl+l++ unter Windows und Linux) im Panel; ++enter++ führt dorthin, und ++escape++ kehrt zu den Ordnern zurück. Wenn der Pfad breiter als die Leiste ist, bleibt sein Ende sichtbar.

Eine Zeile unter jeder Liste zählt ihre Einträge (während das Suchfeld die Liste einengt, wie viele von allen angezeigt werden), die ausgewählten Einträge mit ihrer Größe und die versteckten Einträge, die der Augen-Button ausblendet. Die **Größe**-Spalte ist rechtsbündig, und beim Überfahren eines Datums wird es vollständig angezeigt.

###  Sortierbare Spalten

Beide Paneele zeigen die gleichen Spalten an, von denen alle durch Klicken auf den Spaltenkopf sortierbar sind:

| Spalte | Beschreibung |
|--------|---|
| **Name** | Datei oder Verzeichnisaname |
| **Typ** | Ein Typ-Icon: ein Ordner oder ein Dateiknopf, der anhand des Dateinamens (Code, Bild, Archiv, Dokument, anderes) gewählt wird |
| **Größe** | Dateigröße im menschenlesbaren Format; ein Ordner zeigt — bis [Größe berechnen](#ordnergroen) gemessen wurde. Sortierung verwendet die exakte Byte-Anzahl innerhalb jeder Gruppe des [Sortierreihenfolge](#sortierreihenfolge) |
| **Datum** | Letzte Änderungsdatum und -zeit |
| **Benutzer** | Besitzername (lokal: vom Dateisystem; auf dem Server: wie der Server es sendet, oder dort nachgeschlagen, wenn er nur die UID sendet; siehe [Besitzer und Gruppenname](#besitzer-und-gruppennamen)) |
| **Gruppe** | Gruppenname (lokal: vom Dateisystem; auf dem Server: wie der Server es sendet, oder dort nachgeschlagen, wenn er nur die GID sendet) |
| **Berechtigungen** | Berechtigungen im Unix-Stil (z. B. `rwxr-xr-x`) |

## Sortierreihenfolge

Unabhängig davon, nach welcher Spalte Sie sortieren, bleiben die Einträge in diesen Gruppen:

1. **Übergeordnetes Verzeichnis** (`..`) – immer oben
2. **Versteckte Ordner** — Namen, die mit "." beginnen (z. B. `.git`, `.config`)
3. **Versteckte Dateien** (z. B. `.bashrc`)
4. **Andere Ordner**
5. **Andere Dateien**

Klicken Sie auf einen Spaltenkopf, um die Einträge innerhalb jeder Gruppe nach dieser Spalte zu sortieren; ein erneutes Klicken kehrt die Reihenfolge um, jedoch niemals die Reihenfolge der Gruppen. Standardmäßig werden die Listen nach der Spalte **Typ** sortiert, wodurch jede Gruppe alphabetisch nach Namen geordnet wird; die Sortierung berücksichtigt keine Groß- und Kleinschreibung. Während ein Panel seine [versteckten Dateien](#versteckte-dateien) ausblendet, bleiben die Gruppen 2 und 3 ausgeschlossen.

## Durchsuchen des Servers

Remote-Ordner werden im Hintergrund gelesen, sodass das Fenster auch bei Ordnern mit Zehntausenden von Einträgen reaktionsfähig bleibt. Während ein Ordner geladen wird, überdeckt ein **Wird geladen…**-Indikator die Remote-Liste. Das Pfadfeld und die Liste wechseln erst in den neuen Ordner, wenn er gelesen wurde; falls das Lesen fehlschlägt (Ordner existiert nicht oder Sie haben keine Rechte), wird ein Fehler angezeigt und der Ordner, in dem Sie sich befanden, bleibt bestehen.

Geben Sie einen Pfad in das Remote-**Pfad**-Feld ein und drücken ++enter++, um dorthin zu gelangen. `~` und `~/…` stehen für Ihr Login-Verzeichnis auf dem Server, und ein Pfad ohne führendes `/` wird relativ zum angezeigten Ordner interpretiert; `..` und `.` werden aufgelöst, sodass `../logs` den Geschwisterordner `logs` öffnet.

### Besitzer- und Gruppennamen

OpenSSH und andere Server, die SFTP Version 3 sprechen, senden Eigentümer und Gruppen nur als Zahlen (UID und GID). korTTY fragt dann einmal vom Server die Namen hinter Zahlen ab, die es noch nicht gesehen hat, mit `getent`, das auch LDAP und andere Verzeichniskonten kennt, oder von `/etc/passwd` und `/etc/group`, wo `getent` fehlt. Die Namen ersetzen die Zahlen kurz nachdem die Liste erscheint; die Liste wartet nie auf sie. **Besitzer/Berechtigungen setzen** ist ebenfalls mit den Namen vorausgefüllt.

Ein Server, der keine Befehle ausführen kann (z. B. ein auf SFTP beschränktes Konto) oder der nach 8 Sekunden nicht geantwortet hat, zeigt weiterhin die Zahlen an, und korTTY fragt nicht erneut, bis Sie sich wieder verbinden.

### Ordnergrößen

**Größe berechnen** im Rechtsklick-Menü der Serverliste misst die ausgewählten Ordner mit `du -sk` auf dem Server. Die Spalte **Größe** zeigt das Ergebnis für jeden Ordner und die Statusleiste den Gesamtwert an; nach **Größe** sortieren ordnet sie diese Ordner danach. Die Zahl gibt den Speicherplatz an, den die Dateien einnehmen, der sich leicht von der Summe ihrer Größen unterscheiden kann. Während die Messung läuft, lautet derselbe Menüeintrag **Größenberechnung stoppen**. Die Größen werden verworfen, wenn die Liste erneut gelesen wird.

## Verbindung verloren

Wenn der Server oder das Netzwerk die Sitzung beendet, zeigt die Statusleiste **Getrennt von** *host* mit einer Schaltfläche **Neu verbinden**, und die Remote-Aktionen sind deaktiviert. Wenn die Verbindung ohne Mitteilung des Servers abbricht, führt die erste Remote-Aktion, die Sie versuchen (Refresh, Upload, Download usw.), wieder denselben Zustand aus, anstatt nichts zu tun.

**Neu verbinden** öffnet eine neue Sitzung mit denselben Anmeldeinformationen und listet den Ordner auf, in dem Sie sich befanden. Der Hostschlüssel des Servers wird wie bei der ersten Verbindung geprüft: ein unveränderter Schlüssel verbindet ohne Aufforderung, ein geänderter Schlüssel bleibt blockiert.

## Wieder öffnen mit einem Projekt

SFTP-Manager-Tabs werden mit einem [Projekt](projects.md#sftp-manager-tabs) gespeichert. Das Öffnen des Projekts mit **Automatisches Wiederverbinden** verbindet jeden Tab erneut mit den lokalen und entfernten Ordnern, in denen er sich zum Zeitpunkt des Speicherns befand. Ein Ordner, der nicht mehr existiert, öffnet stattdessen den Home-Ordner; auf der entfernten Seite sagt die Statusleiste dies an.

## Was wird verglichen

**Vergleichen** in der lokalen Schaltflächenreihe, und **Vergleichen...** im Rechtsklick-Menü beider Listen, betrachtet das Ausgewählte:

- **Zwei Dateien**, zwei ausgewählt im aktiven Panel oder jeweils eine in jedem Panel, öffnen nebeneinander; siehe [Vergleich zweier Dateien](#zwei-dateien-vergleichen).
- **Zwei Ordner**: ein lokaler und ein Server-Ordner werden in der Leiste über den Listen verglichen, und beide Panels gehen hinein; zwei Ordner auf derselben Seite öffnen sich in einem eigenen Fenster; siehe [Zwei Ordner auf derselben Seite](#zwei-ordner-auf-der-gleichen-seite).
- **Nichts davon**: Der angezeigte lokale Ordner wird mit dem angezeigten Serverordner verglichen.

Eine Datei und ein Ordner können nicht miteinander verglichen werden; die Statusleiste weist darauf hin.

## Zwei Dateien vergleichen

Die beiden Dateien öffnen sich nebeneinander in einem Fenster, wobei ihre Änderungen markiert sind, ähnlich dem Vergleich von zwei Snippets im Snippet-Manager. Die Zeile darüber zählt die hinzugefügten und entfernten Zeilen. Dateien größer als 4 MB und Dateien, die kein Text sind (ein NUL-Byte nahe am Anfang), werden nicht verglichen. Eine Datei auf dem Server wird über SFTP gelesen; dafür ist eine Verbindung erforderlich und sie wird verweigert, wenn Ihre Organisation nicht zulässt, dass Serverdateien in einen Editor geladen werden. In einem Ordnervergleich öffnet ein Doppelklick auf eine Datei, die an beiden Seiten vorhanden ist, die beiden Dateien auf dieselbe Weise.

![Two versions of a deploy script side by side, with the AI panel beside them](../assets/screenshots/sftp/sftp-file-diff.png)

### KI anfragen

**KI fragen** öffnet ein Panel neben den beiden Dateien und erweitert das Fenster dafür, sodass die Dateien nebeneinander bleiben. Wählen Sie eine vorbereitete Frage (wo die Dateien unterschiedlich sind, was die Unterschiede bewirken oder was in der rechten Version verbessert werden könnte) oder schreiben Sie Ihre eigene, wählen Sie das KI-Profil, wenn mehrere vorhanden sind, und klicken Sie auf **Fragen**; die Schaltfläche daneben stoppt die Anfrage. Beide Dateien mit Zeilennummern und ihrem einheitlichen Diff werden an das Profil übergeben, und Geheimnisse, die es erkennt, wie z. B. Schlüssel und Passwörter, werden zunächst maskiert, sofern das Profil nicht auf diesem Computer ausgeführt wird. Die Antwort erscheint unter der Frage, und die Anfrage zählt zur Nutzung des Profils wie jede andere KI-Anfrage. Dies eignet sich für Shell-Skripte und Konfigurationsdateien, bei denen eine geänderte Zeile das Verhalten eines Dienstes verändern kann.

**KI fragen** kann nur angeklickt werden, wenn die KI-Funktionen aktiviert sind, Ihre Organisation KI-Anfragen zulässt und mindestens ein KI-Profil existiert; andernfalls zeigt das Überfahren mit der Maus an, was fehlt.

### Eine neue Datei aus beiden Dateien

Im oberen Bereich des KI-Panels wählen **Fragen** und **Neue Datei**, was die KI tun soll. Mit **Neue Datei** schreibt die KI eine neue, dritte Datei aus den beiden, wie Sie es beschreiben: wählen Sie eine vorbereitete Anweisung wie **Das Beste aus beiden** oder **Die rechte Datei verbessern**, oder schreiben Sie Ihre eigene, und klicken Sie auf **Datei erstellen**. Die KI listet zunächst auf, was sie geändert hat, und sendet dann die komplette Datei. Der Vergleich zeigt anschließend die rechte Datei gegenüber der neuen an, und die Liste über den Dateien wählt die beiden angezeigten Dateien aus: links und rechts, rechts und die neue Datei oder links und die neue Datei. Eine Antwort ohne komplette Datei, zum Beispiel eine, die abgeschnitten wurde, wird angezeigt, aber nicht als Datei übernommen.

![The new file from the AI shown against the right file, with the AI panel in New file mode](../assets/screenshots/sftp/sftp-file-diff-new-file.png)

Unter der Antwort bietet **Neue Datei** an, was mit der Datei zu tun ist:

| Button | Aktion |
| --- | --- |
| **Anzeigen** | vergleicht die neue Datei erneut mit der rechten Datei |
| **Speichern...** | fragt, wohin die Datei geht, auf dem Server oder auf diesem Computer, und welchen Pfad; der Ort der rechten Datei wird vorgeschlagen, da die neue Datei sie ersetzen soll, und **Durchsuchen...** wählt mit einem Klick einen anderen Ort |
| **Als Snippet speichern...** | Öffnet die neue Datei im Snippet-Editor. Dort können Sie sie bearbeiten, ihr einen Namen und eine Kategorie geben und im Snippet-Manager mit **Als Snippet speichern** behalten. **Rechte Datei ersetzen** und **Speichern unter...** im Editor speichern die bearbeitete Datei stattdessen |

![Saving the new file on the server](../assets/screenshots/sftp/sftp-save-new-file.png)

Auf diesem Computer öffnet **Durchsuchen...** den Speicherdialog des Systems. Auf dem Server listet es die Ordner und Dateien des Servers auf: ein Doppelklick öffnet einen Ordner (`..` ist der Ordner darüber), ein Klick auf eine Datei übernimmt ihren Namen, und der Name kann auch eingegeben werden. Ein Ordner, der in das Pfadfeld über der Liste eingegeben wird, öffnet sich mit ++enter++.

![Choosing the folder and the file name on the server](../assets/screenshots/sftp/sftp-remote-save-chooser.png)

Falls die Datei bereits existiert, fragt korTTY vor dem Überschreiben und speichert zunächst die vorherige Version neben ihr als `.bak`, sofern Sie **Eine vorhandene Datei vorher als .bak sichern** nicht deaktivieren. Beim Überschreiben bleiben die Dateiberechtigungen erhalten, und das Backup erhält dieselben Rechte; eine Konfiguration, bei der nur der Eigentümer lesen darf, bleibt unverändert. Das Speichern auf dem Server erfordert eine Verbindung und wird nicht angeboten, wenn Ihre Organisation die Serverdateien in Editoren schreibgeschützt hält oder den Dateitransfer deaktiviert. Wenn die neue Datei über eine der beiden verglichenen Dateien gespeichert wird, zeigt der Vergleich, was diese Datei jetzt enthält.

## Ordner vergleichen und synchronisieren

Wenn ein lokaler Ordner mit einem Serverordner (die beiden angezeigten oder zwei ausgewählte) verglichen wird, werden alle Unterordner einbezogen. Zuerst fragt er nach Mustern zum Ausschließen, jeweils eine pro Zeile, wie `node_modules` oder `.cache`. Anschließend werden beide Ordner im Hintergrund gelesen; die Leiste über den Listen zählt, was gelesen wurde, und ein Knopf am Ende stoppt den Vergleich. Name, Größe und Änderungszeit werden verglichen, und Zeitunterschiede von weniger als 2 Sekunden gelten als gleich, weil SFTP-Version 3 ganze Sekunden beibehält. Symbolische Links werden auf beiden Seiten ausgeschlossen, und mehr als 100 000 Einträge auf einer Seite stoppen den Vergleich.

Das Ergebnis bleibt in der Leiste über den Listen: wie viele Einträge nur auf einer Seite existieren, neuere auf einer Seite sind, unterschiedlich (gleich alt aber unterschiedlicher Größe oder eine Datei auf einer Seite und ein Ordner auf der anderen) und gleich sind. Die Zeilen nehmen Farben an, jede aus ihrer eigenen Sicht: grün nur hier, orange neuer hier, rot unterschiedlich und gedämpft, wenn gleich oder älter hier. Ein Ordner ist rot, wenn etwas darin unterschiedlich ist, und die Farben folgen Ihnen in die Unterordner der verglichenen Ordner. **Nur Unterschiede** verbirgt, was gleich ist; **Erneut vergleichen** (der Pfeil) und **Vergleich beenden** (das Kreuz) stehen am Ende der Leiste. Ein Doppelklick auf eine verglichene Datei, die auf beiden Seiten existiert, zeigt die beiden Dateien nebeneinander.

![A comparison of a local folder with its copy on the server](../assets/screenshots/sftp/sftp-compare.png)

**Auf den Server** kopiert das, was auf dem Server fehlt, was hier neuer ist und was gleich alt aber anders groß ist; **Auf diesen Computer** tut das Gleiche in die andere Richtung. Ein Dialog listet zunächst auf, was kopiert wird und wie viel. Dateien, die im Ziel existieren, werden ohne Nachfrage pro Datei ersetzt, und jede Kopie behält die Änderungszeit ihres Originals bei, sodass der nächste Vergleich die beiden als gleich erkennt. Eine neuere Datei im Ziel bleibt unverändert, ebenso ein Pfad, der auf einer Seite eine Datei und auf der anderen einen Ordner ist.

Der Dialog verfügt außerdem über ein Kontrollkästchen, das standardmäßig deaktiviert ist und löscht, was ausschließlich auf dem Ziel vorhanden ist; nach einer zweiten Bestätigung wird es endgültig gelöscht. Die Kopien laufen in der [Transferliste](#ubertragungsliste), und die Ordner werden erneut verglichen, sobald sie abgeschlossen sind. Wenn Ihre Organisation die Dateiübertragung deaktiviert, zeigen beide Schaltflächen nur den Grund in der Statusleiste an.

![The dialog before synchronizing to the server](../assets/screenshots/sftp/sftp-sync-dialog.png)

### Zwei Ordner auf der gleichen Seite

Zwei lokale Ordner, oder zwei Ordner auf dem Server, werden auf die gleiche Weise verglichen, mit denselben Mustern zum Ausschließen, aber das Ergebnis öffnet sich in einem eigenen Fenster: die Panels können nur einen lokalen Ordner gegen einen Serverordner einfärben. Das Fenster listet jeden Pfad mit seiner Vergleichsart auf, wie **Nur rechts** oder **Links neuer**, sowie seine Größe und Zeit auf beiden Seiten, mit **Nur Unterschiede** zunächst aktiviert. Ein Doppelklick auf eine Datei, die auf beiden Seiten existiert, zeigt die beiden Dateien nebeneinander. Synchronisieren wird hier nicht angeboten.

![Two releases on the server compared in their own window](../assets/screenshots/sftp/sftp-folder-compare.png)

## Dateioperationen

Jedes Panel verfügt über eigene Schaltflächen unter seiner Liste und ein Rechtsklick-Kontextmenü; eine Schaltfläche wirkt auf das Panel, in dem sie sich befindet (siehe [Aktives Panel](#aktives-panel)):

| Betrieb | Wie |
|-----------|-----|
| **Hochladen** | Wählen Sie lokale Dateien oder Ordner aus und klicken Sie auf **Hochladen** am rechten Ende der lokalen Zeile, oder ziehen Sie sie in das Remote-Panel; Siehe [Ziehen und Ablegen](#ziehen-und-ablegen) |
| **Herunterladen** | Wählen Sie Remote-Dateien oder Ordner aus und klicken Sie auf **Herunterladen** am linken Ende der Serverzeile, oder ziehen Sie sie in das lokale Panel |
| **Umbenennen** | Wählen Sie einen Eintrag aus und drücken Sie ++f2++, oder wählen Sie **Umbenennen** im Kontextmenü. Ein vorhandener Eintrag mit dem neuen Namen wird niemals ersetzt; stattdessen erhalten Sie einen Fehler |
| **Neuer Ordner** | Klicken Sie auf die Ordner-Schaltfläche neben **Aktualisieren**, oder wählen Sie **Neuer Ordner** im Kontextmenü, in beiden Panels. Der Ordner wird im angezeigten Verzeichnis erstellt; ein bereits vorhandener Name ist ein Fehler |
| **Löschen** | Wählen Sie Einträge aus und klicken Sie auf **Löschen**, wählen Sie **Löschen** im Kontextmenü, oder drücken Sie ++delete++ (++ctrl+backspace++, auf macOS ++cmd+backspace++). Sie bestätigen vor dem Löschen von etwas |
| **Kopieren** | **Kopieren nach…** im Kontextmenü kopiert innerhalb derselben Seite: lokal in ein von Ihnen ausgewähltes Verzeichnis (im Hintergrund), remote in einen von Ihnen eingegebenen Pfad. Ein Remote-Verzeichnis, das an einer Stelle kopiert wird, an der bereits ein Verzeichnis mit diesem Namen existiert, wird in dieses zusammengeführt; das Kopieren eines Elements auf sich selbst oder in einen seiner eigenen Unterordner wird mit einem Fehler abgelehnt. |
| **Im Snippet-Editor bearbeiten** | Wählen Sie genau eine lokale oder Remote-Datei aus und verwenden Sie dann das Symbolleistenmenü *Bearbeiten* oder das Kontextmenü mit der rechten Maustaste |
| **Archiv** | **Archiv** in der Schaltflächenzeile beider Panels, oder **Archiv...** im Kontextmenü, packt die Auswahl als ZIP, TAR.BZ2 oder 7z, je nach verfügbaren Werkzeugen auf dieser Seite. Ein lokales Archiv kann ein Passwort haben; ein Archiv auf dem Server nicht, weil `zip` und `7z` das Passwort auf der Befehlszeile des Servers benötigen, wo andere Benutzer des Servers es lesen können |
| **Vergleichen** | Wählen Sie zwei Dateien oder zwei Ordner (oder nichts), dann verwenden Sie **Vergleichen** in der lokalen Zeile oder **Vergleichen...** im Rechtsklick-Menü; siehe [Was wird verglichen](#was-wird-verglichen) |
| **Größe berechnen** | Wählen Sie Serverordner aus, klicken Sie mit der rechten Maustaste darauf und wählen Sie **Größe berechnen**; siehe [Ordnergrößen](#ordnergroen) |
| **Hier entpacken** | Klicken Sie mit der rechten Maustaste auf ein Archiv auf dem Server (`.zip`, `.tar`, `.tar.gz`/`.tgz`, `.tar.bz2`, `.tar.xz` oder `.7z`) und wählen Sie **Hier entpacken...**. Es wird auf dem Server in einen neuen Ordner daneben entpackt; siehe [Archive auf dem Server entpacken](#archive-auf-dem-server-entpacken) |
| **Besitzer/Berechtigungen setzen** | Wählen Sie Einträge aus, klicken dann auf **Rechte** oder wählen Sie **Besitzer/Berechtigungen setzen...** im Kontextmenü. Es verfügt über separate Felder für Benutzer und Gruppe und akzeptiert die Berechtigungen als Oktalzahlen (z. B. 755) oder als Kontrollkästchen; siehe [Berechtigungen](#berechtigungen). Mit **Rekursiv auf Verzeichnisse anwenden** erreicht die Änderung auch das, was sich in den ausgewählten Ordnern befindet |
| **Bild öffnen** | Wählen Sie eine Bilddatei aus und wählen **Bild öffnen** im Kontextmenü eines der beiden Panels. Das Bild öffnet sich in einem Bildbetrachter-Tab; ein Serverbild wird zunächst in den Speicher heruntergeladen |

Ein Name für **Umbenennen** oder **Neuer Ordner** muss ein einzelnes Element im angezeigten Ordner sein: er darf nicht leer, `.` oder `..` sein, und darf `/` oder `\` nicht enthalten. Das Dialogfeld bleibt mit einem Fehler offen, bis der Name nutzbar ist.

### Archive erstellen

Der obere Teil des Archivdialogs gibt an, was archiviert wird und wo: der Computer (**Server** mit dem Verbindungsnamen und user@host, oder **Dieser Computer** mit seinem Namen), der angezeigte Ordner, wie viele Ordner und Dateien ausgewählt sind, und deren Namen. Das Archiv wird auf demselben Computer geschrieben.

![Archive dialog naming the server, the folder, two folders and two files](../assets/screenshots/sftp/sftp-archive-dialog.png)

Bevor ein Server-Archivdialog öffnet, fragt korTTY den Server einmal nach den vorhandenen Archivierungswerkzeugen, und die Statusleiste gibt dies gleichzeitig an. Für einen Server, der keine Befehle ausführt (wie ein Konto, das nur SFTP zulässt) oder der nach 8 Sekunden nicht geantwortet hat, geht korTTY davon aus, dass `zip` und `tar` installiert sind. Für die geschätzte Größe eines Serverarchivs misst der Dialog die ausgewählten Ordner auf dieselbe Weise wie **Größe berechnen**, im Hintergrund, und addiert sie, sobald `du` antwortet; ein Ordner, den er nicht messen kann, wird ausgelassen, und der Dialog gibt dies an.

Der Dialog nimmt das Format (ZIP, TAR.BZ2 oder 7z; ein Format, dessen Tool der Server nicht hat, wird als *nicht installiert* markiert), den Archivpfad und eine Kompressionsstufe von keiner bis zur besten. Die übrigen Felder sind optional:

- **Exclude** — ein Muster pro Zeile, z. B. `*.log`, `.git` oder `node_modules`; es existiert in beiden Panels.
- **Passwort** — local archives only. It is greyed out for TAR.BZ2, a format without encryption, and server archives get no password at all.
- **Besitzer** und **Berechtigungen** — nur für Remote-Archive. Der Besitzer wird mit `chown` und die Berechtigungen, als Oktalzahlen, mit `chmod` festgelegt, sobald das Archiv geschrieben wird. Das Berechtigungsfeld ist vorgefüllt mit `644`.

### Archive auf dem Server entpacken

**Hier entpacken...** im Remote-Kontextmenü entpackt das ausgewählte Archiv direkt auf dem Server, sodass nichts heruntergeladen oder hochgeladen wird. Nach Ihrer Bestätigung zeigt ein Fortschrittsfenster jeden Schritt an, und **Abbrechen** stoppt das Entpacken und entfernt, was bereits entpackt wurde.

- **Immer ein neuer Ordner** — die Dateien landen in einem neuen Ordner neben dem Archiv, der nach ihm ohne Dateiendung benannt ist (aus `site-1.2.tar.gz` wird `site-1.2`). Ist dieser Name vergeben, verwendet korTTY `site-1.2 (1)`, `site-1.2 (2)` und so weiter; eine vorhandene Datei oder ein vorhandener Ordner wird nie überschrieben oder zusammengeführt.
- **Geprüft, bevor etwas geschrieben wird** — korTTY listet zuerst den Inhalt des Archivs auf und lehnt es als Ganzes ab, wenn ein Eintrag außerhalb des neuen Ordners landen würde: ein absoluter Pfad, `..`, das über den Ordner hinausführt, ein Laufwerksbuchstabe wie `C:`, ein Name mit Zeilenumbruch, eine Gerätedatei oder ein Link, dessen Ziel außerhalb liegt. In diesem Fall wird nichts geschrieben.
- **Nach dem Entpacken erneut geprüft** — das Archiv wird in einen versteckten Staging-Ordner (`.kortty-extract.` plus zufällige Zeichen) entpackt, den nur Sie öffnen können, ohne die im Archiv gespeicherten Eigentümer zu übernehmen. korTTY prüft dann jeden symbolischen Link darin; zeigt einer aus dem Ordner hinaus, wird der Staging-Ordner gelöscht und Sie erhalten einen Fehler. Nur ein sauberes Ergebnis wird in den neuen Ordner umbenannt, sodass dieser vollständig oder gar nicht erscheint.
- **Werkzeuge auf dem Server** — ZIP benötigt `unzip`, die tar-Formate `tar` und 7z `7z` oder `7za`. Fehlt das Werkzeug, nennt die Fehlermeldung es. Mit einem anderen tar als GNU tar (zum Beispiel BusyBox) werden Archive mit Hardlinks abgelehnt, weil deren Ziele nicht geprüft werden können.
- **Keine Passwörter** — passwortgeschützte Archive werden mit einer Meldung abgelehnt: Die Werkzeuge bräuchten das Passwort auf der Befehlszeile des Servers, wo andere Benutzer des Servers es lesen können.

### Ziehen und Ablegen

Sie können Einträge zwischen den beiden Panels ziehen, von Ihrem Desktop in den SFTP-Manager und in kleinen Mengen vom Server auf Ihren Desktop. Während Sie ziehen, wird das Panel oder die Ordnerzeile, in die der Drop erfolgen würde, hervorgehoben; ein Drop auf eine Ordnerzeile legt die Einträge in diesen Ordner, ein Drop an einer anderen Stelle in den angezeigten Ordner.

| Ziehen | Ergebnis |
|------|--------|
| Lokale Zeilen auf das Remote-Panel | Hochgeladen, wie bei **Hochladen**: Ein Ordner wird zusammengeführt, und eine gleichnamige Datei auf dem Server wird nicht ohne Nachfrage ersetzt (siehe [Wenn eine Datei bereits existiert](#wenn-eine-datei-bereits-existiert)) |
| Dateien aus Finder, Explorer oder der Dateibrowser-Seitenleiste auf das Remote-Panel | hochgeladen – dieselbe Methode |
| Remote-Zeilen auf das lokale Panel | Im Hintergrund heruntergeladen, wie bei **Herunterladen**: einschließlich Ordnern, und eine gleichnamige lokale Datei wird nicht ohne Nachfrage ersetzt |
| Dateien aus Finder, Explorer oder der Dateibrowser-Seitenleiste auf das lokale Panel | Kopiert im Hintergrund; ein bereits vorhandener Name bekommt eine Nummer, wie in `report (2).txt`, und eine Datei, die auf den Ordner fallen gelassen wird, bleibt unverändert |
| Lokale Zeilen auf den Desktop oder ein anderes Programm | Als die Dateien selbst angeboten |
| Remote Zeilen auf den Desktop oder ein anderes Programm | Nur für maximal 20 Dateien mit insgesamt höchstens 16 MB, keine Ordner |

Ein Drag in dasselbe Panel bewirkt nichts; nutzen Sie dort **Kopieren nach...** oder **Umbenennen**.

!!! note "Ziehen vom Server zum Desktop"
    Der Desktop kann nur Dateien übernehmen, die bereits existieren, wenn der Drag beginnt. korTTY lädt daher die gezogenen Remote-Dateien zunächst in einen privaten temporären Ordner, was das Fenster bis zu 5 Sekunden blockieren kann. Ordner, mehr als 20 Dateien, mehr als 16 MB oder ein Download, der länger dauert, können nur innerhalb des Fensters abgelegt werden; die Statusleiste weist darauf hin, und Sie legen sie stattdessen auf dem lokalen Panel ab. Ein Download, der die Zeit überschreitet, stoppt mitten in seiner Datei und löscht, was er geschrieben hat, damit er den temporären Ordner nicht im Hintergrund weiter füllt. Ein Link auf dem Server zählt als die Datei, auf die er zeigt, sodass ein Link zu einer großen Datei ebenfalls nicht dem Desktop angeboten wird. Die temporären Kopien werden gelöscht, wenn Sie den nächsten Drag starten oder den Tab schließen.

### Tasten

Diese Tasten funktionieren in beiden Panels:

| Taste | Aktion |
|-----|--------|
| ++f2++ (in der Liste) | Den ausgewählten Eintrag umbenennen |
| ++delete++ oder ++ctrl+backspace++ (++cmd+backspace++ auf macOS, in der Liste) | Löschen der Auswahl, nach Bestätigung |
| ++enter++ (im **Pfad** Feld) | Gehe zum eingegebenen Ordner |
| ++cmd+l++ (++ctrl+l++ auf Windows und Linux) | Geben Sie einen Pfad in die Pfadleiste des Panels ein |
| ++cmd+shift+period++ (++ctrl+shift+period++ auf Windows und Linux, in der Liste) | Versteckte Einträge anzeigen oder verbergen |

### Dateien und Ordner hochladen

**Hochladen** kopiert die ausgewählten lokalen Dateien und Ordner in den entfernten Ordner, der angezeigt wurde, als Sie **Hochladen** geklickt haben; das Durchsuchen anderer Orte während es läuft ändert das Ziel nicht. **Hochladen** bleibt deaktiviert, bis der erste entfernte Ordner aufgelistet wurde, weil der Server `~` selbst nicht erweitert.

- **Ein erneut hochgeladener Ordner wird zusammengeführt** mit dem vorhandenen Remote-Ordner: Andere Remote-Dateien bleiben erhalten, und für jede bereits vorhandene Datei fragt korTTY, was geschehen soll (siehe unten).
- **Dateien werden gestreamt**, sodass ihre Größe nicht durch den Speicher begrenzt ist; Dateien größer als 2 GB werden wie jede andere hochgeladen.
- Bricht die Verbindung während eines Uploads oder Downloads ab, schlagen die unfertigen Einträge mit **Verbindung verloren** fehl, und es erscheint ein einziger Status **Getrennt** statt eines Fehlerdialogs pro Datei; wählen Sie sie nach **Neu verbinden** in der [Übertragungsliste](#ubertragungsliste) aus und klicken Sie auf **Wiederholen**.
- **Verlinkten Ordnern wird nicht gefolgt.** Ein symbolischer Link auf eine Datei wird mit dem Inhalt der Datei übertragen; ein Link auf einen Ordner wird übersprungen und in der Zusammenfassung am Ende genannt, sodass eine Link-Schleife eine Übertragung nicht endlos laufen lassen kann.
- **Heruntergeladene Namen bleiben im Zielordner.** Ein Servername wie `..` oder `a/b` wird abgelehnt. Unter Windows werden außerdem Namen abgelehnt, die Windows für Geräte reserviert (`CON`, `PRN`, `AUX`, `NUL`, `COM1` bis `COM9`, `LPT1` bis `LPT9`, auch mit einer Endung wie `nul.txt`), sowie Namen, die auf einen Punkt oder ein Leerzeichen enden oder `:` enthalten, weil Windows sie an einer anderen Stelle schreiben oder verändern würde.

### Übertragungsliste

Uploads und Downloads laufen im Hintergrund, standardmäßig drei Dateien gleichzeitig (*Settings → SFTP Manager → [Parallele Übertragungen](../reference/settings/sftp.md#ubertragungen)*, 1 bis 8), jeweils über einen eigenen SFTP-Kanal. Ein Tab, der die Sitzung eines Terminals übernimmt, kopiert maximal zwei Dateien gleichzeitig. Die erste Übertragung öffnet die **Übertragungen**-Liste am unteren Rand des Tabs; der Pfeil links davon verkleinert sie auf die Überschrift. Jede Zeile ist eine Datei oder ein Ordner, den Sie übertragen haben, mit ihrer Richtung, einer Fortschrittsleiste, den bisher übertragenen Bytes, der Geschwindigkeit und der verbleibenden Zeit; eine Ordnerzeile summiert die darin enthaltenen Dateien. Die Statusleiste zeigt, wie viele Dateien aller laufenden Übertragungen abgeschlossen sind und die Gesamtschnelligkeit an, und sagt **Upload abgeschlossen** oder **Download abgeschlossen**, wenn ein Batch beendet ist.

![Übertragungsliste mit einem Ordner-Upload, einer fehlgeschlagenen Datei darin, einem laufenden Download und fertigen Zeilen](../assets/screenshots/sftp/sftp-transfer-list.png)

| Schaltfläche | Aktion |
|--------|--------|
| **Abbrechen** | Stoppt die ausgewählten Zeilen; eine abgebrochene Datei hinterlässt keine Teildatei, es sei denn, die Einstellungen behalten sie für ein späteres Fortsetzen |
| **Wiederholen** | Startet die ausgewählten fehlgeschlagenen oder abgebrochenen Zeilen erneut; für einen Ordner nur das, was nicht angekommen ist |
| **Alle abbrechen** | Stoppt alle Übertragungen, einschließlich einer offenen Frage **Datei existiert bereits** |
| **Fertige entfernen** | Entfernt die Zeilen, die fertig, übersprungen oder abgebrochen sind; fehlgeschlagene Zeilen bleiben für einen neuen Versuch stehen |

- **Fehler öffnen nicht mehr einen Fehlerdialog pro Datei.** Eine fehlgeschlagene Datei erhält eine eigene rote Zeile (auch wenn sie in einem übertragenen Ordner liegt), der Grund steht in ihrer Spalte **Status**, und sobald der Stapel abgeschlossen ist, listet ein einziges Zusammenfassungsfenster die fehlgeschlagenen Dateien und die verlinkten Ordner auf, denen nicht gefolgt wurde. Die Zusammenfassung blockiert den Tab nicht.
- **Die Listen folgen den Übertragungen.** Wenn Dateien in dem Ordner ankommen, den ein Panel anzeigt, wird dieses Panel kurz darauf neu eingelesen; ein Ordner, den Sie inzwischen verlassen haben, nicht.
- **Dateien kommen vollständig oder gar nicht an.** Eine Datei wird zuerst als `name.kortty-part` neben dem Ziel geschrieben und erhält ihren echten Namen erst, wenn sie vollständig ist, sodass eine unterbrochene Übertragung nie eine halb geschriebene Datei unter dem echten Namen hinterlässt. Die einzige Ausnahme ist das Ersetzen einer Serverdatei, die einem anderen Benutzer gehört; sie wird direkt an Ort und Stelle geschrieben (siehe [Wenn eine Datei bereits existiert](#wenn-eine-datei-bereits-existiert)).
- **Unterbrochene Übertragungen setzen dort fort, wo sie aufgehört haben.** Nach einem Fehler oder einem Verbindungsabbruch setzt **Wiederholen** eine unfertige Datei fort, statt von vorn zu beginnen; siehe [Fortsetzen unterbrochener Übertragungen](#fortsetzen-unterbrochener-ubertragungen).
- **Das Schließen des Tabs fragt nach, solange Übertragungen laufen.** Die Schließen-Schaltfläche des Tabs, *Datei > Tab schließen*, *Alle Tabs schließen*, das Schließen des Fensters und das Beenden von korTTY fragen zuerst **_n_ laufende Übertragungen abbrechen und schließen?**; **Tab geöffnet lassen** ist die Vorgabe und lässt alle Übertragungen weiterlaufen. Das Schließen bricht die nicht abgeschlossenen Übertragungen ab und löscht ihre Teildateien, es sei denn, *Einstellungen → SFTP-Manager* behält die Teildateien abgebrochener Übertragungen. Bei fertigen oder fehlgeschlagenen Zeilen wird nicht nachgefragt, und das automatische Schließen inaktiver Tabs wartet, bis die Übertragungen abgeschlossen sind.
- **Übrig gebliebene Teildateien** werden kursiv und mit einem Tooltip angezeigt. **Übrig gebliebene Teildateien entfernen** am Ende des Kontextmenüs beider Panels löscht nach einer Bestätigung die `.kortty-part`-Dateien im angezeigten Ordner; Teildateien noch laufender Übertragungen bleiben unberührt.

### Fortsetzen unterbrochener Übertragungen

Eine Übertragung, die fehlschlägt oder deren Verbindung abbricht, behält ihre Datei `name.kortty-part`, und **Wiederholen** (nach **Neu verbinden**, falls die Verbindung verloren ging) setzt sie dort fort, wo sie aufgehört hat. Während sie fortgesetzt wird, zeigt ihre Spalte **Status** **Wird fortgesetzt** an.

- **Nur wenn sich nichts geändert hat.** korTTY merkt sich beim Start der Übertragung Größe und Änderungszeit der Quelle. Ist die Quelle beim erneuten Versuch anders, ist die Teildatei größer als die Quelle oder stimmen die letzten 64 KB vor dem Fortsetzungspunkt nicht mehr mit der Quelle überein, wird die Teildatei entfernt und die Datei von Anfang an kopiert.
- **Nur Ihre eigenen Teildateien.** Auf dem Server wird eine Teildatei nur fortgesetzt, wenn sie noch eine normale Datei ist, die dem Benutzer gehört, als der Sie angemeldet sind; in einen Link oder eine Datei eines anderen Benutzers unter diesem Namen wird nie geschrieben. Auch auf Ihrem Computer wird der Teildatei nie über einen Link gefolgt. Eine Teildatei, die korTTY nicht für genau diese Übertragung hinterlassen hat (ein anderer Tab, ein anderer Computer oder ein anderer Benutzer schreibt sie vielleicht gerade), wird weder fortgesetzt noch entfernt: Die Übertragung schlägt mit einer Meldung fehl, die die Datei nennt, und einen echten Rest entfernen Sie mit **Übrig gebliebene Teildateien entfernen**.
- **Abbrechen entfernt die Teildatei**, es sei denn, *Einstellungen → SFTP-Manager → [Teildatei behalten, wenn eine Übertragung abgebrochen wird](../reference/settings/sftp.md#ubertragungen)* ist eingeschaltet. Ist dort **Unterbrochene Übertragungen fortsetzen** ausgeschaltet, wird jede unfertige Teildatei entfernt, und ein erneuter Versuch beginnt immer von vorn.
- **Was korTTY speichert.** Die gemerkten Größen und Zeiten liegen in `sftp-resume-index.json` im Konfigurationsordner von korTTY und sind nur für Sie lesbar. Ein Eintrag enthält einen Fingerabdruck der Verbindung und der beiden Pfade, nicht die Pfade oder Hostnamen selbst und nie Dateiinhalte; Einträge von Übertragungen, die Sie nie wiederholt haben, werden nach 30 Tagen verworfen. Die Datei ist nicht Teil der Konfigurationssicherung, weil die Teildateien, die sie beschreibt, auf diesem Computer und seinen Servern bleiben.
- **Übrig gebliebene Teildateien** von Übertragungen, die Sie aufgegeben haben, entfernen Sie mit **Übrig gebliebene Teildateien entfernen** im Kontextmenü beider Panels (siehe [Übertragungsliste](#ubertragungsliste)).

### Wenn eine Datei bereits existiert

Ein Upload oder Download ersetzt nie stillschweigend eine vorhandene Datei. Enthält der Zielordner bereits einen Eintrag mit demselben Namen, zeigt korTTY **Datei existiert bereits** mit Größe und Änderungszeit beider Seiten an (die spätere Zeit ist als neuer markiert) und bietet diese Möglichkeiten:

| Auswahl | Ergebnis |
|--------|--------|
| **Ersetzen** | Die vorhandene Datei wird durch die übertragene ersetzt |
| **Überspringen** | Der vorhandene Eintrag bleibt unverändert, und dieses Element wird nicht übertragen |
| **Beide behalten** | Das Element wird unter einem freien Namen mit einer Nummer übertragen, etwa `report (1).txt`; eine doppelte Endung bleibt zusammen (`backup (1).tar.gz`), und eine Punktdatei erhält die Nummer am Ende (`.bashrc (1)`) |
| **Übertragung abbrechen** | Der Rest des Stapels wird gestoppt; das Schließen des Dialogs oder ++escape++ bewirkt dasselbe |

![Dialog Datei existiert bereits bei einem Upload auf eine ältere Datei](../assets/screenshots/sftp/sftp-conflict-dialog.png)

- **Für alle weiteren Konflikte dieser Art übernehmen** beantwortet jeden späteren Konflikt derselben Art in diesem Stapel, ohne erneut zu fragen. Die Arten werden auseinandergehalten: Das Ersetzen aller Dateien ersetzt nie einen symbolischen Link oder einen Ordner.
- **Ein Ordner wird mit einem gleichnamigen Ordner zusammengeführt**, ohne nachzufragen; nur die darin enthaltenen Dateien können in Konflikt geraten.
- **Ein symbolischer Link wird nie ersetzt.** Ist der vorhandene Eintrag ein Link, werden nur **Überspringen** und **Beide behalten** angeboten, sodass eine Übertragung nicht über einen Link in eine Datei an anderer Stelle schreiben kann.
- **Eine Datei und ein Ordner können sich nicht gegenseitig ersetzen.** Ist eine Seite eine Datei und die andere ein Ordner, werden nur **Überspringen** und **Beide behalten** angeboten.
- **Eine Datei, die einem anderen Benutzer gehört, wird an Ort und Stelle geschrieben.** Gehört die vorhandene Remote-Datei einem anderen Benutzer, weist der Dialog darauf hin; beim Ersetzen wird direkt in die Datei geschrieben, sodass Eigentümer und Berechtigungen erhalten bleiben.
- **Immer nur eine Frage.** Geraten mehrere Dateien eines Stapels gleichzeitig in Konflikt, fragt korTTY nach einer davon und lässt die anderen warten, sodass eine Antwort für alle vor der nächsten Frage vorliegt. Das Schließen des Tabs beantwortet eine offene Frage mit **Übertragung abbrechen**.
- **Die Frage lässt sich im Voraus beantworten.** *Einstellungen → SFTP-Manager → [Wenn das Ziel bereits existiert](../reference/settings/sftp.md#ubertragungen)* kann vorhandene Dateien ohne Nachfrage überspringen oder überschreiben; bei Links und bei Konflikten zwischen Datei und Ordner wird weiterhin nachgefragt. Eine Organisation kann diese Auswahl für Sie festlegen.
- Auf einem Computer, dessen Datenträger nicht zwischen Groß- und Kleinschreibung unterscheidet (die Vorgabe unter macOS und Windows), gilt ein Name, der sich nur in der Groß-/Kleinschreibung unterscheidet, als derselbe Name, sowohl für die Frage als auch für den nummerierten Namen, den **Beide behalten** wählt.

### Wenn Ihre Organisation die Dateiübertragung abgeschaltet hat

Eine Organisation kann die Dateiübertragung mit ihrer [Unternehmensrichtlinie](../reference/enterprise-policy.md#rulefeatures) abschalten (`file-transfer = "deny"`). Der SFTP-Manager öffnet und durchsucht den Server dann weiterhin, und Umbenennen, Löschen, Berechtigungen, Archive, Suche und Kopieren auf dem Server funktionieren weiter, aber **Hochladen**, **Herunterladen** und **Wiederholen** in der Übertragungsliste sind ausgegraut, das Ziehen von Dateien auf das Server-Panel oder von Serverdateien auf das lokale Panel wird nicht angenommen (der Mauszeiger zeigt kein Kopiersymbol), und Remote-Dateien lassen sich nicht auf den Desktop ziehen. Dateien vom Desktop können weiterhin auf dem lokalen Panel abgelegt werden. Das Ablegen von Dateien auf einem Terminalbereich wird ebenso schon beim Ziehen abgelehnt, und **Als Datei(en) ins Terminal-Verzeichnis kopieren** und **Ordner ins Terminal-Verzeichnis kopieren** im Snippet-Manager sind für SSH-Tabs ausgegraut. **SFTP hier öffnen** öffnet den SFTP-Manager weiterhin zum Durchsuchen. JobScheduler-Jobs, die per SFTP hochladen, herunterladen oder synchronisieren, sowie rsync-Jobs schlagen mit derselben Meldung fehl, ohne eine Verbindung aufzubauen. Befehle wie `scp`, die in ein Terminal eingegeben werden, sind davon nicht betroffen.

### Berechtigungen

Das Berechtigungsfeld von **Besitzer/Berechtigungen setzen** und des Remote-Archivdialogs akzeptiert genau drei Oktalziffern, wie `755` oder `644`; lassen Sie es leer (oder unverändert), um den aktuellen Modus beizubehalten. Alles andere lässt das Dialogfeld mit einem Fehler offen, sodass kein anderer Text jemals `chmod` erreicht.

In **Besitzer/Berechtigungen setzen** zeigt ein Raster von Kontrollkästchen unter dem Feld den Modus als Lesen, Schreiben und Ausführen für Besitzer, Gruppe und andere an. Das Ankreuzen eines Kontrollkästchens schreibt die drei Ziffern in das Feld, und das Eingeben von drei Ziffern aktiviert die Kontrollkästchen. Neben dem Feld wird der Modus ausgeschrieben wie `ls -l` es anzeigt (`rwxr-xr-x`), oder als *unverändert*, wenn das Feld leer ist. Für einen Ordner bedeutet Ausführen, dass der Ordner geöffnet werden kann und sein Inhalt erreicht werden kann.

**Besitzer/Berechtigungen setzen** ändert auch den Benutzer und die Gruppe lokaler Dateien und Ordner; ein anderer Besitzer benötigt in der Regel Administratorrechte. **Rekursiv auf Verzeichnisse anwenden** überspringt symbolische Links innerhalb eines Ordners, genau wie `chmod -R`. Ein unbekannter Name oder eine abgelehnte Änderung wird mit dem Namen des Eintrags gemeldet.

![Set Owner/Permissions with the permission checkboxes](../assets/screenshots/sftp/sftp-permissions-dialog.png)

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

- **Remote-Datei überschreiben** (**Lokale Datei überschreiben** für eine Datei im linken Panel) — schreibt den aktuellen Editorinhalt zurück in die ursprüngliche Datei
- **Speichern unter...** — schreibt eine neue Datei an einen mit einem Klick ausgewählten Ort: im Speicherdialog des Systems für eine lokale Datei, in den Ordnern und Dateien des Servers für eine entfernte Datei
- **Als Snippet speichern** – speichert den aktuellen Inhalt als neues Snippet-Manager-Snippet, ohne die Quelldatei als gespeichert zu markieren

Eine Organisation kann das Bearbeiten von Serverdateien mit dem Schlüssel `load-into-snippet-editor` ihrer [Unternehmensrichtlinie](../reference/enterprise-policy.md) einschränken. Mit `read-only` öffnen sich Remote-Dateien weiterhin im Snippet-Editor und können als Snippet gespeichert werden, aber **Datei überschreiben** und **Speichern unter...** sind gesperrt, und ihr Tooltip nennt die Richtlinie. Mit `deny` sind **Im Snippet-Editor bearbeiten** und **Bild öffnen** für Remote-Dateien deaktiviert. Lokale Dateien im linken Panel liegen auf Ihrem eigenen Computer und bleiben in jedem Fall bearbeitbar.

### Im eigenen Editor bearbeiten

**In externem Editor bearbeiten** (im Rechtsklick-Menü einer Serverdatei und im Remote **Bearbeiten** Menü) öffnet die ausgewählte Serverdatei in Ihrem eigenen Texteditor und kopiert jede Speicherung zurück zum Server. Wählen Sie den Editor unter [*Settings → SFTP Manager → External editor*](../reference/settings/sftp.md#externer-editor); ohne einen öffnet macOS die Datei im Standard-Texteditor, Windows nutzt seinen Edit-Befehl (Skripte gehen zu Notepad), und Linux öffnet Textdateien mit der Standardanwendung für Text und fragt nach einem Editor-Befehl für andere Dateitypen.

- **Lokale Kopie** — die Datei wird in einen privaten Ordner namens `kortty-remote-edit-…` im temporären Ordner des Systems heruntergeladen, den nur Sie öffnen können, und zwar unter einem bereinigten Namen: Andere Zeichen als Buchstaben, Ziffern, `.`, `_`, `-` und Leerzeichen werden zu `_`, und Windows-Gerätenamen wie `CON` erhalten ein vorangestelltes `_`. Einem symbolischen Link wird einmal gefolgt, und bearbeitet wird die Datei, auf die er zeigt.
- **Automatischer Upload** — korTTY prüft die Kopie jede Sekunde. Etwa eine Sekunde nach dem Speichern wird der neue Inhalt hochgeladen, und die Statusleiste meldet **hochgeladen** mit dem Dateinamen; erneutes Speichern desselben Inhalts lädt nichts hoch. Der Upload funktioniert wie jeder andere: über eine `.kortty-part`-Datei, die die Berechtigungen der Datei beibehält, oder direkt an Ort und Stelle geschrieben, wenn die Datei einem anderen Benutzer gehört, sodass ihr Eigentümer erhalten bleibt.
- **Bearbeitete Serverdateien** — die Liste unter der Übertragungsliste zeigt jede bearbeitete Datei, ob sie auf ein Speichern wartet, gerade hochlädt oder hochgeladen wurde (mit Uhrzeit), und bietet **Jetzt hochladen** für Editoren, deren Speichern nicht erkannt wird, sowie **Stoppen**.
- **Konflikte** — vor jedem Upload prüft korTTY, ob die Serverdatei noch die ist, die es heruntergeladen hat (Größe, Zeit, Eigentümer und bis 10 MB auch der Inhalt). Hat jemand anderes sie geändert oder gelöscht, fragt es nach: **Serverdatei überschreiben**, **Lokale Kopie speichern unter...** (behält Ihre Version auf diesem Computer und beendet die Beobachtung), **Beobachten beenden** oder **Später entscheiden** (beobachtet weiter; hochgeladen wird erst beim nächsten Speichern oder mit **Jetzt hochladen**).
- **Aufräumen** — **Stoppen**, das Schließen des SFTP-Tabs und das Beenden von korTTY löschen die lokale Kopie. Geht die Verbindung verloren, bevor Ihr letztes Speichern hochgeladen wurde, bietet korTTY zuerst **Kopie speichern unter...** an. Ordner, die ein Absturz hinterlassen hat, werden beim nächsten Start entfernt, sobald sie einen Tag alt sind.

Der Editor wird immer direkt gestartet, nie über eine Shell oder als Standardanwendung der Datei, sodass ein heruntergeladenes Skript nie ausgeführt wird. Mit `load-into-snippet-editor = "read-only"` in der [Unternehmensrichtlinie](../reference/enterprise-policy.md) öffnet sich die Datei, aber gespeicherte Änderungen bleiben auf Ihrem Computer, und die Liste weist darauf hin; `load-into-snippet-editor = "deny"` oder `file-transfer = "deny"` graut **In externem Editor bearbeiten** aus.

### Als root bearbeiten

**Öffnen mit → Als root bearbeiten (sudo)...** (im Rechtsklickmenü einer Serverdatei, sowie **Als root bearbeiten (sudo)...** im Remote-Menü **Bearbeiten**) öffnet eine Datei, die nur root ändern darf, etwa eine Konfigurationsdatei unter `/etc`, in Ihrem eigenen Editor, genauso wie unter [Im eigenen Editor bearbeiten](#im-eigenen-editor-bearbeiten). Die Zeile in **Bearbeitete Serverdateien** trägt ein **sudo**-Badge.

![Als root bearbeiten: mit sudo cat lesen, lokal bearbeiten, jedes Speichern über stdin in einen root gehörenden Staging-Ordner senden, prüfen, an Ort und Stelle schreiben, aufräumen](../assets/diagrams/sftp-sudo-edit-flow.svg)

- **Passwort** — korTTY versucht zuerst `sudo -n`, sodass mit `NOPASSWD` oder einem noch gültigen sudo-Ticket nichts abgefragt wird. Andernfalls fragt es in einem maskierten Dialog nach Ihrem Passwort. Hat der [JobScheduler](jobscheduler.md) für den Server oder dessen Gruppe ein sudo-Passwort gespeichert, bietet der Dialog an, es ab jetzt für diesen Server zu verwenden; ohne diese Wahl verwendet korTTY es nie, und wenn Sie das Kästchen später abwählen, wird sie zurückgenommen. Das Passwort wird nur so lange aufbewahrt, wie die Datei bearbeitet wird, nur dann an sudo übergeben, wenn sudo tatsächlich danach fragt, und beim Stoppen gelöscht. Es erscheint nie in einer Befehlszeile, im Log, in einer Fehlermeldung oder im Sitzungsjournal.
- **Lesen** — die Datei wird mit `sudo cat` direkt in die private lokale Kopie gelesen; dafür wird auf dem Server nichts geschrieben.
- **Speichern** — jedes Speichern wird über die Verbindung auf den Server übertragen, in einen Staging-Ordner, den root anlegt und besitzt, und erst wenn Größe und SHA-256 übereinstimmen, wird es in die Datei selbst geschrieben. Die Datei behält Inode, Eigentümer, Berechtigungen, ACLs und SELinux-Label, und eine abgebrochene Übertragung lässt sie nie halb geschrieben zurück. Der Staging-Ordner wird in jedem Fall entfernt. Vor jedem Speichern liest korTTY die Datei erneut als root; hat jemand anderes sie inzwischen geändert, erscheint dieselbe Konfliktfrage wie oben.
- **Links** — ist der Pfad ein symbolischer Link oder führt er durch einen Ordner, der einer ist, zeigt korTTY an, wo die Datei wirklich liegt, und bearbeitet sie dort erst nach Ihrer Bestätigung. Unmittelbar vor jedem Lesen und Schreiben prüft root erneut, dass die Datei kein Link ist und ihr Ordner noch derselbe reale Ordner ist.
- **Abgelehnt** — eine Datei in einem Ordner, in den Sie ohne sudo schreiben können, oder irgendwo unterhalb eines solchen Ordners, wird nicht als root bearbeitet, weil alles, was unter Ihrem Benutzer läuft, die Datei oder einen Ordner auf dem Weg dorthin zwischenzeitlich ersetzen könnte; die Meldung nennt diesen Ordner, und Sie bearbeiten die Datei normal. Dateien über 64 MB werden ebenfalls abgelehnt.
- **Journal** — zeichnet ein Terminal auf derselben Verbindung ein [Sitzungsjournal](session-journal.md) auf, erhält es eine Zeile mit `sudo-edit` und dem Pfad, nie den Inhalt.
- **Was der Server braucht** — `sudo`, eine POSIX-`sh` mit `mktemp`, `cat` und `stat` sowie `sha256sum` oder `shasum` für die Inhaltsprüfung. Ohne eines der beiden Hash-Werkzeuge vergleicht korTTY vor dem Schreiben weiterhin die Größe, kann aber den Inhalt nicht vergleichen.
- **Server mit `requiretty`** — enthält die sudo-Konfiguration des Servers `Defaults requiretty`, verweigert sudo die Ausführung ohne Terminal, und korTTY meldet **sudo verlangt auf diesem Server ein Terminal (requiretty), und korTTY führt sudo nie mit einem Terminal aus**. korTTY umgeht das nicht, indem es ein Terminal öffnet: Ein Terminal verändert die durchlaufenden Bytes (Zeilenenden, Steuerzeichen) und gibt das Gesendete als Echo zurück, sodass weder die Datei noch das Passwort unverändert und ungesehen ankämen. Ein Administrator kann die Regel für Ihr Konto mit einer Zeile wie `Defaults:alice !requiretty` (bearbeitet mit `visudo`) aufheben; bis dahin bearbeiten Sie die Datei in einem Terminal-Tab, zum Beispiel mit `sudoedit`.

Die [Unternehmensrichtlinie](../reference/enterprise-policy.md) kann die Funktion mit `sftp-sudo-edit = "deny"` abschalten; sie ist außerdem ausgegraut, solange `file-transfer` verweigert wird oder `load-into-snippet-editor` nicht `allow` ist.

## Suche

Das Suchfeld über jeder Liste filtert den angezeigten Ordner während der Eingabe, ohne ihn zu verlassen; der übergeordnete Eintrag `..` bleibt sichtbar. korTTY bewertet die Platzhalter selbst, gleichartig für lokale und entfernte Namen. Ein Muster mit einem Platzhalter muss den gesamten Namen entsprechen, ohne Groß-/Kleinschreibung:

| Platzhalter | Trifft auf | Beispiel |
|----------|---------|---------|
| `*` | Beliebig viele Zeichen | `*.log` findet alle Logdateien, `backup*` alle Namen beginnend mit "backup" |
| `?` | Genau ein Zeichen | `data?.csv` findet `data1.csv`, nicht `data10.csv` |
| `[abc]`, `[a-z]` | Eines der aufgeführten Zeichen; `[!abc]` oder `[^abc]` eines, das nicht aufgeführt ist | `[ab]*` findet Namen beginnend mit a oder b |
| `{py,sh}` | Eine der Alternativen | `*.{py,sh}` findet Python- und Shell-Dateien |

Text ohne Platzhalter passt überall im Namen, Groß-/Kleinschreibung ignorierend: `rep` findet `Report.txt`. Ein Muster, das nicht gelesen werden kann, wie ein nicht geschlossenes `[`, wird als einfacher Text gesucht.

### Unterordner auf dem Server durchsuchen

Aktivieren Sie **Unterordner einbeziehen** neben dem Remote-Suchfeld und drücken Sie ++enter++, um den angezeigten Remote-Ordner und jeden Ordner darunter zu durchsuchen, mit denselben Platzhaltern und ebenfalls ohne Unterscheidung von Groß- und Kleinschreibung. Die Remote-Liste wechselt zu den Ergebnissen: Jeder Treffer zeigt seinen Pfad unterhalb des durchsuchten Ordners, und die Zeile über der Liste zählt die Treffer, während die Suche läuft, und meldet, wie sie endete, zum Beispiel *120 Treffer* oder *5000 Treffer, am Limit gestoppt*. Doppelklicken Sie auf einen Treffer oder wählen Sie ihn aus und drücken Sie ++enter++, um seinen Ordner mit dem ausgewählten Treffer zu öffnen. **Zurück zum Ordner** kehrt zur Ordnerliste zurück, und ++esc++ im Suchfeld stoppt eine laufende Suche (ein zweites ++esc++ verlässt die Ergebnisse).

| Limit | Wert |
|-------|-------|
| Ordnerebenen unterhalb des durchsuchten Ordners | 10 |
| Treffer | 5000 |
| Zeit | 60 Sekunden |

Wenn der Server korTTY das Ausführen von Befehlen erlaubt und `find`, läuft die Suche dort und nur die passenden Pfade werden über die Verbindung übertragen; andernfalls durchsucht korTTY die Ordner per SFTP, was langsamer ist und nach dem Lesen von 200.000 Einträgen stoppt. Symbolische Links werden aufgelistet, aber nie verfolgt, sodass ein Link, der zurück zum Stamm führt, die Suche nicht in eine Schleife bringt. **Auf diesem Dateisystem bleiben** (standardmäßig aktiviert und nur mit **Unterordner einbeziehen**) hält `find` von anderen gemounteten Dateisystemen wie Netzwerklaufwerken und `/proc` fern; die Durchsuchung per SFTP kann Dateisysteme nicht unterscheiden. Ordner, die Sie nicht lesen können, werden übersprungen. Die Suche liest nur Namen auf dem Server, sodass sie verfügbar bleibt, wenn eine Unternehmensrichtlinie Dateitransfers blockiert.

---

