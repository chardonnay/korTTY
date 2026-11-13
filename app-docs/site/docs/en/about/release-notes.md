# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.26.0

### AI agent and AI chat

- **Continue a run in an AI chat** — when a terminal run has finished, a bar under the terminal offers **Continue in AI chat** and a field for a follow-up question; the run menu of every agent design has the same entry. The chat starts with the task and the final answer, and lists the steps of the run (commands, exit codes, shortened output) in a collapsed **Steps** list. Everything stored is masked, including a sudo password typed during the run. See [Continue a run in an AI chat](../features/ai-agent-designs.md#continue-a-run-in-an-ai-chat).
- **AI agent profile** — a new **AI agent profile** role under **AI > AI Manager > Local AI > Profile roles** picks the profile terminal agent runs use when neither the run nor the connection names one; left empty, runs keep using the Coding profile and then the default profile. See [Local AI manager settings](../reference/settings/ai.md#local-ai-manager-settings).
- **Searching the AI Manager** — a search line above the AI Manager's tabs finds an option on any page, opens the page and highlights it; ++cmd+f++ / ++ctrl+f++ jumps to it. See [Searching the AI Manager](../reference/settings/ai.md#searching-the-ai-manager).
- **Sudo in the AI Agent** — Each SSH run opens its own pseudoterminal when it first needs sudo. Enter the password once when validation requests it, and later commands can use the server's sudo ticket until the run ends or the ticket expires. **Allow Always** approves the run's remaining commands. See [Privilege handling](../features/ai-tools.md#privilege-handling).

### Session journal

- **AI chats in the journal** — while the journal records, an **AI agent** card continued in an AI chat gets an **AI chat** tag, an **Open in AI chat** button and a collapsible transcript with the steps of the run. The journal keeps a masked copy that follows the chat until the journal stops, so a deleted chat still opens read-only from it. Exports get an **Include AI chats** option: an appendix in PDF, a file per chat beside Markdown, the transcript in HTML bundles. See [AI agent cards and their AI chats](../features/session-journal.md#ai-agent-cards-and-their-ai-chats).
- **Summaries per command** — the AI summarizer reads the log command by command and never splits one, so a long `dnf upgrade` becomes one entry instead of a dozen. Replies typed into a running program belong to their command; typos and commands that only show help, such as `ls --help` or `man crontab`, are left out of the cards and of the **Commands** count. A long command is condensed or, with chunking, read in full in several prompts; a command still running gets a **still running** entry after 15 minutes. With shell integration korTTY knows each command's exact end and exit status. See [AI summaries](../features/session-journal.md#ai-summaries).
- **Finishing in the background** — the closing pass of a journal runs in the background and shows as **finishing** in the journal manager; a journal left unfinished by quitting korTTY or a crash is finished at the next start.
- **Re-evaluation in the background** — **Re-evaluate with AI…** keeps running when you close its window or the journal manager. The progress window shows the current step, a progress bar, the elapsed time and an estimate of the time left; the journal manager shows the run in a status line with **Show progress** and **Cancel**. See [Re-evaluating a journal with AI](../features/session-journal.md#re-evaluating-a-journal-with-ai).
- **Scrollbars on the journal page** — the log panel has a visible scrollbar of its own, every scrollbar thumb stays at least 40 px long, and the scrollbars of excerpts no longer show through the docked log panel.

### Snippets

- **Structure panel** — **Structure** in the snippet editor toolbar shows a panel left of the code with the classes and functions, the variables (kind, scope, first line, usages) and the placeholders of the snippet. Click an entry to select it in the code; the tree follows the caret and a filter narrows all three lists. See [Structure panel](../features/snippets.md#structure-panel).
- **Lua** — Lua is a snippet language with highlighting, completion and structure rules, also in the file editor of the SFTP manager. **Format Code** uses StyLua and **Check Syntax** uses `luac -p` when they are installed. See [Format Code](../features/snippets.md#format-code).
- **Installed formatters found from the Finder** — Black, RuboCop, StyLua and the other formatters you install yourself are also found in the usual install folders such as `/opt/homebrew/bin` and `~/.cargo/bin`, so they work when korTTY was started from the Finder or the Dock.
