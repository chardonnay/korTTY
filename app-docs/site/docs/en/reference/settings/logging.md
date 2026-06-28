---
title: Logging
---

# Logging

Configure terminal session logging, including where logs are stored and how long they are retained. Open via **Configuration → Global Settings → Logging**; stored in `~/.kortty/global-settings.xml`.

![Logging settings tab](../../assets/screenshots/settings/logging.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Log directory | path | — | `~/.kortty/logs` | `logDirectoryPath` |
| Keep logs | number | 0–3650 days | 7 days | `logRetentionDays` |

!!! note
    **Log retention**: Set to `0` for unlimited retention; otherwise, log archives older than the specified number of days are deleted automatically. Archives older than 24 hours are compressed automatically.