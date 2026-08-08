---
title: AI
---

# AI

Configure AI profiles and terminal AI Agent settings. This is the largest settings tab, encompassing AI features enablement, profiles (with model, API endpoint, connection mode, and reasoning levels), token quota management, snippet editor settings, and internet access configuration. Open via **Configuration → Global Settings → AI**; stored in `~/.kortty/global-settings.xml`.

![AI settings tab](../../assets/screenshots/settings/ai.png)

## Core Settings

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Enable AI features | toggle | — | On | `aiFeaturesEnabled` |
| Show confirmation dialog before sending AI requests | toggle | — | On | `aiConfirmBeforeSend` |

## Terminal AI Agent

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Enable AI Agent execution | toggle | — | On | `terminalAgentExecutionEnabled` |
| Ask before AI Agent changes the target system | toggle | — | Off | `terminalAgentConfirmMutatingCommandSets` |
| Use OSC 133 prompt markers when the shell already provides them | toggle | — | On | `defaultPromptHookEnabled` |
| Show agent debug messages | toggle | — | Off | `terminalAgentShowDebugMessages` |
| Show agent runtime messages | toggle | — | Off | `terminalAgentShowRuntimeMessages` |
| Show terminal agent setup dialog before each run | toggle | — | On | `terminalAgentShowRunDialog` |
| Agent command name | text | — | agent | `terminalAgentCommandName` |
| Match agent command name case-insensitively | toggle | — | Off | `terminalAgentCommandNameCaseInsensitive` |
| AI agent task target | dropdown | Terminal window, New chat window | Terminal window | `terminalAgentExecutionTarget` |
| Terminal agent input history size | number | 5–100 | 20 | `terminalAgentInputHistorySize` |

## AI Profiles

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Default profile | dropdown | (list of configured profiles) | — | `defaultAiProfileId` |
| AI request timeout | number | 0–1440 minutes | 0 (no timeout) | `aiRequestTimeoutMinutes` |
| Security-check profile | dropdown | (list of configured profiles; empty = use default profile) | — | `securityCheckAiProfileId` |

The security-check profile is a dedicated AI profile for snippet **Security Check** actions. Leave it empty (or use **Clear**) to reuse the default profile. It can also be set directly in the snippet Security Check window, and both places share the same remembered setting.

**AI request timeout** is the maximum runtime of a single AI request and applies to every profile. The default `0` means korTTY imposes no timeout at all: long-running tasks such as the snippet editor's **Full code analysis** run until the model answers. Set a positive number of minutes to cancel requests that exceed it. A profile can override the value — see **Timeout for this profile** below.

### Profile Settings (in Editor Grid)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Profile name | text | — | AI Profile | (profile `name` field) |
| Connection | dropdown | HTTP API, Local CLI, Integrated llama.cpp, Integrated MLX (Apple Silicon; offered on Apple Silicon Macs only) | HTTP API | (profile `connectionMode` field) |
| API URL | text | — | — | (profile `apiUrl` field) |
| CLI provider | dropdown | (registered providers) | — | (profile `cliProviderId` field) |
| CLI executable | text | — | — | (profile `cliExecutablePath` field) |
| Model | dropdown/text | (editable; "Default", curated cloud-provider suggestions plus live-loaded models; "Auto" only for local LM Studio endpoints) | — | (profile `model` field) |
| Local GGUF model | dropdown | Installed chat models; available when Connection is Integrated llama.cpp | — | (profile `embeddedModelId` field) |
| Local MLX model | dropdown | Installed MLX models; available when Connection is Integrated MLX (Apple Silicon) | — | (profile `embeddedModelId` field) |
| Custom model | text | — | — | (profile `cliCustomModel` field) |
| Prompt optimization | dropdown | Auto (model detection), Generic, Llama, Qwen, Mistral, Gemma, DeepSeek, Phi, GPT-OSS | Auto | (profile `promptPreset` field) |
| Reasoning | dropdown | Disabled, None, Minimal, Low, Medium, High, Extra high | Disabled | (profile `reasoningEffort` field) |
| Internet access | dropdown | Disabled, KorTTY Tavily Tool, LM Studio Tavily MCP, Bright Data Web MCP, Brave Search MCP, SearXNG MCP, LM Studio Toolpack | Disabled | (profile `internetAccessMode` field) |
| API Key (optional) | text | (password field) | — | (profile `encryptedApiKey` field) |
| Max characters | number | 1–50,000,000 | 100,000 | (profile `maxSelectionChars` field) |
| Timeout for this profile | check box + number | Own timeout off = follow the global timeout; on: 0–1440 minutes (0 = never time out) | Off | (profile `requestTimeoutMinutes` field) |
| Tokenizer | dropdown | Estimate, OpenAI cl100k_base, OpenAI o200k_base, OpenAI p50k_base, OpenAI r50k_base | Estimate | (profile `tokenizerType` field) |
| Max tokens | number + unit | (amount: 0–1,000,000; unit: Thousands or Millions) | 0 (unlimited) | (profile `tokenLimitAmount`, `tokenLimitUnit` fields) |
| Warning thresholds | number pair | Yellow %: 0–100, Red %: 0–100 | 75%, 90% | (profile `tokenWarningYellowPercent`, `tokenWarningRedPercent` fields) |
| Reset | number + anchor date | Period: 1–3650 days; Anchor date | 30 days | (profile `tokenResetPeriodDays`, `tokenResetAnchorDate` fields) |
| Test AI Connection | button | — | — | (action only) |

## Snippet Editor

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Default language for AI text in code | dropdown | (available language options) | — | `aiCodeTextDefaultLanguage` |
| Show optional additional instructions for AI actions in the snippet editor | toggle | — | Off | `aiSnippetEditorAdditionalInstructionsEnabled` |
| Maximum alternative solutions | number | 1–10 | 3 | `aiSnippetAlternativeSolutionCount` |

## Internet Access Configuration

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Tavily API key | text | (password field) | — | `encryptedAiTavilyApiKey` |
| Bright Data API token | text | (password field) | — | `encryptedAiBrightDataApiToken` |
| Brave Search API key | text | (password field) | — | `encryptedAiBraveSearchApiKey` |
| SearXNG URL | text | — | — | `aiSearxngUrl` |
| Tavily MCP server label | text | — | tavily | `aiTavilyMcpServerLabel` |
| Bright Data MCP server label | text | — | bright-data | `aiBrightDataMcpServerLabel` |
| Brave Search MCP plugin ID | text | — | — | `aiBraveSearchMcpPluginId` |
| SearXNG MCP plugin ID | text | — | — | `aiSearxngMcpPluginId` |
| LM Studio Toolpack MCP plugin ID | text | — | — | `aiLmStudioToolpackMcpPluginId` |

## Notes

### AI Profiles

KorTTY stores multiple named AI profiles, each with its own model, connection method, reasoning settings, prompt preset, and optional knowledge stores. Each profile tracks its own token usage separately. Profiles support three connection modes:

- **HTTP API**: Direct connection to an OpenAI-compatible REST endpoint (specify API URL, model name, and optional API key).
- **Local CLI**: Execute a local command-line AI client (configure CLI provider, custom executable, arguments template, and custom model name).
- **Integrated llama.cpp**: Choose the installed chat GGUF in **Local GGUF model**. korTTY acquires a private loopback `llama-server` lease for it; the API URL and profile API key are managed by korTTY and are not editable.

An explicitly chosen or security-check profile remains most specific. Otherwise terminal text actions use the configured Text profile, code actions use the Coding profile, and an unassigned role falls back to the **Default profile**. Configure those roles and the local runtime under **AI > AI Manager > Local AI**; see [Local models with llama.cpp](../../features/local-models.md).

The AI Manager is modeless and can remain open while you use the main window. Invoking it again restores and focuses the same manager for that main window, and its open primary section remains visibly marked with a bold accent underline when you interact with controls inside that section.

### Local AI manager settings

| Setting | Values | Default | Stored as |
| --- | --- | --- | --- |
| Text and translation profile | Configured AI profile, or use default | Use default | `textAiProfileId` |
| Coding profile | Configured AI profile, or use default | Use default | `codingAiProfileId` |
| Session journal profile | Configured AI profile, or use default | Use default | `sessionJournalAiProfileId` |
| RAG embedding model ID | Installed local embedding model | — | `ragEmbeddingModelId` |
| llama.cpp runtime updates | Off, Notify me, Install stable updates automatically | Notify me | `llamaRuntimeUpdatePolicy` |
| Preferred runtime backend | Auto/CPU/Metal on macOS; Auto/CPU/Vulkan on Windows/Linux | Auto | `preferredLlamaRuntimeBackend` |
| Hugging Face token | Optional encrypted token for gated/private repositories | — | `encryptedHuggingFaceToken` |

**Automatic (keep active backend)** retains the active runtime package backend for updates. With no installed package, Auto initially selects Metal on macOS and CPU elsewhere. Starting a model configured for another supported GPU backend offers to install the matching signed package.

The **Local Models > Setup assistant** exposes optional Text, Coding, and RAG-embedding slots. It verifies every selected fixed revision, quantization, license, and exact size before starting its asynchronous runtime/model installation, runs a real chat or embedding test for each installed GGUF, and saves the resulting role assignments only after all tests succeed. The Text and Coding slots may share one model. **Configure** refuses to replace a model's persisted runtime settings while that model is serving an active request.

Knowledge stores assigned to the Text/Coding roles add only bounded, cited excerpts to matching normal terminal and snippet AI requests, never the complete knowledge store. A cloud Text/Coding profile receives those excerpts over its configured provider connection, so assigning the knowledge store to that role/profile is explicit permission for that disclosure. Agent, Planning, Swarm, and scheduled autonomous prompts remain a separate opt-in; see [RAG knowledge stores](../../features/rag.md).

### Prompt optimization presets

**Auto (model detection)** resolves common Llama, Qwen, Mistral/Mixtral, Gemma, DeepSeek, Phi, and GPT-OSS names. A family preset adds concise compatibility guidance while leaving korTTY's strict JSON/code contracts authoritative; **Generic** adds no family-specific guidance. llama.cpp still applies the GGUF's native chat template.

### Reasoning Effort Levels

Reasoning effort configures how deeply the AI thinks before responding. Available levels depend on the model and endpoint:

- **Disabled**: No reasoning parameter sent; model uses its default behavior.
- **None**: Explicitly disable reasoning with the transport's supported off value.
- **Minimal**: Light reasoning; fastest execution.
- **Low**: Low effort reasoning; balance between speed and depth.
- **Medium**: Medium effort; reasonable depth.
- **High**: High effort; more thorough reasoning.
- **Extra high**: Maximum reasoning effort; slowest but most comprehensive.

Not all models support all levels. Use the **Refresh reasoning options** button to detect available levels for the current profile and model. When LM Studio publishes `capabilities.reasoning.allowed_options` through its native model metadata, korTTY uses that exact list instead of treating a silently converted value as supported. For a binary `off`/`on` model, an explicit `none` request switches this feature off, while omitting the reasoning parameter uses the model's published default; the unsupported Minimal, Low, Medium, High, and Extra high levels are not offered. Other compatible endpoints continue to use active connection probes when no such metadata is available.

For the native Anthropic (Claude) endpoint, an enabled reasoning level requests **extended thinking** with a level-dependent thinking budget; models that do not support extended thinking are retried once without it. The model's reasoning is shown in the Terminal AI Agent's 💭 thinking rows.

### Token Quota Management

Each AI profile maintains a token usage quota with the following controls:

- **Tokenizer**: Choose which tokenizer estimates token counts—useful when switching between OpenAI and other providers. Options are Estimate (generic), cl100k_base (GPT-3.5/4), o200k_base (o1/o1-mini), p50k_base (Codex), and r50k_base (GPT-2).
- **Max tokens limit**: Set a spending cap (in thousands or millions of tokens, or unlimited). Token count resets on a rolling schedule.
- **Reset period**: Number of days between resets (1–3650), with an optional anchor date for predictable reset timing.
- **Warning thresholds**: Yellow warning triggers at a percentage of the limit; red warning at a higher percentage. Configure both as integers 0–100.

Token usage is displayed as a colored bar and summary on the profile editor, and the profile list shows token status inline.

### Internet Access Modes

Per-profile internet access strategy for AI requests. Each mode requires different credentials and MCP configuration:

- **Disabled** (default): No internet access.
- **KorTTY Tavily Tool**: Built-in web search using Tavily API directly (requires Tavily API key).
- **LM Studio Tavily MCP**: Web search via an LM Studio Tavily MCP instance (requires Tavily API key and MCP server label).
- **Bright Data Web MCP**: Structured data extraction and browsing via Bright Data Web MCP (requires Bright Data API token and MCP server label).
- **Brave Search MCP**: Search via Brave Search MCP (requires Brave Search API key and MCP plugin ID in `mcp/<server_label>` format).
- **SearXNG MCP**: Search via a SearXNG MCP instance (requires SearXNG URL and MCP plugin ID in `mcp/<server_label>` format).
- **LM Studio Toolpack**: Community LM Studio Toolpack web-search server (requires plugin ID in `mcp/<server_label>` format).

Credentials are encrypted and stored securely. Use the **Clear** toggle adjacent to each secret field to erase stored values on the next save.
