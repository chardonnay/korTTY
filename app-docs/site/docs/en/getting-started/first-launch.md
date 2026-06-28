# First launch — master password

On first launch, korTTY asks you to create a **master password**. This password
encrypts all stored connection passwords, SSH key passphrases and credentials
using **AES-256-GCM**.

1. Enter a password (minimum 6 characters). The field border turns **green** when
   long enough and **red** when too short; a strength indicator rates the quality.
   A weak or common password shows a warning but can still be used if you confirm.
2. Confirm the password.
3. Click **Setup**.

On subsequent launches you are prompted to enter the master password to unlock
your encrypted data.

!!! warning "Disabling the prompt"
    You can disable the unlock prompt in **Settings → Security**, but stored
    passwords remain inaccessible until you enter the master password manually.

## What is encrypted

| Data | File | Protection |
| --- | --- | --- |
| Connection passwords | `~/.kortty/connections.xml` | AES-256-GCM (master-password-derived key) |
| Stored credentials | `~/.kortty/credentials.xml` | AES-256-GCM |
| SSH key passphrases | `~/.kortty/ssh-keys.xml` | AES-256-GCM |
| Master password | `~/.kortty/master-password-hash` | salted hash (verification only) |

See the [Security reference](../features/connections.md) for the full
encryption and backup model.

[Next: Main window overview →](main-window.md){ .md-button }
