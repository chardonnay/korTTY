---
title: Language
---

# Language

Configure the user interface language for korTTY. Supports 8 built-in languages plus automatic detection based on your system settings. Open via **Configuration → Global Settings → Language**; stored in `~/.kortty/global-settings.xml`.

![Language settings tab](../../assets/screenshots/settings/language.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Select Language: | dropdown | Auto-detect (System Language), English, German, Italian, Spanish, Portuguese, French, Croatian, Dutch | Auto-detect (System Language) | `language` |

!!! note
    Language changes take effect after the application is restarted. The setting will apply to the user interface labels, menus, and dialogs the next time korTTY launches.

!!! note "Date and number formats"
    The installed application bundles Java locale data only for the 8 supported interface languages. If your operating system runs in a locale outside this list, dates and numbers are formatted using English conventions.