---
kortty-ai-skill: 1
kortty-builtin-id: builtin.action.snippet-class
kortty-builtin-version: 1
kortty-builtin-topics: [mermaid, class]
name: "Mermaid Class Diagram"
description: "Compact type-structure diagrams reflecting only declarations present in the code."
tags: [mermaid, class, structure]
enabled: true
target: CHAT
---
# Class Diagram Quality

Model the types the code actually declares and how they relate — never a design the code merely hints at.

- One class per declared class, struct, record, interface, module, or clearly class-like structure (for example a Python class or a Perl package). Do not model functions, scripts, or variables as classes.
- Keep class and member names exactly as spelled in the code, including case. Write generics with tildes (`List~String~`), never angle brackets.
- Include the members that carry the structure: public fields, constructors when they reveal dependencies, and the significant methods. Omit trivial accessors and boilerplate before dropping meaningful members; at most 20 members per class.
- Use the correct relation arrow: `<|--` inheritance, `..|>` interface realization, `*--` composition, `o--` aggregation, `-->` association or field reference, `..>` dependency such as a parameter or local use. Add a `: label` or quoted cardinalities only when they add real information.
- Do not use `<<stereotype>>` annotations; express abstractness or roles in the relation structure or omit them.
- Keep the diagram compact: at most 12 classes. When the code declares more, keep the classes central to the snippet's purpose and the ones they directly relate to.
- If you provide `codeReferences`, map each class to the source range of its declaration; leave the array empty when no clear mapping exists.

Before replying, check that every relation endpoint is a declared or clearly named class and every member really exists in the code.
