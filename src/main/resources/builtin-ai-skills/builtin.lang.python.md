---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.python
kortty-builtin-version: 1
kortty-builtin-topics: [python]
name: "Python"
description: "Conventions the assistant applies when writing Python code: comment style, robustness, pitfalls, and secure patterns."
tags: [python, python3, .py, pytest, venv, django]
enabled: true
target: BOTH
---
# Python Best Practices

When generating or reviewing Python code or configuration, apply the rules below.

## Python Comments

- Comment to explain why — a workaround, a non-obvious algorithm choice, a deliberate trade-off — never to restate what the code already says.
- Write PEP 257 docstrings on every public module, class, and function: a one-line summary first, then parameters, return value, and raised exceptions when non-trivial.
- Treat type hints as documentation: annotate public signatures fully so callers and tools such as mypy can rely on them instead of prose.
- Document contracts and invariants in docstrings: preconditions, thread-safety, which exceptions are part of the API, and who owns passed-in mutable objects.
- Mark deferred work as `# TODO(owner): reason` so it stays findable.

## Robust Python Code

- Catch the most specific exception available and handle it deliberately; chain re-raises with `raise NewError(...) from err` to preserve the cause.
- Validate external input — CLI arguments, environment variables, file and network payloads — at the boundary and fail fast with a clear message.
- Use context managers (`with`) for files, locks, sockets, and database connections so resources are released on every exit path.
- Report diagnostics through the `logging` module with appropriate levels, not `print`.
- Fail safely: on unrecoverable errors log the reason and call `sys.exit()` with a non-zero status instead of continuing with corrupt state.
- Guard entry points with `if __name__ == "__main__":` so modules stay importable and testable.

## Avoid in Python

- Never use mutable default arguments such as `def f(x=[])`; default to `None` and create the object inside the function.
- Never write bare `except:` or `except Exception: pass`; catch specific exceptions and at minimum log them.
- Never build filesystem paths by string concatenation; use `pathlib.Path`.
- Never compare to `None` with `==`; use `is None` and `is not None`.
- Never mutate a list or dict while iterating over it; iterate over a copy or build a new collection.
- Never install dependencies into the system interpreter; use a virtual environment with pinned versions in a requirements or lock file.

## Python Security

- Never pass external data to `eval`, `exec`, or `compile`; parse it with `json`, `ast.literal_eval`, or an explicit parser.
- Run subprocesses with an argument list and `shell=False` whenever any input is untrusted; never interpolate user data into a shell command string.
- Load YAML with `yaml.safe_load`, never `yaml.load`, and never unpickle untrusted data — prefer JSON or another data-only format.
- Generate tokens, salts, and keys with the `secrets` module, never `random`.
- Use parameterized database queries with placeholders; never format user input into SQL strings.
- Keep secrets out of source code — read them from the environment or a secret store — and run `bandit` on security-sensitive code.
