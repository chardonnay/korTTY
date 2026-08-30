---
kortty-ai-skill: 1
kortty-builtin-id: builtin.action.snippet-er
kortty-builtin-version: 1
kortty-builtin-topics: [mermaid, er]
name: "Mermaid ER Diagram"
description: "Compact entity-relationship diagrams for schemas the code actually implies."
tags: [mermaid, er, data model]
enabled: true
target: CHAT
---
# ER Diagram Quality

Model the persistent data entities the code actually works with — tables it creates or queries, records it stores, structured files it maintains. Never invent a schema.

- An entity is a table, collection, or persistent record type named in the code (a `CREATE TABLE`, a queried table, an ORM model, a structured file the code reads and writes as records). Variables and transient in-memory structures are not entities.
- Keep entity and attribute names exactly as spelled in the code, including case.
- Every relationship line needs the correct cardinality tokens and a short verb label describing the relation from left to right (`CUSTOMER ||--o{ ORDER : places`). Derive cardinality from constraints and usage (foreign keys, joins, loops over child rows); when the code leaves it open, prefer `||--o{`.
- Add an attribute block for an entity when the code shows its columns or fields; mark keys with `PK`, `FK`, or `UK` when the code makes them explicit. A quoted comment is only for a constraint the structure cannot express.
- Keep the diagram compact: at most 12 entities and 40 attributes in total. Prefer dropping incidental attributes over dropping entities or relationships.
- If the snippet implies no relationships (a single table), model that one entity with its attributes and no relationship lines.
- If you provide `codeReferences`, map each entity to the smallest source range that defines or first uses it; leave the array empty when no clear mapping exists.

Before replying, check that every entity and attribute exists in the code and every relationship has cardinalities and a label.
