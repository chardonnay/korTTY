# Übersicht über das Hauptfenster

![korTTY main window](../assets/screenshots/main/main-window.png)

![Main window layout](../assets/diagrams/mainwindow-layout.svg)

Ein neues korTTY-Fenster: die Menüleiste, der Terminalbereich (in dem Sitzungsregisterkarten und das optionale Dashboard angezeigt werden, sobald Sie eine Verbindung hergestellt haben) und die Statusleiste. Das folgende Diagramm bildet dieselben Regionen ab:

![korTTY architecture](../assets/diagrams/architecture.svg)

Das Hauptfenster von korTTY hat diese Bereiche:

- **Menüleiste** – Datei · Bearbeiten · Verbindungen · Sicherheit · Konfiguration · Tools · Plugins · Anzeigen · Teamwork · KI · Hilfe. Alle Funktionen sind hier und über [Tastaturkürzel](../reference/keyboard-shortcuts.md)] erreichbar. Ein Live-Menü **JobScheduler-Status** erscheint nach *Hilfe*, wenn ein geplanter Eintrag aktiv ist.
- **Tab-Leiste** – jede SSH/Mosh-Sitzung wird in einem eigenen Tab ausgeführt. ++Strg+T++ opens Quick Connect for a new tab; ++Strg+Tab++ / ++Strg+Umschalt+Tab++ Tabs wechseln.
- **Dashboard** (umschalten ++Strg+Umschalt+D++) – listet alle offenen Verbindungen mit Statusanzeigen und KI-Agent-Abzeichen auf. Klicken Sie mit der rechten Maustaste auf eine Verbindung, um sie erneut zu verbinden, zu duplizieren, SFTP zu öffnen oder zu schließen.
- **Terminalbereich** – das aktive Terminal, mit optionalem Splitscreen und Broadcast-Eingang.
- **Statusleiste** – Verbindungsstatus, Host/IP, aktives Protokoll, temporärer SSH-Schlüssel-Timer und Verbindungsdauer.

!!! tip "Nur Terminal-Vollbild"
    Drücken Sie ++f12++ (oder **Ansicht → Nur Terminal-Vollbild**), um das gesamte Fensterchrom auszublenden und nur das Terminal anzuzeigen. **Ansicht → Terminal-Bildlaufleisten im Vollbildmodus ausblenden** entfernt auch die Bildlaufleisten. Drücken Sie erneut ++f12++, um die Wiederherstellung durchzuführen.

## macOS Dock-Menü

Klicken Sie unter macOS mit der rechten Maustaste (oder bei gedrückter Strg-Taste) auf das korTTY-Symbol im Dock, um schnelle Aktionen durchzuführen, ohne zur App wechseln zu müssen. Sie gelten für das aktuelle (fokussierte) Fenster und öffnen zuerst eines, wenn keines geöffnet ist:

- **Neues Fenster**
- **Neuer Tab im aktuellen Fenster**
- **Verbindungen verwalten…** (Verbindungsmanager)
- **Projekt öffnen…**
- **Anleitung** (die In-App-Anleitung)
- **Über korTTY**

Sehen Sie sich die [Menüreferenz ](../reference/menu.md) für jeden Menüpunkt und die [Tastaturkürzel-Referenz ](../reference/keyboard-shortcuts.md) für die vollständige Liste der Zugriffstasten an.
