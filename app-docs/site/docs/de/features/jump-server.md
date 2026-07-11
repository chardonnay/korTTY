---
title: Jump-Server (Bastion Host)
---

# Jump Server (Bastion Host)

Ein Jump-Server (Bastion-Host) fungiert als Zwischengateway, um Server in einem privaten Netzwerk zu erreichen. KorTTY kann Verbindungen über einen Jump-Server tunneln, sodass Sie von Ihrem lokalen Computer aus auf interne Systeme zugreifen können, ohne direkten Netzwerkzugriff zu benötigen.

## Verbindungsfluss

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

Ihr lokaler Computer stellt zunächst eine Verbindung zum Jump-Server (Bastion-Host) her, der dann eine Verbindung zum Zielserver herstellt. Der gesamte Datenverkehr wird über den Jump-Server geleitet.

## Konfiguration

So konfigurieren Sie einen Jump-Server für eine Verbindung:

1. Öffnen Sie den *Verbindungsmanager* oder erstellen Sie eine neue Verbindung.
2. Bearbeiten Sie die Verbindung und wechseln Sie zur Registerkarte **Jump Server** (oder zur Registerkarte **Erweitert**).
3. Aktivieren Sie **Jump Server**.
4. Geben Sie die Details des Jump-Servers ein:
   - **Host** – Hostname oder IP-Adresse des Jump-Servers
   - **Port** – SSH-Port (Standard: 22)
   - **Benutzername** – Login-Benutzername für den Jump-Server
5. Wählen Sie eine **Authentifizierungsmethode**:
   - **Passwort** – Geben Sie das Passwort direkt ein (wird verschlüsselt mit Ihrem Master-Passwort gespeichert)
   - **SSH-Schlüssel** – Wählen Sie einen gespeicherten SSH-Schlüssel aus Ihrer Schlüsselverwaltung aus
6. Legen Sie optional einen **Auto-Befehl** fest, der nach der Verbindung mit dem Jump-Server ausgeführt wird (z. B. `ssh internal-server`).
7. Klicken Sie auf **Speichern**.

## Wie es funktioniert

Wenn Sie eine Verbindung zu einem Zielserver mit konfiguriertem Jump-Server herstellen, führt KorTTY Folgendes aus:

1. Stellt eine SSH-Verbindung zum Jump-Server her
2. Verwendet diese Verbindung, um den Zielserver zu erreichen
3. Leitet den gesamten Datenverkehr über den Jump-Server
4. Führt den optionalen automatischen Befehl auf dem Jump-Server aus

Dies ist besonders nützlich, wenn:

- Zielserver befinden sich hinter einer Firewall und sind nur über einen Bastion-Host erreichbar
- Sie müssen Sicherheitsrichtlinien einhalten, die das Routing über ein Gateway erfordern
- Die interne Infrastruktur verwendet private IPs und erfordert einen Vermittler für den Zugriff

!!! note
    Die Jump-Server-Anmeldeinformationen werden wie normale Verbindungsanmeldeinformationen verschlüsselt in KorTTY gespeichert. Ihr Master-Passwort schützt alle gespeicherten Jump-Server-Passwörter und SSH-Schlüssel-Passphrasen.
