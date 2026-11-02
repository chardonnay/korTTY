---
title: KI-Skills
---

# KI-Skills

Konfigurieren Sie benutzerdefinierte KI-Skills, die KI-Interaktionen verbessern. Dieser Tab ermöglicht es Ihnen, eine Bibliothek von markdown-basierten Skills zu verwalten, die automatisch oder manuell in KI-Anfragen einbezogen werden. Öffnen Sie über **KI → KI-Manager → KI-Skills**; gespeichert in `~/.kortty/global-settings.xml`.

!!! note "Aus den globalen Einstellungen entfernt"
    Die Skill-Bibliothek war früher eine Registerkarte unter **Konfiguration → Globale Einstellungen**. Es befindet sich jetzt im **KI-Manager**, neben Profilen, lokalen Modellen und Wissensspeichern. Die gespeicherten Daten und die Einstellungsdatei bleiben unverändert.

![AI Skills settings tab](../../assets/screenshots/settings/ai-skills.png)

## Globale Einstellungen

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| KI-Skills aktivieren | umschalten | – | Ein | `aiSkillsEnabled` |
| Automatisch nur passende Fähigkeiten senden | umschalten | – | Ein | `aiSkillAutoDetectionEnabled` |
| korTTY darf Skills anlegen und gelernte Regeln in Skills speichern | Schalter | — | Ein | `aiSkillAutoCreate` |
| Lokal / Extern | Umschalter | Lokal: eigene und integrierte Skills; Extern: vom Internet importierte Skills | Lokal | — (Ansichtsfilter) |
| Versteckte integrierte Fähigkeiten anzeigen | umschalten | – | Aus | – (Filter anzeigen) |
| Suchfähigkeiten | Text | Filtert die Liste nach Name, Beschreibung oder Tags | – | – (Filter anzeigen) |
| Sortieren | Menüschaltfläche | Alphabetisch, Status (zuerst aktiviert) | – | – |
| Statistik | Schaltfläche | Öffnet die Nutzungs- und Größenstatistik der Skills, siehe [Skill-Statistik](#skill-statistik) | — | — |
| Schaltfläche „Speichern“ | | Schreibt die Bibliothek in die globale Einstellungsdatei | – | – |

Eine Zählzeile unterhalb der Liste fasst die gesamte Bibliothek zusammen – **Gesamt**, **Aktiv** (aktiviert und nicht ausgeblendet) und **Inaktiv/ausgeblendet** – unabhängig von der aktuellen Suche oder dem ausgeblendeten Filter.

## Felder des Skill-Editors

Wenn Sie eine Fertigkeit auswählen oder erstellen, werden im rechten Bereich Felder pro Fertigkeit angezeigt. Jede Fertigkeit wird einzeln in der Fertigkeitsliste gespeichert.

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Skill-Name | Text | — | "KI-Skill" | `name` (auf AiSkill-Objekt) |
| Beschreibung | Text | – | – | `description` |
| Tags | Text | Durch Kommas getrennte Tags (z. B. Linux, Bash) | – | `tags` |
| Ziel | Dropdown | KI-Chat/-Funktionen, KI-Agent, Beide, Verbindung | Beide | `target` |
| Aktiv | umschalten | — | Ein | `enabled` |
| Skill-Markdown | Text | Markdown-formatierter Skill-Inhalt | – | `content` |

Die Skill-Bibliothek wird in `global-settings.xml` gespeichert, die bestimmte unsichtbare Zeichen nicht aufnehmen kann: Steuerzeichen außer Tab, Zeilenvorschub und Wagenrücklauf, U+FFFE, U+FFFF und die Hälfte eines Surrogatpaares. Solch ein Zeichen kann auftreten, wenn Sie in den **Skill-Markdown**-Editor einfügen. Ein Feld, das eines enthält, zeigt darunter eine Meldung an, die das Zeichen benennt, zum Beispiel `U+0007`, und **Speichern** verweigert die Speicherung der Bibliothek und wählt das Skill aus, bis Sie das Zeichen entfernen. Wenn Sie den KI-Manager mit einem solchen Skill schließen, speichert korTTY Ihre anderen Änderungen und behält die zuletzt gespeicherte Version dieses Skills bei oder lässt einen Skill weg, der nie gespeichert wurde. **Importieren** entfernt solche Zeichen aus dem Namen, der Beschreibung, den Tags und dem Markdown der importierten Datei.

## Eingebaute Fähigkeiten

korTTY bietet 39 integrierte Best-Practice-Kenntnisse für Shells (Bash, KornShell, Zsh, Csh, POSIX sh, PowerShell), Programmiersprachen (Python, C, C++, Java, C#, JavaScript, Visual Basic, SQL, R, Rust, Go, PHP, Swift, Assembly, Macro Assembler, Ruby, Perl, Lua, Groovy, TypeScript, Kotlin, Dart), Markup und Datenformate (HTML, XML, YAML, JSON) und Automatisierungs-/Beobachtbarkeitstools (Puppet, Ansible, Azure DevOps Pipelines, Jenkins Declarative and Scripted Pipelines, Filebeat, Logstash). Jeder Skill bietet professionelle Anleitungen zu Codekommentaren, Robustheit, häufig zu vermeidenden Fallstricken und sprachspezifischen Sicherheitspraktiken. Sie werden beim ersten Start zur Bibliothek hinzugefügt und erscheinen mit einem **integrierten** Abzeichen.

Integrierte Fertigkeiten verhalten sich wie Ihre eigenen Fertigkeiten – sie können bearbeitet, deaktiviert und Verbindungen zugewiesen werden – mit folgenden Unterschieden:

- **Sie können nicht gelöscht, sondern nur ausgeblendet werden.** *Ausblenden* entfernt eine integrierte Funktion aus der Liste und aus allen Fertigkeitsauswahlen, und eine ausgeblendete integrierte Funktion wird nicht mehr mit KI-Anfragen gesendet – selbst bei Verbindungen, denen sie zuvor zugewiesen wurde. *Versteckte integrierte Fertigkeiten anzeigen* zeigt versteckte Einträge an, die mit einem **Versteckt**-Abzeichen gekennzeichnet sind, sodass sie mit *Einblenden* wieder angezeigt werden können.
- **Unveränderte integrierte Funktionen werden automatisch aktualisiert.** Wenn eine neue korTTY-Version verbesserte Skill-Inhalte bereitstellt, werden unveränderte integrierte Funktionen beim Start automatisch ersetzt (Ihre Auswahl zwischen Aktiv/Ausgeblendet bleibt erhalten).
- **Geänderte integrierte Funktionen werden nie berührt.** Sobald Sie eine integrierte Funktion bearbeiten, wird für sie das Abzeichen „Integriert (geändert)“ angezeigt und die automatische Aktualisierung wird beendet. *Auf die ausgelieferte Version zurücksetzen* verwirft Ihre Änderungen und stellt die ausgelieferte Version wieder her, auf der Ihre Änderungen basierten. Wenn eine neuere ausgelieferte Version vorhanden ist, wird im Eintrag 🔄 **Update verfügbar** angezeigt und *Update auf neueste ausgelieferte Version* übernimmt diese.
- **Ihre eigenen Fertigkeiten haben immer Vorrang.** Wenn einer Ihrer aktivierten Fertigkeiten ein Tag trägt, das mit dem Thema einer integrierten Fertigkeit übereinstimmt (z. B. eine persönliche Fertigkeit mit der Bezeichnung `perl`), wird die integrierte Fertigkeit unterdrückt: Sie zeigt **Von Benutzerfertigkeit überschrieben** an, ist ausgegraut und wird nicht mehr mit einer KI-Anfrage gesendet. Durch das Löschen oder Deaktivieren Ihres Skills wird die integrierte Funktion sofort wieder aktiviert.
- Deaktivierte, ausgeblendete und überschriebene Einträge werden ausgegraut dargestellt; Dies funktioniert in jedem Anwendungsdesign-Thema.

!!! note "Durch das Ausschalten der automatischen Erkennung werden integrierte Funktionen deaktiviert"
    Ohne automatische Erkennung wird jeder aktivierte Skill mit jeder KI-Anfrage gesendet, was bei 35 integrierten Skills die Eingabeaufforderungen massiv übersteigen würde. Wenn Sie **Automatisch nur passende Fertigkeiten senden** ausschalten, werden Sie daher nach einer Bestätigung gefragt und anschließend alle integrierten Fertigkeiten deaktiviert. Aktivieren Sie diejenigen, die Sie benötigen, einzeln wieder. In späteren Versionen bereitgestellte integrierte Funktionen sind ebenfalls deaktiviert, wenn die automatische Erkennung deaktiviert ist.

## Externe Skills

Die **Externe**-Ansicht listet Skills auf, die aus Internet-Skill-Verzeichnissen importiert wurden. Externe Skills befinden sich in derselben Bibliothek wie Ihre eigenen Skills: Sobald Sie einen aktivieren, wird er ausgewählt und genau wie ein lokaler Skill gesendet. Die Anzahl auf jedem Umschaltknopf zeigt, wie viele Skills jede Ansicht enthält.

![AI Skills, External view](../../assets/screenshots/ai/ai-skills-external.png)

| Schaltfläche | Was es tut |
| --- | --- |
| Aus dem Internet importieren… | Öffnet den Importdialog: wählen Sie einen Anbieter, suchen, zeigen Sie eine SKILL.md-Vorschau an und importieren die ausgewählten Skills |
| Auf Updates prüfen | Fragt jeden externen Skill-Anbieter nach seiner aktuellen Version; Skills mit einer neueren Version zeigen 🔄 **Update verfügbar** |
| Anbieter… | Öffnet die Anbietertabelle mit Anmeldedaten (siehe unten) |
| Löschen / Exportieren | Gleich wie bei lokalen KI-Skills |

!!! warning "Importierte Skills starten deaktiviert"
    korTTY übermittelt den Text einer Skill wortwörtlich an das KI-Modell, sodass ein aus dem Internet importierter Skill Anweisungen enthalten kann, die Sie nicht wünschen. Jeder importierte Skill kommt daher **deaktiviert**, unabhängig davon, was in seiner Front-Matter steht. Öffnen Sie ihn im Editor, aktivieren Sie dann **Aktiv** und klicken Sie auf **Speichern**. Nur die Datei `SKILL.md` wird importiert; Skripte, Referenzdateien und andere Dateien, die zu einem Skill gehören, werden nie heruntergeladen oder ausgeführt.

Wenn ein externer KI-Skill ausgewählt wird, zeigt das Banner über dem Editor dessen Anbieter, dessen Quelle und die importierte Revision an. **Quelle öffnen** öffnet die Seite des KI-Skills im Browser. Wenn Sie den Text oder die Beschreibung bearbeiten, markieren das Banner und die Liste den KI-Skill als **Lokal geändert**; **Kürzen…** ändert niemals den Text eines importierten KI-Skills, sondern nur dessen erlernte Regeln. Nach dem **Auf Updates prüfen** eine neuere Version gefunden hat, öffnet **Update anzeigen…** einen nebeneinander gestellten Vergleich Ihrer Version und der Version des Anbieters. **Update übernehmen** ersetzt den Text und die Beschreibung und behält Ihren Namen, Ihre Tags, Ihr Ziel, Ihren aktiven Zustand und Ihre erlernten Regeln bei; ein KI-Skill, der importiert wurde, bevor Beschreibungen erfasst wurden, behält seine Beschreibung. Ein KI-Skill, der **Lokal geändert** ist, wird niemals überschrieben, und der Dialog weist darauf hin: Er bietet **Als Kopie importieren** an, was die Anbieter-Version als neuen, deaktivierten KI-Skill mit dem Namen der Revision hinzufügt, sowie **Diese Version überspringen**, was diese Revision ausblendet, während eine neuere weiterhin angeboten wird. Ohne einen Klick wird nichts aktualisiert, und jede Änderung wird wirksam, wenn Sie auf **Speichern** klicken.

### KI-Skills importieren

![Import AI skills from the internet](../../assets/screenshots/ai/ai-skills-import.png)

Anbieter wählen, eine Suche eingeben und **Suchen** anklicken. Beim Auswählen eines Ergebnisses wird dessen `SKILL.md` in die Vorschau geladen. Die Ergebnisliste zeigt den Namen, die Quelle, GitHub-Sterne und die Beschreibung jeder Fähigkeit; ein ✓ vor dem Namen kennzeichnet eine Fähigkeit, die bereits in der Bibliothek vorhanden ist. Wählen Sie ein oder mehrere Ergebnisse aus und klicken Sie auf **Auswahl importieren**; Ergebnisse mit ✓ werden übersprungen. Was Sie eingeben, hängt vom Anbieter-Typ ab:

| Anbietertyp | Eingabe | Beispiel |
| --- | --- | --- |
| GitHub-Repository | Suchbegriffe zum Durchsuchen `SKILL.md` Dateien auf GitHub (benötigt ein Token), `owner/repo` für jedes Skill eines Repositories, `owner/repo@skill` oder ein GitHub-Link für ein einzelnes Skill; aus agenticskills.io oder skills.sh kopierte Befehle funktionieren ebenfalls | `python testing`, `anthropics/skills`, `npx skills add anthropics/skills@pdf` |
| SkillsMP-Suche | Keywords | `kubernetes` |
| HTTP(S)-Server | Die URL einer `SKILL.md`-Datei oder ein Pfad relativ zum Basis-URL des Anbieters | `incident/SKILL.md` |

Kataloge wie agenticskills.io und skills.sh veröffentlichen ihre Skills als GitHub-Repositories, daher importiert der **GitHub**-Provider von allen davon. korTTY liest Repositories über die GitHub REST API unter `https://api.github.com` (API-Version 2022-11-28) und merkt sich die Git-Revision jedes importierten `SKILL.md`, sodass ein Commit, der andere Dateien des Repositories ändert, nicht als Update gemeldet wird. Ohne Token erlaubt GitHub 60 Anfragen pro Stunde; ein Import benötigt ein oder zwei. GitHub beantwortet eine Suchbegriffssuche nur mit Token: ohne Token erklärt der Dialog dies und bietet **Stattdessen auf SkillsMP suchen** an, der dieselben GitHub-Skills ohne Anmeldung durchsucht. Suchbegriffe müssen nicht exakt übereinstimmen; jedes Wort muss irgendwo im `SKILL.md` vorkommen.

Der **SkillsMP**-Provider durchsucht den SkillsMP-Index über `https://skillsmp.com/api/v1/skills/search`. Ohne einen API-Schlüssel erlaubt SkillsMP 50 Suchen pro Tag, mit einem Schlüssel 500. Die gefundenen Skills werden von GitHub über den aktiven GitHub-Provider für github.com heruntergeladen, unter Verwendung des Tokens dieses Providers.

### Anbieter

![AI skill providers](../../assets/screenshots/ai/ai-skill-providers.png)

**Anbieter…** öffnet eine Tabelle mit Skill-Anbietern, die Sie nach Belieben erweitern können. korTTY startet mit einem **GitHub**- und einem **SkillsMP**-Anbieter; fügen Sie weitere mit **+ Hinzufügen** hinzu, z. B. einen GitHub-Enterprise-Server oder den eigenen Server eines Teams. Das Löschen eines Anbieters behält die daraus importierten Skills bei, diese können jedoch nicht mehr auf Updates geprüft werden.

| Feld | Werte | Gespeichert als |
| --- | --- | --- |
| Name | Jeder Name | `aiSkillProviders/provider/name` |
| Typ | GitHub-Repository, SkillsMP-Suche, HTTP(S)-Server | `type` |
| Aktiv | Nur aktive Anbieter erscheinen im Importdialog | `enabled` |
| Basis-URL | Leer verwendet den Standard des Typs (`https://api.github.com`, `https://skillsmp.com`); für GitHub Enterprise verwenden Sie `https://<host>/api/v3` | `baseUrl` |
| Anmeldung | Keine, Token (Bearer), Benutzername und Passwort (HTTP Basic) | `auth` |
| Benutzername | Nur für Benutzername und Passwort | `username` |
| Token / Passwort | ein GitHub-Personal-Access-Token, ein SkillsMP-API-Schlüssel oder das Serverpasswort | `encryptedSecret`, verschlüsselt mit dem Master-Passwort |

**Verbindung testen** prüft, ob der Anbieter antwortet und die Anmeldedaten akzeptiert, und zeigt das verbleibende Anfragekontingent an, sofern der Anbieter eines meldet. Ein Token oder Passwort wird mit dem Master-Passwort verschlüsselt, wenn Sie auf **Speichern** klicken; falls der Tresor gesperrt ist, bietet korTTY an, ihn zu entsperren. Lassen Sie das Feld leer, um einen gespeicherten Token beizubehalten, oder aktivieren Sie **Gespeicherten Token bzw. Passwort entfernen**, um ihn zu löschen.

!!! note "Netzwerksicherheit"
    korTTY kommuniziert ausschließlich über HTTPS mit Anbietern; reines HTTP wird nur für diesen Computer akzeptiert (`localhost`). Weiterleitungen werden nicht verfolgt, sodass ein Token oder Passwort niemals an einen anderen Host weitergeleitet wird, und ein `SKILL.md` größer als 256 KiB wird abgelehnt.

## Von korTTY erstellte KI-Skills und erlernte Regeln

korTTY kann Regeln, die Sie bestätigen, in Ihren KI-Skills speichern und einen KI-Skill für ein Thema erstellen, das noch keins hat. Ohne Ihre Bestätigung wird nichts gespeichert: Die Regelkarte zeigt den genauen Text und das Ziel, und **Speichern** schreibt ihn. Der Schalter **korTTY darf Skills anlegen und gelernte Regeln in Skills speichern** steuert beides, und die Unternehmensrichtlinie kann ihn sperren.

Eine Regel, die eine Verbindung benennt, wird in das Gedächtnis dieser Verbindung eingetragen; eine Regel zu einem Thema, das bereits einen KI-Skill hat – eingebaut oder eigenen –, wird in die erlernten Regeln dieses KI-Skills eingetragen; eine Regel zu einer Sprache oder einem Tool ohne KI-Skill erstellt einen; jede andere Regel wird in das globale Gedächtnis eingetragen. Das Gedächtnis selbst wird in [KI-Memory](../../features/ai-memory.md) beschrieben.

* Ein von korTTY erstellter Skill zeigt das Badge **NEU · von korTTY erstellt**, bis Sie ihn in der Liste auswählen; danach wird **korTTY** angezeigt. Der Skill, den die Liste beim Öffnen automatisch auswählt, zählt nicht.
* Ein **+N Regel(n)**-Chip zählt die erlernten Regeln, die Sie noch nicht gesehen haben; das Öffnen des KI-Skills markiert sie als gesehen und entfernt den Chip.
* Der Editor listet die gelernten Regeln eines Skills unter **Gelernte Regeln** auf, die nur angezeigt werden, wenn der Skill welche besitzt. **Bearbeiten** ändert den Text einer Regel in einem Dialog, **Löschen** entfernt sie nach einer Bestätigung, und beide werden mit dem Skill gespeichert, wenn Sie auf **Speichern** klicken.
* Gelernte Regeln werden zusammen mit ihrem Skill gesendet, jedes Mal, wenn der Skill für eine Anfrage ausgewählt wird, sofern das Gedächtnis aktiviert ist und die Richtlinie dies zulässt.

Regeln werden aus Ihren Nachrichten nur im Lernmodus **Regeln vorschlagen** vorgeschlagen; **Als Regel merken…** speichert eine Regel, die Sie selbst verfassen.

## Geheimnisse in KI-Skills

korTTY speichert kein mögliches Passwort, keinen Schlüssel und kein Token in einem Skill, einer erlernten Regel, dem Gedächtnis oder einer genehmigten Gedächtnisdatei. Die Prüfung sucht nach Schlüsselwortphrasen auf Deutsch und Englisch, `key=value`-Zuweisungen (auch in `.env`-Dateien und Befehlszeilen-Flags), lange, zufällig aussehende Zeichenketten, Token-Formate, die es kennt, und Werte, die im Passwort-Tresor gespeichert sind, während der Tresor entsperrt ist.

Wenn Sie einen Skill so ändern, dass er ein mögliches Secret enthält, verweigert **Speichern** das Speichern der Bibliothek und wählt diesen Skill aus. Ein markierter Skill, den Sie nicht ändern, blockiert **Speichern** nicht; sein Badge bleibt, bis das Geheimnis entfernt wird. Die Meldung benennt die Zeile, das Feld und die Art des Geheimnisses, niemals den Wert, zum Beispiel *Zeile 4 im Skill-Text sieht so aus, als würde sie ein Geheimnis enthalten (die Zuweisung eines Passworts oder Tokens). Entfernen Sie es und versuchen Sie es erneut.* Eine Markdown-Datei oder ein Skill aus dem Internet, der einen Befund enthält, wird beim Import auf dieselbe Weise abgelehnt.

Beim Start prüft korTTY die vorhandenen Skills und markiert in der Liste einen Skill, der ein mögliches Secret enthält, mit einem **⚠ mögliches Secret**-Badge. Er verändert oder löscht diesen Skill nicht. Ein markierter Skill wird weiterhin mit Anfragen gesendet, bis die Secret-Zeile entfernt wird; nur das ausgehende Masking greift.

Die Prüfung ist keine Garantie. Ein Passwort, das Sie selbst gewählt haben, ohne Schlüsselwort und nicht im Vault gespeichert, kann nicht zuverlässig erkannt werden, daher schreiben Sie keines in ein KI-Skill.

## Skill-Statistik

**Statistik** in der Kopfzeile der Liste öffnet die Statistik Ihrer Skills: wie oft jedes mit einer Anfrage gesendet wurde, wie groß es ist und wie sich das ändert. Die Werte stammen aus der lokalen Datei `stats/skill-usage.json`, siehe [Konfigurationsdateien](../config-files.md#stats).

![Skill statistics dialog](../../assets/screenshots/ai/ai-skill-statistics.png)

| Steuerung | Wirkung |
| --- | --- |
| **Tag**, **Monat**, **Jahr** | Legt die Granularität des Diagramms und der Größenhistorie fest; die Bereichsliste folgt dieser |
| Bereich | Tag: letzte 30, 90 oder 400 Tage. Monat: letzte 12, 24 oder 60 Monate. Jahr: alle Jahre |
| **Statistik aufzeichnen** | Schaltet die Aufzeichnung ein oder aus; während sie deaktiviert ist, wird nichts gezählt oder gespeichert. Die Unternehmensrichtlinie kann sie sperren |
| **Gepinnte einbeziehen** | Zählt auch die Verwendungen von KI-Skills, die einer Verbindung zugewiesen oder explizit ausgewählt wurden. Deaktiviert werden nur automatische Verwendungen angezeigt |
| **Zurücksetzen...** | Löscht nach einer Bestätigung jeden aufgezeichneten Aufruf und jeden Größenverlauf; die Skills bleiben unverändert |

Die vier Werte oben sind **Automatische Aufrufe**, **Ø Größe aktiver Skills** in Tokens, **Gesendete Skill-Tokens** und **Wachstum pro Monat** in Tokens pro Monat. Das gestapelte Balkendiagramm zeigt die Aufrufe pro Skill im Zeitraum: die acht am häufigsten genutzten Skills und **Andere**. Die Tabelle listet jeden Skill auf, beginnend mit dem am häufigsten genutzten, mit seinem Badge unter dem Namen, automatischen und gepinnten Aufrufen, durchschnittlicher und aktueller Größe, Wachstum pro Monat und in Prozent, letztem Aufruf und erlernten Regeln. Wählen Sie eine Zeile aus, um deren Größenverlauf unter der Tabelle zu sehen.

**Hinweise** nennen die Skills zur Überprüfung: einen, der seit 90 Tagen nicht verwendet wurde, einen, der sich innerhalb von 30 Tagen mehr als verdoppelt hat, und einen mit mehr als 1.000 Tokens. Die letzten beiden bieten **Kürzen...** an.

**Kürzen...** fragt das Standard-KI-Profil um eine kürzere Version des Skills und seiner erlernten Regeln, zeigt die Änderung als Diff mit den Token-Größen vor und nach und speichert nichts, bis Sie auf **Übernehmen** klicken. Der Text eines selbst geschriebenen Skills kann gekürzt werden. Bei einem eingebauten oder importierten Skill werden nur die erlernten Regeln gekürzt, sodass der Text weiterhin Updates erhält. Setzen Sie zuerst ein Standard-KI-Profil; der Dialog weist darauf hin, wenn keines gesetzt ist.

Tageswerte werden 400 Tage, Monatswerte fünf Jahre und Jahreswerte unbegrenzt aufbewahrt. Der Unternehmensrichtlinien-Schlüssel `statistics` in `[rule.ai-memory]` kann die Aufzeichnung für alle deaktivieren; siehe [Unternehmensrichtlinie](../enterprise-policy.md#ruleai-memory).

## Notizen

!!! note "Automatisches Erkennungsverhalten"
    Wenn **Nur übereinstimmende Fertigkeiten automatisch senden** aktiviert ist, bewertet korTTY jede Fertigkeit anhand der aktuellen Anfrage in vier Feldern – Tags, Name, Beschreibung und die Überschriften im Markdown der Fertigkeit – und schließt höchstens die **zwei** Fertigkeiten mit der höchsten Bewertung ein, die den Relevanzschwellenwert erreichen (Gleichstände nach Katalogreihenfolge aufgelöst). Explizit angeheftete und verbindungszugewiesene Fertigkeiten werden zusätzlich hinzugefügt und verbrauchen dieses Limit nicht. Wenn die lokalen Bewertungen nicht schlüssig sind (keine oder eine passende Fähigkeit oder genau zwei übereinstimmende mit nahezu gleichen Bewertungen), bittet korTTY das Modell zusätzlich, die Anfrage zu klassifizieren und bevorzugt diese Antwort – ebenfalls begrenzt auf zwei Fähigkeiten – und greift auf das lokale Ergebnis zurück, wenn der Anruf fehlschlägt. Bei Deaktivierung werden alle anwendbaren Fertigkeiten ohne Bewertung gesendet – eine Fertigkeit wird weiterhin übersprungen, wenn sie inaktiv ist, leeren Inhalt hat oder nicht mit dem aktuellen Ziel übereinstimmt.

!!! note "Fertigkeitsziele"
    - **KI-Chat/Funktionen**: Fähigkeiten, die in KI-Chat- und Funktionsaufrufkontexten verfügbar sind
    - **KI-Agent**: Vom Terminal-KI-Agenten verwendete Fähigkeiten
    - **Beide**: Verfügbar sowohl für AI Chat- als auch für AI Agent-Kontexte
    - **Verbindung**: Spezielle Fähigkeiten für die Handhabung von SSH-Verbindungen

!!! note "Skill-Lebenszyklus"
    Skills werden als XML-Elemente innerhalb der globalen Einstellungen gespeichert. Verwenden Sie **Importieren**, um Skills aus Markdown-Dateien zu laden, und **Exportieren**, um ausgewählte Skills als Markdown-Dateien zu speichern; um Skills aus Internetverzeichnissen zu importieren, nutzen Sie die **Externe Ansicht** (siehe oben). **Löschen** gilt nur für Ihre eigenen Skills; eingebaute Skills werden stattdessen ausgeblendet (siehe oben). Zurücksetzen, Aktualisieren, Ausblenden und Einblenden sind im Kontextmenü der Liste sowie im Banner über dem Editor verfügbar, wenn ein eingebauter Skill ausgewählt ist. Die Skill-Liste kann alphabetisch nach Namen oder Status (erst aktiviert) sortiert werden. **Speichern** speichert die Bibliothek sofort und bestätigt neben dem Button; ausstehende Änderungen werden ebenfalls geschrieben, wenn das KI-Manager-Fenster geschlossen wird. Das Importieren einer Markdown-Datei erzeugt immer einen unabhängigen Benutzerskill – auch wenn die Datei aus einem eingebauten Skill exportiert wurde.

!!! note "Auswahl der Fähigkeiten pro Anfrage"
    Dieser Tab verwaltet die globale Bibliothek. Welche der hier aktivierten KI-Skills einer bestimmten Aktion entsprechen, wird anderswo gewählt: Der KI-Skills-Auswahlfeld im Snippet-Editor bindet Ihre Auswahl an jede Aktion im Snippet-Editor, während das Fenster „Vollständige Code-Analyse“ die enthaltenen Skills als Chips anzeigt – mit den Bezeichnungen *(automatisch)* oder *(manuell)* – und ein suchbares Auswahlfeld, dessen Änderungen beim nächsten Neustart wirksam werden. Innerhalb der Snippet-Aktionen wird das Auswahlfeld die automatische Erkennung ersetzen: Nur die markierten KI-Skills sowie die Skills, die der Verbindung zugewiesen sind, werden übermittelt, und das Entfernen des Auswahlfelds sendet keine Bibliotheks-Skills. Die Snippet-Aktionen „Richtig machen“ und „Übersetzen“ sowie die feste Anfrage „Diagramm“ enthalten nie Bibliotheks-Skills, unabhängig von Ziel oder Pinning. Siehe [Snippets → KI-Skills](../../features/snippets.md#ki-skills).
