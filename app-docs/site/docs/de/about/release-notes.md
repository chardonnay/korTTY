# Versionshinweise

Was hat sich in der aktuellen Version geändert? Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.13.0

### Verbindungsabbruch und Wiederverbinden

- **Eine verlorene Verbindung schließt den Tab nicht mehr** – bricht eine bestehende SSH-Verbindung ab (Netzwerkausfall, VPN-Trennung, Server weg), bleibt der Tab jetzt in einem roten `(DISCONNECT)`-Zustand offen, mit einer roten Statusleiste, die den Zeitpunkt des Abbruchs zeigt, statt sich still zu schließen, als hätten Sie `exit` eingegeben. Wiederverbinden im selben Tab per Doppelklick auf die Leiste oder den Tab oder über **Neu verbinden** in den Kontextmenüs; ein normaler Remote-Logout schließt den Tab weiterhin wie bisher.
- **Ein Verbindungsabbruch wird innerhalb von etwa zehn Sekunden erkannt** – korTTY prüft den Server aktiv über die SSH-Verbindung (dieselbe Technik wie OpenSSHs `ServerAliveInterval`) und wertet zwei aufeinanderfolgende unbeantwortete Prüfungen als Verbindungsverlust, statt Minuten auf einen TCP-Timeout zu warten. In einem getrennten Tab hört außerdem der Terminal-Cursor auf zu blinken, sodass eine tote Sitzung nicht mehr aktiv aussieht.
- **Automatisches Wiederverbinden** – eine neue Option **Einstellungen → Terminal → Verlorene Verbindungen automatisch wiederherstellen** (standardmäßig aktiviert) verbindet einen getrennten Tab selbstständig neu, mit Wartezeiten, die von 3 Sekunden bis zu einem Versuch pro Minute wachsen, und einem Countdown in der roten Statusleiste. Dauerhafte Fehler wie ein falsches Passwort, ein geänderter Hostschlüssel oder eine Konfigurationsablehnung werden nie automatisch wiederholt, und solange ein Sitzungsjournal über das weitere Vorgehen entscheidet, hat die Wiederverbinden-Wahl des Journals Vorrang. Siehe [Terminal-Sitzungen → Verbindungsverlust](../features/terminal.md).

### Datenschutz

- **Anonyme Nutzungsstatistiken sind bei der Ersteinrichtung jetzt vorausgewählt** – das Kontrollkästchen neben der Master-Passwort-Einrichtung ([Anonyme Daten zur Anwendungsoptimierung](../about/anonymous-data.md)) ist standardmäßig aktiviert, sodass das Teilen die Voreinstellung ist und das Entfernen des Hakens vor der Bestätigung den Opt-out darstellt. Eine Organisationsrichtlinie, die Telemetrie verbietet, sperrt das Kontrollkästchen stattdessen auf den erzwungenen Zustand. An der Erfassung selbst ändert sich nichts: freiwillig, anonym, EU-Server, jederzeit änderbar unter **Einstellungen → Datenschutz**.

### Darstellung

- **Eine Neuinstallation startet jetzt mit aktivierter Option [An Bildschirmauflösung anpassen](../reference/settings/appearance.md#ui-schriftgroe)** – korTTY passt die UI-Schriftgröße von Anfang an an den Bildschirm an, statt erst, wenn jemand die Einstellung findet. Bei einer Update-Installation ändert sich daran nichts: Es bleibt bei der bisherigen Einstellung.

### Übersetzung

- **Yandex Translate funktioniert wieder** – der Dienst sprach noch die abgeschaltete Translate-API v1.5, deren Zugangsdaten Yandex nicht mehr akzeptiert, sodass der Anbieter für jeden, der ihn auswählte, nicht mehr funktionierte. Er ruft jetzt Cloud Translate v2 mit der aktuellen `Api-Key`-/IAM-Token-Authentifizierung auf, fasst Anfragen gemäß den Grenzwerten der API zusammen und sendet keine übrig gebliebene v1.5-API-URL mehr an einen nicht mehr existierenden Host. Bruchstücke eines abgelehnten Schlüssels, die Yandex in seiner eigenen Fehlermeldung zurückspiegelt, werden jetzt geschwärzt, bevor sie in eine Protokolldatei gelangen.
- **Microsoft Translator erreicht jetzt regionale Ressourcen und Ressourcen mit eigener Domain** – eine neue optionale Einstellung **Azure-Region**, die nur bei ausgewähltem Microsoft Translator angezeigt wird, wird von jeder nicht-globalen Azure-Ressource benötigt; eine solche Ressource war zuvor überhaupt nicht erreichbar.
- **DeepL erholt sich jetzt von einer falsch geratenen Free/Pro-Host-Zuordnung** – das Schlüsselsuffix, anhand dessen korTTY den Host wählt, ist nur bei älteren Free-Schlüsseln zuverlässig; eine 403-Antwort löst jetzt einen erneuten Versuch beim jeweils anderen Host aus, statt fehlzuschlagen.

!!! note "Frühere Versionen"
    Hier wird nur die aktuelle Version aufgeführt, daher bleibt der Anleitung in jeder Sprache, in die er übersetzt wurde, kurz. Jede Version ist auf der [GitHub-Release-Seite ](https://github.com/chardonnay/korTTY/releases); Die kuratierten Notizen für frühere Versionen werden im Repository in `app-docs/release-notes-archive.md` und `app-docs/RELEASE_NOTES.adoc` aufbewahrt.
