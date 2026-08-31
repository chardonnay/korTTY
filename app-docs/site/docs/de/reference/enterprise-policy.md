---
title: Richtlinienkonfiguration
---

# Richtlinienkonfiguration

In verwalteten Umgebungen kann ein Administrator korTTY über eine einzige TOML-Datei, `kortty-policy.toml`, die im Installationsverzeichnis abgelegt wird, einschränken oder vorkonfigurieren. Benutzer können es nicht ändern oder umgehen: Die Datei wird nur aus dem vom Administrator beschreibbaren Installationsordner gelesen, gesperrte Einstellungen werden ausgegraut mit dem Hinweis „Von Ihrer Organisation verwaltet“ angezeigt und die manuelle Bearbeitung von `global-settings.xml` wird beim nächsten Laden rückgängig gemacht. In diesem Kapitel wird erläutert, wo sich die Datei befindet, wie Regeln auf Benutzer, Gruppen und Server abzielen und alle verfügbaren Parameter anhand von Beispielen dokumentiert werden.

!!! note
    Ohne `kortty-policy.toml` ändert sich nichts – korTTY verhält sich genauso wie zuvor. Bei der ausgelieferten Vorlage `policy/kortty-policy.toml.example` handelt es sich um eine vollständig kommentierte Vorlage, bei der alles deaktiviert ist.

## Dateispeicherort und Sicherheitsmodell

korTTY lädt die Richtlinie ausschließlich aus dem Ordner `policy/` seines Installationsverzeichnisses – dem Ordner, der auch die Anwendungs-JAR-Datei enthält – und niemals aus `~/.kortty/` oder einem anderen vom Benutzer beschreibbaren Speicherort. Eine kopierfertige Vorlage wird als `policy/kortty-policy.toml.example` geliefert; Kopieren Sie es nach `kortty-policy.toml` im selben Ordner und starten Sie korTTY neu (es gibt kein Hot-Reload).

| Plattform | Speicherort der Richtliniendatei |
| --- | --- |
| macOS | `/Applications/KorTTY.app/Contents/app/policy/kortty-policy.toml` |
| Windows | `C:\Program Files\KorTTY\app\policy\kortty-policy.toml` |
| Linux (deb/rpm) | `/opt/kortty/lib/app/policy/kortty-policy.toml` |

Das Durchsetzungsmodell basiert auf den Dateiberechtigungen des Betriebssystems: Das Installationsverzeichnis darf nur von Administratoren beschreibbar sein, was für die oben genannten Speicherorte die Standardeinstellung ist. korTTY protokolliert zusätzlich eine Warnung, wenn die aktive Richtliniendatei vom aktuellen Benutzer beschreibbar ist. Während der Entwicklung (niemals in einer Paketinstallation) kann eine Richtlinie mit `-Dkortty.policy.file=/path/to/policy.toml` getestet werden.

Wenn die Datei existiert, aber nicht geparst werden kann oder einen ungültigen Wert enthält, startet korTTY mit einer ausfallsicheren Sperre: Alle durch Richtlinien steuerbaren Funktionen werden verweigert, keine Serververbindung ist erlaubt und ein Startdialog benennt die Datei und die genaue Fehlerposition. Ein Tippfehler kann daher niemals stillschweigend die Durchsetzung verhindern. Eine ungültige Regel lehnt die gesamte Datei ab; Unbekannte Schlüssel erzeugen nur Protokollwarnungen, daher sperrt eine für ein neueres korTTY geschriebene Richtlinie Benutzer einer älteren Version nicht aus.

## Benutzer, Gruppen und Regelpriorität

Regeln zielen auf den aktuellen Anmeldenamen des Betriebssystems ab (entspricht Kleinbuchstaben). Die Gruppenmitgliedschaft stammt aus zwei Quellen gleichzeitig: Gruppen, die in der Tabelle `[groups]` der Richtlinie definiert sind, und den Gruppenmitgliedschaften des Benutzers auf Betriebssystemebene. Auf in die Domäne eingebundenen Windows-Computern umfassen die Betriebssystemgruppen Active Directory-Gruppen, sodass Regeln direkt auf AD-Gruppen abzielen können – sowohl vollständig qualifiziert (`ACME\Operations`) als auch als bloßer Name (`operations`) – ohne dass eine Verzeichnisserverkonfiguration erforderlich ist.

Jeder `[[rule]]`-Block benennt optional `users` und/oder `groups`; Eine Regel, die keine Benennung vornimmt, gilt für jeden Benutzer. Pro Einstellung gewinnt die spezifischste Stufe, die sie festlegt: **Benutzer schlägt Gruppe schlägt alle**. Das ist das bekannte Gruppenrichtlinienmuster: Sperren Sie alles in einer Grundregel für alle und lockern Sie dann die individuellen Einstellungen für eine vertrauenswürdige Gruppe. Wenn mehrere Regeln derselben Ebene denselben Schlüssel festlegen, gilt der restriktivste Wert (`deny` über `read-only` über `confirm` über `allow`); Gemäß den Serverregeln muss eine Verbindung alle geltenden Einschränkungen der Gewinnerstufe erfüllen. Die Reihenfolge der Regeln in der Datei hat keine Bedeutung.

```toml
[meta]
schema-version = 1
organization = "ACME Corp"

[groups]
devs = ["alice", "bob"]

[[rule]]                              # applies to ALL users
name = "company-baseline"
  [rule.features]
  ai-agent = "deny"

[[rule]]                              # group tier: relaxes the baseline for ops
name = "ops-exception"
groups = ["ops", "ACME\\Operations"]  # policy group OR OS/AD group
  [rule.features]
  ai-agent = "allow"
  ai-agent-execution = "confirm"

[[rule]]                              # user tier: beats eve's group
users = ["eve"]
  [rule.features]
  ai-agent = "deny"
```

## Server-Zugriffskontrolle

Die `[rule.servers]`-Tabelle schränkt ein, zu welchen Servern ein Benutzer eine Verbindung herstellen darf – als Zulassungsliste (`mode = "allow"`: nur aufgelistete Server sind erreichbar) oder als Sperrliste (`mode = "deny"`: aufgelistete Server sind blockiert). Die Einschränkung wird zentral für jeden Verbindungspfad durchgesetzt: gespeicherte Verbindungen, QuickConnect, Sitzungswiederherstellung, SFTP, von Teamwork freigegebene Verbindungen, KI-Schwarmziele und geplante Jobs, einschließlich des Jump-Hosts einer Verbindung. Blockierte Verbindungen bleiben im Verbindungsmanager sichtbar, werden jedoch mit einer Sperrmarkierung ausgegraut und bei jedem Verbindungsversuch wird eine eindeutige Richtlinienmeldung angezeigt.

Muster stimmen genau mit der Hostzeichenfolge überein, wie sie in der Verbindung konfiguriert ist – korTTY löst DNS nie für Richtlinienprüfungen auf, daher sind Hostnamen und IP-Adressen separate Namespaces: Wenn ein Server in beide Richtungen erreichbar ist, listen Sie beide auf.

| Musterform | Beispiel | Entspricht |
| --- | --- | --- |
| Genauer Hostname | `db01.acme.com` | dieser Host, ohne Berücksichtigung der Groß-/Kleinschreibung, jeder Port |
| Hostname-Glob | `*.prod.acme.com`, `web-??.acme.com` | `*` = beliebige Zeichen, `?` = ein Zeichen |
| Host mit Port | `vault.acme.com:22` | nur Verbindungen zu diesem Port |
| Einzelne IP-Adresse | `192.168.10.42`, `2001:db8::1` |, die Adresse (IPv4 oder IPv6) |
| IP mit Port | `192.168.10.42:22`, `[2001:db8::1]:22` | IPv6-Ports erfordern die Halterungsform |
| CIDR-Netzwerk | `10.99.0.0/16`, `2001:db8::/32` | jede Adresse im Netzwerk |
| IP-Bereich | `10.20.0.100-10.20.0.199` | inklusive Von–Bis-Bereich |

```toml
[[rule]]
  [rule.servers]
  mode  = "deny"
  hosts = ["*.prod.acme.com", "vault.acme.com:22", "192.168.10.42", "10.99.0.0/16", "10.20.0.100-10.20.0.199"]
```

## Parameterreferenz

### `[meta]`

| Schlüssel | Typ | Werte | Erforderlich | Wirkung |
| --- | --- | --- | --- | --- |
| `schema-version` | ganze Zahl | `1` | Ja | Abgelehnt (Sperrung), wenn dieser korTTY die Version nicht versteht |
| `organization` | Zeichenfolge | Freitext | Nein | Wird in jedem Hinweis und Dialog „Von Ihrer Organisation verwaltet“ angezeigt |

### `[groups]`

| Schlüssel | Typ | Wirkung |
| --- | --- | --- |
| `<group-name>` | Array von Benutzernamen | Definiert eine Richtliniengruppe; Regeln, die auf den Namen verweisen, zielen auf seine Mitglieder ab. OS/AD-Gruppen passen automatisch zusammen und benötigen hier keinen Eintrag |

### `[[rule]]` Bereich

| Schlüssel | Typ | Wirkung |
| --- | --- | --- |
| `name` | Zeichenfolge | Optionale Bezeichnung, die in Protokollnachrichten verwendet wird |
| `users` | Array von Benutzernamen | Zielt auf die aufgelisteten Betriebssystem-Anmeldenamen (Benutzerebene) |
| `groups` | Array von Gruppennamen | Zielt auf Mitglieder von Richtliniengruppen und/oder OS/AD-Gruppen (Gruppenebene) ab; Eine Regel mit weder `users` noch `groups` gilt für alle |

### `[rule.servers]`

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `mode` | Zeichenfolge | `allow`, `deny` | Semantik der Zulassungs- oder Ablehnungsliste für `hosts` |
| `hosts` | Array von Mustern | siehe Tabelle oben | Nicht leere Liste von Servermustern |

### `[rule.features]`

| Schlüssel | Typ | Werte | Beschränkt |
| --- | --- | --- | --- |
| `ai` | Zeichenfolge | `allow`, `deny` | Hauptschalter: `deny` deaktiviert alle AI-Funktionen auf einmal. |
| `ai-agent` | Zeichenfolge | `allow`, `deny` | AI Agent (Menü, Terminal-Kontextmenü, Tastaturkürzel, Headless-Job-Ausführungen) |
| `ai-chat` | Zeichenfolge | `allow`, `deny` | KI-Chat, gespeicherte Chats und die Terminalauswahl-KI-Aktionen |
| `ai-swarm` | Zeichenfolge | `allow`, `deny` | KI-Schwarm, einschließlich geplanter Schwarmjobs |
| `ai-planning` | Zeichenfolge | `allow`, `deny` | AI-Planung |
| `teamwork` | Zeichenfolge | `allow`, `deny` | Synchronisierung freigegebener Teamwork-Verbindungen (Dienst ist nicht gestartet, Menü gesperrt) |
| `plugins` | Zeichenfolge | `allow`, `deny` | Laden des Plugins und Plugins-Menü (z. B. Terminaleffekte) |
| `session-journal` | Zeichenfolge | `allow`, `deny` | The [session journal](../features/session-journal.md): Erfassung, Journalleiste, Manager, Viewer und Exporte. Nicht angekettet `ai` – Auch wenn die KI verweigert wird, zeichnet das Journal immer noch Rohaktivitäten auf |
| `ai-agent-execution` | Zeichenfolge | `allow`, `confirm`, `read-only` | `confirm` erzwingt die interaktive Genehmigung jedes mutierenden Befehlssatzes und deaktiviert die Option zur automatischen Genehmigung; `read-only` lässt den Agenten planen und chatten, aber niemals Befehle ausführen |

### `[rule.security]`

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `require-master-password` | boolean | `true` | Erzwingt das Master-Passwort-Gate beim Start; Die Einstellung ist gesperrt |
| `enforce-host-key-check` | boolean | `true` | Die Überprüfung des SSH-Hostschlüssels kann nirgendwo deaktiviert werden – global, pro Gruppe oder pro Verbindung |
| `allow-telemetry` | boolean | `false` | Verbietet anonyme Nutzungsstatistiken |
| `allow-terminal-recording` | boolean | `false` | Verbietet die Aufzeichnung von Terminalsitzungen, einschließlich der Umschaltung auf Sitzungsebene |
| `clipboard-mode` | Zeichenfolge | `system`, `internal` | `internal` beschränkt korTTY auf seine eigene Zwischenablage im Speicher – siehe unten |

### `[rule.teamwork]`, `[rule.snippets]`, `[rule.ai-profiles]`

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `allow-custom-sources` | boolean | `false` | Benutzer können keine Teamwork-Quellen hinzufügen; Es verbleiben nur die Einträge `[[teamwork-source]]` und |
| `allow-custom-script-headers` | boolean | `false` | Benutzer können keine Skript-Header erstellen; Es verbleiben nur die Einträge `[[script-header]]` und |
| `allow-create` | boolean | `false` | Benutzer können keine AI-Profile erstellen (Schaltflächen und Assistent sind gesperrt) |
| `allow-edit` | boolean | `false` | Benutzer können ihre vorhandenen AI-Profile auch nicht bearbeiten |
| `allow-internet` | boolean | `false` | Verbietet jeden KI-Internetzugriffsmodus – siehe unten |

!!! info "`allow-internet = false` unterbindet den KI-Internetzugriff auf drei Ebenen"
    Im Dropdown-Menü [internet access](settings/ai.md#internetzugriffsmodi) eines AI-Profils wird ein ausgewählt
    Backend für Websuche oder MCP-Browsing. Das Verbot wird an drei Stellen durchgesetzt, weil eine
    einzelne eine Lücke ließe:

    1. **Gespeicherte Einstellungen** – der Modus jedes Profils wird auf *Deaktiviert* zurückgesetzt, wenn die Einstellungen geändert werden
       *Deaktiviert* zurückgesetzt. So überlebt weder ein Wert aus der Zeit vor der Richtlinie noch
       eine Handänderung an `global-settings.xml`.
    2. **Die Schnittstelle** – das Dropdown-Menü ist mit dem Hinweis „Von Ihrer Organisation verwaltet“ gesperrt
       sowohl den KI-Manager als auch **Einstellungen → AI**.
    3. **Jede AI-Anfrage** – korTTY weigert sich, einen Dienst für ein Profil zu erstellen, dessen Modus aktiviert ist,
       und schließt damit das Zeitfenster zwischen zwei Klemmungen. Die Anfrage scheitert mit einer
       Richtlinienmeldung, statt still ohne das angeforderte Web-Werkzeug zu antworten.

    Dies betrifft ausschließlich die Such-Backends. Ein *Cloud*-KI-Profil wird dadurch nicht daran
    eigener Anbieter – verweigern Sie dazu die `ai`-Funktion oder stellen Sie an dieser Stelle `[[ai-profile]]`-Einträge bereit
    stellen `[[ai-profile]]`-Einträge bereit, die auf einen internen Endpunkt zeigen.

### `[rule.ai-runtime]`

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `allow-runtime-downloads` | boolean | `false` | Keine llama.cpp/MLX-Laufzeit-Downloads oder Update-Prüfungen |
| `allow-model-downloads` | boolean | `false` | Der Browser und die Downloads des Hugging Face-Modells sind deaktiviert |
| `allow-user-models` | boolean | `false` | Nur vom Administrator bereitgestellte `[[ai-runtime.model]]`-Einträge können von eingebetteten AI-Profilen geladen werden |

### `[rule.updates]`

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | Deaktiviert die automatische Update-Prüfung und die manuelle Prüfung im Info-Dialog |
| `feed-url` | Zeichenfolge | http(s)-URL | Aktualisierungsprüfungen fragen diesen Endpunkt anstelle von GitHub ab; Es muss dieselbe JSON-Form zurückgeben wie die GitHub `releases/latest`-API (`tag_name`, `assets[]` mit `name` und `browser_download_url`) |

### `[rule.terminal]`

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `load-into-snippet-editor` | Zeichenfolge | `allow`, `read-only`, `deny` | `read-only` Lädt weiterhin entfernte Dateien in den Snippet-Editor, verbietet jedoch das Zurückschreiben in das Zielsystem; `deny` entfernt die Funktion vollständig |

### `[rule.logging]`

korTTY rotiert sein Protokoll täglich (`kortty.YYYY-MM-DD.log`); Diese Tabelle steuert, wohin die Dateien gehen, wie lange sie leben, wie sie formatiert sind und wie die Rotation begrenzt wird. Verzeichnis und Aufbewahrung werden in die entsprechenden (dann gesperrten) Benutzereinstellungen gezwungen; Format, Komprimierung und die Rotationsbeschränkungen haben keine Benutzereinstellung und wirken direkt. Die Konfiguration wird vor der allerersten Protokollzeile eines Starts angewendet, sodass auch die Startprotokollierung dem Schema des Administrators folgt.

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `directory` | Zeichenfolge | absoluter oder `~/`-relativer Pfad | Protokollverzeichnis (Standard `~/.kortty/logs`); Die Einstellung ist gesperrt |
| `retention-days` | Ganzzahl | `0` = für immer behalten | Rotierte Protokolle, die älter als N Tage sind, werden gelöscht; Die Einstellung ist gesperrt |
| `compress` | boolean | Standard `true` | Gzip rotierte Protokolle nach einem Tag |
| `format` | Zeichenfolge | `text`, `json` | `json` schreibt ein strukturiertes JSON-Objekt pro Ereignis (Logback-JSON-Encoder – praktisch für SIEM/zentrale Protokollerfassung) |
| `rotation-max-files` | Ganzzahl | `0` = unbegrenzt | Behalten Sie höchstens N rotierte tägliche Dateien bei |
| `rotation-total-size-mb` | Ganzzahl | `0` = nicht begrenzt | Begrenzen Sie die Gesamtgröße aller gedrehten Dateien (älteste zuerst gelöscht) |

```toml
[[rule]]
  [rule.logging]
  directory = "/var/log/kortty"
  retention-days = 30
  compress = true
  format = "json"
  rotation-max-files = 14
  rotation-total-size-mb = 512
```

!!! note
    Wenn mehrere gleichstufige Regeln die Protokollierung konfigurieren, wird jeder Schlüssel separat aufgelöst: kürzere Aufbewahrung und engere Obergrenzen gewinnen, Komprimierung gewinnt, `json` gewinnt gegenüber `text`. In der Praxis fassen Sie die Protokollierungskonfiguration in einer einzigen Regel für alle Benutzer zusammen. Das ausgewählte Verzeichnis muss für den Benutzer, der korTTY ausführt, beschreibbar sein.

### `[rule.session-journal]`

Mandate für das [Sitzungsjournal](../features/session-journal.md). Erzwungene Werte sperren die entsprechenden Steuerelemente in den Journaloptionen, im Einstellungsdialog und im Verbindungseditor mit dem Hinweis „Von Ihrer Organisation verwaltet“.

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `enforced` | Boolescher Wert | `true` | Für **jede** Verbindung wird ein Journal geschrieben, unabhängig von der verbindungsspezifischen Einstellung; Benutzer können es nicht stoppen und der Aktivierungsschalter ist gesperrt |
| `log-format` | Zeichenfolge | `json` (Standard), `xml`, `yaml` | Erzwingt das Capture-Log-Format für neue Journale |
| `ai-max-lines` | Ganzzahl | `0` = Kontextfüllung | Erzwingt das KI-Auswertungsfenster (max. Terminalzeilen pro Zusammenfassung) |
| `storage-path` | Zeichenfolge | absoluter Pfad | Erzwingt das Journalspeicherverzeichnis; Die Einstellung ist gesperrt |
| `allow-rename` | boolean | `false` | Journale können im Manager nicht umbenannt werden |
| `allow-delete` | boolean | `false` | Journale können im Manager nicht gelöscht werden |
| `name-template` | Zeichenfolge | Vorlage | Ursprünglicher Journaltitel mit den Platzhaltern `{connection}`, `{host}`, `{user}`, `{date}` und `{time}` |
| `ai-title` | Boolescher Wert | `true` | Der abschließende KI-Titel wird unabhängig von der Benutzereinstellung generiert |
| `ai-screenshot-analysis` | boolean | `true` / `false` | `true` erzwingt die KI-Screenshot-Analyse, `false` verbietet sie – einschließlich der manuellen Ausführung pro Screenshot; Die Journaloption ist in beide Richtungen gesperrt |
| `ai-ask` | boolean | `false` | Verbietet On-Demand-KI für Journalinhalte: Das Frage-und-Antwort-Panel des Viewers und die tagebuchübergreifende KI-Suche des Managers werden ausgeblendet. KI-Zusammenfassungen sind nicht betroffen |
| `max-log-parts` | Ganzzahl | ≥ 1 | Begrenzt die Anzahl der rotierten Capture-Logteile pro Journal; Der wirksame Grenzwert ist das Minimum dieser Obergrenze und der Einstellung pro Verbindung, und der Spinner des Verbindungseditors ist darauf begrenzt |

```toml
[[rule]]
  [rule.features]
  session-journal = "allow"
  [rule.session-journal]
  enforced = true
  log-format = "json"
  storage-path = "/srv/audit/kortty-journals"
  allow-delete = false
  name-template = "{connection} {date} {time} ({user})"
```

!!! note
    `enforced`-Mandate erfassen, nicht AI: Wenn AI verweigert oder nicht verfügbar ist, zeichnet das erzwungene Journal Rohaktivitätseinträge auf. Wenn mehrere gleichstufige Regeln das Journal konfigurieren, werden `enforced` und `ai-title` zu „true“ aufgelöst, wenn eine Regel sie festlegt, `allow-rename`/`allow-delete` zu „false“, wenn eine Regel sie verbietet, `ai-screenshot-analysis` und `ai-ask` zu „off“, wenn eine Regel sie ausschaltet, die Zeilenobergrenze wird auf den engeren Wert aufgelöst (`0` gilt als unbegrenzt) und `max-log-parts` wird auf die niedrigere Obergrenze aufgelöst.

### `[[rule.session-journal.replace]]`

Automatisches Suchen und Ersetzen in jedem Journal – die Möglichkeit, eine ganze Kategorie von Geheimnissen aus dem Transkript herauszuhalten, anstatt sich darauf zu verlassen, dass der Benutzer es bemerkt. Jeder Eintrag stellt eine Regel dar, und eine Regel kann einen regulären Ausdruck verwenden.

| Schlüssel | Typ | Standard | Wirkung |
| --- | --- | --- | --- |
| `pattern` | Zeichenfolge | *erforderlich* | Der zu suchende Text. Wenn `regex` aktiviert ist, handelt es sich um einen regulären Ausdruck |
| `replacement` | Zeichenfolge | `***` | Der Text, der jede Übereinstimmung ersetzt. In einer Regex-Regel `$1` fügt eine erfasste Gruppe ein |
| `regex` | boolean | `false` | Behandelt `pattern` als regulären Ausdruck |
| `ignore-case` | boolean | `false` | Entspricht jeder Groß-/Kleinschreibung |
| `label` | Zeichenfolge | – | Beschreibung für den Administrator; korTTY zählt nur die Regeln in seinem UI-Hinweis |

```toml
[[rule]]
  [rule.session-journal]
  enforced = true

    [[rule.session-journal.replace]]
    pattern = "AKIA[0-9A-Z]{16}"
    replacement = "***AWS-ACCESS-KEY***"
    regex = true
    label = "AWS access keys"

    [[rule.session-journal.replace]]
    pattern = "(?i)bearer\\s+[A-Za-z0-9._-]{20,}"
    replacement = "Bearer ***"
    regex = true
    label = "Bearer tokens"

    [[rule.session-journal.replace]]
    pattern = "vpn.internal.acme.corp"
    replacement = "<internal-host>"
    ignore-case = true
```

Die Regeln werden **im Erfassungsthread ausgeführt, bevor eine Zeile geschrieben wird**, sodass ein übereinstimmender Text überhaupt nicht in die Protokolldatei gelangt, und sie werden auch auf KI-Zusammenfassungen und Benutzernotizen angewendet. Im Gegensatz zu den anderen Schlüsseln werden Ersetzungsregeln **über alle übereinstimmenden Regeln und alle Ebenen hinweg zusammengeführt** und nicht, dass die höchste Ebene gewinnt: Mehr Schwärzung ist das restriktivere Ergebnis, sodass eine benutzer- oder gruppenspezifische Regel nur Muster hinzufügen und niemals die organisationsweiten ausschalten kann. Doppelte Einträge werden ausgeblendet.

!!! warning
    Ein `pattern`, der kein gültiger regulärer Ausdruck ist, ist ein **Richtlinienfehler** und keine Warnung – die Datei wird abgelehnt. Eine Regel, die stillschweigend nichts übereinstimmt, ist schlimmer als eine Regel, die der Administrator korrigieren muss, weil die Schwärzung scheinbar vorhanden ist.

!!! note
    Es fallen nur Journale an, die während der Geltungsdauer der Regelung verfasst wurden; bestehende Journale werden nicht rückwirkend umgeschrieben. Benutzer können diese mit der Such- und Ersetzungsfunktion des Viewers bereinigen.

### Von Admin bereitgestellte Objekte

Diese Tabellen der obersten Ebene definieren Objekte, die für jeden Benutzer schreibgeschützt und mit der Kennzeichnung „Von Ihrer Organisation bereitgestellt“ gekennzeichnet sind. Sie werden bei jedem Start aus der Richtlinie neu erstellt und nie in die Konfigurationsdateien des Benutzers geschrieben. Wenn Sie sie aus der Richtlinie entfernen, werden sie auch aus korTTY entfernt.

| Tabelle | Schlüssel | Notizen |
| --- | --- | --- |
| `[[script-header]]` | `name`, `content` | Unveränderlicher Skript-Header in der Script-Header-Kategorie des Snippet-Systems |
| `[[ai-profile]]` | `id`, `name`, `provider`, `endpoint`, `model`, `api-key-encrypted` | `id` muss mit `policy-` beginnen; `provider` ist einer von `anthropic`, `openai-compatible`, `lm-studio`, `embedded-llama`, `embedded-mlx` (eingebettete Anbieter lesen `model` als lokale Modell-ID) |
| `[[ai-runtime.model]]` | `name`, `runtime`, `source` | `runtime` Ist `llama` oder `mlx`; `source` ist ein absoluter lokaler/UNC-Pfad oder für GGUF-Modelle eine http(s)-URL, die korTTY einmal beim Start herunterlädt |
| `[[teamwork-source]]` | `name`, `type`, `url` | `type` Ist `git` oder `shared-file`; als schreibgeschützte Teamwork-Quelle eingefügt |

## Interner Zwischenablagemodus

Mit `clipboard-mode = "internal"` trennt sich korTTY vollständig von der Zwischenablage des Betriebssystems und verwendet stattdessen seine eigene Zwischenablage im Arbeitsspeicher: In einer anderen Anwendung kopierter Text kann nirgendwo in korTTY eingefügt werden, und in korTTY kopierter Text gelangt nie in die Zwischenablage des Betriebssystems – während das Kopieren, Ausschneiden und Einfügen *innerhalb* von korTTY weiterhin überall funktioniert, da Terminals, SFTP-Ansichten, der Snippet-Editor und alle Dialoge denselben internen Puffer verwenden. Da es sich hierbei um reine Anwendungslogik handelt und keine Betriebssystem-Zwischenablage-Isolations-API beteiligt ist, verhält es sich unter macOS, Windows und Linux identisch, einschließlich der X11-Primärauswahl (Einfügen mit mittlerem Klick): Das Einfügen von Inhalten mit mittlerem Klick aus anderen Anwendungen ist blockiert, das Einfügen mit mittlerem Klick von korTTY-internen Kopien funktioniert weiterhin.

Der Modus umfasst das Terminal (Verknüpfungen, Kontextmenü, Mittelklick), den Code-Editor, alle Kopierschaltflächen, die Snippet-Variable `${clipboard}` und die Verknüpfungen zum Kopieren/Ausschneiden/Einfügen einfacher Eingabefelder. Das Kopieren von Bildern (KI-generierte Bilder, Diagrammexporte) ist im internen Modus nicht verfügbar, da ein Bild nur über die Zwischenablage des Betriebssystems geteilt werden kann.

!!! note "Scope"
    Die interne Zwischenablage ist ein Richtlinientool gegen zufällige Datenübertragung über die Zwischenablage und kein fester Luftspalt: Ein Benutzer kann weiterhin Text auf dem Bildschirm lesen. Die Rechtsklick-*Einfüge*-Eingabe von Nur-Text-Feldern wird vom UI-Toolkit bereitgestellt und kann weiterhin auf die Zwischenablage des Betriebssystems zugreifen – die Tastenkombination und jedes von korTTY bereitgestellte Menü werden abgedeckt.

## Verschlüsselte API-Schlüssel

Der API-Schlüssel eines AI-Profils erscheint niemals im Klartext in der Richtlinie. Der Administrator verschlüsselt es einmal von einem Terminal aus – der Befehl gibt einen `kortty-enc:v1:`-Wert für den `api-key-encrypted`-Schlüssel aus:

```bash
korTTY --encrypt-policy-value
```

Benutzern wird im Profil nur „Von Ihrer Organisation bereitgestellter API-Schlüssel“ angezeigt. Der Schlüssel wird im Speicher entschlüsselt, sobald eine Anfrage gestellt wird.

!!! warning "Sicherheitsbereich"
    The envelope uses AES-256-GCM with an application-wide key, so it protects against casual disclosure (shoulder surfing, config diffs, backups) and detects tampering — it is not hard secrecy, since anyone with the korTTY binary could recover the application key. The installation directory's OS permissions remain the actual security boundary; prefer per-user keys via the normal profile flow when that boundary is not enough.

## Fehlerbehebung

| Symptom | Ursache und Abhilfe |
| --- | --- |
| Startdialog „Organisationsrichtlinie konnte nicht geladen werden“ | Die Richtliniendatei weist einen Syntaxfehler oder einen ungültigen Wert auf; Der Dialog und das Protokoll benennen die genaue Position. korTTY bleibt ausfallsicher gesperrt, bis die Datei repariert ist |
| Richtlinie scheint ignoriert zu werden | Die Datei heißt nicht `kortty-policy.toml`, befindet sich nicht im `policy/`-Ordner der Installation oder korTTY wurde nicht neu gestartet. Die Startzeilen des Protokolls geben an, welche Richtliniendatei (falls vorhanden) geladen wurde |
| Eine Regel gilt nicht für einen Benutzer | Der Regelbereich besteht aus Betriebssystem-Anmeldenamen in Kleinbuchstaben. überprüfen `[groups]` Mitgliedschaft und denken Sie daran, dass eine spezifischere Stufe (Benutzer > Gruppe > Jeder) weniger spezifische Regeln außer Kraft setzt |
| Warning "policy file is writable by the current user" | The installation directory permissions are too open — the enforcement model relies on admin-only write access |
| Das Admin-Modell wird nicht angezeigt | Sehen Sie sich das Protokoll an: GGUF-URL-Downloads erfolgen beim Start im Hintergrund und für die Registrierung ist eine installierte llama.cpp-Laufzeit erforderlich. MLX-Quellen müssen lokale Safetensors-Verzeichnisse sein |
