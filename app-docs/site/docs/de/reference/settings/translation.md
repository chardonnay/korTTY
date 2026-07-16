---
title: Übersetzung
---

# Übersetzung

Konfigurieren Sie die dynamische Übersetzung der Benutzeroberfläche von korTTY mithilfe externer Übersetzungs-APIs. Auf dieser Registerkarte können Sie einen Übersetzungsanbieter auswählen, sich mit seiner API authentifizieren und Sprachdateien generieren, um die Benutzeroberfläche in Ihrer Zielsprache anzuzeigen. Öffnen über **Konfiguration → Globale Einstellungen → Übersetzung**; in `~/.kortty/global-settings.xml` gespeichert.

![Translation settings tab](../../assets/screenshots/settings/translation.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Systemsprache | Text | — | Systemgebietsschema | — |
| Übersetzungs-API | Dropdown-Liste | Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex Translate, lokales AI-Textprofil | Google Translate | `translationApiProvider` |
| API-Schlüssel | Text | — | — | `encryptedTranslationApiKey` |
| API-URL (optional) | Text | — | — (null = Anbieterstandard verwenden) | `translationApiUrl` |
| API-Verbindung testen | Schaltfläche | — | — | — |
| Zielsprache | Dropdown | Systemgebietsschema und verfügbare Gebietsschemata (Gebietsschemaobjekte) | Systemgebietsschema | — |
| Sprachdatei generieren | Schaltfläche | — | — | — |
| Generierte Sprachen | Liste | Verfügbare dynamisch übersetzte Sprachdateien | — | — |
| Löschen | Schaltfläche | (Entfernt die ausgewählte generierte Sprachdatei) | — | — |
| Veraltete | neu generieren Schaltfläche | (sichtbar, wenn veraltete Dateien vorhanden sind) | — | — |

!!! note
    **Speicherung des API-Schlüssels:** Der API-Schlüssel wird mit Ihrem Master-Passwort verschlüsselt und sicher in `global-settings.xml` gespeichert. Wenn der Master-Passwort-Tresor gesperrt ist, erhalten Sie eine Warnung und der Schlüssel wird im Feld aufbewahrt, bis Sie den Tresor entsperren oder in den Einstellungen ein Master-Passwort festlegen können.

!!! note
    **Generierte Sprachen:** In der Liste „Generierte Sprachen“ werden Sprachdateien angezeigt, die über die Schaltfläche „Sprachdatei generieren“ erstellt wurden. Jede generierte Datei entspricht einer dynamisch übersetzten Benutzeroberfläche in dieser Zielsprache. Verwenden Sie die Schaltfläche „Löschen“, um eine Sprachdatei zu entfernen, oder verwenden Sie „Veraltet neu generieren“, um Dateien, die mit einer älteren App-Version erstellt wurden, so zu aktualisieren, dass sie neu hinzugefügte Übersetzungsschlüssel enthalten.

## Lokale Übersetzung

Wählen Sie **Lokales AI-Textprofil**, um über das eingebettete llama.cpp-Profil zu übersetzen, das der Text-/Übersetzungsrolle in **AI > AI Manager > Lokale KI** zugewiesen ist. API-URL und API-Schlüssel sind für diesen Anbieter deaktiviert, da der authentifizierte Loopback-Endpunkt von korTTY verwaltet wird. Das lokale Modell muss ein striktes JSON `translations`-Array mit der gleichen Anzahl und Reihenfolge der Eingabezeichenfolgen zurückgeben; Eine ungültige Ausgabe stoppt die Generierung, anstatt UI-Beschriftungen stillschweigend falsch auszurichten.

!!! warning
    **Anmeldeinformationen:** Externe Anbieter benötigen ihren normalen API-Schlüssel, mit Ausnahme eines LibreTranslate-Endpunkts, der explizit ohne diesen konfiguriert ist. Lokale KI erfordert keinen Übersetzungsanbieterschlüssel, aber ihr GGUF-Modell und die llama.cpp-Laufzeit müssen installiert sein und der Master-Passwort-Tresor muss entsperrt werden, wenn das ausgewählte AI-Profil ein verschlüsseltes Geheimnis benötigt.
