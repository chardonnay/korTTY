---
title: Sicherheit
---

# Sicherheit

Auf dieser Registerkarte werden die Optionen für die Sicherheit des Passwort-Tresors und die SSH-Schlüsselauthentifizierung verwaltet. Öffnen über **Konfiguration → Globale Einstellungen → Sicherheit**; in `~/.kortty/global-settings.xml` gespeichert.

![Security settings tab](../../assets/screenshots/settings/security.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Master-Passwort ändern | Schaltfläche | — | — | — |
| Master-Passwort beim Programmstart anfordern | Schalter | — | Ein | `requireMasterPasswordOnStartup` |
| Master-Passwort-Aufforderung beim Programmstart deaktivieren (automatischer Login) | Schalter | — | Aus | `skipMasterPasswordPrompt` |
| Temporäre SSH-Schlüsseloption aktivieren | umschalten | – | Aus | `temporarySshKeyEnabled` |

!!! note "Temporäre SSH-Schlüssel und der Temp-Ordner"
    Mit dieser Option aktiviert, bietet der Connection-Manager und die Schnellverbindung einen temporären SSH-Schlüssel: einen privaten Schlüssel, den Sie für die Sitzung einfügen statt einer Schlüsseldatei. Für Terminal-Tabs, Aufteilungen und SFTP liest korTTY einen temporären Schlüssel im Speicher und schreibt ihn niemals in eine Schlüsseldatei; eine gespeicherte Verbindung behält den Schlüssel nur verschlüsselt mit Ihrem Master-Passwort in `connections.xml`. Geplante Aufgaben authentifizieren sich ebenfalls im Speicher, mit einer Ausnahme: weil ein Rsync-Job den Schlüssel an das externe `ssh` übergibt, schreibt der JobScheduler den temporären Schlüssel eines Rsync-Jobs in eine nur für den Besitzer lesbare Datei im Systemtemp-Ordner und löscht ihn, wenn die Verbindung des Jobs geschlossen oder fehlschlägt. Schlüsseldateien, die frühere Versionen im Temp-Ordner zurückgelassen haben (`kortty_temp_key_*.key`, `kortty_scheduler_key_*.key`), werden beim nächsten Start gelöscht, sofern sie Ihnen gehören und älter als fünf Minuten sind.

!!! warning "Master-Passwort beim Start"
    Wenn "Master-Passwort beim Programmstart anfordern" deaktiviert ist, startet korTTY mit dem Tresor gesperrt: verschlüsselte Passwörter und SSH-Schlüssel können nicht entschlüsselt werden, bis Sie ihn mit **Konfiguration → Sicherheit → Tresor entsperren…** oder dem **Tresor entsperren…**-Button einer beliebigen "Tresor gesperrt"-Nachricht entsperren. Dies ist ein Sicherheitsrisiko und sollte nur deaktiviert werden, wenn Sie die Konsequenzen verstehen.

!!! danger "Auto-Login speichert Ihr Master-Passwort auf der Festplatte"
    Die Aktivierung von „Master-Passwort-Frage beim Programmstart deaktivieren“ lässt korTTY Ihr Master-Passwort speichern – nur verschleiert (nicht sicher verschlüsselt) im `~/.kortty/master.autounlock` mit Besitzerrechten auf der Datei – und entsperrt den Tresor automatisch bei jedem Start ohne Dialog. Im Gegensatz zur Deaktivierung der Option oben bleiben verschlüsselte Daten (KI-Profile, SSH-Passwörter, Zugangsdaten) nutzbar, da der Tresor tatsächlich entsperrt wird. Der Preis dafür ist, dass jeder, der Ihren `~/.kortty`-Ordner oder eine Sicherung lesen kann, alle gespeicherten Passwörter, SSH-Schlüssel und API-Schlüssel entschlüsseln kann – die Dateirechte sind die einzige verbleibende Schutzmaßnahme. Bei einem neuen Profil erstellt korTTY ein Standardpasswort automatisch, sodass die Anwendung ohne Eingabe starten kann. korTTY fragt Sie vor der Aktivierung nach Bestätigung, und diese Einstellung ist ausschließlich für kurzfristige oder Testumgebungen wie eine Virtuelle Maschine vorgesehen. Solange automatischer Login aktiv ist, ist „Master-Passwort beim Programmstart anfordern“ deaktiviert und grau, da beide Optionen gegenseitig ausschließend sind. Eine Unternehmensrichtlinie, die ein Master-Passwort erfordert, überschreibt diese Einstellung.

!!! tip "Unbeaufsichtigter erster Start (Automatisierungs-/Test-VMs)"
    Um korTTY ohne Dialog aufzurufen – selbst bei einem brandneuen Profil, das noch nie freigeschaltet wurde – erstellen Sie `~/.kortty/global-settings.xml` vor dem ersten Start mit bereits aktivierter Option:

    ```xml
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <globalSettings>
        <skipMasterPasswordPrompt>true</skipMasterPasswordPrompt>
    </globalSettings>
    ```

    Bei dem ersten Start legt korTTY das Standard-Master-Passwort `kortty-auto` automatisch fest und entsperrt den Tresor, sodass skriptbasierte oder CI-Abläufe keine Interaktion erfordern. Wenn Sie später den automatischen Login auf einem solchen Profil deaktivieren, ist `kortty-auto` das Passwort, das eingegeben werden muss — ändern Sie es sofort über **Master-Passwort ändern**, falls das Profil langfristig verwendet werden soll. Dies ist ausschließlich für kurzlebige oder Testumgebungen vorgesehen — siehe die Sicherheitswarnung oben.

!!! note "Schaltfläche „Master-Passwort ändern“"
    Öffnet ein Dialogfeld, das das **Aktuelle Passwort**, das **Neue Passwort (mindestens 6 Zeichen)** und eine **Neues Passwort bestätigen**-Wiederholung erfordert; **Ändern** wendet diese Anpassung an. korTTY lehnt ein falsches aktuelles Passwort, ein neues Passwort mit weniger als sechs Zeichen und eine Ungleichheit zwischen den beiden neuen Eingaben ab, jeweils mit einer eigenen Nachricht. Im Erfolg wird angegeben, wie viele Geheimnisse neu verschlüsselt wurden: Jedes Geheimnis, das mit dem Master-Passwort geschützt ist, wird mit dem alten Passwort entschlüsselt und mit dem neuen Passwort neu verschlüsselt — Verbindungspasswörter und Schlüsselphrasen, Jump-Server-Passwörter, SSH-Schlüsselphrasen, gespeicherte Zugangsdaten, KI-Profile-API-Schlüssel und andere KI-/Übersetzungsschlüssel sowie RAG- und Job-Scheduler-Geheimnisse — so dass nach der Änderung nichts unlesbar wird.

    Das neue Passwort übernimmt erst dann, wenn alle Speicher übertragen wurden, sodass ein Fehler während des Prozesses das alte Passwort behält statt Sie teilweise auszuschließen. Wenn ein einzelnes Geheimnis nicht übertragen werden kann (zum Beispiel ein Geheimnis, das mit einem anderen Passwort verschlüsselt wurde), bleibt korTTY das alte Wert unverändert, meldet, wie viele Elemente betroffen sind, und notiert dies im Protokoll — diese Geheimnisse behalten das alte Passwort und müssen manuell neu eingegeben werden. Wenn automatischer Login aktiv ist, wird das in `~/.kortty/master.autounlock` gespeicherte Passwort mit dem neuen Master-Passwort überschrieben, sodass das automatische Entsperrungsverhalten nach der Änderung weiterhin funktioniert.