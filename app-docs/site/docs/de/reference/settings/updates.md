---
title: Aktualisierungen
---

# Updates

Configure automatic update checking and the frequency at which korTTY queries GitHub for new releases. Open via **Configuration → Global Settings → Updates**; stored in `~/.kortty/global-settings.xml`.

![Updates settings tab](../../assets/screenshots/settings/updates.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Check automatically for KorTTY updates | toggle | — | On | `updateChecksEnabled` |
| Check interval | slider | 1–30 days | 1 day | `updateCheckIntervalDays` |

!!! note
    Automatische Update-Prüfungen laufen unbemerkt im Hintergrund und zeigen nur dann einen Benachrichtigungsdialog an, wenn eine neuere kompatible Version verfügbar ist. Manuelle Update-Prüfungen sind jederzeit über das Dialogfeld „Info“ verfügbar.
