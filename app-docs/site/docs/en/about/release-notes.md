# Release notes

The full, version-by-version changelog. The version this guide was built for is
shown in the footer.

## v2.2.2

### Critical fix: crash opening Monaco editors

- **Fixed a hard crash (no on-screen error) when opening the Snippet Manager, the
  Snippet editor, or the Settings AI-skill editor in packaged builds**: the
  bundled runtime was missing the `jdk.jsobject` module, so
  `netscape.javascript.JSObject` was unavailable at runtime and the JVM crashed
  in JNI `get_method_id` (`SIGSEGV`). `jdk.jsobject` is now bundled in the
  packaged runtime. This release supersedes v2.2.0 and v2.2.1, whose binaries are
  affected by this crash.

## v2.2.1

### Stability fixes

- **Settings / Snippet Manager crash fixed**: opening **Global Settings** or the
  **Snippet Manager** could abort the app. The embedded Monaco editor's
  JavaScript→Java bridge is now held by a strong reference for the editor's
  lifetime.
- **WebView lifecycle hardening**: Monaco editors are disposed when their dialog
  closes; late timer/load callbacks after close are ignored. The Settings *AI
  Skills* editor loads lazily on first use.

### Master-password login window

- **Full-bleed animated logo** in the standard app design, with the password form
  overlaid in a translucent card.

## v2.2.0

### Terminal engine and hyperlinks

- **SithTermFX 1.2.0** terminal engine (built from source).
- **OSC 8 clickable hyperlinks** — links emitted by programs such as
  `ls --hyperlink` or `eza`, restricted to a safe URI-scheme allowlist.

### Mosh (mosh4j) 2.0.2 upgrade & security hardening

- mosh4j `2.0.0 → 2.0.2` with per-direction replay/freshness protection and
  decompression-bomb limits; release JARs bundled in native builds.
- Bouncy Castle `1.78.1 → 1.84` (fixes CVE-2026-5598 HIGH and CVE-2026-0636
  MODERATE); protobuf-java `4.28.2 → 4.35.1`.

### AI agent panel & activity

- **AI Agent Panel placement**: *At Bottom* (default), *Dock Left*, or *Dock
  Right*, remembered across restarts.
- **Multiple concurrent runs per split** (cap 5), per-run pause/resume, and
  Dashboard / tab status badges (✋ awaiting · ⚡ working · ⏸ paused · ✓ finished).

!!! note
    Older releases are recorded in the repository's `app-docs/RELEASE_NOTES.adoc`
    and will be migrated here in full.
