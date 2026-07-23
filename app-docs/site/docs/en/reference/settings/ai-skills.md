---
title: AI Skills
---

# AI Skills

Configure custom AI skills that enhance AI interactions. This tab lets you manage a library of markdown-based skills that are automatically or manually included in AI requests. Open via **AI → AI Manager → AI Skills**; stored in `~/.kortty/global-settings.xml`.

!!! note "Moved out of Global Settings"
    The skill library used to be a tab in **Configuration → Global Settings**. It now lives in the **AI Manager**, next to profiles, local models and knowledge stores. The stored data and the settings file are unchanged.

![AI Skills settings tab](../../assets/screenshots/settings/ai-skills.png)

## Global Settings

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Enable AI Skills | toggle | — | On | `aiSkillsEnabled` |
| Automatically send only matching skills | toggle | — | On | `aiSkillAutoDetectionEnabled` |
| Show hidden built-in skills | toggle | — | Off | — (view filter) |
| Search skills | text | Filters the list by name, description or tags | — | — (view filter) |
| Sort | menu button | Alphabetical, Status (enabled first) | — | — |
| Save | button | Writes the library to the global settings file | — | — |

A count line below the list summarizes the whole library — **Total**, **Active** (enabled and not hidden), and **Inactive/hidden** — regardless of the current search or hidden filter.

## Skill Editor Fields

When you select or create a skill, the right panel shows per-skill fields. Each skill is persisted individually in the skill list.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Skill name | text | — | "AI Skill" | `name` (on AiSkill object) |
| Description | text | — | — | `description` |
| Tags | text | Comma-separated tags (e.g. linux, bash) | — | `tags` |
| Target | dropdown | AI Chat/Functions, AI Agent, Both, Connection | Both | `target` |
| Active | toggle | — | On | `enabled` |
| Skill Markdown | text | Markdown-formatted skill content | — | `content` |

## Built-in skills

korTTY ships 39 built-in best-practice skills covering shells (Bash, KornShell, Zsh, Csh, POSIX sh, PowerShell), programming languages (Python, C, C++, Java, C#, JavaScript, Visual Basic, SQL, R, Rust, Go, PHP, Swift, Assembly, Macro Assembler, Ruby, Perl, Lua, Groovy, TypeScript, Kotlin, Dart), markup and data formats (HTML, XML, YAML, JSON) and automation/observability tools (Puppet, Ansible, Azure DevOps Pipelines, Jenkins Declarative and Scripted Pipelines, Filebeat, Logstash). Each skill carries professional guidance on code commenting, robustness, common pitfalls to avoid, and language-specific security practices. They are added to the library on first start and appear with a **Built-in** badge.

Built-in skills behave like your own skills — they can be edited, deactivated and assigned to connections — with these differences:

- **They cannot be deleted, only hidden.** *Hide* removes a built-in from the list and from all skill pickers; *Show hidden built-in skills* reveals hidden entries so they can be brought back with *Unhide*.
- **Unmodified built-ins update automatically.** When a new korTTY release ships improved skill content, unchanged built-ins are replaced silently at startup (your Active/hidden choices are kept).
- **Modified built-ins are never touched.** Once you edit a built-in it shows a **Built-in (modified)** badge and stops auto-updating. *Reset to shipped version* discards your changes and restores the delivered version your edits were based on. If a newer delivered version exists, the entry shows 🔄 **Update available** and *Update to latest shipped version* adopts it.
- **Your own skills always take precedence.** When one of your enabled skills carries a tag matching a built-in's topic (for example a personal skill tagged `perl`), the built-in is suppressed: it shows **Overridden by user skill**, is grayed out, and is no longer sent with any AI request. Deleting or deactivating your skill immediately reactivates the built-in.
- Deactivated, hidden and overridden entries are rendered grayed out; this works in every application design theme.

!!! note "Switching off auto-detection deactivates built-ins"
    Without auto-detection every enabled skill is sent with every AI request, which would inflate prompts massively with 35 built-in skills. Turning **Automatically send only matching skills** off therefore asks for confirmation and then deactivates all built-in skills; re-enable the ones you need individually. Built-ins delivered by later releases also arrive deactivated while auto-detection is off.

## Notes

!!! note "Auto-detection behavior"
    When **Automatically send only matching skills** is enabled, korTTY scores each skill against the current request across four fields — tags, name, description, and the headings in the skill's Markdown — and includes those that clear the relevance threshold. When the local scores are inconclusive (nothing matched, or several skills score almost equally), it additionally asks the model to classify the request and prefers that answer, falling back to the local result if the call fails. When disabled, all applicable skills are sent without scoring — a skill is still skipped when it is inactive, has empty content, or does not match the current target.

!!! note "Skill targets"
    - **AI Chat/Functions**: Skills available in AI Chat and function call contexts
    - **AI Agent**: Skills used by the terminal AI agent
    - **Both**: Available to both AI Chat and AI Agent contexts
    - **Connection**: Available to both AI Chat and AI Agent, but only on the connections the skill is assigned to. Such skills are always sent on those connections and bypass auto-detection.

!!! note "Skill lifecycle"
    Skills are stored as XML elements within the global settings file. Use **Import** to load skills from markdown files and **Export** to save selected skills as markdown files. **Delete** applies to your own skills only; built-in skills are hidden instead (see above). Reset, update, hide and unhide are available from the list's context menu and from the banner above the editor when a built-in skill is selected. The skill list can be sorted alphabetically by name or by status (enabled first). **Save** persists the library immediately and confirms next to the button; pending edits are also written when the AI Manager window is closed. Importing a markdown file always creates an independent user skill — even a file exported from a built-in skill.

!!! note "Choosing skills per request"
    This tab manages the global library. Which of these enabled skills apply to a given action is chosen elsewhere: the snippet editor's **AI skills** picker pins your selection to every snippet AI action, and the **Full code analysis** window shows the included skills as chips — labelled *(auto-selected)* or *(manual)* — with a searchable picker whose changes take effect on the next re-run. See [Snippets → AI skills](../../features/snippets.md#ai-skills).
