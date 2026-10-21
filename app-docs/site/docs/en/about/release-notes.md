# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.22.0

### AI agent designs

- **Choose how agent runs look** — *Plugins → AI Agent Design* (also *View → AI Agent Panel → Design...*) decides how runs of the terminal AI agent are shown: as blocks in the terminal above the prompt, in a slim bar below or above it, on a rail or in a column beside it, as a card floating over it, or in the classic activity panel. A combination picks one design for each role (history, status, approval, arrangement) with a live preview; the new default shows the run as a block in the terminal with a metro-line status bar, and each connection can pick its own combination on the connection editor's AI tab. See [AI agent designs](../features/ai-agent-designs.md).
- **More status displays** — Powerline, minimap, top bar and neon displays, and the remaining designs of the catalogue, complete the choice. See [More status displays](../features/ai-agent-designs.md#more-status-displays).

### Local AI models

- **Context window in the profile** — an Integrated llama.cpp profile has a **Context window** row with a number field and a slider, from 512 tokens up to the context the model was trained for; saving the profile applies it to the model. New local chat models start with 32,768 tokens, or less when the model was trained for less, so dense models still load on common hardware. See [Configure and run models](../features/local-models.md#configure-and-run-models).
- **GPU backend and runtime package match** — saving a model with Metal or Vulkan while another runtime package is active now offers to install and activate the matching signed package, and the **Backend** column shows a mismatch such as `VULKAN (runtime: CPU)`. See [Configure and run models](../features/local-models.md#configure-and-run-models).
- **Self-contained Windows runtimes** — the llama.cpp packages for Windows no longer depend on OpenSSL libraries of the build machine, so **Install runtime** works on a clean system, and a failed health check is reported instead of rolling back silently.
- **Truncated agent replies are reported** — when a model cuts the agent's reply off at its token limit, the run stops with a message that names the token counts and points to the model's context size or the profile's maximum output tokens, instead of ending with an empty response.

### Toolbar, terminal and Windows

- **Connection Manager in the toolbar** — the icon toolbar's Tools group now starts with a Connection Manager button; saved layouts gain it once, and removing it is remembered. See [Customizing the icon toolbar](../getting-started/main-window.md#customizing-the-icon-toolbar).
- **Ctrl+V pastes in the terminal on Windows** — as in Windows Terminal; Linux keeps ++ctrl+v++ for the shell. See [Pasting text](../features/terminal.md#pasting-text).
- **Dark title bars on Windows** — in dark mode the main window and all dialogs get a dark native title bar, and a system theme switch reaches open windows.
- **Local shells come back** — *Restore Previous Session* reopens local shell tabs started from the toolbar instead of parking them as "connection missing". See [Session Restore](../reference/settings/window.md#session-restore).
- **Terminal agent on PowerShell and cmd** — an intercepted `agent ...` line is cleared without a stray `^U`, and nested `powershell -Command` scripts from the model run correctly.

### Fixes

- **Agent prompts fit the panel** — a pending approval or password prompt grows a collapsed agent panel until the prompt is fully visible.
- **AI confirmation explained** — a hint under **Show confirmation dialog before sending AI requests** names the actions it covers, and the **Save** button of the General, Local AI and AI Skills tabs sits at the bottom right. See [AI settings](../reference/settings/ai.md).
- **Recording and journal icons follow the theme** — they no longer turn black in the AtlantaFX designs.

