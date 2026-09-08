# SithTermFX vendor patches

korTTY consumes its terminal emulator, **SithTermFX**, as a versioned Maven artifact
(`com.sithtermfx:sithtermfx-{core,ui}`), pinned by `sithtermfxVersion` in `build.gradle.kts`.
The source lives in `vendor/sithtermfx/` — a gitignored `git clone` of the tagged release
(`cloneSithtermfx`) built into `~/.m2` by `installSithtermfxLocal`. So a change to the terminal
renderer is a change to **SithTermFX**, not to korTTY, and must ship as a new SithTermFX release.

This directory holds the SithTermFX source changes a korTTY branch depends on, so the dependency is
reviewable and reproducible even though `vendor/sithtermfx/` itself is not tracked.

Not every change has to wait for a release: `patches/sithtermfx/` carries reviewed patches that
`applySithtermfxPatches` re-applies to the pinned tag on every build, which is where a korTTY-specific
customisation belongs. This directory is for the other kind — a fix that is SithTermFX's own bug and
should stop being a patch at all. A change can be in both while the release is pending; the
`patches/sithtermfx/` copy is then dropped once the tag it fixes has shipped.

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

## `0002-terminal-scroll-region-cursor-clamp.patch` → SithTermFX **1.2.2** (unreleased)

A SithTermFX bug inherited from JediTerm, not a korTTY customisation: `SithTerminal.scrollY()`
enforced the DECSTBM margins on every write, and enforced them by scrolling. But the margins bound
the *scrolling*, not the *addressing* — only origin mode (DECOM) confines the cursor to the region,
and DECOM is off by default. So:

- text addressed above the top margin was pulled down into the region, and
- text addressed below the bottom margin scrolled the region out from under what was on screen.

tmux hits both: it paints the first pane row while the margins are still set to the sub-region it
just scrolled, which put that row one line too low and stranded the cursor on the blank line above
it — korTTY's visible symptom.

The patch splits the two jobs apart. A new `lineFeed()` is the only place that scrolls (on the bottom
margin it scrolls the region; below it the cursor is outside the region and just advances to the last
screen line), shared by `newLine()`, `index()`, `nextLine()` and the autowrap in `wrapLines()`;
`scrollY()` is left as a plain clamp onto an addressable line. Five cases in `ScrollingTest` cover
both directions plus the DECOM and bottom-margin behaviour that must not change.

### Releasing it (SithTermFX repo, `github.com/chardonnay/SithTermFX`)

```sh
git checkout v1.2.1            # base the patch was cut from
git am /path/to/0002-terminal-scroll-region-cursor-clamp.patch
mvn versions:set -DnewVersion=1.2.2 -DgenerateBackupPoms=false
git commit -am "Release 1.2.2"
git tag v1.2.2 && git push origin main --tags
```

Until that tag exists korTTY ships the same fix through
`patches/sithtermfx/1.2.1-terminal-scroll-region-cursor-clamp.patch`, which additionally carries the
`META-INF` marker resource korTTY's build gate needs (upstream has no use for it). After bumping
`sithtermfxVersion` to `1.2.2`, delete that patch, its entries in `sithtermfxPatchFiles` /
`sithtermfxCorePatchMarkers`, and the marker check it feeds — but keep
`src/test/java/com/sithtermfx/core/SithTerminalScrollRegionPatchTest.java`, which pins the behaviour
from korTTY's own suite regardless of where the fix lives.
