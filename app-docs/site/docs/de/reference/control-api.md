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

Zwei weitere Schalter unter der Statuszeile gehören zu den [MCP-Clients](#mcp-clients), etwa [`kortty-cli mcp`](cli.md#mcp-clients-bedienen), und beide sind standardmäßig aus:

| Schalter | Was er bewirkt |
| --- | --- |
| **MCP-Server** | Bedient Clients, die sich als MCP-Clients ausweisen, mit der schreibgeschützten Methodenliste, maskierter Ausgabe und begrenzten Lesezugriffen, wie unten beschrieben. |
| **Schreib-Tools erlauben** | Bietet solchen Clients zusätzlich `pane.send_text`, `pane.run` und `pane.send_keys` an. korTTY fragt Sie trotzdem vor jedem Schreibzugriff. Der Schalter lässt sich nur ändern, solange **MCP-Server** angehakt ist. |

Beide Schalter sind ausgegraut, solange das Kontrollkästchen der Steuerungs-API nicht angehakt ist, mit einem entsprechenden Hinweis; sie behalten ihre Werte und wirken wieder, sobald Sie die Steuerungs-API anhaken. Wenn die Unternehmensrichtlinie `mcp-server` oder `control-api` verweigert, sind beide nicht angehakt und mit dem Hinweis „Verwaltet von Ihrer Organisation“ gesperrt. Wenn Sie speichern, während einer der beiden Schalter aus ist, werden außerdem alle Antworten „Für diesen Bereich in dieser Sitzung erlauben“ verworfen, sodass nach erneutem Einschalten der Schreib-Tools wieder neu gefragt wird.

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

Statt die Methodenliste hier zu wiederholen, fragen Sie korTTY: `api.schema` liefert die vollständige maschinenlesbare Oberfläche – jede Methode mit Parametern, Ergebnisform, Fehlern, CLI-Entsprechung und einem ausgearbeiteten Beispiel, dazu das Tastenvokabular, die Fehlertabelle und die Grenzwerte. Parameter, Ergebnisse, Fehler und Grenzwerte entstehen aus denselben Deklarationen, die der Dispatcher registriert, und können daher nicht von der Implementierung abweichen; jede Zeile `cli` ist ein Befehl, den das ausgelieferte `kortty-cli` nachweislich für genau diese Methode annimmt.

### Was die Methoden tun

| Gruppe | Methoden | Zweck |
| --- | --- | --- |
| `api` | `ping`, `auth`, `api.schema` | Handshake und Erkundung. |
| `events` | `events.subscribe`, `events.unsubscribe` | Meldungen, wenn ein Coding-Agent seinen Zustand ändert. |
| `window` / `tab` | `window.list`, `tab.list`, `tab.focus` | Fenster und Tabs auflisten und eines nach vorn holen. |
| `pane` | `pane.list`, `pane.current`, `pane.get`, `pane.resolve`, `pane.focus`, `pane.read`, `pane.send_text`, `pane.run`, `pane.send_keys`, `pane.wait_output`, `pane.split`, `pane.close` | Einen Bereich lesen, darin tippen, auf Ausgabe warten, eine lokale Shell teilen oder einen geteilten Bereich schließen. |
| `agent` | `agent.list`, `agent.get`, `agent.explain`, `agent.prompt`, `agent.send_keys`, `agent.wait`, `agent.rename`, `agent.start` | Mit den von korTTY erkannten Coding-Agents arbeiten – siehe [Coding-Agents](../features/coding-agents.md). |
| `notification` | `notification.show` | Eine Desktop-Benachrichtigung auslösen. |

Der Titel, den `tab.list` für einen Terminal-Tab meldet, ist sein Name ohne das Coding-Agent-Symbol, das Gruppenpräfix und das Suffix `(DISCONNECT)`: der Name, den Sie ihm mit [Tab umbenennen](../features/terminal.md#arbeiten-mit-tabs) gegeben haben, andernfalls der [Titel, den seine Shell gesetzt hat](../features/terminal.md#titel-aus-der-shell), andernfalls der Name der Verbindung. Den Titel der Shell wählt der Server, daher sollte sich ein Client, der wissen muss, mit welchem Host ein Tab verbunden ist, nicht allein auf den Titel verlassen. Umbenennen können nur Sie selbst in der Benutzeroberfläche.

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

### In einen Bereich tippen

`pane.send_text`, `pane.run` und die einzelbuchstabigen Tasten von `pane.send_keys` werden in der [Zeichenkodierung](../features/connections.md#zeichenkodierung) des Bereichs kodiert, sodass sie als die gleichen Bytes ankommen, wie die Tastendrücke erzeugen würden: ein SSH-Paneel, das auf ISO-8859-1 eingestellt ist, erhält `é` als einzelnes Byte `E9`, nicht als UTF-8. Ein Zeichen, das diese Kodierung nicht darstellen kann, wird als `?` gesendet. Die `agent.*`-Methoden senden immer UTF-8, was die Coding Agents lesen.

Wenn `pane.send_text` seinen Text in Bracketed Paste einbettet – `bracketed` ist `always`, oder `auto` hat mehrere Zeilen und ein Pane mit eingeschaltetem Bracketed Paste erkannt –, entfernt korTTY zuerst alle Bracketed-Paste-Marker aus dem Text selbst (`ESC[200~`, `ESC[201~` und ihre 8-Bit-Formen, die mit dem einzelnen `CSI`-Byte `9B` beginnen – in einem Windows-1252-Pane ist das das Zeichen `›`), sodass der Text den Paste nicht vorzeitig beenden und seine restlichen Zeilen als getippte Befehle ausführen kann. Ein Schreiben ohne Bracketed Paste, also auch jedes `pane.run`, behält solche Marker. `agent.prompt` und der Prompt von `agent.start` entfernen sie immer, ob geklammert oder nicht.

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

## MCP-Clients

Ein Client kann korTTY mit dem optionalen Parameter `client_kind` von `auth` mitteilen, welche Art von Client er ist: `cli`, der Standard, für `kortty-cli` und jedes Skript, oder `mcp` für einen MCP-Server, der korTTY an einen KI-Assistenten weiterreicht. korTTY liefert einen solchen Server selbst mit: [`kortty-cli mcp`](cli.md#mcp-clients-bedienen). Jeder andere Wert wird mit `invalid_params` abgewiesen. Eine Verbindung, die sich als `mcp` authentifiziert hat, bleibt eine `mcp`-Verbindung: Ein zweites `auth` darauf als `cli` wird mit `invalid_params` abgewiesen. Ein `mcp`-Client wird nur bedient, solange drei Dinge es erlauben: die Steuerungs-API selbst, der separate Schalter **MCP-Server**, der standardmäßig aus ist, und der Schlüssel `mcp-server` der [Unternehmensrichtlinie](enterprise-policy.md). Verweigert einer davon, schlägt der Handshake mit `mcp_server_disabled` oder `blocked_by_policy` fehl, und die Prüfung wird vor jeder Anfrage wiederholt, sodass das Ausschalten des MCP-Servers eine bereits offene Verbindung bei ihrem nächsten Aufruf stoppt.

Ein `mcp`-Client erhält eine feste Methodenliste nach dem Fail-closed-Prinzip:

| Zugriff | Methoden |
| --- | --- |
| Immer | `ping`, `api.schema`, `window.list`, `tab.list`, `pane.list`, `pane.current`, `pane.get`, `pane.read`, `pane.wait_output`, `agent.list`, `agent.get` |
| Nur mit **Schreib-Tools erlauben**, ebenfalls standardmäßig aus | `pane.send_text`, `pane.run`, `pane.send_keys` |
| Nie | alles andere: Ereignisse, Fokussieren, Teilen und Schließen, Benachrichtigungen, jede `agent.*`-Methode, die etwas bewirkt, die reservierten Verben und jede Methode, die ein späteres korTTY hinzufügt, bis sie für MCP-Clients geprüft ist |

Eine abgewiesene Methode antwortet mit `method_not_allowed_for_mcp`, wobei `data.reason` auf `write_tools_disabled` oder `not_exposed` gesetzt ist. Die Methodenliste in der `auth`-Antwort und das `api.schema`-Dokument zeigen einem `mcp`-Client nur die Methoden, die er aufrufen darf, und die `auth`-Antwort gibt in `mcp_write_tools` an, ob die Schreib-Tools eingeschaltet sind.

### Was ein MCP-Client liest

Alles, was ein `mcp`-Client liest, wird immer maskiert, weil sein Modell in der Regel in der Cloud läuft: korTTY behandelt ihn wie ein Cloud-KI-Profil, ohne Möglichkeit zum Abschalten. Bildschirm und Scrollback von `pane.read`, die passende Zeile und der Bildschirm von `pane.wait_output`, die Belegzeile und die Prozess-Befehlszeile von `agent.list`, `agent.get` und des Agenten in einer Bereichsbeschreibung sowie die Tab- und Fenstertitel verlieren alle das Passwort der Verbindung, die Ersetzungsregeln der Organisation aus der [Unternehmensrichtlinie](enterprise-policy.md) und bekannte Tokenformate wie Cloud-Zugriffsschlüssel, jeweils ersetzt durch `***`. Auch eine Befehlszeile wird maskiert, weil sie einen API-Schlüssel enthalten kann. Ein Geheimnis, das über den rechten Rand einer vollen Zeile in die nächste hineinläuft, wird ebenfalls maskiert; diese Zeilen werden danach wieder auf die Bereichsbreite umgebrochen.

`pane.read` und `pane.wait_output` liefern einem `mcp`-Client höchstens 2000 Zeilen und 64.000 Zeichen, wobei die neuesten erhalten bleiben, und setzen `truncated`, wenn etwas weggelassen wurde; ein `cli`-Client behält die eigenen Grenzen des Protokolls. `pane.read` ergänzt `masked_count`, die Anzahl der im Ergebnis maskierten Geheimnisse. `pane.wait_output` durchsucht den maskierten Text, sodass ein Muster ein erratenes Passwort nicht dadurch bestätigen kann, ob es passt. Ein `cli`-Client liest den Rohtext genau wie bisher.

### Schreibvorgänge eines MCP-Clients

Wenn **Schreib-Tools erlauben** eingeschaltet ist, fragt korTTY Sie trotzdem vor jedem Schreibzugriff, den ein `mcp`-Client vornehmen möchte. `pane.send_text`, `pane.run` und `pane.send_keys` öffnen in korTTY eine Rückfrage, die den Namen des Clients zeigt (als vom Client angegeben gekennzeichnet, weil nichts ihn belegt), den Zielbereich mit seinem Tab-Titel und Host, ob der Schreibzugriff eine Zeile absendet, sowie den genauen Text oder die Tastennamen. Steuerzeichen und unsichtbare Zeichen werden als Symbole dargestellt, etwa `␊` für einen Zeilenumbruch, `␛` für Escape und `<U+202E>` für eine Richtungsumkehr, sodass nichts den Bereich erreicht, was Sie nicht sehen könnten.

| Antwort | Was sie erlaubt |
| --- | --- |
| **Ablehnen** (die Standardschaltfläche) | Nichts; der Aufruf antwortet mit `mcp_write_denied` und `data.reason` `denied` |
| **Einmal erlauben** | Diesen einen Schreibzugriff |
| **Für diesen Bereich in dieser Sitzung erlauben** | Diesen Schreibzugriff und jedes spätere `pane.send_text` in denselben Bereich vom selben MCP-Server-Prozess ohne erneute Rückfrage, solange der Text nichts absendet |

Die dritte Antwort wird nur für ein `pane.send_text` angeboten, dessen Text keinen Zeilenumbruch und kein anderes Steuerzeichen enthält und das `submit` nicht setzt. `pane.run`, `pane.send_keys` und jeder Text, der eine Zeile absendet, fragen immer nach, weil ein einziger Klick einem Assistenten niemals erlauben darf, danach beliebig etwas auszuführen. Die Sitzung ist der `kortty-cli mcp`-Prozess: Er sendet bei jeder Verbindung eine zufällige `mcp_session`-ID, sodass die Antwort gilt, bis der Host des Assistenten diesen Prozess beendet oder korTTY beendet wird. Ein Client, der kein `mcp_session` sendet, behält die Antwort nur für eine Verbindung.

Eine Rückfrage, die nicht innerhalb von 60 Sekunden beantwortet wird, zählt als Ablehnen (`data.reason` `timeout`), ebenso ein korTTY, das keine anzeigen kann (`no_prompt`). Es ist immer nur eine Rückfrage gleichzeitig offen; ein zweiter Schreibzugriff wartet auf den ersten, innerhalb derselben 60 Sekunden. Der Aufruf wartet auf seiner eigenen Verbindung, während Sie entscheiden, niemals auf der Benutzeroberfläche von korTTY.

korTTY verweigert manche Schreibzugriffe ohne Rückfrage, mit `mcp_write_refused` und einem `data.reason`, weil es selbst auch nicht in einen solchen Bereich tippen würde:

| `data.reason` | Der Bereich |
| --- | --- |
| `paste_pacing` | sendet noch ein Einfügen Zeile für Zeile |
| `alternate_screen` | zeigt ein Vollbildprogramm wie `vim` oder `less` |
| `foreign_session` | läuft vermutlich als anderer Benutzer oder auf einem anderen Host, nach `su` oder einem verschachtelten `ssh` |
| `broadcast` | liegt in einem Tab mit eingeschaltetem Broadcast-Modus |
| `multi_exec` | nimmt an Multi-Exec teil |
| `coding_agent` | zeigt einen erkannten Coding-Agent, oder ein Agentenlauf von korTTY steuert ihn |

Diese Prüfungen laufen unmittelbar vor dem Tippen des Textes erneut, sodass ein Bereich, der `vim` öffnet, während Sie die Rückfrage lesen, trotzdem abgelehnt wird. Jede Entscheidung, einschließlich jeder Verweigerung und jedes Schreibzugriffs, den eine Sitzungsantwort erlaubt hat, wird im Log von korTTY als `mcp.consent`-Zeile mit der Methode, der Entscheidung, dem Grund, der Zeichenanzahl und dem Clientnamen festgehalten, aber niemals mit dem Text.

!!! warning "Eine engere Angriffsfläche, keine Sandbox"
    `client_kind` wird vom Client angegeben, nicht nachgewiesen. Die Liste begrenzt, was ein MCP-Server einem KI-Assistenten zugänglich macht; sie schützt korTTY nicht vor einem Assistenten, der auch Shell-Befehle als Sie ausführen kann, weil jedes Ihrer Programme das Token lesen und sich als `cli` verbinden kann. Behandeln Sie jeden MCP-Client wie ein Cloud-Modell und alles, was er aus einem Terminal liest, als nicht vertrauenswürdigen Text.

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
