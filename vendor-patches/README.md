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

## `0002-terminal-scroll-region-cursor-clamp.patch` → SithTermFX **1.2.2**

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

Released on 2026-09-09 and korTTY is on `sithtermfxVersion = "1.2.2"`, so this fix is no longer a
patch: `patches/sithtermfx/1.2.1-terminal-scroll-region-cursor-clamp.patch` and its `META-INF` marker
are gone, and the two surviving patches were renamed to the `1.2.2-` base they now apply to.
`src/test/java/com/sithtermfx/core/SithTerminalScrollRegionPatchTest.java` stays: it pins the
behaviour from korTTY's own suite regardless of where the fix lives, and would catch a downgrade or a
regression in a later SithTermFX release. The patch file is kept here as the record of what 1.2.2
contains.

## `0003-control-sequence-push-back-bounds.patch` → SithTermFX **1.2.3** (pending)

Tracked upstream as [chardonnay/SithTermFX#5](https://github.com/chardonnay/SithTermFX/issues/5).

A SithTermFX bug inherited from JediTerm, and a remote-triggerable one: the chars a CSI cannot place
(an intermediate byte, a control character, a misplaced `?` or `:`) are pushed back to be read again
ahead of the sequence, and `ControlSequence.pushBackReordered()` copied them, plus `ESC [`, the
marker, the parameters and the final char, into a fixed 1024-char array without a bounds check. A
sequence is as long as the sender makes it, since `readControlSequence()` reads across buffer
refills. More than about a thousand stray chars, or one stray char beside a few hundred parameters,
threw `ArrayIndexOutOfBoundsException` on the emulator thread; `SithTermFxWidget.EmulatorTask` then
closed the `TtyConnector` and the session ended. A malicious server, a `cat`-ed file or a log line
was enough.

The patch caps the copy. `ESC`, `[`, the marker and the final char always keep their room, so the
sequence is pushed back terminated and cannot swallow the output after it; stray chars fill the rest
first, those that do not fit are dropped, and so are the parameters from the first one that does not
fit, whole, as xterm drops the parameters beyond its limit. `addUnhandled()` stops collecting beyond
what can be pushed back. A sequence that fits is pushed back exactly as before. The patch adds
`ControlSequenceTest` (five cases, two of which fail without the fix).

Until 1.2.3 ships, korTTY carries the same change as
`patches/sithtermfx/1.2.2-control-sequence-bounds.patch`, with a `META-INF` marker in the **core** jar
(`sithtermfxPatchMarkers` now names the artifact each marker lives in). Two korTTY tests pin the
behaviour regardless of where the fix lives: `ControlSequenceBoundsPatchTest` (the push-back itself
and the emulator) and `HeadlessTerminalTest.anOverlongControlSequenceDoesNotStopTheEmulator`.
`OscEventSplitter` replays a CSI's stray chars the way SithTermFX does, so its `CSI_PUSH_BACK_LENGTH`
budget (1024 minus `ESC [`, the marker and the final char) must follow any change to this limit;
`OscSplitterDifferentialTest` checks it at the boundary against the real emulator.

Not in this patch, worth doing upstream in the same release: an unterminated CSI still grows `myArgv`
(one `int` per parameter) and the raw `mySequenceString` debug copy without bound, and a parameter
value silently overflows `int` (xterm caps both the number of parameters and each value). Neither
throws; both only cost memory or produce a wrong parameter.

### Releasing it (SithTermFX repo, `github.com/chardonnay/SithTermFX`)

```sh
git checkout v1.2.2            # base the patch was cut from
git am /path/to/0003-control-sequence-push-back-bounds.patch
mvn versions:set -DnewVersion=1.2.3 -DgenerateBackupPoms=false
git commit -am "Release 1.2.3"
git tag v1.2.3 && git push origin main --tags
```

Then move korTTY to 1.2.3: bump `sithtermfxVersion` and the two `com.sithtermfx` dependencies in
`build.gradle.kts` and the clone tag in `.github/workflows/test.yml` and `build-release.yml`; delete
`patches/sithtermfx/1.2.2-control-sequence-bounds.patch` and its `sithtermfxPatchMarkers` entry;
rename the surviving patches to the `1.2.3-` base.
