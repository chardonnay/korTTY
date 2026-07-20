# Übersicht über das Hauptfenster

![korTTY main window](../assets/screenshots/main/main-window.png)

![Main window layout](../assets/diagrams/mainwindow-layout.svg)

Ein neues korTTY-Fenster: die Menüleiste, der Terminalbereich (in dem Sitzungsregisterkarten und das optionale Dashboard angezeigt werden, sobald Sie eine Verbindung hergestellt haben) und die Statusleiste. Das folgende Diagramm bildet dieselben Regionen ab:

![korTTY architecture](../assets/diagrams/architecture.svg)

Das Hauptfenster von korTTY hat diese Bereiche:

- **Menüleiste** – Datei · Bearbeiten · Verbindungen · Sicherheit · Konfiguration · Tools · Plugins · Anzeigen · Teamwork · KI · Hilfe. Alle Funktionen sind hier und über [Tastaturkürzel](../reference/keyboard-shortcuts.md)] erreichbar. Ein Live-Menü **JobScheduler-Status** erscheint nach *Hilfe*, wenn ein geplanter Eintrag aktiv ist.
- **Tab-Leiste** – jede SSH/Mosh-Sitzung wird in einem eigenen Tab ausgeführt. ++Strg+T++ opens Quick Connect for a new tab; ++Strg+Tab++ / ++Strg+Umschalt+Tab++ Tabs wechseln.
- **Dashboard** (umschalten ++Strg+Umschalt+D++) – ein Seitenbereich, der jede offene Verbindung mit Statuspunkten, Protokoll-Badges und KI-Agent-Badges auflistet. Siehe [Dashboard](#dashboard) unten.
- **Terminalbereich** – das aktive Terminal, mit optionalem Splitscreen und Broadcast-Eingang.
- **Statusleiste** – Verbindungsstatus, Host/IP, aktives Protokoll, temporärer SSH-Schlüssel-Timer und Verbindungsdauer.

!!! tip "Nur korTTY Applikationsfenster"
    Drücken Sie zum Wiederherstellen erneut ++Strg+Umschalt+f++ (or **View → Terminal-only Fullscreen**) to show the whole korTTY window — menus, tabs and status bar included — kept at its previous window size and centered on an empty fullscreen background, so the desktop and other windows stop competing for attention. **View → Hide terminal scrollbars in fullscreen** removes the scrollbars too. A transparent terminal background becomes opaque while fullscreen is active and returns to its saved level when you leave. Press ++Strg+Umschalt+F++.

## Dashboard

Schalten Sie das Dashboard mit ++Strg+Umschalt+D++ oder **Ansicht → Dashboard anzeigen** um. Es wird auf der linken Seite eingeschoben, passt seine Breite an den längsten Eintrag an und folgt den Farben des aktiven App-Designs.

In der Kopfzeile wird der Titel des Bedienfelds mit zwei Schaltflächen angezeigt: einem Umschalter zum Reduzieren/Erweitern (reduziert alles, während ein Knoten geöffnet ist, erweitert alles andere) und einer Schaltfläche „Aktualisieren“. Darunter sind die Verbindungen als Baum organisiert:

- **Hauptfenster** – der Stammknoten mit einer aktiven/Gesamtsitzungsanzahl.
- **Umgebungen** – Verbindungen, deren gespeicherte Anmeldeinformationen eine Umgebung haben (z. B. *Produktion* oder *Test*), werden unter einem Umgebungsknoten geclustert; Anschlüsse ohne einen befinden sich direkt unter dem Hauptfenster.
- **Gruppen** – Registerkarten, die einer Verbindungsgruppe zugewiesen sind, werden unter ihrem Gruppenknoten angezeigt. Durch das Speichern einer geänderten Gruppe im Verbindungsmanager werden geöffnete Registerkarten sofort aktualisiert.

Jede Verbindungszeile zeigt ein Typsymbol, einen Statuspunkt, den Servernamen und ein Protokoll-Badge (`ssh`, `mosh` oder `local`). Der Statuspunkt unterscheidet drei Zustände: grün gefüllt für eine gesunde Verbindung, rot gefüllt für eine Verbindung, die unerwartet unterbrochen wurde (einschließlich einer Mosh-Netzwerkunterbrechung) und ein leerer Umriss für eine Sitzung, die normal beendet wurde. Terminals mit KI-Agentenläufen tragen das gleiche ✋/⚡/⏸/ ✓-Abzeichen wie anderswo. Wenn Sie den Mauszeiger über eine Zeile bewegen, werden `user@host` und der Verbindungsstatus angezeigt. Ein Doppelklick (oder ++enter++) fokussiert die Registerkarte der Sitzung.

Klicken Sie mit der rechten Maustaste auf eine Verbindung für **Fokus**, **Duplizieren**, **Erneut verbinden**, **SFTP-Client...** (nur verbundene Sitzungen) und **Schließen**. In einer Fußzeile wird die Anzahl der „verbunden von insgesamt“ fortlaufend angezeigt, und in einem leeren Bereich wird ein Platzhalter angezeigt, bis die erste Sitzung geöffnet wird.

## macOS Dock-Menü

Klicken Sie unter macOS mit der rechten Maustaste (oder bei gedrückter Strg-Taste) auf das korTTY-Symbol im Dock, um schnelle Aktionen durchzuführen, ohne zur App wechseln zu müssen. Sie gelten für das aktuelle (fokussierte) Fenster und öffnen zuerst eines, wenn keines geöffnet ist:

- **Neues Fenster**
- **Neuer Tab im aktuellen Fenster**
- **Verbindungen verwalten…** (Verbindungsmanager)
- **Projekt öffnen…**
- **Anleitung** (die In-App-Anleitung)
- **Über korTTY**

Sehen Sie sich die [Menüreferenz ](../reference/menu.md) für jeden Menüpunkt und die [Tastaturkürzel-Referenz ](../reference/keyboard-shortcuts.md) für die vollständige Liste der Zugriffstasten an.
