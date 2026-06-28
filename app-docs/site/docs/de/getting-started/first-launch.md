# Erster Start – Master-Passwort

Beim ersten Start fordert korTTY Sie auf, ein **Master-Passwort** zu erstellen. Dieses Passwort
verschlüsselt alle gespeicherten Verbindungspasswörter, SSH-Schlüsselpassphrasen und Anmeldeinformationen
unter Verwendung von **AES-256-GCM**.

1. Geben Sie ein Passwort ein (mindestens 6 Zeichen). Der Feldrand wird **grün**, wenn
lang genug und **rot**, wenn zu kurz; Ein Stärkeindikator bewertet die Qualität.
Bei einem schwachen oder gebräuchlichen Passwort wird eine Warnung angezeigt, es kann jedoch weiterhin verwendet werden, wenn Sie es bestätigen.
2. Bestätigen Sie das Passwort.
3. Klicken Sie auf **Setup**.

Bei nachfolgenden Starts werden Sie aufgefordert, zum Entsperren das Master-Passwort einzugeben
Ihre verschlüsselten Daten.

!!! Warnung „Die Eingabeaufforderung wird deaktiviert“
Sie können die Aufforderung zum Entsperren unter **Einstellungen → Sicherheit** deaktivieren, sie wird jedoch gespeichert
Passwörter bleiben solange unzugänglich, bis Sie das Master-Passwort manuell eingeben.

## Was ist verschlüsselt?

| Daten | Datei | Schutz |
| --- | --- | --- |
| Verbindungspasswörter | `~/.kortty/connections.xml` | AES-256-GCM (vom Master-Passwort abgeleiteter Schlüssel) |
| Gespeicherte Anmeldeinformationen | `~/.kortty/credentials.xml` | AES-256-GCM |
| SSH-Schlüsselpassphrasen | `~/.kortty/ssh-keys.xml` | AES-256-GCM |
| Master-Passwort | `~/.kortty/master-password-hash` | gesalzener Hash (nur Verifizierung) |

Vollständige Informationen finden Sie in der [Sicherheitsreferenz ](../features/connections.md)
Verschlüsselungs- und Backup-Modell.

[Weiter: Übersicht über das Hauptfenster →](main-window.md){ .md-button }
