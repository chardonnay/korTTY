---
title: Ressourcen
---

# Ressourcen

Legen Sie fest, wie viel Speicher korTTY nutzen darf. Die Voreinstellung hält den Verbrauch niedrig und begrenzt; die anderen Profile erlauben der paketierten Anwendung, mehr Ressourcen Ihres Rechners für sehr große Sitzungen zu nutzen (riesiger Scrollback, viele geteilte Bereiche, lange KI-Chats). Öffnen über **Konfiguration → Globale Einstellungen → Ressourcen**; gespeichert in `~/.kortty/global-settings.xml`.

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

!!! Hinweis „Gilt nur für die paketierte Anwendung“
    Diese Einstellung wird von der paketierten App (dem `.dmg`/`.msi`/AppImage-Build) angewendet, die sich beim Start einmal kurz selbst neu startet, um Heap-Größe und Garbage Collector umzustellen – die Java-Laufzeit kann diese im laufenden Betrieb nicht ändern, und ein Bearbeiten des signierten Anwendungspakets würde dessen Signatur beschädigen. Wird korTTY aus der einfachen `.jar` gestartet, setzen Sie die JVM-Optionen stattdessen selbst (zum Beispiel `-Xmx8g`).

!!! Hinweis „Wird nach einem Neustart wirksam“
    Eine Profiländerung wird beim nächsten Start von korTTY wirksam. Die Voreinstellung Ausbalanciert startet nie neu; Hoch und Maximal starten einmal pro Start neu, ihr Kaltstart ist dadurch geringfügig langsamer.

!!! warning „Reserve für den Rest des Systems lassen“
    Höhere Profile lassen korTTY deutlich mehr Speicher reservieren. Terminal- und Editor-Darstellung (die eingebetteten Browser-Engines) belegen auch Speicher *außerhalb* des Java-Heaps – deshalb begrenzt das Profil Maximal den Heap bewusst auf etwa drei Viertel des RAM, statt das Limit ganz aufzuheben: ein wirklich unbegrenzter Heap könnte das Betriebssystem aushungern.
