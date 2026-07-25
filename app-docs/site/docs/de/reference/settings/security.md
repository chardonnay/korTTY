---
title: Sicherheit
---

# Sicherheit

Auf dieser Registerkarte werden die Optionen für die Sicherheit des Passwort-Tresors und die SSH-Schlüsselauthentifizierung verwaltet. Öffnen über **Konfiguration → Globale Einstellungen → Sicherheit**; in `~/.kortty/global-settings.xml` gespeichert.

![Security settings tab](../../assets/screenshots/settings/security.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Master-Passwort ändern | Schaltfläche | – | – | – |
| Master-Passwort beim Start erforderlich | umschalten | – | Ein | `requireMasterPasswordOnStartup` |
| Master-Passwort-Abfrage beim Start deaktivieren (automatische Anmeldung) | umschalten | – | Aus | `skipMasterPasswordPrompt` |
| Temporäre SSH-Schlüsseloption aktivieren | umschalten | – | Aus | `temporarySshKeyEnabled` |

!!! warning "Master-Passwort beim Start"
    Wenn „Master-Passwort beim Start erforderlich“ deaktiviert ist, können verschlüsselte Passwörter und SSH-Schlüssel nicht automatisch ohne manuelle Passworteingabe entschlüsselt werden. Dies stellt ein Sicherheitsrisiko dar und sollte nur deaktiviert werden, wenn Sie die Konsequenzen verstehen.

!!! danger "Auto-Login speichert Ihr Master-Passwort auf der Festplatte"
    Wenn Sie „Master-Passwort-Eingabeaufforderung beim Start deaktivieren“ aktivieren, merkt sich korTTY Ihr Master-Passwort – es wird nur verschleiert (nicht sicher verschlüsselt) in `~/.kortty/master.autounlock` mit Dateiberechtigungen nur für Besitzer gespeichert – und entsperrt den Tresor automatisch bei jedem Start, ohne Dialog. Anders als bei der Deaktivierung der oben genannten Option bleiben verschlüsselte Daten (KI-Profile, SSH-Passwörter, Anmeldeinformationen) nutzbar, da der Tresor tatsächlich entsperrt ist. Der Nachteil: Jeder, der Ihren `~/.kortty`-Ordner oder ein Backup lesen kann, kann dann **alle** gespeicherten Passwörter, SSH-Schlüssel und API-Schlüssel entschlüsseln – die Dateiberechtigungen sind der einzige verbleibende Schutz. Bei einem brandneuen Profil richtet korTTY automatisch ein Standardpasswort ein, sodass die App ohne Eingaben gestartet werden kann. korTTY bittet Sie um eine Bestätigung, bevor Sie es aktivieren, und ist nur für Wegwerf-/Testumgebungen wie eine VM gedacht. Eine Unternehmensrichtlinie, die ein Hauptkennwort erfordert, setzt diese Option außer Kraft.

!!! tip "Unbeaufsichtigter erster Start (Automatisierungs-/Test-VMs)"
    Um korTTY ohne Dialog aufzurufen – selbst bei einem brandneuen Profil, das noch nie freigeschaltet wurde – erstellen Sie `~/.kortty/global-settings.xml` vor dem ersten Start mit bereits aktivierter Option:

    ```xml
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <globalSettings>
        <skipMasterPasswordPrompt>true</skipMasterPasswordPrompt>
    </globalSettings>
    ```

    Beim ersten Start richtet korTTY automatisch ein Standard-Master-Passwort ein und entsperrt den Tresor, sodass für Skript- oder CI-Ausführungen keine Interaktion erforderlich ist. Nur für Einweg-/Testumgebungen gedacht – siehe Sicherheitswarnung oben.

!!! note "Schaltfläche „Master-Passwort ändern“"
    Öffnet einen Dialog, in dem Sie nach dem **Aktuellen Passwort**, dem **Neuen Passwort (mindestens 6 Zeichen)** und einer Wiederholung **Neues Passwort bestätigen** gefragt werden. **Änderung** wendet es an. korTTY lehnt ein falsches aktuelles Passwort, ein neues Passwort mit weniger als sechs Zeichen und eine Nichtübereinstimmung zwischen den beiden neuen Einträgen ab, jeweils mit einer eigenen Nachricht. Bei Erfolg meldet es, wie viele Geheimnisse neu verschlüsselt wurden: Jedes durch ein Master-Passwort geschützte Geheimnis wird mit dem alten Passwort entschlüsselt und mit dem neuen neu verschlüsselt – Verbindungspasswörter und Schlüsselpassphrasen, Jump-Server-Passwörter, SSH-Schlüsselpassphrasen, gespeicherte Anmeldeinformationen, AI-Profil-API-Schlüssel und die anderen AI-/Übersetzungsschlüssel sowie RAG- und Job Scheduler-Geheimnisse – sodass nach der Änderung nichts unlesbar wird.