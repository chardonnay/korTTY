---
title: Jump-Server (Bastion Host)
---

# Jump Server (Bastion Host)

Ein Jump-Server (Bastion-Host) fungiert als Zwischengateway, um Server in einem privaten Netzwerk zu erreichen. korTTY tunnelt die Verbindung über einen Jump-Server, sodass Sie von Ihrem lokalen Computer aus ein internes System erreichen können, ohne direkten Netzwerkzugriff darauf zu haben.

## Verbindungsfluss

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

korTTY authentifiziert sich beim Jump-Server mit den eigenen Anmeldeinformationen des Jump-Servers, öffnet einen Tunnel dadurch und öffnet dann die echte SSH-Sitzung zum Zielhost über diesen Tunnel. Die Anmeldeinformationen des Ziels werden nur für das Ziel verwendet, und die Anmeldeinformationen des Jump-Servers werden nur für den Jump-Server verwendet – keinem Host wird das Kennwort oder der Schlüssel des anderen angeboten.

Sowohl SSH-Terminal- als auch SFTP-Verbindungen zur Zielroute über den Jump-Server; Für SFTP muss nichts extra konfiguriert werden.

!!! warning
    Mosh-Verbindungen werden nicht über einen Jump-Server übertragen. Moshs SSH-Bootstrap springt durch die Bastion, aber seine laufende Sitzung verwendet UDP, das der SSH-Tunnel des Jump-Servers nicht weiterleitet – sodass eine Mosh-Verbindung, deren Ziel nur über die Bastion erreichbar ist, nicht hergestellt wird. Verwenden Sie für solche Ziele das SSH-Protokoll.

## Konfiguration

So konfigurieren Sie einen Jump-Server für eine Verbindung:

1. Öffnen Sie den *Verbindungsmanager* und bearbeiten (oder erstellen) Sie eine Verbindung.
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

## Host-Schlüsselüberprüfung

Der Host-Schlüssel des Jump-Servers wird bei der ersten Verwendung genau wie jeder andere Host überprüft: korTTY zeigt den SHA-256-Fingerabdruck des Schlüssels an, fordert Sie zur Bestätigung auf und heftet ihn dann fest. Bei späteren Verbindungen wird ein geänderter Jump-Server-Schlüssel abgelehnt, der gleiche Vertrauensschutz bei der ersten Verwendung erhält der Zielhost.

Dies gilt auch dann, wenn die Überprüfung des Host-Schlüssels für das Ziel gelockert wurde: Die verbindungs-, gruppen- und globalen Opt-outs decken nie die Bastion ab, sodass ihr Schlüssel immer streng überprüft wird. Siehe [Lockere Hostschlüsselüberprüfung](security.md#relaxing-host-key-verification).

Der Zielhost wird unter seinem eigenen Namen verifiziert, auch wenn der Transport durch den Tunnel erfolgt, sodass eine kompromittierte Bastion nicht unbemerkt einen anderen Zielhostschlüssel ersetzen kann.

## Wann es verwendet werden soll

- Zielserver befinden sich hinter einer Firewall und sind nur über einen Bastion-Host erreichbar.
- Sicherheitsrichtlinie erfordert Routing über ein Gateway.
- Die interne Infrastruktur verwendet private Adressen und benötigt einen Vermittler für den Zugriff.

!!! note
    Jump-Server-Passwörter werden zusammen mit den Anmeldeinformationen der Verbindung verschlüsselt mit Ihrem Master-Passwort gespeichert. Da das gespeicherte Passwort nie wieder angezeigt wird, bleibt das gespeicherte Passwort erhalten, wenn Sie die Verbindung mit einem leeren Jump-Server-Passwortfeld bearbeiten. Geben Sie einen neuen Wert ein, nur um ihn zu ändern. Wenn der Master-Passwort-Tresor beim Speichern eines neuen Jump-Passworts gesperrt ist, meldet korTTY, dass das Jump-Passwort nicht gespeichert werden konnte und lässt die anderen Einstellungen unberührt.
