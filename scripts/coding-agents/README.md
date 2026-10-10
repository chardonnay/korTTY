# Coding-agent check

`check.py` verifies on a developer machine that korTTY still **detects** the coding agents installed there and can **drive** them the way its Coding Agents panel does. It is the tool behind the manually started "coding-agent update" Claude routine, and it can be run by hand.

```bash
python3 scripts/coding-agents/check.py --out /tmp/agent-check            # all agents
python3 scripts/coding-agents/check.py --out /tmp/agent-check --only codex,aider
```

Options: `--base-url` (default `$KORTTY_AGENT_CHECK_BASE_URL` or the LAN LM Studio), `--model` (default `$KORTTY_AGENT_CHECK_MODEL` or `openai/gpt-oss-20b`). The first run creates `<out>/.venv` with `pyte`; Gradle compiles korTTY to get the real detector.

## What it does per agent

| Step | korTTY code exercised |
| --- | --- |
| Reads the installed version and compares it with `verified_version` in `agents.json` | — |
| Starts the agent below `/bin/sh` in a pty (120×40), with throwaway home/config dirs and an empty working dir, and records every distinct screen | — |
| Asks which agent runs below the shell | `LocalProcessInspector.findAgentProcess` |
| Classifies every screen with the bundled rules | `CodingAgentDetector` + `src/main/resources/coding-agents/<kind>.json` |
| `live` only: sends a one-line prompt followed by Enter, as the panel's prompt box does, and notes whether the agent switched bracketed paste on | prompt box behaviour |
| `live` only: waits for the tool-approval dialog (BLOCKED) and answers it with the agent's **deny** keys, as the panel's quick answers do, then waits for WORKING/IDLE | quick-answer keys |

Nothing is ever approved and no vendor account is used by `live` agents: they talk to an OpenAI-compatible endpoint. `smoke` agents (account-bound, or no account on this machine) are only started and recorded; no prompt is sent.

## Output

`<out>/report.md` (a table) and `<out>/report.json`, plus every frame under `<out>/<agent-id>/frames/NNN-<phase>.txt` (first line `#! alt=<true|false>`, then the screen) and the raw pty output in `raw.bin`. Status per agent:

| Status | Meaning |
| --- | --- |
| `ok` | detected as the expected kind, screens classified, version unchanged |
| `version-changed` | works, but the installed version differs from `verified_version`: re-check the screens and record the new version |
| `problem` | the process was not detected (or as another kind), no screen matched a rule, the deny keys did not leave the dialog, or the check crashed |
| `not-installed` / `skipped` | the agent is not on this machine / the endpoint is unreachable |

Frames that match no rule (fallback state) are listed per agent; they are not a failure on their own (Aider and Goose deliberately fall back to WORKING while they stream), but should be looked at.

## Turning a finding into a rule change

1. Look at the frames named in the report. Adjust `src/main/resources/coding-agents/<kind>.json` (keep patterns anchored, keep the word `re-verified` in the comment) or `CodingAgentKind` / `LocalProcessInspector` for process detection.
2. Add fixtures under `src/test/resources/coding-agents/<kind-id>/`: copy a frame, put `#! expect state=<STATE> rule=<rule-id|none>` on top, blank the working-directory path and user names column-preserving, pad to at least 10 rows.
3. Update `verified_version` in `agents.json`, rerun `check.py --only <id>` and `./gradlew test --tests 'de.kortty.codingagent.*'`.
4. User-facing text changes (the agent list in i18n and the guide) follow `AGENTS.md`.
