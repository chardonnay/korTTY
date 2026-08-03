---
title: Export
---

# Export

Wasserzeichen und Fußzeile der von korTTY exportierten Dokumente — für [Sitzungsjournale](../../features/session-journal.md#exportieren) ebenso wie für [KI-Chats](../../features/ai-assistant.md). Öffnen über **Konfiguration → Globale Einstellungen → Export**; in `~/.kortty/global-settings.xml` gespeichert.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Ein Wasserzeichen zu exportierten PDFs hinzufügen | boolean | ein/aus | aus | `pdfWatermarkEnabled` |
| Wasserzeichentext | Text | – | Integriertes korTTY-Wasserzeichen | `pdfWatermarkText` |
| Wasserzeichenfarbe | Farbe | – | Grau (`#6b7280`) | `pdfWatermarkColor` |
| Fußzeile in exportierten Dokumenten anzeigen | boolean | ein/aus | ein | `exportFooterEnabled` |
| Fußzeilentext | Text | – | Integrierte Markenzeile | `exportFooterText` |

![Export settings](../../assets/screenshots/settings/export.png)

## Wasserzeichen

Das Wasserzeichen ist **standardmäßig deaktiviert** – ein Dokument wird markiert, wenn Sie dies wünschen. Sobald es aktiviert ist, wird es schwach und diagonal über die Mitte jeder PDF-Seite gezeichnet, angepasst an die Seitenbreite und in der von Ihnen gewählten Farbe. Dies gilt für Sitzungsjournal- und AI-Chat-PDF-Exporte.

Wenn Sie das Textfeld leer lassen, wird das integrierte korTTY-Wasserzeichen verwendet, das zusätzlich den Projekt-Repository-Link darunter druckt. Ein eigener Text wird wörtlich übernommen, es wird nichts angehängt.

!!! tip
    Ein Wasserzeichen wie `CONFIDENTIAL` oder der Name Ihrer Organisation ist eine visuelle Markierung und kein Schutz. Jeder kann es aus einem PDF entfernen. Für Journale, die nicht für andere lesbar sein dürfen, exportieren Sie stattdessen ein [verschlüsseltes Archiv](../../features/session-journal.md#mehrere-journale-exportieren).

## Fußzeile

Die Fußzeile ist **standardmäßig aktiviert** und erscheint in jedem exportierten Format, das über eine Fußzeile verfügt: die untere Zeile jeder PDF-Seite, die letzte Zeile eines Markdown-Exports und die Fußzeile der exportierten Journalseite. Unabhängig von dieser Einstellung bleiben die Seitenzahlen in der PDF-Fußzeile erhalten.

Wenn Sie das Textfeld leer lassen, wird die integrierte Zeile verwendet, die korTTY benennt und den Repository-Link anhängt (anklickbar im PDF). Ein eigener Text ersetzt diesen vollständig, ohne den Link.

Wenn Sie die Fußzeile deaktivieren, wird sie aus allen diesen Formaten entfernt.
