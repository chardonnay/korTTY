---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.cpp
kortty-builtin-version: 1
kortty-builtin-topics: [c++, cpp]
name: "C++"
description: "Conventions the assistant applies when writing C++ code: comment style, robustness, pitfalls, and secure patterns."
tags: [c++, cpp, .cpp, raii, cmake]
enabled: true
target: BOTH
---
# C++ Best Practices

When generating or reviewing C++ code or build configuration, apply the rules below.

## C++ Comments

- Comment the why — design intent, ownership decisions, concurrency assumptions — never a restatement of the code.
- Give public classes and functions Doxygen-style doc comments (`///` or `/** */`) covering purpose, parameters, exceptions thrown or errors returned, and thread-safety guarantees.
- Document contracts and invariants: preconditions, postconditions, valid object states, iterator-invalidation rules; back the cheap ones with assertions.
- State ownership and lifetime whenever a raw pointer or reference escapes: who owns it, how long it stays valid, whether null is allowed.
- Mark deliberate deviations (raw `new` for a framework, a cast for a C API) with a one-line justification so reviewers know they are intentional.

## Robust C++ Code

- Manage every resource with RAII — memory, files, locks, sockets: acquire in a constructor, release in a destructor, never a naked acquire/release pair.
- Use `std::unique_ptr` by default and `std::shared_ptr` only for genuine shared ownership; no raw owning pointers.
- Follow the rule of zero (preferred) or the rule of five; never define a partial set of special member functions.
- Choose one error strategy per codebase — exceptions or `std::expected`/status-code style — and apply it consistently; mark functions that cannot fail `noexcept`.
- Validate external input at boundaries; use `.at()` or explicit bounds checks on untrusted indices, and overflow-check size arithmetic before allocations.
- Prefer `std::string`, `std::vector`, `std::array`, and `std::span` over C arrays and raw pointer arithmetic.
- Fail safely: destructors must not throw, and error paths must leave objects in a valid state (strong or basic exception guarantee).

## Avoid in C++

- Never use manual `new`/`delete` in application code; use `make_unique`/`make_shared` and standard containers.
- Never use C-style casts; use `static_cast`, and treat `reinterpret_cast`/`const_cast` as red flags that require written justification.
- Never use macros where `constexpr`, `inline` functions, templates, or `enum class` work.
- Never return references or pointers to locals, or keep iterators and `string_view`s alive past container mutation; copy or re-fetch instead.
- Never put `using namespace std;` in headers; qualify names explicitly.
- Never let warnings or lints accumulate; build with `-Wall -Wextra -Werror` and keep the code clang-tidy clean.

## C++ Security

- Enforce const-correctness throughout; it turns whole classes of mutation bugs into compile errors.
- Run debug and test builds under AddressSanitizer and UBSan; treat any sanitizer report as a bug, not noise.
- Never build shell commands or SQL by concatenating user input; use exec-style argument vectors and parameterized queries.
- Compare secrets in constant time, and zero sensitive buffers with a non-elidable primitive before releasing them.
- Keep secrets out of source, logs, and exception messages; load them from the environment or a secret store at runtime.
- Bounds-check every untrusted index and length before use; prefer checked accessors over unchecked `operator[]` on hostile data.
