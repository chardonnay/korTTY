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
    Opens a dialog asking for the **Current Password**, the **New Password (at least 6 characters)** and a **Confirm New Password** repetition; **Change** applies it. korTTY rejects a wrong current password, a new password under six characters, and a mismatch between the two new entries, each with its own message. On success it reports how many connection passwords were re-encrypted — every stored connection password is re-encrypted with the new master password.