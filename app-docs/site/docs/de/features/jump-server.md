---
title: Jump-Server (Bastion Host)
---

# Jump-Server (Bastion Host)

Ein Jump-Server (Bastion-Host) fungiert als Zwischengateway, um Server in einem privaten Netzwerk zu erreichen. korTTY tunnelt die Verbindung über einen Jump-Server, sodass Sie von Ihrem lokalen Computer aus ein internes System erreichen können, ohne direkten Netzwerkzugriff darauf zu haben.

## Verbindungsfluss

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

korTTY authentifiziert sich beim Jump-Server mit den eigenen Anmeldeinformationen des Jump-Servers, öffnet einen Tunnel dadurch und öffnet dann die echte SSH-Sitzung zum Zielhost über diesen Tunnel. Die Anmeldeinformationen des Ziels werden nur für das Ziel verwendet, und die Anmeldeinformationen des Jump-Servers werden nur für den Jump-Server verwendet – keinem Host wird das Kennwort oder der Schlüssel des anderen angeboten.

Sowohl SSH-Terminals als auch SFTP-Verbindungen zum Ziel laufen über den Jump-Server; für SFTP muss nichts zusätzlich konfiguriert werden. Das funktioniert unabhängig von der eigenen Authentifizierung des Ziels – Passwort, keyboard-interactive, SSH-Schlüssel oder ein temporärer SSH-Schlüssel: ein gespeichertes Jump-Server-Passwort wird in jedem Fall mit Ihrem Master-Passwort entschlüsselt.

!!! warning
    Mosh-Verbindungen können nicht über einen Jump-Server laufen: Eine Mosh-Sitzung läuft über UDP, das vom SSH-Tunnel (TCP) des Jump-Servers nicht weitergeleitet wird. korTTY lehnt die Kombination von vornherein ab – die Registerkarte „Jump Server“ warnt, sobald sie konfiguriert ist, und die Verbindung schlägt sofort mit einer eindeutigen Meldung fehl, anstatt nach dem SSH-Bootstrap ins Stocken zu geraten. Verwenden Sie das SSH-Protokoll für Ziele hinter einer Bastion oder deaktivieren Sie den Jump-Server.

## Konfiguration

So konfigurieren Sie einen Jump-Server für eine Verbindung:

1. Öffnen Sie den *Connection-Manager* und bearbeiten (oder erstellen) Sie eine Verbindung.
2. Gehen Sie zur Registerkarte **Jump-Server**.
3. **Jump-Server** aktivieren.
4. Geben Sie die Details des Jump-Servers ein:
    - **Host** – Hostname oder IP-Adresse des Jump-Servers
    - **Port** – SSH-Port (Standard: 22)
    - **Benutzername** – Anmeldebenutzername für den Jump-Server
5. Wählen Sie eine **Authentifizierungsmethode**:
    - **Passwort** – das Passwort wird verschlüsselt mit Ihrem Master-Passwort gespeichert. Lassen Sie das Feld beim Bearbeiten leer, um das zuvor gespeicherte Passwort beizubehalten.
    - **SSH-Schlüsseldatei (keine Passphrase)** – der Pfad zu einer unverschlüsselten privaten Schlüsseldatei. Passphrase-geschützte Schlüssel werden für den Jump Hop nicht unterstützt.
6. Klicken Sie auf **Speichern**.

## Wenn der Jump-Server nicht verwendet werden kann

korTTY entschlüsselt ein gespeichertes Jump-Server-Passwort, bevor es den Jump-Server kontaktiert. Falls das gespeicherte Jump-Passwort nicht verwendet werden kann – keines ist gespeichert, der Master-Passwort-Tresor ist gesperrt oder der gespeicherte Wert lässt sich nicht entschlüsseln – stoppt die Verbindung sofort mit einer Meldung, ohne den Jump-Server zu kontaktieren, ohne nach dessen Host-Key zu fragen und ohne die Wiederholungsanzahl durchzugehen. Entsperren Sie den Tresor mit **Konfiguration > Sicherheit > Tresor entsperren…** (siehe [Später den Tresor entsperren](security.md#spater-den-tresor-entsperren)) oder speichern Sie das Passwort im **Jump-Server**-Tab der Verbindung, dann verbinden Sie sich erneut.

Ein unvollständiges Jump-Setup (kein Benutzername, oder Schlüssel-Authentifizierung ohne nutzbaren Schlüsseldatei) und ein von Ihnen abgelehnter Jump-Server-Host-Key werden ebenfalls nur einmal gemeldet, anstatt erneut versucht zu werden, weil ein weiterer Versuch identisch scheitern würde. Ein Jump-Server, der nicht erreichbar ist, oder ein Netzwerkfehler auf dem Weg, wird wie jede andere Verbindungsfehlermeldung erneut versucht.

## Host-Schlüsselüberprüfung

Das Host-Key des Jump-Servers wird beim ersten Einsatz genau wie jedes andere Host-System überprüft: korTTY zeigt den SHA-256-Fingerprint des Schlüssels und bittet Sie, ihn zu bestätigen, danach wird er gespeichert. Bei späteren Verbindungen wird ein geändertes Host-Key des Jump-Servers abgelehnt, genauso wie die gleiche 'Trust-on-First-Use'-Sicherheit dem Ziel-Host gewährt wird. Der Bastion wird stets streng überprüft: [Die Entspannung der Host-Key-Überprüfung](security.md#lockere-uberprufung-des-hostschlussels) gilt ausschließlich für Ziel-Hosts und wird nie für den Jump-Hop angewendet.

Dies gilt auch dann, wenn die Host-Key-Überprüfung für das Ziel gelockt wurde: Die pro-Verbindung, pro-Gruppe und globale Ausnahmen betreffen den Bastion nie, sodass dessen Schlüssel stets streng überprüft wird. Siehe [Entspannung der Host-Key-Überprüfung](security.md#lockere-uberprufung-des-hostschlussels).

Der Zielhost wird unter seinem eigenen Namen verifiziert, auch wenn der Transport durch den Tunnel erfolgt, sodass eine kompromittierte Bastion nicht unbemerkt einen anderen Zielhostschlüssel ersetzen kann.

## Wann es verwendet werden soll

- Zielserver befinden sich hinter einer Firewall und sind nur über einen Bastion-Host erreichbar.
- Sicherheitsrichtlinie erfordert Routing über ein Gateway.
- Die interne Infrastruktur verwendet private Adressen und benötigt einen Vermittler für den Zugriff.

!!! note
    Jump-Server-Passwörter werden zusammen mit den Anmeldeinformationen der Verbindung verschlüsselt mit Ihrem Master-Passwort gespeichert. Da das gespeicherte Passwort nie wieder angezeigt wird, bleibt das gespeicherte Passwort erhalten, wenn Sie die Verbindung mit einem leeren Jump-Server-Passwortfeld bearbeiten. Geben Sie einen neuen Wert ein, nur um ihn zu ändern. Wenn der Master-Passwort-Tresor beim Speichern eines neuen Jump-Passworts gesperrt ist, meldet korTTY, dass das Jump-Passwort nicht gespeichert werden konnte und lässt die anderen Einstellungen unberührt.
