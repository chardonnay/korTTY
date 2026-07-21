---
title: AI Skills
---

# AI Skills

Configure custom AI skills that enhance AI interactions. This tab lets you manage a library of markdown-based skills that are automatically or manually included in AI requests. Open via **Configuration → Global Settings → AI Skills**; stored in `~/.kortty/global-settings.xml`.

![AI Skills settings tab](../../assets/screenshots/settings/ai-skills.png)

## Global Settings

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Enable AI Skills | toggle | — | On | `aiSkillsEnabled` |
| Automatically send only matching skills | toggle | — | On | `aiSkillAutoDetectionEnabled` |
| Sort | menu button | Alphabetical, Status (enabled first) | — | — |

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

## Notes

!!! note "Auto-detection behavior"
    When **Automatically send only matching skills** is enabled, korTTY scores each skill against the current request across four fields — tags, name, description, and the headings in the skill's Markdown — and includes those that clear the relevance threshold. When the local scores are inconclusive (nothing matched, or several skills score almost equally), it additionally asks the model to classify the request and prefers that answer, falling back to the local result if the call fails. When disabled, all applicable skills are sent without scoring — a skill is still skipped when it is inactive, has empty content, or does not match the current target.

!!! note "Skill targets"
    - **AI Chat/Functions**: Skills available in AI Chat and function call contexts
    - **AI Agent**: Skills used by the terminal AI agent
    - **Both**: Available to both AI Chat and AI Agent contexts
    - **Connection**: Available to both AI Chat and AI Agent, but only on the connections the skill is assigned to. Such skills are always sent on those connections and bypass auto-detection.

!!! note "Skill lifecycle"
    Skills are stored as XML elements within the global settings file. Use **Import** to load skills from markdown files and **Export** to save selected skills as markdown files. The skill list can be sorted alphabetically by name or by status (enabled first).

!!! note "Choosing skills per request"
    This tab manages the global library. Which of these enabled skills apply to a given action is chosen elsewhere: the snippet editor's **AI skills** picker pins your selection to every snippet AI action, and the **Full code analysis** window shows the included skills as chips — labelled *(auto-selected)* or *(manual)* — with a searchable picker whose changes take effect on the next re-run. See [Snippets → AI skills](../../features/snippets.md#ai-skills).
