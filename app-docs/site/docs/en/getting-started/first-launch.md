# First launch — master password

On first launch, korTTY asks you to create a **master password**. This password encrypts all stored connection passwords, SSH key passphrases and credentials using **AES-256-GCM**.

1. Enter a password (minimum 6 characters). The field border turns **green** when long enough and **red** when too short; a strength indicator rates the quality. A weak or common password shows a warning but can still be used if you confirm.
2. Confirm the password.
3. Leave **Share anonymous usage statistics** ticked to help improve korTTY, or clear the checkbox if you would rather share nothing. It is pre-selected, but the choice is yours before you confirm — what is collected is fully anonymous and GDPR-compliant, and the setting can be changed any time in **Settings → Privacy**. The **?** button opens [Anonymous data for application optimization](../about/anonymous-data.md). An organization policy that forbids telemetry locks the checkbox and leaves it clear.
4. Click **Setup**.

On subsequent launches you are prompted to enter the master password to unlock your encrypted data.

!!! warning "Disabling the prompt"
    **Settings → Security** offers two ways to skip the prompt. Turning off **Require master password on startup** hides the prompt, but stored passwords remain inaccessible until you enter the master password manually. **Disable master password prompt on startup (auto-login)** instead unlocks the vault automatically from a copy of your master password kept on disk — only obfuscated, not encrypted, so reserve it for throwaway or test environments. See the [Security settings](../reference/settings/security.md) for details, including unattended first launches that skip the setup dialog entirely.

## What is encrypted

| Data | File | Protection |
| --- | --- | --- |
| Connection passwords | `~/.kortty/connections.xml` | AES-256-GCM (master-password-derived key) |
| Stored credentials | `~/.kortty/credentials.xml` | AES-256-GCM |
| SSH key passphrases | `~/.kortty/ssh-keys.xml` | AES-256-GCM |
| Master password | `~/.kortty/master.key` | salted hash (verification only) |
| Remembered master password (auto-login only) | `~/.kortty/master.autounlock` | obfuscated only — not encrypted; owner-only file permissions |

See the [Security reference](../features/connections.md) for the full encryption and backup model.

[Next: Main window overview →](main-window.md){ .md-button }
