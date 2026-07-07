---
title: Übersetzung
---

# Übersetzung

Konfigurieren Sie die dynamische Übersetzung der Benutzeroberfläche von korTTY mithilfe externer Übersetzungs-APIs. Auf dieser Registerkarte können Sie einen Übersetzungsanbieter auswählen, sich mit seiner API authentifizieren und Sprachdateien generieren, um die Benutzeroberfläche in Ihrer Zielsprache anzuzeigen. Öffnen über **Konfiguration → Globale Einstellungen → Übersetzung**; in `~/.kortty/global-settings.xml` gespeichert.

![Translation settings tab](../../assets/screenshots/settings/translation.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Systemsprache | Text | — | Systemgebietsschema | — |
| Übersetzungs-API | Dropdown | Google Translate, DeepL, LibreTranslate, Microsoft Translator, Yandex Translate | Google Translate | `translationApiProvider` |
| API-Schlüssel | Text | — | — | `encryptedTranslationApiKey` |
| API-URL (optional) | Text | — | — (null = Anbieterstandard verwenden) | `translationApiUrl` |
| API-Verbindung testen | Schaltfläche | — | — | — |
| Zielsprache | Dropdown | Systemgebietsschema und verfügbare Gebietsschemata (Gebietsschemaobjekte) | Systemgebietsschema | — |
| Sprachdatei generieren | Schaltfläche | — | — | — |
| Generierte Sprachen | Liste | Verfügbare dynamisch übersetzte Sprachdateien | — | — |
| Löschen | Schaltfläche | (Entfernt die ausgewählte generierte Sprachdatei) | — | — |
| Veraltete | neu generieren Schaltfläche | (sichtbar, wenn veraltete Dateien vorhanden sind) | — | — |

!!! Notiz
    **Speicherung des API-Schlüssels:** Der API-Schlüssel wird mit Ihrem Master-Passwort verschlüsselt und sicher in `global-settings.xml` gespeichert. Wenn der Master-Passwort-Tresor gesperrt ist, erhalten Sie eine Warnung und der Schlüssel wird im Feld aufbewahrt, bis Sie den Tresor entsperren oder in den Einstellungen ein Master-Passwort festlegen können.

!!! Notiz
    **Generierte Sprachen:** In der Liste „Generierte Sprachen“ werden Sprachdateien angezeigt, die über die Schaltfläche „Sprachdatei generieren“ erstellt wurden. Jede generierte Datei entspricht einer dynamisch übersetzten Benutzeroberfläche in dieser Zielsprache. Verwenden Sie die Schaltfläche „Löschen“, um eine Sprachdatei zu entfernen, oder verwenden Sie „Veraltet neu generieren“, um Dateien, die mit einer älteren App-Version erstellt wurden, so zu aktualisieren, dass sie neu hinzugefügte Übersetzungsschlüssel enthalten.

!!! Warnung
    **API-Schlüssel erforderlich:** Um die Verbindung zu testen oder eine Sprachdatei zu generieren, muss ein API-Schlüssel für den von Ihnen gewählten Übersetzungsanbieter eingegeben werden. Mit der Schaltfläche „API-Verbindung testen“ wird überprüft, ob Anbieter und Schlüssel korrekt sind, bevor versucht wird, eine Datei zu generieren.