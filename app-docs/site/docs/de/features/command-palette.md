---
title: Befehlspalette
---

# Befehlspalette

Die Befehlspalette findet jeden Befehl der Menüs des Hauptfensters und führt ihn aus, wenn Sie ein paar Buchstaben seines Namens eingeben, sodass Sie weder die Maus brauchen noch wissen müssen, wo der Befehl in den Menüs steht. Sie führt außerdem die Rechtsklick-Befehle des Terminals aus, in dem Sie sich befinden, etwa **Puffer löschen** oder **Neu verbinden**, wechselt zu jedem offenen Tab, die zuletzt benutzten zuerst, einschließlich der Terminal-Tabs Ihrer anderen korTTY-Fenster, öffnet einen Tab für jede gespeicherte oder per Teamwork geteilte Verbindung und führt jedes Snippet des [Snippet-Managers](snippets.md) im Terminal aus. Sie erreicht jeden Menübefehl auch bei ausgeblendeter Menüleiste und im Nur-Terminal-Vollbildmodus.

## Palette öffnen

- Drücken Sie ++ctrl+shift+p++ (++cmd+shift+p++ unter macOS) oder wählen Sie **Ansicht → Befehlspalette…**. Dieselben Tasten schließen sie wieder.
- Die Tasten funktionieren in jedem Tab eines Hauptfensters, auch wenn ein Terminal, ein Snippet-Editor oder ein anderer Tool-Tab den Tastaturfokus hat, bei ausgeblendeter Menüleiste und im Nur-Terminal-Vollbildmodus. Ein Tool, das in einem eigenen Fenster statt als Tab geöffnet ist, hat keine Palette.
- Unter Windows und Linux behält korTTY ++ctrl+shift+p++ für sich, sodass die Tastenkombination das Programm im Terminal nicht erreicht, während ein einfaches ++ctrl+p++ (der vorherige Befehl in bash) weiterhin die Shell erreicht. Kombinationen mit ++alt-graph++, die als ++ctrl+alt++ ankommen, bleiben ebenfalls unberührt.

Die Palette öffnet sich oben im Fenster mit einem leeren Suchfeld. Wenn unter macOS noch die Menüleiste eines Fensters angezeigt wird, das Sie geschlossen haben, öffnet **Ansicht → Befehlspalette…** die Palette im vordersten offenen Fenster.

## Befehl finden

Geben Sie einen Teil des Befehlsnamens ein. Die Buchstaben müssen in dieser Reihenfolge vorkommen, aber nicht direkt nebeneinander, und die Groß- und Kleinschreibung spielt keine Rolle, sodass `nfen` **Neues Fenster** findet. Jede Zeile zeigt:

- ein **Befehl**-Badge;
- den Namen des Befehls, wie ihn sein Menü anzeigt, gefolgt von seinem Menüpfad in Grau, zum Beispiel **Links andocken** Ansicht › Live-Journal;
- ein Häkchen, wenn der Befehl eine aktivierte Einstellung ist, etwa **Dashboard anzeigen**;
- sein Tastaturkürzel, falls er eines hat, auch eines, das Sie selbst unter [Einstellungen → Tastatur](../reference/settings/keyboard.md) gewählt haben.

Der Menüpfad wird ebenfalls durchsucht, wodurch gleichnamige Befehle unterscheidbar bleiben: `journal links` findet **Ansicht › Live-Journal › Links andocken**, und `datei links` findet **Ansicht › Dateibrowser › Links anzeigen**. Ein Treffer im Namen rangiert vor einem Treffer im Pfad, und unter gleich guten Treffern kommen die zuletzt gewählten Befehle zuerst.

Ohne Eingabe listet die Palette die zuletzt gewählten Befehle und Verbindungen auf, bis zu acht und die neuesten zuerst, dann die [offenen Tabs](#tabs-wechseln) und dann alle Befehle, Menü für Menü. Andere Verbindungen erscheinen erst, wenn Sie tippen. [Snippets](#snippets-ausfuhren) erscheinen nie, bevor Sie tippen, auch nicht die zuletzt ausgeführten, denn ein Snippet führt einen Befehl im Terminal aus. Neben den Menübefehlen bietet sie **Nächster Tab** und **Vorheriger Tab**, die wie ++ctrl+tab++ und ++ctrl+shift+tab++ die Tabs wechseln, sowie die [Terminal- und Tab-Befehle](#terminal-und-tab-befehle).

Als erstes Zeichen eingegeben, beschränkt ein Filterzeichen die Liste auf eine Art von Zeilen: `>` listet nur Befehle, `#` nur Tabs, `@` nur Verbindungen und `$` nur Snippets, sodass `>schließen` die Befehle zum Schließen findet, aber keinen Tab, dessen Name „schließen“ enthält. Die Zeile unter der Liste nennt diese Zeichen.

Die Liste der zuletzt gewählten Einträge besteht, bis korTTY beendet wird; alle Fenster teilen sie, und sie wird nie gespeichert. Was Sie in die Palette eingeben, wird weder protokolliert noch irgendwohin gesendet.

## Terminal- und Tab-Befehle

Die Palette bietet außerdem die Befehle der Rechtsklickmenüs des Terminals und des Tabs an, die es in der Menüleiste nicht gibt. Sie wirken auf den Terminal-Tab, in dem Sie sich befinden, die Terminalbefehle auf dessen fokussierten Bereich, also den, in den Ihre Eingabe geht und den **Bearbeiten → Suchen…** durchsucht:

| Befehl | Graues Detail | Wirkung |
| --- | --- | --- |
| **Puffer löschen** | Terminal | Löscht den Scrollback und den Bildschirm des fokussierten Bereichs, behält aber die Prompt-Zeile bei, wie **Puffer löschen** im Rechtsklickmenü des Terminals. Während ein Vollbildprogramm wie `vim` oder `less` läuft, tut es nichts. Unter macOS zeigt die Zeile die eigene Taste des Terminals, ++cmd+k++. |
| **Duplizieren** | Tabs | Öffnet eine Kopie des Tabs daneben und meldet sich an wie **Duplizieren** im Rechtsklickmenü des Tabs. |
| **Neu verbinden** | Tabs | Verbindet den Tab erneut, wie **Neu verbinden** in seinem Rechtsklickmenü. |

In allen anderen Arten von Tabs, etwa einem Snippet-Editor, sind diese Zeilen ausgegraut. **Suchen** wird nicht ein zweites Mal aufgeführt: Geben Sie `suchen` für **Bearbeiten → Suchen…** ein, das die Suche des fokussierten Bereichs öffnet.

Die Befehle für Bereiche stehen nicht in dieser Tabelle, weil die Menüleiste sie hat: Die Befehle von *Ansicht → Bereiche* und *Ansicht → Multi-Exec* sind Zeilen wie jeder andere Menübefehl, mit ihrem Menüpfad, ihrem Tastaturkürzel und ihrem Häkchen. Geben Sie `rechts teilen` für **Rechts teilen** Ansicht › Bereiche ein, das den fokussierten Bereich auf dessen eigenem Server teilt wie **Rechts teilen (gleicher Server)** im Rechtsklickmenü, mit demselben Fortschrittsdialog und derselben Prüfung der [Serverrichtlinie](terminal.md#sicher-verbinden), und die Tastatur in den neuen Bereich setzt. **Unten teilen**, **Bereich teilen**, **Bereich schließen**, die Fokusbefehle, **Nächster Bereich**, **Vorheriger Bereich**, **Bereich maximieren**, **Broadcast an alle Bereiche dieses Tabs** und die Befehle von *Multi-Exec* finden Sie auf dieselbe Weise, und sie tun genau das, was ihre Menüeinträge tun; eine Zeile ist ausgegraut, solange ihr Menüeintrag es ist, sodass die Befehle, die einen Bereich schließen, fokussieren oder maximieren, zwei oder mehr Bereiche brauchen. **Bereich maximieren**, der Broadcast-Befehl, **Diesen Bereich einbeziehen** und **Alle Bereiche dieses Tabs einbeziehen** zeigen ein Häkchen, solange sie eingeschaltet sind, und der [Broadcast-Modus](terminal.md#broadcast-modus) lässt sich immer ausschalten, auch wenn nur noch ein Bereich übrig ist.

## Tabs wechseln

Jeder Tab des Fensters ist eine Zeile mit einem **Tab**-Badge und heißt so, wie die Tab-Leiste ihn nennt: mit dem Namen, den Sie ihm mit **Tab umbenennen** gegeben haben, dem Titel, den seine Shell gesetzt hat, oder dem Namen seiner Verbindung, ohne das Gruppenpräfix und den Verbindungsstatus. Das graue Detail eines Terminal-Tabs nennt, wohin er verbunden ist, als `user@host` aus der gespeicherten Verbindung (oder den Namen der Verbindung bei einer lokalen Shell), sowie seine Tab-Gruppe, zum Beispiel **root@db-01 · Produktion**. Ein Programm im Terminal kann den Titel des Tabs setzen, aber nicht dieses Detail, sodass ein Titel, der einen anderen Server vortäuscht, nicht verbirgt, wo der Tab wirklich verbunden ist; ein langer Titel wird nach der halben Zeilenbreite abgeschnitten und kann das Detail daher auch nicht aus dem Blick schieben.

Die Tabs werden in der Reihenfolge aufgelistet, in der Sie sie zuletzt benutzt haben, der zuletzt benutzte zuerst, und wenn Sie tippen, behalten gleich gute Treffer diese Reihenfolge. Der Tab, in dem Sie sich befinden, steht am Ende und ist mit **Aktueller Tab** gekennzeichnet, sodass `#` und ++enter++ zu dem Tab zurückführen, den Sie davor benutzt haben. Ein Tab zählt als benutzt, wenn Sie ihn auswählen, mit der Maus, mit Tasten wie ++ctrl+tab++ oder ++ctrl+1++ oder aus der Palette. Ist [++ctrl+tab++ in dieser Reihenfolge](../reference/settings/window.md#tabs) eingeschaltet, zählt beim Durchblättern der Tabs, während Sie ++ctrl++ gedrückt halten, nur der Tab, bei dem Sie anhalten. Wenn Sie mehrere Tabs auf einmal schließen, sie neu gruppieren oder einen Tab an eine andere Stelle ziehen, zählt nur der danach angezeigte Tab, nicht die Tabs, über die die Auswahl dabei hinweggeht. Die Reihenfolge gilt, solange das Fenster besteht, und wird nie gespeichert.

Danach folgen die Terminal-Tabs Ihrer anderen korTTY-Fenster, die Tabs jedes Fensters in der Reihenfolge, in der sie dort benutzt wurden, mit der Nummer des Fensters vor dem Detail, etwa **Fenster 2 · admin@web-01**; die Nummer eines Fensters ist seine Position unter den offenen Fenstern, in der Reihenfolge, in der sie geöffnet wurden. Wenn Sie einen solchen Tab wählen, kommt sein Fenster nach vorne, wird wiederhergestellt, falls es minimiert ist, und der Tab wird dort ausgewählt. Andere Arten von Tabs der anderen Fenster, etwa Editoren oder KI-Ergebnisse, werden nicht aufgeführt.

## Verbinden

Jede im [Connection-Manager](connections.md#connection-manager) gespeicherte Verbindung ist eine Zeile mit einem **Verbindung**-Badge und heißt so, wie der Connection-Manager sie nennt. Das graue Detail nennt, wohin sie verbindet, als `user@host` (**Lokale Shell** bei einer lokalen Shell), sowie ihre Gruppe, zum Beispiel **postgres@db-01.example.org · Produktion**; eine Verbindung ohne eigenen Namen zeigt dort nur ihre Gruppe, weil ihr Name bereits `user@host` ist. Das Detail zeigt nie ein Passwort, gespeicherte Anmeldeinformationen oder einen SSH-Schlüssel.

Wenn die Richtlinie Ihrer Organisation [Teamwork](teamwork.md) erlaubt, folgen die mit Ihnen geteilten Verbindungen, mit **Geteilt (Teamwork)** vor dem Detail. Ihre Namen stammen aus einer Datei, die jemand anderes schreibt, deshalb nennen ihre Zeilen immer auch `user@host`, selbst wenn der Name wie eine Adresse aussieht, und ein langer Name wird nach der halben Zeilenbreite abgeschnitten, damit das Detail sichtbar bleibt. Teamwork-Verbindungen, die Sie auf diesem Computer gelöscht haben, werden nicht aufgeführt.

Gespeicherte Verbindungen werden mit der zuletzt benutzten zuerst aufgelistet, die nie benutzten nach Namen; die Teamwork-Verbindungen folgen nach Namen. Wenn Sie tippen, kommen unter gleich guten Treffern die Verbindungen zuerst, die Sie kürzlich in der Palette gewählt haben, und die übrigen behalten diese Reihenfolge. Geben Sie `@` ein, um nur Verbindungen aufzulisten, die kürzlich in der Palette gewählten zuerst.

Wenn Sie eine Verbindung wählen, öffnet sich in diesem Fenster ein Tab dafür, und die Anmeldung läuft genau wie bei **Verbinden** im Connection-Manager (siehe [Anmelden](connections.md#anmelden)): korTTY prüft zuerst die Serverrichtlinie, verwendet dann das gespeicherte Passwort oder den gespeicherten Schlüssel und fragt nur nach dem, was fehlt. Wenn Sie eine Frage abbrechen, wird kein Tab geöffnet. Anders als im Connection-Manager zählt eine aus der Palette geöffnete Verbindung als benutzt, sodass sie in der Palette und unter den häufig verwendeten Verbindungen der Schnellverbindung nach oben rückt.

Eine Verbindung, deren Server oder Jump-Server die [Server-Zugriffsrichtlinie](../reference/enterprise-policy.md#server-zugriffskontrolle) Ihrer Organisation sperrt, ist ausgegraut. Wenn Sie sie wählen, bleibt die Palette offen, und die Zeile unter der Liste nennt den gesperrten Server und weist darauf hin, dass Ihre Organisation ihn verwaltet.

## Snippets ausführen

Jedes Snippet des [Snippet-Managers](snippets.md) ist eine Zeile mit einem **Snippet**-Badge und heißt so, wie die Bibliothek es nennt. Geben Sie einen Teil seines Namens oder seines Ordners, seiner Kategorie oder seiner Tags ein. Geben Sie zuerst `$` ein, um nur Snippets aufzulisten: Die zuletzt aus der Palette ausgeführten kommen zuerst, dann die übrigen, die zuletzt benutzten zuerst und die nie benutzten nach Namen; unter gleich guten Treffern gilt dieselbe Reihenfolge. Das graue Detail nennt das Terminal, in dem das Snippet läuft, zum Beispiel **Ausführen in root@db-01.example.org (prod-db)**: den `user@host` der Verbindung des Tabs, dann den Namen des Tabs. Ein Programm im Terminal kann den Titel des Tabs ändern, aber nicht seine Verbindung, und die Verbindung steht vorn, sodass bei zu schmaler Zeile ein langer Titel abgeschnitten wird, nie der Server. Wenn Sie den Namen eines Tabs oder Servers eingeben, werden nicht alle Snippets aufgelistet, denn ein Snippet wird nicht über das Terminal gefunden, das seine Zeile nennt.

Wenn Sie ein Snippet wählen, wird es genau wie mit [An Terminal senden](snippets.md#an-terminal-senden) im Snippet-Manager ausgeführt: korTTY ersetzt seine [Platzhalter](snippets.md#platzhaltervariablen), fragt nach einer deklarierten Variablen ohne gespeicherten Wert (Abbrechen sendet nichts), sendet es als Einzeiler, wo die Sprache das zulässt, führt es mit ++enter++ aus, wechselt zum Tab und zeigt **An … gesendet** in der Statusleiste. Es läuft in dem Terminal-Tab, in dem Sie sich befinden, oder, wenn eine andere Art von Tab wie der Snippet-Manager ausgewählt ist, in dem zuletzt benutzten Terminal-Tab (oder dem einzigen offenen), und immer im ersten Bereich dieses Tabs, also dem, mit dem der Tab geöffnet wurde, nicht in dem Bereich, der den Fokus hat. Wenn der Tab geteilt ist, sagt das Detail das: **Ausführen im ersten Bereich von root@db-01.example.org (prod-db)**. Das Snippet läuft genau in dem Tab, den seine Zeile genannt hat; wurde dieser Tab inzwischen geschlossen, meldet korTTY, dass kein Terminal geöffnet ist, statt einen anderen zu wählen.

Drücken Sie stattdessen ++alt+enter++ (++option+enter++ unter macOS), um das Snippet im Snippet-Manager zu öffnen, ohne es auszuführen; die Zeile unter der Liste nennt diese Taste, solange eine Snippet-Zeile gewählt ist. Ohne Terminal-Tab im Fenster sind die Snippet-Zeilen ausgegraut und weisen darauf hin, und ++alt+enter++ öffnet sie trotzdem.

!!! warning "Ein Snippet läuft sofort auf dem Server"
    Wenn Sie eine Snippet-Zeile wählen, wird das Snippet sofort mit ++enter++ im genannten Terminal ausgeführt, auf dem Server, mit dem dieser Bereich verbunden ist. Lesen Sie das graue Detail, bevor Sie ++enter++ drücken, und sehen Sie sich ein Snippet mit ++alt+enter++ zuerst an. Die Palette listet nie Snippets auf, bevor Sie tippen, daher kann ++enter++ in einer leeren Palette keines ausführen.

## Befehl ausführen

| Taste | Aktion |
| --- | --- |
| ++up++ / ++down++ | Zeile wählen |
| ++enter++ oder Doppelklick | Die gewählte Zeile ausführen, oder die erste Zeile, wenn keine gewählt ist |
| ++alt+enter++ (++option+enter++ unter macOS) | Das gewählte Snippet im Snippet-Manager öffnen, statt es auszuführen; in jeder anderen Zeile wie ++enter++ |
| ++tab++ / ++shift+tab++ | Zwischen Suchfeld und Liste wechseln |
| ++esc++ | Palette schließen |
| ++ctrl+shift+p++ (++cmd+shift+p++ unter macOS) | Palette schließen |

Die Palette schließt sich zuerst und führt dann den Befehl genau so aus wie sein Menüeintrag: Ein Dialog öffnet sich, eine Einstellung wie **Dashboard anzeigen** wird umgeschaltet, und **Bearbeiten → Suchen…** öffnet die Suche des aktiven Terminals. Eine Tab-Zeile wählt ihren Tab aus, eine Verbindungszeile öffnet einen Tab für ihre Verbindung, und eine Snippet-Zeile führt ihr Snippet in dem Terminal aus, das sie nennt. Ein Klick außerhalb der Palette schließt sie ebenfalls.

Befehle, die gerade nicht ausgeführt werden können, sind ausgegraut, etwa **Tresor entsperren…**, während der Tresor bereits geöffnet ist, oder **Tab umbenennen…** außerhalb eines Terminal-Tabs. Wenn Sie einen davon wählen, bleibt die Palette offen, und die Zeile unter der Liste nennt den Grund: **Gerade nicht verfügbar** oder dass Ihre Organisation die Funktion verwaltet, wenn ihre [Richtlinie](../reference/enterprise-policy.md) die Funktion abgeschaltet hat.

!!! note "Tasten bleiben in der Palette"
    Solange die Palette geöffnet ist, geht jede gedrückte Taste an die Palette. ++ctrl+d++, ++ctrl+l++, ++page-up++ oder eine Funktionstaste erreichen weder das Terminal dahinter noch im [Broadcast-Modus](terminal.md#broadcast-modus) die anderen Bereiche, und auch das Schließen der Palette mit ihrem Tastaturkürzel hinterlässt kein Zeichen im Terminal.

## Puffer löschen und Suchen unter Windows und Linux

Unter Windows und Linux gehen ++ctrl+l++ und ++ctrl+f++ an das Programm im Terminal, deshalb haben **Puffer löschen** und die Suche des Terminals dort keine eigene Taste. Öffnen Sie die Palette und geben Sie `puffer` für **Puffer löschen** oder `suchen` für **Bearbeiten → Suchen…** ein, um sie per Tastatur zu erreichen.
