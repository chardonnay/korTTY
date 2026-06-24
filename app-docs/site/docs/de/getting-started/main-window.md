# Übersicht über das Hauptfenster

![korTTY main window](../assets/screenshots/main/main-window.png)

![Main window layout](../assets/diagrams/mainwindow-layout.svg)

Ein neues korTTY-Fenster: die Menüleiste, der Terminalbereich (wo Sitzungsregisterkarten und
(das optionale Dashboard wird angezeigt, sobald Sie eine Verbindung hergestellt haben) und die Statusleiste. Der
Das folgende Diagramm bildet dieselben Regionen ab:

![korTTY architecture](../assets/diagrams/architecture.svg)

Das Hauptfenster von korTTY hat diese Bereiche:

- **Menüleiste** – Datei · Bearbeiten · Verbindungen · Sicherheit · Konfiguration · Tools ·
Plugins · Anzeigen · Teamwork · KI · Hilfe. Alle Funktionen sind hier und über erreichbar
[Tastaturkürzel](../reference/keyboard-shortcuts.md). Ein Live-**JobScheduler
Das Statusmenü** erscheint nach *Hilfe*, wenn ein geplanter Eintrag aktiv ist.
- **Tab-Leiste** – jede SSH/Mosh-Sitzung wird in einem eigenen Tab ausgeführt. ++Strg+T++ öffnet Quick
Für einen neuen Tab verbinden; ++Strg+Tab++ / ++Strg+Umschalt+Tab++ Tabs wechseln.
- **Dashboard** (umschalten ++Strg+Umschalt+D++) – listet alle offenen Verbindungen mit Status auf
Indikatoren und KI-Agent-Abzeichen. Klicken Sie mit der rechten Maustaste auf eine Verbindung, um die Verbindung wiederherzustellen.
duplizieren, SFTP öffnen oder schließen.
- **Terminalbereich** – das aktive Terminal, mit optionalem geteiltem Bildschirm und
Broadcast-Eingang.
- **Statusleiste** – Verbindungsstatus, Host/IP, aktives Protokoll, temporärer SSH-Schlüssel
Timer und Verbindungsdauer.

!!! Tipp „Nur Terminal-Vollbild“
Drücken Sie ++f12++ (oder **Ansicht → Nur Terminal-Vollbild**), um alle Fenster auszublenden
Chrome und zeigen nur das Terminal. **Ansicht → Terminal-Bildlaufleisten ausblenden
Vollbild** entfernt auch die Bildlaufleisten. Drücken Sie erneut ++f12++, um die Wiederherstellung durchzuführen.

Siehe die [Menüreferenz](../reference/menu.md) für jeden Menüpunkt und die
[Tastaturkürzel-Referenz ](../reference/keyboard-shortcuts.md) für die vollständige Beschreibung
Liste der Beschleuniger.
