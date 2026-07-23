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

!!! warning "Master-Passwort beim Start"
    Wenn „Master-Passwort beim Start erforderlich“ deaktiviert ist, können verschlüsselte Passwörter und SSH-Schlüssel nicht automatisch ohne manuelle Passworteingabe entschlüsselt werden. Dies stellt ein Sicherheitsrisiko dar und sollte nur deaktiviert werden, wenn Sie die Konsequenzen verstehen.

!!! note "Schaltfläche „Master-Passwort ändern“"
    Öffnet einen Dialog, in dem Sie nach dem **Aktuellen Passwort**, dem **Neuen Passwort (mindestens 6 Zeichen)** und einer Wiederholung **Neues Passwort bestätigen** gefragt werden. **Änderung** wendet es an. korTTY lehnt ein falsches aktuelles Passwort, ein neues Passwort mit weniger als sechs Zeichen und eine Nichtübereinstimmung zwischen den beiden neuen Einträgen ab, jeweils mit einer eigenen Nachricht. Bei Erfolg meldet es, wie viele Verbindungspasswörter neu verschlüsselt wurden – jedes gespeicherte Verbindungspasswort wird mit dem neuen Master-Passwort neu verschlüsselt.