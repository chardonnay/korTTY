---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.sql
kortty-builtin-version: 1
kortty-builtin-topics: [sql]
name: "SQL"
description: "Conventions the assistant applies when writing SQL code: comment style, robustness, pitfalls, and secure patterns."
tags: [sql, plsql, t-sql, tsql]
enabled: true
target: BOTH
---
# SQL Best Practices

When generating or reviewing SQL code or configuration, apply the rules below.

## SQL Comments

- Head every non-trivial query, view, and procedure with a comment stating the business question it answers.
- Comment every non-obvious predicate: magic status codes, date-window logic, deliberate edge-row inclusion or exclusion.
- Document object contracts in the schema (`COMMENT ON` where supported): table grain, units, what NULL means in each column, and uniqueness assumptions.
- Explain performance-motivated shapes — hints, redundant predicates for partition pruning, forced join order — so later editors do not "simplify" them away.
- Head migration scripts with purpose, ticket reference, and rollback notes.

## Robust SQL Code

- Wrap multi-statement changes in explicit transactions with error handling and rollback (TRY/CATCH in T-SQL, EXCEPTION blocks in PL/SQL and PL/pgSQL); never leave a partial write committed.
- List columns explicitly in every SELECT and INSERT so schema changes cannot silently shift results.
- Respect three-valued logic: NULL comparisons yield unknown — test with `IS [NOT] NULL`, prefer `NOT EXISTS` over `NOT IN` when the subquery can return NULLs.
- Keep predicates sargable and index the columns they filter on: compare bare columns, moving computation to the literal side.
- Enforce validity in the schema: NOT NULL, CHECK, UNIQUE, and FOREIGN KEY constraints are the database's boundary validation.
- Make rerunnable writes idempotent (MERGE, or the dialect's INSERT-on-conflict form) and verify affected-row counts after DML.
- Test queries on representative data volumes and inspect the actual execution plan; small development tables hide full scans.

## Avoid in SQL

- Never use `SELECT *` in production queries, views, or inserts; name the columns explicitly.
- Never join with comma-separated tables in FROM; write explicit `JOIN ... ON` so join conditions cannot be forgotten.
- Never wrap an indexed column in a function inside WHERE; rewrite the predicate or add a computed/function-based index.
- Never use `NOT IN` against a nullable subquery; use `NOT EXISTS`.
- Never rely on implicit type conversion in predicates; cast the literal, not the column.
- Never depend on row order without ORDER BY; add it wherever order matters.
- Never run UPDATE or DELETE without first verifying the WHERE clause with a matching SELECT.

## SQL Security

- Use parameterized queries and prepared statements for every value reaching SQL; never string-concatenate user input into a statement — this is the injection canon.
- In dynamic SQL, bind values (`sp_executesql`, `EXECUTE ... USING`) and allowlist identifiers, quoting them with `QUOTENAME` or `quote_ident` since names cannot be bound.
- Run applications on least-privilege accounts: separate migration and runtime roles; never connect as superuser, dbo, or sysdba by default.
- Keep credentials out of SQL scripts and committed connection strings; load them from a secret manager.
- Return generic errors to end users and log detail server-side; raw database errors leak schema information.
- Expose sensitive tables through roles and views rather than direct grants; mask or exclude PII columns the caller does not need.
