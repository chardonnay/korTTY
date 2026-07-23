---
title: KI-Fähigkeiten
---

# KI-Fähigkeiten

Konfigurieren Sie benutzerdefinierte KI-Fähigkeiten, die KI-Interaktionen verbessern. Auf dieser Registerkarte können Sie eine Bibliothek mit Markdown-basierten Fertigkeiten verwalten, die automatisch oder manuell in KI-Anfragen einbezogen werden. Öffnen über **KI → KI-Manager → KI-Fähigkeiten**; in `~/.kortty/global-settings.xml` gespeichert.

!!! note "Aus den globalen Einstellungen entfernt"
    Die Skill-Bibliothek war früher eine Registerkarte unter **Konfiguration → Globale Einstellungen**. Es befindet sich jetzt im **AI Manager**, neben Profilen, lokalen Modellen und Wissensspeichern. Die gespeicherten Daten und die Einstellungsdatei bleiben unverändert.

![AI Skills settings tab](../../assets/screenshots/settings/ai-skills.png)

## Globale Einstellungen

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| KI-Fähigkeiten aktivieren | umschalten | — | Auf | `aiSkillsEnabled` |
| Nur passende Fähigkeiten automatisch senden | umschalten | — | Auf | `aiSkillAutoDetectionEnabled` |
| Sortieren | Dropdown | Alphabetisch, Status (zuerst aktiviert) | Alphabetisch | — |
| Schaltfläche „Speichern“ | | Schreibt die Bibliothek in die globale Einstellungsdatei | – | – |

## Felder des Skill-Editors

Wenn Sie eine Fertigkeit auswählen oder erstellen, werden im rechten Bereich Felder pro Fertigkeit angezeigt. Jede Fertigkeit wird einzeln in der Fertigkeitsliste gespeichert.

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Fähigkeitsname | Text | — | „KI-Fähigkeit“ | `name` (auf AiSkill-Objekt) |
| Beschreibung | Text | — | — | `description` |
| Schlagworte | Text | Durch Kommas getrennte Tags (z. B. Linux, Bash) | — | `tags` |
| Ziel | Dropdown | KI-Chat/-Funktionen, KI-Agent, Beide, Verbindung | Beide | `target` |
| Aktiv | umschalten | — | Aus | `enabled` |
| Fertigkeitsabschlag | Text | Markdown-formatierte Fertigkeitsinhalte | — | `content` |

## Notizen

!!! note "Automatisches Erkennungsverhalten"
    Wenn **Nur übereinstimmende Fertigkeiten automatisch senden** aktiviert ist, wertet korTTY die Tags jeder Fertigkeit anhand der aktuellen Anfrage aus und schließt nur diejenigen ein, die übereinstimmen. Wenn diese Option deaktiviert ist, werden alle aktiven Fertigkeiten unabhängig von den Tags gesendet.

!!! note "Fertigkeitsziele"
    - **KI-Chat/Funktionen**: Fähigkeiten, die in KI-Chat- und Funktionsaufrufkontexten verfügbar sind
    - **KI-Agent**: Vom Terminal-KI-Agenten verwendete Fähigkeiten
    - **Beide**: Verfügbar sowohl für AI Chat- als auch für AI Agent-Kontexte
    - **Verbindung**: Spezielle Fähigkeiten für die Handhabung von SSH-Verbindungen

!!! note "Skill-Lebenszyklus"
    Fertigkeiten werden als XML-Elemente in der globalen Einstellungsdatei gespeichert. Verwenden Sie **Importieren**, um Fertigkeiten aus Markdown-Dateien zu laden, und **Exportieren**, um ausgewählte Fertigkeiten als Markdown-Dateien zu speichern. Die Fertigkeitsliste kann alphabetisch nach Name oder Status (zuerst aktiviert) sortiert werden. **Speichern** speichert die Bibliothek sofort und bestätigt neben der Schaltfläche; Ausstehende Änderungen werden auch geschrieben, wenn das AI Manager-Fenster geschlossen wird.

!!! note "Auswahl der Fähigkeiten pro Anfrage"
    Auf dieser Registerkarte wird die globale Bibliothek verwaltet. Welche dieser aktivierten Fertigkeiten für eine bestimmte Aktion gelten, wird an anderer Stelle ausgewählt: Die **KI-Fähigkeiten**-Auswahl des Snippet-Editors heftet Ihre Auswahl an jede Snippet-KI-Aktion, und das Fenster **Vollständige Codeanalyse** zeigt die enthaltenen Fertigkeiten als Chips an – mit der Bezeichnung *(automatisch ausgewählt)* oder *(manuell)* – mit einer durchsuchbaren Auswahl, deren Änderungen bei der nächsten Wiederholung wirksam werden. Siehe [Schnipsel → KI-Fähigkeiten](../../features/snippets.md#ai-skills).
