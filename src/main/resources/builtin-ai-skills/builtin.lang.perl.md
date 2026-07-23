---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.perl
kortty-builtin-version: 1
kortty-builtin-topics: [perl]
name: "Perl (Perl 5)"
description: "Conventions the assistant applies when writing Perl code: comment style, robustness, pitfalls, and secure patterns."
tags: [perl code, cpan, perldoc]
enabled: true
target: BOTH
---
# Perl Best Practices

When generating or reviewing Perl (Perl 5) code or configuration, apply the rules below.

## Perl Comments

- Comment to explain why — a workaround, a compatibility constraint, a non-obvious idiom — never to restate what the code already says.
- Document every public script and module with POD: `NAME`, `SYNOPSIS`, `DESCRIPTION`, and a section per public subroutine covering arguments and return values.
- Document contracts and invariants: list versus scalar context behavior, whether errors `die` or return `undef`, and side effects on globals.
- Write complex regexes with the `/x` modifier and inline comments explaining each part.
- Mark deferred work as `# TODO(owner): reason` so it stays findable.

## Robust Perl Code

- Start every file with `use strict;` and `use warnings;` — no exceptions; they turn whole classes of silent mistakes into diagnostics.
- Open files with three-argument `open` and a lexical filehandle, and check the result: `open my $fh, '<', $path or die "Cannot open $path: $!";`.
- Include `$!` (or `$@` after `eval`) in every error message, and check the return values of `close`, `system`, and `print` where failure matters.
- Report caller-side misuse with `Carp::croak`/`carp` instead of `die`/`warn` so errors point at the caller, not the library internals.
- Validate `@ARGV` and all external input up front; exit with a non-zero status on failure so callers and schedulers see it.
- Wrap failure-prone sections in block `eval { ... }` (or Try::Tiny) and inspect the error immediately afterwards.

## Avoid in Perl

- Never use two-argument `open`; special characters in a filename can select modes or open pipes — always three-argument with an explicit mode.
- Never use bareword filehandles (`FH`); use lexical filehandles (`my $fh`), which close automatically when they go out of scope.
- Never reuse `$_` implicitly across nested loops or callbacks; use explicit named loop variables (`for my $line (...)`).
- Never hand-parse structured formats with ad-hoc regexes; use a CPAN module (JSON::PP, Text::CSV) that already handles quoting and edge cases.
- Never ignore Perl::Critic; keep code clean at its default severity and justify any `## no critic` marker.
- Never call subroutines with the `&` sigil or rely on prototypes; use plain `name(@args)` calls.

## Perl Security

- Enable taint mode (`-T`) for anything setuid, CGI-like, or otherwise fed by untrusted input, and untaint only through explicit regex captures.
- Call `system` and `exec` in list form (`system 'cmd', @args`), never as a single interpolated string that a shell parses.
- Never pass external data to string `eval`; reserve block `eval` for exception handling.
- Use DBI placeholders (`?`) with bound values for all SQL; never interpolate variables into statements.
- Keep secrets in the environment, not source; declare dependencies in a `cpanfile` and pin them with Carton.
