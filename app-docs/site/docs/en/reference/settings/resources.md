---
title: Resources
---

# Resources

Choose how much memory korTTY may use. The default keeps a low, bounded footprint; the other profiles let the packaged application use more of your machine's resources for very large sessions (huge scrollback, many split panes, long AI chats). Open via **Configuration → Global Settings → Resources**; stored in `~/.kortty/global-settings.xml`.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Resource profile: | dropdown | Balanced, High, Maximum | Balanced | `jvmResourceProfile` |

## Profiles

| Profile | Heap limit | Garbage collector | Relaunch |
| --- | --- | --- | --- |
| **Balanced** (recommended) | Fixed 2 GB | G1, with idle memory return | No |
| **High** | ~50% of physical RAM | G1 | Yes, once at startup |
| **Maximum** | ~75% of physical RAM | Z Garbage Collector (low pause) | Yes, once at startup |

The Resources tab shows your machine's detected memory and the approximate heap limit each profile would apply on it.

## Notes

!!! note "Applies to the packaged application only"
    This setting is applied by the packaged app (the `.dmg`/`.msi`/AppImage build), which briefly relaunches itself once at startup to switch the heap size and garbage collector — the Java runtime cannot change these while running, and editing the signed application bundle would break its signature. When korTTY is started from the plain `.jar`, set JVM options yourself (for example `-Xmx8g`) instead.

!!! note "Takes effect after a restart"
    Changing the profile takes effect the next time korTTY starts. The Balanced default never relaunches; High and Maximum relaunch once per start, so their cold start is slightly slower.

!!! warning "Leave headroom for the rest of your system"
    Higher profiles let korTTY reserve much more memory. Terminal and editor rendering (the embedded browser engines) also use memory *outside* the Java heap, so the Maximum profile deliberately caps the heap at about three quarters of RAM rather than removing the limit entirely — a truly unbounded heap could starve the operating system.
