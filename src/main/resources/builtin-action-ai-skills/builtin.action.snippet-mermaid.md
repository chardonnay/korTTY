---
kortty-ai-skill: 1
kortty-builtin-id: builtin.action.snippet-mermaid
kortty-builtin-version: 1
kortty-builtin-topics: [mermaid, flowchart]
name: "Mermaid Logical Flowchart"
description: "Compact, connected, source-mapped control-flow diagrams for snippet analysis."
tags: [mermaid, flowchart, control flow]
enabled: true
target: CHAT
---
# Logical Flowchart Quality

Model the program's runtime behavior, not its declaration order, individual statements, or output lines.

- Start at the actual runtime entry point. A function, method, class, import, constant, or helper declaration is not an execution step by itself; represent its behavior where it is called.
- Use as few nodes as the source requires. For a nontrivial snippet, keep at most 12 nonterminal nodes by grouping behavior rather than padding or transcribing statements.
- Group adjacent or repeated retrieval, formatting, validation, and output operations when they serve the same purpose. Never create one node per variable, command, print statement, helper definition, file, item, or time range.
- Keep labels short and behavioral. Preserve every material condition, branch, error path, early exit, and loop outcome visible in the source, but never invent behavior.
- Every declared node must be reachable from `start_1` and able to reach `stop_1`. `start_1` has no incoming edge; `stop_1` has no outgoing edge. Never emit orphan nodes, self-edges, duplicate edges, or backward terminal paths.
- Every decision has exactly two explicit outgoing outcomes labeled only with the localized equivalents of `yes` and `no` in the requested response language. For a multi-way branch, use consecutive binary decisions. Branches either merge into a later action or end through a classified outcome before `stop_1`. Use a back edge only for a real loop and show its exit path. For an intentionally continuous process, map the loop decision to the real loop statement; its negative edge to `stop_1` denotes external termination and needs no invented action node.
- Class `start_1`, `stop_1`, and initialization as `setup`; ordinary actions and decisions as `work`; explicit positive outcomes as `success`; and errors, rejection, abort, or fallback outcomes as `failure`.
- Map every nonterminal node to the smallest source range that supports its complete behavior. A node for grouped repeated calls spans the smallest caller range from the first through the last call; a node that describes helper internals instead maps the helper definition, never both. Keep each `codeReferences` label exactly identical to its node label.

Before replying, check compactness, connectivity, branch completeness, semantic classes, and source mapping.
