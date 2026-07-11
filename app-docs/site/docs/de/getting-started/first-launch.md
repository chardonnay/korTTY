# Erster Start – Master-Passwort

Beim ersten Start fordert korTTY Sie auf, ein **Master-Passwort** zu erstellen. Dieses Passwort verschlüsselt alle gespeicherten Verbindungspasswörter, SSH-Schlüsselpassphrasen und Anmeldeinformationen mit **AES-256-GCM**.

1. Geben Sie ein Passwort ein (mindestens 6 Zeichen). Der Feldrand wird **grün**, wenn er lang genug ist, und **rot**, wenn er zu kurz ist; Ein Stärkeindikator bewertet die Qualität. Bei einem schwachen oder gebräuchlichen Passwort wird eine Warnung angezeigt, es kann jedoch weiterhin verwendet werden, wenn Sie es bestätigen.
2. Bestätigen Sie das Passwort.
3. Aktivieren Sie optional **Anonyme Nutzungsstatistiken teilen**, um korTTY zu verbessern. Dies ist standardmäßig deaktiviert, vollständig anonym und DSGVO-konform und kann jederzeit unter **Einstellungen → Datenschutz** geändert werden. Die Schaltfläche **?** öffnet [Anonyme Daten zur Anwendungsoptimierung](../about/anonymous-data.md).
4. Klicken Sie auf **Setup**.

Bei nachfolgenden Starts werden Sie aufgefordert, das Master-Passwort einzugeben, um Ihre verschlüsselten Daten zu entsperren.

!!! warning "Deaktivieren der Eingabeaufforderung"
    Sie können die Aufforderung zum Entsperren unter **Einstellungen → Sicherheit** deaktivieren, aber auf gespeicherte Passwörter kann erst dann zugegriffen werden, wenn Sie das Master-Passwort manuell eingeben.

## Was ist verschlüsselt?

| Daten | Datei | Schutz |
| --- | --- | --- |
| Verbindungspasswörter | `~/.kortty/connections.xml` | AES-256-GCM (vom Master-Passwort abgeleiteter Schlüssel) |
| Gespeicherte Anmeldeinformationen | `~/.kortty/credentials.xml` | AES-256-GCM |
| SSH-Schlüsselpassphrasen | `~/.kortty/ssh-keys.xml` | AES-256-GCM |
| Master-Passwort | `~/.kortty/master-password-hash` | gesalzener Hash (nur Verifizierung) |

Das vollständige Verschlüsselungs- und Sicherungsmodell finden Sie in der [Sicherheitsreferenz](../features/connections.md)].

[Weiter: Übersicht über das Hauptfenster →](main-window.md){ .md-button }
