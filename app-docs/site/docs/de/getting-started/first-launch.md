# Erster Start – Master-Passwort

Beim ersten Start fordert korTTY Sie auf, ein **Master-Passwort** zu erstellen. Dieses Passwort verschlüsselt alle gespeicherten Verbindungspasswörter, SSH-Schlüsselpassphrasen und Anmeldeinformationen mit **AES-256-GCM**.

1. Geben Sie ein Passwort ein (mindestens 6 Zeichen). Der Feldrand wird **grün**, wenn er lang genug ist, und **rot**, wenn er zu kurz ist; Ein Stärkeindikator bewertet die Qualität. Bei einem schwachen oder gebräuchlichen Passwort wird eine Warnung angezeigt, es kann jedoch weiterhin verwendet werden, wenn Sie es bestätigen.
2. Bestätigen Sie das Passwort.
3. Lassen Sie die Option **Anonyme Nutzungsstatistiken teilen** aktiviert, um korTTY zu verbessern, oder deaktivieren Sie das Kontrollkästchen, wenn Sie lieber nichts teilen möchten. Es ist vorab ausgewählt, aber Sie haben die Wahl, bevor Sie es bestätigen – was erfasst wird, ist vollständig anonym und DSGVO-konform, und die Einstellung kann jederzeit unter **Einstellungen → Datenschutz** geändert werden. Die Schaltfläche **?** öffnet [Anonyme Daten zur Anwendungsoptimierung](../about/anonymous-data.md). Eine Organisationsrichtlinie, die Telemetrie verbietet, sperrt das Kontrollkästchen und lässt es leer.
4. Klicken Sie auf **Setup**.

Bei nachfolgenden Starts werden Sie aufgefordert, das Master-Passwort einzugeben, um Ihre verschlüsselten Daten zu entsperren.

!!! warning "Deaktivieren der Eingabeaufforderung"
    **Einstellungen → Sicherheit** bietet zwei Möglichkeiten, die Eingabeaufforderung zu überspringen. Wenn Sie **Beim Start Master-Passwort anfordern** ausschalten, wird die Eingabeaufforderung ausgeblendet, aber auf gespeicherte Passwörter kann erst dann zugegriffen werden, wenn Sie das Master-Passwort manuell eingeben. **Deaktivieren Sie die Eingabeaufforderung für das Master-Passwort beim Start (automatische Anmeldung)**. Stattdessen wird der Tresor automatisch über eine auf der Festplatte gespeicherte Kopie Ihres Master-Passworts entsperrt – nur verschleiert, nicht verschlüsselt, also behalten Sie es für Wegwerf- oder Testumgebungen auf. Weitere Informationen finden Sie in den [-Sicherheitseinstellungen ](../reference/settings/security.md), einschließlich unbeaufsichtigter Erststarts, bei denen der Einrichtungsdialog vollständig übersprungen wird.

## Was ist verschlüsselt?

| Daten | Datei | Schutz |
| --- | --- | --- |
| Verbindungspasswörter | `~/.kortty/connections.xml` | AES-256-GCM (vom Master-Passwort abgeleiteter Schlüssel) |
| Gespeicherte Anmeldeinformationen | `~/.kortty/credentials.xml` | AES-256-GCM |
| SSH-Schlüsselpassphrasen | `~/.kortty/ssh-keys.xml` | AES-256-GCM |
| Master-Passwort | `~/.kortty/master.key` | Salted Hash (nur Verifizierung) |
| Master-Passwort gespeichert (nur automatische Anmeldung) | `~/.kortty/master.autounlock` | nur verschleiert – nicht verschlüsselt; Nur-Eigentümer-Dateiberechtigungen |

Das vollständige Verschlüsselungs- und Sicherungsmodell finden Sie in der [-Sicherheitsreferenz ](../features/connections.md).

[Weiter: Übersicht über das Hauptfenster →](main-window.md){ .md-button }
