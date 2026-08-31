---
kortty-ai-skill: 1
kortty-builtin-id: builtin.action.snippet-sequence
kortty-builtin-version: 1
kortty-builtin-topics: [mermaid, sequence]
name: "Mermaid Sequence Diagram"
description: "Compact, participant-declared runtime-interaction diagrams for snippet analysis."
tags: [mermaid, sequence, interactions]
enabled: true
target: CHAT
---
# Sequence Diagram Quality

Model who talks to whom at runtime, not the code's statement order.

- Choose participants that are real interacting parties: the script or program itself, remote hosts, external commands and services, APIs, databases, files treated as endpoints, and the user. Never make a variable, branch, or helper function a participant.
- Declare every participant before the first message and give it a short display name via `as`. Keep participant ids stable and descriptive.
- A message is one meaningful request or action from one participant to another; use `->>` for calls and requests, `-->>` for replies and results. Every message label states what is transferred or requested, briefly.
- Group repeated or looped calls with a single `loop` block instead of repeating messages; use `alt`/`else` for a real branching outcome and `opt` for a genuinely optional interaction. Close every block with `end`.
- Use a `note` only for a fact the message flow cannot express, such as a retry policy or an important precondition.
- Keep the diagram compact: at most 12 participants and 60 messages. If the snippet has fewer than two real interacting parties, model the script and its dominant counterpart (for example the file system or the shell) rather than inventing parties.
- If you provide `codeReferences`, map each participant to the smallest source range where it is introduced or first used; leave the array empty when no clear mapping exists.

Before replying, check that every message uses declared participants, blocks are balanced, and labels are in the requested language.
