# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.13.0

### Connection loss and reconnect

- **A lost connection no longer closes the tab** — when an established SSH connection drops (network outage, VPN cut, server gone), the tab now stays open in a red `(DISCONNECT)` state with a red status bar showing when the connection was lost, instead of silently closing as if you had typed `exit`. Reconnect in place with a double-click on the bar or tab, or via **Reconnect** in the context menus; a normal remote logout still closes the tab as before.
- **Connection loss is detected within about ten seconds** — korTTY actively probes the server over the SSH connection (the same technique as OpenSSH's `ServerAliveInterval`) and treats two consecutive unanswered probes as a lost connection, instead of waiting minutes for a TCP timeout. The terminal cursor also stops blinking in a disconnected tab, so a dead session no longer looks alive.
- **Automatic reconnect** — a new **Settings → Terminal → Automatically reconnect lost connections** option (on by default) reconnects a lost tab on its own, with delays growing from 3 seconds up to one attempt per minute and a countdown in the red status bar. Permanent failures such as a wrong password, a changed host key, or a configuration refusal are never retried automatically, and while a session journal is deciding how to continue, the journal's own reconnect choice takes precedence. See [Terminal sessions → Connection loss](../features/terminal.md#connection-loss-and-automatic-reconnect).

### Privacy

- **Anonymous usage statistics are now pre-selected during first-run setup** — the checkbox next to the master-password setup ([Anonymous data for application optimization](../about/anonymous-data.md)) starts ticked, so sharing is the default and clearing it before you confirm is the opt-out. An organization policy that forbids telemetry locks the checkbox to the enforced state instead. What is collected is unchanged: voluntary, anonymous, EU servers, and changeable any time in **Settings → Privacy**.

### Appearance

- **A first installation now starts with [Match display resolution](../reference/settings/appearance.md#ui-font-size) on** — korTTY scales its UI font size to the screen from the very first launch instead of only after someone finds the setting. Updating an existing installation never changes it: whatever it had stays.

### Translation

- **Yandex Translate works again** — it still spoke the retired Translate API v1.5, whose credentials Yandex stopped accepting, so the provider was dead for anyone who selected it. It now calls Cloud Translate v2 with the current `Api-Key`/IAM-token authentication, batches requests to the API's limits, and no longer sends a leftover v1.5 API URL to a host that is gone. A rejected key's fragments, which Yandex echoes back inside its own error message, are now redacted before anything reaches a log file.
- **Microsoft Translator now reaches regional and custom-domain resources** — a new optional **Azure region** setting, shown only while Microsoft Translator is selected, is required by any Azure resource that is not global; such a resource previously could not be reached at all.
- **DeepL now recovers from a wrong Free/Pro host guess** — the key suffix korTTY uses to pick a host is only reliable for older Free keys; a 403 response now retries once against the other host instead of failing outright.

!!! note "Earlier releases"
    Only the current release is listed here, so the guide stays short in every language it is translated into. Every version is on the [GitHub releases page](https://github.com/chardonnay/korTTY/releases); the curated notes for earlier versions are kept in the repository, in `app-docs/release-notes-archive.md` and `app-docs/RELEASE_NOTES.adoc`.
