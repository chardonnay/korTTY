---
kortty-ai-skill: 1
kortty-builtin-id: builtin.action.snippet-state
kortty-builtin-version: 1
kortty-builtin-topics: [mermaid, state]
name: "Mermaid State Diagram"
description: "Compact, flat state-and-transition diagrams for snippet analysis."
tags: [mermaid, state, lifecycle]
enabled: true
target: CHAT
---
# State Diagram Quality

Model the observable states the program or the thing it manages passes through, not the code's statements.

- A state is a stable, nameable condition: idle, connecting, connected, retrying, processing, failed, done. A single assignment, print, or call is not a state.
- Start with exactly one `[*] --> first_state` transition and let terminal outcomes flow to `[*]`. Every state must be reachable from the initial transition.
- Label a transition with the event or condition that causes it (`: connection lost`, `: retries exhausted`) whenever the trigger is not obvious.
- Model error and retry paths explicitly when the code has them: a failure state with a transition back for a retry loop, and a distinct terminal failure when retries run out.
- Use `state "Display name" as state_id` when a readable name needs spaces; use `state_id : description` for a short clarification.
- Keep the diagram flat and compact: at most 12 states, no composite states, no concurrency. Merge micro-states that the code never distinguishes observably.
- If you provide `codeReferences`, map each state to the smallest source range that establishes or handles it; leave the array empty when no clear mapping exists.

Before replying, check that the initial transition exists, transitions only use declared or clearly named states, and labels are in the requested language.
