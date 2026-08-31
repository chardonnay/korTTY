---
title: Übersetzung
---

# Übersetzung

Konfigurieren Sie die dynamische Übersetzung der Benutzeroberfläche von korTTY mithilfe externer Übersetzungs-APIs. Auf dieser Registerkarte können Sie einen Übersetzungsanbieter auswählen, sich mit seiner API authentifizieren und Sprachdateien generieren, um die Benutzeroberfläche in Ihrer Zielsprache anzuzeigen. Öffnen über **Konfiguration → Globale Einstellungen → Übersetzung**; in `~/.kortty/global-settings.xml` gespeichert.

![Translation settings tab](../../assets/screenshots/settings/translation.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Systemsprache | Text | – | Systemgebietsschema | – |
| Übersetzungs-API | Dropdown-Liste | Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex Translate, lokales AI-Textprofil | Google Translate | `translationApiProvider` |
| AI-Profil | Dropdown-Liste | „Standard (lokales Textprofil)“ plus jedes konfigurierte AI-Profil | Standard (lokales Textprofil) | – |
| API-Schlüssel | Text | – | – | `encryptedTranslationApiKey` |
| API-URL (optional) | Text | – | – (null = Anbieterstandard verwenden) | `translationApiUrl` |
| Azure-Region (optional) | Text | – | – (null = globale Ressource) | `translationApiRegion` |
| API-Verbindung testen | Schaltfläche | – | – | – |
| Zielsprache | Dropdown-Liste | Systemgebietsschema und verfügbare Gebietsschemata (Gebietsschemaobjekte) | Systemgebietsschema | – |
| Sprachdatei generieren | Schaltfläche | – | – | – |
| Generierte Sprachen | Liste | Verfügbare dynamisch übersetzte Sprachdateien | — | — |
| Schaltfläche „Löschen“ | | (entfernt die ausgewählte generierte Sprachdatei) | – | – |
| Veraltete Schaltfläche „|“ neu generieren | (sichtbar, wenn veraltete Dateien vorhanden sind) | – | – |

!!! note
    **Speicherung des API-Schlüssels:** Der API-Schlüssel wird mit Ihrem Master-Passwort verschlüsselt und sicher in `global-settings.xml` gespeichert. Wenn der Master-Passwort-Tresor gesperrt ist, erhalten Sie eine Warnung und der Schlüssel wird im Feld aufbewahrt, bis Sie den Tresor entsperren oder in den Einstellungen ein Master-Passwort festlegen können.

!!! note
    **Generierte Sprachen:** In der Liste „Generierte Sprachen“ werden Sprachdateien angezeigt, die über die Schaltfläche „Sprachdatei generieren“ erstellt wurden. Jede generierte Datei entspricht einer dynamisch übersetzten Benutzeroberfläche in dieser Zielsprache. Verwenden Sie die Schaltfläche „Löschen“, um eine Sprachdatei zu entfernen, oder verwenden Sie „Veraltet neu generieren“, um Dateien, die mit einer älteren App-Version erstellt wurden, so zu aktualisieren, dass sie neu hinzugefügte Übersetzungsschlüssel enthalten.

## Provider-Anmeldeinformationen und Endpunkte

Jeder Schlüsselanbieter erwartet seine eigene Art von Anmeldeinformationen im Feld **API-Schlüssel**. Die **API-URL**
Das Feld bleibt leer, es sei denn, Sie weisen korTTY absichtlich auf einen anderen Host hin – einen selbstgehosteten
LibreTranslate, ein regionaler Azure-Endpunkt oder ein Proxy.

| Anbieter | API-Version korTTY-Aufrufe | Was drin ist **API-Schlüssel** | Standardendpunkt |
| --- | --- | --- | --- |
| Google Translate | Cloud Translation v2 (Basic) | Ein Google Cloud API-Schlüssel mit aktivierter Cloud Translation API | `https://translation.googleapis.com/language/translate/v2` |
| DeepL | DeepL API v2 | Ihr DeepL-Authentifizierungsschlüssel, gesendet als `DeepL-Auth-Key` | `https://api.deepl.com/v2/translate` oder `https://api-free.deepl.com/v2/translate` für einen kostenlosen Schlüssel |
| LibreTranslate | LibreTranslate `/translate` | Optional; Wird von öffentlichen Instanzen benötigt, die Quoten erzwingen | `https://libretranslate.com` |
| Microsoft Translator | Azure AI Translator v3.0 | Der Azure Translator-Ressourcenabonnementschlüssel | `https://api.cognitive.microsofttranslator.com` |
| Yandex | Yandex Cloud Translate v2 | Ein Yandex Cloud **Dienstkonto-API-Schlüssel**, gesendet als `Api-Key` | `https://translate.api.cloud.yandex.net/translate/v2` |

!!! warning "Yandex: Die v1.5-API ist nicht mehr verwendbar"
    Yandex hat die Ausgabe von Schlüsseln für die alte Translate API v1.5 (`translate.yandex.net/api/v1.5`) eingestellt und
    die bereits im Umlauf befindlichen kostenlosen Schlüssel abgeschaltet. Der Gastgeber antwortet immer noch, aber Sie können keinen Schlüssel finden
    Erhalten Sie Authentifizierungen dagegen. Erstellen Sie ein Dienstkonto in der Yandex Cloud-Konsole und weisen Sie ihm zu
    `ai.translate.user`-Rolle, stellen Sie einen API-Schlüssel für dieses Konto aus und speichern Sie diesen Schlüssel hier. Verlassen
    **API-URL** leer: Eine Adresse, die immer noch auf den v1.5-Host verweist, wird ignoriert und korTTY schreibt eine
    Warnung im Protokoll, bis Sie sie löschen. Der Ordner wird durch das Dienstkonto impliziert, also nichts
    Sonst muss konfiguriert werden.

!!! note
    **DeepL Free vs Pro:** korTTY errät den Endpunkt anhand des Schlüssels – historisch gesehen nur kostenlose Schlüssel
    endet in `:fx` – und korrigiert sich einmal selbst, wenn sich herausstellt, dass der andere Endpunkt der richtige ist.
    Durch Festlegen der **API-URL** wird der Endpunkt explizit angeheftet und diese Korrektur deaktiviert.

!!! note
    **Regionale Azure-Ressourcen:** Die Zeile **Azure-Region** wird nur angezeigt, während *Microsoft Translator*
    ist ausgewählt, da nur dieser Anbieter es liest. Lassen Sie es für eine im erstellte Ressource leer
    *Globale* Region. Eine Ressource, die in einer bestimmten Region erstellt wurde – oder eine mit einer benutzerdefinierten Domäne oder einem
    Virtuelles Netzwerk – lehnt jeden Anruf ab, der seine Region nicht angibt. Geben Sie daher die angezeigte Region ein
    die Seite *Schlüssel und Endpunkt* der Ressource, zum Beispiel `germanywestcentral`. Eine benutzerdefinierte Domain
    Die Ressource benötigt außerdem ihren vollständigen Pfad in der **API-URL**:
    `https://<resource>.cognitiveservices.azure.com/translator/text/v3.0`.

## Lokale Übersetzung

Wählen Sie **Lokales KI-Textprofil**, um über ein KI-Profil statt über eine Übersetzungs-API zu übersetzen. API-URL und API-Schlüssel sind für diesen Anbieter deaktiviert, da das Profil seinen eigenen Endpunkt und seine eigenen Anmeldeinformationen mitbringt. Das Modell muss ein striktes JSON-`translations`-Array mit der gleichen Anzahl und Reihenfolge der Eingabezeichenfolgen zurückgeben. Ein Stapel, der fehlerhaft zurückkommt, wird erneut versucht und in zwei Hälften geteilt, anstatt den Lauf abzubrechen, und eine Zeichenfolge, die das Modell nie in verwendbarer Form zurückgibt, behält ihren englischen Text.

Das Dropdown-Menü **KI-Profil** daneben entscheidet, welches Profil die Arbeit erledigt:

- **Ein von Ihnen ausgewähltes Profil** wird immer verwendet, unabhängig vom Verbindungsmodus – ein eingebettetes llama.cpp- oder MLX-Modell, ein Cloud-Endpunkt wie Anthropic oder eine OpenAI-kompatible API oder ein lokales CLI-Profil. Sein API-Schlüssel wird auf die gleiche Weise aufgelöst, wie der Rest der Anwendung ihn auflöst. Daher muss der Master-Passwort-Tresor entsperrt werden, wenn das Profil ein verschlüsseltes Geheimnis benötigt.
- **Standard (lokales Textprofil)** verwendet das Profil, das der Rolle „Text/Übersetzung“ in **KI > KI-Manager** zugewiesen ist, und nur, wenn dieses Profil ein eingebettetes (lokales) Modell ausführt. Die Standardeinstellung greift nie automatisch auf ein Cloud-Profil zurück: Dieser Anbieter existiert für Personen, die ihre Schnittstellenzeichenfolgen nicht an einen externen Dienst senden können oder wollen. Wenn diese Rolle ein Cloud- oder CLI-Profil enthält, benennt korTTY es und leitet Sie zu dieser Dropdown-Liste weiter, anstatt mit einem allgemeinen Fehler fehlzuschlagen.

Dies spiegelt das Dropdown-Menü **KI-Profil** im Abschnitt „Guide-Übersetzung“ unten wider, sodass beide Übersetzungsjobs auf das gewünschte Modell verwiesen werden können – zum Beispiel ein kleines lokales Modell für die Schnittstellenzeichenfolgen und ein stärkeres Cloud-Modell für den Guide oder dasselbe Profil für beide.

!!! warning
    **Anmeldeinformationen:** Externe Anbieter benötigen ihren normalen API-Schlüssel, mit Ausnahme eines LibreTranslate-Endpunkts, der explizit ohne diesen konfiguriert ist. Lokale KI erfordert keinen Übersetzungsanbieterschlüssel, aber ein eingebettetes Modell muss sein GGUF/MLX-Modell und seine Laufzeit installiert haben, und der Master-Passwort-Tresor muss entsperrt werden, wenn das ausgewählte KI-Profil ein verschlüsseltes Geheimnis benötigt.

## Guide-Übersetzung

Ein zweiter, unabhängiger Job unten übersetzt den gebündelten Offline-Anleitung – dieselbe Website, zu der diese Seite gehört – in die oben ausgewählte Zielsprache, sodass das In-App-Fenster „Hilfe → Anleitung“ und seine Suche vollständig in dieser Sprache gelesen werden können und nicht nur die Schnittstellenbezeichnungen.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| AI profile | dropdown | "Default (text profile)" plus every configured AI profile | Default (text profile) | — |
| Estimate duration | button | — | — | — |
| Start translation | button | — | — | — |
| Cancel | button | (shown while a translation or a duration estimate is running) | — | — |
| Translated guides | list | Guide languages translated so far | — | — |
| Delete | button | (removes the selected translated guide, its assets and its search index) | — | — |

Picking a profile in the **AI profile** dropdown here always uses that profile, whatever its connection mode. Leaving it on **Default (text profile)** follows the Translation API selection above: with the provider set to **Local AI text profile**, the guide uses the profile assigned to the Text/translation role in KI-Manager — and only if that profile runs an embedded (local) model — while any other provider with a stored key translates the guide through that translation API instead.

**Start translation** with no guide profile picked requires a workable Translation API above: with the provider on a keyed service (for example Google Translate) and no API key stored, starting is refused with *"Please enter an API key."* — a picked AI profile always suffices on its own. If the run cannot start for another reason, the message names the cause: a picked profile whose model is not downloaded or selected, no guide profile and no API key, or no local text profile configured.

Deleting a translated guide removes its whole directory under `~/.kortty/guide/<language>/` — the translated pages, the staged theme assets, its search index and its resume checkpoint — after a confirmation prompt.

### Warum dies im Hintergrund läuft

Das Übersetzen der Anleitungs ist keine schnelle Angelegenheit: Die gebündelte Website enthält etwa 5.500 verschiedene Textteile, sobald wiederholte Navigation und Überschriften dedupliziert werden, und je nach Modell kann dies zwischen einigen Minuten und fast einem Tag dauern. Beim Starten wird weder das Einstellungsdialogfeld noch der Rest von korTTY blockiert:

- Die Übersetzung wird weiter ausgeführt, nachdem Sie die Einstellungen geschlossen, die Registerkarten gewechselt oder in Terminals weitergearbeitet haben.
- A small progress indicator — a bar, a percentage and an estimated time remaining — appears at the right end of korTTY's menu bar for as long as a guide translation is in progress, and disappears again once it finishes.
- While the run is active, the dialog shows a progress bar and a status line ("Translating… 42%"). **Cancel** stops the job at the next safe point rather than losing what has already been translated: progress is checkpointed to disk as it goes, so starting the same language again continues from where it left off instead of starting over.
- Die Fortschrittsanzeige im Dialog und **Abbrechen** gehören zum Einstellungsdialog, der den Lauf gestartet hat – ein späteres erneutes Öffnen von Einstellungen führt nicht zu einer erneuten Verknüpfung mit einem laufenden Auftrag, und **Übersetzung starten** meldet dann nur *„Eine Übersetzung der Anleitungs läuft bereits.“* Nach dem Schließen der Einstellungen wird der laufende Auftrag über den Beenden-Dialog unten angehalten (oder einfach zum Beenden gelassen).
- Wenn Sie versuchen, korTTY zu beenden, während eine Anleitungsübersetzung ausgeführt wird, wird in einem Dialogfeld angeboten, die Übersetzung anzuhalten und zu beenden oder korTTY geöffnet zu lassen. Wenn Sie sich für eine Pause entscheiden, bleibt der Kontrollpunkt für den nächsten Lauf bestehen.
- Nach der Installation eines korTTY-Updates, das den Inhalt der Anleitungs geändert hat, und wenn Sie bereits über eine übersetzte Anleitung für Ihre Sprache verfügen, bietet korTTY einmal pro Lauf an – ein paar Sekunden nach dem Öffnen des Fensters und nie, während eine Übersetzung bereits läuft –, um es auf den neuesten Stand zu bringen; **Jetzt aktualisieren** öffnet diese Registerkarte „Einstellungen“, auf der Sie den Lauf selbst starten können. Da der Fortschritt durch den genauen englischen Text jedes Stücks kontrolliert wird, werden bei der Aktualisierung nur Sätze neu übersetzt, die sich in der Veröffentlichung tatsächlich geändert haben – alles andere wird unverändert wiederverwendet.

### Warum ein Reasoning-Modell schlecht passt

Einige KI-Modelle sind so konzipiert, dass sie „laut nachdenken“, bevor sie antworten. Dabei schreiben sie vor der eigentlichen Ausgabe eine erweiterte Gedankenkette in ihre Antwort ein, eine Technik, die auf schwierige Probleme wie Mathematik oder Codierung abgestimmt ist. Dieses Reasoning ist nicht Teil der Übersetzung, aber das Modell muss sie trotzdem generieren, und das Generieren von Text ist der langsamste Teil bei der Ausführung eines lokalen Modells. Gemessen an der eigenen Anleitung dieses Projekts erzeugte ein Reasoning-Modell etwa **4,4 Abschluss-Tokens für jedes Eingabe-Token** – ungefähr 4.400 Reasoning-Tokens, um 1.000 Zeichen Prosa zu übersetzen. Auf identischer Hardware war dies der Unterschied zwischen einem Übersetzungslauf von etwa einer Stunde und einem von sechs Stunden oder mehr.

If the **AI profile** you pick for guide translation is a reasoning model, korTTY warns you before starting or estimating, naming the model and letting you continue anyway or cancel. The check looks at the model's name (publishers of reasoning models advertise it there — "reasoning", "thinking", "R1", "QwQ", "o1" and similar are recognized), so it costs nothing to run and may occasionally miss a differently-named reasoning model, but it will not falsely warn about a plain instruct model.

**Suitable models** are instruction-tuned ("instruct") chat models without a reasoning/thinking mode — the kind normally used for translation, summarization or rewriting rather than multi-step problem solving. For an embedded local profile, look for MLX or GGUF builds whose name includes "instruct" and not "reasoning", "thinking" or "R1", for example a Qwen2.5-Instruct or Phi-4-mini-instruct build at a size your hardware can run comfortably (4-bit quantized 7-8B models are a practical default on Apple Silicon). Larger instruct models generally translate more fluently at the cost of speed; the duration estimate below lets you compare before committing to a full run.

### Schätzung, wie lange eine vollständige Übersetzung dauern wird

**Geschätzte Dauer** misst das oben ausgewählte KI-Profil anhand einer realen, repräsentativen Stichprobe der Anleitungtextes und prognostiziert, wie lange die Übersetzung aller noch ausstehenden Elemente dauern würde, ohne sich auf die vollständige Ausführung festzulegen:

1. Es sendet zuerst eine kleine, zeitlich nicht festgelegte „Aufwärm“-Anfrage, damit die einmaligen Ladekosten eines lokalen Modells – die einmal für einen gesamten Lauf und nicht pro Stapel bezahlt werden – nicht mit der laufenden Übersetzungsgeschwindigkeit verwechselt und über jeden Stapel der Projektion hinweg multipliziert werden.
2. Es übersetzt dann einen echten, budgetgroßen Stapel Leittext und synchronisiert ihn. Hierbei handelt es sich um eine echte Übersetzung, nicht um eine Simulation: Die Stichprobe wird beibehalten, sodass diese Arbeit nicht wiederholt wird, wenn zuerst geschätzt wird und dann ein vollständiger Durchlauf gestartet wird.
3. From that single timed batch, it computes two projections for the remaining text — one assuming cost scales with the number of requests, one assuming it scales with the amount of text — and reports the resulting range together with how long the remaining pages and text would take. Reporting a range rather than one number reflects that a single sample cannot fully separate a model's fixed per-request overhead from its per-character cost; the estimate keeps that uncertainty visible rather than hiding it behind a single overly precise number.

If the sample fails outright — the AI profile is unreachable, misconfigured, or produced nothing usable — the estimate reports that as a connection problem instead of a duration, so it is not mistaken for "translation will be instant." And if nothing is left to translate, the estimate simply reports that the guide is already fully translated for this language.

!!! note
    Die obige Warnung zum Reasoning-Modell wird sowohl bei **Schätzdauer** als auch bei **Übersetzung starten** angezeigt, da das Ausführen einer Schätzung anhand eines Reasoning-Modells bereits Echtzeit dafür in Anspruch nimmt.
