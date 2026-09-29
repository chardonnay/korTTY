---
title: Exportieren
---

# Export

Watermark und Fußzeile der von korTTY exportierten Dokumente – [Sitzungsjournale](../../features/session-journal.md#exportieren), [KI-Chats](../../features/ai-assistant.md) und [Code-Analysenberichte](../../features/snippets.md) – sind identisch. Öffnen Sie sie über **Konfiguration → Globale Einstellungen → Export**; gespeichert in `~/.kortty/global-settings.xml`.

KI-Chat-PDFs betten Fallback-Schriftarten für Unicode-Symbole und Emojis ein, sodass Zeichen wie `✓`, `★`, `😀` und `🚀` im exportierten Dokument sichtbar und durchsuchbar bleiben, anstatt durch Fragezeichen ersetzt zu werden.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Ein Wasserzeichen zu exportierten PDFs hinzufügen | boolean | ein/aus | aus | `pdfWatermarkEnabled` |
| Wasserzeichentext | Text | – | Integriertes korTTY-Wasserzeichen | `pdfWatermarkText` |
| Wasserzeichenfarbe | Farbe | – | Grau (`#6b7280`) | `pdfWatermarkColor` |
| Fußzeile in exportierten Dokumenten anzeigen | boolean | ein/aus | ein | `exportFooterEnabled` |
| Fußzeilentext | Text | – | Integrierte Markenzeile | `exportFooterText` |

![Export settings](../../assets/screenshots/settings/export.png)

## Wasserzeichen

Das Wasserzeichen ist **standardmäßig deaktiviert** – ein Dokument wird markiert, wenn Sie dies wünschen. Sobald es aktiviert ist, wird es schwach und diagonal über die Mitte jeder PDF-Seite gezeichnet, angepasst an die Seitenbreite und in der von Ihnen gewählten Farbe. Dies gilt für Sitzungsjournal- und KI-Chat-PDF-Exporte.

Wenn Sie das Textfeld leer lassen, wird das integrierte korTTY-Wasserzeichen verwendet, das zusätzlich den Projekt-Repository-Link darunter druckt. Ein eigener Text wird wörtlich übernommen, es wird nichts angehängt.

!!! tip
    Ein Wasserzeichen wie `CONFIDENTIAL` oder der Name Ihrer Organisation dient als visueller Hinweis und ist keine Sicherheit. Jeder kann das Wasserzeichen aus einem PDF entfernen. Für Journale, die nicht von anderen lesbar sein dürfen, exportieren Sie stattdessen ein [verschlüsseltes Archiv](../../features/session-journal.md#mehrere-tagebucher-exportieren).

## Fußzeile

Die Fußzeile ist **standardmäßig aktiviert** und erscheint in jedem exportierten Format, das über eine Fußzeile verfügt: die untere Zeile jeder PDF-Seite, die letzte Zeile eines Markdown-Exports und die Fußzeile der exportierten Journalseite. Unabhängig von dieser Einstellung bleiben die Seitenzahlen in der PDF-Fußzeile erhalten.

Wenn Sie das Textfeld leer lassen, wird die integrierte Zeile verwendet, die korTTY benennt und den Repository-Link anhängt (anklickbar im PDF). Ein eigener Text ersetzt diesen vollständig, ohne den Link.

Wenn Sie die Fußzeile deaktivieren, wird sie aus allen diesen Formaten entfernt.
