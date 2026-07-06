# SithTermFX vendor patches

korTTY consumes its terminal emulator, **SithTermFX**, as a versioned Maven artifact
(`com.sithtermfx:sithtermfx-{core,ui}`), pinned by `sithtermfxVersion` in `build.gradle.kts`.
The source lives in `vendor/sithtermfx/` — a gitignored `git clone` of the tagged release
(`cloneSithtermfx`) built into `~/.m2` by `installSithtermfxLocal`. So a change to the terminal
renderer is a change to **SithTermFX**, not to korTTY, and must ship as a new SithTermFX release.

This directory holds the SithTermFX source changes a korTTY branch depends on, so the dependency is
reviewable and reproducible even though `vendor/sithtermfx/` itself is not tracked.

## `0001-terminal-background-transparency.patch` → SithTermFX **1.2.1**

Needed by the `feature/terminal-background-transparency` branch (see the Ansicht → Zoom
"Hintergrund-Transparenz" slider). It:

- adds `TerminalColor.rgba(r, g, b, a)` so a background colour can carry an alpha channel, and
- makes `TerminalPanel.doRepaint()` clear the canvas before the window-background fill, and skip the
  redundant per-cell / margin fills when that background is translucent — so a see-through terminal
  background is painted exactly once instead of accumulating alpha frame-over-frame.

Opaque rendering is byte-for-byte unchanged.

### Releasing it (SithTermFX repo, `github.com/chardonnay/SithTermFX`)

```sh
git checkout v1.2.0            # base the patch was cut from
git am /path/to/0001-terminal-background-transparency.patch
mvn versions:set -DnewVersion=1.2.1 -DgenerateBackupPoms=false
git commit -am "Release 1.2.1"
git tag v1.2.1 && git push origin main --tags
```

After the `v1.2.1` tag is pushed, korTTY's `cloneSithtermfx` fetches it automatically on fresh
checkouts and in CI. Until then the branch builds only where `vendor/sithtermfx/` is already at the
local `v1.2.1` (source change + pom bump) and the `1.2.1` jars are in `~/.m2` — which is the current
state on this machine.
