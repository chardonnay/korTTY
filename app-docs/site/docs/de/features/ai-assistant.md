---
title: KI-Assistent
---

# AI-Assistent

KorTTY kann ausgewählten Terminaltext mit einem OpenAI-kompatiblen KI-Endpunkt, einem integrierten lokalen llama.cpp-Modell oder einer konfigurierten lokalen CLI analysieren und die Antwort in einer temporären KI-Ergebnisregisterkarte öffnen. Sie können auch Workflows im Agentenstil starten, um SSH-Aufgaben zu automatisieren oder Pläne vor der Implementierung überprüfen zu lassen.

Wenn **Konfiguration > System-Ruhezustand verhindern** unter macOS oder Windows aktiviert ist, hält korTTY den Computer wach, während eine AI-API-, lokale Modell-, Web-Tool- oder lokale AI-CLI-Anfrage auf ein Ergebnis wartet. Die Behauptung wird freigegeben, nachdem die letzte gleichzeitige AI-Anfrage abgeschlossen ist. Wenn kein Terminal angeschlossen ist und kein zukünftiger oder laufender Scheduler-Job vorhanden ist, kann der Computer normal schlafen. Der Display-Ruhezustand bleibt verfügbar.

![AI request/integration flow](../assets/diagrams/ai-api-integration.svg)

!!! warning "Datensicherheit"
    Ausgewählter Terminaltext wird zur Analyse an den konfigurierten KI-Dienst übermittelt. Dieser Text kann vertrauliche Informationen wie Anmeldeinformationen, Hostnamen, Dateipfade, Stack-Traces oder andere Betriebsdetails enthalten. Verwenden Sie für sensible Daten ein [integriertes lokales GGUF-Modell ](local-models.md) oder einen anderen Endpunkt, dem Sie vertrauen. Die eingebettete Inferenz verwendet einen authentifizierten Nur-Loopback-Server. Remote-Anbieter erhalten die überprüfte Anfrage über das Netzwerk. Wenn Sie einen **API-Schlüssel** angeben, speichert korTTY diesen verschlüsselt mit Ihrem Master-Passwort.

## Setup

1. Öffnen Sie **Bearbeiten > Globale Einstellungen**.
2. Gehe zu **AI**.
3. Erstellen Sie ein oder mehrere AI-Profile unter **Einstellungen > AI** oder **AI > AI Manager > Profile**. Wählen Sie **HTTP API**, **Local CLI** oder **Integrated llama.cpp** als Verbindungsmodus.
4. Geben Sie für HTTP-Profile eine API-URL, ein Modell und optional einen verschlüsselten API-Schlüssel ein. Wählen Sie für ein eingebettetes Profil ein installiertes GGUF-Modell aus. korTTY stellt den privaten Endpunkt und den temporären Schlüssel bereit. Verwenden Sie zuerst **AI Manager > Lokale Modelle**, wenn kein GGUF installiert ist.
5. Konfigurieren Sie optional **Prompt-Optimierung**, **Max. Zeichen**, **Tokenizer**, **Token-Limit**, Warnschwellenwerte, Token-Reset-Zyklus, unterstützten **Begründungsaufwand** und **Internetzugriff** pro Profil. korTTY stellt Argumentationsoptionen basierend auf dem konfigurierten Endpunkt und Modell bereit; Profile ohne unterstützten Argumentationsmodus bleiben deaktiviert.
6. Klicken Sie auf **AI-Verbindung testen**.
7. Wählen Sie optional ein **Standardprofil** und weisen Sie dann unter **AI-Manager > Lokale KI** separate Text-/Übersetzungs- und Codierungsrollen zu. Eine leere Rolle verwendet das Standardprofil.
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
3. **Cloud-Anbieter-Setup** – Bei Cloud: Wählen Sie den Anbieter aus (Anthropic Claude API, OpenAI oder ein anderer OpenAI-kompatibler Endpunkt).
4. **API-Details** – Geben Sie den API-Schlüssel ein und wählen Sie den Modellnamen aus oder geben Sie ihn ein – die Modellliste ist vorab mit gängigen Modellen für den ausgewählten Anbieter gefüllt, und **Modelle laden** führt die Live-Modellliste des Endpunkts oben zusammen. Konfigurieren Sie optional den Reasoning-Aufwand (wenn der Anbieter/das Modell erweitertes Denken unterstützt).
5. **Profilname** – Geben Sie einen Anzeigenamen für das Profil ein (z. B. „Claude Opus“, „Local LM Studio“).

Native Anthropic (Claude) API-Unterstützung ist neben vorhandenen OpenAI-kompatiblen Endpunkten enthalten.

Für eine vollständig integrierte GGUF-Inferenz verwenden Sie den separaten Assistenten **AI Manager > Lokale Modelle > Einrichtungsassistent**. Es überprüft die Hardware, empfiehlt Text-/Codierungs-/Einbettungsmodelle, überprüft die Lizenz und die genaue Größe, verifiziert den unveränderlichen Download, erstellt ein eingebettetes Profil, weist die ausgewählte Rolle zu und führt einen echten lokalen Chat oder einen Einbettungsfunktionstest durch. Siehe [Lokale Modelle mit llama.cpp](local-models.md).

## Modellauswahl

Die Modellauswahl unter **Einstellungen > AI** und **Tools > AI Manager > Profile** kann bearbeitet werden:

* Bei bekannten Cloud-Anbietern ist das Dropdown-Menü mit allgemeinen Modellnamen für den konfigurierten Endpunkt vorgefüllt, sodass ein konkretes Modell ohne API-Schlüssel ausgewählt werden kann. Die Schaltfläche „Aktualisieren“ neben der Auswahl führt die Live-`/v1/models`-Liste des Endpunkts oben zusammen, wenn der API-Schlüssel gültig ist.
Das in * A aufgeführte Modell speichert dieses Modell als manuelle Auswahl.
Der eingegebene Modellname * A wird als manuelle Auswahl gespeichert, sodass jeder OpenAI-kompatible Endpunkt funktioniert.
* **Auto** wird nur für lokale LM Studio-Endpunkte angeboten, bei denen korTTY das geladene Modell tatsächlich erkennen kann. Cloud-Profile benötigen ein konkretes Modell; Wenn keine Option ausgewählt ist, werden die Anforderungen mit der expliziten Fehlermeldung „Wählen Sie ein bestimmtes KI-Modell aus“ beendet.
* Ein **Integriertes llama.cpp**-Profil listet installierte Modell-IDs von `~/.kortty/llm/models.xml` auf; Es verwendet weder die HTTP-URL noch das manuelle Cloud-Modell-Feld.

## Rollenrouting, RAG und prompte Reihenfolge

korTTY klassifiziert seine eigenen Aktionstypen deterministisch, ohne ein Modell zu fragen. Übersetzungen, Zusammenfassungen, Problemlösungen, Fragen, Prosabeschreibungen und KI-ASCII-Artbilder verwenden die Textrolle. Codierung, Vervollständigung, Snippet-Überprüfung, Sicherheitskorrekturen, Diagramme und Workflow-Generierung verwenden die Coding-Rolle. Eine explizite Profilauswahl, ein Sicherheitsüberprüfungsprofil oder ein verbindungsspezifisches Profil hat Vorrang, gefolgt vom Rollenprofil und dann dem Standardprofil.

Wenn passende Wissensspeicher der Text- oder Codierungsrolle der Anfrage zugewiesen werden, wird eine gewöhnliche KI-Anfrage in dieser Reihenfolge zusammengestellt: Aktions-/Ausgabevertrag von korTTY, ausgewählte KI-Fähigkeiten, begrenzter nicht vertrauenswürdiger RAG-Kontext mit `[R1]`-Quellmarkierungen, die aufgelöste Modellfamilienvoreinstellung und schließlich der Anbietertransport. Es werden nur abgerufene Auszüge hinzugefügt, nicht der komplette Wissensspeicher; Durch die Auswahl eines Cloud-Profils werden diese Auszüge an diesen Anbieter gesendet, sodass die Wissensspeicher-Rollen-/Profilzuweisung die explizite Offenlegungsentscheidung ist. Strenge JSON- und Code-Payload-Regeln bleiben maßgebend. Autonomous Agent, Planning, Swarm und geplante Eingabeaufforderungen erfordern eine explizite RAG-Anmeldung. Einzelheiten zum Abruf und Datenschutz finden Sie unter [RAG Wissensspeicher](rag.md).

### Lokale LM Studio-Modellauswahl

Für lokale LM Studio-Profile kann korTTY aktuell geladene LLM-Modellschlüssel über den `GET /api/v1/models`-Endpunkt von LM Studio ermitteln.

Der automatische Modus löst das effektive Modell unmittelbar vor Verbindungstests, KI-Chat- und Folgeanfragen, Terminal-KI-Aktionen und der Ausführung des Terminal-KI-Agenten auf. Wenn genau ein LLM geladen ist, verwendet korTTY dieses Modell. Wenn mehrere LLMs geladen sind, verwendet korTTY das gespeicherte bevorzugte Modell nur, wenn dieses Modell gerade geladen ist. Wenn kein LLM geladen ist oder mehrere LLMs ohne gültige gespeicherte Präferenz geladen werden, stoppt korTTY die Anfrage mit einem expliziten Fehler, anstatt zu raten.

## AI Internetzugang

Der Internetzugang wird pro AI-Profil konfiguriert. Vorhandene und neue Profile sind standardmäßig **Deaktiviert**.

| Modus | Verhalten |
|------|----------|
| **Deaktiviert** | Es werden keine Web-Tools oder MCP-Integrationen mit AI-Anfragen gesendet. |
| **KorTTY Tavily Tool** | korTTY fügt berechtigten OpenAI-kompatiblen `/v1/chat/completions`-Anfragen ein `web_search`-Tool hinzu. Werkzeugaufrufe werden von korTTY über `POST https://api.tavily.com/search` ausgeführt. |
| **LM Studio Tavily MCP** | korTTY sendet eine LM Studio native `/api/v1/chat`-Anfrage mit einer Tavily MCP-Integration. |
| **Bright Data Web MCP** | korTTY sendet eine native LM Studio `/api/v1/chat`-Anfrage mit einer Bright Data MCP-Integration. |
| **Brave Search MCP** | korTTY sendet eine LM Studio native `/api/v1/chat`-Anfrage mit einer konfigurierten Brave Search MCP-Plugin-ID. |
| **SearXNG MCP** | korTTY sendet eine native LM Studio `/api/v1/chat`-Anfrage mit einer konfigurierten SearXNG MCP-Plugin-ID. |
| **LM Studio Toolpack** | korTTY sendet eine LM Studio native `/api/v1/chat`-Anfrage mit der konfigurierten LM Studio Toolpack MCP-Plugin-ID. |

Die erforderliche Provider-Konfiguration wird unter **Einstellungen > AI > Internet-Tool-Konfiguration** eingetragen. API-Schlüssel und Token werden mit dem Master-Passwort verschlüsselt gespeichert. MCP-Serverbezeichnungen und Plugin-IDs werden als normale Einstellungen gespeichert.

Wichtiges Verhalten:

* Snippet AI, Textkorrektur, Übersetzung, Snippet-Beschreibungen, alternative Lösungsanfragen und AI ASCII-Art-Generierung nutzen keinen Internetzugang.
* Direct korTTY-Webtools haben ein Verbindungs-Timeout von 5 Sekunden, ein Anfrage-Timeout von 20 Sekunden und maximal zwei Web-Tool-Runden pro KI-Anfrage.
* LM Studio MCP-Anfragen mit Internetzugang nutzen ein längeres Gesamtanfrage-Timeout, da der MCP-Server hinter LM Studio läuft.
* Das Abbrechen einer laufenden Anfrage unterbricht die Java-HTTP-Anfrage, wenn der aktive Anbieter eine Unterbrechung unterstützt.
* Tool-Fehler werden als strukturierte Daten an das Modell zurückgegeben. Wenn das Web-Tool das Zeitlimit überschreitet, die Authentifizierung fehlschlägt, keine Ergebnisse zurückgibt oder das Tool-Runden-Limit erreicht, wird das Modell angewiesen, dies explizit zu sagen und keine Web-Fakten zu erfinden.
* Für die Terminal-Agent-JSON-Planung bietet korTTY Web-Tools nur dann an, wenn die Benutzeraufgabe eindeutig nach aktuellen oder externen Informationen fragt. Lokale Datei-/Skriptüberprüfungsaufgaben sollten durch Shell-Befehle wie `sed`, `cat`, `find` oder Testbefehle und nicht durch die Websuche erledigt werden.

## AI-Fähigkeiten

KI-Fähigkeiten sind wiederverwendbare lokale Anweisungsblöcke, die korTTY zu KI-Anfragen hinzufügen kann. Verwenden Sie sie für dauerhafte Präferenzen wie Codierungsstandards, Überprüfungsregeln, Betriebsrichtlinien oder sprachspezifische Stilrichtlinien.

Öffnen Sie **KI > KI-Manager > KI-Fähigkeiten**. Die Bibliothek wurde aus dem globalen Einstellungsdialog hierher verschoben. **Speichern** schreibt es sofort und ausstehende Änderungen werden auch gespeichert, wenn der AI Manager geschlossen wird.

### Fertigkeitsfelder

* **Skill-Name** – Für Menschen lesbarer Name, der in der Skill-Liste und den Aktivitätsprotokollen angezeigt wird.
* **Beschreibung** – Kurze Erklärung, die beim automatischen Skill-Matching verwendet wird.
* **Tags** – Durch Kommas getrennte Schlüsselwörter, die beim automatischen Skill-Matching verwendet werden.
* **Ziel** – `AI Chat/Functions`, `AI Agent` oder `Both`.
* **Aktiv** – Aktiviert oder deaktiviert nur diesen Skill.
* **Skill Markdown** – Der Anweisungstext, der an das Modell gesendet wird, wenn die Fertigkeit ausgewählt wird.

### Steuerelemente

* **Enable AI Skills** deaktiviert oder aktiviert alle Skills global.
* **Automatisch nur passende Fertigkeiten senden** sendet nur aktive Fertigkeiten, die der aktuellen Anfrage entsprechen. Bei Deaktivierung werden alle aktiven Fertigkeiten mit einem passenden Ziel gesendet.
* **Hinzufügen** erstellt einen neuen aktiven Skill mit dem Ziel `Both`.
* **Delete** entfernt den ausgewählten Skill nach Bestätigung.
* **Import** akzeptiert `.md`- und `.markdown`-Dateien.
* **Export** schreibt Markdown-Dateien für den ausgewählten Skill oder alle Skills, wenn keiner ausgewählt ist.
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

### Verwendung von KI für ausgewählten Text

1. Text im Terminal auswählen.
2. Klicken Sie mit der rechten Maustaste auf den ausgewählten Text.
3. Öffnen Sie **AI** und wählen Sie:
   * **Zusammenfassen** – Erstellt eine prägnante Zusammenfassung der ausgewählten Ausgabe.
   * **Problem lösen** – Analysiert die ausgewählte Fehlerausgabe und schlägt mögliche Korrekturen vor.
   * **Ask** – Sendet die Auswahl zusammen mit Ihrer eigenen Folgefrage oder Anweisung.
4. Bestätigen Sie die Anfrage im Vorschaudialog. Sie können den ausgewählten Text vor dem Senden bearbeiten. Fügen Sie für **Fragen** Ihre eigene Eingabeaufforderung hinzu. Das Dialogfeld zeigt auch die geschätzten Anforderungstoken und das prognostizierte verbleibende Kontingent an.
5. Die Antwort wird in einer temporären AI-Registerkarte geöffnet. Sie können den gleichen Kontext mit Folgeaufforderungen aus dem unteren Verfasserfeld fortsetzen.
6. Verwenden Sie **Speichern** auf der Registerkarte „AI“, um die Konversation unter einem benutzerdefinierten Titel zu speichern.
7. Öffnen Sie gespeicherte Konversationen später erneut über **Tools > AI Manager** oder ++Ctrl+Shift+Y++ (++Cmd+Shift+Y++ unter macOS).

### Funktionen der AI-Ergebnisregisterkarte

* Das Gesprächsprotokoll ist schreibgeschützt und nicht im gespeicherten Projekt-/Sitzungsstatus enthalten.
* `<think> ... </think>`-Blöcke werden aus der sichtbaren Ausgabe entfernt.
* Mit der Symbolleiste können Sie die Konversation kopieren, den Chat speichern oder umbenennen, ihn als PDF/Markdown/Nur-Text teilen/exportieren, die letzte Anfrage erneut versuchen, die Registerkarte schließen, laufende Anfragen abbrechen und die Schriftgröße ändern.
* Die Antwortsprache ist standardmäßig die aktuelle GUI-Sprache. Sie können die Antwortsprache und das aktive KI-Profil pro Chat ändern, bevor Sie eine Folgeaufforderung senden.
* Follow-up-Eingabeaufforderungen in **Zusammenfassen** und **Problem lösen** werden als normale Chat-Fragen fortgesetzt; Sie werden nicht zur ursprünglichen Eingabeaufforderung für Zusammenfassung/Problemanalyse zurück gezwungen.
* Erkannte Codeblöcke erhalten einen eigenen Kopier-Button und können auch direkt im Snippet Manager gespeichert werden. Blöcke, die Bilder, Diagramme oder Mathematik enthalten, werden stattdessen als Bilder gerendert – siehe [Gerenderte Bilder, Diagramme und Mathematik](#rendered-images-diagrams-and-math).
* gerenderte Markdown-Tabellen können als ganze Tabelle, einzelne Spalte oder einzelne Zelle kopiert werden.
* Die ausgewählte Schriftgröße der AI-Registerkarte wird global gespeichert und für zukünftige AI-Ergebnisregisterkarten wiederverwendet.
Die * Token-Nutzung wird nach erfolgreichen Anfragen pro KI-Profil aufgezeichnet, sodass Warnungen und Rücksetzzyklen korrekt bleiben.
* Wenn ein gespeicherter Chat auf ein AI-Profil verweist, das nicht mehr existiert, fordert korTTY Sie auf, ein Ersatzprofil auszuwählen, bevor Sie mit den Folgeaufforderungen fortfahren.

### gerenderte Bilder, Diagramme und Mathematik

KI-Antworten, die Bilder, Diagramme oder mathematische Formeln enthalten, werden im Chat als Bilder gerendert, anstatt rohes Markup anzuzeigen. Dies gilt auch für gespeicherte Chats, die über den AI Manager erneut geöffnet werden.

| Inhalt in der KI-Antwort | Gerendert als |
|--------------------------|-------------|
| ` ```svg ` / ` ```xml ` / ` ```html ` Codeblock (oder Block ohne Tags), der ein `<svg>` Dokument enthält | Inline-Vektorbild |
| Markdown-Bildlink mit einem `data:image/png;base64,…`-URI im Antworttext oder ein Codeblock, der nur einen solchen Daten-URI enthält. | Inline-Rasterbild (PNG, JPEG, GIF, BMP; bis zu 8 MB dekodiert) mit einer Schaltfläche **Bild kopieren** |
| ` ```mermaid ` Codeblock | Mermaid-Diagramm (gebündelte Bibliothek, kein Netzwerk) |
| ` ```latex ` / ` ```tex ` / ` ```math ` Codeblock oder `$$ … $$` Mathematik im Antworttext | Satzformel (gebündeltes MathJax, kein Netzwerk) |

Jeder gerenderte Block behält eine Kopfzeile mit der üblichen Kopierschaltfläche und einem Umschalter zwischen **Code anzeigen/Bild anzeigen**, sodass die zugrunde liegende Quelle nur einen Klick entfernt bleibt. Während ein Mermaid-/Math-Block noch gerendert wird, bleibt die Quelle sichtbar; Wenn das Rendern fehlschlägt (z. B. ein Mermaid-Syntaxfehler), bleibt der Block in der Quellansicht und die Kopfzeile zeigt den Grund an. `plantuml`/`puml`-Zäune und nicht getaggte `@startuml`-Inhalte sind gewöhnliche Quellcodeblöcke und werden nicht speziell gerendert.

Beispielaufforderungen, die gerenderte Antworten erzeugen:

```text
Draw a simple house as an SVG image.
Create a Mermaid flowchart of a typical login flow.
Create a Mermaid sequence diagram for an SSH handshake.
Explain the Pythagorean theorem and show the formula.
```

Eine Mermaid-Antwort, eingegrenzt mit dem Sprachtag `mermaid` und dieser Text wird als Flussdiagramm dargestellt:

```mermaid
flowchart TD
  Login-->Validate;
  Validate-->|ok| Session;
  Validate-->|fail| Error;
```

Und zeigen Sie die Mathematik im Antworttext als gesetzte Formel an:

```text
$$a^2 + b^2 = c^2$$
```

!!! note "Rendering-Details und Anforderungen"
    * SVG und die gerenderte Mermaid-Ausgabe werden mit deaktiviertem JavaScript und entfernten Skripten/Ereignishandlern aus dem Dokument angezeigt.
    * Mermaid 11.16.0 läuft mit der Sicherheitsstufe `strict` von einem SHA-256-gepinnten lokalen Bundle; LaTeX wird durch ein separat geladenes lokales MathJax-Bundle gesetzt. Keiner von beiden benötigt einen Internetzugang.
    * Chat Mermaid behält die vollständige gebündelte Diagrammunterstützung bei, einschließlich Flussdiagrammen, Sequenz-, Klassen-, Zustands-, ER-, Mindmap- und Architekturdiagrammen. Frontmatter, Anweisungen, Netzwerk-/Datei-/Daten-/JavaScript-URLs, externe Bilder/Symbole, Links und Klickrückrufe werden abgelehnt; Quell-, Kantenanzahl-, Rastergrößen- und Timeout-Grenzwerte schützen den Renderer.
    * Vollständige LaTeX-Dokumente (`\documentclass`) bleiben absichtlich Codeblöcke; Es werden nur Formeln gesetzt.
    * Mermaid folgt der Hell/Dunkel-Palette des aktiven Chats; Andere gerenderte Bilder und Formeln behalten eine lesbare neutrale Leinwand.

## AI-Manager

Öffnen Sie **AI > AI Manager** oder drücken Sie ++Ctrl+Shift+Y++ (++Cmd+Shift+Y++ unter macOS).

![AI Manager with Local Models selected and persistently underlined](../assets/screenshots/ai/ai-manager.png)

Der AI Manager ist ein modales Fenster: Sie können es geöffnet lassen, während Sie im Hauptfenster weiterarbeiten. Durch erneutes Aufrufen von **AI Manager** wird derselbe Manager für dieses Hauptfenster wiederhergestellt und fokussiert, anstatt ein Duplikat zu öffnen. Der aktive primäre Abschnitt bleibt durch eine fette Akzentunterstreichung gekennzeichnet, nachdem der Fokus auf die Steuerelemente des Abschnitts verschoben wurde.

Der Manager kombiniert Profil-, lokale Inferenz-, Abruf- und gespeicherte Chat-Verwaltung:

* **Profile** – KI-Profile erstellen, bearbeiten, testen, speichern und entfernen. Die Profilliste zeigt den aktuellen Kontingent-/Nutzungsstatus für jedes Profil.
* **Lokale Modelle** – Hugging Face durchsuchen, GGUF-Dateien importieren/herunterladen/konfigurieren, den Funktionstest nach der Installation ausführen und mehrere llama.cpp-Sidecars starten oder stoppen.
* **Lokale KI** – Weisen Sie Text-/Übersetzungs- und Codierungsprofile zu, wählen Sie das Einbettungsmodell und das bevorzugte Laufzeit-Backend aus, speichern Sie ein verschlüsseltes Hugging Face-Token und wählen Sie die Laufzeitaktualisierungsrichtlinie aus.
* **Wissensspeicher** – Erstellen Sie lokale HNSW-Speicher, fügen Sie überprüfte Dateien oder rekursive Ordner hinzu, synchronisieren Sie Quellen und führen Sie eine Testsuche durch.
* **Gespeicherte Chats** – zuvor gespeicherte KI-Konversationen öffnen, umbenennen, aktualisieren oder löschen. Gespeicherte [AI Swarm](ai-swarm.md)-Konversationen werden in einem eigenen Abschnitt **Swarm-Chats** angezeigt, einschließlich der Konversationen, die durch geplante Swarm-Jobs erstellt wurden.

Verwenden Sie **Einstellungen > KI** für globale Verhaltensänderungen und **AI Manager** für die tägliche Profil-, Modell-, Wissensspeicher- und Chat-Verwaltung.

## Fragen Sie nach der Anleitung (AI-Dokumentensuche)

Das integrierte Anleitung (**Hilfe > Anleitung**, ++f1++) enthält eine KI-gestützte Suche. Schalten Sie **KI-Suche** in der Symbolleiste des manuellen Fensters ein, um einen Seitenbereich zu öffnen, geben Sie eine Frage in natürlicher Sprache ein – zum Beispiel *„Wie führe ich den KI-Agenten im Terminalfenster aus?“* – und drücken Sie ++enter++.

So funktioniert es:

* korTTY wählt die relevantesten Abschnitte aus dem gebündelten Offline-Anleitung aus (keine Einbettungen, kein externer Suchdienst) und sendet nur diese Auszüge zusammen mit Ihrer Frage an Ihr **Standard-KI-Profil**.
* Die Antwort wird **ausschließlich aus der Anleitunginhalt** generiert und ist in der App-Sprache verfasst. Wenn die Anleitung die Frage nicht beantwortet, sagt der Assistent dies, anstatt zu raten.
* Antworten zitieren ihre Quellen: Klicken Sie auf ein Inline-Zitat oder einen Eintrag in der Liste **Quellen**, um in der manuellen Ansicht direkt zur Seite und zum Abschnitt zu gelangen, auf die verwiesen wird.
* Wenn nichts im Anleitung mit der Frage übereinstimmt, antwortet korTTY lokal, ohne den KI-Endpunkt überhaupt zu kontaktieren.

Anforderungen:

* A konfiguriertes AI-Profil (siehe [Setup](#setup)); Es wird das Standardprofil verwendet.
* Ein entsperrter Master-Passwort-Tresor, wenn das Profil einen verschlüsselten API-Schlüssel speichert.

!!! warning "Datensicherheit"
    Der Fragetext und die ausgewählten manuellen Auszüge werden an den konfigurierten KI-Endpunkt übermittelt. Der Inhalt der Anleitungs selbst ist eine öffentliche Dokumentation, aber Ihre Frage ist Freitext – vermeiden Sie das Einfügen von Geheimnissen oder verwenden Sie einen vertrauenswürdigen lokalen Endpunkt wie **LM Studio**. Bei manuellen Fragen sind die Internetzugriffsmodi immer deaktiviert.

## AI Agent und KI-Planung

korTTY unterstützt Workflows im Agentenstil für eine aktive Terminalsitzung.

!!! note "SSH und lokale Shells"
    Die Befehlsausführungs-Engine des Agenten ist hinter einer `AgentCommandRunner`-Abstraktion mit zwei Backends – **SSH** (Exec-Kanal) und **local** (ein neuer lokaler Prozess) von SSH entkoppelt. Der **AI Agent** und **AI Planning** laufen daher sowohl in SSH-Sitzungen als auch in [lokalen Shells](connections.md#local-shell) unter Windows, macOS und Linux: Befehle verwenden ein natives lokales Backend (PowerShell über `-EncodedCommand`, `cmd.exe` oder POSIX `/bin/sh`), die Umgebungsprüfung und die Systemeingabeaufforderung sind plattformbewusst, sodass das Modell native Befehle generiert und der gleiche Genehmigungsablauf gilt. Eine lokale Ausführung erfasst das aktuelle Verzeichnis der interaktiven Shell einmal und verwendet es für die Probe und jeden Befehl. **Lokale Shell-Einschränkung:** keine `sudo`/Administrator-Erhöhung unter Windows. Die kopflose KI-Agent-Aktion des JobScheduler bleibt nur SSH.

### Der Agent wird gestartet

* **AI Agent** – Starten Sie über **Tools > AI Agent...**, über das Kontextmenü des Terminals oder mit dem Terminal-Verknüpfungsbefehl. Der Agent kann je nach **Einstellungen > AI** einen speziellen Chat-Tab öffnen oder auf das aktive Terminalfenster zielen.
* **AI-Planung** – Starten Sie über **Tools > AI-Planung...**, über das Terminal-Rechtsklickmenü oder mit `agent-plan` / `agent -plan`. Im Planungsmodus werden klärende Fragen gestellt, eine oder mehrere Optionen vorgeschlagen, ein endgültiger Planbericht erstellt und Sie können mit dem akzeptierten Plan beginnen.

### Aktivitätspanel und Platzierung

* **Split-Local-Aktivitätsbereich** – Auf Terminals ausgerichtete Läufe werden am unteren Rand des Terminalsplits dort angezeigt, wo der Lauf gestartet wurde. Jeder Split verfügt über ein eigenes Panel, sodass verschiedene Splits ihre eigenen Agentenaufgaben parallel ausführen können.
* **Panel-Platzierung** – Verwenden Sie **Ansicht > AI Agent Panel**, um **Unten** (Standard), **Links andocken** oder **Rechts andocken** auszuwählen. Im angedockten Modus wird die Aktivität in einem in der Größe veränderbaren Seitenbereich angezeigt, der an das Hauptfenster angeschlossen ist. Ziehen Sie den Teiler, um die Größe zu ändern. Die Platzierung und Breite werden bei jedem Neustart gespeichert. Im Seitenmodus gibt es eine äußere Lasche pro Anschluss der aktiven Anschlusslasche und die Läufe werden vertikal gestapelt. Wenn Sie zu einer anderen Terminal-Registerkarte wechseln, wechselt das Dock zu den Terminals dieser Registerkarte.
* **Agentenstatusanzeigen** – Ein KI-Agentenstatussymbol pro Terminal wird in der Dashboard-Struktur angezeigt und dem Registerkartentitel des Terminals vorangestellt:
  - ✋ wartet auf Eingabe
  - ⚡ funktioniert
  - ⏸ pausiert
  -  ✓ fertig
* **Gleichzeitige Ausführungen** – Mehrere gleichzeitige Ausführungen pro Split werden als schließbare Registerkarten im Aktivitätsbereich angezeigt (eine Registerkarte pro Ausführung), mit einer Parallelitätsobergrenze pro Widget von 5 Ausführungen. Abgeschlossene Läufe bleiben als Tabs erhalten, bis sie geschlossen werden.
* **Tippen während des Laufens** – Das Tippen ist nicht mehr gesperrt, während ein Lauf aktiv ist. Sie können mit der Eingabe am Shell-Prompt fortfahren und einen weiteren `agent ...`-Befehl starten (er öffnet eine neue gleichzeitige Registerkarte). Es werden nur Laufsteuerungstasten abgefangen: ++Esc++ oder ++Ctrl+C++ brechen die Ausführung des ausgewählten Tabs ab; ++Ctrl+R++ schaltet die Denkdetails dieses Laufs um.
* **Pause und Fortsetzen** – Auf jeder Laufregisterkarte werden Schaltflächen zum Anhalten und Abbrechen angezeigt. Pause parkt den Agenten an einem sicheren Punkt zwischen den Schritten; Die Pausenzeit wird von der Laufarbeitszeit ausgeschlossen.
* **Aktuelles Verzeichnis** – Terminalverknüpfungen verwenden das von korTTY verfolgte aktuelle Verzeichnis für SSH und lokale Shells. Ein lokaler Lauf erfasst ein stabiles Verzeichnis für seine Probe und jeden Befehl, sodass Befehle und generierte Dateien relativ zu dem Ort bleiben, an dem die interaktive Shell zu Beginn des Laufs arbeitete.
* **Genehmigungen und Sudo** – Der Agent kann vor der Befehlsausführung eine explizite Genehmigung anfordern und im Aktivitätsbereich nach einem Sudo-Passwort fragen. Die Passworteingabe ist maskiert, kann mit ++Enter++ übermittelt werden und ermöglicht bis zu drei Wiederholungsversuche mit falschem Passwort. Wenn ein Passwort zwischengespeichert wird, wird es nur für den aktuellen Agenten-/Sitzungskontext verwendet. Wenn eine Benutzereingabe (Sudo-Passwort/Befehlsgenehmigung) erforderlich ist, wird das Bedienfeld automatisch erweitert.
* **Reduzierte Statusleiste** – Wenn das Bedienfeld minimiert ist, wird eine kompakte Statusleiste mit der Ausführungsaufforderung, dem Status, den Schaltflächen „Pause/Abbrechen“ und einer Schaltfläche „Erweitern“ angezeigt. Während der Agent aktiv arbeitet, wird ein Kreissymbol angezeigt, und eine fettgedruckte ✋-Markierung signalisiert, wenn eine Benutzereingabe erforderlich ist.
* **Reduziert halten** – Verwenden Sie **Reduziert halten**, um das Panel minimiert auf die Statusleiste zu bringen.
* **Terminalausgabe** – Die endgültigen Antworten des Terminalagenten werden in den Terminalbereich zurückgeschrieben, sodass das Shell-Transkript die Antwort enthält.
* **Panel-Steuerelemente** – Verwenden Sie die Schaltfläche „Neu laden“, um den ausgewählten Agentenbefehl erneut auszuführen. Kopier- oder Snippet-Aktionen sind für Aktivitätszeilen verfügbar. Exportieren Sie den aktuellen Lauf oder den vollständigen Verlauf als Markdown, Nur-Text, YAML, XML, JSON, PDF oder Asciidoctor. Verwenden Sie **Alle erweitern**, um die Aktivitätsdetails offen zu halten. Verwenden Sie **A-** und **A+**, um die Schriftgröße der Aktivität zu ändern. Ziehen Sie den Größenänderungsgriff, um die Panelhöhe zu ändern.

## Terminal-Agent-Verknüpfungsbefehle

Wenn Terminal-Agent-Verknüpfungen aktiviert sind und sich das Terminal an einem Shell-Prompt befindet, fängt korTTY diese Befehle lokal ab, anstatt sie an den Server zu senden:

```bash
agent <goal>
agent-ask <question>
agent-plan <task>
agent -plan <task>
```

Der Basisbefehlsname kann unter **Einstellungen > AI** konfiguriert werden. Wenn Sie `agent` umbenennen, leitet korTTY automatisch die passenden Befehle `-ask` und `-plan` ab. Auf derselben Einstellungsseite kann die Groß-/Kleinschreibung des Befehlsnamens nicht beachtet werden, und der Setup-Dialog pro Lauf kann deaktiviert werden. Wenn der Dialog deaktiviert ist, verwendet korTTY das konfigurierte Standardprofil.

### TAB Abschluss- und Eingabeaufforderungsverlauf

An der Shell-Eingabeaufforderung wird die TAB-Vervollständigung für Agentenbefehle verbessert:

* Geben Sie den Namen des Agentenbefehls ein (z. B. `agent`) und drücken Sie dann ++TAB++, um die Befehlsvarianten (`agent`, `agent-ask`, `agent-plan`) anzubieten.
* Geben Sie den Befehl plus ein Leerzeichen ein (z. B. `agent `) und drücken Sie dann ++TAB++, um den aktuellen Verlauf der Agentenaufforderungen anzuzeigen. In jeder Zeile werden die Eingabeaufforderung und das Datum/die Uhrzeit der letzten Ausführung angezeigt, dedupliziert durch den Eingabeaufforderungstext (neuester zuerst).
* Jede Verlaufszeile zeigt links die Eingabeaufforderung und rechts das Datum/die Uhrzeit der letzten Ausführung. Eingabeaufforderungen mit mehr als 60 Zeichen werden durch Auslassungspunkte gekürzt. Die vollständige Eingabeaufforderung wird weiterhin eingefügt, wenn der Eintrag ausgewählt wird.
* Die Größe des Verlaufs-Popup kann geändert werden – ziehen Sie den Griff in der unteren rechten Ecke – und merkt sich seine Größe bei jedem Neustart. Wenn der Verlauf länger als das Popup ist, wird eine vertikale Bildlaufleiste angezeigt.
* Entfernen Sie im Verlaufs-Popup eine einzelne Eingabeaufforderung, indem Sie auf die Schaltfläche ✕ der Zeile klicken (oder ++Del++ auf Tastaturen mit Vorwärts-Löschen-Taste drücken), oder entfernen Sie den gesamten Verlauf mit **Alle löschen** (eine zweistufige Bestätigung schützt vor versehentlichem Löschen). Löschungen werden sofort gespeichert.
* Außerhalb dieses Kontexts ist ++TAB++ eine normale Shell-Vervollständigung.

Die Verlaufsgröße kann unter **Einstellungen > AI** konfiguriert werden (Standard 20, Bereich 5–100).

### So funktioniert der AI Agent

Der Terminal AI Agent ist ein kontrollierter Terminalautomatisierungsworkflow. Es führt keine beliebige Modellausgabe direkt im interaktiven Terminal aus. Stattdessen folgt jede Runde diesem Muster:

1. korTTY prüft die aktive SSH- oder lokale Shell-Sitzung mit einem nicht interaktiven Befehl und zeichnet kompakten Kontext wie aktuellen Benutzer, Host, Betriebssystem, aktives Terminal-Arbeitsverzeichnis, Sudo-Verfügbarkeit, Festplattenpfad und aktuellen Befehlsstatus auf.
2. korTTY sendet die Benutzeraufgabe, den Sonden-Snapshot, frühere Befehlsergebnisse, aktive KI-Fähigkeiten und optional die Web-Tool-Verfügbarkeit an das ausgewählte KI-Profil.
3. Das Modell muss eine strikte JSON-Entscheidung zurückgeben: Befehle ausführen, Bestätigung anfordern, beenden oder blockieren.
4. korTTY validiert das JSON-Schema und die Befehlseinschränkungen. Ungültige Antworten werden einmalig repariert; unsichere oder nicht unterstützte Befehlsentscheidungen werden abgelehnt.
5. korTTY führt genehmigte Befehle über das aktive Backend aus: SSH-Exec-Kanäle für SSH-Sitzungen oder neue lokale Prozesse für lokale Shells. Jeder Befehl startet in dem für die Ausführung erfassten Verzeichnis. Ein `cd` innerhalb eines Befehls bleibt nicht bis zum nächsten Befehl bestehen.
6. Die Befehlsausgabe wird dem Aktivitätsbereich und der nächsten Modellrunde hinzugefügt, bis die Aufgabe abgeschlossen, blockiert, abgebrochen oder das Rundenlimit erreicht ist.

### Passende Aufgaben

Das Mittel ist geeignet für:

* überprüft Dateien, Verzeichnisse, Paketstatus, Protokolle, Dienststatus und Systemkonfiguration;
* Erstellen oder Ändern von Skripten und Konfigurationsdateien, wenn die Aufgabe dies erfordert;
* führt Tests, Syntaxprüfungen, Linters oder schreibgeschützte Diagnosebefehle aus;
* Zusammenfassung der Befehlsausgabe und Erläuterung der Ergebnisse;
* Planung mehrstufiger betrieblicher Änderungen vor der Implementierung.

Das Mittel ist nicht bestimmt für:

* interaktive Vollbildprogramme wie `vim`, `nano`, `top`, `less`, `ssh` oder Befehle, die auf Eingabeaufforderungen warten;
* Daemons oder Befehle mit langer Laufzeit ohne eindeutigen Abschluss;
* geheime Exfiltration oder blinde destruktive Befehle;
* web recherchiert nach lokalen Dateien, es sei denn, Sie fragen ausdrücklich nach externen/aktuellen Informationen.

### Beispielbefehle

```bash
agent show the 10 largest XML files in this directory
agent update groesste_xml.pl so the -r flag searches subdirectories recursively
agent check why nginx failed to start and suggest the safest fix
agent-ask what user and directory am I currently using?
agent-plan migrate this host from package X to package Y
```

Benennen Sie bei lokalen Dateiüberprüfungsaufgaben die Datei in der Aufgabe. Der Agent sollte es dann mit Shell-Befehlen wie `sed -n`, `cat`, `file` oder sprachspezifischen Syntaxprüfungen überprüfen. Wenn ein internetfähiges Profil aktiv ist, hält korTTY Web-Tools weiterhin von diesen lokalen Dateiplanungsaufforderungen fern, es sei denn, Ihre Aufgabe fragt eindeutig nach aktuellen oder externen Informationen.

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
| 💭 | Zustand: Denken/Argumentieren |
| 💬 | Ausgabe: Meldung oder Ergebnis |
| ✋ | Erforderlich: Warten auf Benutzereingabe (Sudo/Genehmigung) |
| ❌ | Status: Fehler oder fehlgeschlagene Aktivität |
| 🚫 | Status: abgebrochene Aktivität |

Der rote Stil ist für Fehler- und Fehlerzustände reserviert.

### Sicherheit und Fehlerbehandlung

korTTY fügt Leitplanken für die Agentenausführung hinzu:

* Befehle sind pro Runde begrenzt und dürfen nicht interaktiv sein.
* Befehle, die das System ändern oder Berechtigungen erfordern, können abhängig von den Einstellungen und der Modellentscheidung durch eine Bestätigung weitergeleitet werden.
* Sudo verwendet `sudo -n` und Passwortabfragen im Aktivitätsbereich. korTTY erlaubt keine `sudo -S`, `su` oder Befehle, die unbegrenzt auf eine Terminal-Passwortabfrage warten.
* SSH-Verzeichnisse werden anhand von Shell-Hooks, Terminal-Eingabeaufforderungskontext und Prüfergebnissen verfolgt. Wenn ein verfolgtes Remote-Verzeichnis nicht mehr vorhanden ist, versucht korTTY die Prüfung erneut vom SSH-Standardverzeichnis aus und meldet das Problem. Lokale Ausführungen aktualisieren das Shell-Prozessverzeichnis oder verwenden einen absoluten nativen Eingabeaufforderungspfad, frieren das Ergebnis für die Ausführung ein und stoppen sicher, wenn ein geändertes Verzeichnis nicht ermittelt oder zugeordnet werden kann.
* Während eine auf das Terminal ausgerichtete Ausführung aktiv ist, ist die normale Eingabe weiterhin zulässig. Nur die Laufsteuerungstasten (++Esc++/++Ctrl+C++ zum Abbrechen des ausgewählten Laufs, ++Ctrl+R++ zum Umschalten seiner Denkdetails) werden abgefangen.
* Websuchfehler, HTTP-Fehler, Authentifizierungsfehler, leere Ergebnisse und Zeitüberschreitungen werden als explizite Toolfehler angezeigt.
* Wenn die KI-Antwort nicht mit dem erforderlichen JSON-Schema übereinstimmt, fordert korTTY eine Reparatur an. Schlägt auch die Reparatur fehl, wird der Lauf mit einer Begründung gesperrt.

## AI Swarm (Multiserver)

Der [KI-Schwarm](ai-swarm.md) sendet eine KI-Agentenaufgabe gleichzeitig an viele Server: Jeder ausgewählte Server erhält seinen eigenen Agentenlauf – auch Server **ohne offenes Terminal** – und die Antworten pro Server werden in einer einzigen Vergleichstabelle zusammengefasst. Die Schwarm-Registerkarte fügt einen animierten Statusstreifen pro Agent, erweiterbare Live-Transkripte, Pausen-/Fortsetzungs-/Neustart-/Stopp-Steuerelemente pro Agent und schwarmweit, KI-freie Skriptausführung über den Snippet-Manager sowie eine Schaltfläche **Planen…** hinzu, die den Lauf in einen unbeaufsichtigten Lauf verwandelt [JobScheduler](jobscheduler.md) Arbeit. Öffnen Sie es mit **AI > AI Swarm...** oder ++ctrl+alt+s++ (Befehl unter macOS); siehe die [KI-Schwarm](ai-swarm.md) Seite für den vollständigen Funktionsumfang.

## Workflow-Skript generieren

Nachdem eine fertige Agentenausführung erfolgreich abgeschlossen wurde, verwandelt eine **Workflow**-Schaltfläche die Ausführung in ein einzelnes eigenständiges, reproduzierbares Skript in einer ausgewählten Sprache (Bash, Python, Perl, Ruby, PowerShell, Ansible Playbook, **Windows-CMD** Batch oder **AppleScript**) mit robuster Fehlerbehandlung, detaillierten Kommentaren und einem Header (Skriptname, Ersteller, Datum/Uhrzeit).

Skripterstellung:

* Ladet automatisch passende KI-Fähigkeiten (z. B. eine Sprachqualitätsfähigkeit für die Zielsprache).
* Kann mehrere Sprachvarianten und mehrere Vorschläge als Inline-Tabs erzeugen.
* Unterstützt Header-Vorlagen aus der festen, nicht löschbaren Snippet-Kategorie **Script-Header**.
* Enthält optional ein Mermaid-Flussdiagramm für die Skriptlogik. Während das Diagramm erstellt wird, wird ein funktionierender Kreisel angezeigt, sodass klar ist, dass die KI-Verbindung ausgelastet ist.
* Speichert im Snippet-Manager mit einem kurzen, automatisch generierten Namen und der korrekten Erweiterung (dedupliziert durch den vollständigen Namen einschließlich Erweiterung).
* Tagt das Snippet zur einfachen Filterung als `workflow`.
* Setzt die Spalte **System** (OS) automatisch aus dem geprüften Betriebssystem des Agenten (jede Linux-Distribution wird zu Linux).
* Der Internetzugriff wird während der Generierung erzwungen AUS.

**Zielsprachen:** Bash, Python, Perl, Ruby, PowerShell, Ansible, plus **Windows-CMD** (`.cmd`-Batch – `@echo off`-Lead-Zeile, `REM`-Header-Kommentare, `errorlevel`-Prüfungen) und **AppleScript** (`.applescript` – `osascript`-Shebang, `--`-Kommentare, `try`/`on error`-Verarbeitung).

Jeder Editor für generierte Skripte verfügt über die Schaltflächen **A−** / **A+** und unterstützt ++ctrl++ + Mausrad (Cmd unter macOS), um die Schriftgröße zu ändern; Die gewählte Größe wird sitzungsübergreifend gespeichert.

Die Größe des Workflow-Dialogfelds kann geändert werden und seine Größe und Position werden für die zukünftige Verwendung gespeichert.

!!! tip "Fehler im KI-Backend löschen"
    Wenn der KI-Server nicht mehr über genügend Arbeitsspeicher verfügt oder ein Ressourcenlimit erreicht (z. B. LM Studio/MLX „Ressourcenlimit überschritten“, „metal::malloc“), zeigt das Dialogfeld einen kurzen, umsetzbaren Hinweis anstelle des rohen mehrzeiligen Backend-Stack-Trace an; Alle anderen KI-Fehler werden in einer einzigen Zeile zusammengefasst.
