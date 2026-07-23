---
kortty-ai-skill: 1
kortty-builtin-id: builtin.markup.xml
kortty-builtin-version: 1
kortty-builtin-topics: [xml]
name: "XML"
description: "Conventions the assistant applies when writing XML documents: comment style, robustness, pitfalls, and secure patterns."
tags: [xml, xsd, xslt, xpath]
enabled: true
target: BOTH
---
# XML Best Practices

When generating or reviewing XML documents, apply the rules below.

## XML Comments

- Comment to explain why — a schema constraint, a consumer quirk, a compatibility decision — never to restate what an element obviously contains.
- Document the governing schema and namespace URIs near the root element so readers know what the document must conform to.
- Never place `--` inside a comment; `<!-- a -- b -->` is not well-formed and breaks strict parsers.
- Never leave commented-out elements in production configuration files; delete them — version control remembers.
- Never put credentials or internal infrastructure notes into comments; comments travel with the document.

## Robust XML Documents

- Start with an XML declaration carrying an explicit encoding (`<?xml version="1.0" encoding="UTF-8"?>`) and actually save the file in that encoding.
- Keep documents well-formed and validate them against their XSD (or schema language in use) before shipping; well-formed is the floor, valid is the bar.
- Declare namespaces explicitly with stable prefixes and use them consistently; unprefixed mixing breaks XPath and downstream tooling.
- Escape the predefined entities (`&lt;`, `&gt;`, `&amp;`, `&quot;`, `&apos;`) in content and attribute values; use CDATA only for genuinely markup-heavy payloads.
- Follow a consistent element-versus-attribute policy: data and repeatable structures in elements, small identifying metadata in attributes.
- Emit XML through a serializer or writer API so escaping, encoding and nesting stay correct under all inputs.

## Avoid in XML

- Never parse or edit XML with regular expressions or string slicing; use a real parser — regex breaks on comments, CDATA and nested structures.
- Never ignore namespaces in XPath queries; register the prefixes and query with them, or matches silently return nothing.
- Never rely on attribute order or insignificant whitespace; no conforming parser preserves them as meaning.
- Never use mixed content (text interleaved with elements) unless the schema genuinely models prose; it makes processing ambiguous.
- Never grow one monolithic document without structure; split along natural boundaries or use includes the toolchain supports.
- Never invent cryptic abbreviated element names; spell terms out — documents are read far more often than written.

## XML Security

- Disable DTD processing and external entity resolution in every parser that touches untrusted input — XXE is the canonical XML attack (e.g. `disallow-doctype-decl`, secure-processing feature, or the platform equivalent).
- Cap entity expansion and document depth to stop billion-laughs style expansion bombs.
- Treat XPath built from user input as an injection vector; use variable bindings or strict allowlist escaping, never string concatenation.
- Validate untrusted documents against a strict schema before processing and reject on failure instead of best-effort parsing.
- Fetch schemas and DTDs from local, pinned copies; resolving schema locations over the network hands an attacker a hook.
- Keep secrets out of attributes, comments and processing instructions; XML config files get copied and logged.
