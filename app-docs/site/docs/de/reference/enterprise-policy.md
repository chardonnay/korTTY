---
title: Unternehmenspolitik
---

# Unternehmensrichtlinie

In verwalteten Umgebungen kann ein Administrator korTTY über eine einzige TOML-Datei, `kortty-policy.toml`, die im Installationsverzeichnis abgelegt wird, einschränken oder vorkonfigurieren. Benutzer können es nicht ändern oder umgehen: Die Datei wird nur aus dem vom Administrator beschreibbaren Installationsordner gelesen, gesperrte Einstellungen werden ausgegraut mit dem Hinweis „Von Ihrer Organisation verwaltet“ angezeigt und die manuelle Bearbeitung von `global-settings.xml` wird beim nächsten Laden rückgängig gemacht. In diesem Kapitel wird erläutert, wo sich die Datei befindet, wie Regeln auf Benutzer, Gruppen und Server abzielen und alle verfügbaren Parameter anhand von Beispielen dokumentiert werden.

!!! note
    Ohne `kortty-policy.toml` ändert sich nichts – korTTY verhält sich genauso wie zuvor. Bei der ausgelieferten Vorlage `policy/kortty-policy.toml.example` handelt es sich um eine vollständig kommentierte Vorlage, bei der alles deaktiviert ist.

## Dateispeicherort und Sicherheitsmodell

korTTY lädt die Richtlinie ausschließlich aus dem Ordner `policy/` seines Installationsverzeichnisses – dem Ordner, der auch das Anwendungs-JAR enthält – und niemals aus `~/.kortty/` oder einem anderen vom Benutzer beschreibbaren Speicherort. Eine kopierfertige Vorlage wird als `policy/kortty-policy.toml.example` geliefert; Kopieren Sie es nach `kortty-policy.toml` im selben Ordner und starten Sie korTTY neu (es gibt kein Hot-Reload).

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

Die `[rule.servers]`-Tabelle schränkt ein, zu welchen Servern ein Benutzer eine Verbindung herstellen darf – als Zulassungsliste (`mode = "allow"`: nur aufgelistete Server sind erreichbar) oder als Sperrliste (`mode = "deny"`: aufgelistete Server sind blockiert). Die Einschränkung wird zentral für jeden Verbindungspfad durchgesetzt: gespeicherte Verbindungen, QuickConnect, Sitzungswiederherstellung, SFTP, gemeinsam genutzte Teamwork-Verbindungen, KI-Schwarmziele und geplante Jobs, einschließlich des Jump-Hosts einer Verbindung. Blockierte Verbindungen bleiben im Verbindungsmanager sichtbar, werden jedoch mit einer Sperrmarkierung ausgegraut und bei jedem Verbindungsversuch wird eine eindeutige Richtlinienmeldung angezeigt.

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
| `ai-agent-execution` | Zeichenfolge | `allow`, `confirm`, `read-only` | `confirm` erzwingt die interaktive Genehmigung jedes mutierenden Befehlssatzes und deaktiviert die Option zur automatischen Genehmigung; `read-only` lässt den Agenten planen und chatten, aber niemals Befehle ausführen |

### `[rule.security]`

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `require-master-password` | boolean | `true` | Erzwingt das Master-Passwort-Gate beim Start; Die Einstellung ist gesperrt |
| `enforce-host-key-check` | boolean | `true` | Die Überprüfung des SSH-Hostschlüssels kann nirgendwo deaktiviert werden – global, pro Gruppe oder pro Verbindung |
| `allow-telemetry` | boolean | `false` | Verbietet anonyme Nutzungsstatistiken |
| `allow-terminal-recording` | boolean | `false` | Verbietet die Aufzeichnung von Terminalsitzungen, einschließlich der Umschaltung auf Sitzungsebene |

### `[rule.teamwork]`, `[rule.snippets]`, `[rule.ai-profiles]`

| Schlüssel | Typ | Werte | Wirkung |
| --- | --- | --- | --- |
| `allow-custom-sources` | boolean | `false` | Benutzer können keine Teamwork-Quellen hinzufügen; Es verbleiben nur die Einträge `[[teamwork-source]]` und |
| `allow-custom-script-headers` | boolean | `false` | Benutzer können keine Skript-Header erstellen; Es verbleiben nur die Einträge `[[script-header]]` und |
| `allow-create` | boolean | `false` | Benutzer können keine AI-Profile erstellen (Schaltflächen und Assistent sind gesperrt) |
| `allow-edit` | boolean | `false` | Benutzer können ihre vorhandenen AI-Profile auch nicht bearbeiten |

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

### Von Admin bereitgestellte Objekte

Diese Tabellen der obersten Ebene definieren Objekte, die für jeden Benutzer schreibgeschützt und mit der Kennzeichnung „Von Ihrer Organisation bereitgestellt“ gekennzeichnet sind. Sie werden bei jedem Start aus der Richtlinie neu erstellt und nie in die Konfigurationsdateien des Benutzers geschrieben. Wenn Sie sie aus der Richtlinie entfernen, werden sie auch aus korTTY entfernt.

| Tabelle | Schlüssel | Notizen |
| --- | --- | --- |
| `[[script-header]]` | `name`, `content` | Unveränderlicher Skript-Header in der Script-Header-Kategorie des Snippet-Systems |
| `[[ai-profile]]` | `id`, `name`, `provider`, `endpoint`, `model`, `api-key-encrypted` | `id` muss mit `policy-` beginnen; `provider` ist einer von `anthropic`, `openai-compatible`, `lm-studio`, `embedded-llama`, `embedded-mlx` (eingebettete Anbieter lesen `model` als lokale Modell-ID) |
| `[[ai-runtime.model]]` | `name`, `runtime`, `source` | `runtime` Ist `llama` oder `mlx`; `source` ist ein absoluter lokaler/UNC-Pfad oder für GGUF-Modelle eine http(s)-URL, die korTTY einmal beim Start herunterlädt |
| `[[teamwork-source]]` | `name`, `type`, `url` | `type` Ist `git` oder `shared-file`; als schreibgeschützte Teamwork-Quelle eingefügt |

## Verschlüsselte API-Schlüssel

Der API-Schlüssel eines AI-Profils erscheint niemals im Klartext in der Richtlinie. Der Administrator verschlüsselt es einmal von einem Terminal aus – der Befehl gibt einen `kortty-enc:v1:`-Wert für den `api-key-encrypted`-Schlüssel aus:

```bash
korTTY --encrypt-policy-value
```

Benutzern wird im Profil nur „Von Ihrer Organisation bereitgestellter API-Schlüssel“ angezeigt. Der Schlüssel wird im Speicher entschlüsselt, sobald eine Anfrage gestellt wird.

!!! warning "Sicherheitsbereich"
    Der Umschlag verwendet AES-256-GCM mit einem anwendungsweiten Schlüssel und schützt so vor zufälliger Offenlegung (Schulterzugriff, Konfigurationsunterschiede, Backups) und erkennt Manipulationen – es handelt sich nicht um strenge Geheimhaltung, da jeder mit der korTTY-Binärdatei den Anwendungsschlüssel wiederherstellen könnte. Die Betriebssystemberechtigungen des Installationsverzeichnisses bleiben die eigentliche Sicherheitsgrenze; Bevorzugen Sie benutzerspezifische Schlüssel über den normalen Profilfluss, wenn diese Grenze nicht ausreicht.

## Fehlerbehebung

| Symptom | Ursache und Abhilfe |
| --- | --- |
| Startdialog „Organisationsrichtlinie konnte nicht geladen werden“ | Die Richtliniendatei weist einen Syntaxfehler oder einen ungültigen Wert auf; Der Dialog und das Protokoll benennen die genaue Position. korTTY bleibt ausfallsicher gesperrt, bis die Datei repariert ist |
| Richtlinie scheint ignoriert zu werden | Die Datei heißt nicht `kortty-policy.toml`, befindet sich nicht im `policy/`-Ordner der Installation oder korTTY wurde nicht neu gestartet. Die Startzeilen des Protokolls geben an, welche Richtliniendatei (falls vorhanden) geladen wurde |
| Eine Regel gilt nicht für einen Benutzer | Der Regelbereich besteht aus Betriebssystem-Anmeldenamen in Kleinbuchstaben. überprüfen `[groups]` Mitgliedschaft und denken Sie daran, dass eine spezifischere Stufe (Benutzer > Gruppe > Jeder) weniger spezifische Regeln außer Kraft setzt |
| Warnung „Richtliniendatei kann vom aktuellen Benutzer geschrieben werden“ | Die Berechtigungen für das Installationsverzeichnis sind zu offen – das Durchsetzungsmodell basiert auf ausschließlich Administrator-Schreibzugriff |
| Das Admin-Modell wird nicht angezeigt | Sehen Sie sich das Protokoll an: GGUF-URL-Downloads erfolgen beim Start im Hintergrund und für die Registrierung ist eine installierte llama.cpp-Laufzeit erforderlich. MLX-Quellen müssen lokale Safetensors-Verzeichnisse sein |
