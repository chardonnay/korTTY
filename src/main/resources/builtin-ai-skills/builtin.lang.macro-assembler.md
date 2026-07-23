---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.macro-assembler
kortty-builtin-version: 1
kortty-builtin-topics: [macro-assembler, masm, hlasm, makroassembler]
name: "Macro Assembler"
description: "Conventions the assistant applies when writing Macro Assembler code: comment style, robustness, pitfalls, and secure patterns."
tags: [masm, hlasm, macroassembler, makroassembler]
enabled: true
target: BOTH
---
# MASM/HLASM Best Practices

When generating or reviewing Macro Assembler code (MASM on x86/x64, HLASM on z/OS), apply the rules below.

## MASM/HLASM Comments

- Comment blocks by intent — what the sequence accomplishes — not instruction-by-instruction transliteration.
- Start every macro with a header block documenting parameters, expansion side effects, registers used or destroyed, and symbols defined.
- Start every routine (PROC or CSECT) with a header stating purpose, entry and exit register contents, save-area usage, and return codes.
- Document contracts and invariants: addressability assumptions, AMODE/RMODE requirements, reentrancy status, condition-code effects.
- Mark each conditional-assembly branch with a comment naming the build variant that selects it.

## Robust MASM/HLASM Code

- Macro hygiene: declare LOCAL labels in every macro (MASM `LOCAL`; HLASM `&SYSNDX`-suffixed names with `LCLA`/`LCLB`/`LCLC` variables) so repeated expansion never collides.
- Validate macro parameters at expansion time; reject bad operands with an assembly-time diagnostic (`.ERR`, `MNOTE` with severity) rather than emitting broken code.
- Use conditional assembly (IF/ELSE, AIF/AGO) for build variants instead of copy-pasted near-duplicate code paths.
- HLASM: keep strict USING/DROP discipline — establish addressability explicitly and DROP promptly so stale bases fail at assembly, not at runtime.
- HLASM: write reentrant code — no self-modifying storage; map working storage with DSECTs, acquire it per invocation via GETMAIN or STORAGE, with standard save-area chaining on entry and exit.
- MASM: pair every PROC/ENDP; know the active `.model`, calling convention, and PROLOGUE/EPILOGUE generation before touching the stack.
- Check the return code of every system service or external call; branch to a defined error exit that restores registers and frees storage.

## Avoid in MASM/HLASM

- Never nest macros so deep that the expansion becomes unreviewable; refactor into smaller macros or plain routines instead.
- Never define fixed global labels inside a macro body; generate names with LOCAL or `&SYSNDX` so multiple expansions assemble.
- Never copy-paste variant code blocks; drive differences through conditional assembly with a documented switch variable.
- Never leave a USING in effect past the storage it covers; DROP it so stale addressability cannot resolve silently wrong.
- Never store state in the code CSECT or modify instructions at runtime; use dynamically acquired, DSECT-mapped working storage.
- Never assume register contents survive a macro invocation; preserve and document them per the header contract.

## MASM/HLASM Security

- Zero sensitive data — keys, passwords, tokens — in working storage before FREEMAIN/release, and clear registers that held it.
- Validate externally supplied lengths and addresses before use in MVC, MVCL, or indexed addressing to prevent storage overlays.
- Treat EX/EXRL with attacker-influenced length or target as security-critical; validate the executed operand first.
- Keep code read-only and reentrant; never create writable-and-executable storage or self-modifying paths that defeat storage protection.
- Never embed credentials in source, literals, or listings; obtain them at runtime from the platform's secure facility and keep them out of dumps.
- Follow the platform's save-area and linkage conventions exactly; a corrupted save-area chain is both a stability and exploitation risk.
