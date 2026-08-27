# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.13.1

### AI assistant

- **[Full code analysis](../features/snippets.md#ai-code-actions) no longer fails on some local reasoning models** — when korTTY requests a strictly structured result from a local server, certain reasoning models return their complete answer in the reasoning channel and leave the actual reply empty. korTTY treated this as an empty reply and discarded a finished analysis after minutes of work. korTTY now detects such a reply and uses the analysis it already contains. Only if that text is unusable does it ask a second time, without the strict format. This was observed with the newer Qwen reasoning models in LM Studio, and it affected **Full code analysis** and **Apply selected** in the snippet editor. Models that return a normal reply are unaffected.

### Terminal

- **A split no longer asks again for the reason of the connection** — when a server wants a reason for the operation, as a CyberArk-style jump host does, every split of a tab opened that dialog again although the reason had been given when the tab was opened. korTTY now sends the answer already given in that tab. It is still sent rather than skipped, because a server that asks for a reason closes a session that answers with nothing. A split to a different server, or a server asking something else, is asked once as well, and a new tab always starts by asking. If the server refuses the reason, because a ticket number has expired in the meantime for example, korTTY drops it and asks again. See [Split-screen with broadcast](../features/terminal.md#split-operations).

!!! note "Earlier releases"
    Only the current release is listed here, so the guide stays short in every language it is translated into. Every version is on the [GitHub releases page](https://github.com/chardonnay/korTTY/releases); the curated notes for earlier versions are kept in the repository, in `app-docs/release-notes-archive.md` and `app-docs/RELEASE_NOTES.adoc`.
