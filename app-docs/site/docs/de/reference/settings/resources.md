---
title: Ressourcen
---

# Ressourcen

Choose how much memory korTTY may use. The default keeps a low, bounded footprint; the other profiles let the packaged application use more of your machine's resources for very large sessions (huge scrollback, many split panes, long AI chats). Open via **Configuration → Global Settings → Resources**; stored in `~/.kortty/global-settings.xml`.

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
    This setting is applied by the packaged app (the `.dmg`/`.msi`/AppImage build), which briefly relaunches itself once at startup to switch the heap size and garbage collector — the Java runtime cannot change these while running, and editing the signed application bundle would break its signature. When korTTY is started from the plain `.jar`, set JVM options yourself (for example `-Xmx8g`) instead.

!!! note "Wird nach einem Neustart wirksam"
    Eine Profiländerung wird beim nächsten Start von korTTY wirksam. Die Voreinstellung Ausbalanciert startet nie neu; Hoch und Maximal starten einmal pro Start neu, ihr Kaltstart ist dadurch geringfügig langsamer.

!!! warning "Lassen Sie Spielraum für den Rest Ihres Systems."
    Higher profiles let korTTY reserve much more memory. Terminal and editor rendering (the embedded browser engines) also use memory *outside* the Java heap, so the Maximum profile deliberately caps the heap at about three quarters of RAM rather than removing the limit entirely — a truly unbounded heap could starve the operating system.
