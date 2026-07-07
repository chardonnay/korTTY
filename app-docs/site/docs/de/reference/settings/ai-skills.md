---
title: KI-Fähigkeiten
---

# KI-Fähigkeiten

Konfigurieren Sie benutzerdefinierte KI-Fähigkeiten, die KI-Interaktionen verbessern. Auf dieser Registerkarte können Sie eine Bibliothek mit Markdown-basierten Fertigkeiten verwalten, die automatisch oder manuell in KI-Anfragen einbezogen werden. Öffnen über **Konfiguration → Globale Einstellungen → KI-Fähigkeiten**; in `~/.kortty/global-settings.xml` gespeichert.

![AI Skills settings tab](../../assets/screenshots/settings/ai-skills.png)

## Globale Einstellungen

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| KI-Fähigkeiten aktivieren | umschalten | — | Auf | `aiSkillsEnabled` |
| Nur passende Fähigkeiten automatisch senden | umschalten | — | Auf | `aiSkillAutoDetectionEnabled` |
| Sortieren | Dropdown | Alphabetisch, Status (zuerst aktiviert) | Alphabetisch | — |

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

!!! Hinweis „Verhalten der automatischen Erkennung“
    Wenn **Nur übereinstimmende Fertigkeiten automatisch senden** aktiviert ist, wertet korTTY die Tags jeder Fertigkeit anhand der aktuellen Anfrage aus und schließt nur diejenigen ein, die übereinstimmen. Wenn diese Option deaktiviert ist, werden alle aktiven Fertigkeiten unabhängig von den Tags gesendet.

!!! Hinweis „Fähigkeitsziele“
    - **KI-Chat/Funktionen**: Fähigkeiten, die in KI-Chat- und Funktionsaufrufkontexten verfügbar sind
    - **KI-Agent**: Vom Terminal-KI-Agenten verwendete Fähigkeiten
    - **Beide**: Verfügbar sowohl für AI Chat- als auch für AI Agent-Kontexte
    - **Verbindung**: Spezielle Fähigkeiten für die Handhabung von SSH-Verbindungen

!!! Hinweis „Skill-Lebenszyklus“
    Fertigkeiten werden als XML-Elemente in der globalen Einstellungsdatei gespeichert. Verwenden Sie **Importieren**, um Fertigkeiten aus Markdown-Dateien zu laden, und **Exportieren**, um ausgewählte Fertigkeiten als Markdown-Dateien zu speichern. Die Fertigkeitsliste kann alphabetisch nach Name oder Status (zuerst aktiviert) sortiert werden.
