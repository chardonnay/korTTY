# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.17.0

### Terminal

- **A misplaced scrolling region no longer confines the cursor** — DECSTBM sets the region that scrolls, not the region the cursor may be addressed in, but every write clamped the cursor to the margins by scrolling anyway: text addressed above the top margin was pulled into the region, and text addressed below the bottom margin scrolled the region out from under what was already on screen. tmux triggers the first case while it paints a pane with the margins still narrowed, which showed the cursor one line above the prompt. Scrolling now happens only on an explicit line feed, so the cursor can address any line inside the terminal without moving what is already there.

### Tools

- **AI pictures in the ASCII Art tool now match their subject and arrive in seconds instead of minutes** — the AI Picture tab asked the model to type an aligned character picture, which models do badly and slowly: thinking models spent their whole budget reasoning about it, the reply had no output cap, a cut-off answer looked like an empty one, and every request first paid for a knowledge-store search. The model now returns a small vector drawing that korTTY rasterises and converts to ASCII itself, reasoning is switched off and the answer capped for this action, and knowledge stores are skipped. A cut-off drawing is shown as far as it arrived, and an unusable answer gets one second request naming the defect and then a direct-typing fallback. The tab gained a **Cancel** button, a **Size** dropdown from 40 × 20 to 100 × 50 characters, and step-by-step status texts. Rejected answers are kept under `ai-answers/` for diagnosis. See [ASCII art](../features/ascii-art.md#ai-picture-tab).
- **ASCII art from an existing image** — the new **Image File** tab of the ASCII Art tool converts a photo, logo or icon on your machine into characters through the same converter the AI pictures use: open a PNG, JPEG, GIF or BMP or drop it onto the tab, pick the size, adjust brightness and contrast, and invert a light subject on a dark background. The tonal range is stretched automatically so mid-tone photographs do not turn into one grey block. See [Image File tab](../features/ascii-art.md#image-file-tab).
- **Copy ASCII art straight into code** — the new **Copy as** row in the ASCII Art tool prefixes every line with the comment marker of your language, or wraps the picture in a block comment or a JSON string array. It can also turn each line into a ready print statement for Bash, Perl, Python, Ruby, PHP, JavaScript, Java, C, C#, Go, Rust, Kotlin, Swift, Lua, PowerShell or Batch, with the quoting and escaping that language needs. A **Gap** setting aligns the picture. See [Copying to clipboard](../features/ascii-art.md#copying-to-clipboard).
- **The ASCII Art tool, the guide viewer and the journal windows reopen at the size you gave them** — these windows stored their geometry but never recorded the UI font scale the size was measured at, so once any other window had recorded a different scale the stored size counted as stale on every open and only the position came back. Closing them now refreshes that record, so the next open restores both position and size. See [ASCII art](../features/ascii-art.md#dialog-state).

!!! note "Earlier releases"
    Only the current release is listed here, so the guide stays short in every language it is translated into. Every version is on the [GitHub releases page](https://github.com/chardonnay/korTTY/releases); the curated notes for earlier versions are kept in the repository, in `app-docs/release-notes-archive.md` and `app-docs/RELEASE_NOTES.adoc`.
