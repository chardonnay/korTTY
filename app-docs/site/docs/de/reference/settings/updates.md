---
title: Aktualisierungen
---

# Updates

Konfigurieren Sie die automatische Prüfung auf Updates und die Häufigkeit, mit der korTTY GitHub abfragt, um neue Versionen zu überprüfen. Öffnen Sie dies über **Konfiguration → Globale Einstellungen → Aktualisierungen**; gespeichert in `~/.kortty/global-settings.xml`.

![Updates settings tab](../../assets/screenshots/settings/updates.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Automatisch nach KorTTY-Updates suchen | Schalter | — | Ein | `updateChecksEnabled` |
| Prüfintervall | Schieberegler | 1–30 Tage | 1 Tag | `updateCheckIntervalDays` |

!!! note
    Automatische Update-Prüfungen laufen unbemerkt im Hintergrund und zeigen nur dann einen Benachrichtigungsdialog an, wenn eine neuere kompatible Version verfügbar ist. Manuelle Update-Prüfungen sind jederzeit über das Dialogfeld „Info“ verfügbar.

## Ein Update herunterladen und installieren

korTTY wählt das native Release-Asset aus, das dem aktuellen Betriebssystem, der Prozessorarchitektur und dem Pakettyp entspricht, lädt es in das normale Download-Verzeichnis der Plattform herunter und überprüft den von GitHub veröffentlichten SHA-256-Digest, bevor die Datei verfügbar gemacht wird. Eine Flatpak-Installation akzeptiert nur ein passendes `.flatpak`-Bundle; Es bietet niemals ein DEB-, RPM-, Pacman-Paket oder tragbares Archiv als direkten Ersatz an.

Flatpak-Bundles werden heruntergeladen, aber nicht automatisch installiert. Nach dem Download zeigt korTTY den genauen Host-Terminal-Befehl an, der dem folgenden Beispiel entspricht, und lässt den heruntergeladenen Pfad auswählbar:

```bash
flatpak install --user ./kortty-Linux-<version>-<architecture>.flatpak
```

Die GitHub-Release-Seite bleibt über die Anleitung verfügbar, wenn der Paketmanager eine zusätzliche Bestätigung oder eine systemweite Installation erfordert.
