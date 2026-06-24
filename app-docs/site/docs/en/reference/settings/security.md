---
title: Security
---

# Security

This tab manages password vault security and SSH key authentication options. Open via **Configuration → Global Settings → Security**; stored in `~/.kortty/global-settings.xml`.

![Security settings tab](../../assets/screenshots/settings/security.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Change Master Password | button | — | — | — |
| Require master password on startup | toggle | — | On | `requireMasterPasswordOnStartup` |
| Enable temporary SSH key option | toggle | — | Off | `temporarySshKeyEnabled` |

!!! warning "Master password on startup"
    If "Require master password on startup" is disabled, encrypted passwords and SSH keys cannot be automatically decrypted without manual password entry. This is a security risk and should only be disabled if you understand the consequences.

!!! note "Change Master Password button"
    Opens a dialog to set or change the master password. All stored connection passwords are automatically re-encrypted when you change this password.