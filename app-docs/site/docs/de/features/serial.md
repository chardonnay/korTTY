---
title: Serielle Verbindungen
---

# Serielle Verbindungen

Eine **Serielle** Verbindung öffnet ein Terminal auf einem seriellen Port dieses Computers statt auf einem Netzwerkhost, ähnlich den seriellen Sitzungen von PuTTY: der Konsolenport eines Routers oder Switches, ein Mikrocontroller-Board, ein Modem oder ein beliebiges Gerät hinter einem USB-zu-Seriell-Adapter. Sie ist als Protokoll sowohl in der **Schnellverbindung** als auch im **Connection-Manager** auf Windows, macOS und Linux wählbar.

Es gibt keine Anmeldung und keinen Passwort-Prompt: Was Sie eingeben, wird über die Leitung gesendet, und was das Gerät sendet, wird im Tab angezeigt. Host, Port, Benutzername und Authentifizierung werden nicht verwendet und sind in den Dialogen deaktiviert.

## Einstellungen

Wählen Sie **Seriell** als Protokoll und füllen Sie anschließend die seriellen Zeilen darunter aus.

| Einstellung | Beschreibung |
| --- | --- |
| **Serielle Schnittstelle:** | Der zu öffnende Port: `COM3` unter Windows, `/dev/ttyUSB0` oder `/dev/ttyACM0` unter Linux, `/dev/cu.usbserial-…` unter macOS. Die Liste zeigt die Ports, die beim Öffnen des Dialogs vorhanden waren, jeweils mit der Beschreibung des Treibers; mit **Aktualisieren** wird erneut gesucht, nachdem Sie einen Adapter angeschlossen haben. Sie können auch einen Port eingeben, der noch nicht angeschlossen ist. Unter macOS erscheint jeder Port einmal, als sein `cu.`-Gerät, das geöffnet wird, ohne auf ein Trägersignal zu warten. |
| **Geschwindigkeit (Baud):** | 300 bis 921600 aus der Liste oder eine andere, eingegebene Rate (bis zu 20000000). Standard: 9600. |
| **Datenbits / Parität / Stoppbits:** | 5–8 Datenbits; **Keine Parität**, **Ungerade**, **Gerade**, **Mark** oder **Space**; **1 Stoppbit**, **1,5 Stoppbits** oder **2 Stoppbits**. Standard: 8 Datenbits, keine Parität, 1 Stoppbit (8N1). |
| **Flusskontrolle:** | **Keine** (Standard), **RTS/CTS (Hardware)**, **XON/XOFF (Software)** oder **DSR/DTR**. |
| **Enter sendet:** | **CR** (Standard), **LF** oder **CR LF**: welches Zeichen die ++enter++-Taste übermittelt, für Geräte, die einen Zeilenumbruch erwarten. Andere Tasten werden unverändert gesendet. |
| **Leitungsoptionen:** | **DTR beim Verbinden setzen** und **RTS beim Verbinden setzen** (beide aktiviert, wie in PuTTY): Einige Boards, darunter Arduino, werden zurückgesetzt, wenn DTR hochgeht; deaktivieren Sie es daher, damit sie weiterlaufen. Ein Flusssteuerungsmodus, der eine Leitung verwendet, behält sie für sich, und sein Kontrollkästchen ist deaktiviert. **Lokales Echo** zeigt Ihre eigene Eingabe an, für Geräte, die diese nicht zurückgeben. |

Die Einstellungen werden mit der Verbindung gespeichert und bleiben beim Duplizieren, Exportieren und Importieren erhalten; wählen Sie eine gespeicherte serielle Verbindung in der Schnellverbindung aus, werden sie im Formular angezeigt. Eine Verbindung ohne Namen wird nach ihrem Port und ihrer Geschwindigkeit benannt, beispielsweise `COM3 @ 115200`.

**Zeichenkodierung** im Verbindungseditor gilt auch für serielle Verbindungen: **Standard verwenden** folgt *Einstellungen → Terminal → Kodierung*; viele Geräte senden reines ASCII oder ISO-8859-1.

## Arbeiten in einem seriellen Tab

**Break senden** hält die Leitung für ein Viertel einer Sekunde im Break-Zustand, wodurch ein Cisco-Gerät in den ROMMON übergeht oder ein SysRq an eine Linux-Konsole gesendet wird. Sie finden es im Kontextmenü des Terminals eines seriellen Panels sowie in **Verbindungen → Break senden** für das fokussierte Panel. Der Menüpunkt hat standardmäßig keine Taste; weisen Sie ihm eine in *Einstellungen → Tastatur* zu.

Eine serielle Leitung hat keine Fenstergröße, daher informiert das Ändern der Tab-Größe das Gerät nicht. Wenn der Adapter abgezogen wird oder sein Treiber aufgibt, bleibt der Tab geöffnet und zeigt die Meldung *Serielle Schnittstelle … getrennt* sowie das Angebot zum erneuten Verbinden an, wie bei einer abgebrochenen Netzwerkverbindung.

Beim Schließen eines seriellen Tabs wird *Serielle Schnittstelle schließen?* abgefragt, sofern die Verbindung nicht ohne Bestätigung geschlossen werden kann.

Im Connection-Manager zeigt eine serielle Verbindung ein Steckersymbol, ihre Port- und Leitungseinstellungen (`COM3 · 9600 8N1`) sowie **Serielle Schnittstelle** und **Leitung** im Inspektor. SFTP, Tunnel, der Jump-Server, die Host-Key-Prüfung, der KI-Swarm und geplante Headless-Läufe benötigen einen Netzwerk-Host und stehen für serielle Verbindungen nicht zur Verfügung.

## Importieren

Serielle Sitzungen in einer **MobaXterm**-Sitzungsdatei (`.mxtsessions`, `MobaXterm.ini`) und ein **PuTTY Connection-Manager**-CSV-Export werden als serielle Verbindungen mit ihrem Port und ihrer Geschwindigkeit importiert; die übrigen Leitungseinstellungen starten mit 8N1 ohne Flusssteuerung. Aus MobaXterm wird ein Windows-Port, der als `COM3 (USB Serial Port (COM3))` angezeigt wird, zu `COM3`.

## Berechtigungen und Fehlerbehebung

| Meldung | Empfohlene Maßnahmen |
| --- | --- |
| *Serielle Schnittstelle … wurde nicht gefunden.* | Schließen Sie den Adapter ein, drücken Sie **Aktualisieren** und wählen Sie den Port erneut aus; USB-Adapter können nach dem erneuten Einstecken einen anderen Namen erhalten. Unter Windows listet der Geräte-Manager den Port unter *Ports (COM & LPT)*. |
| *Serielle Schnittstelle … wird von einem anderen Programm verwendet.* | Schließen Sie das andere Terminalprogramm oder den Tab, der den Port geöffnet hat. korTTY öffnet einen Port exklusiv. |
| *Sie dürfen die serielle Schnittstelle … nicht öffnen.* | Fügen Sie unter Linux Ihren Benutzer der `dialout`-Gruppe (`uucp` unter Arch Linux) hinzu und melden Sie sich erneut an. |
| *… hat die Leitungseinstellungen abgelehnt.* | Der Adapter unterstützt diese Geschwindigkeit nicht; wählen Sie eine Standardrate. |

Das Flatpak-Paket verfügt über Gerätezugriff (`--device=all`), sodass serielle Ports unter `/dev` innerhalb der Sandbox sichtbar sind; die `dialout`-Gruppenmitgliedschaft auf dem Host gilt weiterhin.

## Unternehmensrichtlinie

Eine Organisation kann serielle Verbindungen mit `serial-connections = "deny"` in ihrer [Unternehmensrichtlinie](../reference/enterprise-policy.md#rulefeatures) verbieten. Der Verbindungseditor und die Schnellverbindung bieten dann für neue Verbindungen nicht mehr **Seriell** an, und das Öffnen einer gespeicherten oder importierten seriellen Verbindung wird mit *Die Richtlinie Ihrer Organisation erlaubt keine seriellen Verbindungen.* abgelehnt. Die Server-Allowlist der Richtlinie gilt nicht für serielle Ports, da diese keinen Host haben.

!!! note
    Eine serielle Sitzung läuft innerhalb von korTTY selbst. Sie kann nicht in einem eigenen Prozess oder einer eigenen Sandbox [isoliert](session-isolation.md) werden; wenn Ihre Organisation Isolation erfordert, öffnet korTTY den Port nicht.
