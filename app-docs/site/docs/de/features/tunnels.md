---
title: SSH-Tunnel (Portweiterleitung)
---

# SSH-Tunnel (Portweiterleitung)

SSH-Tunnels leiten den Datenverkehr sicher zwischen lokalen und entfernten Ports über eine verschlüsselte SSH-Verbindung weiter. korTTY unterstützt drei Tunnelarten: lokales Port-Forwarding, entferntes Port-Forwarding und dynamisches Port-Forwarding (SOCKS-Proxy). Sie funktionieren wie die Optionen `-L`, `-R` und `-D` von OpenSSH und laufen über die Login-Sitzung des Terminal-Tabs, sodass kein zweites Login erforderlich ist.

## Tunnel konfigurieren

1. Öffnen Sie eine Verbindung zum Bearbeiten: **Verbindungen > Verbindungen verwalten** → Verbindung auswählen → **Bearbeiten**.
2. Navigieren Sie zur Registerkarte **SSH-Tunnel**.
3. Klicken Sie auf **Hinzufügen** und konfigurieren Sie den Tunnel.

### Tunnelkonfigurationsfelder

| Feld | Lokal (`-L`) | Remote (`-R`) | Dynamisch (`-D`) |
|-------|--------------|---------------|----------------|
| **Typ** | `LOCAL` | `REMOTE` | `DYNAMIC` |
| **Lokaler Host** | Adresse, an die der Listener auf Ihrem Computer bindet (`localhost` bleibt privat) | Ihr Computer leitet die Verbindungen an | Adresse, an die der SOCKS-Proxy auf Ihrem Computer bindet |
| **Lokaler Port** | Port, der auf Ihrem Computer lauscht | Port des Dienstes auf Ihrem Computer | Port des SOCKS-Proxys |
| **Remote Host** | Zielhost, wie er vom SSH-Server gesehen wird | Adresse, an die der SSH-Server lauscht (Standard `localhost`) | Nicht verwendet |
| **Remote Port** | Zielport | Port, an dem der SSH-Server lauscht | Nicht verwendet |
| **Beschreibung** | Optionaler Bezeichner für den Tunnel | Optionaler Bezeichner | Optionaler Bezeichner |
| **Tunnel aktivieren** | Nur aktivierte Tunnel werden geöffnet | Nur aktivierte Tunnel werden geöffnet | Nur aktivierte Tunnel werden geöffnet |

Mehrere Tunnel können pro Verbindung konfiguriert werden. Deaktivierte Tunnel bleiben in der Konfiguration, werden aber nicht geöffnet, wenn die Verbindung hergestellt wird.

## Tunneltypen

### Lokale Portweiterleitung (`-L`)

Leiten Sie einen lokalen Port über den SSH-Tunnel an einen Remotedienst weiter.

```
Your machine:8080  -->  SSH Server  -->  database-server:5432
```

**Beispielanwendungsfall:** Greifen Sie auf einen Remote-Datenbankserver zu, der von Ihrem Computer aus nicht direkt erreichbar ist.

- **Lokaler Host:** `localhost`
- **Lokaler Port:** `8080`
- **Remote Host:** `database-server` (oder IP)
- **Remote Port:** `5432`

Sobald Sie verbunden sind, verbinden Sie sich mit der Remote-Datenbank über `localhost:8080` auf Ihrem Rechner.

### Remote-Port-Weiterleitung (`-R`)

Machen Sie einen lokalen Dienst auf dem SSH-Server zugänglich.

```
SSH Server localhost:9090  -->  Your machine:3000
```

**Beispielanwendung:** Lassen Sie ein Programm auf dem SSH-Server einen Entwicklungsserver auf Ihrem Rechner erreichen.

- **Remote Host:** `localhost` (die Adresse, auf der der SSH-Server lauscht)
- **Remote Port:** `9090` (der Port, auf dem der SSH-Server lauscht)
- **Lokaler Host:** `localhost`
- **Lokaler Port:** `3000` (Ihr lokaler Dienst)

Nach dem Verbinden erreichen Programme auf dem SSH-Server Ihren Dienst unter `localhost:9090`. Wie OpenSSH bittet korTTY den Server, nur auf seiner Loopback-Adresse zu lauschen. Durch Setzen von **Remote Host** auf `0.0.0.0` fordert der Server, auch Verbindungen aus seinem Netzwerk zu akzeptieren; dies geschieht nur, wenn die Einstellung `GatewayPorts` des Servers dies zulässt, und korTTY kennzeichnet einen solchen Tunnel in der Statusleiste als von anderen Computern erreichbar.

### Dynamische Portweiterleitung (`-D`)

Erstellen Sie einen SOCKS-Proxy für Netzwerkverkehr über den SSH-Tunnel.

```
Your machine:1080  -->  SSH Server  -->  (any destination)
```

**Beispielhafter Anwendungsfall:** Verschlüsseln Sie den gesamten Datenverkehr von Ihrem Browser oder Ihrer Anwendung, indem Sie ihn durch den SSH-Tunnel leiten.

- **Lokaler Port:** `1080` (oder jeder verfügbare Port)

Konfigurieren Sie Ihren Browser oder Ihre Anwendung so, dass sie `localhost:1080` als SOCKS5-(oder SOCKS4)-Proxy verwendet. Hostnamen werden vom SSH-Server aufgelöst.

!!! note
    Für Dynamic Port Forwarding werden nur der lokale Host und der lokale Port verwendet. Die Felder für Remote-Host und Remote-Port werden ignoriert.

## Wenn Tunnel geöffnet sind

- **Einmal pro Terminal-Tab:** die Tunnel öffnen sich unmittelbar nach dem Login in der ersten SSH-Sitzung des Tabs und bleiben offen, solange der Tab verbunden ist. Aufgeteilte Panes öffnen sie nicht erneut und stehen auch nicht im Konflikt mit ihnen.
- **Einmal gefragt:** Beim ersten Öffnen der Tunnel einer Verbindung listet korTTY sie auf und fragt **Tunnel öffnen** oder **Nicht jetzt**. **Nicht jetzt** ist die Standardschaltfläche, sodass ein Tastendruck, den Sie noch in das Terminal eingeben, wenn die Frage erscheint, sie nicht öffnet. Die Antwort wird für diese Verbindung gespeichert; korTTY fragt erneut, wenn sich die Tunnel oder der Server ändern. **Nicht jetzt** gilt für den Tab, einschließlich seiner Neuverbindungen – öffnen Sie die Verbindung in einem neuen Tab, um erneut gefragt zu werden.
- **Neu verbinden:** Eine manuelle oder automatische Neuverbindung schließt zunächst die Tunnel und öffnet sie anschließend in der neuen Sitzung.
- **Pane schließen:** Schließen Sie den Pane, in dem die Tunnel laufen (oder geben dort `exit` ein), während ein mit **Rechts teilen (gleicher Server)** oder **Unten teilen (gleicher Server)** geöffneter Pane offen bleibt, werden die Tunnel in diesen Pane verschoben. Mit **Rechts teilen (neue Verbindung)** oder **Unten teilen (neue Verbindung)** geöffnete Pane übernehmen sie nie, selbst wenn sie sich mit demselben Server verbinden.
- **Tab schließen** schließt seine Tunnel.
- **Zwei Tabs, eine Verbindung:** Jeder Tab öffnet eigene Tunnel, sodass im zweiten Tab ein lokaler oder dynamischer Tunnel meldet, dass die Adresse bereits verwendet wird, und ein Remote-Tunnel meldet, dass der SSH-Server ihn abgelehnt hat. Der erste Tab bleibt funktionsfähig.
- **Nicht geöffnet** von SFTP-Tabs, Mosh-Verbindungen (die Statusleiste sagt, dass Tunnel das SSH-Protokoll benötigen), oder für Aufteilungen zu einem anderen Server.
- **Serverseitig:** Der SSH-Server muss Weiterleitung zulassen (`AllowTcpForwarding` in OpenSSH). Ein Remote-Tunnel, den der Server ablehnt (Weiterleitung deaktiviert oder sein Port bereits auf dem Server in Benutzung), wird in der Statusleiste angezeigt; die anderen Tunnel bleiben offen.

### Statusleiste

Während ein Tab Tunnel hat, zeigt die Statusleiste `Tunnel: 2/3 aktiv`, gefolgt vom ersten Tunnel, der fehlgeschlagen ist und warum (zum Beispiel `Address already in use`) sowie dem ersten Tunnel, den andere Computer erreichen können. Bewegen Sie die Maus über die Statusleiste, um jeden Tunnel mit seinem Zustand anzuzeigen.

### Geteilte Verbindungen (Teamarbeit)

Tunnel einer Verbindung, die von einer [Teamarbeit](teamwork.md)-Quelle stammt, wurden von der Person geschrieben, die die gemeinsame Datei bearbeitet; daher beschränkt korTTY sie:

- Remote-Tunnel werden nie geöffnet, egal welche Adresse sie angeben: Loopback auf einem gemeinsamen SSH-Server ist von jedem Konto darauf erreichbar.
- Lokale und dynamische Tunnel können nur auf `localhost` hören.
- Der Rest erfordert noch Ihre einmalige Bestätigung, die darauf hinweist, dass die Tunnel aus der gemeinsamen Datei stammen.

Um einen anderen Tunnel zu verwenden, erstellen Sie Ihre eigene Verbindung zum selben Server und fügen dort den Tunnel hinzu; gemeinsame Verbindungen können im Connection-Manager nicht dupliziert werden.

### Organisationsrichtlinie

Ein Administrator kann alle Tunnel mit `allow-port-forwarding = false` in der [Unternehmensrichtlinie](../reference/enterprise-policy.md) verbieten. Die Statusleiste sagt dann, dass Tunnel von Ihrer Organisation deaktiviert sind.

## Tunnel verwalten

- **Aktivieren/Deaktivieren:** Schalten Sie **Tunnel aktivieren** an einem Tunnel ein, um ihn zu aktivieren oder zu deaktivieren, ohne die Konfiguration zu entfernen.
- **Bearbeiten:** Wählen Sie einen Tunnel aus und ändern Sie seine Einstellungen.
- **Entfernen:** Entfernen Sie einen Tunnel von der Verbindung.
- **Mehrere Tunnel:** Auf einer einzigen Verbindung können beliebig viele Tunnel konfiguriert und gleichzeitig geöffnet werden.

Änderungen treten beim nächsten Verbindungsaufbau oder bei der Wiederverbindung des Tabs in Kraft.

!!! warning
    Ports unter 1024 (wie 80 oder 443) erfordern erhöhte Privilegien: auf Ihrem Computer für lokale und dynamische Tunnel sowie ein Root-Login auf dem SSH-Server für entfernte Tunnel. Verwenden Sie Ports ab 1024, um Berechtigungsprobleme zu vermeiden.
