---
title: Terminal-KI-Agent und -Tools
---

# Terminal-KI-Agent und -Tools

Der Terminal-KI-Agent von korTTY ist ein kontrollierter Automatisierungsworkflow, der die sichere, intelligente Ausführung von Befehlen auf remote-Servern ermöglicht – und da der Ausführungsengine hinter einer `AgentCommandRunner`-Abschattung (SSH-Exec-Channel und lokalen Prozess-Backends) entkoppelt ist, funktioniert er auch in [lokalen Shells](connections.md#lokale-shell) unter Windows, macOS und Linux. Im Gegensatz zu einer naiven Automatisierung untersucht der Agent den Zustand der Sitzung, überlegt Schritt für Schritt und wartet auf die Genehmigung des Benutzers, bevor systemverändernde Befehle ausgeführt werden.

!!! note "SSH vs. lokale Shells"
    In lokalen Shells verwenden Befehle ein natives lokales Backend (PowerShell über `-EncodedCommand`, `cmd.exe` oder POSIX `/bin/sh`) und der Umgebungstest und die Systemeingabeaufforderung sind plattformorientiert. Der Agent erfasst das aktuelle Verzeichnis der interaktiven Shell, wenn ein Lauf startet, und verwendet denselben Snapshot für seine Sonde und jeden Befehl im Lauf. Im Flatpak-Paket werden diese Prozesse auf dem Host über `flatpak-spawn --host` und nicht innerhalb der Freedesktop-Laufzeit-Sandbox ausgeführt. Einschränkungen der lokalen Shell: keine `sudo`/Administrator-Erhöhung unter Windows. Die kopflose KI-Agent-Aktion des JobScheduler bleibt nur SSH.


![AI agent execution loop](../assets/diagrams/ai-agent-execution-loop.svg)

!!! important "RAG ist explizit für autonomes Arbeiten "
    Wissensspeicher, die normalen Text-/Codierungsanfragen zugewiesen sind, erweitern nicht automatisch den Kontext des Terminal AI Agent, der Planung, des Schwarms oder des geplanten Jobs. Diese autonomen Flüsse erfordern ein explizites RAG-Opt-in; siehe [RAG Wissensspeicher](rag.md). Dadurch wird verhindert, dass ein Hintergrundworkflow indizierte lokale Quellen liest, nur weil dasselbe Modellprofil für den interaktiven Chat verwendet wird.

## Befehlsvarianten

Das Terminal KI-Agent wird über Tastenkombinationen am Shell-Prompt ausgelöst. Wenn er in **KI > KI-Manager > General** aktiviert ist, interceptiert KorTTY diese Befehle lokal statt sie an den Server zu senden.

```bash
agent <goal>
agent-ask <question>
agent-plan <task>
agent -plan <task>
```

Die Basisbefehlsnamen sind in **KI > KI-Manager > Allgemein** konfigurierbar. Wenn Sie `agent` umbenennen, ermittelt KorTTY automatisch die passenden Befehle `-ask` und `-plan`. Die gleiche Einstellungsseite kann:

- Machen Sie beim Befehlsnamen keine Berücksichtigung der Groß-/Kleinschreibung
- Deaktivieren Sie den Einrichtungsdialog pro Lauf (verwendet das konfigurierte Standardprofil, wenn es deaktiviert ist)

!!! note
    korTTY erkennt diese Kürzel im Eingabepfad des Connectors vor der normalen Shellausführung. Tastatureingaben und Clipboard-Einfügungen werden aus demselben Byte-Stream zusammengesetzt, sodass eingefügte Dateinamen und Unicode-Text in die Anfrage einbezogen werden, der komplette Prompt im Agentenverlauf gespeichert wird und ein Druck auf Enter genau einen Agentenlauf erzeugt. Nur das, was Sie tippen oder einfügen, wird erkannt: Text, den der Server ausgibt, z. B. eine Datei, die mit `cat` angezeigt wird und eine präparierte Escape-Sequenz enthält, kann keinen Agentenlauf starten.

### Befehlszwecke

- **`agent <goal>`** – Führen Sie sichere Terminalbefehle aus, um ein Ziel zu erreichen. Der Agent prüft die Sitzung, plant nicht interaktive Befehle, fordert bei Bedarf eine Genehmigung an und schreibt (standardmäßig) die endgültige Antwort zurück an das Terminal. Das Ausführungsdialogfeld bietet die Option **Endgültige Antwort auch im Terminal anzeigen**: Lassen Sie diese Option aktiviert, um die Antwort im Terminal zu spiegeln, oder schalten Sie sie aus, damit die Antwort nur im Aktivitätsfenster des KI-Agenten verbleibt. In beiden Fällen werden die Befehle weiterhin ausgeführt und ihre Ausgabe sowie alle von ihnen erstellten Dateien verbleiben auf dem Server. Lediglich die Anzeige des endgültigen Antworttextes ändert sich. Die letzte Auswahl wird als Standard gespeichert.
- **`agent-ask <question>`** — Erhalten Sie eine nicht ausführende Antwort zum aktuellen Sitzungsinhalt, ohne Befehle auszuführen. Wird das Menü im Terminal per Rechtsklick gestartet (**KI → Fragen Agent...**) und Text ausgewählt, wird die Auswahl als Kontext mitgesendet, sodass die Frage zum ausgewählten Output oder Skript beantwortet wird.
- **`agent-plan <task>` / `agent -plan <task>`** – Wechseln Sie zuerst in den Planungsmodus. Der Agent stellt klärende Fragen, schlägt Vorgehensweisen vor, erstellt einen endgültigen Plan und führt die Implementierung erst durch, nachdem Sie auf **Umsetzen** geklickt haben.

### Beispiele

```bash
agent show the 10 largest XML files in this directory
agent update groesste_xml.pl so the -r flag searches subdirectories recursively
agent check why nginx failed to start and suggest the safest fix
agent-ask what user and directory am I currently using?
agent-plan migrate this host from package X to package Y
```

## TAB Abschluss- und Eingabeaufforderungsverlauf

An der Shell-Eingabeaufforderung wird die TAB-Vervollständigung für Agentenbefehle verbessert:

- Geben Sie den Namen des Agentenbefehls ein (z. B. `agent`) und drücken Sie dann ++tab++, um Befehlsvarianten anzuzeigen (`agent`, `agent-ask`, `agent-plan`).
- Geben Sie den Befehl + ein Leerzeichen ein (z. B. `agent `) und drücken Sie dann ++tab++, um den aktuellen Verlauf der Agentenaufforderungen anzuzeigen. In jeder Zeile werden die Eingabeaufforderung und das Datum/die Uhrzeit der letzten Ausführung angezeigt, dedupliziert durch den Eingabeaufforderungstext (neuester zuerst).
- Eingabeaufforderungen mit mehr als 60 Zeichen werden zur besseren Lesbarkeit mit Auslassungspunkten gekürzt. Bei Auswahl wird weiterhin die vollständige Eingabeaufforderung eingefügt.
- Die Größe des Verlaufs-Popup kann geändert werden – ziehen Sie den Griff in der unteren rechten Ecke – und merkt sich seine Größe bei jedem Neustart. Es zeigt eine vertikale Bildlaufleiste an, wenn der Verlauf die Popup-Höhe überschreitet.
- Klicken Sie im Verlaufs-Popup auf die Schaltfläche ✕ einer Zeile (oder drücken Sie ++del++ auf Tastaturen mit Vorwärts-Löschen-Taste), um eine einzelne Eingabeaufforderung zu entfernen, oder verwenden Sie **Alle löschen** (Bestätigung in zwei Schritten), um den gesamten Verlauf zu löschen. Löschungen werden sofort gespeichert.
- Außerhalb dieses Kontexts bleibt ++tab++ die normale Shell-Vervollständigung.

Verlaufsgröße ist in **KI > KI-Manager > Allgemein** konfigurierbar (Standard 20, Bereich 5–100).

## So funktioniert der AI Agent

Das Terminal AI Agent folgt einer strengen, sicheren Ausführungsschleife:

1. **Sitzung prüfen** – KorTTY prüft die aktive Terminalsitzung mit einem nicht interaktiven Befehl und zeichnet kompakten Kontext auf: aktueller Benutzer, Host, Betriebssystem, Arbeitsverzeichnis des aktiven Terminals, Sudo-Verfügbarkeit, Festplattenpfad und aktueller Befehlsstatus.
2. **Kontext an das Modell senden** – KorTTY sendet die Benutzeraufgabe, den Sonden-Snapshot, frühere Befehlsergebnisse, aktive KI-Skills und optional die Web-Tool-Verfügbarkeit an das ausgewählte KI-Profil.
3. **Modell gibt eine JSON-Entscheidung zurück** – Das Modell muss eine strikte JSON-Antwort zurückgeben: Befehle ausführen, um Bestätigung bitten, beenden oder blockieren.
4. **Entscheidung validieren** – KorTTY validiert das JSON-Schema und die Befehlseinschränkungen. Ungültige Antworten werden einmalig repariert; unsichere oder nicht unterstützte Entscheidungen werden abgelehnt.
5. **Genehmigte Befehle ausführen** – korTTY verwendet SSH-Exec-Kanäle für reguläre SSH-Befehle und ein privates Pseudoterminal für sudo-Befehle des Laufs. Lokale Befehle verwenden jeweils einen neuen Prozess. Jeder Befehl beginnt im erfassten Terminalverzeichnis. Ein `cd` innerhalb eines Agentenbefehls gilt nur für diesen Befehl.
6. **Iterieren oder abschließen** – Die Befehlsausgabe wird dem Aktivitätsbereich und der nächsten Modellrunde hinzugefügt, bis die Aufgabe abgeschlossen, blockiert, abgebrochen oder das Rundenlimit erreicht ist (maximal 8 Runden).

### Was der Agent aufrufen kann

Das Modell beantwortet jede Runde mit einer Entscheidung, und korTTY führt ausschließlich die hier aufgeführten Entscheidungen und Werkzeuge aus:

- **`run_commands`** — Bis zu 3 nicht-interaktive Shell-Befehle für das aktive Backend. Systemverändernde Befehle gehen durch die Genehmigungsstelle, wie unter [Genehmigungsschalter](#genehmigungstore) beschrieben.
- **`needs_confirmation`** — Sie werden aufgefordert, ein Befehlsset vor dem Ausführen zu bestätigen.
- **`done`** oder **`blocked`** — Beendet die Ausführung mit der endgültigen Antwort, oder mit einer Erklärung, warum die Aufgabe nicht fortgesetzt werden kann.
- **`web_search` and `web_extract`** — Nur mit dem **KorTTY Tavily Tool** Internetmodus (siehe [Internetzugriff](#internetzugang)). Die Suche liefert fünf kurze Ergebnisse; das Seitenlesen gibt den Text einer Webseite zurück, bis zu 12.000 Zeichen.

### Passende Aufgaben

- Untersuchung von Dateien, Verzeichnissen, Paketstatus, Protokollen, Dienststatus und Systemkonfiguration
- Erstellen oder Ändern von Skripten und Konfigurationsdateien, wenn die Aufgabe dies erfordert
- Ausführen von Tests, Syntaxprüfungen, Linters oder schreibgeschützten Diagnosebefehlen
- Zusammenfassung der Befehlsausgabe und Erläuterung der Ergebnisse
- Planung mehrstufiger betrieblicher Änderungen vor der Implementierung

### Nicht geeignet für

- Interaktive Vollbildprogramme wie `vim`, `nano`, `top`, `less`, `ssh` oder Befehle, die auf Eingabeaufforderungen warten
- Lange laufende Daemons oder Befehle ohne eindeutigen Abschluss
- Geheime Exfiltration oder blinde destruktive Befehle
- Webrecherche nach lokalen Dateien, es sei denn, der Benutzer fragt ausdrücklich nach externen/aktuellen Informationen

!!! tip
    Benennen Sie bei lokalen Dateiüberprüfungsaufgaben die Datei in Ihrer Eingabeaufforderung. Der Agent sollte es dann mit Shell-Befehlen wie `sed -n`, `cat`, `file` oder sprachspezifischen Syntaxprüfungen überprüfen. Wenn ein internetfähiges Profil aktiv ist, hält KorTTY weiterhin Web-Tools von der lokalen Dateiplanung fern, es sei denn, Ihre Aufgabe verlangt eindeutig nach aktuellen oder externen Informationen.

## Aktivitätspanel

Wie Terminal-orientierte Agent-Ausführungen angezeigt werden, wird unter [*Plugins → KI-Agent Design*](ai-agent-designs.md) gewählt: standardmäßig als Block im Terminal mit einem Metro-Linienbalken darunter und pro Verbindung auf dem **KI**-Tab des Verbindungseditors. Die **Klassisch**-Kombination zeigt sie im hier beschriebenen Inline-Aktivitäts-Panel am unteren Rand der aktuellen Terminal-Aufteilung.

### Panel-Funktionen

- **Laufregisterkarten** – Mehrere gleichzeitige Ausführungen werden als schließbare Registerkarten angezeigt. Klicken Sie auf eine Registerkarte, um sie auszuwählen. Nur der Lauf der ausgewählten Registerkarte wird durch Laufsteuerungstasten und -schaltflächen gesteuert. Bis zu 5 gleichzeitige Läufe pro Split.
- **Steuerelemente** – Jeder Lauf verfügt über Schaltflächen zum Neuladen, Anhalten/Fortsetzen, Abbrechen und Kopier-/Snippet-Aktionen pro Zeile. Mit der Schaltfläche „Neu laden“ wird der Befehl mit dem **aktuell aktiven** KI-Profil erneut ausgeführt, sodass der Profilwechsel zwischen den Ausführungen bei der Wiederholung wirksam wird.
- **Details** – Das Panel zeigt die Benutzeraufforderung in einem zweizeiligen scrollbaren Feld, Agentenmeldungen, Lese-/Ausführungsaktionen, Aufgabenzeitpunkt, gemeldete Token-Nutzung, semantische Aktivitätsmarkierungen und ausblendbare Details.
- **KI-Profilzeile** – Jedes Laufprotokoll beginnt mit einem `AI profile: <name> (<model>)`-Eintrag, sodass das Protokoll aufzeichnet, welches Profil und welches Modell den Lauf erzeugt hat.
- **Modellbegründung** – Durch Erweitern einer 💭-Denkzeile wird die vollständige Begründung des Modells angezeigt, wenn der Anbieter sie verfügbar macht (Anthropisches erweitertes Denken, wenn der Begründungsaufwand des Profils aktiviert ist, OpenAI-kompatibles `reasoning_content`, LM Studio-Begründungsausgabe oder `<think>`-Blöcke von lokalen CLI-Modellen). Modelle ohne offengelegte Begründung behalten die kurze Entscheidungszusammenfassung bei.
- **Statusleiste** – Wenn das Bedienfeld minimiert ist, wird eine kompakte Statusleiste mit der Ausführungsaufforderung, dem Status, den Schaltflächen „Pause/Abbrechen“ und der Schaltfläche „Erweitern“ angezeigt. Während der Agent arbeitet, wird ein Spinner angezeigt. Eine fettgedruckte ✋-Markierung signalisiert, wenn eine Benutzereingabe erforderlich ist.
- **Reduziert bleiben** – Verwenden Sie **Reduziert halten**, um das Bedienfeld minimiert zu starten und minimiert zu halten, wenn neue Aktivitäten oder Eingabeaufforderungen eintreffen. Sie können weiterhin manuell erweitern.
- **Größenänderung** – Ziehen Sie den Größenänderungsgriff, um die Panelhöhe zu ändern. Aktivieren Sie **Größe merken**, um Höhe und Schriftgröße bei Anwendungsneustarts beizubehalten.
- **Parallele Splits** – Verschiedene Splits können ihre eigenen Agentenaufgaben parallel ausführen; Jeder Split verfügt über ein eigenes Aktivitätsfenster.
- **Schriftartsteuerung** – Verwenden Sie **A−** und **A+**, um die Schriftgröße der Aktivität zu ändern.
- **Exportieren** – Speichern Sie den aktuellen Lauf oder alle Panel-Verlaufsläufe als Markdown, Nur-Text, YAML, XML, JSON, PDF oder Asciidoctor. Zu den Exporten gehören das KI-Profil, das Modell/LLM, der Begründungsstatus, die Ausführungszeitstempel, die Gesamtlaufzeit, die Laufzeiten pro Aktivität, die Token-Nutzung und Detailtext.
- **Alle erweitern** – Aktivitätsdetails offen halten, anstatt sie zu komprimieren. Diese Option wird global gespeichert.

### Panel-Platzierung

Verwenden Sie **Ansicht → AI Agent Panel**, um auszuwählen, wo das Aktivitätsfenster angezeigt wird:

- **At Bottom** (Standard) – Der Aktivitätsbereich wird unterhalb des Terminalsplits angezeigt, an dem der Lauf gestartet wurde.
- **Links andocken / Rechts andocken** – Das Bedienfeld erscheint als größenveränderbares Seitenfeld, das am Hauptfenster angedockt ist (wie der Dateibrowser). Im Seitenmodus gibt es eine äußere Lasche pro Anschluss der aktiven Lasche, wobei die Läufe vertikal gestapelt sind. Durch Ziehen der Trennlinie wird die Größe des Docks geändert. Platzierung und Breite werden bei jedem Neustart gespeichert. Wenn Sie zu einer anderen Terminal-Registerkarte wechseln, wechselt das Dock zu den Terminals dieser Registerkarte.
- **In Spotlight fragen …** — Öffnet ein Feld in der Mitte des fokussierten Terminals, das den Agenten mit einem wählbaren Profil abfragt; siehe [Schwebende Anzeigen](ai-agent-designs.md#schwebende-anzeigen).
- **Statusleiste ein- oder ausblenden** — Blendet die [Statusanzeige](ai-agent-designs.md#weitere-statusanzeigen) des fokussierten Terminals nach dem Ausblenden wieder ein oder blendet sie aus.
- **Design...** — Öffnet [*Plugins → KI-Agent Design*](ai-agent-designs.md).

### Statusanzeigen

Im Dashboard wird ein KI-Agent-Statussymbol für jedes Terminal angezeigt und dem Titel der Terminal-Registerkarte vorangestellt, über die Ausführungen dieses Terminals hinweg aggregiert und etwa einmal pro Sekunde aktualisiert:

- **✋** – Warten auf Benutzereingaben (Genehmigung oder Sudo-Passwort)
- **⚡** – Funktioniert (Agent plant aktiv Befehle oder führt sie aus)
- **⏸** – Angehalten (Lauf wird an einem sicheren Kontrollpunkt geparkt)
- ** ✓** – Beendet (Lauf erfolgreich abgeschlossen)

### Aktivitätssymbole

Aktivitätszeilen verwenden semantische Emoji-Symbole (statisch, nicht blinkend):

| Symbol | Bedeutung |
|------|---------|
| 💾 | Aktion: eine Datei schreiben oder erstellen |
| 📖 | Aktion: Datei/Verzeichnis lesen |
| ▶️ | Aktion: Befehl ausführen/ausführen |
| 📁 | Aktion: Verzeichnisoperation |
| 📦 | Kontext: Paketmanager |
| ⚙️ | Kontext: Dienst oder System |
| 🌐 | Aktion: Netzwerkbetrieb |
| 🔍 | Aktion: prüfen/analysieren |
| 💭 | Zustand: Denken/Argumentieren |
| 💬 | Ausgabe: Meldung oder Ergebnis |
| ✋ | Erforderlich: Warten auf Benutzereingabe (Sudo/Genehmigung) |
| ❌ | Status: Fehler oder fehlgeschlagene Aktivität |
| 🚫 | Status: abgebrochene Aktivität |

Der rote Stil ist für Fehler- und Fehlerzustände reserviert.

## Sicherheit und Fehlerbehandlung

KorTTY erzwingt mehrere Leitplanken rund um die Agentenausführung:

### Verbindungen ohne Agent

Für eine Verbindung, bei der der Agent niemals Befehle ausführen darf, etwa ein Produktionssystem, aktivieren Sie **KI-Agent für diese Verbindung deaktivieren** auf dem **KI**-Tab des [Verbindungs-Editors](connections.md#verbindung-erstellenbearbeiten). korTTY verweigert daraufhin jede Agent-Ausführung auf dieser Verbindung mit einer Nachricht, die sie benennt: aus dem Menü, dem Terminal-Kontextmenü (das den Agent-Eintrag auslässt), dem `agent ...` Tastenkürzel, einem akzeptierten Plan, einem [KI-Schwarm](ai-swarm.md)-Mitglied und einem geplanten **KI-Agent**-Job. **Agent fragen**, **KI-Planung** und die anderen KI-Funktionen, wie Erklärungen und KI-Befehlsvervollständigung, bleiben verfügbar. Der Schalter wird aus der gespeicherten Verbindung gelesen, gilt daher auch für bereits geöffnete Tabs und bleibt erhalten, wenn die Verbindung dupliziert, exportiert oder importiert wird.

### Befehlsgrenzen

- **Limit pro Runde** – Maximal 3 Befehle pro Runde verhindern eine außer Kontrolle geratene Automatisierung.
- **Nur nicht interaktiv** – Der Agent lehnt interaktive Befehle (wie `vim`, `less`, `su`) ab, die in einem nicht interaktiven Befehls-Backend hängen bleiben würden.
- **Mutierende-Befehls-Erkennung** — Ein Befehl, der mit einem von `chmod`, `chown`, `chgrp`, `rm`, `rmdir`, `mv`, `cp`, `mkdir`, `touch`, `ln`, `tee`, `dd`, `truncate` oder `install` (nach `;`, `&`, `|` oder `(` auch) beginnt, wird als systemverändernd behandelt und zur Bestätigung markiert, es sei denn, der Agent hat eine automatische Genehmigungsumgehung.

### Privilegienbehandlung

- **SSH-sudo-Ticket** – Sobald Sie den ersten sudo-Befehl genehmigen, öffnet korTTY ein privates SSH-Pseudoterminal für den Lauf und validiert dort sudo. Spätere sudo-Befehle verwenden dasselbe Pseudoterminal und das sudo-Ticket des Servers. korTTY schließt das Pseudoterminal am Ende des Laufs oder beim Abbruch.
- **Passwortabfrage** – Wenn die Validierung ein Passwort verlangt, öffnet korTTY ein maskiertes Feld im Aktivitäts-Panel. Die anfängliche Validierung erhält das Passwort. Bei SSH-Läufen im Pseudoterminal verwendet korTTY das Ticket des Servers erneut und behält das Passwort nicht zur Wiederverwendung.
- **Serverrichtlinie** – Jeder Befehl unterliegt den sudo-Regeln des Servers. sudo kann einen Befehl ablehnen, wenn das Ticket abgelaufen ist, das Zwischenspeichern deaktiviert ist oder der Aufruf mit `-k` die zwischengespeicherten Anmeldedaten umgeht. Starten Sie einen neuen Lauf für ein neues Ticket oder verwenden Sie das interaktive Terminal, wenn der Server eine Authentifizierung für jeden Befehl verlangt.
- **Lokale Shells** – Lokale POSIX-Läufe validieren sudo für jeden Befehl separat. Ein direkter sudo-Befehl kann Eingaben aus einem Here-Dokument lesen. Je nach sudo-Richtlinie des Servers oder lokalen Systems müssen Pipelines und zusammengesetzte Befehle auf mehrere Schritte verteilt werden.

### Genehmigungstore

- **Privilegierte oder systemverändernde Befehle** – Standardmäßig fragt der Agent nach einer Genehmigung, bevor er Befehle ausführt, die sudo verwenden oder das System verändern.
- **Bestätigungsdialog** – Der Genehmigungsdialog (**KI-Agent-Bestätigung**) zeigt die geplanten Befehle und deren Zweck an. Wählen Sie **Einmal erlauben** für die angezeigten Befehle oder **Immer erlauben** für die verbleibenden Befehle im Durchlauf. korTTY kann weiterhin nach einem sudo-Passwort fragen.

### Verzeichnisverfolgung

- **Aktives Verzeichnis** – Terminalverknüpfungen verwenden das von KorTTY verfolgte aktuelle Verzeichnis sowohl für SSH als auch für lokale Shells. Bei einer lokalen Ausführung aktualisiert korTTY das Shell-Prozessverzeichnis unter macOS/Linux, akzeptiert einen vorhandenen absoluten Pfad von einer nativen PowerShell/cmd-Eingabeaufforderung und verwendet dann das letzte vertrauenswürdige Verzeichnis oder das Startverzeichnis der Verbindung nur dann, wenn kein Befehl zum Ändern des Verzeichnisses diesen Fallback veraltet gemacht hat.
- **Snapshot der stabilen Ausführung** – Das Verzeichnis wird außerhalb des JavaFX-Threads erfasst, wenn die Ausführung beginnt. Der Umgebungstest und alle Befehle in dieser Ausführung verwenden denselben Snapshot, auch wenn die interaktive Shell später das Verzeichnis ändert.
- **Unbekanntes oder fremdes Verzeichnis** – Nach `cd`, `pushd`, `popd` oder `Set-Location` ist ein unbekanntes Verzeichnis eher ein Fehler als ein stiller Fallback. WSL, Git Bash, Cygwin und benutzerdefinierte Befehle bleiben unter Windows am besten geeignet. Wenn ihr Pfad-Namespace nicht dem lokalen Dateisystem zugeordnet werden kann, stoppt korTTY die Aktion, anstatt auf eine gleichnamige Datei an einer anderen Stelle abzuzielen.
- **SSH-Verzeichnisverlust** – Wenn ein verfolgtes Remote-Verzeichnis nicht mehr vorhanden ist, versucht korTTY die Prüfung erneut vom SSH-Standardverzeichnis aus und meldet das Problem.

### Eingabe während der Ausführung

- **Nicht gesperrt** – Während eine auf das Terminal ausgerichtete Ausführung aktiv ist, ist die normale Eingabe weiterhin zulässig. Sie können weiterhin die Shell-Eingabeaufforderung eingeben und neue `agent`-Befehle starten (sie öffnen neue Registerkarten).
- **Nur Laufkontrollschlüssel** – Es werden nur Laufkontrollschlüssel abgefangen:
  - ++esc++ oder ++ctrl+c++ – Brechen Sie die Registerkarte des ausgewählten Laufs ab
  - ++ctrl+r++ – Schaltet die Denkdetails des ausgewählten Laufs um

### Web-Tools

- **Explizite Fehler** – Websuchfehler, HTTP-Fehler, Authentifizierungsfehler, leere Ergebnisse und Zeitüberschreitungen werden als explizite Toolfehler angezeigt und nicht als Modell, aus dem Fakten bestehen.

### JSON-Schemareparatur

- **Ein Reparaturversuch** – Wenn die KI-Antwort nicht mit dem erforderlichen JSON-Schema übereinstimmt, fordert KorTTY eine Reparatur an. Schlägt auch die Reparatur fehl, wird der Lauf mit einer Begründung gesperrt.

### Maskieren dessen, was der Agent sendet

- **Maskierung vor dem Senden** – Bevor der Agent eine Anfrage an ein Profil außerhalb der integrierten Modelle (llama.cpp, MLX) oder eines vertrauenswürdigen lokalen Endpunkts sendet, maskiert korTTY erkannte Geheimnisse wie das Verbindungspasswort und gängige Tokenformate. Dabei wendet korTTY auch die Ersetzungsregeln Ihrer Organisation an. Die Maskierung umfasst die Umgebungsprüfung, Befehlsausgaben, Planungsfragen, Optionen und Berichte, Korrekturversuche und die abschließende Antwort bei Erreichen des Schrittlimits. Siehe [Maskierung von Geheimnissen vor dem Senden](ai-assistant.md#geheimnisse-vor-dem-senden-maskieren).
- **Der Lauf behält das Original** — Maskiert wird nur, was an das Modell geht. Das Terminal, das Aktivitätspanel, der Genehmigungsdialog und das Sitzungsjournal zeigen die echten Befehle und ihre echte Ausgabe.
- **Zähler im Aktivitätspanel** — Ein Eintrag **Vor dem Senden maskiert** gibt an, wie viele verschiedene Werte im Lauf maskiert wurden. Ein Wert, der in jeder Runde wiederkommt, weil der Agent seinen Befehlsverlauf erneut sendet, zählt einmal.
- **Keine Befehle mit dem Platzhalter** — Das Modell erfährt, dass `***` für ein maskiertes Geheimnis steht. Plant es trotzdem einen Befehl, der `***` enthält, führt korTTY ihn nicht aus, vermerkt dies im Transkript und teilt es dem Modell in der nächsten Runde mit, weil der Befehl sonst mit den drei wörtlichen Zeichen statt des Geheimnisses liefe.

## Lösungsoptionen

Bevor der Agent eine Anfrage ausführt, kann er anzeigen, wie fortzufahren ist. **Komplexität einschätzen** in **KI > KI-Manager > Allgemein** legt fest, wann dies geschieht. **Automatisch** (Standard) führt vor jeder Ausführung einen kleinen KI-Aufruf mit dem Speicher der Ausführung durch und bietet Optionen nur an, wenn die Anfrage komplex erscheint; **Immer** bietet Optionen für jede Ausführung an; **Nie** führt die Anfrage so aus, wie sie ist. Falls die Prüfung fehlschlägt, keine verwertbare Antwort liefert oder länger als 120 Sekunden dauert, wird die Anfrage wie gewünscht ausgeführt. Swarm-Ausführungen und geplante Jobs bieten niemals Optionen an.

Die Karte nennt die Gründe, warum die Anfrage komplex erscheint, sofern dies der Fall ist, sowie zwei oder drei Optionen, wobei die empfohlene zuerst steht. Jede Option zeigt in ihren Details Durchführbarkeit, Risiken, Voraussetzungen, Schritte und Alternativen.

| Steuerung | Wirkung |
| --- | --- |
| Eine Option | Wählt den Ansatz aus, den **Mit Auswahl fortfahren** ausführt |
| **Eigene Idee** | Ersetzt die Optionen durch Ihren eigenen Ansatz oder Ihre eigene Anweisung. Ist sie ausgewählt, ist Ihr Text der Plan; andernfalls wird er zur gewählten Option hinzugefügt |
| **Dauerhafte Regel** | Eine optionale Regel, die korTTY für spätere Anfragen aufbewahrt. **Speichern in** wählt **Globales Memory**, **Diese Verbindung**, das Skill, um das es geht, oder ein neues Skill |
| **Mit Auswahl fortfahren** | Führt die Anfrage mit der gewählten Option oder Ihrer eigenen Idee aus. Nur diese Schaltfläche speichert eine dauerhafte Regel |
| **Wie angefragt ausführen** | Führt die Anfrage so aus, wie sie geschrieben ist, ohne Optionen |
| **Abbrechen** | Bricht die Ausführung ab |

Eine Regel, die korTTY nicht speichern kann, z. B. eine, die ein mögliches Secret enthält, lässt die Karte mit dem Grund geöffnet, und der Lauf startet nicht. Wenn innerhalb von 10 Minuten nichts ausgewählt wird, wird die Anfrage wie gewünscht ausgeführt. Die Karte erscheint im klassischen Agent-Panel, im Lauf-Tab des Chat-Fensters und in der Leiste eines Agent-Designs.

## Gedächtnis in Agent-Läufen

Jeder Agentlauf und jeder Planungsschritt sendet den korTTY-Speicher: den globalen Speicher und, für eine Verbindung, den Speicher dieser Verbindung – budgetiert und maskiert wie der restliche Prompt. Die Zeile **Mitgesendet** im Panel des Laufs listet die Speichereinträge, Skills und Referenzdateien, die gesendet wurden, mit ihren Token-Zahlen. Im Prompt-Dialog des Agents speichert **Als Regel merken…** eine Regel, die Sie schreiben, ohne KI-Aufruf. Siehe [KI-Memory](ai-memory.md).

## Einen Lauf im KI-Chat fortsetzen

Ein abgeschlossener Terminal-Lauf kann in einen KI-Chat übernommen werden: die Leiste unter dem Terminal und das Lauf-Menü bieten **Im KI-Chat fortsetzen**, und bei jedem Design außer dem klassischen Panel bietet das Kontextmenü des Terminals diese Option unter *KI → Letzter Agent-Lauf*. Der Chat behält die Aufgabe, die finale Antwort und die Schritte des Laufs bei, maskiert wie alles andere, das korTTY sendet, und Sie können darin Nachfragen stellen. Siehe [Einen Lauf in einem KI-Chat fortsetzen](ai-agent-designs.md#im-ki-chat-fortsetzen) und [Chats aus KI-Agent-Läufen](ai-assistant.md#chats-aus-ki-agentenlaufen).

## Workflow-Skript generieren

Nachdem eine fertige Agentenausführung erfolgreich abgeschlossen wurde, konvertiert eine **Workflow**-Schaltfläche die Ausführung in ein einzelnes eigenständiges, reproduzierbares Skript in einer ausgewählten Sprache (Bash, Python, Perl, Ruby, PowerShell, Ansible Playbook, **Windows-CMD**-Batch oder **AppleScript**) mit robuster Fehlerbehandlung, detaillierten Kommentaren und einem deterministischen Metadaten-Header (Skriptname, Ersteller, Datum/Uhrzeit).

Für umfassende Aufgaben im gesamten Fleet wird [KI-Swarm](ai-swarm.md#generieren-sie-einen-multi-server-workflow) Der Tab verfügt über ein eigenes Dialogfeld **Multi-Server-Workflow erzeugen**, das zusätzliche Funktionen für Host-Listen und Optionen zur Multi-Server-Härtung bereitstellt, den generierten Skriptcode mit Syntaxhervorhebung und einem Live-Zähler für die verstrichene Zeit anzeigt, eine Historie zusätzlicher Anweisungen speichert und die Datei in Snippets mit vorab ausgefülltem Namen speichert.

### Funktionen zur Skriptgenerierung

- **Passende KI-Skills automatisch laden** – Skills wie Sprachqualitätsrichtlinien für die Zielsprache werden automatisch einbezogen.
- **Härtungsoptionen** – Eine zusammenklappbare Gruppe von Techniken in Produktionsqualität (strenger Modus, Fehlerfallen, aussagekräftige Exit-Codes, Protokollierung, Idempotenz, Probelauf, `--help` und mehr), die in das generierte Skript integriert werden. Alle sind standardmäßig aktiviert. Deaktivieren Sie alle, die Sie nicht möchten. Unter [Härtungsoptionen](../reference/hardening-options.md) erfahren Sie, was die einzelnen Optionen bedeuten.
- **Eingabe-Härtung** – Ein zweites zusammenklappbares Panel, das die KI auffordert, einen Schutzblock für die Eingabevalidierung in das generierte Skript einzubauen: Parameter-Zulassungslisten und Längenbeschränkungen, Dateiformatprüfungen, eine maximale Eingabedateigröße, die durch die einstellbare Skriptvariable `MAX_FILE_SIZE` gesteuert wird, Sicherheitswarnungen im Protokoll des Skripts und eine `FORCE=1`/`--force`-Überschreibung. Der Wächter prüft Dateimetadaten, bevor er Inhalte liest, und `0` bedeutet unbegrenzt. Streng opt-in – das Master-Kontrollkästchen ist zu Beginn deaktiviert. Siehe [Eingabe-Härtung](../reference/input-hardening.md).
- **Acht Zielsprachen** – Bash, Python, Perl, Ruby, PowerShell, Ansible, plus **Windows-CMD** (`.cmd`-Batch – `@echo off`, `REM`-Header, `errorlevel`-Prüfungen) und **AppleScript** (`.applescript` – `osascript`-Shebang, `--`-Kommentare, `try`/`on error`).
- **Mehrere Sprachvarianten** – Generieren Sie mehrere Sprachvarianten und Vorschläge als Inline-Registerkarten im Workflow-Dialogfeld.
- **Anpassbare Schriftgröße** – Jeder Editor für generierte Skripte verfügt über **A−** / **A+**-Tasten und unterstützt ++ctrl++ + Mausrad (Cmd unter macOS); Die gewählte Größe wird sitzungsübergreifend gespeichert.
- **Header-Vorlagen** – Verwenden Sie wiederverwendbare Header aus der festen, nicht löschbaren Snippet-Kategorie **Script-Header**.
- **Mermaid-Diagramm** – Fügen Sie optional ein Mermaid-Flussdiagramm hinzu, das die Skriptlogik darstellt. Seine dedizierte Nur-Quellen-Anfrage lässt KI-Skills und Wissensspeicher-Auszüge weg und setzt den anforderungsbezogenen **Reasoning**-Wert nur dann auf `none`, wenn der Anbieter diesen Wert angekündigt hat; Das gespeicherte Profil bleibt unverändert. Während das Diagramm erstellt wird, wird ein funktionierender Spinner angezeigt.
- **Snippet-Manager** – Speichern Sie das generierte Skript im Snippet-Manager mit einem kurzen, automatisch generierten Namen und der richtigen Dateierweiterung. Skripte werden nach vollständigem Namen einschließlich Erweiterung dedupliziert.
- **Workflow-Tagging** – Das Snippet ist zur einfachen Filterung mit dem Tag `workflow` versehen.
- **OS-Erkennung** – Die Spalte **System** (OS) wird automatisch vom untersuchten Betriebssystem des Agenten festgelegt (jede Linux-Distribution → Linux).
- **Kein Internetzugang** – Der Internetzugang wird während der Generierung erzwungen, unabhängig vom Internetmodus des Profils ausgeschaltet zu werden.
- **Skalierbares Dialogfeld** – Das Workflow-Dialogfeld merkt sich seine Größe und Position für die zukünftige Verwendung.

## Terminalspezifisches Verhalten

### Aktuelles Arbeitsverzeichnis

Der Agent verfolgt das aktuelle Verzeichnis für die aktive SSH- oder Local-Shell-Sitzung. Lokale Ausführungen frieren das am besten vertrauenswürdige Verzeichnis einmal beim Start der Ausführung ein und verwenden es für die Probe und jeden Befehl, sodass generierte Dateien dort erstellt werden, wo die interaktive Shell zu Beginn der Ausführung ausgeführt wurde.

### Eingabeanforderungen

Wenn eine Benutzereingabe erforderlich ist (Sudo-Passwort oder Befehlsgenehmigung):

- **Automatisch erweitern** – Das Aktivitätsfenster wird automatisch erweitert und wählt den Lauf aus, der Eingaben erfordert.
- **Maskierte Eingabe** – Die Sudo-Passworteingabe ist im Aktivitätsbereich maskiert.
- **Senden** – Drücken Sie ++enter++ oder klicken Sie auf die Schaltfläche „Senden“, um das Passwort zu senden.
- **Bis zu 3 Wiederholungen** – Falsche Passwörter können bis zu 3 Mal wiederholt werden, bevor der Lauf abgebrochen wird.
- **Fettgedrucktes Abzeichen** – Auf der Registerkarte „Ausführen“ wird während des Wartens ein fettgedrucktes ✋-Symbol „Eingabe erforderlich“ angezeigt.

### Terminalausgang

Die endgültigen Antworten des Terminalagenten werden in den Terminalbereich zurückgeschrieben, sodass das Shell-Transkript die Antwort und nicht nur den Status des Aktivitätsbereichs enthält.

### Transkripte

Spezielle Agenten- und Planungsregisterkarten können ihr Transkript zur späteren Überprüfung oder Weitergabe kopieren und speichern.

## Befehle ausführen

So starten Sie eine Agentenaufgabe:

1. **Geben Sie an einer Shell-Eingabeaufforderung** in einem aktiven SSH- oder lokalen Shell-Terminal Ihren Agentenbefehl ein:
   ```bash
   agent show the 10 largest files in this directory
   ```

2. **Der Agent prüft die Sitzung** – Er erfasst sofort den aktuellen Benutzer, das Betriebssystem, das Arbeitsverzeichnis und den Sudo-Status.

3. **Im Planungsmodus** stellt der Agent klärende Fragen und schlägt Vorgehensweisen vor, bevor Sie Folgendes genehmigen:
   - Beantworten Sie die Fragen oder schlagen Sie Ihren eigenen Ansatz vor
   - Überprüfen Sie den endgültigen Plan
   - Klicken Sie auf **Implementieren**, um die Ausführung zu starten

4. **Das Aktivitätsfenster** erscheint am unteren Rand der Aufteilung und zeigt Folgendes:
   - Die Benutzeraufforderung
   - Jede getroffene Entscheidung (welche Befehle ausgeführt werden sollen)
   - Echtzeit-Befehlsausgabe während der Ausführung
   - Alle Genehmigungs- oder Passwortanfragen

5. **Genehmigungen** – Wenn der Agent darum bittet, Befehle auszuführen, die sudo verwenden oder das System ändern, wählen Sie **Einmal erlauben**, **Immer erlauben** oder **Abbrechen**.

6. **Sudo-Passwort** – Geben Sie auf Aufforderung Ihr sudo-Passwort ein. Ein SSH-Agentenlauf validiert sudo in seinem privaten Pseudoterminal und verwendet das daraus resultierende sudo-Ticket für spätere Befehle.

7. **Die Ausführung wird abgeschlossen**, wenn die Aufgabe erfolgreich ist, durch einen Fehler blockiert oder vom Benutzer abgebrochen wird.

8. **Nach Abschluss** erscheint eine Schaltfläche **Workflow**, wenn Sie die erfolgreiche Ausführung in ein wiederverwendbares Skript umwandeln möchten.

## Tastaturkürzel

| Verknüpfung | Aktion |
|----------|--------|
| ++tab++ auf Befehl des Agenten | Zeigen `agent`, `agent-ask`, `agent-plan` Varianten |
| ++tab++ nach `agent ` | Aktuellen Verlauf der Agent-Eingabeaufforderungen anzeigen |
| ++esc++ oder ++ctrl+c++ während des Laufs | Brechen Sie die Registerkarte des ausgewählten Laufs ab |
| ++ctrl+r++ während des Laufs | Schalten Sie die Denkdetails für den ausgewählten Lauf um |
| Aktivitätsfeld ⏸ Schaltfläche | Pausieren Sie den ausgewählten Lauf an einem sicheren Kontrollpunkt |
| Aktivitätsfeld ▶️ Schaltfläche | Fortsetzen eines angehaltenen Laufs |

## Planungsmodus

Der Planungsmodus (`agent-plan` oder `agent -plan`) bietet vor der Ausführung einen Beratungsworkflow:

1. **Klärende Fragen** – Der Agent stellt Folgefragen, um Ihre Anforderungen zu verstehen.
2. **Optionen vorschlagen** – Basierend auf Ihren Antworten schlägt der Agent einen oder mehrere Ansätze mit Machbarkeit, Risiken und Voraussetzungen vor.
3. **Endgültiger Plan** – Nachdem Sie einen Ansatz ausgewählt haben, erstellt der Agent einen detaillierten endgültigen Plan mit Schritten, Erfolgskriterien und Risiken.
4. **Umsetzen** – Überprüfen Sie den Plan und klicken Sie auf **Umsetzen**, um mit der Ausführung zu beginnen. Der Agent fordert ein sudo-Passwort an, wenn ein Befehl dies erfordert.

## Einstellungen und Konfiguration

Konfigurieren Sie den Terminal KI-Agenten in **KI > KI-Manager > Allgemein**:

| Einstellung | Wirkung |
|---------|--------|
| **KI-Agent-Ausführung aktivieren** | Aktivieren oder deaktivieren Sie ausführbare Agentenläufe und die Umsetzung von Plänen; KI-Planung und Agent fragen bleiben verfügbar |
| **Agent-Befehlsname** | Der Basisbefehl (Standard: `agent`); andere Varianten werden automatisch abgeleitet |
| **Agent-Befehlsnamen ohne Groß-/Kleinschreibung vergleichen** | Befehlsabgleich ohne Berücksichtigung der Groß-/Kleinschreibung |
| **Ziel für KI-Agent-Aufgaben** | **Terminalfenster** (das Aktivitätspanel im Terminalsplit) oder **Neues Chatfenster** |
| **Terminal-Agent-Abfrage vor jedem Start anzeigen** | Zeigen Sie vor jedem Start einen Setup-Dialog an (bei deaktiviert wird das Standardprofil verwendet) |
| **Komplexität einschätzen** | **Automatisch** (Standard), **Immer** oder **Nie**: wenn der Agent [Lösungsoptionen](#losungsoptionen) |
| **Größe der Terminal-Agent-Eingabehistorie** | Anzahl der zuletzt gespeicherten Prompts zu merken (5–100, Standard 20) |
| **Standardprofil** | Das KI-Profil, das verwendet wird, wenn der Setup-Dialog deaktiviert ist |

Die Platzierung des Panels wird unter **Ansicht → KI-Agent-Panel** festgelegt, und **Eingeklappt lassen** ist eine Option des Aktivitätspanels selbst (Siehe [Panelplatzierung](#panel-platzierung) und [Panel-Funktionen](#panel-funktionen)).

**Welches Profil ein Lauf verwendet** folgt der spezifischsten Auswahl. Das im Setup- oder Plan-Dialog des Laufs gewählte Profil oder das im Agentenbefehl benannte Profil hat Vorrang. Danach folgen das eigene KI-Profil der Verbindung, die Rolle **Profil für den KI-Agenten** unter **KI > KI-Manager > Lokale KI > Profil-Rollen**, das Coding-Profil und schließlich das Standardprofil. Eine leere Rolle **Profil für den KI-Agenten** bedeutet das bisherige Verhalten: das Coding-Profil, dann das Standardprofil. [Agent fragen…](ai-assistant.md#ai-agent-und-ki-planung) verwendet dieselbe Reihenfolge. Siehe [Text-, Coding-, Übersetzungs- und Embedding-Rollen](local-models.md#text-codierungs-ubersetzungs-und-einbettungsrollen).

## KI-Skills

AI Skills sind wiederverwendbare lokale Anweisungsblöcke, die der Agent verwenden kann. Für Agentenausführungen können Sie Fertigkeiten global aktivieren oder deaktivieren oder KorTTY automatisch nur relevante Fertigkeiten der Aufgabe zuordnen lassen. Die Fähigkeiten können Folgendes umfassen:

- Betriebsrichtlinien und -standards
- Best Practices für die Sicherheit
- Sprachspezifische Codierungsrichtlinien
- Systemverwaltungskonventionen

Siehe den **KI-Skills**-Abschnitt in der Hauptdokumentation zur KI für Details zur Einrichtung und Verwaltung. Die für ein Skill bestätigten Regeln werden mit diesem Skill gesendet; siehe [KI-Memory](ai-memory.md).

## Internetzugang

Der Agent kann optional Web-Tools verwenden, wenn für die Aufgabe eindeutig aktuelle oder externe Informationen erforderlich sind:

- **Deaktiviert** (Standard) – Es werden keine Web-Tools oder MCP-Integrationen mit Agentenanfragen gesendet.
- **KorTTY Tavily Tool** — Direkte Websuche und Seitenlesung über die Tavily-API (`web_search` und `web_extract`, Siehe [Was der Agent aufrufen kann](#was-der-agent-aufrufen-kann)).
- **LM Studio MCP-Modi** – Integrieren Sie Tavily, Bright Data, Brave Search, SearXNG oder LM Studio Toolpack über die native MCP-Unterstützung von LM Studio.

Konfigurieren Sie den Internetzugang pro KI-Profil mit dem Feld **Internetzugang** im Profil-Editor; der Tavily-Schlüssel und die anderen Web-Tool-Einstellungen werden unter **KI > KI-Manager > Allgemein > Internetzugang** eingegeben.

!!! warning
    **Web-Tools werden von lokalen Datei-/Skriptüberprüfungsaufgaben ausgeschlossen**, es sei denn, in Ihrer Eingabeaufforderung werden eindeutig aktuelle oder externe Informationen abgefragt. Für die Untersuchung einer lokalen Datei sollten Shell-Befehle wie `sed`, `cat`, `find` oder sprachspezifische Tools und keine Websuche verwendet werden.
