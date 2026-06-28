---
title: KI
---

# KI

Konfigurieren Sie AI-Profile und Terminal-AI-Agent-Einstellungen. Dies ist die größte Einstellungsregisterkarte und umfasst die Aktivierung von KI-Funktionen, Profile (mit Modell, API-Endpunkt, Verbindungsmodus und Argumentationsebenen), Token-Kontingentverwaltung, Snippet-Editor-Einstellungen und Internetzugangskonfiguration. Öffnen über **Konfiguration → Globale Einstellungen → AI**; in `~/.kortty/global-settings.xml` gespeichert.

![AI settings tab](../../assets/screenshots/settings/ai.png)

## Grundeinstellungen

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| KI-Funktionen aktivieren | umschalten | — | Auf | `aiFeaturesEnabled` |
| Bestätigungsdialog vor dem Senden von AI-Anfragen anzeigen | umschalten | — | Auf | `aiConfirmBeforeSend` |

## Terminal-KI-Agent

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| AI-Agent-Ausführung aktivieren | umschalten | — | Auf | `terminalAgentExecutionEnabled` |
| Fragen Sie nach, bevor AI Agent das Zielsystem ändert | umschalten | — | Aus | `terminalAgentConfirmMutatingCommandSets` |
| Verwenden Sie OSC 133-Eingabeaufforderungsmarkierungen, wenn die Shell sie bereits bereitstellt | umschalten | — | Auf | `defaultPromptHookEnabled` |
| Debug-Meldungen des Agenten anzeigen | umschalten | — | Aus | `terminalAgentShowDebugMessages` |
| Laufzeitmeldungen des Agenten anzeigen | umschalten | — | Aus | `terminalAgentShowRuntimeMessages` |
| Vor jeder Ausführung den Terminal-Agent-Setup-Dialog anzeigen | umschalten | — | Auf | `terminalAgentShowRunDialog` |
| Name des Agentenbefehls | Text | — | Agent | `terminalAgentCommandName` |
| Groß- und Kleinschreibung des Agentenbefehlsnamens nicht berücksichtigen | umschalten | — | Aus | `terminalAgentCommandNameCaseInsensitive` |
| AI-Agent-Aufgabenziel | Dropdown | Terminalfenster, Neues Chatfenster | Terminalfenster | `terminalAgentExecutionTarget` |
| Größe des Eingabeverlaufs des Terminalagenten | Nummer | 5–100 | 20 | `terminalAgentInputHistorySize` |

## KI-Profile

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Standardprofil | Dropdown | (Liste der konfigurierten Profile) | — | `defaultAiProfileId` |

### Profileinstellungen (im Editor-Raster)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Profilname | Text | — | KI-Profil | (Profilfeld `name`) |
| Verbindung | Dropdown | HTTP-API, lokale CLI | HTTP-API | (Profilfeld `connectionMode`) |
| API-URL | Text | — | — | (Profilfeld `apiUrl`) |
| CLI-Anbieter | Dropdown | (registrierte Anbieter) | — | (Profilfeld `cliProviderId`) |
| CLI-ausführbare Datei | Text | — | — | (Profilfeld `cliExecutablePath`) |
| Modell | Dropdown/Text | (editable; supports "Default" / "Auto") | — | (Profilfeld `model`) |
| Kundenspezifisches Modell | Text | — | — | (Profilfeld `cliCustomModel`) |
| Begründung | Dropdown | Deaktiviert, Keine, Minimal, Niedrig, Mittel, Hoch, Extra hoch | Deaktiviert | (Profilfeld `reasoningEffort`) |
| Internetzugang | Dropdown | Deaktiviert, KorTTY Tavily Tool, LM Studio Tavily MCP, Bright Data Web MCP, Brave Search MCP, SearXNG MCP, LM Studio Toolpack | Deaktiviert | (Profilfeld `internetAccessMode`) |
| API-Schlüssel (optional) | Text | (Passwortfeld) | — | (Profilfeld `encryptedApiKey`) |
| Max. Zeichen | Nummer | 1–50.000.000 | 100.000 | (Profilfeld `maxSelectionChars`) |
| Tokenizer | Dropdown | Schätzung, OpenAI cl100k_base, OpenAI o200k_base, OpenAI p50k_base, OpenAI r50k_base | Schätzung | (Profilfeld `tokenizerType`) |
| Max. Token | Zahl + Einheit | (Betrag: 0–1.000.000; Einheit: Tausender oder Millionen) | 0 (unbegrenzt) | (Profilfelder `tokenLimitAmount`, `tokenLimitUnit`) |
| Warnschwellen | Zahlenpaar | Gelb %: 0–100, Rot %: 0–100 | 75 %, 90 % | (Profilfelder `tokenWarningYellowPercent`, `tokenWarningRedPercent`) |
| Zurücksetzen | Nummer + Ankerdatum | Zeitraum: 1–3650 Tage; Ankerdatum | 30 Tage | (Profilfelder `tokenResetPeriodDays`, `tokenResetAnchorDate`) |
| AI-Verbindung testen | Schaltfläche | — | — | (nur Aktion) |

## Snippet-Editor

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Standardsprache für AI-Text im Code | Dropdown | (verfügbare Sprachoptionen) | — | `aiCodeTextDefaultLanguage` |
| Optionale Zusatzanweisungen für KI-Aktionen im Snippet-Editor anzeigen | umschalten | — | Aus | `aiSnippetEditorAdditionalInstructionsEnabled` |
| Maximale Alternativlösungen | Nummer | 1–10 | 3 | `aiSnippetAlternativeSolutionCount` |

## Internetzugriffskonfiguration

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Tavily-API-Schlüssel | Text | (Passwortfeld) | — | `encryptedAiTavilyApiKey` |
| Bright Data API-Token | Text | (Passwortfeld) | — | `encryptedAiBrightDataApiToken` |
| Brave Search API-Schlüssel | Text | (Passwortfeld) | — | `encryptedAiBraveSearchApiKey` |
| SearXNG-URL | Text | — | — | `aiSearxngUrl` |
| Tavily MCP-Server-Label | Text | — | tavily | `aiTavilyMcpServerLabel` |
| Bright Data MCP-Serveretikett | Text | — | helle Daten | `aiBrightDataMcpServerLabel` |
| Brave Search MCP-Plugin-ID | Text | — | — | `aiBraveSearchMcpPluginId` |
| SearXNG MCP-Plugin-ID | Text | — | — | `aiSearxngMcpPluginId` |
| LM Studio Toolpack MCP-Plugin-ID | Text | — | — | `aiLmStudioToolpackMcpPluginId` |

## Notizen

### KI-Profile

KorTTY speichert mehrere benannte KI-Profile, jedes mit seinem eigenen Modell, API-Endpunkt, Verbindungsmethode und Argumentationseinstellungen. Jedes Profil verfolgt seine eigene Token-Nutzung separat. Profile unterstützen zwei Verbindungsmodi:

- **HTTP-API**: Direkte Verbindung zu einem OpenAI-kompatiblen REST-Endpunkt (API-URL, Modellnamen und optionalen API-Schlüssel angeben).
- **Lokale CLI**: Führen Sie einen lokalen Befehlszeilen-KI-Client aus (konfigurieren Sie den CLI-Anbieter, die benutzerdefinierte ausführbare Datei, die Argumentvorlage und den benutzerdefinierten Modellnamen).

Das **Standardprofil** wird von Terminal-AI-Aktionen und dem Terminal-AI-Agenten verwendet, wenn kein explizites Profil ausgewählt ist.

### Grad des Argumentationsaufwands

Der Argumentationsaufwand konfiguriert, wie tief die KI nachdenkt, bevor sie antwortet. Die verfügbaren Ebenen hängen vom Modell und Endpunkt ab:

- **Deaktiviert**: Kein Begründungsparameter gesendet; Das Modell verwendet sein Standardverhalten.
- **Keine**: Argumentation explizit deaktivieren (`reasoning: "none"` senden).
- **Minimal**: Leichte Argumentation; schnellste Ausführung.
- **Niedrig**: Argumentation mit geringem Aufwand; Balance zwischen Geschwindigkeit und Tiefe.
- **Medium**: Medium effort; angemessene Tiefe.
- **Hoch**: Hoher Aufwand; gründlichere Begründung.
- **Extra hoch**: Maximaler Argumentationsaufwand; am langsamsten, aber am umfassendsten.

Nicht alle Modelle unterstützen alle Ebenen. Verwenden Sie die Schaltfläche **Begründungsoptionen aktualisieren**, um verfügbare Ebenen für das aktuelle Profil und Modell zu ermitteln.

### Token-Kontingentverwaltung

Jedes AI-Profil verwaltet ein Token-Nutzungskontingent mit den folgenden Kontrollen:

- **Tokenizer**: Wählen Sie aus, welcher Tokenizer die Anzahl der Token schätzt – nützlich beim Wechsel zwischen OpenAI und anderen Anbietern. Optionen sind Estimate (generisch), cl100k_base (GPT-3.5/4), o200k_base (o1/o1-mini), p50k_base (Codex) und r50k_base (GPT-2).
- **Maximales Token-Limit**: Legen Sie eine Ausgabenobergrenze fest (in Tausenden oder Millionen Token oder unbegrenzt). Die Anzahl der Token wird nach einem fortlaufenden Zeitplan zurückgesetzt.
- **Reset-Zeitraum**: Anzahl der Tage zwischen Resets (1–3650), mit optionalem Ankerdatum für vorhersehbaren Reset-Zeitpunkt.
- **Warnschwellenwerte**: Gelbe Warnung wird bei einem Prozentsatz des Grenzwerts ausgelöst; red warning at a higher percentage. Konfigurieren Sie beide als Ganzzahlen 0–100.

Die Token-Nutzung wird als farbiger Balken und Zusammenfassung im Profileditor angezeigt, und die Profilliste zeigt den Token-Status inline an.

### Internetzugriffsmodi

Profilspezifische Internetzugriffsstrategie für KI-Anfragen. Jeder Modus erfordert unterschiedliche Anmeldeinformationen und MCP-Konfiguration:

- **Deaktiviert** (Standard): Kein Internetzugang.
- **KorTTY Tavily Tool**: Integrierte Websuche mit direkter Tavily-API (erfordert Tavily-API-Schlüssel).
- **LM Studio Tavily MCP**: Websuche über eine LM Studio Tavily MCP-Instanz (erfordert Tavily-API-Schlüssel und MCP-Server-Label).
- **LM Studio Tavily MCP**: Websuche über eine LM Studio Tavily MCP-Instanz (erfordert Tavily-API-Schlüssel und MCP-Server-Label).
- **Bright Data Web MCP**: Strukturierte Datenextraktion und Browsing über Bright Data Web MCP (erfordert Bright Data API-Token und MCP-Server-Label).
- **Brave Search MCP**: Suche über Brave Search MCP (erfordert den Brave Search API-Schlüssel und die MCP-Plugin-ID im `mcp/<server_label>`-Format).
- **SearXNG MCP**: Suche über eine SearXNG MCP-Instanz (erfordert SearXNG-URL und MCP-Plugin-ID im `mcp/<server_label>`-Format).
- **LM Studio Toolpack**: Community-Websuchserver für LM Studio Toolpack (erfordert Plugin-ID im `mcp/<server_label>`-Format).

Anmeldeinformationen werden verschlüsselt und sicher gespeichert. Verwenden Sie den Schalter **Löschen** neben jedem geheimen Feld, um gespeicherte Werte beim nächsten Speichern zu löschen.