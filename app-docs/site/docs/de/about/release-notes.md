# Versionshinweise

Was hat sich in der aktuellen Version geändert? Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.22.0

### KI-Agent-Designs

- **Wählen, wie Agent-Läufe aussehen** — *Plugins → KI-Agent Design* (auch *Ansicht → KI-Agent-Panel → Design...*) bestimmt, wie die Läufe des KI-Agenten im Terminal angezeigt werden: als Blöcke im Terminal oberhalb des Prompts, in einer schmalen Leiste darunter oder darüber, auf einer Schiene oder in einer Spalte daneben, als Karte, die darüber schwebt, oder im klassischen Aktivitäts-Panel. Eine Kombination wählt für jede Rolle (Verlauf, Status, Genehmigung, Anordnung) ein Design, mit Live-Vorschau; die neue Standardkombination zeigt den Lauf als Block im Terminal mit einer Linienplan-Statusleiste, und jede Verbindung kann im KI-Tab des Verbindungseditors eine eigene Kombination wählen. Siehe [KI-Agent-Designs](../features/ai-agent-designs.md).
- **Weitere Statusanzeigen** — Powerline-, Minimap-, Kopfleisten- und Neon-Anzeigen sowie die übrigen Designs des Katalogs vervollständigen die Auswahl. Siehe [Weitere Statusanzeigen](../features/ai-agent-designs.md#weitere-statusanzeigen).

### Lokale KI-Modelle

- **Kontextfenster im Profil** — ein Profil vom Typ Integriertes llama.cpp hat eine Zeile **Kontextfenster** mit Zahlenfeld und Schieberegler, von 512 Tokens bis zum Kontext, mit dem das Modell trainiert wurde; beim Speichern des Profils wird der Wert für das Modell übernommen. Neue lokale Chat-Modelle starten mit 32.768 Tokens, oder weniger, wenn das Modell für weniger trainiert wurde, sodass auch dichte Modelle auf üblicher Hardware laden. Siehe [Modelle konfigurieren und ausführen](../features/local-models.md#modelle-konfigurieren-und-ausfuhren).
- **GPU-Backend und Runtime-Paket passen zusammen** — wird ein Modell mit Metal oder Vulkan gespeichert, während ein anderes Runtime-Paket aktiv ist, bietet korTTY jetzt an, das passende signierte Paket zu installieren und zu aktivieren, und die Spalte **Backend** zeigt eine Abweichung wie `VULKAN (runtime: CPU)`. Siehe [Modelle konfigurieren und ausführen](../features/local-models.md#modelle-konfigurieren-und-ausfuhren).
- **Eigenständige Windows-Runtimes** — die llama.cpp-Pakete für Windows hängen nicht mehr von OpenSSL-Bibliotheken der Build-Maschine ab, sodass **Runtime installieren** auf einem frischen System funktioniert, und eine fehlgeschlagene Integritätsprüfung wird gemeldet, statt stillschweigend zurückgerollt zu werden.
- **Abgeschnittene Agent-Antworten werden gemeldet** — schneidet ein Modell die Antwort des Agenten an seinem Token-Limit ab, hält der Lauf mit einer Meldung an, die die Token-Zahlen nennt und auf die Kontextgröße des Modells oder die maximalen Ausgabe-Tokens des Profils verweist, statt mit einer leeren Antwort zu enden.

### Symbolleiste, Terminal und Windows

- **Connection-Manager in der Symbolleiste** — die Gruppe Werkzeuge der Icon-Leiste beginnt jetzt mit einer Schaltfläche für den Connection-Manager; gespeicherte Layouts erhalten sie einmalig, und eine Entfernung wird gespeichert. Siehe [Anpassung der Symbolleiste](../getting-started/main-window.md#anpassung-der-symbolleiste).
- **Strg+V fügt unter Windows im Terminal ein** — wie im Windows Terminal; unter Linux bleibt ++ctrl+v++ bei der Shell. Siehe [Text einfügen](../features/terminal.md#text-einfugen).
- **Dunkle Titelleisten unter Windows** — im dunklen Modus erhalten das Hauptfenster und alle Dialoge eine dunkle native Titelleiste, und ein Wechsel des Systemdesigns erreicht auch geöffnete Fenster.
- **Lokale Shells kommen zurück** — *Vorherige Sitzung wiederherstellen* öffnet lokale Shell-Tabs, die über die Symbolleiste gestartet wurden, wieder, statt sie als „Verbindung fehlt“ abzulegen. Siehe [Sitzungswiederherstellung](../reference/settings/window.md#sitzungswiederherstellung).
- **Terminal-Agent unter PowerShell und cmd** — eine abgefangene Zeile `agent ...` wird ohne ein verirrtes `^U` gelöscht, und verschachtelte `powershell -Command`-Skripte des Modells laufen korrekt.

### Korrekturen

- **Agent-Abfragen passen ins Panel** — eine ausstehende Genehmigungs- oder Passwortabfrage vergrößert ein eingeklapptes Agent-Panel, bis die Abfrage vollständig sichtbar ist.
- **KI-Bestätigung erklärt** — ein Hinweis unter **Bestätigungsdialog vor dem Senden an die KI anzeigen** nennt die Aktionen, für die er gilt, und die Schaltfläche **Speichern** der Tabs Allgemein, Lokale KI und KI-Skills sitzt unten rechts. Siehe [KI-Einstellungen](../reference/settings/ai.md).
- **Aufnahme- und Journal-Symbole folgen dem Design** — sie werden in den AtlantaFX-Designs nicht mehr schwarz.

