---
title: Sicherheit
---

# Sicherheit

Auf dieser Registerkarte werden die Optionen für die Sicherheit des Passwort-Tresors und die SSH-Schlüsselauthentifizierung verwaltet. Öffnen über **Konfiguration → Globale Einstellungen → Sicherheit**; in `~/.kortty/global-settings.xml` gespeichert.

![Security settings tab](../../assets/screenshots/settings/security.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Master-Passwort ändern | Schaltfläche | — | — | — |
| Master-Passwort beim Start anfordern | umschalten | — | Auf | `requireMasterPasswordOnStartup` |
| Option „Temporären SSH-Schlüssel aktivieren“ | umschalten | — | Aus | `temporarySshKeyEnabled` |

!!! Warnung „Master-Passwort beim Start“
Wenn „Master-Passwort beim Start erforderlich“ deaktiviert ist, können verschlüsselte Passwörter und SSH-Schlüssel nicht automatisch ohne manuelle Passworteingabe entschlüsselt werden. Dies stellt ein Sicherheitsrisiko dar und sollte nur deaktiviert werden, wenn Sie die Konsequenzen verstehen.

!!! Hinweis „Schaltfläche „Master-Passwort ändern““
Öffnet einen Dialog zum Festlegen oder Ändern des Master-Passworts. Alle gespeicherten Verbindungspasswörter werden automatisch neu verschlüsselt, wenn Sie dieses Passwort ändern.