# Verbindungen

korTTY verwaltet SSH-, Mosh- und **Local-Shell**-Verbindungen über drei Einstiegspunkte: die **Schnellverbindung**, den **Connection-Manager** und gespeicherte **Projekte**.

![Connection flow](../assets/diagrams/connection-flow.svg)

## Schnellverbindung

Öffnen mit ++ctrl+k++ (oder **Verbindungen → Schnellverbindung…**). Geben Sie Host, Port, Benutzernamen und Authentifizierung ein und stellen Sie eine Verbindung her, ohne zu speichern. Häufig verwendete Verbindungen werden als Schnellschaltflächen angezeigt. Eine Live-Suche filtert sie. Gespeicherte Verbindungen können aus einem Dropdown-Menü mit einem eigenen Suchfeld ausgewählt werden, das nach Name, Host oder [Tag](#tags) filtert (`*` funktioniert als Platzhalter); Das Tag einer gespeicherten Verbindung wird neben ihrem Namen (🏷) im Dropdown-Menü und in den QuickInfos der Schnellschaltflächen angezeigt.

## Connection-Manager

**Verbindungen → Verbindungen verwalten…** öffnet einen durchsuchbaren Baum gespeicherter Verbindungen (optional gruppiert); Das Suchfeld durchsucht Name, Host, IP-Adresse oder [Tag](#tags), mit `*` als Platzhalter. Von hier aus können Sie Verbindungen erstellen, bearbeiten, duplizieren, löschen, markieren, importieren und exportieren.

## Verbindung erstellen/bearbeiten

Der Verbindungseditor verfügt über folgende Registerkarten:

| Registerkarte | Inhalt |
| --- | --- |
| Verbindung | Host, Port, Benutzername, Protokoll (SSH / Mosh / Lokale Shell), Terminalemulation, **Zeichenkodierung** (Standard verwenden / UTF-8 / ISO-8859-1 / ISO-8859-15 / Windows-1252), Authentifizierung (Passwort / Schlüssel / keyboard-interactive), **Host-Key-Prüfung** (Standard verwenden / prüfen / nicht prüfen), Gruppen-/Ordnerzuweisung und ein optionaler Freitext [Tag](#tags). Für **Lokale Shell** Verbindungen sind Host, Port, Benutzername und Authentifizierung nicht erforderlich und deaktiviert. Siehe [Zeichenkodierung](#zeichenkodierung). |
| Terminaleinstellungen | Farben pro Verbindung, Schriftart, ANSI/TrueColor-Behandlung, Terminaleffekt |
| SSH-Tunnel | Lokale / Remote- / dynamische Portweiterleitung |
| Jump Server | Bastion-Host-Verkettung |
| Terminal-Logging | Schreibt den Terminal-Ausgang dieser Verbindung in eine Datei – Ordner, Format, tägige Rotation, Kompression und Aufbewahrung. Siehe [Terminal-Logging](terminal.md#terminalprotokollierung). |
| Journal | Pro Verbindung [Sitzungsjournal](session-journal.md): Aktivieren Sie das Journaling für diese Verbindung und konfigurieren Sie das Capture-Log und die KI-Zusammenfassung |
| Fenstergeometrie | Gespeicherte Größe/Position für diese Verbindung |
| KI | KI-Standardeinstellungen pro Verbindung: die [KI-Profil](ai-assistant.md) und KI-Skills, die von Terminal-KI-Funktionen auf dieser Verbindung verwendet werden |

### Zeichenkodierung

**Zeichenkodierung** legt fest, wie korTTY dekodiert, was die Sitzung dieser Verbindung ausgibt und wie Sie eingeben und einfügen, für Server deren Programme noch ISO-8859-1, ISO-8859-15 oder Windows-1252 statt UTF-8 schreiben. **Standard verwenden** folgt [Einstellungen → Terminal → Kodierung](../reference/settings/terminal.md#hinweise) für SSH-Verbindungen und bedeutet UTF-8 für lokale Shells. Mosh funktioniert nur mit UTF-8, daher ist das Dropdown bei Mosh-Verbindungen gesperrt und daneben steht eine entsprechende Notiz. Die Auswahl wird mit der Verbindung gespeichert, bleibt bei Duplizieren, Exportieren und Importieren erhalten und gilt beim nächsten Verbinden oder Wiederverbinden des Tabs.

## Tags

Jede gespeicherte Verbindung kann ein optionales Freitext-**Tag** tragen – eine Bezeichnung wie `prod`, `staging` oder einen Kundennamen – unabhängig von der Gruppen-/Ordnerhierarchie. Legen Sie es auf der Registerkarte *Verbindung* des Verbindungseditors (neben der Gruppe) oder gesammelt im Connection-Manager fest. Tags werden mit der Verbindung in `connections.xml` gespeichert und überstehen das Duplizieren, Exportieren und Importieren.

- **Sichtbar** – markierte Verbindungen zeigen ein 🏷-Symbol nach ihrem Namen im Connection-Manager-Baum und im Dropdown-Menü „Gespeicherte Verbindungen“ der Schnellverbindung; Das Tag erscheint auch im Tooltip der häufig verwendeten Schnellschaltflächen.
- **Durchsuchbar** – Die Connection-Manager-Suche (Registerkarten „Lokal“ und „Teamwork“) und die Suche nach gespeicherten Verbindungen der Schnellverbindung berücksichtigen Tags ebenso wie Namen und Hosts.
- **Massenzuweisung/-entfernung** – Wählen Sie einen oder mehrere Server aus und wählen Sie **Tag zuordnen** aus dem Kontextmenü, um sie in einem Schritt zu taggen. Die Eingabeaufforderung ist vorab ausgefüllt, wenn alle ausgewählten Verbindungen bereits dasselbe Tag haben. Durch das Löschen dieses vorab ausgefüllten Werts wird das Tag entfernt. **Tag entfernen** löscht das Tag und ist nur aktiviert, solange die Auswahl mindestens eine markierte Verbindung enthält. Die gleichen zwei Einträge im Kontextmenü eines Ordners gelten für jede Verbindung in diesem Ordner, einschließlich Unterordnern.
- **Nach Tag exportieren** – Sobald mindestens ein Tag vorhanden ist, bietet das Exportdialogfeld des Connection-Managers die Option **Zu exportierende Verbindungen**: Behalten Sie die vorab ausgewählten Verbindungen bei oder exportieren Sie **alle Verbindungen mit diesen Tags** – wählen Sie ein oder mehrere Tags aus der Liste aus, die Verbindungsanzahl des Headers folgt der Auswahl live und die Schaltfläche „Exportieren“ bleibt deaktiviert, solange nichts übereinstimmt.

## Protokolle

=== "SSH"
    Standard SSH über Apache MINA SSHD. Unterstützt Passwort-, Public-Key- und keyboard-interactive-Authentifizierung, Keep-Alive und anklickbare OSC 8-Hyperlinks für Web- und Mailadressen (siehe [Links in der Terminalausgabe](terminal.md#links-in-der-terminalausgabe)).

=== "Mosh"
    Roaming, latenzfreundlicher Mosh-Transport (mosh4j). Das Mosh-Backend ist in nativen Builds gebündelt; Bestehende Verbindungen benötigen keine Migration.

=== "Lokale Shell"
    Öffnet die **lokale Maschine**-Shell in einem Terminal-Tab (ohne Netzwerk) über einen pty4j-gebackenen Pseudoterminal. Host, Port, Benutzername und Authentifizierung sind nicht erforderlich. Siehe unten [Lokale Shell](#lokale-shell).

## SSH-Hostschlüsselüberprüfung

Interactive Terminal- und SFTP-Verbindungen verwenden denselben TOFU-Hostschlüsselspeicher (Trust-on-First-Use). Mosh verwendet es auch für den SSH-Bootstrap. Die Vertrauenswürdigkeit wird durch den normalisierten Hostnamen und Port bestimmt, sodass verschiedene gespeicherte Verbindungen zum selben Endpunkt eine gemeinsame Entscheidung treffen.

Bei der ersten Verbindung zeigt korTTY den Schlüsselalgorithmus und den OpenSSH-SHA-256-Fingerabdruck an. Verifizieren Sie diesen Fingerabdruck mit dem Serveradministrator, bevor Sie **Ja** auswählen; **Nein** ist die sichere Standardeinstellung. Ein übereinstimmender Schlüssel wird bei späteren Verbindungen stillschweigend akzeptiert. Wenn der Server einen anderen Schlüssel präsentiert, blockiert korTTY die Verbindung hart, zeigt den erwarteten und angebotenen Fingerabdruck an und versucht nicht erneut, da ein wiederholter Versuch einen möglichen Man-in-the-Middle-Angriff nicht lösen kann. Ein geänderter Schlüssel wird niemals automatisch ersetzt.

Wenn ein Server legitimerweise neu aufgebaut wurde, bietet die Warnung „SSH-Host-Schlüssel wurde geändert“ eines Terminal-Tabs oder des SFTP-Managers neben **Schließen**, das die Standardauswahl bleibt, die Option **Prüfen und ersetzen…** an. Die Prüfung zeigt den derzeit vertrauenswürdigen Schlüssel und den neuen Schlüssel, jeweils mit ihrem Algorithmus und SHA-256-Fingerabdruck. **Schlüssel ersetzen und verbinden** bleibt deaktiviert, bis Sie **Ich habe den neuen Fingerabdruck mit dem Serveradministrator verifiziert** ankreuzen, und **Abbrechen** ist die Standardtaste, sodass ++enter++ niemals bestätigt. Nach Ihrer Bestätigung ersetzt korTTY den vertrauenswürdigen Key und die gleiche Verbindung bleibt aktiv. Der Ersatz gelingt nur, solange der vertrauenswürdige Key noch derselbe ist, den Sie überprüft haben: wenn ein anderes Fenster ihn in der Zwischenzeit geändert oder entfernt hat, bleibt die Verbindung blockiert und Sie verbinden sich erneut, um den aktuellen Key zu prüfen. Ein geänderter Key eines Jump-Servers erhält die gleiche Prüfung, wenn die Verbindung von einem Terminal-Tab oder dem SFTP-Manager geöffnet wurde.

Andere Verbindungen bieten niemals die Ersetzung an, sodass nichts ohne Ihre Aufmerksamkeit auf einen Prüfdialog wartet: das SSH-Bootstrap von Mosh-Sitzungen, Remote-Editoren und Bildbetrachter, die mit einem Projekt wiederhergestellt wurden, sowie andere Hintergrundübertragungen. Ihre Benachrichtigung verweist stattdessen auf **Konfiguration → Sicherheit → Bekannte Hosts…**. Während eine Unternehmensrichtlinie `enforce-host-key-check` setzt, kann korTTY weder einen vertrauenswürdigen Key ersetzen noch entfernen; die Benachrichtigung sagt dies, und Ihr Administrator muss den Key aktualisieren.

**Konfiguration → Sicherheit → Bekannte Hosts…** listet jeden vertrauenswürdigen Schlüssel mit Host, Port, Algorithmus, SHA-256-Fingerabdruck und dem Zeitpunkt der Vertrauensstellung. Das Suchfeld filtert nach Host, Port, Algorithmus oder Fingerabdruck und ignoriert die Groß-/Kleinschreibung. **Entfernen…** fordert eine Bestätigung an, wobei **Nein** die Standardauswahl ist, und löscht den Schlüssel nur, wenn er noch den angezeigten Fingerabdruck besitzt; die nächste Verbindung zu diesem Server zeigt erneut die Erstverwendungsmeldung an. Wenn der Speicher nicht gelesen werden kann, zeigt das Dialogfeld den Fehler an und bietet keine Aktionen.

Das erste Benutzerprompt kann für Hosts, bei denen es nicht gewünscht ist, deaktiviert werden – setzen Sie **Host-Key-Prüfung** im Verbindungsersteller auf der *Verbindung*-Registerkarte oder in der Schnellverbindung (**Standard verwenden** / **Verifizieren** / **Nicht verifizieren**), pro Gruppe über das Kontextmenü des Connection-Managers oder global unter **Einstellungen → Terminal**. Die Entspannung ist auf Akzeptanz neuer beschränkt: Ein unbekannter Schlüssel wird ohne Prompt festgelegt, ein Schlüssel, der von einem bereits für diesen Host festgelegten abweicht, wird jedoch weiterhin blockiert. Siehe [Entspannung der Host-Key-Prüfung](security.md#lockere-uberprufung-des-hostschlussels).

Die interaktiven Pins werden atomar in `~/.kortty/ssh-host-keys.properties` gespeichert, wobei ein Cross-Process-Locking verwendet wird, sodass zwei korTTY-Fenster sich nicht gegenseitig überschreiben können. Das Entfernen des letzten vertrauenswürdigen Schlüssels löscht die Datei, die korTTY als leeren Speicher behandelt. Diese auf Endpunkten basierenden Pins sind getrennt von den auf der Verbindungs-ID basierenden Pins, die vom unbemannten JobScheduler SSH, SFTP und Rsync verwendet werden.

Wenn eine neue geteilte Verbindung geöffnet wird, wird der SSH-Handshake auf einem Worker ausgeführt, während ein Fortschrittsdialog dafür sorgt, dass die JavaFX-Schnittstelle reagiert. Dadurch können sowohl die Hostschlüsselbestätigung als auch die interaktive Tastaturauthentifizierung abgeschlossen werden, ohne dass die Benutzeroberfläche blockiert wird.

## Lokale Shell

Eine **Lokale Shell**-Verbindung erzeugt ein lokales Pseudo-Terminal (PTY) auf Ihrem eigenen Computer, anstatt eine Verbindung zu einem Remote-Host herzustellen. Es ist sowohl in der **Schnellverbindung** als auch im **Connection-Manager** auswählbar; Für diese Verbindungen sind Host, Port, Benutzername und Authentifizierung nicht erforderlich (und in den Dialogen deaktiviert), und es wird keine Passwortabfrage angezeigt.

### Eine Shell auswählen

| Plattform | Optionen |
| --- | --- |
| Windows | **PowerShell** (Standard) oder **cmd.exe**. **Git Bash**, **Cygwin** und **WSL** werden ebenfalls als Voreinstellungen angeboten – allerdings nur, wenn sie tatsächlich installiert sind (Git Bash/Cygwin werden über ihre üblichen Installationsorte / `PATH` erkannt; WSL erscheint nur, wenn `wsl.exe` vorhanden und mindestens eine Distribution installiert ist). |
| macOS / Linux | Standardmäßig Ihr `$SHELL` (Rückfall auf `/bin/zsh` oder `/bin/bash`). |

Ein Freiformfeld **Benutzerdefinierter Befehl** akzeptiert jede ausführbare Datei mit Argumenten (z. B. `pwsh.exe`, `wsl.exe -d Ubuntu`, ein Git-Bash-Pfad) und ein optionales **Startverzeichnis** kann festgelegt werden. Der Befehlsparser erkennt Anführungszeichen, sodass Shell-Pfade, die Leerzeichen enthalten – wie `"C:\Program Files\Git\bin\bash.exe"` – korrekt gestartet werden.

Wenn korTTY über sein Flatpak-Paket ausgeführt wird, wird die lokale Shell auf dem Host über `flatpak-spawn --host` gestartet, einschließlich des ausgewählten Startverzeichnisses und des Terminalgebietsschemas. Das Paket verfügt über Host-Dateisystemzugriff, sodass Terminaldateiaktionen Hostpfade verwenden können. Da die Sandbox-seitige Prozess-ID zu `flatpak-spawn` und nicht zur Host-Shell gehört, verwendet die Verfolgung des aktuellen Verzeichnisses vertrauenswürdige absolute Eingabeaufforderungspfade, anstatt `/proc/<pid>/cwd` zu lesen. Wenn kein sicherer Hostpfad eingerichtet werden kann, werden pfadabhängige Aktionen mit einem expliziten Fehler beendet.

### Terminalfunktionen in lokalen Shells

Die Terminalprotokollierung und -aufzeichnung sowie die KI-Eingabe-/Daten-Hooks funktionieren für lokale Shells über eine gemeinsam genutzte `ObservableTtyConnector`-Schnittstelle. Eingegebene und eingefügte Agentenanforderungen verwenden denselben Eingabepfad auf Byteebene, und Terminaldateiaktionen sowie lokale Agentenausführungen folgen dem aktuellen Verzeichnis der interaktiven Shell. macOS/Linux verwenden das lokale Prozessverzeichnis; Native PowerShell und cmd verwenden absolute Eingabeaufforderungspfade. WSL, Git Bash, Cygwin und benutzerdefinierte Befehle eignen sich am besten, wenn sich ihr Shell-Pfad-Namespace vom Host-Dateisystem unterscheidet und ein nicht zuordenbares Verzeichnis einen expliziten Fehler anstelle eines Fallbacks auf eine falsche Datei erzeugt. Funktionen, die von einem SSH-Kanal abhängen, bleiben nur SSH.

!!! note "AI Agent in lokalen Shells"
    Der **KI-Agent** und die **KI-Planung** laufen ebenfalls in lokalen Shells unter Windows, macOS und Linux – siehe [KI-Assistenz](ai-assistant.md#ai-agent-und-ki-planung).

## Tunnels und Sprungserver

- **SSH-Tunnel** – Ports über die Verbindung weiterleiten: **lokal** (`-L`), **remote** (`-R`) oder **dynamisch / SOCKS** (`-D`).
- **Jump-Server (Bastion)** – Leiten Sie die Verbindung über einen Zwischenhost weiter; Sowohl SSH-Terminal- als auch SFTP-Sitzungen springen darüber. Siehe [Jump Server](jump-server.md).

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

## Import von anderen Clients

**Verbindungen → Importieren…** liest Verbindungsdateien von **MTPuTTY**, **MobaXterm** und **PuTTY Connection Manager**, mit Gruppenfilterung und Anmeldeinformationsverarbeitung.

!!! note "Mehr folgt"
    Diese Seite ist Teil des Scaffolded-Anleitungs. Als nächstes wird die vollständige Funktionsbibliothek – SFTP, Snippets, JobScheduler, KI-Assistent und -Tools, Terminalaufzeichnung, Sicherheit und die vollständigen Einstellungstabellen – ausgefüllt.
