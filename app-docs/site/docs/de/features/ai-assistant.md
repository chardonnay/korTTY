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
4. Geben Sie optional **Modell** und **API-Schlüssel** ein. Die bearbeitbare Modellauswahl unterstützt manuelle Modellnamen; für bekannte Cloud-Anbieter (OpenAI, Anthropic, Google Gemini, Mistral, DeepSeek, Groq, OpenRouter, MiniMax) ist es mit gängigen Modellnamen vorgefüllt und für lokale LM Studio-Endpunkte bietet es eine **Auto**-Option plus die aktuell geladenen lokalen LLMs. Der **API-Schlüssel** wird verschlüsselt mit Ihrem Master-Passwort gespeichert. Bevorzugen Sie lokale Endpunkte für vertrauliche Daten oder überprüfen Sie die Vertrauensstufe des Endpunkts, bevor Sie eine Auswahl senden.
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
4. **API-Details** – Geben Sie den API-Schlüssel ein und wählen Sie den Modellnamen aus oder geben Sie ihn ein – die Modellliste ist vorab mit gängigen Modellen für den ausgewählten Anbieter gefüllt, und **Modelle laden** führt die Live-Modellliste des Endpunkts oben zusammen. Konfigurieren Sie optional den Reasoning-Aufwand (wenn der Anbieter/das Modell erweitertes Denken unterstützt).
5. **Profilname** – Geben Sie einen Anzeigenamen für das Profil ein (z. B. „Claude Opus“, „Local LM Studio“).

Native Anthropic (Claude) API-Unterstützung ist neben vorhandenen OpenAI-kompatiblen Endpunkten enthalten.

## Modellauswahl

Die Modellauswahl unter **Einstellungen > AI** und **Tools > AI Manager > Profile** kann bearbeitet werden:

* Bei bekannten Cloud-Anbietern ist das Dropdown-Menü bereits mit gängigen Modellnamen für den konfigurierten Endpunkt gefüllt, sodass ein konkretes Modell ohne API-Schlüssel ausgewählt werden kann. Die Schaltfläche „Aktualisieren“ neben der Auswahl führt die Live-`/v1/models`-Liste des Endpunkts oben zusammen, wenn der API-Schlüssel gültig ist.
* Ein aufgelistetes Modell speichert dieses Modell als manuelle Auswahl.
* Ein eingegebener Modellname wird als manuelle Auswahl gespeichert, sodass jeder OpenAI-kompatible Endpunkt funktioniert.
* **Auto** wird nur für lokale LM Studio-Endpunkte angeboten, bei denen korTTY das geladene Modell tatsächlich erkennen kann. Cloud-Profile benötigen ein konkretes Modell; Wenn keine Option ausgewählt ist, werden die Anforderungen mit der expliziten Fehlermeldung „Wählen Sie ein bestimmtes KI-Modell aus“ beendet.

### Lokale LM Studio-Modellauswahl

Für lokale LM Studio-Profile kann korTTY aktuell geladene LLM-Modellschlüssel über den `GET /api/v1/models`-Endpunkt von LM Studio ermitteln.

Der automatische Modus löst das effektive Modell unmittelbar vor Verbindungstests, KI-Chat- und Folgeanfragen, Terminal-KI-Aktionen und der Ausführung von Terminal-KI-Agenten auf. Wenn genau ein LLM geladen ist, verwendet korTTY dieses Modell. Wenn mehrere LLMs geladen sind, verwendet korTTY das gespeicherte bevorzugte Modell nur, wenn dieses Modell gerade geladen ist. Wenn kein LLM geladen ist oder mehrere LLMs ohne gültige gespeicherte Präferenz geladen werden, stoppt korTTY die Anfrage mit einem expliziten Fehler, anstatt zu raten.

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
* Erkannte Codeblöcke erhalten einen eigenen Kopier-Button und können auch direkt im Snippet Manager gespeichert werden. Blöcke, die Bilder, Diagramme oder Mathematik enthalten, werden stattdessen als Bilder gerendert – siehe [Gerenderte Bilder, Diagramme und Mathematik](#rendered-images-diagrams-and-math).
* Gerenderte Markdown-Tabellen können als ganze Tabelle, einzelne Spalte oder einzelne Zelle kopiert werden.
* Die ausgewählte AI-Tab-Schriftgröße wird global gespeichert und für zukünftige AI-Ergebnis-Tabs wiederverwendet.
* Die Token-Nutzung wird nach erfolgreichen Anfragen pro KI-Profil aufgezeichnet, sodass Warnungen und Rücksetzzyklen korrekt bleiben.
* Wenn ein gespeicherter Chat auf ein AI-Profil verweist, das nicht mehr existiert, fordert korTTY Sie auf, ein Ersatzprofil auszuwählen, bevor Sie mit den Folgeaufforderungen fortfahren.

### Gerenderte Bilder, Diagramme und Mathematik

KI-Antworten, die Bilder, Diagramme oder mathematische Formeln enthalten, werden im Chat als Bilder gerendert, anstatt rohes Markup anzuzeigen. Dies gilt auch für gespeicherte Chats, die über den AI Manager erneut geöffnet werden.

| Inhalt in der KI-Antwort | Gerendert als |
|--------------------------|-------------|
| ` ```svg ` / ` ```xml ` / ` ```html `-Codeblock (oder Block ohne Tags), der ein `<svg>`-Dokument enthält | Inline-Vektorbild |
| Markdown-Bildlink mit einem `data:image/png;base64,…`-URI im Antworttext oder ein Codeblock, der nur einen solchen Daten-URI enthält | Inline-Rasterbild (PNG, JPEG, GIF, BMP; bis zu 8 MB dekodiert) mit einer Schaltfläche **Bild kopieren** |
| ` ```plantuml ` / ` ```puml `-Codeblock oder ein nicht markierter `@startuml`-Block | Lokal gerendertes PlantUML-Diagramm |
| ` ```mermaid ` Codeblock | Mermaid-Diagramm (gebündelte Bibliothek, kein Netzwerk) |
| ` ```latex ` / ` ```tex ` / ` ```math ` Codeblock oder `$$ … $$` Mathematik im Antworttext | Satzformel (im Lieferumfang von MathJax enthalten, kein Netzwerk) |

Jeder gerenderte Block behält eine Kopfzeile mit der üblichen Kopierschaltfläche und einem Umschalter zwischen **Code anzeigen/Bild anzeigen**, sodass die zugrunde liegende Quelle nur einen Klick entfernt bleibt. Während ein PlantUML/Mermaid/Math-Block noch gerendert wird, bleibt die Quelle sichtbar; Wenn das Rendern fehlschlägt (z. B. ein Mermaid-Syntaxfehler), bleibt der Block in der Quellansicht und die Kopfzeile zeigt den Grund an.

Beispielaufforderungen, die gerenderte Antworten erzeugen:

```text
Draw a simple house as an SVG image.
Create a Mermaid flowchart of a typical login flow.
Create a PlantUML sequence diagram for an SSH handshake.
Explain the Pythagorean theorem and show the formula.
```

Ein Mermaid-Antwortblock wie dieser wird als Flussdiagramm dargestellt:

````text
```mermaid
Diagramm TD;
  Anmelden -> Bestätigen;
  Validieren-->|ok| Sitzung;
  Validieren ->|fehlschlagen| Fehler;
```
````

Und zeigen Sie die Mathematik im Antworttext als gesetzte Formel an:

```text
$$a^2 + b^2 = c^2$$
```

!!! Hinweis „Darstellungsdetails und Anforderungen“
    * Die SVG- und PlantUML-Ausgabe wird mit deaktiviertem JavaScript und entfernten Skripten/Ereignishandlern aus dem Dokument angezeigt.
    * Mermaid läuft mit der Sicherheitsstufe `strict` aus einer lokal gebündelten Bibliothek; LaTeX wird von einem lokal gebündelten MathJax gesetzt. Keiner von beiden benötigt einen Internetzugang.
    * Beim PlantUML-Rendering wird die lokale PlantUML-Toolchain verwendet: `java` und Graphviz `dot` müssen sich auf `PATH` befinden, und das PlantUML-JAR wird bei der ersten Verwendung in den Benutzercache heruntergeladen (dieselben Anforderungen wie bei Snippet-Diagrammen).
    * Vollständige LaTeX-Dokumente (`\documentclass`) bleiben absichtlich Codeblöcke; Es werden nur Formeln gesetzt.
    * Für gerenderte Bilder wird eine weiße Leinwand verwendet, sodass Diagramme und Formeln mit dunklen Strichen bei dunklen Themen lesbar bleiben.

## KI-Manager

Öffnen Sie **Tools > AI Manager** oder drücken Sie ++Strg+Umschalt+Y++ (++Cmd+Umschalt+Y++ unter macOS).

![AI Manager](../assets/screenshots/ai/ai-manager.png)

Der AI Manager verfügt über zwei Arbeitsbereiche:

* **Profile** – KI-Profile erstellen, bearbeiten, testen, speichern und entfernen. Die Profilliste zeigt den aktuellen Kontingent-/Nutzungsstatus für jedes Profil.
* **Gespeicherte Chats** – Öffnen, umbenennen, aktualisieren oder löschen Sie zuvor gespeicherte KI-Konversationen. Gespeicherte [AI Swarm](ai-swarm.md)-Konversationen werden in einem eigenen Abschnitt **Swarm-Chats** angezeigt, einschließlich derjenigen, die durch geplante Swarm-Jobs erstellt wurden.

Verwenden Sie **Einstellungen > AI** für die globalen Standardeinstellungen und Verhaltensänderungen und **AI Manager** für die tägliche Profil-/Chatverwaltung.

## Fragen Sie nach der Anleitung (AI-Dokumentensuche)

Das integrierte Anleitung (**Hilfe > Anleitung**, ++f1++) enthält eine KI-gestützte Suche. Schalten Sie **KI-Suche** in der Symbolleiste des manuellen Fensters ein, um einen Seitenbereich zu öffnen, geben Sie eine Frage in natürlicher Sprache ein – zum Beispiel *„Wie führe ich den KI-Agenten im Terminalfenster aus?“* – und drücken Sie ++enter++.

So funktioniert es:

* korTTY wählt die relevantesten Abschnitte aus dem gebündelten Offline-Anleitung aus (keine Einbettungen, kein externer Suchdienst) und sendet nur diese Auszüge zusammen mit Ihrer Frage an Ihr **Standard-KI-Profil**.
* Die Antwort wird **ausschließlich aus der Anleitunginhalt** generiert und ist in der App-Sprache verfasst. Wenn die Anleitung die Frage nicht beantwortet, sagt der Assistent dies, anstatt zu raten.
* Antworten geben ihre Quellen an: Klicken Sie auf ein Inline-Zitat oder einen Eintrag in der Liste **Quellen**, um in der manuellen Ansicht direkt zur Seite und zum Abschnitt zu gelangen, auf die verwiesen wird.
* Wenn nichts im Anleitung mit der Frage übereinstimmt, antwortet korTTY lokal, ohne den KI-Endpunkt überhaupt zu kontaktieren.

Anforderungen:

* Ein konfiguriertes AI-Profil (siehe [Setup](#setup)); Es wird das Standardprofil verwendet.
* Ein entsperrter Master-Passwort-Tresor, wenn das Profil einen verschlüsselten API-Schlüssel speichert.

!!! Warnung „Datensicherheit“
    Der Fragetext und die ausgewählten manuellen Auszüge werden an den konfigurierten KI-Endpunkt übermittelt. Der Inhalt der Anleitungs selbst ist eine öffentliche Dokumentation, aber Ihre Frage ist Freitext – vermeiden Sie das Einfügen von Geheimnissen oder verwenden Sie einen vertrauenswürdigen lokalen Endpunkt wie **LM Studio**. Bei manuellen Fragen sind die Internetzugriffsmodi immer deaktiviert.

## KI-Agent und KI-Planung

korTTY unterstützt Workflows im Agentenstil für eine aktive Terminalsitzung.

!!! Hinweis „SSH und lokale Shells“
    Die Befehlsausführungs-Engine des Agenten ist hinter einer `AgentCommandRunner`-Abstraktion mit zwei Backends – **SSH** (Exec-Kanal) und **local** (ein neuer lokaler Prozess) von SSH entkoppelt. Der **AI Agent** und **AI Planning** laufen daher sowohl in SSH-Sitzungen als auch in [lokalen Shells](connections.md#local-shell) unter Windows, macOS und Linux: Befehle werden in der Shell der Verbindung ausgeführt (PowerShell über `-EncodedCommand`, `cmd.exe` oder `$SHELL`), die Umgebungsprüfung und die Systemeingabeaufforderung sind plattformbewusst, sodass das Modell native Befehle generiert und der gleiche Genehmigungsablauf gilt. **Einschränkungen der lokalen Shell:** keine `sudo`/Administrator-Erhöhung unter Windows und keine Live-Nachverfolgung des Arbeitsverzeichnisses (der Agent verwendet das Startverzeichnis der Verbindung). Die kopflose KI-Agent-Aktion des JobScheduler bleibt nur SSH.

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

## AI Swarm (Multiserver)

Der [AI Swarm](ai-swarm.md) sendet eine KI-Agentenaufgabe gleichzeitig an viele Server: Jeder ausgewählte Server erhält seinen eigenen Agentenlauf – einschließlich Server **ohne offenes Terminal** – und die Antworten pro Server werden in einer einzigen Vergleichstabelle zusammengefasst. Die Schwarm-Registerkarte fügt einen animierten Statusstreifen pro Agent, erweiterbare Live-Transkripte, Steuerelemente zum Anhalten/Fortsetzen/Neustarten/Stoppen pro Agent und im gesamten Schwarm, eine KI-freie Skriptausführung über den Snippet-Manager sowie eine Schaltfläche **Planen…** hinzu, die die Ausführung in einen unbeaufsichtigten Job [JobScheduler](jobscheduler.md)] umwandelt. Öffnen Sie es mit **AI > AI Swarm...** oder ++Strg+Alt+S++ (Cmd unter macOS); Den vollständigen Funktionsumfang finden Sie auf der Seite [AI Swarm](ai-swarm.md)].

## Workflow-Skript generieren

Nachdem eine fertige Agentenausführung erfolgreich abgeschlossen wurde, verwandelt eine **Workflow**-Schaltfläche die Ausführung in ein einzelnes eigenständiges, reproduzierbares Skript in einer ausgewählten Sprache (Bash, Python, Perl, Ruby, PowerShell, Ansible Playbook, **Windows-CMD** Batch oder **AppleScript**) mit robuster Fehlerbehandlung, detaillierten Kommentaren und einem Header (Skriptname, Ersteller, Datum/Uhrzeit).

Skripterstellung:

* Lädt automatisch passende KI-Fähigkeiten (z. B. eine Sprachqualitätsfähigkeit für die Zielsprache).
* Kann mehrere Sprachvarianten und mehrere Vorschläge als Inline-Tabs erstellen.
* Unterstützt Header-Vorlagen aus der festen, nicht löschbaren Snippet-Kategorie **Script-Header**.
* Enthält optional ein PlantUML-Diagramm für die Skriptlogik. Während das Diagramm erstellt wird, wird ein funktionierender Kreisel angezeigt, sodass klar ist, dass die KI-Verbindung ausgelastet ist.
* Wird im Snippet-Manager mit einem kurzen, automatisch generierten Namen und der korrekten Erweiterung gespeichert (dedupliziert durch den vollständigen Namen einschließlich Erweiterung).
* Kennzeichnen Sie das Snippet zur einfachen Filterung als `workflow`.
* Setzt die Spalte **System** (Betriebssystem) automatisch auf das geprüfte Betriebssystem des Agenten (jede Linux-Distribution wird zu Linux).
* Der Internetzugang wird während der Generierung zwangsweise ausgeschaltet.

**Zielsprachen:** Bash, Python, Perl, Ruby, PowerShell, Ansible, plus **Windows-CMD** (`.cmd`-Batch – `@echo off`-Lead-Zeile, `REM`-Header-Kommentare, `errorlevel`-Prüfungen) und **AppleScript** (`.applescript` – `osascript`-Shebang, `--`-Kommentare, `try`/`on error`-Verarbeitung).

Jeder Editor für generierte Skripte verfügt über die Schaltflächen **A−** / **A+** und unterstützt ++ctrl++ + Mausrad (Cmd unter macOS), um die Schriftgröße zu ändern; Die gewählte Größe wird sitzungsübergreifend gespeichert.

Die Größe des Workflow-Dialogfelds kann geändert werden und seine Größe und Position werden für die zukünftige Verwendung gespeichert.

!!! Tipp „KI-Backend-Fehler deutlicher machen“
    Wenn der KI-Server nicht mehr über genügend Arbeitsspeicher verfügt oder ein Ressourcenlimit erreicht (z. B. LM Studio/MLX „Ressourcenlimit überschritten“, „metal::malloc“), zeigt das Dialogfeld einen kurzen, umsetzbaren Hinweis anstelle des rohen mehrzeiligen Backend-Stack-Trace an; Alle anderen KI-Fehler werden in einer einzigen Zeile zusammengefasst.
