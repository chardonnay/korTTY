---
title: Ressourcen
---

# Ressourcen

Wählen Sie, wie viel Speicher korTTY verwenden darf. Der Standard wert hat eine geringe, begrenzte Auswirkung; die anderen Profile ermöglichen es dem eingebetteten Programm, mehr Ressourcen des Rechners zu nutzen, um sehr große Sitzungen (große Scrollback, viele geteilte Fenster, lange KI-Chats) zu unterstützen. Öffnen Sie die Einstellungen über **Konfiguration → Globale Einstellungen → Ressourcen**; gespeichert unter `~/.kortty/global-settings.xml`.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Ressourcenprofil: | Dropdown | Ausbalanciert, Hoch, Maximal | Ausbalanciert | `jvmResourceProfile` |

## Profile

| Profil | Heap-Limit | Garbage Collector | Relaunch |
| --- | --- | --- | --- |
| **Ausbalanciert** (empfohlen) | Feste 2 GB | G1, mit Rückgabe ungenutzten Speichers | Nein |
| **Hoch** | ~50 % des physischen RAM | G1 | Ja, einmal beim Start |
| **Maximal** | ~75 % des physischen RAM | Z-Garbage-Collector (pausenarm) | Ja, einmal beim Start |

Der Ressourcen-Reiter zeigt den erkannten Speicher Ihres Rechners und das ungefähre Heap-Limit an, das jedes Profil darauf anwenden würde.

## Hinweise

!!! note "Gilt nur für die Paketanwendung"
    Diese Einstellung wird von der paketierten App (dem `.dmg`-/`.msi`-/AppImage-Build) ausgeführt, das sich bei Start kurz neu startet, um die Heap-Größe und den Garbage Collector zu wechseln — die Java-Laufzeit kann diese während der Ausführung nicht ändern, und die Bearbeitung des signierten Anwendungspakets würde dessen Signatur beschädigen. Wenn korTTY direkt aus dem reinen `.jar` gestartet wird, setzen Sie die JVM-Optionen selbst (z. B. `-Xmx8g`) ein.

!!! note "Wird nach einem Neustart wirksam"
    Eine Profiländerung wird beim nächsten Start von korTTY wirksam. Die Voreinstellung Ausbalanciert startet nie neu; Hoch und Maximal starten einmal pro Start neu, ihr Kaltstart ist dadurch geringfügig langsamer.

!!! warning "Lassen Sie Spielraum für den Rest Ihres Systems."
    Höhere Profile lassen korTTY viel mehr Speicher reservieren. Die Darstellung des Terminals und des Editors (die eingebetteten Browser-Engines) verwendet ebenfalls Speicher *außerhalb* des Java-Heaps, weshalb der Maximum-Modus den Heap absichtlich auf etwa drei Viertel des RAMs begrenzt — ein vollständig unbeschränkter Heap könnte das Betriebssystem überlasten.
