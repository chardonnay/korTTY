---
title: Aktualisierungen
---

# Updates

Konfigurieren Sie die automatische Update-Überprüfung und die Häufigkeit, mit der korTTY GitHub nach neuen Versionen abfragt. Öffnen über **Konfiguration → Globale Einstellungen → Updates**; in `~/.kortty/global-settings.xml` gespeichert.

![Updates settings tab](../../assets/screenshots/settings/updates.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Automatisch nach KorTTY-Updates suchen | umschalten | — | Auf | `updateChecksEnabled` |
| Prüfintervall | Schieberegler | 1–30 Tage | 1 Tag | `updateCheckIntervalDays` |

!!! note
    Automatische Update-Prüfungen laufen unbemerkt im Hintergrund und zeigen nur dann einen Benachrichtigungsdialog an, wenn eine neuere kompatible Version verfügbar ist. Manuelle Update-Prüfungen sind jederzeit über das Dialogfeld „Info“ verfügbar.
