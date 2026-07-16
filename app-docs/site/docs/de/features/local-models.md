---
title: Lokale Modelle mit llama.cpp
---

# Lokale Modelle mit llama.cpp

korTTY kann GGUF-Sprachmodelle direkt über einen integrierten, modellspezifischen `llama-server` ausführen. Sie benötigen weder LM Studio, Ollama noch ein Cloud-Inferenzkonto für lokale Chat-, Text-, Codierungs-, Übersetzungs- und Einbettungs-Workloads.

Öffnen Sie **AI > AI Manager** und verwenden Sie diese Registerkarten:

- **Local Models** installiert, importiert, konfiguriert, startet, stoppt und entfernt GGUF-Registrierungen.
- **Lokale KI** weist Profile den Rollen Text/Übersetzung und Codierung zu, wählt das RAG-Einbettungsmodell aus, speichert ein optionales Hugging Face-Token und zeichnet die Laufzeitaktualisierungsrichtlinie llama.cpp auf.
- **Profiles** erstellt ein **Integrated llama.cpp**-Profil für ein installiertes Modell und steuert dessen Prompt-Optimierungsvoreinstellung.

![Local Models with two stopped demo GGUF registrations](../assets/screenshots/ai/local-models.png)

!!! note "Speicher- und Netzwerkzugriff"
    Die Modellinferenz bleibt auf diesem Computer. korTTY kontaktiert Hugging Face nur, wenn Sie nach einem von Ihnen genehmigten Modell suchen oder es herunterladen. Öffentliche Repositories funktionieren ohne Token; Ein optionales Token für private oder geschlossene Repositorys wird mit dem Master-Passwort verschlüsselt. Offizielle Builds können den separat signierten Modell-/Prompt-Katalog auch über HTTPS im Hintergrund abrufen; Mit dieser Anfrage wird keine Eingabeaufforderung, kein Quelldokument oder Modellgewicht gesendet.

## Schneller Einrichtungsassistent

Wählen Sie **AI > AI Manager > Lokale Modelle > Einrichtungsassistent**. Der sechsstufige Assistent umfasst Datenschutz, erkannten Systemspeicher und Backend-Anleitung, optionale rollenspezifische Empfehlungen, Lizenz- und genaue Überprüfung der Downloadgröße, verifizierte Installation und eine abschließende Zusammenfassung der Bereitschaft.

Aktivieren Sie unter **Modelle für optionale Rollen auswählen** eine beliebige Kombination aus **Text und Übersetzung**, **Codierung** und **RAG-Einbettungen**. Jeder aktivierte Slot verfügt über einen eigenen Empfehlungsselektor. deaktivierte Slots bleiben unverändert. Text und Codierung teilen möglicherweise absichtlich eine kompatible Empfehlung. In diesem Fall lädt korTTY diesen GGUF nur einmal herunter und registriert ihn. Wenn Sie den Assistenten über eine Warnung wegen fehlender Einbettung öffnen, wird nur der RAG-Einbettungsslot vorab ausgewählt.

Der integrierte Bootstrap-Katalog verwendet konservative RAM-Stufen:

| Erkannter Speicher | Textempfehlung | Codierungsempfehlung | RAG-Einbettungen |
| --- | --- | --- | --- |
| Weniger als 16 GiB | Qwen3 1.7B, `Q4_K_M` | Qwen3 1.7B, `Q4_K_M` | Qwen3-Embedding 0.6B, `Q8_0` |
| 16–23 GiB | Qwen3 4B, `Q4_K_M` | Qwen2.5-Coder 7B Instruct, `Q4_K_M` | Qwen3-Embedding 0.6B, `Q8_0` |
| 24 GiB oder mehr | Qwen3 8B, `Q4_K_M` | Qwen2.5-Coder 7B Instruct, `Q4_K_M` | Qwen3-Embedding 0.6B, `Q8_0` |

Empfehlungen sind Ausgangspunkte, keine Hardwaregarantien. Die Modelltabelle kennzeichnet die geschätzte Passform als **Bequem**, **Möglich**, **Zu groß** oder **Unbekannt**; Kontextgröße und gleichzeitige Modelle wirken sich auch auf die Speichernutzung aus.

Bevor **Install** verfügbar wird, lädt korTTY jedes ausgewählte Repository mit der festen Revision des Katalogs im Hintergrund, überprüft, ob die angeforderte Quantisierung vorhanden ist, und zeigt das angeheftete Repository, die Lizenz und die genaue kombinierte GGUF-Downloadgröße an. Sie müssen diese Werte überprüfen und akzeptieren. Eine veränderliche Repository-Revision oder fehlende Quantisierung stoppt den Assistenten, bevor ein Download beginnt.

Laufzeitinstallation, GGUF-Downloads, Verifizierung, Registrierung und Funktionstests werden asynchron ausgeführt, sodass der JavaFX-Dialog weiterhin reagiert. Die Fortschrittsansicht benennt das aktuelle Modell und die aktuelle Phase. **Installation abbrechen** stoppt an einer sicheren Grenze und behält fortsetzbare `.part`-Dateien bei, während **Wiederholen** einen fehlgeschlagenen oder abgebrochenen Lauf mit den überprüften Auswahlen neu startet.

Die vom Hub gemeldete Kontextlänge bleibt zum Vergleich sichtbar, aber ein neues lokales Modell beginnt mit dem konservativen Laufzeitkontext von 4.096 Token. Erhöhen Sie ihn später unter **Konfigurieren** nur unter Berücksichtigung der zusätzlichen RAM/VRAM-Nutzung.

Nachdem jeder ausgewählte GGUF heruntergeladen und registriert wurde, sendet der Assistent eine echte lokale Chat-Abschlussanfrage oder, für ein reines Einbettungsmodell, eine echte Einbettungsanfrage über die installierte Laufzeit. Erst nachdem alle Tests bestanden wurden, werden die ausgewählten Text-/Codierungsprofil-IDs und die RAG-Einbettungsmodell-ID atomar in `global-settings.xml` gespeichert. Es erstellt ein wiederverwendbares **Lokal: …** eingebettetes Profil, wenn ein ausgewähltes Chat-Modell keines hat, und macht das erste derartige Profil nur dann zum Standard, wenn kein Standard vorhanden ist. Ein fehlgeschlagener Test führt nicht zu einer erfolgreichen Abschlussseite oder zur Beibehaltung teilweise aktualisierter Rollenzuweisungen.

## Signiertes Modell und prompter Katalog

korTTY enthält immer einen kleinen Bootstrap-Katalog mit den Empfehlungen zur Speicherschicht und der Erkennung der Eingabeaufforderungsfamilie, die für die Offline-Einrichtung erforderlich sind. Alle fünf gebündelten Modellempfehlungen enthalten einen konkreten Hugging Face-Commit mit 40 Zeichen, sodass selbst der Bootstrap nie einen veränderlichen Repository-Kopf auflöst, wenn der Assistent später Metadaten oder GGUF-Dateien abruft. Offizielle Builds können diese Daten unabhängig von der Anwendung über `model-prompt-catalog-v1.json` und die separate Ed25519-Signatur `model-prompt-catalog-v1.sig` aktualisieren.

Bei der ersten Verwendung von Empfehlungen oder der automatischen Eingabeaufforderungserkennung lädt korTTY sofort den letzten signaturverifizierten Cache und führt höchstens eine Hintergrundaktualisierung über den festen HTTPS-Stabilkanal durch. Der Katalog kann empfohlene Modell-IDs, feste Revisionen, Quantisierungen, Rollen, RAM-Schwellenwerte und Reihenfolge sowie die Zuordnung von Modellnamen-Tokens zu integrierten Eingabeaufforderungsvoreinstellungen aktualisieren. Es kann keinen beliebigen ausführbaren Code einschleusen oder die integrierten Aktions-, Sicherheits-, JSON- oder Codeausgabeverträge von korTTY ersetzen.

Die genauen Katalogbytes werden vor der strikten Schema-v1-Analyse überprüft und unbekannte Schemafelder werden abgelehnt. Bei einem fehlgeschlagenen Download, einer ungültigen Signatur oder einem ungültigen Katalog bleibt der letzte gültige Cache unberührt. Wenn der Anwendungsbuild kein gültiges öffentliches Vertrauensstammverzeichnis für den Katalog hat, führt korTTY keine Katalognetzwerkanfrage aus, vertraut keinem vorhandenen Cache und verwendet nur den integrierten Bootstrap.

Jeder Katalog trägt auch eine positive monotone Folge. Während der Aktualisierung lehnt korTTY einen signierten Katalog ab, dessen Sequenz älter als der zuletzt akzeptierte Cache oder Bootstrap ist, und lehnt einen Katalog gleicher Sequenz mit einer anderen Version ab. Ein neu akzeptierter Katalog mit der höchsten Sequenz muss in den Atomcache geschrieben werden, bevor er aktiv wird; Der geschützte Promotion-Workflow erfordert separat, dass jede offizielle Sequenz streng größer als die Sequenz in der neuesten veröffentlichten Version sein muss, wodurch verhindert wird, dass ein korrekt signierter älterer Katalog veraltete Empfehlungen wiedergibt.

## Suchen und Herunterladen von GGUF-Modellen

Der Hugging Face-Browser durchsucht GGUF-Repositories und zeigt Repository, Architektur, verfügbare Quantisierungen, Lizenz, ausgewählte Quantisierungsgröße, Kontextlänge und eine Hardwareschätzung an. Die leichtgewichtige Suchantwort enthält Dateinamen, aber keine verlässlichen Dateigrößen, sodass korTTY automatisch das erste Ergebnis auswählt und **Wird ermittelt…** anzeigt, während es genaue Metadaten für die unveränderliche Revision dieses Repositorys abruft. Wenn Sie eine andere Zeile auswählen, wiederholt korTTY diese Suche und aktualisiert dann sowohl **Größe** als auch **Hardwareschätzung**. Die gewählte Quantisierung bleibt während dieser Aktualisierung ausgewählt. Projektor-, Quantisierungsmatrix- und spekulative Dekodierungshilfs-GGUFs sind von den herunterladbaren Sprachmodelloptionen ausgeschlossen. Die Ergebnisse verwenden die Cursor-Paginierung des Hubs. **Weitere laden** setzt die gleiche Suche fort, ohne frühere Ergebnisse zu verwerfen. Wählen Sie ein Repository und eine Quantisierung aus, überprüfen Sie dessen Lizenz und Größe und wählen Sie dann **Herunterladen und installieren**.

korTTY installiert nur eine unveränderliche 40-Zeichen-Repository-Revision mit genauen GGUF-Metadaten. Für Downloads werden `.part`-Dateien, Freiraumprüfungen, HTTP `Range` und `If-Range`, Fortschritt, Geschwindigkeit und ETA, SHA-256-Verifizierung und mehrteilige GGUF-Reihenfolge verwendet. Sie können den Vorgang anhalten, fortsetzen oder abbrechen. Bei einer Stornierung bleiben die Teildaten erhalten, die für einen späteren Lebenslauf benötigt werden. Ein nicht angeheftetes Repository oder eine fehlende Dateiprüfsumme werden abgelehnt, anstatt veränderbare Inhalte stillschweigend zu installieren.

## Vorhandene GGUF-Dateien importieren

Wählen Sie **GGUF importieren**, wählen Sie eine oder mehrere `.gguf`-Dateien und dann einen Modus:

| Modus | Verhalten |
| --- | --- |
| **Verwaltete Kopie** | Kopiert den GGUF mithilfe einer temporären Datei und atomarer Aktivierung in das Modellverzeichnis von korTTY, sofern das Dateisystem dies unterstützt. |
| **Externe Referenz** | Registriert den ursprünglichen Pfad. Durch das Verschieben oder Löschen dieser Datei ist das Modell nicht mehr verfügbar. Durch das Entfernen der Registrierung wird die externe Datei niemals gelöscht. |

Außerdem muss eine kompatible, verifizierte `llama-server`-Laufzeitumgebung installiert sein. Laufzeitpakete befinden sich separat unter `~/.kortty/llm/runtime/` und sind nicht Teil des Installationsprogramms der Basisanwendung. Wenn Sie das erste Modell herunterladen oder importieren, bietet korTTY an, die passende signierte stabile Laufzeit zu installieren, bevor Sie fortfahren; Sie können auch **Laufzeit installieren** unter **Lokale Modelle** auswählen. Der Modellmanager akzeptiert keine beliebige ausführbare Datei als Ersatz für das verifizierte Paket.

## Modelle konfigurieren und ausführen

Wählen Sie ein installiertes Modell aus und wählen Sie **Konfigurieren**. Die verfügbaren Einstellungen sind bewusst typisiert und begrenzt; Beliebige Serverargumente werden nicht akzeptiert. Beim Speichern zuerst wird der Laufzeitmanager aufgefordert, das Modell zu stoppen. Wenn dieses Modell eine Anfrage verarbeitet, lehnt korTTY die Änderung ab und lässt sowohl den laufenden Sidecar als auch die persistente Konfiguration unberührt; Versuchen Sie es erneut, nachdem die Anfrage abgeschlossen ist.

| Einstellung | Werte | Standard |
| --- | --- | --- |
| Backend | Auto, CPU, Metall, Vulkan | Auto |
| Kontextgröße | 512–2.097.152 Token | 4.096 |
| CPU-Threads | 0–1.024; `0` bedeutet automatisch | automatisch |
| GPU-Schichten | -1–10.000; `-1` bedeutet automatisch | automatisch |
| Entladen nach | 1–1.440 Minuten oder **Niemals** | 10 Minuten |

Das Backend pro Modell beschreibt, wie dieses Modell ausgeführt werden soll. In **AI Manager > Local AI** steuert **Bevorzugtes Laufzeit-Backend**, welches signierte native Paket installiert und aktualisiert wird: macOS bietet Auto, CPU und Metal; Windows/Linux bieten Auto, CPU und Vulkan. **Automatisch (aktives Backend beibehalten)** behält das bereits aktive Backend während Aktualisierungen bei; Für eine Erstinstallation wählt es Metal auf macOS und CPU woanders aus.

Verwenden Sie die Mehrfachauswahl und **Auswahl starten**, um mehrere verschiedene Modelle gleichzeitig zu laden. Profile, die auf dasselbe installierte Modell und dieselbe Laufzeitkonfiguration verweisen, teilen sich einen authentifizierten Sidecar. Die Tabelle meldet `STOPPED`, `STARTING`, `LOADING`, `READY`, `BUSY`, `SLEEPING` oder `FAILED`.

Wenn ein ausgewähltes Metal- oder Vulkan-Modell ein anderes Paket als die aktive Laufzeit erfordert, bietet korTTY an, das passende signierte Paket herunterzuladen, zu überprüfen und zu aktivieren, ohne aktuelle Anfragen zu unterbrechen. Modelle, die inkompatible GPU-Laufzeiten erfordern, müssen separat mit dem passenden bevorzugten Backend gestartet werden.

Nach der konfigurierten Leerlaufzeit gibt llama.cpp die Modelltensoren aus RAM/VRAM frei und korTTY markiert das Modell als schlafend, während der Lightweight-Prozess beibehalten wird. Die nächste Anfrage erwirbt eine Laufzeitmiete und aktiviert das Modell automatisch. **Ausgewählter Stopp** beendet nur inaktive Beiwagen; Eine aktive Generierung wird niemals durch eine Modellentfernung oder eine normale Stoppanforderung unterbrochen.

## Text-, Codierungs-, Übersetzungs- und Einbettungsrollen

Wählen Sie unter **AI Manager > Lokale KI** separate Profile für **Text und Übersetzung** und **Codierung**. Wenn Sie eine der beiden Optionen auf **Standard-KI-Profil verwenden** belassen, bleibt der normale Fallback erhalten. Terminalzusammenfassung, Problemlösung, Fragen, Beschreibungen und Übersetzung verwenden die Textrolle; Für die Generierung, Vervollständigung, Analyse, Sicherheitskorrekturen und Workflow-Generierung von Snippets wird die Coding-Rolle verwendet.

Die Profilauflösung folgt der spezifischsten verfügbaren Auswahl: einem explizit ausgewählten Profil, dann ggf. einem sicherheitsspezifischen Profil oder Verbindungsprofil, dann der Text-/Codierungsrolle und dann dem Standardprofil. Der gleiche Rollenmechanismus funktioniert mit eingebetteten, Remote-HTTP- oder lokalen CLI-Profilen; Eine Rolle erzwingt kein lokales Modell.

Für die dynamische UI-Übersetzung kann **Lokales AI-Textprofil** unter **Konfiguration > Globale Einstellungen > Übersetzung** verwendet werden. Dieser Pfad sendet die Übersetzungsanforderung an das zugewiesene eingebettete Textprofil und erfordert keinen Übersetzungsanbieter-API-Schlüssel.

Die **RAG-Einbettungsmodell-ID** identifiziert das installierte lokale Modell, das zur Vektorisierung von Wissensspeicherdokumenten und -suchen verwendet wird. Verwenden Sie ein dediziertes Einbettungs-GGUF anstelle eines Chat-Modells, es sei denn, dieses Modell unterstützt explizit die Einbettungsroute und die konfigurierten Vektordimensionen.

## Prompte Optimierung

Jedes KI-Profil verfügt über eine Voreinstellung für **Prompte Optimierung**. **Auto (Modellerkennung)** verwendet die Modellnamenzuordnung des verifizierten Katalogs, dessen Bootstrap Qwen-, DeepSeek-, Mistral/Mixtral-, Gemma-, Phi-, GPT-OSS- und Llama-Namen erkennt; **Allgemein** fügt keine familienspezifische Anleitung hinzu. Sie können auch jede Familienvoreinstellung erzwingen, wenn ein Modellname ungewöhnlich ist.

Voreinstellungen fügen kurze Kompatibilitätsanweisungen nach dem Aktionsvertrag und den KI-Fähigkeiten von korTTY hinzu und behalten gleichzeitig die bestehenden strengen JSON-, Code-Payload- und Sicherheitsanforderungen bei. Die GGUF-Chat-Vorlagen selbst bleiben in der Verantwortung von llama.cpp. Die Voreinstellung fordert unterstützte Modelle auf, nur das angeforderte Endformat zurückzugeben und Argumentationsspuren aus JSON-/Code-Antworten fernzuhalten.

## Runtime-Isolation und Updates

Jede geladene Konfiguration startet `llama-server` auf `127.0.0.1` mit einem zufälligen Port und einem pro Prozess generierten API-Schlüssel. korTTY übergibt einen festen Modellpfad, entfernt geerbte `LLAMA_ARG_*`- und Hugging Face-Token-Überschreibungen und startet den angehefteten Server im Offline-Modus sowie deaktivierter Web-Benutzeroberfläche, Agent, UI-MCP-Proxy und Slot-Endpunkt. Chat und Einbettungen verwenden authentifizierte OpenAI-kompatible lokale Routen.

Der Laufzeit-Build ist unabhängig vom Anwendungsinstallationsprogramm. `build.gradle.kts` pinnt das Upstream-Tag llama.cpp, das vollständige Commit, das Quellarchiv SHA-256, die API-Vertragsversion und die korTTY-Paketrevision. Release CI erstellt CPU-, Metal- und Vulkan-Varianten für die unterstützte Betriebssystem-/Architekturmatrix und startet jede ausführbare Datei; Das Linux x86_64 CPU-Referenzpaket übt zusätzlich den vollständigen authentifizierten Serververtrag aus. Unveränderliche Deskriptoren werden nur durch eine explizite menschliche Werbeaufgabe veröffentlicht. CUDA ist kein v1-Backend.

Das Aktualisierungsformat verwendet die getrennte Ed25519-Signatur `runtime-index-v1.sig` über die exakten Bytes von `runtime-index-v1.json`. Jeder Eintrag bindet die Laufzeit-ID, das Upstream-Tag und den Commit, die API-Vertragsversion, die korTTY-Mindestversion, die Plattform, die Architektur, das Backend, die komprimierte Größe, SHA-256, die HTTPS-URL, den ausführbaren Pfad und den Sperrstatus. Ein Release-Build enthält den öffentlichen Verifizierungsschlüssel; Ein fehlender oder ungültiger Vertrauensstamm, eine ungültige Indexsignatur, Paketgröße oder ein ungültiger Paket-Hash stoppt den Vorgang, bevor nicht vertrauenswürdiger Code aktiviert wird.

Ein Paket wird neben der aktiven Version installiert und vor der Aktivierung lokal mit einem begrenzten `llama-server --version`-Start überprüft. Die Aktivierung und die atomare Neubindung registrierter Modelle erfolgen nur, während alle lokalen Rückschlüsse inaktiv sind. Der Kandidat bleibt dann **bis zum ersten Start**, bis ein echter GGUF-gestützter Server seine authentifizierte API erreicht: Bei Erfolg wird er in den fehlerfreien Verlauf befördert, während der erste fehlgeschlagene echte Start die neueste, nicht widerrufene vorherige Laufzeit wiederherstellt, betroffene Modelle erneut bindet, das fehlgeschlagene Paket entfernt und die Laufzeitverwaltung neu startet. Die beiden neuesten fehlerfreien Installationen bleiben erhalten und ein Paket, das während der aktiven Arbeit bereitgestellt wird, wird nach Abschluss der Anforderungen erneut versucht. Release CI führt vor der Veröffentlichung die tiefergehenden authentifizierten Chat-, Einbettungs-, JSON-Schema-, Sleep/Wake- und Parallel-Sidecar-Vertragstests durch.

Für die Prüfungen **Benachrichtigen** und **Stabile Updates automatisch installieren** wird sofort eine verifizierte Indexentfernung erzwungen. korTTY behält zunächst die widerrufenen Laufzeit-/Installations-IDs in einer dauerhaften Sperrliste bei und schreibt eine paketlokale Quarantänemarkierung, löscht dann den aktiven Zeiger, stoppt seine Sidecars, entfernt widerrufene Versionen aus dem Rollback-Verlauf und ersetzt betroffene Modellbindungen durch eine nicht ausführbare Markierung. Sowohl das Laufzeitinstallationsprogramm als auch jeder neue Prozessstart konsultieren diese Wächter, sodass eine unterbrochene Bereinigung oder ein veralteter `models.xml`-Eintrag die zurückgezogene ausführbare Datei nicht neu starten kann. Die lokale KI bleibt blockiert, bis ein kompatibler, signierter Ersatz installiert wird. Das Hauptfenster und der Status „Lokale Modelle“ geben Auskunft über die widerrufene Laufzeit und ob ein verifizierter Ersatz verfügbar ist. **Off** führt keine Netzwerkprüfung durch und kann daher erst dann von einer neu veröffentlichten Auszahlung erfahren, wenn der Benutzer die Überprüfung des signierten Index explizit überprüft oder aktiviert.

Die Option **llama.cpp-Laufzeitaktualisierungen** steuert die automatische Prüfung, die mit korTTY gestartet wird und wann immer die Einstellung gespeichert wird:

| Richtlinie | Startverhalten |
| --- | --- |
| **Aus** | Führt keine Netzwerkanforderung zur Laufzeitaktualisierung durch. Eine nicht widerrufene installierte Laufzeit bleibt nutzbar, während jede bereits lokal persistente Entnahme erzwungen bleibt. |
| **Benachrichtigen** (Standard) | Überprüft den signierten stabilen Index und zeigt eine Benachrichtigung an, wenn ein kompatibles Paket verfügbar ist, ohne es automatisch zu installieren. Eine verifizierte Auszahlung wird sofort erzwungen und blockiert das aktive Paket. |
| **Stabile Updates automatisch installieren** | Lädt ein kompatibles stabiles Paket herunter, überprüft, installiert und aktiviert es, einschließlich eines sicheren Ersatzes für eine zurückgezogene aktive Version; Aktive Inferenz verzögert die normale Aktivierung, anstatt unterbrochen zu werden. |

Die Laufzeitzeile in **Lokale Modelle** zeigt den aktuellen Update- oder Installationsstatus. Die Aktion lautet **Runtime installieren**, wenn kein verifiziertes Paket aktiv ist, andernfalls **Runtime-Update prüfen/installieren**. Diese explizite Aktion prüft und installiert den stabilen Kanal, selbst wenn die gespeicherte automatische Richtlinie **Aus** oder **Benachrichtigen** ist. Laufzeitkandidaten werden durch einen täglichen Workflow entdeckt, die Heraufstufung bleibt jedoch eine bewusste, überprüfte Release-Aktion, anstatt jedes Upstream-Tag automatisch zu übernehmen.

## Dateien und Sicherungsverhalten

| Pfad | Zweck | In einem korTTY-Backup enthalten? |
| --- | --- | --- |
| `~/.kortty/global-settings.xml` | Eingebettete Profile, Text-/Codierungszuweisungen, Einbettungsmodell-ID, Laufzeit-Backend/Update-Richtlinie, verschlüsseltes Hugging Face-Token | Ja |
| `~/.kortty/llm/models.xml` | Lokale Modellregistrierungen und typisierte Laufzeiteinstellungen | Ja |
| `~/.kortty/llm/models/` | Verwaltete GGUF-Gewichte | Nein; Laden Sie sie herunter oder kopieren Sie sie erneut |
| `~/.kortty/llm/runtime/` | Regenerierbare llama.cpp-Pakete und aktive Paketmetadaten | Nein; Installieren Sie ein kompatibles Paket | neu
| `~/.kortty/llm/catalog/last-valid-catalog-v1.json` | Regenerierbarer, signaturverifizierter Modell-/Prompt-Katalog-Cache | Nein; korTTY kehrt zum Bootstrap zurück und aktualisiert ihn erneut |
| `~/.kortty/llm/run/` | Temporäre Beiwagenschlüssel und Protokolle | Nein |

## Fehlerbehebung

**Es ist keine llama.cpp-Laufzeitumgebung installiert**
: Öffnen Sie **AI > AI Manager > Lokale Modelle** und wählen Sie **Laufzeit installieren** oder akzeptieren Sie die Installationsaufforderung, wenn Sie ein Modell herunterladen oder importieren. korTTY lädt das passende signierte stabile Paket herunter; Das Anwendungsinstallationsprogramm enthält absichtlich keine native Laufzeit.

**Eine signierte Laufzeitprüfung oder Installation schlägt fehl**
: Bestätigen Sie, dass dieser korTTY-Build den offiziellen öffentlichen Schlüssel des Laufzeitkanals enthält, dass HTTPS-Zugriff auf den stabilen Index und das Paket verfügbar ist und dass die Plattform/Backend-Kombination veröffentlicht ist. korTTY schlägt fehl, geschlossen zu werden, anstatt einen fehlenden Vertrauensstamm, eine ungültige Signatur, eine nicht übereinstimmende Prüfsumme, einen inkompatiblen API-Vertrag oder eine Laufzeitquarantäne zu umgehen.

**Die Laufzeit wird als widerrufen gemeldet**
: Öffnen Sie **AI > AI Manager > Lokale Modelle** und installieren Sie den angebotenen verifizierten Ersatz. Entfernen Sie nicht die Paketmarkierung und bearbeiten Sie `models.xml` nicht: Die dauerhafte Sperrliste blockiert weiterhin die Installation und widerrufene Versionen sind absichtlich nicht für ein Rollback oder eine Neuinstallation geeignet. Wenn kein kompatibler Ersatz aufgeführt ist, bleibt die lokale KI nicht verfügbar, bis der stabile Kanal einen für diese Plattform/dieses Backend veröffentlicht.

**Eine neue Laufzeit wird beim ersten Modellstart zurückgesetzt**
: Die einfache `--version`-Prüfung wurde bestanden, aber der erste echte GGUF-gestützte authentifizierte API-Start schlug fehl. korTTY stellt das neueste fehlerfreie, nicht widerrufene Paket wieder her, sofern verfügbar, und meldet **Rollback**; Überprüfen Sie den Fehler „Lokale Modelle“, die Modell-/Backend-Kompatibilität und die Speichereinstellungen, bevor Sie das Update erneut versuchen.

**Ein Modell verbleibt in `FAILED`**
: Stellen Sie sicher, dass GGUF und die ausführbare Datei noch vorhanden sind, dass die ausführbare Datei ausführbar ist und dass das ausgewählte Backend auf diesem Computer verfügbar ist. Reduzieren Sie die Kontextgröße oder gleichzeitige Modelle, wenn der System- oder GPU-Speicher nicht ausreicht.

**Der Setup-Funktionstest schlägt nach der Installation fehl**
: Der GGUF bleibt registriert, sodass Sie ihn einsehen können. Bestätigen Sie, dass das passende signierte Laufzeit-Backend aktiv ist, reduzieren Sie den Modellkontext oder die GPU-Ebenen, wenn der Speicher knapp ist, und versuchen Sie es erneut, indem Sie das Modell starten. Einbettungstests erfordern zusätzlich lesbare GGUF-Einbettungsdimensionsmetadaten.

**Ein geschlossenes Hugging Face-Repository gibt einen Autorisierungsfehler zurück**
: Akzeptieren Sie die Repository-Bedingungen für Hugging Face, entsperren Sie den korTTY-Master-Passwort-Tresor und speichern Sie ein autorisiertes Token unter **AI Manager > Lokale KI**. Das Token wird nur an den vertrauenswürdigen Hugging Face-Host gesendet, nicht an umgeleitete Speicherhosts.

**Ein Download kann nicht fortgesetzt werden**
: Wenn sich das ETag oder die unveränderlichen Dateimetadaten des Repositorys geändert haben, startet korTTY diese Datei neu, anstatt inkompatible Bytes anzuhängen. Bei einer Prüfsummenabweichung wird die ungültige Teildatei gelöscht.
