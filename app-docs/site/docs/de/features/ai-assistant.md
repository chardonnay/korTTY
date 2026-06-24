---
title: KI-Assistent
---

# KI-Assistent

KorTTY kann ausgewählten Terminaltext mit einem OpenAI-kompatiblen KI-Endpunkt analysieren und die Antwort in einer temporären KI-Ergebnisregisterkarte öffnen. Sie können auch Workflows im Agentenstil starten, um SSH-Aufgaben zu automatisieren oder Pläne vor der Implementierung überprüfen zu lassen.

![AI request/integration flow](../assets/diagrams/ai-api-integration.svg)

!!! Warnung „Datensicherheit“
Ausgewählter Terminaltext wird zur Analyse an den konfigurierten KI-Endpunkt übertragen. Dieser Text kann vertrauliche Informationen wie Anmeldeinformationen, Hostnamen, Dateipfade, Stack-Traces oder andere Betriebsdetails enthalten. Bevorzugen Sie für vertrauliche Daten einen vertrauenswürdigen lokalen Endpunkt wie **LM Studio** oder vergewissern Sie sich, dass Sie dem Remote-Endpunkt vertrauen, bevor Sie etwas senden. Wenn Sie einen **API-Schlüssel** angeben, speichert korTTY diesen verschlüsselt mit Ihrem Master-Passwort.

## Aufstellen

1. Öffnen Sie **Bearbeiten > Globale Einstellungen**.
2. Gehen Sie zu **AI**.
3. Erstellen Sie ein oder mehrere AI-Profile und geben Sie die **API-URL** für jedes Profil ein, das Sie verwenden möchten. Sie können Profile unter **Einstellungen > AI** oder unter **Tools > AI Manager > Profile** verwalten.
4. Geben Sie optional **Modell** und **API-Schlüssel** ein. Der bearbeitbare Modellselektor unterstützt manuelle Modellnamen und für lokale LM Studio-Endpunkte eine **Auto**-Option sowie die aktuell geladenen lokalen LLMs. Der **API-Schlüssel** wird verschlüsselt mit Ihrem Master-Passwort gespeichert. Bevorzugen Sie lokale Endpunkte für vertrauliche Daten oder überprüfen Sie die Vertrauensstufe des Endpunkts, bevor Sie eine Auswahl senden.
5. Konfigurieren Sie optional **Max. Zeichen**, **Tokenizer**, **Token-Limit**, Warnschwellenwerte, Token-Reset-Zyklus, unterstützten **Begründungsaufwand** und **Internetzugriff** pro Profil. korTTY stellt Argumentationsoptionen basierend auf der konfigurierten API-URL und dem konfigurierten API-Modell bereit; Profile ohne unterstützten Argumentationsmodus bleiben deaktiviert.
6. Klicken Sie auf **AI-Verbindung testen**.
7. Wählen Sie optional ein **Standardprofil** für Terminal-KI-Aktionen und Folgechats, die nicht explizit ein anderes Profil auswählen.
8. Konfigurieren Sie optional die Standardsprache für KI-generierten Text in Codekommentaren und Programmausgaben, aktivieren Sie das Feld für zusätzliche Anweisungen für Snippet-KI-Aktionen und legen Sie fest, wie viele alternative Lösungen der Snippet-Editor anfordern soll.
9. Konfigurieren Sie optional die Größe des Terminal-Agent-Eingabeverlaufs (Standard 20, Bereich 5–100), den Agent-Befehlsnamen, die Befehlsübereinstimmung ohne Berücksichtigung der Groß-/Kleinschreibung, das Ausführungsziel, die Verwendung des Prompt-Hooks, den Einrichtungsdialog pro Ausführung, die Debug-/Laufzeitsichtbarkeit und die Aktivitätsfenstereinstellungen für **AI Agent** und **AI Planning**.
10. Deaktivieren Sie optional den Bestätigungsdialog für **Zusammenfassen** und **Problem lösen**, wenn Sie einen schnelleren Arbeitsablauf wünschen. **Fragen** öffnet immer den Eingabeaufforderungsdialog.

## AI-Profil-Setup-Assistent

Ein geführter Assistent erstellt KI-Profile mit Unterstützung sowohl für lokale als auch für cloudbasierte Sprachmodelle.

So öffnen Sie den Assistenten:

* **Einstellungen > AI**, klicken Sie auf **Profil hinzufügen** oder **Bearbeiten** ein vorhandenes Profil.
* **Tools > AI Manager > Profile**, klicken Sie auf **Hinzufügen**.

Der Assistent führt Sie durch:

1. **Verbindungstyp** – Wählen Sie lokales LM Studio oder einen Cloud-Anbieter.
2. **LM Studio-Setup** – Wenn lokal: Wählen Sie ein geladenes LM Studio-Modell aus der Liste aus oder verwenden Sie den **Auto**-Modus.
3. **Einrichten des Cloud-Anbieters** – Bei Cloud: Wählen Sie den Anbieter aus (Anthropic Claude API, OpenAI oder ein anderer OpenAI-kompatibler Endpunkt).
4. **API-Details** – Geben Sie den API-Schlüssel ein, wählen Sie den Modellnamen aus oder geben Sie ihn ein und konfigurieren Sie optional den Reasoning-Aufwand (wenn der Anbieter/das Modell erweitertes Denken unterstützt).
5. **Profilname** – Geben Sie einen Anzeigenamen für das Profil ein (z. B. „Claude Opus“, „Local LM Studio“).

Native Anthropic (Claude) API-Unterstützung ist neben vorhandenen OpenAI-kompatiblen Endpunkten enthalten.

## Lokale LM Studio-Modellauswahl

Für lokale LM Studio-Profile kann korTTY aktuell geladene LLM-Modellschlüssel über den `GET /api/v1/models`-Endpunkt von LM Studio ermitteln. Die Modellauswahl unter **Einstellungen > AI** und **Tools > AI Manager > Profile** kann bearbeitet werden:

* **Auto** hält das Profil im automatischen Modus.
* Ein aufgelistetes Modell speichert dieses Modell als manuelle Auswahl.
* Ein eingegebener Modellname wird als manuelle Auswahl gespeichert, sodass andere OpenAI-kompatible Endpunkte weiterhin funktionieren.

Der automatische Modus löst das effektive Modell unmittelbar vor Verbindungstests, KI-Chat- und Folgeanfragen, Terminal-KI-Aktionen und der Ausführung des Terminal-KI-Agenten auf. Wenn genau ein LLM geladen ist, verwendet korTTY dieses Modell. Wenn mehrere LLMs geladen sind, verwendet korTTY das gespeicherte bevorzugte Modell nur, wenn dieses Modell gerade geladen ist. Wenn kein LLM geladen ist oder mehrere LLMs ohne gültige gespeicherte Präferenz geladen werden, stoppt korTTY die Anfrage mit einem expliziten Fehler, anstatt zu raten.

## KI-Internetzugang

Der Internetzugang wird pro AI-Profil konfiguriert. Vorhandene und neue Profile sind standardmäßig **Deaktiviert**.

| Modus | Verhalten |
|------|----------|
| **Deaktiviert** | Mit KI-Anfragen werden keine Webtools oder MCP-Integrationen gesendet. |
| **KorTTY Tavily Tool** | korTTY fügt berechtigten OpenAI-kompatiblen `/v1/chat/completions`-Anfragen ein `web_search`-Tool hinzu. Werkzeugaufrufe werden von korTTY über `POST https://api.tavily.com/search` ausgeführt. |
| **LM Studio Tavily MCP** | korTTY sendet eine native `/api/v1/chat`-Anfrage von LM Studio mit einer Tavily MCP-Integration. |
| **Bright Data Web MCP** | korTTY sendet eine native `/api/v1/chat`-Anfrage von LM Studio mit einer Bright Data MCP-Integration. |
| **Brave Search MCP** | korTTY sendet eine native LM Studio-`/api/v1/chat`-Anfrage mit einer konfigurierten Brave Search MCP-Plugin-ID. |
| **SearXNG MCP** | korTTY sendet eine native `/api/v1/chat`-Anfrage von LM Studio mit einer konfigurierten SearXNG MCP-Plugin-ID. |
| **LM Studio Toolpack** | korTTY sendet eine LM Studio native `/api/v1/chat`-Anfrage mit der konfigurierten LM Studio Toolpack MCP-Plugin-ID. |

Die erforderliche Provider-Konfiguration wird unter **Einstellungen > AI > Internet-Tool-Konfiguration** eingetragen. API-Schlüssel und Token werden mit dem Master-Passwort verschlüsselt gespeichert. MCP-Serverbezeichnungen und Plugin-IDs werden als normale Einstellungen gespeichert.

Wichtiges Verhalten:

* Snippet-KI, Textkorrektur, Übersetzung, Snippet-Beschreibungen und Alternativlösungsanfragen nutzen keinen Internetzugang.
* Direct korTTY-Webtools haben ein Verbindungs-Timeout von 5 Sekunden, ein Anfrage-Timeout von 20 Sekunden und maximal zwei Web-Tool-Runden pro KI-Anfrage.
* LM Studio MCP-Anfragen mit Internetzugang nutzen ein längeres Gesamtanfrage-Timeout, da der MCP-Server hinter LM Studio läuft.
* Durch das Abbrechen einer laufenden Anfrage wird die Java-HTTP-Anfrage unterbrochen, sofern der aktive Anbieter eine Unterbrechung unterstützt.
* Werkzeugfehler werden als strukturierte Daten an das Modell zurückgegeben. Wenn das Web-Tool das Zeitlimit überschreitet, die Authentifizierung fehlschlägt, keine Ergebnisse zurückgibt oder das Tool-Runden-Limit erreicht, wird das Modell angewiesen, dies explizit zu sagen und keine Web-Fakten zu erfinden.
* Für die Terminal-Agent-JSON-Planung bietet korTTY Web-Tools nur dann an, wenn die Benutzeraufgabe eindeutig nach aktuellen oder externen Informationen fragt. Lokale Datei-/Skriptüberprüfungsaufgaben sollten über SSH-Befehle wie `sed`, `cat`, `find` oder Testbefehle und nicht über die Websuche erledigt werden.

## KI-Fähigkeiten

KI-Fähigkeiten sind wiederverwendbare lokale Anweisungsblöcke, die korTTY zu KI-Anfragen hinzufügen kann. Verwenden Sie sie für dauerhafte Präferenzen wie Codierungsstandards, Überprüfungsregeln, Betriebsrichtlinien oder sprachspezifische Stilrichtlinien.

Öffnen Sie **Bearbeiten > Globale Einstellungen > KI-Fähigkeiten**.

### Kompetenzfelder

* **Skill-Name** – Für Menschen lesbarer Name, der in der Skill-Liste und den Aktivitätsprotokollen angezeigt wird.
* **Beschreibung** – Kurze Erklärung, die beim automatischen Skill-Matching verwendet wird.
* **Tags** – Durch Kommas getrennte Schlüsselwörter, die beim automatischen Skill-Matching verwendet werden.
* **Ziel** – `AI Chat/Functions`, `AI Agent` oder `Both`.
* **Aktiv** – Aktiviert oder deaktiviert nur diese Fähigkeit.
* **Skill Markdown** – Der Anweisungstext, der an das Modell gesendet wird, wenn die Fertigkeit ausgewählt wird.

### Steuerelemente

* **KI-Fähigkeiten aktivieren** deaktiviert oder aktiviert alle Fertigkeiten global.
* **Automatisch nur passende Fertigkeiten senden** sendet nur aktive Fertigkeiten, die der aktuellen Anfrage entsprechen. Bei Deaktivierung werden alle aktiven Fertigkeiten mit einem passenden Ziel gesendet.
* **Hinzufügen** erstellt einen neuen aktiven Skill mit dem Ziel `Both`.
* **Löschen** entfernt den ausgewählten Skill nach Bestätigung.
* **Import** akzeptiert `.md`- und `.markdown`-Dateien.
* **Exportieren** schreibt Markdown-Dateien für die ausgewählte Fertigkeit oder alle Fertigkeiten, wenn keine ausgewählt ist.
* Die Fertigkeitsliste kann alphabetisch oder nach aktiviertem/deaktiviertem Status sortiert werden.

### Import-/Exportformat

```markdown
---
kortty-ai-skill: 1
name: My Skill
description: Short purpose statement
enabled: true
target: BOTH
tags: [linux, bash]
---

Skill instructions as Markdown.
```

Einfaches Markdown ohne korTTY-Frontmatter wird mit dem Dateinamen als Skill-Name, Ziel `Both`, importiert und standardmäßig deaktiviert, sodass Sie es vor der Verwendung überprüfen können. `SKILL.md`-Frontmatter im Claude/Codex-Stil mit `name`, `description` und `tags` wird ebenfalls akzeptiert und der Import ist standardmäßig deaktiviert, es sei denn, es handelt sich um das eigene Exportformat von korTTY.

Wenn eine AI-Agent-Ausführung einen oder mehrere Skills verwendet, protokolliert das Terminal-Agent-Aktivitätsfenster die ausgewählten Skill-Namen. Verbindungstests senden niemals Fertigkeiten, daher bleiben `Reply with exactly OK`-Tests stabil.

## Terminalauswahl

### KI für ausgewählten Text verwenden

1. Text im Terminal auswählen.
2. Klicken Sie mit der rechten Maustaste auf den ausgewählten Text.
3. Öffnen Sie **AI** und wählen Sie:
* **Zusammenfassen** – Erstellt eine prägnante Zusammenfassung der ausgewählten Ausgabe.
* **Problem lösen** – Analysiert die ausgewählte Fehlerausgabe und schlägt mögliche Korrekturen vor.
* **Fragen** – Sendet die Auswahl zusammen mit Ihrer eigenen Folgefrage oder Anleitung.
4. Bestätigen Sie die Anfrage im Vorschaudialog. Sie können den ausgewählten Text vor dem Senden bearbeiten. Fügen Sie für **Fragen** Ihre eigene Eingabeaufforderung hinzu. Das Dialogfeld zeigt auch die geschätzten Anforderungstoken und das prognostizierte verbleibende Kontingent an.
5. Die Antwort wird in einer temporären AI-Registerkarte geöffnet. Sie können den gleichen Kontext mit Folgeaufforderungen aus dem unteren Verfasserfeld fortsetzen.
6. Verwenden Sie **Speichern** auf der Registerkarte „AI“, um die Konversation unter einem benutzerdefinierten Titel zu speichern.
7. Öffnen Sie gespeicherte Konversationen später erneut über **Extras > AI Manager** oder ++Strg+Umschalt+Y++ (++Cmd+Umschalt+Y++ unter macOS).

### Funktionen der Registerkarte „AI-Ergebnis“.

* Das Gesprächsprotokoll ist schreibgeschützt und nicht im gespeicherten Projekt-/Sitzungsstatus enthalten.
* `<think> ... </think>`-Blöcke werden aus der sichtbaren Ausgabe entfernt.
* Mit der Symbolleiste können Sie die Konversation kopieren, den Chat speichern oder umbenennen, ihn als PDF/Markdown/Nur-Text teilen/exportieren, die letzte Anfrage erneut versuchen, die Registerkarte schließen, laufende Anfragen abbrechen und die Schriftgröße ändern.
* Die Antwortsprache ist standardmäßig die aktuelle GUI-Sprache. Sie können die Antwortsprache und das aktive KI-Profil pro Chat ändern, bevor Sie eine Folgeaufforderung senden.
* Folgeaufforderungen in **Zusammenfassen** und **Problem lösen** werden als normale Chat-Fragen fortgesetzt; Sie werden nicht zur ursprünglichen Eingabeaufforderung für Zusammenfassung/Problemanalyse zurück gezwungen.
* Erkannte Codeblöcke erhalten einen eigenen Kopier-Button und können auch direkt im Snippet Manager gespeichert werden.
* Gerenderte Markdown-Tabellen können als ganze Tabelle, einzelne Spalte oder einzelne Zelle kopiert werden.
* Die ausgewählte AI-Tab-Schriftgröße wird global gespeichert und für zukünftige AI-Ergebnis-Tabs wiederverwendet.
* Die Token-Nutzung wird nach erfolgreichen Anfragen pro KI-Profil aufgezeichnet, sodass Warnungen und Rücksetzzyklen korrekt bleiben.
* Wenn ein gespeicherter Chat auf ein AI-Profil verweist, das nicht mehr existiert, fordert korTTY Sie auf, ein Ersatzprofil auszuwählen, bevor Sie mit den Folgeaufforderungen fortfahren.

## KI-Manager

Öffnen Sie **Tools > AI Manager** oder drücken Sie ++Strg+Umschalt+Y++ (++Cmd+Umschalt+Y++ unter macOS).

![AI Manager](../assets/screenshots/ai/ai-manager.png)

Der AI Manager verfügt über zwei Arbeitsbereiche:

* **Profile** – KI-Profile erstellen, bearbeiten, testen, speichern und entfernen. Die Profilliste zeigt den aktuellen Kontingent-/Nutzungsstatus für jedes Profil.
* **Gespeicherte Chats** – Öffnen, umbenennen, aktualisieren oder löschen Sie zuvor gespeicherte KI-Konversationen.

Verwenden Sie **Einstellungen > AI** für die globalen Standardeinstellungen und Verhaltensänderungen und **AI Manager** für die tägliche Profil-/Chatverwaltung.

## KI-Agent und KI-Planung

korTTY unterstützt Workflows im Agentenstil für eine aktive Terminalsitzung.

### Starten des Agenten

* **AI Agent** – Starten Sie über **Tools > AI Agent...**, über das Kontextmenü des Terminals oder mit dem Terminal-Verknüpfungsbefehl. Der Agent kann je nach **Einstellungen > AI** einen speziellen Chat-Tab öffnen oder auf das aktive Terminalfenster zielen.
* **KI-Planung** – Starten Sie über **Extras > KI-Planung...**, über das Kontextmenü des Terminals oder mit `agent-plan` / `agent -plan`. Im Planungsmodus werden klärende Fragen gestellt, eine oder mehrere Optionen vorgeschlagen, ein endgültiger Planbericht erstellt und Sie können mit dem akzeptierten Plan beginnen.

### Aktivitätsbereich und Platzierung

* **Split-Local-Aktivitätsbereich** – Auf Terminals ausgerichtete Läufe werden am unteren Rand des Terminalsplits dort angezeigt, wo der Lauf gestartet wurde. Jeder Split verfügt über ein eigenes Panel, sodass verschiedene Splits ihre eigenen Agentenaufgaben parallel ausführen können.
* **Panel-Platzierung** – Verwenden Sie **Ansicht > AI-Agent-Panel**, um **Unten** (Standard), **Links andocken** oder **Rechts andocken** auszuwählen. Im angedockten Modus wird die Aktivität in einem in der Größe veränderbaren Seitenbereich angezeigt, der an das Hauptfenster angeschlossen ist. Ziehen Sie den Teiler, um die Größe zu ändern. Die Platzierung und Breite werden bei jedem Neustart gespeichert. Im Seitenmodus gibt es eine äußere Lasche pro Anschluss der aktiven Anschlusslasche und die Läufe werden vertikal gestapelt. Wenn Sie zu einer anderen Terminal-Registerkarte wechseln, wechselt das Dock zu den Terminals dieser Registerkarte.
* **Agentenstatusanzeigen** – Ein KI-Agentenstatussymbol pro Terminal wird in der Dashboard-Struktur angezeigt und dem Registerkartentitel des Terminals vorangestellt:
- ✋ wartet auf Eingabe
- ⚡funktioniert
- ⏸ pausiert
- ✓ fertig
* **Gleichzeitige Ausführungen** – Mehrere gleichzeitige Ausführungen pro Split werden als schließbare Registerkarten im Aktivitätsbereich angezeigt (eine Registerkarte pro Ausführung), mit einer Parallelitätsobergrenze pro Widget von 5 Ausführungen. Abgeschlossene Läufe bleiben als Tabs erhalten, bis sie geschlossen werden.
* **Tippen während des Laufens** – Das Tippen ist nicht mehr gesperrt, während ein Lauf aktiv ist. Sie können mit der Eingabe am Shell-Prompt fortfahren und einen weiteren `agent ...`-Befehl starten (er öffnet eine neue gleichzeitige Registerkarte). Es werden nur Laufsteuerungstasten abgefangen: ++Esc++ oder ++Strg+C++ cancel the selected tab's run; ++Strg+R++ schaltet die Denkdetails dieses Laufs um.
* **Pause und Fortsetzen** – Auf jeder Laufregisterkarte werden Schaltflächen zum Anhalten und Abbrechen angezeigt. Pause parkt den Agenten an einem sicheren Punkt zwischen den Schritten; Die Pausenzeit wird von der Laufarbeitszeit ausgeschlossen.
* **Aktuelles Verzeichnis** – Terminal-Verknüpfungen verwenden das von korTTY verfolgte aktuelle Remote-Verzeichnis. Befehle und generierte Dateien werden relativ zu diesem Verzeichnis ausgeführt.
* **Genehmigungen und Sudo** – Der Agent kann vor der Befehlsausführung eine explizite Genehmigung anfordern und im Aktivitätsbereich nach einem Sudo-Passwort fragen. Die Passworteingabe ist maskiert, kann mit ++Enter++ übermittelt werden und ermöglicht bis zu drei Wiederholungsversuche mit falschem Passwort. Wenn ein Passwort zwischengespeichert wird, wird es nur für den aktuellen Agenten-/Sitzungskontext verwendet. Wenn eine Benutzereingabe (Sudo-Passwort/Befehlsgenehmigung) erforderlich ist, wird das Bedienfeld automatisch erweitert.
* **Reduzierte Statusleiste** – Wenn das Bedienfeld minimiert ist, wird eine kompakte Statusleiste mit der Ausführungsaufforderung, dem Status, den Schaltflächen „Pause/Abbrechen“ und einer Schaltfläche „Erweitern“ angezeigt. Während der Agent aktiv arbeitet, wird ein Kreissymbol angezeigt, und eine fettgedruckte ✋-Markierung signalisiert, wenn eine Benutzereingabe erforderlich ist.
* **Reduziert halten** – Verwenden Sie **Reduziert halten**, um das Bedienfeld minimiert auf die Statusleiste anzuzeigen.
* **Terminalausgabe** – Die endgültigen Antworten des Terminalagenten werden in den Terminalbereich zurückgeschrieben, sodass das Shell-Transkript die Antwort enthält.
* **Panel-Steuerelemente** – Verwenden Sie die Schaltfläche „Neu laden“, um den ausgewählten Agentenbefehl erneut auszuführen. Kopier- oder Snippet-Aktionen sind für Aktivitätszeilen verfügbar. Exportieren Sie den aktuellen Lauf oder den vollständigen Verlauf als Markdown, Nur-Text, YAML, XML, JSON, PDF oder Asciidoctor. Verwenden Sie **Alle erweitern**, um die Aktivitätsdetails offen zu halten. Verwenden Sie **A-** und **A+**, um die Schriftgröße der Aktivität zu ändern. Ziehen Sie den Größenänderungsgriff, um die Panelhöhe zu ändern.

## Verknüpfungsbefehle für Terminal-Agenten

Wenn Terminal-Agent-Verknüpfungen aktiviert sind und sich das Terminal an einem Shell-Prompt befindet, fängt korTTY diese Befehle lokal ab, anstatt sie an den Server zu senden:

```bash
agent <goal>
agent-ask <question>
agent-plan <task>
agent -plan <task>
```

Der Basisbefehlsname kann unter **Einstellungen > AI** konfiguriert werden. Wenn Sie `agent` umbenennen, leitet korTTY automatisch die passenden Befehle `-ask` und `-plan` ab. Auf derselben Einstellungsseite kann die Groß-/Kleinschreibung des Befehlsnamens nicht beachtet werden, und der Setup-Dialog pro Lauf kann deaktiviert werden. Wenn der Dialog deaktiviert ist, verwendet korTTY das konfigurierte Standardprofil.

### TAB-Vervollständigung und Eingabeaufforderungsverlauf

An der Shell-Eingabeaufforderung wird die TAB-Vervollständigung für Agentenbefehle verbessert:

* Geben Sie den Namen des Agentenbefehls ein (z. B. `agent`) und drücken Sie dann ++TAB++, um die Befehlsvarianten (`agent`, `agent-ask`, `agent-plan`) anzubieten.
* Geben Sie den Befehl plus ein Leerzeichen ein (z. B. `agent `) und drücken Sie dann ++TAB++, um den aktuellen Verlauf der Agentenaufforderungen anzuzeigen. In jeder Zeile werden die Eingabeaufforderung und das Datum/die Uhrzeit der letzten Ausführung angezeigt, dedupliziert durch den Eingabeaufforderungstext (neuester zuerst).
* In jeder Verlaufszeile wird links die Eingabeaufforderung und rechts das Datum/die Uhrzeit der letzten Ausführung angezeigt. Eingabeaufforderungen mit mehr als 60 Zeichen werden durch Auslassungspunkte gekürzt. Die vollständige Eingabeaufforderung wird weiterhin eingefügt, wenn der Eintrag ausgewählt wird.
* Die Größe des Verlaufs-Popups lässt sich ändern – ziehen Sie den Griff in der unteren rechten Ecke – und merkt sich seine Größe bei jedem Neustart. Wenn der Verlauf länger als das Popup ist, wird eine vertikale Bildlaufleiste angezeigt.
* Entfernen Sie im Verlaufs-Popup eine einzelne Eingabeaufforderung, indem Sie auf die Schaltfläche ✕ der Zeile klicken (oder auf Tastaturen mit Vorwärts-Löschen-Taste ++Del++ drücken), oder entfernen Sie den gesamten Verlauf mit **Alle löschen** (eine zweistufige Bestätigung schützt vor versehentlichem Löschen). Löschungen werden sofort gespeichert.
* Außerhalb dieses Kontexts ist ++TAB++ eine normale Shell-Vervollständigung.

Die Verlaufsgröße kann unter **Einstellungen > AI** konfiguriert werden (Standard 20, Bereich 5–100).

### So funktioniert der AI Agent

Der Terminal AI Agent ist ein kontrollierter SSH-Automatisierungsworkflow. Es führt keine beliebige Modellausgabe direkt im interaktiven Terminal aus. Stattdessen folgt jede Runde diesem Muster:

1. korTTY prüft die aktive SSH-Sitzung mit einem nicht interaktiven Befehl und zeichnet kompakten Kontext auf, z. B. aktuellen Benutzer, Host, Betriebssystem, aktives Terminal-Arbeitsverzeichnis, Sudo-Verfügbarkeit, Festplattenpfad und aktuellen Befehlsstatus.
2. korTTY sendet die Benutzeraufgabe, den Sonden-Snapshot, frühere Befehlsergebnisse, aktive KI-Fähigkeiten und optional die Web-Tool-Verfügbarkeit an das ausgewählte KI-Profil.
3. Das Modell muss eine strikte JSON-Entscheidung zurückgeben: Befehle ausführen, Bestätigung anfordern, beenden oder blockieren.
4. korTTY validiert das JSON-Schema und die Befehlseinschränkungen. Ungültige Antworten werden einmalig repariert; unsichere oder nicht unterstützte Befehlsentscheidungen werden abgelehnt.
5. korTTY führt genehmigte Befehle über SSH-Exec-Kanäle aus. Jeder Befehl startet im verfolgten aktiven Terminalverzeichnis. Ein `cd` innerhalb eines Befehls bleibt nicht bis zum nächsten Befehl bestehen.
6. Die Befehlsausgabe wird dem Aktivitätsfeld und der nächsten Modellrunde hinzugefügt, bis die Aufgabe abgeschlossen, blockiert, abgebrochen oder das Rundenlimit erreicht ist.

### Passende Aufgaben

Das Mittel ist geeignet für:

* Überprüfung von Dateien, Verzeichnissen, Paketstatus, Protokollen, Dienststatus und Systemkonfiguration;
* Erstellen oder Ändern von Skripten und Konfigurationsdateien, wenn die Aufgabe dies verlangt;
* Ausführen von Tests, Syntaxprüfungen, Linters oder schreibgeschützten Diagnosebefehlen;
* Zusammenfassung der Befehlsausgabe und Erläuterung der Ergebnisse;
* Planung mehrstufiger betrieblicher Änderungen vor der Implementierung.

Das Mittel ist nicht bestimmt für:

* interaktive Vollbildprogramme wie `vim`, `nano`, `top`, `less`, `ssh` oder Befehle, die auf Eingabeaufforderungen warten;
* Daemons oder Befehle mit langer Laufzeit ohne eindeutigen Abschluss;
* geheime Exfiltration oder blinde destruktive Befehle;
* Webrecherche nach lokalen Dateien, es sei denn, Sie fragen ausdrücklich nach externen/aktuellen Informationen.

### Beispielbefehle

```bash
agent show the 10 largest XML files in this directory
agent update groesste_xml.pl so the -r flag searches subdirectories recursively
agent check why nginx failed to start and suggest the safest fix
agent-ask what user and directory am I currently using?
agent-plan migrate this host from package X to package Y
```

Benennen Sie bei lokalen Dateiüberprüfungsaufgaben die Datei in der Aufgabe. Der Agent sollte es dann mit SSH-Befehlen wie `sed -n`, `cat`, `file` oder sprachspezifischen Syntaxprüfungen überprüfen. Wenn ein internetfähiges Profil aktiv ist, hält korTTY Web-Tools weiterhin von diesen lokalen Dateiplanungsaufforderungen fern, es sei denn, Ihre Aufgabe fragt eindeutig nach aktuellen oder externen Informationen.

### Aktivitätszeilensymbole

Aktivitätszeilen verwenden semantische Emoji-Symbole, um den Aktionstyp anzugeben:

| Symbol | Bedeutung |
|------|---------|
| 💾 | Anfrage oder Eingabe: Datei schreiben/erstellen |
| 📖 | Aktion: Datei/Verzeichnis lesen |
| ▶️ | Aktion: Befehl ausführen/ausführen |
| 📁 | Aktion: Verzeichnisoperation |
| 📦 | Kontext: Paketmanager |
| ⚙️ | Kontext: Dienst oder System |
| 🌐 | Aktion: Netzwerkbetrieb |
| 🔍 | Aktion: prüfen/analysieren |
| 💭 | Zustand: Denken/Argumentation |
| 💬 | Ausgabe: Nachricht oder Ergebnis |
| ✋ | Erforderlich: Warten auf Benutzereingabe (sudo/approval) |
| ❌ | Status: Fehler oder fehlgeschlagene Aktivität |
| 🚫 | Status: abgebrochene Aktivität |

Der rote Stil ist für Fehler- und Fehlerzustände reserviert.

### Sicherheit und Fehlerbehandlung

korTTY fügt Leitplanken für die Agentenausführung hinzu:

* Befehle sind pro Runde begrenzt und dürfen nicht interaktiv sein.
* Befehle, die das System ändern oder Berechtigungen erfordern, können je nach Einstellungen und Modellentscheidung durch Bestätigung weitergeleitet werden.
* Sudo verwendet `sudo -n` und Passwortabfragen im Aktivitätsbereich. korTTY erlaubt keine `sudo -S`, `su` oder Befehle, die unbegrenzt auf eine Terminal-Passwortabfrage warten.
* Das aktuelle Remote-Verzeichnis wird anhand von Shell-Hooks, Terminal-Eingabeaufforderungskontext und Prüfergebnissen verfolgt. Wenn ein nachverfolgtes Verzeichnis nicht mehr vorhanden ist, versucht korTTY die Prüfung erneut aus dem SSH-Standardverzeichnis und meldet das Problem.
* Während eine auf das Terminal ausgerichtete Ausführung aktiv ist, ist normales Tippen weiterhin zulässig; Es werden nur die Run-Control-Tasten (++Esc++/++Strg+C++ to cancel the selected run, ++Strg+R++ zum Umschalten der Denkdetails) abgefangen.
* Websuchfehler, HTTP-Fehler, Authentifizierungsfehler, leere Ergebnisse und Zeitüberschreitungen werden als explizite Toolfehler angezeigt.
* Wenn die KI-Antwort nicht mit dem erforderlichen JSON-Schema übereinstimmt, fordert korTTY eine Reparatur an. Schlägt auch die Reparatur fehl, wird der Lauf mit einer Begründung gesperrt.

## Workflow-Skript generieren

Nachdem die Ausführung eines fertigen Agenten erfolgreich abgeschlossen wurde, verwandelt eine **Workflow**-Schaltfläche die Ausführung in ein einzelnes eigenständiges, reproduzierbares Skript in einer ausgewählten Sprache (Bash, Python, Perl, Ruby, PowerShell, Ansible Playbook) mit robuster Fehlerbehandlung, detaillierten Kommentaren und einem Header (Skriptname, Ersteller, Datum/Uhrzeit).

Skripterstellung:

* Lädt automatisch passende KI-Fähigkeiten (z. B. eine Sprachqualitätsfähigkeit für die Zielsprache).
* Kann mehrere Sprachvarianten und mehrere Vorschläge als Inline-Tabs erstellen.
* Unterstützt Header-Vorlagen aus der festen, nicht löschbaren Snippet-Kategorie **Script-Header**.
* Enthält optional ein PlantUML-Diagramm für die Skriptlogik.
* Wird im Snippet-Manager mit einem kurzen, automatisch generierten Namen und der korrekten Erweiterung gespeichert (dedupliziert durch den vollständigen Namen einschließlich Erweiterung).
* Kennzeichnen Sie das Snippet zur einfachen Filterung als `workflow`.
* Setzt die Spalte **System** (Betriebssystem) automatisch auf das geprüfte Betriebssystem des Agenten (jede Linux-Distribution wird zu Linux).
* Der Internetzugang wird während der Generierung zwangsweise ausgeschaltet.

Die Größe des Workflow-Dialogfelds kann geändert werden und seine Größe und Position werden für die zukünftige Verwendung gespeichert.
