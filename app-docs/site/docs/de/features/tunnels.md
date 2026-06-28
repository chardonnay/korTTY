---
title: SSH-Tunnel (Portweiterleitung)
---

# SSH-Tunnel (Portweiterleitung)

SSH-Tunnel leiten den Datenverkehr sicher zwischen lokalen und Remote-Ports über eine verschlüsselte SSH-Verbindung weiter. korTTY unterstützt drei Tunneltypen: lokale Portweiterleitung, Remote-Portweiterleitung und dynamische Portweiterleitung (SOCKS-Proxy).

## Tunnel konfigurieren

1. Öffnen Sie eine Verbindung zum Bearbeiten: **Verbindungen > Verbindungen verwalten** → Verbindung auswählen → **Bearbeiten**.
2. Navigieren Sie zur Registerkarte **SSH-Tunnel**.
3. Klicken Sie auf **Tunnel hinzufügen** und konfigurieren Sie den Tunnel.

### Tunnelkonfigurationsfelder

| Feld | Beschreibung |
|-------|-------------|
| **Typ** | Lokal (`-L`), Remote (`-R`) oder Dynamisch (`-D`) |
| **Lokaler Host** | Lokale Bindungsadresse (normalerweise `localhost`) |
| **Lokaler Port** | Lokale Portnummer |
| **Remote-Host** | Zielhost aus Sicht des SSH-Servers |
| **Remote-Port** | Zielport auf dem Remote-Host |
| **Beschreibung** | Optionale Beschriftung für den Tunnel |
| **Aktiviert** | Ein-/Ausschalten, ohne die Konfiguration zu löschen |

Pro Verbindung können mehrere Tunnel konfiguriert werden. Deaktivierte Tunnel bleiben in der Konfiguration erhalten, werden jedoch beim Verbindungsaufbau nicht aktiviert.

## Tunneltypen

### Lokale Portweiterleitung (`-L`)

Leiten Sie einen lokalen Port über den SSH-Tunnel an einen Remotedienst weiter.

```
Your machine:8080  -->  SSH Server  -->  database-server:5432
```

**Beispielanwendungsfall:** Greifen Sie auf einen Remote-Datenbankserver zu, der von Ihrem Computer aus nicht direkt erreichbar ist.

- **Lokaler Host:** `localhost`
- **Lokaler Port:** `8080`
- **Remote-Host:** `database-server` (oder IP)
- **Remote-Port:** `5432`

Stellen Sie nach der Konfiguration mit `localhost:8080` auf Ihrem Computer eine Verbindung zur Remote-Datenbank her.

### Remote-Port-Weiterleitung (`-R`)

Machen Sie einen lokalen Dienst über das Netzwerk des Remote-Servers zugänglich.

```
Remote:9090  -->  SSH Server  -->  Your machine:3000
```

**Beispielanwendungsfall:** Ermöglichen Sie einem Remote-Teammitglied den Zugriff auf einen lokalen Entwicklungsserver auf Ihrem Computer.

- **Remote-Port:** `9090` (der Port, den die Remote-Seite verwendet, um Ihren Dienst zu erreichen)
- **Lokaler Host:** `localhost`
- **Lokaler Port:** `3000` (Ihr lokaler Dienst)

Nach der Verbindung kann der Remote-Computer unter `localhost:9090` auf Ihren Dienst zugreifen.

### Dynamische Portweiterleitung (`-D`)

Erstellen Sie einen SOCKS5-Proxy für den gesamten Netzwerkverkehr durch den SSH-Tunnel.

```
Your machine:1080  -->  SSH Server  -->  (any destination)
```

**Beispielhafter Anwendungsfall:** Verschlüsseln Sie den gesamten Datenverkehr von Ihrem Browser oder Ihrer Anwendung, indem Sie ihn durch den SSH-Tunnel leiten.

- **Lokaler Port:** `1080` (oder jeder verfügbare Port)

Konfigurieren Sie Ihren Browser oder Ihre Anwendung so, dass `localhost:1080` als SOCKS5-Proxy verwendet wird. Der gesamte Datenverkehr wird sicher über den SSH-Server weitergeleitet.

!!! Notiz
Für die dynamische Portweiterleitung ist nur der lokale Port erforderlich. Die Felder „Remote-Host“ und „Remote-Port“ werden nicht verwendet.

## Tunnel verwalten

- **Aktivieren/Deaktivieren:** Schalten Sie das Kontrollkästchen **Aktiviert** für einen Tunnel um, um ihn zu aktivieren oder zu deaktivieren, ohne die Konfiguration zu entfernen.
- **Bearbeiten:** Wählen Sie einen Tunnel aus und ändern Sie seine Einstellungen.
- **Löschen:** Einen Tunnel aus der Verbindung entfernen.
- **Mehrere Tunnel:** Es können beliebig viele Tunnel auf einer einzigen Verbindung konfiguriert und gleichzeitig aktiviert werden.

Tunnel werden beim Öffnen der SSH-Verbindung eingerichtet und bleiben für die Dauer der Sitzung aktiv.

!!! Warnung
Wenn Sie die lokale oder Remote-Portweiterleitung an Ports unter 1024 (z. B. Port 80 oder 443) verwenden, sind für Ihr System möglicherweise erhöhte Berechtigungen erforderlich. Verwenden Sie die Ports 1024 und höher, um Berechtigungsprobleme zu vermeiden.
