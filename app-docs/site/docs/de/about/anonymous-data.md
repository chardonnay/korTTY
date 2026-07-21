---
title: Anonyme Daten zur Applikationsoptimierung
---

# Anonyme Daten zur Applikationsoptimierung

korTTY kann **anonyme Nutzungsstatistiken** sammeln, um zu entscheiden, welche Funktionen es wert sind, verbessert zu werden, und um Abstürze und häufige Fehler zu finden. Dies ist völlig optional, **standardmäßig deaktiviert** und kann jederzeit aktiviert oder deaktiviert werden.

![Anonymous telemetry consent and data flow](../assets/diagrams/telemetry-consent-flow.svg)

## Übersicht

* **Opt-in.** Es werden keine Daten erfasst, es sei denn, Sie stimmen ausdrücklich zu. Sie werden einmal gefragt, zusammen mit der Einrichtung des Master-Passworts beim ersten Start; Vorhandene Installationen werden nach der Freischaltung einmalig abgefragt.
* **Anonym.** Es wird kein Konto, kein Login und keine dauerhafte Gerätekennung übertragen.
* **Widerruflich.** Sie können Ihre Entscheidung jederzeit unter **Einstellungen → Datenschutz** ändern. Wenn Sie die Funktion deaktivieren, wird die Erfassung sofort gestoppt und alle noch nicht gesendeten Daten werden verworfen.

## Was gesammelt wird

| Daten | Beispiel |
| --- | --- |
| Ereignisnamen | App-Start, verwendete Funktion (z. B. ein Tool geöffnet, ein Backup erstellt) |
| Aggregierte Anzahl und Flags | Anzahl der geöffneten Terminal-Tabs, ob AI aktiviert ist |
| App-Version | 2.5.1 |
| Betriebssystem und Version | macOS 15, Windows 11, Linux |
| App-Sprache | de, en |
| Eine anonyme Sitzungs-ID | eine Zufallszahl, die bei jedem Start und nach einer Stunde Inaktivität neu generiert wird |

Die Sitzungs-ID ist **keine** dauerhafte Kennung: Sie wird jedes Mal neu generiert und kann bei jedem Start nicht auf Sie zurückgeführt werden.

## Was niemals gesammelt wird

korTTY übermittelt niemals Folgendes:

* Hostnamen, IP-Adressen, Benutzernamen oder Verbindungsnamen und -adressen
* Dateinamen, Pfade oder Verzeichnisinhalte
* Snippet-Inhalt, Terminalausgabe oder KI-Eingabeaufforderung und Chat-Text
* Passwörter, SSH-Schlüssel, GPG-Schlüssel oder API-Schlüssel
* Fehlermeldungen (nur die Art des Fehlers und die korTTY-Klasse, in der er aufgetreten ist)

## Wohin die Daten gehen

Nutzungsstatistiken werden verarbeitet von **[Aptabase](https://aptabase.com)**, ein Open-Source-Analysedienst, bei dem der Datenschutz an erster Stelle steht. korTTY verwendet die **EU-Region** von Aptabase (`eu.aptabase.com`), sodass die Daten unter Einhaltung der **DSGVO** auf Servern in der Europäischen Union verarbeitet werden. Siehe die [Aptabase-Datenschutzrichtlinie](https://aptabase.com/legal/privacy) für Einzelheiten.

Wenn keine Verbindung verfügbar ist, werden Ereignisse lokal in `~/.kortty` zwischengespeichert und später – auch nach einem Neustart – gesendet, sodass bei einem vorübergehenden Verbindungsausfall nichts verloren geht oder blockiert wird. Dieser Offline-Cache enthält nur dieselben anonymen Ereignisse. Es wird verworfen, wenn Sie sich abmelden, und Ereignisse, die älter als drei Tage sind, werden gelöscht.

## Warum korTTY es sammelt

Das Ziel besteht darin, korTTY mit echten, anonymen Beweisen statt mit Vermutungen zu verbessern:

* **Priorisieren Sie Funktionen**, die tatsächlich genutzt werden, und entfernen Sie diejenigen, die niemand nutzt.
* **Finden Sie Abstürze und häufige Fehler**, damit diese in der nächsten Version behoben werden können.
* **Messen Sie, ob Veröffentlichungen die Stabilität im Laufe der Zeit verbessern**.

## Ihre Auswahl

* **Erster Start:** Der Einrichtungsdialog für das Master-Passwort enthält ein Opt-in-Kontrollkästchen und diese Informationen.
* **Jederzeit:** Öffnen Sie **Einstellungen → Datenschutz**, um die Erfassung zu aktivieren oder zu deaktivieren. Die gleiche Seite verweist auf dieses Kapitel.
* **Durch Deaktivieren** wird die gesamte Erfassung sofort gestoppt und noch nicht gesendete Daten werden verworfen.

![Privacy settings tab](../assets/screenshots/settings/telemetry.png)

## Ihr Einwilligungsdatensatz

Ihre Entscheidung und das Datum, an dem sie getroffen wurde, werden als Einwilligungsprotokoll lokal in `~/.kortty/global-settings.xml` (siehe [Konfigurationsdateien](../reference/config-files.md)) gespeichert. Wenn eine zukünftige korTTY-Version ändert, was gesammelt wird, werden Sie erneut gefragt, sodass Ihre Auswahl immer den aktuellen Umfang widerspiegelt.
