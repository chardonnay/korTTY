# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.25.0

### AI memory and skills

- **AI memory** — korTTY keeps a memory that AI chat and the agent send with their requests: a global memory and one per connection, as plain Markdown files you can read and edit. It has a token budget per request, your own text is never changed without your confirmation, and the entries korTTY wrote can be merged, archived and restored from the archive (Show archive…); the history keeps earlier versions of a memory file that you can restore. See [AI memory](../features/ai-memory.md).
- **Reference files of other tools** — the CLAUDE.md, AGENTS.md and similar files of other AI tools can be added as read-only memory once you have read and approved their text; a changed file waits for your approval again. See [AI memory](../features/ai-memory.md).
- **Solution options** — before a complex request runs, the agent can offer a recommended option, your own idea and an optional permanent rule. **Estimate complexity** sets when this happens: Automatic, Always or Never. See [Solution options](../features/ai-tools.md#solution-options).
- **Rules learned from conversations** — in **Suggest rules** mode korTTY proposes a rule when a message says something should be kept for later; nothing is saved without your confirmation, and a saved rule can be undone for 30 seconds. Rules go into the memory or into a skill, and korTTY can create a new skill for a topic that has none. See [AI memory](../features/ai-memory.md).
- **Remember as rule…** — saves a rule you write yourself, on your own chat messages and in the agent's prompt dialog, without an AI call.
- **Sent along** — under each chat answer and each agent run, a collapsed line lists the memory entries, skills and reference files that were sent, masked as they were sent, with their token counts.
- **Secret guard** — korTTY refuses to save a password, key or token in a skill, a learned rule, the memory or an approved reference file, and it marks skills and memory notes that already hold one. The value is never shown. See [Secrets in skills](../reference/settings/ai-skills.md#secrets-in-skills).
- **Skill statistics** — the **Statistics** dialog of AI Skills shows how often each skill is used, its size and its growth, with hints for skills to review. **Shorten...** proposes a shorter version that is saved only after you confirm it. See [Skill statistics](../reference/settings/ai-skills.md#skill-statistics).
- **Imported skill updates** — an update from the internet never overwrites a skill you changed; the update dialog then offers to import the provider's version as a copy or to skip that version. See [External skills](../reference/settings/ai-skills.md#external-skills).
- **Enterprise policy** — the `[rule.ai-memory]` table sets how the memory may be used, how rules are learned, whether korTTY may create skills, which reference files may be read, the token budget, the triage mode and the statistics. `[[organization-memory]]` adds rules that every user gets and cannot change. See [Enterprise policy](../reference/enterprise-policy.md#ruleai-memory).
- **Backups** — the memory and the skill statistics are part of a backup. See [Backup & restore](../features/backup.md#backup-file-contents).

### Coding agents

- **Kilo CLI** — the Coding Agents panel now recognises **Kilo** (`kilo` or `kilocode`, npm package `@kilocode/cli`), a fork of OpenCode: working, waiting for your answer and idle at its prompt. **Reject** in its permission dialog first asks what Kilo should do differently, so the pane stays *waiting for you* until you confirm with ++enter++. See [What it detects](../features/coding-agents.md#what-it-detects).

### Local models

- **Real compute backend** — the **Backend** column of the Runtimes and model tables shows the GPU or CPU backend a runtime computes on, as CPU, Metal, Vulkan or Auto: MLX runtimes and MLX models show Metal instead of "MLX", and before a runtime is installed the column shows the backend an installation would bring, such as "Metal (on install)". See [Runtimes](../features/local-models.md#runtimes).
