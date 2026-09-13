---
title: Steuerungs-API
---

# Steuerungs-API

korTTY kann einen **lokalen** Steuerungs-Socket öffnen, damit ein Skript oder ein Coding-Agent auf demselben Rechner Ihre Fenster auflisten, den Inhalt eines Terminalbereichs lesen und darin tippen kann. Es ist das maschinenseitige Gegenstück zu dem, was Sie mit Maus und Tastatur tun: nichts davon geht über das hinaus, was Sie selbst in einem offenen Bereich tun könnten.

Die Funktion ist **standardmäßig aus** und muss pro Installation unter **Einstellungen › Terminal › Steuerungs-API** eingeschaltet werden. Über das Netzwerk ist zu keinem Zeitpunkt etwas erreichbar, und kein anderer Benutzer desselben Rechners kann sich verbinden.

!!! warning "Machen Sie sich klar, was Sie einschalten"
    Solange die Steuerungs-API an ist, kann jedes Programm, das unter Ihrem Benutzerkonto auf diesem Rechner läuft, jeden offenen Bereich lesen – lokale Shells wie SSH-Sitzungen – und beliebig darin tippen, einschließlich ++enter++. Das ist dieselbe Macht, als säße jemand an Ihrer Tastatur. Schalten Sie es ein, wenn ein Coding-Agent oder ein Skript korTTY steuern soll, und wieder aus, wenn nicht.

## Einschalten

1. Öffnen Sie **Einstellungen › Terminal** und blättern Sie zu **Steuerungs-API**.
2. Setzen Sie den Haken bei **Einem lokalen Programm erlauben, dieses korTTY zu lesen und zu steuern**.
3. Speichern. Die Statuszeile darunter sagt sofort, was passiert ist – ein Neustart ist nicht nötig.

| Statuszeile | Bedeutung |
| --- | --- |
| Status: aus | Der Haken ist nicht gesetzt, oder korTTY hat den Listener beendet. |
| Status: von Ihrer Organisation gesperrt | Die Unternehmensrichtlinie verbietet die Funktion `control-api`; das Kontrollkästchen ist gesperrt. |
| Status: lauscht auf … | Ein Listener läuft und `endpoint.json` wurde geschrieben. Der Text nennt den Socket oder den Loopback-Port. |
| Status: konnte nicht starten – … | Der Listener ist nicht gestartet. Der häufigste Grund ist, dass bereits ein anderes korTTY den Socket besitzt; der Text sagt welcher. |

Das Entfernen des Hakens beendet den Listener und löscht den Socket sofort.

## Wo der Endpunkt liegt

Alles, was die API besitzt, liegt in einem eigenen Verzeichnis, `~/.kortty/control/`, das **vor** jedem Binden mit Rechten nur für den Eigentümer (`0700`) angelegt wird. Das Verzeichnis, nicht die Socket-Datei, ist der eigentliche Schutz: eine Socket-Inode entsteht mit der umask des Prozesses, die auf den meisten Systemen für alle lesbar ist.

| Plattform | Listener |
| --- | --- |
| Linux, macOS | Ein Unix-Domain-Socket unter `~/.kortty/control/control.sock` |
| Windows | TCP auf `127.0.0.1` mit einem vom Betriebssystem vergebenen Port |

Ein Client findet den Endpunkt, indem er `~/.kortty/control/endpoint.json` (Modus `0600`) liest:

```json
{"transport":"unix","path":"/home/you/.kortty/control/control.sock","host":null,"port":0,
 "token":"kQ7f…","pid":38211,"app_version":"…","protocol_version":1,
 "instance_id":"6f0c2a1e-…","started_at_millis":1736000000000}
```

Die Datei wird **zuletzt** geschrieben, nachdem der Listener gebunden ist, damit ihre Existenz immer einen erreichbaren Endpunkt und ein lesbares Token bedeutet. korTTY löscht sie samt Socket, wenn die API ausgeschaltet wird und wenn die Anwendung endet.

Das Token besteht aus 32 Zufallsbytes, wird bei jedem Start neu erzeugt und ist auf **beiden** Transportwegen erforderlich. Unter Linux und macOS ist das Verzeichnis mit Eigentümerrechten der primäre Schutz und das Token die zweite Verteidigungslinie – gegen eine gelockerte umask, ein geteiltes oder über das Netz eingebundenes Home-Verzeichnis oder eine Sicherung, die das Verzeichnis an einen lesbaren Ort kopiert hat. Unter Windows ist es der einzige Schutz. Es wird nie protokolliert, nie in einer Fehlermeldung wiedergegeben, nie auf einer Kommandozeile übergeben und nie in einer Benachrichtigung gezeigt: der legitime Client liest es aus der Datei, und genau darum geht es.

## Das Protokoll sprechen

Das Protokoll ist zeilenweises JSON-RPC 2.0 – genau ein JSON-Objekt pro Zeile, UTF-8, höchstens 1 MiB pro Zeile. Die erste Anfrage einer Verbindung muss `auth` sein; alles andere schließt die Verbindung. Ein falsches Token schließt sie ebenfalls.

```
→ {"jsonrpc":"2.0","id":1,"method":"auth","params":{"token":"kQ7f…","client":"my-script"}}
← {"jsonrpc":"2.0","id":1,"result":{"api":"kortty-control","protocol_version":1,"transport":"unix","ids_survive_restart":false,"capabilities":["events","split","agent_start","pane_resolve"]}}
```

Anfragen einer Verbindung werden **nacheinander** ausgeführt: die nächste Zeile wird erst gelesen, wenn die vorige Antwort in der Warteschlange liegt. Ein Client, der gleichzeitig lange warten und andere Aufrufe absetzen will, öffnet eine zweite Verbindung – bis zu acht gleichzeitig.

Statt die Methodenliste hier zu wiederholen, fragen Sie korTTY: `api.schema` liefert die vollständige maschinenlesbare Oberfläche – jede Methode mit Parametern, Ergebnisform, Fehlern, CLI-Entsprechung und einem ausgearbeiteten Beispiel, dazu das Tastenvokabular, die Fehlertabelle und die Grenzwerte. Sie wird aus denselben Deklarationen erzeugt, die der Dispatcher registriert, kann also nicht von der Implementierung abweichen.

### Was die Methoden tun

| Gruppe | Methoden | Zweck |
| --- | --- | --- |
| `api` | `ping`, `auth`, `api.schema` | Handshake und Erkundung. |
| `events` | `events.subscribe`, `events.unsubscribe` | Meldungen, wenn ein Coding-Agent seinen Zustand ändert. |
| `window` / `tab` | `window.list`, `tab.list`, `tab.focus` | Fenster und Tabs auflisten und eines nach vorn holen. |
| `pane` | `pane.list`, `pane.current`, `pane.get`, `pane.resolve`, `pane.focus`, `pane.read`, `pane.send_text`, `pane.run`, `pane.send_keys`, `pane.wait_output`, `pane.split`, `pane.close` | Einen Bereich lesen, darin tippen, auf Ausgabe warten, eine lokale Shell teilen oder einen geteilten Bereich schließen. |
| `agent` | `agent.list`, `agent.get`, `agent.explain`, `agent.prompt`, `agent.send_keys`, `agent.wait`, `agent.rename`, `agent.start` | Mit den von korTTY erkannten Coding-Agents arbeiten – siehe [Coding-Agents](../features/coding-agents.md). |
| `notification` | `notification.show` | Eine Desktop-Benachrichtigung auslösen. |

`tab.create`, `tab.close` und `tab.rename` sind **reserviert**: sie antworten mit einem eindeutigen „in dieser Version nicht implementiert“ statt mit einem Unbekannte-Methode-Fehler und stehen in `api.schema` als reserviert, damit ein Client „korTTY wird das nie für dich tun“ von „du hast dich vertippt“ unterscheiden kann.

### Einen Bereich adressieren

| Kennung | Form | Lebensdauer |
| --- | --- | --- |
| Fenster | `w1` | Die Lebensdauer des Fensters. Es ist ein Zähler, keine Position, damit das Schließen eines anderen Fensters nie neu durchnummeriert. |
| Tab | `t` + eine UUID | Die Lebensdauer dieser Tab-Instanz. |
| Bereich | `p` + eine kurze Hex-Zeichenkette | Die Lebensdauer des Bereichs. |
| Vollständig | `w1:t9f3a…:p1a2b3c4d` | Wie oben. |

Jeder Parameter namens `pane` akzeptiert auch eine reine Tab-Kennung – gemeint ist dann der fokussierte Bereich dieses Tabs – und den Alias `@focused`, also den Bereich, den Sie gerade ansehen. Eine reine **Fenster**-Kennung dort, wo ein Bereich erwartet wird, wird als mehrdeutig abgelehnt statt geraten: ein Fenster enthält meist mehrere Bereiche, und stillschweigend einen auszuwählen ist genau der Weg, auf dem ein Skript in das falsche Terminal tippt.

!!! note "Keine Kennung überlebt einen Neustart"
    Kennungen werden pro korTTY-Lauf vergeben. Jede Antwort mit Kennungen trägt auch die `instance` des laufenden korTTY, und jede verändernde Methode akzeptiert einen optionalen Parameter `instance`: passt er nicht, schlägt der Aufruf mit `stale_instance` fehl, statt in den Bereich zu schreiben, der die Kennung geerbt hat. Listen Sie neu auf, sobald sich `instance` ändert.

### Einen Bereich lesen

`pane.read` kennt drei Modi. `visible` liefert den aktuellen Bildschirm einschließlich des Alternativpuffers, rechts beschnitten. `recent` liefert den Scrollback plus den Bildschirm, unter einer einzigen Puffersperre gelesen, die neuesten `lines` Zeilen. `detection` liefert das, was korTTYs Coding-Agent-Erkennung zuletzt für den Bereich veröffentlicht hat – die Erkennung wird für eine Anfrage nie neu ausgeführt.

`pane.wait_output` blockiert, bis ein regulärer Ausdruck oder eine Zeichenkette erscheint, und fragt den Bereich dafür regelmäßig ab. Ausgabe, die innerhalb eines Abfrageintervalls erscheint und wieder weggescrollt wird, kann im Modus `visible` übersehen werden; der Standardmodus `recent` durchsucht auch den Scrollback und hat diese Lücke nicht.

### Teilen

`pane.split` funktioniert **nur** bei einem Bereich, dessen Tab eine lokale Shell ist. Alles andere wird mit `unsupported` abgelehnt. Die neue Shell startet ohne jeden Dialog, damit ein Skript nie auf ein Fenster wartet, das es nicht sehen kann; ein Split, der eine neue Verbindung bräuchte, braucht einen Menschen und wird nicht angeboten.

`pane.close` verweigert den **letzten** Bereich eines Tabs. Ihn zu schließen hinterließe einen leeren Terminalbereich in einem noch offenen Tab – genau deshalb ist korTTYs eigener Menüpunkt „Split schließen“ in diesem Fall ausgegraut. Schließen Sie den Tab selbst.

## Was sie nicht kann

Das Bedrohungsmodell ist ein lokaler Prozess, der als Sie läuft, während die API an ist. Ein solcher Prozess **kann** Ihre Fenster auflisten, jeden Bereich lesen, in jeden Bereich tippen einschließlich des Absendens von Befehlen, eine lokale Shell teilen, einen geteilten Bereich schließen, einen erkannten Coding-Agent steuern und eine Benachrichtigung auslösen.

Er **kann nicht**:

* irgendetwas erreichen, bevor Sie den Haken setzen, oder überhaupt, wenn die Unternehmensrichtlinie die Funktion verbietet;
* einen neuen Tab, ein neues Fenster oder irgendeine Verbindung öffnen – es gibt keinen Weg zu einem Server, der nicht schon offen ist;
* etwas anderes teilen als eine lokale Shell desselben Servers;
* einen Tab schließen, korTTYs letzten Bereich schließen oder einen Tab umbenennen;
* Ihre Einstellungen lesen oder schreiben, Ihre Zugangsdaten, den Tresor, Ihre SSH-Schlüssel oder das Master-Passwort lesen;
* irgendetwas über das Netzwerk erreichen – der Listener ist ein Unix-Socket oder Loopback, nie etwas anderes, und keine Einstellung kann das aufweiten;
* handeln, ohne eine Auditzeile zu hinterlassen, oder ohne eine Desktop-Benachrichtigung beim ersten Schreibzugriff des Laufs.

!!! note "Schreibzugriffe sind absichtlich nicht auf lokale Shells beschränkt"
    Auch in entfernte Bereiche darf getippt werden. Eine Beschränkung würde das Beantworten eines über SSH laufenden Coding-Agents unmöglich machen und wäre ohnehin keine echte Grenze: wer den Socket erreicht, kann stattdessen einfach `ssh` in einem lokalen Bereich starten. Die ehrlichen Kontrollen sind der standardmäßig ausgeschaltete Schalter, das Richtlinienverbot, die Auditzeile und die Benachrichtigung – nicht ein Filter, der wie eine Grenze aussieht und keine ist.

## Audit und Sichtbarkeit

Jede Aktion der API schreibt genau eine Zeile in korTTYs Protokoll, und zwar **nur Byte-Anzahlen und Formen** – `bytes=28 bracketed=false submitted=true`, `keys=2`, `orientation=vertical` – niemals Terminaltext. Die Zeile steht in korTTYs Protokoll und nur dort – in das Sitzungsjournal eines Tabs schreibt die API nichts.

Beim ersten Tippen eines Programms in einen Bereich während eines korTTY-Laufs erhalten Sie eine Desktop-Benachrichtigung. Eine, nicht eine pro Tastendruck: es geht darum, dass eine Übernahme nie unbemerkt bleibt, nicht darum, dass sie zu Lärm wird, den man wegzuklicken lernt. korTTY protokolliert außerdem eine Warnung mit dem Endpunkt, sobald der Listener startet.

Aktionen, die ein Coding-Agent über die API ausführt, werden unterscheidbar von denen protokolliert, die Sie im Coding-Agents-Panel auslösen – ein Blick ins Protokoll sagt also, was was war.

## Grenzwerte

| Grenzwert | Wert |
| --- | --- |
| Gleichzeitige Verbindungen | 8; eine neunte wird abgelehnt und geschlossen |
| Zeilengröße, ein- und ausgehend | 1 MiB |
| Ergebnisgröße | 512 KiB, mit `truncated: true` gekürzt |
| Nicht authentifizierte Verbindung | nach 5 s geschlossen |
| Längste Wartezeit | 10 Minuten; eine größere Anforderung wird gekappt und sagt es |
| Ereigniswarteschlange je Abonnement | 256 Rahmen; bei Überlauf fallen die ältesten weg und die Anzahl wird gemeldet |
| Zeilen bei `pane.read` | 10 000 |

Ein Client, der nicht mehr liest, wird getrennt statt gepuffert.

## Unternehmensrichtlinie

Eine Administratorin kann die Steuerungs-API mit der Funktion `control-api` in `kortty-policy.toml` vollständig verbieten:

```toml
[[rule]]
name = "no-local-automation"
  [rule.features]
  control-api = "deny"
```

Das Kontrollkästchen wird dann mit dem Hinweis „Von Ihrer Organisation verwaltet“ gesperrt, die Einstellung wird beim Laden wie beim Speichern auf aus gezwungen, und der Listener antwortet sofort mit `blocked_by_policy` – die Prüfung erfolgt bei jeder Anfrage, nicht nur beim Annehmen einer Verbindung, sodass eine Richtlinienänderung den Dienst einstellt, bevor der Abbau fertig ist. Siehe [Richtlinienkonfiguration](enterprise-policy.md).

!!! note
    Auch `control-api = "allow"` sperrt das Kontrollkästchen – und zwar im **eingeschalteten** Zustand. Eine Richtliniendatei, die eine Einstellung erwähnt, übernimmt sie, egal wie sie entscheidet. Lassen Sie den Schlüssel ganz weg, um die Wahl beim Benutzer zu belassen.

## Der Client

Der Befehl `kortty-cli`, der neben korTTY ausgeliefert wird, spricht dieses Protokoll und übernimmt Endpunktsuche, Authentifizierung und Exit-Codes für Sie. Siehe [Steuerungs-CLI](cli.md).
