# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.20.0

### Terminal logs

- **Logfile Manager** — *Tools → Logfile Manager…* (++ctrl+shift+x++, ++cmd+shift+x++ on macOS) finds terminal logs in default, connection-specific, previously used and manually added folders. Filter by name, time range, format or compression; inspect file sizes and retention dates; export compressed copies or delete selected files after confirmation. Live logs are protected from deletion. See [Logfile Manager](../features/terminal.md#logfile-manager).
- **Log viewer** — open large or compressed logs without loading the whole file into the viewer, search, jump to a line, copy selected lines and highlight keywords, links and paths. JSON and XML logs have a formatted view with a raw-view toggle. See [Viewing a log](../features/terminal.md#logfile-manager).
- **Context and badges** — log entries can include the SSH username and the full working directory or a path relative to the first reported directory, subject to enterprise policy. The manager displays connection names and stable aliases for IP-only names, supports searchable custom badges, and offers a **Hold** badge that prevents automatic and manual deletion. See [Terminal logging](../features/terminal.md#terminal-logging) and [Logfile Manager](../features/terminal.md#logfile-manager).
- **Protected exports** — export whole logs or selected lines, optionally trim them to a time range and convert between JSON, XML and plain text. Archives can use AES-256 password encryption and additional GPG encryption. See [Exporting logs](../features/terminal.md#logfile-manager).
- **Valid XML after policy replacements** — terminal XML logs remain parseable when an enterprise replacement rule inserts characters XML cannot represent.

### Window and coding agents

- **Customizable icon toolbar** — add, remove, rearrange and preview terminal, tool and settings buttons, with a separate row for saved servers and groups. Visibility and layout are shared across windows; icon size is adjustable from 16 to 64 px. The Tools group includes the Logfile Manager, and existing layouts gain its button once while preserving their other buttons and order. The in-window menu bar starts visible. See [Customizing the icon toolbar](../getting-started/main-window.md#customizing-the-icon-toolbar).
- **Docked panels keep their width** — the AI agent panel, Coding Agents, Live Journal and local file browser retain their selected width beside wide terminals and shrink proportionally when the window has insufficient space.
- **More coding agents** — local shell tabs now also recognise MiniMax Code, Qwen Code, OpenCode, Cursor Agent, Aider, Goose, Crush, Continue, Superagent's Grok CLI, xAI's Grok Build, GitHub Copilot CLI, Jules, Amp and Kiro CLI. Amp and Kiro CLI rules are based on the programs' own texts and remain unverified against live sessions. See [What it detects](../features/coding-agents.md#what-it-detects).
- **Sidebar and quick answers** — the Coding Agents sidebar automatically appears on detection and hides when no agents remain; its selected side is remembered, automatic visibility can be disabled, and **Explain** details collapse after 60 seconds. **YES** and **NO** send the complete uppercase word followed by Enter, so one click submits the answer. See [Coding agents](../features/coding-agents.md).

### AI Manager

- **Profile editor in sections** — the **Profiles** tab keeps the profile name and **Use as default profile** at the top and groups the fields into **Connection**, **Model & capabilities**, **Limits & timeouts** and **Budget & usage**. Only the rows of the selected connection mode are shown, the request timeout that applies to every profile heads the Limits & timeouts section, and **Test AI Connection**, **Delete** and **Save** sit under the editor. See [AI Manager](../features/ai-assistant.md#ai-manager). **Test AI Connection** shows its result in a coloured bar under the profile name instead of only in the status line.
- **Local AI in sections** — the **Local AI** tab splits into **Profile roles**, **Local runtime** and **Hugging Face**. See [Text, coding, translation, and embedding roles](../features/local-models.md#text-coding-translation-and-embedding-roles).
- **Profile setup wizard** — a sidebar shows the stages and the choices already made, a **Quick start** finds LM Studio, Ollama (on this computer, on the hosts you enter under **Other computers on the network**, and on the hosts of existing profiles) and installed command-line tools and offers one-click profiles, connection types and providers are cards, reasoning sits under **Advanced**, and the last step runs the test by itself and shows a summary with **Change** links. See [AI Profile Setup Wizard](../features/ai-assistant.md#ai-profile-setup-wizard).

### Settings

- **Search in the settings** — a search line at the top of **Global Settings** finds a setting on any tab as you type. Choosing a result opens its tab, scrolls to the setting, colours it and focuses its control. See [Searching the settings](../reference/settings/index.md#finding-a-setting).
- **Manual for each tab** — **Manual for this page** in the settings navigation bar opens the built-in manual at the page for the current tab. See [Manual for each tab](../reference/settings/index.md#manual-for-each-tab).

### Notifications and AI skills

- **Messaging settings** — desktop notification choices now have a dedicated **Messaging** settings page, including a separate switch for coding-agent messages. Saving applies the choices to open sessions. See [Messaging settings](../reference/settings/messaging.md).
- **Notification clicks return to the pane** — clicking a desktop notification selects its window, tab and terminal pane. The Flatpak sandbox permits the desktop notification service, and macOS development runs build a native notification helper. The enterprise policy can disable desktop notifications for every feature. See [Clicking a notification](../features/terminal-notifications.md#clicking-a-notification).
- **Import and update external AI skills** — the AI Skills manager imports `SKILL.md` files from GitHub, SkillsMP and HTTP(S) providers. Provider tokens and passwords are encrypted with the master password, and source updates are previewed as a side-by-side diff. Imported skills start deactivated; only the skill text is imported, with a 256 KiB limit and HTTPS required except for loopback addresses. See [External skills](../reference/settings/ai-skills.md#external-skills).

### JobScheduler

- **Job dependencies** — a job can depend on other jobs and either keep its own schedule but only run when every predecessor's last run succeeded (**Condition**), or start automatically once all predecessors have succeeded (**Chain**), optionally only counting recent successes. Cycles are refused, the job list shows a **Depends on** column, and deleting a job that others depend on warns first. **Run with successors** runs a job and everything below it in dependency order, and **Disable chain** switches a job and all its dependents off or on at once. See [Job Dependencies](../features/jobscheduler.md#job-dependencies).
- **Dependency diagram** — draws the job dependencies as a Mermaid flowchart colored by each job's last result, vertical or horizontal, in light or dark mode, with SVG/PNG export. See [Dependency Diagram](../features/jobscheduler.md#dependency-diagram).

### SFTP Manager

- **Active panel** — the panel you clicked or typed in last is framed in the accent colour and draws its selection in it; the other panel's selection is muted. Each panel now has its own row of buttons under its list, including **Archive** on the local side, with **Upload** and **Download** facing each other across the divider. See [Active panel](../features/sftp.md#active-panel).
- **Hide hidden files** — an eye button beside each panel's path, or ++cmd+shift+period++ (++ctrl+shift+period++ on Windows and Linux), shows or hides dot files and folders (and Windows hidden files); each panel remembers its choice. See [Hidden files](../features/sftp.md#hidden-files).
- **Sort order** — hidden folders come first, then hidden files, folders and files, and this holds for every column; the column you sort by orders the entries within each group. See [Sort order](../features/sftp.md#sort-order).
- **Permission checkboxes** — **Set Owner/Permissions** shows the mode as checkboxes for owner, group and others next to the octal field and keeps both in step. On the local side the user, the group and **Apply recursively to directories** now take effect. See [Permissions](../features/sftp.md#permissions).
- **User and group names, folder sizes** — servers that send owners only as numbers, such as OpenSSH, now show user and group names, looked up on the server in the background. **Calculate size** in the server's right-click menu measures folders with `du`, and the size estimate in the archive dialog now includes the selected folders. See [Owner and group names](../features/sftp.md#owner-and-group-names) and [Folder sizes](../features/sftp.md#folder-sizes).
- **Compare and synchronize folders** — **Compare** reads both folders, the local one and the one on the server, with all subfolders, counts what is only on one side, newer or different, and colours the rows. **To the server** and **To this computer** copy what is missing or newer, keep modification times, and can delete what exists only on the target. See [Comparing and synchronizing folders](../features/sftp.md#comparing-and-synchronizing-folders).
- **File comparison with AI questions and a new file from both** — select two files, two in one panel or one in each, also a local one against one on the server, and click **Compare** to see them side by side. With the AI features on, **Ask AI** explains where they differ, what the differences do or what could be improved, for example in scripts and configuration files, and **New file** has the AI write a third file from both, which you can save on the server or on this computer or keep in the Snippet Manager. Two folders on the same side can be compared as well. See [Comparing two files](../features/sftp.md#comparing-two-files).
- **Path bar and status line** — each panel shows its path as clickable folders (++cmd+l++ or ++ctrl+l++ to type one) and a line under the list with the number of entries, the selection and its size, and the hidden entries. See [Path bar and status line](../features/sftp.md#path-bar-and-status-line).
- **Archive dialog says what it archives** — the computer, the folder, the number of folders and files and their names. The server's archive tools are looked up in the background, so a server that runs no commands no longer freezes the window. See [Creating archives](../features/sftp.md#creating-archives).

### Fixes and maintenance

- **Log export controls stay visible** — the options dialog is resizable and scrollable, with an explicit **Export…** button that opens the save dialog for one log or a line selection, or a folder chooser for several logs. Export and Cancel stay visible even in a short window. See [Logfile Manager](../features/terminal.md#logfile-manager).
- **Snippet analysis progress stays usable** — the checklist remains visible in short panels and after a run finishes, and progress content no longer intercepts clicks on the dialog's **Close** button. See [Applying improvements](../features/snippets.md#applying-the-selected-improvements).
- **Complete log translations** — added the missing log context, badge and export labels in Spanish, French, Croatian, Italian, Dutch and Portuguese. The build checks each locale for missing keys and reports their names.
- **Refreshed guide and dependencies** — reconciled the manual with the implemented features, refreshed screenshots, improved dark-theme tree tables, and updated build, documentation and compression dependencies.

!!! note "Earlier releases"
    Only the current release is listed here, so the guide stays short in every language it is translated into. Every version is on the [GitHub releases page](https://github.com/chardonnay/korTTY/releases); the curated notes for earlier versions are kept in the repository, in `app-docs/release-notes-archive.md` and `app-docs/RELEASE_NOTES.adoc`.
