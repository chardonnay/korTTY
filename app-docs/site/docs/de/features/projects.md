---
title: Projekte (Arbeitsbereiche)
---

# Projekte (Arbeitsbereiche)

Projekte speichern und stellen Ihren gesamten Arbeitsbereichsstatus wieder her – alle geöffneten Fenster, Registerkarten, SSH-Verbindungen und Terminalsitzungen. Dadurch können Sie schnell zwischen verschiedenen Arbeitskontexten wechseln, ohne Fenster manuell neu zu verbinden oder neu zu positionieren.

## Ein Projekt speichern

1. Öffnen Sie *Datei > Projekt speichern* oder drücken Sie ++ctrl+s++ (++cmd+s++ unter macOS).
2. Geben Sie einen **Namen** und optional eine **Beschreibung** für das Projekt ein.
3. Konfigurieren Sie **Auto-Reconnect**:
   - Wenn aktiviert, verbindet sich beim Öffnen des Projekts automatisch jeder gespeicherte Terminal- und SFTP-Tab erneut; ein Tab, der ein Passwort, einen neuen temporären SSH-Schlüssel oder den gesperrten Tresor braucht, wartet stattdessen in der [Wiederherstellungsleiste](#tabs-die-auf-sie-warten).
   - Wenn deaktiviert, stellt das Öffnen des Projekts nur die Fenster mit ihrer Position und Größe sowie die Tabs des lokalen Dateieditors und Bildbetrachters wieder her; Terminal-, SFTP- und Remote-Datei-Tabs werden übersprungen.
4. Klicken Sie auf *Speichern*.

Projekte sind `.kortty`-Dateien, die Sie an beliebiger Stelle im Speicherdialog speichern können, zum Beispiel in `~/.kortty/projects/`.

Ein Projekt behält jedes offene korTTY-Fenster, nicht nur das, in dem Sie *Projekt speichern* wählen; dieses Fenster wird zuerst gespeichert. Ein Fenster, das nur KI-Ergebnis- und Werkzeug-Tabs enthält, die Projekte nicht behalten, wird ausgelassen, damit es nicht leer zurückkommt; das Fenster, aus dem Sie speichern, wird wegen seiner Position und Größe immer behalten.

## Ein Projekt öffnen

1. Öffnen Sie *Datei > Projekt öffnen* oder drücken Sie ++ctrl+o++ (++cmd+o++ unter macOS).
2. Wählen Sie im Dateibrowser eine `.kortty`-Projektdatei aus.
3. Das Projekt öffnet sich sofort. Datei- und Snippet-Editoren mit ungespeicherten Änderungen im Fenster fragen zuerst nach; dann ersetzt das Projekt die Tabs des Fensters, und jedes weitere im Projekt gespeicherte Fenster öffnet sich in einem neuen Fenster, siehe [Fenster](#fenster).

### Fenster

Beim Öffnen eines Projekts übernimmt das Fenster, aus dem Sie es öffnen, sein erstes Fenster, und jedes weitere Fenster, das es enthält, öffnet sich in einem neuen Fenster. Bereits offene Fenster behalten ihre Tabs. Projekte, die mit früheren Versionen gespeichert wurden, enthalten nur das Fenster, aus dem sie gespeichert wurden, und öffnen sich wie bisher in einem einzigen Fenster.

- **Position und Größe** — jedes Fenster kommt an seiner früheren Stelle und in seiner Größe zurück, maximiert, wenn es maximiert war. Ist der Bildschirm, auf dem ein Fenster war, nicht mehr angeschlossen, öffnet sich das Fenster zentriert auf dem Hauptbildschirm in seiner gespeicherten Größe, bei Bedarf so verkleinert, dass es passt. Ein maximiertes Fenster behält die Größe, die es vor dem Maximieren hatte, sodass Sie diese Größe zurückbekommen, wenn Sie die Maximierung später aufheben. Der Vollbildmodus wird nicht gespeichert, und das Fenster, aus dem Sie das Projekt öffnen, bleibt im Vollbildmodus, wenn es sich darin befindet.
- **Tabs** — die Tabs kommen in ihrer gespeicherten Reihenfolge zurück und werden wie gewohnt in ihre [Tab-Gruppen](terminal.md#arbeiten-mit-tabs) einsortiert.
- **Aktiver Tab** — jedes Fenster wählt den Tab aus, der beim Speichern aktiv war. Ein Tab eines Remote-Datei-Editors oder Remote-Bildbetrachters öffnet sich erst, wenn seine Datei heruntergeladen ist; war er der aktive Tab, wählt das Fenster ihn aus, sobald er da ist, es sei denn, Sie haben inzwischen einen anderen Tab gewählt oder der Download dauert länger als 10 Sekunden. Kommt der aktive Tab nicht sofort zurück, zum Beispiel weil er in der [Wiederherstellungsleiste](#tabs-die-auf-sie-warten) auf ein Passwort wartet oder seine Verbindung gelöscht wurde, behält das Fenster den Tab, den es gerade anzeigt; wenn Sie ihn später aus der Wiederherstellungsleiste öffnen, wählt das Fenster ihn dann aus.
- **Dashboard** — jedes Fenster zeigt oder verbirgt das Dashboard so, wie es beim Speichern war.
- **Ohne Auto-Reconnect** öffnen sich die Fenster ebenfalls, mit ihren lokalen Datei-Editor- und Bildbetrachter-Tabs; ein Fenster, dessen Tabs alle übersprungen wurden, öffnet sich leer.
- Ein Projekt öffnet höchstens 32 Fenster; eine Projektdatei mit mehr Fenstern, die korTTY nur schreibt, wenn so viele Fenster offen waren, öffnet ihre ersten 32.

## Zuletzt verwendet

*Datei → Zuletzt verwendet* bringt Sie ohne Dateidialog oder Connection-Manager zu dem zurück, was Sie zuletzt verwendet haben:

- **Verbindungen** — die gespeicherten Verbindungen, die Sie zuletzt verwendet haben, bis zu 10, die zuletzt verwendete zuerst: dieselben Verbindungen wie die Schaltflächen oben in der Schnellverbindung, die mitzählen, was Sie aus der Schnellverbindung, der [Befehlspalette](command-palette.md), mit **Gruppe öffnen** und aus diesem Menü öffnen (eine aus dem Connection-Manager geöffnete Verbindung wird nicht gezählt). Jeder Eintrag zeigt den Namen der Verbindung und `user@host`. Die Auswahl eines Eintrags öffnet dafür einen Tab, der sich wie **Verbinden** im Connection-Manager anmeldet (siehe [Anmelden](connections.md#anmelden)), wobei zuerst die [Serverzugriffsrichtlinie](../reference/enterprise-policy.md#server-zugriffskontrolle) geprüft wird, und zählt als Nutzung, sodass die Verbindung nach oben rückt.
- **Projekte** — die Projektdateien, die Sie zuletzt geöffnet oder gespeichert haben, bis zu 10, die neueste zuerst, gefolgt von den übrigen `.kortty`-Dateien in `~/.kortty/projects`, die zuletzt geänderte zuerst. Jeder Eintrag zeigt den Dateinamen und seinen Ordner, wobei Ihr Home-Ordner als `~` geschrieben wird. Die Auswahl eines Eintrags öffnet das Projekt wie *Datei → Projekt öffnen…*: Editoren mit ungespeicherten Änderungen fragen zuerst nach, das Projekt ersetzt die Tabs des Fensters, und weitere darin gespeicherte Fenster öffnen sich in neuen Fenstern. Eine Projektdatei, die verschoben oder gelöscht wurde, wird im Menü ausgelassen.
- **Liste leeren** leert das Menü. Dabei wird keine Verbindung und keine Projektdatei gelöscht: Eine Verbindung erscheint wieder, sobald Sie sie erneut verwenden, ein Projekt, sobald Sie es öffnen oder speichern.

Das Menü wird jedes Mal neu aufgebaut, wenn Sie das Menü *Datei* öffnen. Unter macOS, wo korTTY nach dem Schließen seines letzten Fensters weiterläuft, wird ein Eintrag, den Sie in der Menüleiste wählen, in einem neuen Fenster geöffnet.

korTTY speichert die Pfade Ihrer zuletzt verwendeten Projekte und den Zeitpunkt, zu dem Sie zuletzt **Liste leeren** gewählt haben, in seiner Einstellungsdatei `~/.kortty/global-settings.xml` (siehe [Konfigurationsdateien](../reference/config-files.md#global-settingsxml)). Sie sind daher Teil jeder [Konfigurationssicherung](backup.md) und kommen zurück, wenn Sie eine wiederherstellen. Gespeichert werden nur die Pfade, nie der Inhalt eines Projekts, und die Verbindungen werden nicht ein zweites Mal gespeichert: Die Liste richtet sich danach, wann jede gespeicherte Verbindung zuletzt verwendet wurde.

## Vorherige Sitzung

Während korTTY läuft, hält es einen Snapshot Ihrer offenen Fenster und Tabs vor, sodass Sie diese nach einem Neustart oder Absturz zurückbekommen, ohne vorher ein Projekt zu speichern. Beim Start bietet korTTY sie in einer Leiste an (siehe [Beim Start](#beim-start)), und *Datei → Vorherige Sitzung wiederherstellen* öffnet die Fenster und Tabs, die korTTY vor diesem Start geöffnet hatte.

- **Wann er gespeichert wird** — ein paar Sekunden nach jeder Änderung an den Fenstern und Tabs: Ein Tab wird geöffnet, geschlossen, verschoben, umbenannt oder geteilt, ein Fenster wird geöffnet, geschlossen, verschoben oder in der Größe geändert, das Dashboard wird ein- oder ausgeblendet. Wenn Sie korTTY beenden oder unter Windows und Linux sein letztes Fenster schließen, wird der Snapshot noch einmal geschrieben, solange noch jeder Tab offen ist, sodass er alle Fenster enthält, die Sie hatten, obwohl sie sich beim Beenden von korTTY nacheinander schließen.
- **Was er enthält** — dasselbe wie ein Projekt mit **Auto-Reconnect** (siehe [Was gespeichert wird](#was-gespeichert-wird)): jedes Fenster mit seiner Position und Größe, seine Tabs in ihrer Reihenfolge mit ihren Tab-Gruppen, Namen, geteilten Bereichen, Schriftgrößen und Terminal-Effekten, SFTP-Manager-Tabs mit ihren Ordnern, Tabs des Dateieditors und Bildbetrachters sowie den aktiven Tab und das Dashboard jedes Fensters. Tabs, die noch in der [Wiederherstellungsleiste](#tabs-die-auf-sie-warten) oder auf ihre Remote-Datei warten, werden ebenfalls an ihrer Stelle behalten, ebenso die geteilten Bereiche eines wiederhergestellten Tabs, der sich noch nicht verbunden hat.
- **Was er nie enthält** — Bildschirmtext, Scrollback, Befehlszeitstempel, Passwörter und temporäre SSH-Schlüssel. Jeder Tab verweist wie in einem Projekt über die ID auf seine gespeicherte Verbindung.
- **Eine Sitzung ohne Tabs behält die letzte** — wenn kein Tab offen ist, zum Beispiel nachdem Sie unter macOS, wo korTTY weiterläuft, das letzte Fenster geschlossen haben, behält der Snapshot die Fenster, die er hatte, und übernimmt nur die neue Liste „Zuletzt geschlossen“.

**Vorherige Sitzung wiederherstellen** öffnet die vorherige Sitzung wie ein Projekt mit Auto-Reconnect und fragt nichts, während die Tabs geöffnet werden: Eine lokale Shell, SSH-Schlüssel-Authentifizierung, ein gespeichertes Passwort und ein noch gültiger temporärer SSH-Schlüssel öffnen sofort, und jeder Tab, der ein Passwort, einen neuen temporären SSH-Schlüssel oder den gesperrten Tresor braucht, wartet in der [Wiederherstellungsleiste](#tabs-die-auf-sie-warten), wo auch ein blockierter Server oder eine gelöschte Verbindung aufgeführt wird. Das erste Fenster der Sitzung öffnet sich in dem Fenster, in dem Sie den Befehl wählen, wenn dieses Fenster keine Tabs hat, und sonst in einem neuen Fenster; jedes weitere Fenster öffnet sich in einem neuen Fenster, und die bereits offenen Fenster behalten ihre Tabs. Der Befehl ist ausgegraut, solange es keine vorherige Sitzung gibt, und nachdem Sie sie einmal wiederhergestellt haben, damit sich kein Tab zweimal öffnet.

Die vorherige Sitzung ist die letzte, in der korTTY Tabs geöffnet hatte: Bei jedem Start macht korTTY die Sitzung des letzten Laufs zur vorherigen, aber nur, wenn sie mindestens einen Tab hatte. Ein Start, bei dem Sie nichts öffnen, verdrängt Ihre vorherige Sitzung daher nie.

Die Liste [Zuletzt geschlossen](terminal.md#arbeiten-mit-tabs) wird im selben Snapshot gespeichert und übersteht daher einen Neustart. Sie behält nur die ID der gespeicherten Verbindung jedes geschlossenen Tabs, mit der Tab-Gruppe, dem Namen und dem Terminal-Effekt: Nach einem Neustart öffnet sich ein geschlossener Tab mit der gespeicherten Verbindung in ihrem dann aktuellen Stand wieder, und ein Tab, dessen Verbindung gelöscht oder nie gespeichert wurde (eine Schnellverbindungs-Sitzung), wird nicht mehr aufgeführt.

korTTY speichert die Snapshots in `~/.kortty/session/` (siehe [Konfigurationsdateien](../reference/config-files.md#session)), nur für Sie lesbar. Sie beschreiben die Fenster dieses Computers und sind nicht Teil einer [Konfigurationssicherung](backup.md). Ein zweites korTTY, das gestartet wird, während das erste läuft, speichert weder seine Sitzung noch verschiebt es die vorherige, sodass sich die beiden nie gegenseitig überschreiben; es kann die vorherige Sitzung trotzdem wiederherstellen. Solange ein wiederhergestelltes Backup mit einem anderen Master-Passwort auf den Neustart wartet, speichert korTTY nichts.

### Beim Start

Wenn korTTY startet und die Sitzung vor diesem Start mindestens einen Tab hatte, bietet eine Leiste über der Statuszeile des Fensters sie an, zum Beispiel *Fenster und Tabs von vor diesem Start wiederherstellen? Fenster: 2, Tabs: 6*. Die Leiste blockiert das Fenster nie, und nichts öffnet sich, bevor Sie wählen:

- **Wiederherstellen** öffnet die vorherige Sitzung wie *Datei → Vorherige Sitzung wiederherstellen*.
- **Verwerfen** blendet die Leiste aus und lässt das Fenster, wie es ist. *Datei → Vorherige Sitzung wiederherstellen* öffnet die Sitzung in diesem Lauf weiterhin, aber der nächste Start bietet sie nicht erneut an; wenn Sie selbst Tabs öffnen, sind diese die Sitzung, die der nächste Start anbietet.
- Wenn Sie keines von beiden tun und selbst keinen Tab öffnen, behält der Snapshot die angebotene Sitzung, sodass der nächste Start sie erneut anbietet, auch nach einem Absturz.

*Einstellungen → Fenster → [Sitzungswiederherstellung](../reference/settings/window.md#sitzungswiederherstellung)* bestimmt, was beim Start geschieht:

| Beim Start | Was korTTY tut |
| --- | --- |
| Wiederherstellen der vorherigen Sitzung anbieten (Standard) | Zeigt die oben beschriebene Leiste. |
| Vorherige Sitzung automatisch wiederherstellen | Stellt die vorherige Sitzung von selbst wieder her, sobald kein Dialog offen ist, zum Beispiel nachdem Sie die Frage zu [anonymen Daten](../about/anonymous-data.md) beantwortet haben, damit die Fragen, die ihre Verbindungen stellen können (ein Hostschlüssel, ein Passwort, ein Zugriffsgrund), nie über einem anderen Dialog erscheinen. Bleibt ein Dialog eine Minute lang offen, zeigt korTTY stattdessen die Leiste an. |
| Nichts tun | Zeigt nichts an; *Datei → Vorherige Sitzung wiederherstellen* öffnet die vorherige Sitzung, wenn Sie sie brauchen. |

Die automatische Wiederherstellung fragt nichts, was *Vorherige Sitzung wiederherstellen* nicht auch fragen würde: Tabs, die ein Passwort, einen neuen temporären SSH-Schlüssel oder den gesperrten Tresor brauchen, warten in der [Wiederherstellungsleiste](#tabs-die-auf-sie-warten). Endet korTTY innerhalb einer Minute nach dem Wiederherstellen einer Sitzung, ohne normal beendet zu werden (ein Absturz, ein erzwungenes Beenden oder ein Stromausfall), zeigt der nächste Start die Leiste, statt automatisch wiederherzustellen, und meldet, dass korTTY nach der letzten Wiederherstellung unerwartet beendet wurde, sodass eine Sitzung, die korTTY abstürzen lässt, das nicht bei jedem Start tun kann. Ein zweites korTTY, das gestartet wird, während das erste läuft, bietet die Sitzung nie an und stellt sie nie wieder her.

## Was gespeichert wird

Ein Projekt erfasst den vollständigen Zustand Ihres Arbeitsbereichs:

| Komponente | Einzelheiten |
|-----------|---------|
| **Fenster** | Jedes offene korTTY-Fenster mit seiner Position und Größe und ob es maximiert war (siehe [Fenster](#fenster)) |
| **Tabs** | Alle Terminal-Tabs in jedem Fenster, einschließlich ihrer [geteilten Bereiche](#geteilte-bereiche) mit dem Server jedes Bereichs und der Position jeder Trennlinie, und des Namens eines [umbenannten Tabs](terminal.md#arbeiten-mit-tabs), dazu SFTP-Manager-Tabs mit den lokalen und entfernten Ordnern, die sie anzeigen, sowie Tabs des Dateieditors und des Bildbetrachters |
| **Verbindungen** | Ein Verweis auf die gespeicherte Verbindung jedes Tabs, anhand der internen ID der Verbindung, sodass das Umbenennen einer Verbindung das Projekt nicht bricht |
| **Dashboard** | Ob das Dashboard in jedem Fenster angezeigt wurde |
| **Aktiver Tab** | Welcher Tab in jedem Fenster aktiv war, anhand der Sitzungs-ID des Tabs, damit er wiedergefunden wird, nachdem die Tabs in ihre Gruppen einsortiert wurden |
| **Terminal-Sitzungen** | Der letzte sichtbare Bildschirm des primären Bereichs jedes Terminal-Tabs — nicht dessen Scrollback und nicht die Cursorposition; die Bildschirme weiterer geteilter Bereiche werden nicht gespeichert |

!!! note
    KI-Ergebnis-Tabs und Werkzeug-Tabs (Manager als Tabs geöffnet) werden nicht mit Projekten gespeichert. Sie bleiben nur in der aktuellen Sitzung und gehen verloren, wenn Sie den Tab schließen oder ein Projekt öffnen. SFTP-Manager-Tabs sind in diesem Sinne keine Werkzeug-Tabs: sie werden gespeichert, siehe [SFTP-Manager-Tabs](#sftp-manager-tabs).

## Automatische Wiederverbindung

Wenn **Auto-Reconnect** aktiviert ist, führt KorTTY automatisch Folgendes durch:

- Stellt alle Fenster mit ihrer gespeicherten Position und Größe wieder her, siehe [Fenster](#fenster)
- Verbindet jeden SSH-Tab mit den ursprünglichen Verbindungseinstellungen erneut und öffnet jeden lokalen Shell-Tab wieder
- Öffnet jeden Tab, der sich ohne Nachfrage anmelden kann – eine lokale Shell, SSH-Schlüssel-Authentifizierung, ein gespeichertes Passwort oder ein noch gültiger temporärer SSH-Schlüssel –, und führt die anderen in einer Leiste über der Statuszeile auf, siehe [Tabs, die auf Sie warten](#tabs-die-auf-sie-warten)
- Gibt jeder umbenannten Terminal-Registerkarte ihren Namen zurück; eine Registerkarte, die Sie nie umbenannt haben, zeigt den aktuellen Namen der Verbindung
- Zeigt jeden Tab des Terminals mit dem gespeicherten Bildschirm, der über die neue Sitzung abgedunkelt ist, eingerahmt von einer *Wiederhergestellten Ausgabe aus* Zeile mit dem Datum, an dem das Projekt gespeichert wurde, und einer *Ende der wiederhergestellten Ausgabe* Zeile. Der Text wird nur in das lokale Terminal geschrieben und niemals an den Server gesendet: Er erreicht die Remote-Shell oder deren Befehlsverlauf nicht, und ein Sitzungsjournal, das mit der Verbindung beginnt, zeichnet ihn nicht auf (ein Journal, das Sie später aktivieren, importiert den Scrollback, der dann die wiederhergestellten Zeilen enthält). Steuerzeichen und Escape-Sequenzen werden daraus entfernt, bevor er angezeigt wird.
- Bringt die geteilten Bereiche jedes Terminal-Tabs zurück, sobald der Tab verbunden ist, siehe [Geteilte Bereiche](#geteilte-bereiche)
- Öffnet jeden SFTP-Manager-Tab erneut in den lokalen und entfernten Ordnern, die er beim Speichern des Projekts zeigte
- Wählt den aktiven Tab jedes Fensters aus und zeigt oder verbirgt sein Dashboard wie gespeichert

Wenn **Automatisches Wiederverbinden** deaktiviert ist, werden Terminal-Tabs nicht wiederhergestellt: Das Öffnen des Projekts öffnet die gespeicherten Fenster an ihrer Position und in ihrer Größe und öffnet lokale Datei-Editor- und Bildbetrachter-Tabs erneut, während Terminal-, SFTP- und Remote-Datei-Tabs übersprungen werden. Öffnen Sie diese Verbindungen erneut im Connection-Manager.

### Tabs, die auf Sie warten

Mit **Automatisches Wiederverbinden** fragt das Öffnen eines Projekts Sie nichts, während es die Tabs öffnet. Jeder Terminal-, SFTP-Manager- und Remote-Datei-Tab meldet sich wie **Verbinden** im Connection-Manager an (siehe [Anmelden](connections.md#anmelden)), aber nur mit dem, was gespeichert ist, und die [Serverzugriffsrichtlinie](../reference/enterprise-policy.md#server-zugriffskontrolle) Ihrer Organisation wird zuerst geprüft:

- **Öffnet sofort** — eine lokale Shell, SSH-Schlüssel-Authentifizierung, ein Passwort, das in der Verbindung oder in ihren hinterlegten Anmeldeinformationen gespeichert ist, und ein temporärer SSH-Schlüssel, der noch gültig ist. Eine lokale Shell öffnet sich unabhängig davon, was in ihrem Authentifizierungsfeld steht.
- **Wartet auf Sie** — ein Passwort, das nicht gespeichert ist, ein temporärer SSH-Schlüssel, der abgelaufen ist oder den der gesperrte Tresor noch verschlüsselt hält, und ein Passwort, das im gesperrten Tresor liegt. Ein temporärer Schlüssel wird nie ohne Nachfrage erneuert.
- **Nur aufgeführt** — ein Server oder Jump-Server, den die Serverzugriffsrichtlinie blockiert, eine Verbindung, die gelöscht oder nie gespeichert wurde (eine Schnellverbindungs-Sitzung), und eine lokale Datei, die nicht mehr existiert.

Die Tabs, die sich nicht öffnen, werden in einer Leiste über der Statuszeile ihres Fensters aufgeführt, zum Beispiel *Nicht wieder geöffnete Tabs: Anmeldung nötig (2) · Tresor gesperrt (1) · durch Richtlinie gesperrt (1)*, statt in einem Dialog für jeden Tab. Die Leiste blockiert das Fenster nie, sodass Sie in den geöffneten Tabs arbeiten und später zu ihr zurückkehren können.

| Aktion | Was sie bewirkt |
|--------|--------------|
| **Verbinden…** | Fragt nacheinander ab, was jeder wartende Tab braucht: sein Passwort, einen neuen temporären SSH-Schlüssel oder – bei einem Passwort im Tresor – **Tresor entsperren…**, wobei korTTY nach dem Passwort fragt, wenn Sie das ablehnen. Sie werden einmal pro Verbindung gefragt: Die anderen wartenden Tabs dieser Verbindung öffnen sich mit derselben Antwort. **Abbrechen** hält an; dieser Tab und die nachfolgenden bleiben in der Leiste. Die Serverzugriffsrichtlinie wird vor jeder Frage erneut geprüft, und ein Server, den sie inzwischen blockiert, oder eine zwischenzeitlich gelöschte Verbindung wandert ohne Dialog in den entsprechenden Teil der Liste. |
| **Tresor entsperren…** | Wird angezeigt, solange Tabs auf den gesperrten Tresor warten. Sobald Sie das Master-Passwort eingegeben haben, öffnen sich diese Tabs, in jedem Fenster. Wenn Sie den Tresor auf andere Weise entsperren, zum Beispiel mit *Konfiguration > Sicherheit > Tresor entsperren…*, öffnen sie sich ebenfalls; ein Tab, dessen Passwort sich dann doch nicht im Tresor befindet, wartet stattdessen auf die Anmeldung. |
| **Details** | Listet jeden Tab der Leiste mit seinem Namen und dem Grund auf, warum er sich nicht geöffnet hat, zum Beispiel *web-01: braucht ein Passwort* oder *prod: von den Richtlinien Ihrer Organisation nicht erlaubt (prod.example.com:22)*. Wenn Sie einen Tab wählen, der auf Sie wartet, wird nur dieser Tab verbunden; blockierte und fehlende Tabs sind ausgegraut. |
| **Verwerfen** | Blendet die Leiste aus und vergisst ihre Tabs. |

Ein Tab, den Sie aus der Leiste öffnen, kommt zurück wie die Tabs, die sich sofort geöffnet haben: mit seinem gespeicherten Bildschirm, seiner Tab-Gruppe, seinem Namen, seiner Schriftgröße und seinen [geteilten Bereichen](#geteilte-bereiche), einsortiert in seine Tab-Gruppe. War er der aktive Tab des Fensters, wählt das Fenster ihn aus. Wenn Sie ein anderes Projekt im Fenster öffnen, ersetzt dessen Leiste die bisherige, und das Schließen des Fensters verwirft sie.

### Geteilte Bereiche

Mit **Automatisches Wiederverbinden** bekommt ein Terminal-Tab, der geteilte Bereiche hatte, diese zurück, sobald seine eigene Sitzung verbunden ist: Die Bereiche öffnen sich nacheinander im Hintergrund, ohne einen *Verbinde*-Dialog für jeden, jeder Bereich an seinem bisherigen Platz, und die Trennlinien rücken dorthin, wo sie beim Speichern des Projekts waren. Das geschieht einmal bei jedem Öffnen des Projekts; wenn sich der Tab später erneut verbindet, werden seine Bereiche nicht noch einmal hinzugefügt.

- Ein Bereich, der auf der Verbindung des Tabs lief, meldet sich wie der Tab an, mit demselben Passwort oder SSH-Schlüssel. Der [Zugriffsgrund](terminal.md#vorgange-aufteilen), den Sie für den Tab angegeben haben, wird auch für diese Bereiche gesendet, sodass ein Server im CyberArk-Stil nur einmal fragt. Eine Hostschlüssel-Abfrage und die Eingabeaufforderungen einer Keyboard-Interactive-Anmeldung erscheinen weiterhin, wenn der Server fragt.
- Ein Bereich, den Sie mit **Rechts teilen (neue Verbindung)** oder **Unten teilen (neue Verbindung)** geöffnet hatten, und die Bereiche, die aus ihm auf demselben Server geteilt wurden, öffnen sich auf der gespeicherten Verbindung dieses Bereichs, nicht auf dem Server des Tabs. Sie melden sich nur mit dem an, was gespeichert ist: mit dem SSH-Schlüssel oder dem Passwort aus der Zugangsdatenverwaltung oder der Verbindung. Hier fragt korTTY weder nach einem Passwort noch nach einem neuen temporären SSH-Schlüssel und bietet nicht an, den Tresor zu entsperren.
- Jeder Bereich durchläuft die [Serverzugriffsrichtlinie](../reference/enterprise-policy.md#server-zugriffskontrolle) Ihrer Organisation, auch die Bereiche auf dem eigenen Server des Tabs, weil eine gespeicherte Verbindung seit dem Speichern des Projekts geändert worden sein kann.
- Ein Bereich, der sich nicht öffnen lässt, bleibt zusammen mit den aus ihm geteilten Bereichen geschlossen, und die anderen Bereiche behalten ihre Plätze. Das passiert, wenn der Bereich ein Passwort oder einen neuen temporären SSH-Schlüssel braucht, wenn der Tresor gesperrt ist, wenn die Richtlinie seinen Server nicht erlaubt, wenn seine Verbindung gelöscht oder nie gespeichert wurde (eine Schnellverbindungs-Sitzung) oder wenn die Verbindung fehlschlägt. Die Statusleiste meldet dann, wie viele Bereiche welches Tabs nicht wieder geöffnet wurden und warum, zum Beispiel *web-01: 2 von 4 geteilten Bereichen wurden nicht wieder geöffnet (Tresor gesperrt)*. Öffnen Sie sie mit **Rechts teilen** oder **Unten teilen** erneut.
- Höchstens 32 Bereiche pro Tab werden wieder geöffnet; eine Projektdatei mit mehr, die korTTY selbst nie schreibt, öffnet sich mit den ersten 32.

Projekte, die mit früheren Versionen gespeichert wurden, speichern keinen Server pro Bereich: Alle ihre Bereiche öffnen sich wie bisher auf dem Server des Tabs.

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
- Jedes Fenster mit seiner Position, Größe und Tabs sowie der Sitzungs-ID seines aktiven Tabs
- Verbindungsreferenzen (nach Verbindungs-ID, daher muss die Verbindung im Connection-Manager existieren)
- Sichtbarkeit des Dashboards in jedem Fenster

Der gespeicherte Bildschirminhalt ist nicht Teil der `.kortty`-Datei. Er wird separat als eine gzip-Datei pro Terminal-Tab, `~/.kortty/history/<session-id>.history.gz`, gespeichert, die das Projekt per Dateiname referenziert – ein Projektdokument, das Sie teilen, trägt daher das Layout, jedoch nicht den Bildschirminhalt. korTTY liest, schreibt oder löscht ausschließlich reine Dateinamen innerhalb von `~/.kortty/history/`; ein Projekt, dessen Referenz auf einen anderen Ort zeigt, öffnet sich ohne diesen Bildschirminhalt.

Eine Projektdatei enthält nie das Arbeitsverzeichnis eines Bereichs oder einen Verweis auf gespeicherten Scrollback: korTTY entfernt beides aus jedem Projekt, das es öffnet oder speichert, sodass ein Projekt, das Ihnen jemand schickt, weder den Ordner bestimmen kann, in dem eine lokale Shell startet, noch einen Bereich auf eine Datei verweisen lassen kann.

## Anwendungsfälle

Projekte sind nützlich für:

- **Kontextwechsel** – Speichern Sie ein „Produktionssystem“-Projekt, ein „Entwicklungs“-Projekt und ein „Test“-Projekt; Öffnen Sie das, das Sie benötigen
- **Teamübergaben** – Teilen Sie Projekte mit Kollegen, um identische Arbeitsbereichslayouts und -verbindungen einzurichten
- **Layouts mit mehreren Fenstern** – Speichern Sie ein komplexes Setup über mehrere Monitorfenster hinweg und stellen Sie es sofort wieder her
- **Sitzungswiederherstellung** – Behalten Sie einen Arbeitsbereich, zu dem Sie jederzeit zurückkehren können; nach einem Absturz oder Neustart bringen das [Angebot beim Start](#beim-start) oder [*Datei → Vorherige Sitzung wiederherstellen*](#vorherige-sitzung) zurück, was geöffnet war, und *Datei → Zuletzt geschlossen* die Tabs, die Sie geschlossen haben
