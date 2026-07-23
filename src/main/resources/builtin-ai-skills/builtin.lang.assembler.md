---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.assembler
kortty-builtin-version: 1
kortty-builtin-topics: [assembler, assembly, asm]
name: "Assembler (Assembly)"
description: "Conventions the assistant applies when writing Assembly code: comment style, robustness, pitfalls, and secure patterns."
tags: [assembler, assembly, nasm, x86, arm64]
enabled: true
target: BOTH
---
# Assembly Best Practices

When generating or reviewing Assembler (Assembly) code — NASM or GAS on x86-64, ARM64, or similar targets — apply the rules below.

## Assembly Comments

- Comment every instruction block with intent — what the sequence achieves — not per-instruction transliteration ("increment rax").
- Begin every routine with a header block: purpose, inputs (registers and stack slots), outputs, clobbered registers, and flag effects.
- Maintain a register allocation map in comments for any routine longer than a screen: which register holds which logical variable, and where roles change.
- Document contracts and invariants: expected stack alignment on entry, operand-range assumptions, aliasing rules, direction-flag state.
- Note every deviation from the platform ABI explicitly so callers are warned.

## Robust Assembly Code

- Respect the platform calling convention exactly: argument and return registers, and callee-saved registers (`rbx`, `rbp`, `r12`–`r15` on x86-64 SysV; `x19`–`x28` on AArch64) — preserve any you touch.
- Maintain stack alignment: on x86-64 SysV the stack must be 16-byte aligned before every `call`; account for the pushed return address.
- Bounds-check before every indexed or computed memory access; validate externally supplied lengths and indices before use.
- Check error returns from every syscall or external call (negative `rax` on Linux) and branch to a defined error path.
- Fail safely: on error, restore saved registers, unwind the stack correctly, and return a documented error code — never jump out of a frame you have not cleaned up.
- Prefer assembler-time constants (`equ`, `%define`, `.equ`) and computed lengths over literal numbers.

## Avoid in Assembly

- Never hardcode magic numbers for sizes, offsets, or syscall numbers; define named constants and derive structure offsets in one place.
- Never write self-modifying code; keep code read-only and place mutable state in data sections.
- Never mix ABI assumptions (SysV versus Microsoft x64 argument registers); target one declared ABI per file and state it in the file header.
- Never assume a register survives a call unless the ABI guarantees it; treat caller-saved registers as destroyed.
- Never leave the stack unbalanced: every push must be matched by a pop or an explicit stack-pointer adjustment on all exit paths.
- Never separate a flag-setting instruction from its dependent branch without a comment; an inserted instruction silently breaks the pair.

## Assembly Security

- Never leave secrets in registers or stack slots after use; zero them explicitly (`xor` the registers, overwrite the stack) before returning.
- Enforce W^X: no section both writable and executable; mark the stack non-executable (`.note.GNU-stack` on ELF) and keep data out of code sections.
- Bounds-check all untrusted input before it reaches indexed addressing, and overflow-check length arithmetic before using it in address computation.
- Make secret-dependent code constant-time: no branches or memory lookups indexed by secret data.
- Avoid spilling key material to caller-observable memory; prefer register-only handling where feasible.
- Preserve the toolchain's hardening conventions — frame pointers and CFI directives — so unwinding and exploit mitigations keep working.
