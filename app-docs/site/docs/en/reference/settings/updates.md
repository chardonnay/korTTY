---
title: Updates
---

# Updates

Configure automatic update checking and the frequency at which korTTY queries GitHub for new releases. Open via **Configuration → Global Settings → Updates**; stored in `~/.kortty/global-settings.xml`.

![Updates settings tab](../../assets/screenshots/settings/updates.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Check automatically for KorTTY updates | toggle | — | On | `updateChecksEnabled` |
| Check interval | slider | 1–30 days | 1 day | `updateCheckIntervalDays` |

!!! note
    Automatic update checks run silently in the background and only display a notification dialog when a newer compatible version is available. Manual update checks are available via the **About** dialog at any time.
