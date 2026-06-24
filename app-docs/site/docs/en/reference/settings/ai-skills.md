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
| Sort | dropdown | Alphabetical, Status (enabled first) | Alphabetical | — |

## Skill Editor Fields

When you select or create a skill, the right panel shows per-skill fields. Each skill is persisted individually in the skill list.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Skill name | text | — | "AI Skill" | `name` (on AiSkill object) |
| Description | text | — | — | `description` |
| Tags | text | Comma-separated tags (e.g. linux, bash) | — | `tags` |
| Target | dropdown | AI Chat/Functions, AI Agent, Both, Connection | Both | `target` |
| Active | toggle | — | Off | `enabled` |
| Skill Markdown | text | Markdown-formatted skill content | — | `content` |

## Notes

!!! note "Auto-detection behavior"
    When **Automatically send only matching skills** is enabled, korTTY evaluates each skill's tags against the current request and includes only those that match. When disabled, all active skills are sent regardless of tags.

!!! note "Skill targets"
    - **AI Chat/Functions**: Skills available in AI Chat and function call contexts
    - **AI Agent**: Skills used by the terminal AI agent
    - **Both**: Available to both AI Chat and AI Agent contexts
    - **Connection**: Skills specific to SSH connection handling

!!! note "Skill lifecycle"
    Skills are stored as XML elements within the global settings file. Use **Import** to load skills from markdown files and **Export** to save selected skills as markdown files. The skill list can be sorted alphabetically by name or by status (enabled first).
