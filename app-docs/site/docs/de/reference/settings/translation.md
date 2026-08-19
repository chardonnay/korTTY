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

## Lokale Übersetzung

Wählen Sie **Lokales KI-Textprofil**, um über ein KI-Profil statt über eine Übersetzungs-API zu übersetzen. API-URL und API-Schlüssel sind für diesen Anbieter deaktiviert, da das Profil seinen eigenen Endpunkt und seine eigenen Anmeldeinformationen mitbringt. Das Modell muss ein striktes JSON-`translations`-Array mit der gleichen Anzahl und Reihenfolge der Eingabezeichenfolgen zurückgeben. Ein Stapel, der fehlerhaft zurückkommt, wird erneut versucht und in zwei Hälften geteilt, anstatt den Lauf abzubrechen, und eine Zeichenfolge, die das Modell nie in verwendbarer Form zurückgibt, behält ihren englischen Text.

Das Dropdown-Menü **KI-Profil** daneben entscheidet, welches Profil die Arbeit erledigt:

- **Ein von Ihnen ausgewähltes Profil** wird immer verwendet, unabhängig vom Verbindungsmodus – ein eingebettetes llama.cpp- oder MLX-Modell, ein Cloud-Endpunkt wie Anthropic oder eine OpenAI-kompatible API oder ein lokales CLI-Profil. Sein API-Schlüssel wird auf die gleiche Weise aufgelöst, wie der Rest der Anwendung ihn auflöst. Daher muss der Master-Passwort-Tresor entsperrt werden, wenn das Profil ein verschlüsseltes Geheimnis benötigt.
- **Standard (lokales Textprofil)** verwendet das Profil, das der Rolle „Text/Übersetzung“ in **KI > KI-Manager** zugewiesen ist, und nur, wenn dieses Profil ein eingebettetes (lokales) Modell ausführt. Die Standardeinstellung greift nie automatisch auf ein Cloud-Profil zurück: Dieser Anbieter existiert für Personen, die ihre Schnittstellenzeichenfolgen nicht an einen externen Dienst senden können oder wollen. Wenn diese Rolle ein Cloud- oder CLI-Profil enthält, benennt korTTY es und leitet Sie zu dieser Dropdown-Liste weiter, anstatt mit einem allgemeinen Fehler fehlzuschlagen.

Dies spiegelt das Dropdown-Menü **KI-Profil** im Abschnitt „Anleitungsübersetzung“ unten wider, sodass beide Übersetzungsjobs auf das gewünschte Modell verwiesen werden können – zum Beispiel ein kleines lokales Modell für die Schnittstellenzeichenfolgen und ein stärkeres Cloud-Modell für die Anleitung oder dasselbe Profil für beide.

!!! warning
    **Anmeldeinformationen:** Externe Anbieter benötigen ihren normalen API-Schlüssel, mit Ausnahme eines LibreTranslate-Endpunkts, der explizit ohne diesen konfiguriert ist. Lokale KI erfordert keinen Übersetzungsanbieterschlüssel, aber ein eingebettetes Modell muss sein GGUF/MLX-Modell und seine Laufzeit installiert haben, und der Master-Passwort-Tresor muss entsperrt werden, wenn das ausgewählte KI-Profil ein verschlüsseltes Geheimnis benötigt.

## Guide-Übersetzung

Ein zweiter, unabhängiger Job unten übersetzt den gebündelten Offline-Anleitung – dieselbe Website, zu der diese Seite gehört – in die oben ausgewählte Zielsprache, sodass das In-App-Fenster „Hilfe → Anleitung“ und seine Suche vollständig in dieser Sprache gelesen werden können und nicht nur die Schnittstellenbezeichnungen.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| AI-Profil | Dropdown-Liste | „Standard (Textprofil)“ plus jedes konfigurierte AI-Profil | Standard (Textprofil) | – |
| Dauer schätzen | Schaltfläche | — | — | — |
| Übersetzung starten | Schaltfläche | — | — | — |
| Schaltfläche „Abbrechen“ | | (wird angezeigt, während eine Übersetzung oder Dauerschätzung ausgeführt wird) | – | – |
| Übersetzte Reiseführer | Liste | Bisher übersetzte Reiseführersprachen | — | — |
| Schaltfläche „Löschen“ | | (entfernt die ausgewählte übersetzte Anleitung, ihre Assets und ihren Suchindex) | – | – |

Wenn Sie hier im Dropdown-Menü **AI-Profil** ein Profil auswählen, wird immer dieses Profil verwendet, unabhängig vom Verbindungsmodus. Wenn Sie es auf **Standard (Textprofil)** belassen, folgt die obige Übersetzungs-API-Auswahl: Wenn der Anbieter auf **Lokales AI-Textprofil** eingestellt ist, verwendet der Anleitung das der Text-/Übersetzungsrolle in KI-Manager zugewiesene Profil – und nur, wenn dieses Profil ein eingebettetes (lokales) Modell ausführt – während jeder andere Anbieter mit einem gespeicherten Schlüssel die Anleitung stattdessen über diese Übersetzungs-API übersetzt.

**Übersetzung starten** ohne ausgewähltes Leitprofil erfordert oben eine funktionsfähige Übersetzungs-API: Wenn der Anbieter einen verschlüsselten Dienst nutzt (z. B. Google Translate) und kein API-Schlüssel gespeichert ist, wird der Start mit *„Bitte geben Sie einen API-Schlüssel ein.“* verweigert – ein ausgewähltes KI-Profil allein reicht immer aus. Wenn der Lauf aus einem anderen Grund nicht starten kann, nennt die Meldung die Ursache: ein ausgewähltes Profil, dessen Modell nicht heruntergeladen oder ausgewählt wurde, kein Guide-Profil und kein API-Schlüssel oder kein lokales Textprofil konfiguriert.

Durch das Löschen eines übersetzten Anleitungs wird nach einer Bestätigungsaufforderung sein gesamtes Verzeichnis unter `~/.kortty/guide/<language>/` entfernt – die übersetzten Seiten, die bereitgestellten Themenressourcen, sein Suchindex und sein Lebenslauf-Prüfpunkt.

### Warum dies im Hintergrund läuft

Das Übersetzen der Anleitungs ist keine schnelle Angelegenheit: Die gebündelte Website enthält etwa 5.500 verschiedene Textteile, sobald wiederholte Navigation und Überschriften dedupliziert werden, und je nach Modell kann dies zwischen einigen Minuten und fast einem Tag dauern. Beim Starten wird weder das Einstellungsdialogfeld noch der Rest von korTTY blockiert:

- Die Übersetzung wird weiter ausgeführt, nachdem Sie die Einstellungen geschlossen, die Registerkarten gewechselt oder in Terminals weitergearbeitet haben.
- Eine kleine Fortschrittsanzeige – ein Balken, ein Prozentsatz und die geschätzte verbleibende Zeit – erscheint am rechten Ende der Menüleiste von korTTY, solange eine Übersetzung der Anleitungs läuft, und verschwindet wieder, sobald sie abgeschlossen ist.
- Während der Lauf aktiv ist, zeigt der Dialog einen Fortschrittsbalken und eine Statuszeile („Übersetzung… 42 %“). **Abbrechen** stoppt den Job am nächsten sicheren Punkt, anstatt das bereits Übersetzte zu verlieren: Der Fortschritt wird auf der Festplatte überprüft, so dass beim erneuten Starten derselben Sprache die Arbeit an der Stelle fortgesetzt wird, an der sie aufgehört hat, anstatt noch einmal von vorne zu beginnen.
- Die Fortschrittsanzeige im Dialog und **Abbrechen** gehören zum Einstellungsdialog, der den Lauf gestartet hat – ein späteres erneutes Öffnen von Einstellungen führt nicht zu einer erneuten Verknüpfung mit einem laufenden Auftrag, und **Übersetzung starten** meldet dann nur *„Eine Übersetzung der Anleitungs läuft bereits.“* Nach dem Schließen der Einstellungen wird der laufende Auftrag über das Beenden-Dialogfeld unten angehalten (oder einfach zum Beenden gelassen).
- Wenn Sie versuchen, korTTY zu beenden, während eine Anleitungsübersetzung ausgeführt wird, wird in einem Dialogfeld angeboten, die Übersetzung anzuhalten und zu beenden oder korTTY geöffnet zu lassen. Wenn Sie sich für eine Pause entscheiden, bleibt der Kontrollpunkt für den nächsten Lauf bestehen.
- Nach der Installation eines korTTY-Updates, das den Inhalt der Anleitungs geändert hat, und wenn Sie bereits über eine übersetzte Anleitung für Ihre Sprache verfügen, bietet korTTY einmal pro Lauf an – ein paar Sekunden nach dem Öffnen des Fensters und nie, während eine Übersetzung bereits läuft –, um es auf den neuesten Stand zu bringen; **Jetzt aktualisieren** öffnet diese Registerkarte „Einstellungen“, auf der Sie den Lauf selbst starten können. Da der Fortschritt durch den genauen englischen Text jedes Stücks kontrolliert wird, werden bei der Aktualisierung nur Sätze neu übersetzt, die sich in der Veröffentlichung tatsächlich geändert haben – alles andere wird unverändert wiederverwendet.

### Warum ein Reasoning-Modell schlecht passt

Einige KI-Modelle sind so konzipiert, dass sie „laut nachdenken“, bevor sie antworten. Dabei schreiben sie vor der eigentlichen Ausgabe eine erweiterte Gedankenkette in ihre Antwort ein, eine Technik, die auf schwierige Probleme wie Mathematik oder Codierung abgestimmt ist. Dieses Reasoning ist nicht Teil der Übersetzung, aber das Modell muss sie trotzdem generieren, und das Generieren von Text ist der langsamste Teil bei der Ausführung eines lokalen Modells. Gemessen an der eigenen Anleitung dieses Projekts erzeugte ein Reasoning-Modell etwa **4,4 Abschluss-Tokens für jedes Eingabe-Token** – ungefähr 4.400 Reasoning-Tokens, um 1.000 Zeichen Prosa zu übersetzen. Auf identischer Hardware war dies der Unterschied zwischen einem Übersetzungslauf von etwa einer Stunde und einem von sechs Stunden oder mehr.

Wenn es sich bei dem **KI-Profil**, das Sie für die Leitübersetzung auswählen, um ein Reasoning-Modell handelt, warnt Sie korTTY vor Beginn oder Schätzung, benennt das Modell und lässt Sie trotzdem fortfahren oder abbrechen. Bei der Prüfung wird auf den Namen des Modells geachtet (Herausgeber von Reasoning-Modellen machen dort Werbung dafür – „reasoning“, „thinking“, „R1“, „QwQ“, „o1“ und Ähnliches werden erkannt), die Ausführung kostet also nichts und kann gelegentlich ein anders benanntes Reasoning-Modell übersehen, aber es wird nicht fälschlicherweise vor einem einfachen Instruct-Modell gewarnt.

**Geeignete Modelle** sind auf Anweisungen abgestimmte Chat-Modelle („anweisen“) ohne Reasoning-/Denkmodus – die Art, die normalerweise für Übersetzungen, Zusammenfassungen oder Umschreibungen und nicht für mehrstufige Problemlösungen verwendet wird. Suchen Sie für ein eingebettetes lokales Profil nach MLX- oder GGUF-Builds, deren Name „instruct“ und nicht „reasoning“, „thinking“ oder „R1“ enthält, zum Beispiel ein Qwen2.5-Instruct- oder Phi-4-mini-instruct-Build in einer Größe, die Ihre Hardware bequem ausführen kann (4-Bit-quantisierte 7-8B-Modelle sind eine praktische Standardeinstellung auf Apple Silicon). Größere Instruct-Modelle lassen sich auf Kosten der Geschwindigkeit im Allgemeinen flüssiger übersetzen; Mithilfe der nachstehenden Schätzung der Dauer können Sie einen Vergleich durchführen, bevor Sie sich auf einen vollständigen Lauf festlegen.

### Schätzung, wie lange eine vollständige Übersetzung dauern wird

**Schätzdauer** misst das oben ausgewählte KI-Profil anhand einer realen, repräsentativen Stichprobe der Anleitungtextes und prognostiziert, wie lange die Übersetzung aller noch ausstehenden Elemente dauern würde, ohne sich auf die vollständige Ausführung festzulegen:

1. Es sendet zuerst eine kleine, zeitlich nicht festgelegte „Aufwärm“-Anfrage, damit die einmaligen Ladekosten eines lokalen Modells – die einmal für einen gesamten Lauf und nicht pro Stapel bezahlt werden – nicht mit der laufenden Übersetzungsgeschwindigkeit verwechselt und über jeden Stapel der Projektion hinweg multipliziert werden.
2. Es übersetzt dann einen echten, budgetgroßen Stapel Leittext und synchronisiert ihn. Hierbei handelt es sich um eine echte Übersetzung, nicht um eine Simulation: Die Stichprobe wird beibehalten, sodass diese Arbeit nicht wiederholt wird, wenn zuerst geschätzt wird und dann ein vollständiger Durchlauf gestartet wird.
3. Aus diesem einzelnen zeitgesteuerten Batch werden zwei Prognosen für den verbleibenden Text berechnet – eine unter der Annahme, dass die Kosten mit der Anzahl der Anfragen skalieren, die andere unter der Annahme, dass sie mit der Textmenge skalieren – und meldet den resultierenden Bereich zusammen mit der Zeit, die die verbleibenden Seiten und der verbleibende Text benötigen würden. Die Angabe eines Bereichs statt einer einzigen Zahl spiegelt wider, dass eine einzelne Stichprobe den festen Overhead eines Modells pro Anfrage nicht vollständig von seinen Kosten pro Zeichen trennen kann; Die Schätzung sorgt dafür, dass diese Unsicherheit sichtbar bleibt, anstatt sie hinter einer einzigen übermäßig genauen Zahl zu verbergen.

Wenn das Beispiel völlig fehlschlägt – das KI-Profil ist nicht erreichbar, falsch konfiguriert oder hat nichts Brauchbares produziert – meldet die Schätzung dies als Verbindungsproblem und nicht als Dauer, sodass es nicht mit „Übersetzung erfolgt sofort“ verwechselt werden kann. Und wenn nichts mehr zu übersetzen ist, meldet die Schätzung lediglich, dass der Anleitung bereits vollständig für diese Sprache übersetzt ist.

!!! note
    Die obige Warnung zum Reasoning-Modell wird sowohl bei **Schätzdauer** als auch bei **Übersetzung starten** angezeigt, da das Ausführen einer Schätzung anhand eines Reasoning-Modells bereits Echtzeit dafür in Anspruch nimmt.
