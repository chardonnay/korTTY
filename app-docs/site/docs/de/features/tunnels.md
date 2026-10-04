---
title: SSH-Tunnel (Portweiterleitung)
---

# SSH-Tunnel (Portweiterleitung)

SSH-Tunnels leiten den Datenverkehr sicher zwischen lokalen und entfernten Ports über eine verschlüsselte SSH-Verbindung weiter. korTTY unterstützt drei Tunnelarten: lokales Port-Forwarding, entferntes Port-Forwarding und dynamisches Port-Forwarding (SOCKS-Proxy). Sie funktionieren wie die Optionen `-L`, `-R` und `-D` von OpenSSH und laufen über die Login-Sitzung des Terminal-Tabs, sodass kein zweites Login erforderlich ist.

## Tunnel konfigurieren

1. Öffnen Sie eine Verbindung zum Bearbeiten: **Verbindungen > Verbindungen verwalten** → Verbindung auswählen → **Bearbeiten**.
2. Navigieren Sie zur Registerkarte **SSH-Tunnel**.
3. Klicken Sie auf **Hinzufügen**, wählen Sie den **Typ** und füllen Sie die Felder aus. Ein neuer Tunnel startet mit angekreuztem **Tunnel aktivieren**.
4. Klicken Sie auf **Speichern** im Tunnel-Dialog, dann auf **Speichern** im Verbindungseditor. **Abbrechen** im Verbindungseditor verwirft jeden Tunnel, den Sie seit dem Öffnen hinzugefügt, bearbeitet oder entfernt haben.

### Tunnelkonfigurationsfelder

Der Tunnel-Dialog benennt seine Felder nach der Rolle, die sie für den gewählten Typ spielen, und listet immer zuerst die Adresse auf, die lauscht, gefolgt von der Adresse, zu der die Verbindungen weitergeleitet werden.

| Feld | **Lokal (-L)** | **Remote (-R)** | **Dynamischer SOCKS-Proxy (-D)** |
|-------|----------------|-----------------|------------------------------|
| Lauscht auf | **Lokale Bind-Adresse** und **Lokaler Port** auf Ihrem Computer | **Remote-Bind-Adresse** und **Remote-Port** auf dem SSH-Server | **Lokale Bind-Adresse** und **Lokaler Port** auf Ihrem Computer |
| Weiterleitung zu | **Remote-Host** und **Remote-Port**, wie vom SSH-Server gesehen | **Lokaler Host** und **Lokaler Port**, wie von Ihrem Computer aus gesehen | Unabhängig vom Ziel, das der SOCKS-Client anfordert |
| Standard-Bind-Adresse | `localhost` | `localhost` | `localhost` |
| **Beschreibung** | Optionaler Bezeichner | Optionaler Bezeichner | Optionaler Bezeichner |
| **Tunnel aktivieren** | Nur aktivierte Tunnel werden geöffnet | Nur aktivierte Tunnel werden geöffnet | Nur aktivierte Tunnel werden geöffnet |

Eine Bindungsadresse von `localhost` (oder ein leeres Feld) hält den Listener privat für den Computer, auf dem er läuft. Sobald Sie eine andere Adresse eingeben, z. B. `0.0.0.0` oder ein Netzwerkinterface, warnt das Dialogfeld, dass andere Computer den Tunnel erreichen können; bei einem Remote-Tunnel fügt die Warnung hinzu, dass der SSH-Server eine solche Adresse nur akzeptiert, wenn seine `GatewayPorts`-Einstellung dies zulässt.

Mehrere Tunnel können pro Verbindung konfiguriert werden. Deaktivierte Tunnel bleiben in der Konfiguration, werden aber nicht geöffnet, wenn die Verbindung hergestellt wird.

### Die Tunnelliste

Der Tab **SSH-Tunnel** listet jeden Tunnel in der Reihenfolge, wie die `ssh`-Optionen gelesen werden, Listener zuerst: `✓ L localhost:8080 -> db:5432`, `✓ R localhost:9090 -> localhost:3000` oder `○ D localhost:1080 (SOCKS)`, gefolgt von seiner Beschreibung. `✓` markiert einen aktivierten Tunnel und `○` einen deaktivierten. Ein Tunnel, der von anderen Computern erreicht werden kann, ist in der Liste entsprechend gekennzeichnet, sodass ein vergessener `0.0.0.0` auffällt.

**SSH-Tunnel aktivieren** über der Liste schaltet alle Tunnel der Verbindung gleichzeitig ein oder aus. Es ist aktiviert, wenn alle Tunnel aktiv sind und zeigt einen Bindestrich an, wenn nur einige aktiv sind; ein Klick auf den Bindestrich aktiviert alle. Einen einzelnen Tunnel zu schalten erfolgt über **Tunnel aktivieren** in seinem eigenen Dialog.

## Tunneltypen

### Lokale Portweiterleitung (`-L`)

Leiten Sie einen lokalen Port über den SSH-Tunnel an einen Remotedienst weiter.

```
Your machine:8080  -->  SSH Server  -->  database-server:5432
```

**Beispielanwendungsfall:** Greifen Sie auf einen Remote-Datenbankserver zu, der von Ihrem Computer aus nicht direkt erreichbar ist.

- **Lokale Bind-Adresse:** `localhost`
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

- **Remote-Bind-Adresse:** `localhost` (die Adresse, auf der der SSH-Server lauscht)
- **Remote Port:** `9090` (der Port, auf dem der SSH-Server lauscht)
- **Lokaler Host:** `localhost` (wo Ihr Computer jede Verbindung weiterleitet)
- **Lokaler Port:** `3000` (Ihr lokaler Dienst)

Nach dem Verbinden erreichen Programme auf dem SSH-Server Ihren Dienst unter `localhost:9090`. Wie OpenSSH fordert korTTY den Server auf, nur auf seiner Loopback-Adresse zu lauschen. Das Setzen von **Remote-Bind-Adresse** auf `0.0.0.0` fordert den Server auf, Verbindungen aus seinem Netzwerk ebenfalls zu akzeptieren; ein OpenSSH-Server tut dies nur, wenn seine `GatewayPorts`-Einstellung `clientspecified` ist, und korTTY kennzeichnet einen solchen Tunnel im Tunnel-Dialog, in der Tunnelliste und in der Statusleiste als von anderen Computern erreichbar. Ein Server mit `GatewayPorts yes` lauscht auf allen seinen Adressen für jeden Remote-Tunnel, egal welche Bind-Adresse Sie eingeben.

### Dynamische Portweiterleitung (`-D`)

Erstellen Sie einen SOCKS-Proxy für Netzwerkverkehr über den SSH-Tunnel.

```
Your machine:1080  -->  SSH Server  -->  (any destination)
```

**Beispielhafter Anwendungsfall:** Verschlüsseln Sie den gesamten Datenverkehr von Ihrem Browser oder Ihrer Anwendung, indem Sie ihn durch den SSH-Tunnel leiten.

- **Lokale Bind-Adresse:** `localhost`
- **Lokaler Port:** `1080` (oder jeder verfügbare Port)

Konfigurieren Sie Ihren Browser oder Ihre Anwendung so, dass sie `localhost:1080` als SOCKS5-(oder SOCKS4)-Proxy verwendet. Hostnamen werden vom SSH-Server aufgelöst.

!!! note
    Ein dynamischer Tunnel verwendet nur die lokale Bindadresse und den lokalen Port; die Remote-Felder sind für diesen Typ deaktiviert.

## Wenn Tunnel geöffnet sind

- **Einmal pro Terminal-Tab:** die Tunnel öffnen sich unmittelbar nach dem Login in der ersten SSH-Sitzung des Tabs und bleiben offen, solange der Tab verbunden ist. Aufgeteilte Panes öffnen sie nicht erneut und stehen auch nicht im Konflikt mit ihnen.
- **Einmal gefragt:** Beim ersten Öffnen der Tunnel einer Verbindung listet korTTY sie auf und fragt **Tunnel öffnen** oder **Nicht jetzt**. **Nicht jetzt** ist die Standardschaltfläche, sodass ein Tastendruck, den Sie noch in das Terminal eingeben, wenn die Frage erscheint, sie nicht öffnet. Die Antwort wird für diese Verbindung gespeichert; korTTY fragt erneut, wenn sich die Tunnel oder der Server ändern. **Nicht jetzt** gilt für den Tab, einschließlich seiner Neuverbindungen – öffnen Sie die Verbindung in einem neuen Tab, um erneut gefragt zu werden.
- **Neu verbinden:** Eine manuelle oder automatische Neuverbindung schließt zunächst die Tunnel und öffnet sie anschließend in der neuen Sitzung.
- **Bereiche schließen:** Wenn Sie den Bereich schließen, in dem die Tunnel laufen (oder dort `exit` eingeben), während ein Bereich offen bleibt, der mit **Rechts teilen (gleicher Server)** oder **Unten teilen (gleicher Server)** aus einem Bereich auf dem Server des Tabs geöffnet wurde, wechseln die Tunnel in diesen Bereich. Bereiche, die mit **Rechts teilen (neue Verbindung)** oder **Unten teilen (neue Verbindung)** geöffnet wurden, und die daraus erstellten Teilungen zum gleichen Server übernehmen sie nie, auch wenn sie sich mit demselben Server verbinden.
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

- **Aktivieren/Deaktivieren:** Schalten Sie **Tunnel aktivieren** an einem Tunnel oder **SSH-Tunnel aktivieren** für alle, um Tunnel zu aktivieren oder zu deaktivieren, ohne die Konfiguration zu entfernen.
- **Bearbeiten:** Wählen Sie einen Tunnel aus und klicken Sie auf **Bearbeiten**, um dessen Einstellungen zu ändern.
- **Entfernen:** wählen Sie einen Tunnel aus und klicken Sie auf **Entfernen**, um ihn von der Verbindung zu löschen.
- **Mehrere Tunnel:** beliebig viele Tunnel können auf einer einzigen Verbindung konfiguriert und gleichzeitig geöffnet werden.

Beim Speichern der Verbindung wird die Änderung sofort auf jeden offenen Tab dieser Verbindung angewendet: ausgeschaltete oder entfernte Tunnel werden geschlossen, und ein geänderter Satz von Tunneln ersetzt den laufenden in derselben Sitzung – nach der einmaligen Frage, sofern Sie genau diesen Satz nicht bereits zugelassen haben. Ein Tab, dessen Tunnel sich nicht geändert haben, hält sie offen, sodass die Bearbeitung nur einer Beschreibung oder einer anderen Einstellung der Verbindung sie nicht unterbricht. Ein Tab, der zu diesem Zeitpunkt getrennt ist oder sich noch anmeldet, öffnet die gespeicherten Tunnel, sobald er verbunden ist.

## Importierte Tunnel

Tunnel, die aus dem PuTTY Connection Manager importiert wurden, behalten ihren Typ, ihre Ports und den Zielhost bei. Das CSV-Format nennt keine Bind-Adresse, daher bindet korTTY jeden importierten Listener an `localhost`, was auch PuTTYs Standard für Remote-Tunnel ist. Remote-Tunnels, die frühere Versionen importierten, wurden mit der Remote-Bind-Adresse `0.0.0.0` gespeichert; die Tunnelliste, das Tunnel-Dialogfeld und die Statusleiste kennzeichnen sie als von anderen Computern erreichbar. Setzen Sie daher deren **Remote-Bind-Adresse** auf `localhost`, wenn der weitergeleitete Port privat für den Server bleiben soll.

!!! warning
    Ports unter 1024 (wie 80 oder 443) erfordern erhöhte Privilegien: auf Ihrem Computer für lokale und dynamische Tunnel sowie ein Root-Login auf dem SSH-Server für entfernte Tunnel. Verwenden Sie Ports ab 1024, um Berechtigungsprobleme zu vermeiden.
