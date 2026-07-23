---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.php
kortty-builtin-version: 1
kortty-builtin-topics: [php]
name: "PHP"
description: "Conventions the assistant applies when writing PHP code: comment style, robustness, pitfalls, and secure patterns."
tags: [php, .php, laravel, phpunit, composer]
enabled: true
target: BOTH
---
# PHP Best Practices

When generating or reviewing PHP code or configuration, apply the rules below.

## PHP Comments

- Comment to explain why — a workaround, a framework constraint, a non-obvious decision — never to restate what the code already says.
- Document every public class, method, and function with PHPDoc: a one-line summary plus `@param`, `@return`, and `@throws` tags that agree with the native type declarations.
- Document contracts and invariants: nullability, accepted value ranges, side effects, and which exceptions form the API.
- Note character-encoding and timezone assumptions where they matter; both are classic silent-breakage points.
- Mark deferred work as `// TODO(owner): reason` so it stays findable.

## Robust PHP Code

- Start every file with `declare(strict_types=1);` and use native parameter, return, and property types throughout, so type errors fail loudly instead of coercing silently.
- Signal failure with exceptions, not error codes or `false` returns; catch specific exception types and use `finally` for cleanup that must run on every exit path.
- Validate external input at the boundary with `filter_var`/`filter_input` (e.g. `FILTER_VALIDATE_EMAIL`, `FILTER_VALIDATE_INT`) before using it.
- Check the outcome of I/O and decoding: use `json_decode` with `JSON_THROW_ON_ERROR` — without the flag, invalid JSON yields `null`, indistinguishable from a legitimate `null` — and test file operations for failure instead of assuming success.
- In CLI scripts, exit with a non-zero status on failure so schedulers and pipelines detect it, and log through a PSR-3 logger, not `echo`.

## Avoid in PHP

- Never compare with `==` where type juggling can bite; use `===` and `!==` so coercion cannot change the result.
- Never suppress errors with the `@` operator; handle the failure or let it surface — suppressed failures resurface later as unrelated symptoms.
- Never call `extract()` on request data or use variable variables; read named keys explicitly.
- Never trust `$_GET`, `$_POST`, `$_COOKIE`, or uploaded filenames directly; validate and normalize them first.
- Never edit files under `vendor/`; change dependencies through Composer and commit `composer.lock`.
- Never expose stack traces or raw exception messages to end users; log the details and show a generic error.

## PHP Security

- Use PDO (or mysqli) prepared statements with bound parameters for all SQL; never concatenate user input into query strings.
- Hash passwords only with `password_hash` and check them with `password_verify`; never md5, sha1, or home-grown schemes.
- Escape all dynamic output with `htmlspecialchars($v, ENT_QUOTES, 'UTF-8')` — or the template engine's auto-escaping — to prevent XSS.
- Never `include`/`require` a path derived from user input (LFI/RFI); resolve pages through a fixed allowlist.
- Never `unserialize` untrusted data — crafted payloads can instantiate arbitrary objects (PHP object injection); exchange data as JSON instead.
- Keep secrets in the environment, not code; enable full `error_reporting` in development but keep `display_errors` off in production.
