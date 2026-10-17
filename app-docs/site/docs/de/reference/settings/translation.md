---
title: Übersetzung
---

# Übersetzung

korTTY kann seine eigene Oberfläche und die integrierte Anleitung in Ihre Sprache übersetzen. Dieser Tab enthält die **Übersetzungsprofile** (welcher Dienst oder KI-Modell übersetzt), den **Jetzt übersetzen**-Assistenten, der eine Übersetzung mit Schätzung, Fortschritt und Pause ausführt, sowie die Listen der generierten Sprachdateien und übersetzten Anleitungen. Öffnen Sie über **Konfiguration → Globale Einstellungen → Übersetzung**; gespeichert in `~/.kortty/global-settings.xml`.

![Translation settings tab](../../assets/screenshots/settings/translation.png)

## Übersetzungsprofile { #translation-profiles }

Ein Profil ist eine Möglichkeit der Übersetzung: eine Übersetzungs-API (Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex Translate) mit ihrem Schlüssel, Endpunkt und ihrer Region oder **KI-Übersetzung** über eines Ihrer KI-Profile. Sie können mehrere behalten, zum Beispiel eine günstige API für die Interface-Strings und ein lokales Modell für die Anleitung. Genau ein Profil ist das **Standard** (mit ★ in der Liste); es ist, was korTTY übersetzt, sofern dem Translate now-Assistenten nichts anderes mitgeteilt wird. Der Abschnittsheader verlinkt zu dieser Seite mit **In der Anleitung öffnen**.

Die Liste befindet sich links; der Editor des ausgewählten Profils rechts. **Einrichten…** öffnet den [Setup-Assistent](#setup-assistant), **Hinzufügen** erstellt ein Profil zum manuellen Ausfüllen, **Löschen** entfernt das ausgewählte (das Standardprofil wechselt zum ersten verbleibenden Profil).

| Feld | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Name | text | — | der Anbietername | `translationProfiles/translationProfile/name` |
| Übersetzungs-API | Dropdown | Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex Translate, KI-Übersetzung | Google Translate | `…/provider` |
| API-Schlüssel | Passwort | — | — | `…/encryptedApiKey` (verschlüsselt mit dem Master-Passwort) |
| API-URL (optional) | text | — | — (leer = Standard des Anbieters) | `…/apiUrl` |
| Azure-Region (optional) | text | — | — (Microsoft-Übersetzer only) | `…/apiRegion` |
| KI-Profil | dropdown | "Standard (lokales Text-Profil)" plus jedes KI-Profil | Standard | `…/aiProfileId` (KI-Übersetzung nur) |
| Als Standardprofil verwenden | toggle | — | das erste Profil | `defaultTranslationProfileId` |
| API-Verbindung testen | button | Übersetzt einen kurzen Text mit dem Profil | — | — |

!!! note
    **API-Schlüssel-Speicherung:** Ein von Ihnen eingegebener Schlüssel wird mit Ihrem Master-Passwort verschlüsselt, wenn Sie das Dialogfeld speichern. Wenn der Tresor gesperrt ist, zeigt korTTY eine Warnung mit einer Schaltfläche **Tresor entsperren…** an, hält das Dialogfeld offen und lässt den Schlüssel im Feld stehen; entsperren Sie ihn und speichern Sie erneut. Ein gespeicherter Schlüssel wird nie angezeigt; der Editor meldet *ein Schlüssel ist gespeichert* und ein neuer Eintrag ersetzt ihn.

!!! note
    **Ein Upgrade von einem einzelnen Anbieter:** frühere Versionen speicherten einen Anbieter, Schlüssel und URL. Beim ersten Start werden sie zu einem Profil umgewandelt, das nach dem Anbieter benannt ist (id `migrated-default`), welches auch das Standardprofil ist. Die alten Felder `translationApiProvider`, `encryptedTranslationApiKey`, `translationApiUrl` und `translationApiRegion` bleiben in der Datei erhalten und spiegeln immer das Standardprofil wider, sodass ein älteres korTTY weiterhin mit demselben Dienst übersetzt.

### Einrichtungsassistent { #setup-assistant }

**Einrichten…** führt Sie in vier Schritten durch ein neues Profil: **API oder KI** (ein Übersetzungsdienst mit einem Schlüssel, oder eines Ihrer KI-Profile), **Zugang** (der Anbieter, sein Schlüssel, optional URL und Azure-Region, oder das KI-Profil; ein Link öffnet den KI-Manager, wenn noch kein KI-Profil existiert), **Name** (mit *Als Standardprofil verwenden*), und **Test**, das einen kurzen Text übersetzt und Ihnen ermöglicht, abzuschließen, oder den Test zu überspringen und später aus der Liste zu testen. Das neue Profil erscheint in der Liste und übernimmt, wenn Sie es als Standardprofil festgelegt haben, die ★.

## Jetzt übersetzen { #translate-now }

**Jetzt übersetzen…** öffnet den Assistenten, der eine Übersetzung durchführt.  

- **Dauer abschätzen** übersetzt zunächst ein reales Beispiel: einen Batch von Interface-Strings und, für die Anleitung, einen budgetgerechten Batch an Anleitungstext. Aus seiner Dauer schätzt es die gesamte Laufzeit ab und zeigt das Ergebnis über der Fortschrittsleiste an. Das Beispiel ist eine echte Übersetzung, daher fallen für ein kostenpflichtiges API Gebühren an; die Anleitungsbeispiel wird gespeichert und vom Lauf wiederverwendet.
- **Starten** führt zuerst die Schnittstellentexte aus und startet anschließend die Anleitung, wenn beide ausgewählt sind. Das Dialogfeld zeigt an, welche Phase ausgeführt wird, einen Fortschrittsbalken, den Prozentsatz und die verbleibende Zeit.
- **Pause** stoppt den Lauf nach dem laufenden Batch (ein lokales Modell kann für einen Batch Sekunden bis Minuten benötigen) und wird zu **Fortsetzen**; die Zeit, die pausiert wurde, wird aus der verbleibenden Zeitabschätzung herausgerechnet. **Abbrechen** stoppt an der nächsten Batch-Grenze: die Interface-Strings werden am Ende auf einmal geschrieben, sodass ein abgebrochener Lauf keine halbübersetzte Sprachdatei hinterlässt, und die Anleitung behält ihren Checkpoint, sodass der nächste Lauf dort fortgesetzt wird, wo er aufgehört hat.
- Das Schließen des Dialogs stoppt eine laufende Übersetzung nicht; neben **Jetzt übersetzen…** auf dem Tab wird der Fortschritt angezeigt, und das erneute Öffnen des Dialogs verbindet sich erneut mit dem laufenden Job. Das Beenden von korTTY während die Anleitung übersetzt wird, fragt, ob sie pausiert werden soll.

Die generierten Dateien erscheinen in den beiden Listen unter dem Assistenten: **Generierte Sprachen** (die Interface-Sprachdateien unter `~/.kortty/i18n/`, mit **Löschen** und, für Dateien aus einer älteren Version, **Veraltete neu generieren**) und **Übersetzte Anleitungen** (die Sprachen unter `~/.kortty/guide/`, mit **Löschen**, das die Seiten, Assets, Suchindex und Checkpoint nach einer Bestätigung entfernt).

## Anbieter-Zugangsdaten und Endpunkte

Jeder gekennzeichneter Anbieter erwartet in dem Feld **API-Schlüssel** seine eigene Art von Berechtigung. Die **API-URL**
Das Feld bleibt leer, es sei denn Sie weisen korTTY absichtlich auf einen anderen Host — ein selbstgehostetes.
LibreTranslate, ein regionaler Azure-Endpunkt oder ein Proxy.

| Anbieter | API-Version, die von korTTY aufgerufen wird | Was kommt in **API-Schlüssel** | Standard-Endpunkt |
| --- | --- | --- | --- |
| Google Translate | Cloud-Übersetzung v2 (Basic) | Ein Google Cloud API-Schlüssel mit aktivierter Cloud Übersetzungs-API | `https://translation.googleapis.com/language/translate/v2` |
| DeepL | DeepL API v2 | Ihr DeepL-Auth-Key, gesendet als `DeepL-Auth-Key` | `https://api.deepl.com/v2/translate`, oder `https://api-free.deepl.com/v2/translate` für einen kostenlosen Key |
| LibreTranslate | LibreTranslate `/translate` | Optional; erforderlich von öffentlichen Instanzen, die Quoten durchsetzen | `https://libretranslate.com` |
| Microsoft-Übersetzer | Azure KI Translator v3.0 | Der Azure Translator-Ressourcen-Abonnementschlüssel | `https://api.cognitive.microsofttranslator.com` |
| Yandex | Yandex Cloud Translate v2 | A Yandex Cloud **Service-Account-API-Key**, gesendet als `Api-Key`; ein Wert, der mit `t1.` beginnt, wird stattdessen als Bearer-IAM-Token gesendet | `https://translate.api.cloud.yandex.net/translate/v2` |

!!! warning "Yandex: die v1.5 API ist nicht mehr nutzbar"
    Yandex hat die Ausgabe von Schlüsseln für die alte Translate API v1.5 (`translate.yandex.net/api/v1.5`) eingestellt und
    schalteten die freien Schlüssel bereits im Umlauf ab. Der Host antwortet noch, aber kein Schlüssel kann Sie.
    erhält sich gegen sie authentifiziert. Erstellen Sie ein Dienstkonto in der Yandex Cloud-Konsole, weisen Sie ihm das
    `ai.translate.user` Rolle, erteilen Sie einen API-Schlüssel für dieses Konto und speichern Sie diesen Schlüssel hier. Lassen Sie es
    **API-URL** empty: an address still pointing at the v1.5 host is ignored, and korTTY writes a
    Warnungen werden im Log protokolliert, bis Sie sie löschen. Der Ordner wird durch das Servicekonto impliziert, daher nichts.
    Else muss konfiguriert werden.

!!! note
    **DeepL Free gegen Pro:** korTTY errät den Endpunkt aus dem Schlüssel – historisch nur bei Free-Schlüsseln.
    endet in `:fx` — und korrigiert sich einmal, falls der andere Endpunkt herausstellt, dass er der richtige ist.
    Das Setzen von **API-URL** verankert den Endpunkt explizit und deaktiviert diese Korrektur.

!!! note
    **Regionale Azure-Ressourcen:** die **Azure-Region** Zeile erscheint nur während *Microsoft-Übersetzer*.
    wird ausgewählt, weil nur dieser Anbieter es liest. Lassen Sie es leer für eine Ressource, die im
    *Global* Region. Eine Ressource, die in einer bestimmten Region erstellt wurde — oder eine mit einer benutzerdefinierten Domain oder einer
    virtuelles Netzwerk — lehnt jeden Aufruf ab, der seine Region nicht nennt, also geben Sie die angezeigte Region ein auf
    die Seite *Schlüssel und Endpunkt* der Ressource, zum Beispiel `germanywestcentral`. Eine benutzerdefinierte Domäne
    Die Ressource benötigt ebenfalls ihren vollständigen Pfad in der **API-URL**:
    `https://<resource>.cognitiveservices.azure.com/translator/text/v3.0`.

## KI-Übersetzung { #local-translation }

Ein Profil mit **KI-Übersetzung** übersetzt über eines Ihrer KI-Profile statt einer Übersetzungs-API. Das Modell muss ein einziges, strenges JSON `translations`-Array mit derselben Anzahl und Reihenfolge der Eingabestrings zurückgeben; ein Batch, das fehlerhaft zurückkommt, wird erneut versucht und in der Mitte geteilt statt den Vorgang abzubrechen, und ein String, den das Modell nie in nutzbarer Form zurückgibt, behält seinen englischen Text.

Das Feld **KI-Profil** eines solchen Profils bestimmt, welches Modell die Arbeit erledigt:

- **Ein Profil, das Sie auswählen** wird immer verwendet, egal welcher Verbindungsmodus — ein eingebettetes llama.cpp-oder MLX-Modell, ein Cloud-Endpunkt wie Anthropic oder eine OpenAI-kompatible API, oder ein lokales CLI-Profil. Der API-Schlüssel wird auf die gleiche Weise wie der Rest der Anwendung aufgelöst, daher muss das Master-Passwort-Tresor entsperrt sein, wenn das Profil ein verschlüsseltes Geheimnis benötigt.
- **Standard (lokales Text-Profil)** verwendet das Profil, das der Rolle Text/Übersetzung im **KI > KI-Manager** zugewiesen ist, und nur wenn dieses Profil ein eingebettetes (lokales) Modell ausführt. Das Standardprofil fällt niemals automatisch auf ein Cloud-Profil zurück: diese Option ist für Personen gedacht, die ihre Interface-Strings nicht oder nicht an einen externen Dienst senden möchten. Wenn diese Rolle ein Cloud- oder CLI-Profil besitzt, sagt der Test und der Assistent dies aus und fordert Sie auf, ein Profil auszuwählen.

!!! warning
    **Zugangsdaten:** Übersetzungs-APIs benötigen ihren normalen API-Schlüssel, außer ein LibreTranslate-Endpunkt, der explizit ohne einen konfiguriert ist. KI-Übersetzung erfordert keinen Übersetzungsanbieter-Schlüssel, aber ein eingebettetes Modell benötigt sein GGUF/MLX-Modell und Runtime installiert, und das Master-Passwort-Vault muss entsperrt sein, wenn das ausgewählte KI-Profil ein verschlüsseltes Geheimnis benötigt.

## Anleitung übersetzen

Übersetzen der Anleitung — die gleiche Seite, zu der diese Seite gehört — macht das in-App-**Hilfe → Anleitung**-Fenster und dessen Suche in Ihrer Sprache lesbar statt nur die Interface-Labels. Es wird vom [Jetzt übersetzen](#translate-now)-Assistenten gestartet, wobei **Eingebaute Anleitung** ausgewählt ist; die **Dauer abschätzen**, Fortschrittsanzeige, **Pause** und **Abbrechen** gelten dafür. Der Lauf ist unabhängig von den Interface-Strings: die Strings brauchen Minuten, die Anleitung kann Stunden auf einem lokalen Modell benötigen.

Das Löschen einer übersetzten Anleitung entfernt ihr gesamtes Verzeichnis unter `~/.kortty/guide/<language>/` — die übersetzten Seiten, die gestagten Theme-Assets, ihren Suchindex und ihren Resume-Checkpoint — nach einer Bestätigungsaufforderung.

### Warum dies im Hintergrund läuft

Das Übersetzen der Anleitung ist keine schnelle Aufgabe: Die gebündelte Website enthält ungefähr 5.500 unterschiedliche Textstellen, sobald wiederholte Navigationen und Überschriften dedupliziert sind, und je nach Modell kann dies von wenigen Minuten bis zu einem Großteil eines Tages dauern. Der Start blockiert weder das Einstellungsdialogfeld noch den Rest von korTTY.

- Die Übersetzung läuft weiter, nachdem Sie Einstellungen schließen, Tabs wechseln oder weiterhin in Terminals arbeiten.
- Ein kleiner Fortschrittsindikator — ein Balken, ein Prozentwert und eine geschätzte verbleibende Zeit — erscheint am rechten Ende der Menüleiste von korTTY, solange eine Anleitungsübersetzung läuft, und verschwindet wieder, sobald sie beendet ist.
- Während der Ausführung zeigt der Translate now-Assistent eine Fortschrittsleiste, den Prozentsatz und die verbleibende Zeit. **Pause** hält die Ausführung nach dem aktuellen Batch an, **Abbrechen** stoppt sie am nächsten sicheren Punkt statt das bereits Übersetzte zu verlieren: der Fortschritt wird währenddessen auf die Festplatte checkpointed, sodass das gleiche Sprachpaket erneut gestartet den Fortschritt fortsetzt statt von vorne zu beginnen.
- Das Schließen des Assistenten oder des Einstellungsdialogs lässt den Auftrag weiterlaufen; der Übersetzungstab zeigt dessen Fortschritt neben **Jetzt übersetzen…**, und das erneute Öffnen des Assistenten verbindet sich erneut damit. **Starten** während ein Auftrag läuft meldet nur *"Eine Leitungsübersetzung läuft bereits."*
- Wenn Sie versuchen, korTTY zu beenden, während eine Anleitungsübersetzung läuft, bietet ein Dialog an, sie anzuhalten und zu beenden oder korTTY offen zu lassen; die Auswahl zum Anhalten lässt den Checkpoint für den nächsten Lauf bestehen.
- Nach der Installation eines korTTY-Updates, das den Inhalt der Anleitung geändert hat, und wenn Sie bereits eine übersetzte Anleitung für Ihre Sprache haben, bietet korTTY einmal pro Lauf – ein paar Sekunden nach dem Öffnen des Fensters und niemals während eine Übersetzung bereits läuft – an, sie auf den neuesten Stand zu bringen; **Jetzt aktualisieren** öffnet diesen Einstellungs-Tab, in dem Sie den Lauf selbst starten. Da der Fortschritt durch den genauen englischen Text jedes Abschnitts checkpointed wird, übersetzt das Update nur die Sätze, die tatsächlich in der Veröffentlichung geändert wurden – alles andere wird unverändert wiederverwendet.

### Warum ein Reasoning-Modell schlecht geeignet ist

Einige KI-Modelle sind darauf ausgelegt, „laut zu denken“, bevor sie antworten – sie schreiben eine ausführliche Gedankenkette in ihre Antwort, bevor die eigentliche Ausgabe erfolgt. Diese Überlegung ist nicht Teil der Übersetzung, aber das Modell muss sie trotzdem generieren, und das Erzeugen von Text ist der langsame Teil beim Ausführen eines lokalen Modells. In diesem Projekt wurde gemessen, dass ein solches Modell etwa **4,4 Fertigungstoken pro Eingabetoken** erzeugt – ungefähr 4 400 Tokens an Überlegungen, um 1.000 Zeichen Prosa zu übersetzen. Auf identischer Hardware bedeutete das den Unterschied zwischen einer Übersetzung von etwa einer Stunde und einer, die sechs Stunden oder länger dauerte.

Wenn das gewählte Übersetzungsprofil ein KI-Profil mit einem Reasoning-Modell ist, warnt korTTY Sie vor dem Start oder der Schätzung, nennt das Modell und lässt Sie entscheiden, ob Sie fortfahren oder abbrechen möchten. Die Prüfung erfolgt anhand des Namens des Modells (Verlage von Reasoning-Modellen bewerben es dort – „reasoning“, „thinking“, „R1“, „QwQ“, „o1“ und ähnliche werden erkannt), sodass keine Kosten entstehen, und es kann gelegentlich ein anders benanntes Reasoning-Modell übersehen werden; jedoch wird kein reines Instruktionsmodell fälschlicherweise gewarnt.

**Geeignete Modelle** sind instruction-tuned („instruct“) Chat-Modelle ohne einen Reasoning/Thinking-Modus – die Art, die üblicherweise für Übersetzungen, Zusammenfassungen oder Umschreibungen verwendet wird und nicht für mehrstufige Problemlösungen. Für ein eingebettetes lokales Profil suchen Sie nach MLX- oder GGUF-Builds, deren Name „instruct“ enthält und nicht „reasoning“, „thinking“ oder „R1“, zum Beispiel ein Qwen2.5-Instruct oder Phi-4-mini-instruct Build in einer Größe, die Ihre Hardware komfortabel ausführen kann (4-Bit-quantisierte 7–8B-Modelle sind ein praktischer Standard auf Apple Silicon). Größere Instruct-Modelle übersetzen in der Regel flüssiger, kosten jedoch an Geschwindigkeit; die unten stehende Dauerabschätzung ermöglicht es Ihnen, vor einer vollständigen Ausführung zu vergleichen.

### Schätzung, wie lange eine vollständige Übersetzung dauern wird.

**Dauer abschätzen** im Translate now Assistant misst das ausgewählte Übersetzungsprofil gegen eine echte, repräsentative Stichprobe des Textes der Anleitung und projiziert, wie lange die noch ausstehenden Übersetzungen dauern würden, ohne den vollständigen Durchlauf zu starten:

1. Es sendet zunächst eine kleine, unzeitlich gemessene „Warm-up“-Anfrage, damit die einmalige Ladekosten eines lokalen Modells — die nur einmal für einen gesamten Lauf gezahlt werden, nicht pro Batch — nicht fälschlicherweise als fortlaufende Übersetzungsrate interpretiert und auf jeden Batch der Projektion multipliziert werden.
2. Es übersetzt dann einen echten, budgetgerechten Batch von Anleitungstext und misst die Zeit. Dies ist eine echte Übersetzung, keine Simulation: Die Probe bleibt erhalten, sodass zunächst schätzen und anschließend einen vollständigen Durchlauf starten nicht die gleiche Arbeit wiederholen.
3. Aus dieser einzelnen zeitlich begrenzten Charge berechnet es zwei Projektionen für den verbleibenden Text – eine, die annimmt, dass die Kosten mit der Anzahl der Anfragen skalieren, und eine andere, die davon ausgeht, dass sie sich nach dem Textvolumen richtet – und gibt den resultierenden Bereich zusammen mit der geschätzten Dauer für die verbleibenden Seiten und Text an. Ein Bereich statt einer einzelnen Zahl zu melden, verdeutlicht, dass eine einzelne Probe nicht vollständig die feste pro-Anfrage-Überhead eines Modells von dessen pro-Zeichen-Kosten trennen kann; die Schätzung hält diese Unsicherheit sichtbar, anstatt sie hinter einer zu präzisen Zahl zu verbergen.

Falls die Probe vollständig fehlschlägt – das KI-Profil ist nicht erreichbar, falsch konfiguriert oder liefert nichts Nutzbares – meldet die Schätzung dies als Verbindungsproblem anstelle einer Dauer, sodass nicht fälschlicherweise angenommen wird, dass die Übersetzung sofort erfolgt. Und wenn nichts mehr zu übersetzen bleibt, gibt die Schätzung einfach an, dass die Anleitung bereits vollständig für diese Sprache übersetzt ist.

!!! note
    Die Warnung des Reasoning-Modells oben erscheint sowohl für **Dauer abschätzen** als auch für **Starten**, da das Ausführen einer Abschätzung gegen ein Reasoning-Modell bereits echte Zeit in Anspruch nimmt.
