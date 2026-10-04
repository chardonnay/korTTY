---
title: Fenster
---

# Fenster

Auf dieser Registerkarte werden das Verhalten der Fenstergeometrie, die Beibehaltung des Dashboard-Status, die Sichtbarkeit der Menüleiste, der Rahmen um das Terminal einer farbigen Verbindung, die Übernahme des von der Shell gesetzten Titels in Terminal-Tabs, die Reihenfolge, in der ++ctrl+tab++ die Tabs wechselt, und der Umgang von korTTY mit den zuvor geöffneten Fenstern und Tabs beim Start konfiguriert. Öffnen über **Konfiguration → Globale Einstellungen → Fenster**; in `~/.kortty/global-settings.xml` gespeichert.

![Window settings tab](../../assets/screenshots/settings/window.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Fenstergeometrie merken | umschalten | — | Ein | `rememberWindowGeometry` |
| Dashboard-Status merken | umschalten | – | Ein | `rememberDashboardState` |
| Toolfenster als Registerkarten öffnen | umschalten | – | Aus | `openToolWindowsAsTabs` |
| Terminal in der Tab-Farbe seiner Verbindung umrahmen | umschalten | — | Ein | `connectionColorBorderEnabled` |
| Terminal-Tabs nach dem Titel benennen, den die Shell setzt | umschalten | — | Ein | `tabTitleFromShellEnabled` |
| Strg+Tab wechselt die Tabs in der Reihenfolge ihrer letzten Nutzung | umschalten | — | Aus | `tabSwitchMostRecentFirst` |
| Beim Start: | Auswahl | Wiederherstellen der vorherigen Sitzung anbieten / Vorherige Sitzung automatisch wiederherstellen / Nichts tun | Wiederherstellen der vorherigen Sitzung anbieten | `sessionRestoreMode` (`ask` / `auto` / `off`) |
| Feste Fenstergeometrie verwenden | umschalten | – | Aus | `useFixedWindowGeometry` |
| Breite: | Nummer | 400–4000 | – | `fixedWindowGeometry.width` |
| Höhe: | Nummer | 300–3000 | – | `fixedWindowGeometry.height` |
| X Position: | Nummer | 0–5000 | — | `fixedWindowGeometry.x` |
| Y-Position: | Nummer | 0–3000 | – | `fixedWindowGeometry.y` |

Wenn **Fenstergeometrie merken** aktiviert ist, speichert korTTY die Position und Größe jedes vom Benutzer veränderbaren Anwendungsfensters und benannten Dialogs separat. Beim erneuten Öffnen eines Fensters wird die für diesen Fenstertyp ausgewählte Geometrie wiederhergestellt. Wenn sein vorheriger Monitor nicht mehr angeschlossen ist, verschiebt korTTY das Fenster zurück auf einen verfügbaren Bildschirm, und ein Hauptfenster, das maximiert war, öffnet sich dort maximiert. Unter macOS werden die gespeicherten Grenzen eines Hauptfensters erneut angewendet, nachdem die native einheitliche Titelleiste bereit ist, sodass das System die wiederhergestellte Position beim Öffnen nicht verschieben kann. Eine geänderte Schriftartenskalierung der Benutzeroberfläche behält die gespeicherte Position bei, lässt das Fenster jedoch eine neue Größe berechnen, sodass übersetzte oder vergrößerte Beschriftungen weiterhin passen. Kurzlebige Bestätigungen und Fortschrittsmeldungen behalten ihre vom Inhalt abgeleitete Größe.

!!! note
    Wenn **Feste Fenstergeometrie verwenden** aktiviert ist, hat sie Vorrang vor **Fenstergeometrie speichern** für Hauptfenster des Terminals. Dialoge verwenden weiterhin ihre eigene gespeicherte Geometrie.

!!! note
    Die Einstellung **Dashboard-Status merken** behält bei, ob das Dashboard-Panel beim letzten Schließen der Anwendung geöffnet oder geschlossen war, und stellt diesen Status beim nächsten Start wieder her.

!!! note
    Mit **Tool-Fenster als Tabs öffnen** aktiviert, öffnen sich Verwaltungstools (Snippet-Manager, JobScheduler, KI-Manager, Gespeicherte Chats, Sitzungsjournale, Credential/GPG/SSH-Schlüsselverwaltung, Videoverwaltung, Teamwork-Einstellungen, Terminal-Effekte) als Tabs im Hauptfenster anstelle separater Fenster. Der Tab öffnet sich in dem Fenster, dessen Menü Sie benutzt haben; bei mehreren offenen Hauptfenstern sammelt jedes Fenster seine eigenen Tool-Tabs. Das erneute Öffnen eines Tools fokussiert den bereits vorhandenen Tab. Der Snippet-Manager hat einen Tab pro Hauptfenster und öffnet die von Ihnen bearbeiteten Snippets als Tabs innerhalb des Managers; ein aus einem anderen Ort geöffneter Snippet-Editor (z. B. der SFTP-Manager, der Dateibrowser oder das Terminal) sowie der Sitzungsjournal-Viewer öffnen jedes Mal einen neuen Hauptfenster-Tab, aber ein bereits geöffneter Snippet-Editor wird in den Vordergrund gebracht statt zweimal zu öffnen. Die Vollständige Code-Analyse ist ein Seitenpanel im Snippet-Editor, kein eigener Tab. Die Einstellung tritt beim nächsten Öffnen eines Tools in Kraft.

    Ein als Registerkarte gehostetes Werkzeug verfügt über keine separate Fenstergeometrie. Seine verfügbare Größe richtet sich nach dem Hauptfenster und seiner gespeicherten Hauptfenstergeometrie.

## Tabs

**Terminal in der Tab-Farbe seiner Verbindung umrahmen** zeichnet einen 3 Pixel breiten Rahmen um das Terminal jedes Tabs, dessen Verbindung eine [Tab-Farbe](../../features/connections.md#tab-farbe) hat, zusätzlich zum farbigen Punkt auf dem Tab, sodass ein Produktionsserver genau dort auffällt, wo Sie tippen. Der Rahmen umschließt den gesamten Tab-Inhalt — alle geteilten Bereiche und die Statusleisten darunter —, und Verbindungen ohne Tab-Farbe erhalten nie einen. Ein geteilter Bereich, der zu einer Verbindung geöffnet wurde, deren Tab-Farbe von der des Tabs abweicht, erhält an seinem Rand einen Rahmen in seiner eigenen Farbe, der die Größe seines Terminals nicht ändert (siehe [Tab-Farbe](../../features/connections.md#tab-farbe)). Schalten Sie die Option aus, um nur den Punkt zu behalten; damit verschwinden auch die Rahmen der Bereiche. Die Änderung gilt für die offenen Tabs aller Fenster, sobald Sie speichern.

!!! note
    Der Rahmen belegt auf jeder Seite des Terminals 3 Pixel. Das Ein- oder Ausschalten ändert daher die Größe der offenen Terminals jeder farbigen Verbindung, und das Zuweisen oder Entfernen einer Tab-Farbe ändert die Größe der Terminals dieser Verbindung: Die Gegenseite erhält die neue Größe, und Vollbildprogramme wie `vim`, `htop` oder `less` zeichnen sich neu.

**Terminal-Tabs nach dem Titel benennen, den die Shell setzt** lässt einen Terminal-Tab statt des Verbindungsnamens den Titel anzeigen, den die Shell oder ein anderes Programm darin mit der Escape-Sequenz OSC 0 oder OSC 2 setzt, etwa `user@host: directory`; ein Tab mit geteilten Bereichen zeigt den Titel seines fokussierten Bereichs. Ein Name, den Sie einem Tab mit [Tab umbenennen](../../features/terminal.md#arbeiten-mit-tabs) gegeben haben, hat weiterhin Vorrang. Diesen Titel bestimmt der Server, daher wird er von Steuer- und Bidi-Zeichen bereinigt, auf 80 Zeichen gekürzt und ändert nie die Tab-Farbe; wenn Sie auf einen solchen Tab zeigen, sehen Sie die Verbindung, zu der er gehört. Schalten Sie die Option aus, um auf jedem Tab die Verbindungsnamen zu behalten. Die Änderung gilt für die offenen Tabs aller Fenster, sobald Sie speichern. Siehe [Titel aus der Shell](../../features/terminal.md#titel-aus-der-shell).

**Strg+Tab wechselt die Tabs in der Reihenfolge ihrer letzten Nutzung** ändert, was ++ctrl+tab++ und ++ctrl+shift+tab++ tun (++ctrl++ auch unter macOS). Ist die Option aus, gehen sie zum nächsten bzw. vorherigen Tab der Tab-Leiste. Ist sie an, springt ++ctrl+tab++ zu dem Tab zurück, den Sie vor dem aktuellen benutzt haben, sodass ein Tastendruck zwischen Ihren beiden zuletzt benutzten Tabs wechselt. Halten Sie ++ctrl++ gedrückt und drücken Sie erneut ++tab++, um weiter durch die Tabs zurückzugehen, vom zuletzt bis zum am längsten nicht benutzten, nehmen Sie ++shift++ hinzu, um in die andere Richtung zu gehen, und lassen Sie ++ctrl++ beim gewünschten Tab los; ++ctrl+shift+tab++ allein beginnt bei dem Tab, den Sie am längsten nicht benutzt haben. Nur der Tab, bei dem Sie anhalten, zählt als benutzt, sodass die Tabs, die Sie unterwegs passieren, ihren Platz in der Reihenfolge behalten. Eine andere Taste, die Auswahl eines Tabs mit der Maus oder der Wechsel in ein anderes Fenster beendet das Durchblättern ebenfalls bei dem erreichten Tab, und die gedrückte Taste wirkt dann auf diesen Tab. Es ist dieselbe Reihenfolge, in der die [Befehlspalette](../../features/command-palette.md#tabs-wechseln) die Tabs auflistet: Sie gilt, solange das Fenster besteht, und wird nie gespeichert. Die Änderung gilt für alle Fenster, sobald Sie speichern; auch **Nächster Tab** und **Vorheriger Tab** der Palette folgen ihr.

## Sitzungswiederherstellung

**Beim Start:** legt fest, was korTTY mit den Fenstern und Tabs macht, die vor diesem Start geöffnet waren und die es während des Betriebs im [Sitzungs-Snapshot](../../features/projects.md#vorherige-sitzung) aufbewahrt:

- **Wiederherstellen der vorherigen Sitzung anbieten** (der Standard) zeigt über der Statuszeile des Fensters eine Leiste mit **Wiederherstellen** und **Verwerfen**, zum Beispiel *Fenster und Tabs von vor diesem Start wiederherstellen? Fenster: 2, Tabs: 6*. Sie blockiert das Fenster nie, und nichts öffnet sich, bevor Sie **Wiederherstellen** wählen.
- **Vorherige Sitzung automatisch wiederherstellen** öffnet sie von selbst wieder, sobald kein Dialog offen ist, damit die Fragen, die ihre Verbindungen stellen können, nie über einem anderen Dialog erscheinen; bleibt ein Dialog eine Minute lang offen, bietet korTTY sie stattdessen in der Leiste an.
- **Nichts tun** zeigt beim Start nichts an.

*Datei → Vorherige Sitzung wiederherstellen* öffnet die vorherige Sitzung in jedem Modus. In jedem Fall öffnen sich die Tabs, ohne etwas zu fragen: Ein Tab, der ein Passwort, einen neuen temporären SSH-Schlüssel oder den gesperrten Tresor braucht, wartet in der [Wiederherstellungsleiste](../../features/projects.md#tabs-die-auf-sie-warten). Endet korTTY innerhalb einer Minute nach einer Wiederherstellung unerwartet, bietet der nächste Start die Sitzung an, statt sie automatisch wiederherzustellen, sodass eine Sitzung, die korTTY abstürzen lässt, das nicht bei jedem Start tun kann. Ein fehlender oder unbekannter Wert in `global-settings.xml` bedeutet **Wiederherstellen der vorherigen Sitzung anbieten**, sodass eine beschädigte Datei nie von selbst Verbindungen öffnet. Die Einstellung wird beim nächsten Start wirksam. Siehe [Beim Start](../../features/projects.md#beim-start).
