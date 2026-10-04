# SithTermFX vendor patches

korTTY consumes its terminal emulator, **SithTermFX**, as a versioned Maven artifact
(`com.sithtermfx:sithtermfx-{core,ui}`), pinned by `sithtermfxVersion` in `build.gradle.kts`.
The source lives in `vendor/sithtermfx/` — a gitignored `git clone` of the tagged release
(`cloneSithtermfx`) built into `~/.m2` by `installSithtermfxLocal`. So a change to the terminal
renderer is a change to **SithTermFX**, not to korTTY, and must ship as a new SithTermFX release.

This directory holds the SithTermFX source changes a korTTY branch depends on, so the dependency is
reviewable and reproducible even though `vendor/sithtermfx/` itself is not tracked.

Since SithTermFX 1.2.3 korTTY builds the released tag as is and carries no build-time patches: the
`patches/sithtermfx/` directory, the `applySithtermfxPatches` task, the CI "apply pinned patches"
steps and the `META-INF` marker checks were removed when every patch they carried shipped upstream.
A fix that cannot wait for a release would bring that mechanism back; the preferred route is a new
SithTermFX release, recorded here as a numbered patch.

Every entry below has shipped. korTTY keeps a pinning test for each, so a later SithTermFX upgrade
that regresses one fails korTTY's own suite.

## `0001-terminal-background-transparency.patch` → SithTermFX **1.2.1** (released)

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

Released as `v1.2.1`; every later tag korTTY pins contains it.

## `0002-terminal-scroll-region-cursor-clamp.patch` → SithTermFX **1.2.2** (released)

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

## `0003-control-sequence-push-back-bounds.patch` → SithTermFX **1.2.3** (released)

Tracked upstream as [chardonnay/SithTermFX#5](https://github.com/chardonnay/SithTermFX/issues/5),
closed by the 1.2.3 release.

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

Until 1.2.3 shipped, korTTY carried the same change as a build-time patch on 1.2.2, with a
`META-INF` marker in the core jar; both are gone now that korTTY is on 1.2.3. Two korTTY tests pin
the behaviour regardless of where the fix lives: `ControlSequenceBoundsPatchTest` (the push-back itself
and the emulator) and `HeadlessTerminalTest.anOverlongControlSequenceDoesNotStopTheEmulator`.
`OscEventSplitter` replays a CSI's stray chars the way SithTermFX does, so its `CSI_PUSH_BACK_LENGTH`
budget (1024 minus `ESC [`, the marker and the final char) must follow any change to this limit;
`OscSplitterDifferentialTest` checks it at the boundary against the real emulator.

Not in this patch, still open upstream after 1.2.3: an unterminated CSI still grows `myArgv`
(one `int` per parameter) and the raw `mySequenceString` debug copy without bound, and a parameter
value silently overflows `int` (xterm caps both the number of parameters and each value). Neither
throws; both only cost memory or produce a wrong parameter.

### Released in 1.2.3

SithTermFX 1.2.3 contains this fix line for line, together with the two other changes korTTY used
to patch in on 1.2.2 (the bottom-row hyperlink boundary and the Cmd-shortcut `KEY_TYPED` chord,
pinned by `TerminalPanelBoundaryPatchTest` and `TerminalPanelShortcutKeyTypedPatchTest`). korTTY is
on `sithtermfxVersion = "1.2.3"`: the clone tag in `.github/workflows/test.yml` and
`build-release.yml` follows it, and `patches/sithtermfx/`, `applySithtermfxPatches` and the
`sithtermfxPatchMarkers` checks are deleted. The patch file is kept here as the record of what 1.2.3
contains.
