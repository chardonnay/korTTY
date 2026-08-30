# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.13.1

### Snippets

- **Four new AI diagram types** — besides the logical-structure flowchart, the snippet editor can now generate **sequence**, **state**, **class**, and **ER** diagrams. Each family uses its own compact, safety-restricted Mermaid dialect and its own built-in quality skill, and renders in the bundled offline Mermaid renderer as before. See [Mermaid diagrams](../features/snippets.md#mermaid-diagrams).
- **Several diagrams per snippet** — a snippet now stores any number of diagrams. The diagram window lists them with family, title, and line range, offers **New diagram** with a type choice, and can **Delete** a selected diagram; **Regenerate** keeps each diagram's family and scope.
- **Diagram from a selection** — select part of a script and pick a diagram type from the editor's new **Generate diagram** context submenu to diagram just those lines. The diagram remembers the line range, its code references point at the real snippet lines, and regeneration re-reads the same lines.
- **Diagrams no longer fail on thinking models** — the diagram request's output budget covered the model's hidden reasoning as well, so a reasoning model could use up the whole budget before writing a single character of the diagram, and generation failed after minutes of work. The budget now leaves room for that reasoning, and a response that is still cut off says so instead of reporting a generic failure.

### AI assistant

- **[Full code analysis](../features/snippets.md#ai-code-actions) no longer fails on some local reasoning models** — when korTTY requests a strictly structured result from a local server, certain reasoning models return their complete answer in the reasoning channel and leave the actual reply empty. korTTY treated this as an empty reply and discarded a finished analysis after minutes of work. korTTY now detects such a reply and uses the analysis it already contains. Only if that text is unusable does it ask a second time, without the strict format. This was observed with the newer Qwen reasoning models in LM Studio, and it affected **Full code analysis** and **Apply selected** in the snippet editor. Models that return a normal reply are unaffected.

### Terminal

- **Open in Snippet Editor no longer resolves the wrong path after a user switch** — after switching identity inside a session, with `su - root` for example, or an `ssh` typed into a local shell, the context-menu entry resolved a selected file name against the original login's directories and loaded nothing, or a wrong same-named file. The entry is now greyed out while the session runs as a different identity and re-enables on its own once the prompt shows the original user again. See [Local shell tabs](../features/terminal.md#local-shell-tabs).
- **A split no longer asks again for the reason of the connection** — when a server wants a reason for the operation, as a CyberArk-style jump host does, every split of a tab opened that dialog again although the reason had been given when the tab was opened. korTTY now sends the answer already given in that tab. It is still sent rather than skipped, because a server that asks for a reason closes a session that answers with nothing. A split to a different server, or a server asking something else, is asked once as well, and a new tab always starts by asking. If the server refuses the reason, because a ticket number has expired in the meantime for example, korTTY drops it and asks again. See [Split-screen with broadcast](../features/terminal.md#split-operations).

!!! note "Earlier releases"
    Only the current release is listed here, so the guide stays short in every language it is translated into. Every version is on the [GitHub releases page](https://github.com/chardonnay/korTTY/releases); the curated notes for earlier versions are kept in the repository, in `app-docs/release-notes-archive.md` and `app-docs/RELEASE_NOTES.adoc`.
