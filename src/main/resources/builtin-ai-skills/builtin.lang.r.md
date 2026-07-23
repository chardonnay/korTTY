---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.r
kortty-builtin-version: 1
kortty-builtin-topics: [r, rstats]
name: "R (GNU R)"
description: "Conventions the assistant applies when writing R code: comment style, robustness, pitfalls, and secure patterns."
tags: [rstats, tidyverse, ggplot, dplyr, rscript, cran]
enabled: true
target: BOTH
---
# R-Language Best Practices

When generating or reviewing R (GNU R) code or configuration, apply the rules below.

## R-Language Comments

- Comment to explain why — a statistical choice, a data quirk, a deliberate trade-off — never to restate what the code already says.
- Document package functions with roxygen2: `#' @param`, `#' @return`, and `#' @examples`, keeping expected types and shapes explicit.
- Document contracts and invariants: expected classes, how `NA` and zero-length inputs are handled, and any side effects on options, graphics state, or files.
- In analysis scripts, add brief section banners describing the intent of each stage (load, clean, model, report).
- Record the seed and data-version assumptions a result depends on next to the code that sets them.

## Robust R-Language Code

- Validate inputs at function entry with `stopifnot()` or `rlang::abort()` using classed conditions and informative messages that state what was expected and what arrived.
- Handle failures with `tryCatch` on specific condition classes, and register cleanup with `on.exit()` — closing connections, restoring `par` and `options`.
- Prefer vectorized operations over element-wise loops where idiomatic; use `vapply` for type-stable iteration instead of `sapply`, whose return type varies with its input.
- Treat `NA` explicitly: decide `na.rm` behavior deliberately and check `is.na` rather than letting missingness propagate silently.
- Call `set.seed()` before anything stochastic so results are reproducible across sessions and machines.
- In `Rscript` batch jobs, report the error and terminate with `quit(status = 1)` so schedulers see the failure.

## Avoid in R-Language

- Never rely on partial matching of `$` names or abbreviated arguments; use `[[` with exact names and spell arguments out fully.
- Never depend on `stringsAsFactors` defaults; state factor conversion explicitly when reading or constructing data.
- Never let `[` drop dimensions by surprise; pass `drop = FALSE` when subsetting matrices and data frames programmatically.
- Never use `attach()`; use `with()`, `$`, or explicit data-frame references — attached frames mask variables and make name resolution unpredictable.
- Never scatter `library()` calls through reusable code; in scripts prefer explicit `pkg::fun` namespacing, and in packages declare Imports.
- Never abbreviate `TRUE`/`FALSE` as `T`/`F`; the short forms are ordinary variables that can be reassigned.

## R-Language Security

- Never call `eval(parse(text = ...))` on external input; map user choices to functions through an explicit allowlist.
- Never `readRDS()` or `load()` files from untrusted sources; deserialized objects can carry executable payloads — exchange CSV or JSON instead.
- Build SQL with parameterized queries (`DBI::dbBind`, `sqlInterpolate`); never paste user values into statements.
- Invoke external programs with `system2(cmd, args)` vectors; never paste untrusted input into a `system()` string.
- Keep credentials out of scripts and `.Rhistory`; read them from environment variables or a keyring.
- Pin package versions with renv so analyses stay reproducible and dependency advisories stay actionable.
