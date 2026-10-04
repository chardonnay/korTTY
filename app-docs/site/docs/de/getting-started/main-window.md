# Übersicht des Hauptfensters

![korTTY main window](../assets/screenshots/main/main-window.png)

![Main window layout](../assets/diagrams/mainwindow-layout.svg)

Ein neues korTTY-Fenster: die Menüleiste, der Terminalbereich (in dem Sitzungsregisterkarten und das optionale Dashboard angezeigt werden, sobald Sie eine Verbindung hergestellt haben) und die Statusleiste. Das folgende Diagramm bildet dieselben Regionen ab:

![korTTY architecture](../assets/diagrams/architecture.svg)

Das Hauptfenster von korTTY hat diese Bereiche:

- **Menüleiste** – Datei · Bearbeiten · Verbindungen · Konfiguration · Tools · KI · Teamwork · Plugins · Anzeigen · Hilfe. Alle Funktionen sind hier und über erreichbar [Tastaturkürzel](../reference/keyboard-shortcuts.md). Ein Live-Menü **JobScheduler-Status** erscheint nach *Hilfe*, wenn ein geplanter Eintrag aktiv ist. Während a [Übersetzung des Reiseführers](../reference/settings/translation.md) Wenn ausgeführt wird, befindet sich am rechten Ende der Menüleistenzeile eine Fortschrittsanzeige (Balken, Prozentsatz und geschätzte verbleibende Zeit) und bleibt auch dann sichtbar, wenn die Menüleiste selbst ausgeblendet ist.
- **Tab-Leiste** — jede SSH/Mosh-Sitzung läuft in ihrem eigenen Tab, benannt nach ihrer Verbindung, nach dem [Titel, den ihre Shell setzt](../features/terminal.md#titel-aus-der-shell), oder nach einem Namen, den Sie dem Tab mit **Tab umbenennen** geben. ++ctrl+t++ öffnet die Schnellverbindung für einen neuen Tab; ++ctrl+tab++ / ++ctrl+shift+tab++ wechseln zwischen Tabs, und ++ctrl+1++ bis ++ctrl+8++ springen zum ersten bis achten Tab, ++ctrl+9++ zum letzten (++cmd++ auf macOS). Ein farbiger Punkt vor dem Titel eines Tabs und ein Rahmen in derselben Farbe um sein Terminal zeigen die [Tab-Farbe](../features/connections.md#tab-farbe) seiner Verbindung oder der [Umgebung](../features/security.md#umgebungen-und-tab-farben) seiner Anmeldeinformationen; der Rahmen lässt sich in den [Fenster-Einstellungen](../reference/settings/window.md#tabs) ausschalten. Ein bernsteinfarbenes Fork-Symbol hinter dem Titel markiert einen Tab, dessen Eingabe an andere Bereiche geht, über [Multi-Exec](../features/terminal.md#multi-exec) oder den [Broadcast-Modus](../features/terminal.md#broadcast-modus); sein Tooltip sagt, über welches von beiden. Mit aktivierter Option **Tool-Fenster als Tabs öffnen** ([Fenster-Einstellungen](../reference/settings/window.md)) werden Verwaltungstools wie Snippets, der JobScheduler oder der KI-Manager ebenfalls als Tabs in dem Fenster geöffnet, dessen Menü Sie benutzt haben — anstatt separate Fenster zu öffnen. Der [Snippet-Manager](../features/snippets.md) zeigt die von Ihnen bearbeiteten Snippets als eigene Tabs innerhalb seines eigenen Tabs.
- **Dashboard** (umschalten ++ctrl+shift+d++) – ein Seitenbereich, der jede offene Verbindung mit Statuspunkten, Protokollabzeichen und KI-Agent-Abzeichen auflistet. Siehe [Armaturenbrett](#dashboard) unten.
- **Dateibrowser** (**Ansicht → Dateibrowser ▸ Links anzeigen / Rechts anzeigen**) – ein andockbarer lokaler Dateimanager mit Navigationssymbolleiste, Pfadleiste, Filter, Typsymbolen und einem Ordner-/Datei-/Auswahlzähler. Beim nächsten Start werden Seite, Breite, Status der versteckten Datei und letztes Verzeichnis wiederhergestellt. Siehe [Dateibrowser](../features/file-browser.md).
- **Live-Journal-Panel** (**Ansehen → Live-Journal**, ++ctrl+alt+l++) — das aktive Tab ist ausgeführt [Sitzungsprotokoll](../features/session-journal.md#das-live-journal-panel) wie seine volle Protokoll-Seite, angepflanzt und in Echtzeit aktualisiert; Seiten- und Breite werden beim nächsten Start wiederhergestellt.
- **Terminalbereich** — das aktive Terminal, mit optionaler geteilten Ansicht und Broadcast-Eingabe.
- **Statusleiste** — Verbindungsstatus, Host/IP, aktives Protokoll, temporärer SSH-Schlüssel-Timer und Verbindungsdauer. Solange Bereiche an [Multi-Exec](../features/terminal.md#multi-exec) teilnehmen, zählt ein bernsteinfarbener Chip in jedem Fenster sie samt den Tabs und Fenstern, in denen sie sich befinden, und sein Link **Beenden** beendet Multi-Exec überall.

!!! tip "Nur Terminal-Vollbild"
    Drücken Sie ++ctrl+shift+f++ (oder **Ansicht → Nur Terminal-Vollbild**), um das gesamte korTTY-Fenster – Menüs, Registerkarten und Statusleiste inklusive – in seiner vorherigen Fenstergröße und zentriert auf einem leeren Vollbildhintergrund anzuzeigen, sodass der Desktop und andere Fenster nicht mehr um Aufmerksamkeit konkurrieren. **Ansicht → Terminal-Bildlaufleisten im Vollbildmodus ausblenden** entfernt auch die Bildlaufleisten. Ein transparenter Terminalhintergrund wird undurchsichtig, während der Vollbildmodus aktiv ist, und kehrt beim Verlassen auf die gespeicherte Ebene zurück. Drücken Sie erneut ++ctrl+shift+f++, um die Wiederherstellung durchzuführen.

## Dashboard

Schalten Sie das Dashboard mit ++ctrl+shift+d++ oder **Ansicht → Dashboard anzeigen** um. Es wird auf der linken Seite eingeschoben, passt seine Breite an den längsten Eintrag an und folgt den Farben des aktiven App-Designs.

Die Kopfzeile zeigt das Paneltitel mit zwei Schaltflächen: ein Auf-/Zuklapp-Schalter (klappt alles zu, solange ein Knoten geöffnet ist, andernfalls klappt alles auf) und eine Aktualisierungs-Schaltfläche. Darunter werden die Verbindungen als Baum organisiert:

- **Hauptfenster** – der Stammknoten mit einer aktiven/Gesamtsitzungsanzahl.
- **Umgebungen** – Verbindungen, deren gespeicherte Anmeldeinformationen eine Umgebung haben (z. B. *Produktion* oder *Test*), werden unter einem Umgebungsknoten geclustert; Anschlüsse ohne einen befinden sich direkt unter dem Hauptfenster.
- **Gruppen** – Registerkarten, die einer Verbindungsgruppe zugewiesen sind, werden unter ihrem Gruppenknoten angezeigt. Durch das Speichern einer geänderten Gruppe im Connection-Manager werden geöffnete Registerkarten sofort aktualisiert.

Jede Verbindungszeile zeigt ein Typsymbol, einen Statuspunkt, den Servernamen und ein Protokoll-Badge (`ssh`, `mosh` oder `local`). Der Statuspunkt unterscheidet drei Zustände: grün gefüllt für eine fehlerfreie Verbindung, rot gefüllt für eine Verbindung, die unerwartet unterbrochen wurde (einschließlich einer Unterbrechung des Mosh-Netzwerks) und ein leerer Umriss für eine Sitzung, die normal beendet wurde. Terminals mit KI-Agentenläufen tragen das gleiche ✋/⚡/⏸/ ✓-Abzeichen wie anderswo. Wenn Sie den Mauszeiger über eine Zeile bewegen, werden `user@host` und der Verbindungsstatus angezeigt. Ein Doppelklick (oder ++enter++) fokussiert die Registerkarte der Sitzung.

Rechtsklick auf eine Verbindung für **Fokussieren**, **Duplizieren**, **Neu verbinden**, **SFTP-Client...** (nur verbundene Sitzungen) und **Schließen**. **Schließen** fragt zuerst, wie der Schließen-Button des Tabs, wenn die Sitzung geteilte Bereiche hat oder ein Befehl noch läuft. **Multi-Exec: Alle Bereiche dieses Tabs einbeziehen** in einer Verbindungszeile und **Multi-Exec: Diesen Bereich einbeziehen** in der Zeile eines einzelnen Bereichs eines geteilten Tabs lassen diese Bereiche von jedem Fenster aus an [Multi-Exec](../features/terminal.md#multi-exec) teilnehmen, ohne zu ihnen zu wechseln; das Häkchen zeigt, ob sie teilnehmen, und ein bernsteinfarbenes Fork-Symbol markiert ihre Zeilen. Ein Fußzeilenbereich zeigt einen laufenden „verbunden von insgesamt“-Zähler, und ein leeres Panel zeigt einen Platzhalter, bis die erste Sitzung geöffnet wird.

## macOS Dock-Menü

Klicken Sie unter macOS mit der rechten Maustaste (oder bei gedrückter Ctrl-Taste) auf das korTTY-Symbol im Dock, um schnelle Aktionen auszuführen, ohne zur App wechseln zu müssen. Sie gelten für das aktuelle (fokussierte) Fenster und öffnen zuerst eines, wenn keines geöffnet ist:

- **Neues Fenster**
- **Neuer Tab im aktuellen Fenster**
- **Verbindungen verwalten…** (Connection-Manager)
- **Projekt öffnen…**
- **Anleitung** (die In-App-Anleitung)
- **Über korTTY**

Siehe die [Menüreferenz](../reference/menu.md) für jeden Menüpunkt und die [Referenz zu Tastaturkürzeln](../reference/keyboard-shortcuts.md) für die vollständige Liste der Beschleuniger.
