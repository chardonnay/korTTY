---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.c
kortty-builtin-version: 1
kortty-builtin-topics: [c, c-language]
name: "C (ISO C)"
description: "Conventions the assistant applies when writing C code: comment style, robustness, pitfalls, and secure patterns."
tags: [c99, c11, malloc, stdio.h, valgrind, gcc]
enabled: true
target: BOTH
---
# C-Language Best Practices

When generating or reviewing C (ISO C) code, headers, or build configuration, apply the rules below.

## C-Language Comments

- Comment to explain why — the invariant, the trade-off, the hardware quirk — never to restate what the statement already says.
- Give every non-trivial function a Doxygen-style header (`/** ... */` with `@param`, `@return`) stating purpose, error semantics, and thread-safety.
- Document ownership and lifetime explicitly: who allocates, who frees, whether a returned pointer aliases an argument, and whether NULL is a valid input.
- Record contracts and invariants where they matter: valid ranges, required locking, units, alignment assumptions; mirror the cheap ones with `assert()`.
- Mark intentional fallthrough, truncation, and endianness dependencies with a short comment so reviewers do not "fix" them.

## Robust C-Language Code

- Check every return value — `malloc`, `calloc`, `fopen`, `fread`, `snprintf`, `close`, `fclose` — and handle failure explicitly.
- Bounds-check every buffer write; pass the destination size and compare `snprintf`'s return value against it to detect truncation.
- Use the goto-cleanup single-exit pattern: one `cleanup:` label freeing resources in reverse acquisition order on all error paths; initialize pointers to NULL so cleanup is unconditional.
- Validate all external input (arguments, file contents, network bytes) before use; treat lengths and offsets from outside as hostile.
- Parse numbers with `strtol`/`strtoul`, checking `errno` and the end pointer — never `atoi`.
- Overflow-check size arithmetic (`a > SIZE_MAX / b`) before allocating `a * b` bytes.
- Fail safely: return a distinct error status, release partial state, and exit nonzero from `main` on unrecoverable errors.

## Avoid in C-Language

- Never use `gets` (removed in C11); read lines with `fgets` and strip the newline.
- Never use `strcpy`, `strcat`, or `sprintf` on unbounded input; use `snprintf` or length-checked `memcpy`.
- Never pass user data as a format string (`printf(user)`); always write `printf("%s", user)`.
- Never rely on undefined behavior — signed overflow, out-of-bounds pointers, use-after-free, strict-aliasing puns; use `memcpy` for type reinterpretation.
- Never use a pointer after `free` or free it twice; set pointers to NULL immediately after freeing.
- Never ignore compiler warnings; build with `-Wall -Wextra -Werror` and treat a clean build as mandatory.

## C-Language Security

- Enable AddressSanitizer and UBSan (`-fsanitize=address,undefined`) in debug builds and run tests under them or Valgrind.
- Compare secrets (tokens, MACs, password hashes) with a constant-time comparison, never `memcmp` or `strcmp`.
- Never build shell commands from user input via `system`/`popen`; use the `execv` family with an argument vector and no shell.
- Zero sensitive buffers after use with a non-elidable primitive (`explicit_bzero`, `memset_s`), not plain `memset`, which optimizers may drop.
- Create temporary files with `mkstemp`, open untrusted paths with `O_NOFOLLOW`/`O_EXCL` where appropriate, and never trust `PATH` in privileged code.
- Keep secrets out of source, logs, and error messages; read them from the environment or a secret store at runtime.
