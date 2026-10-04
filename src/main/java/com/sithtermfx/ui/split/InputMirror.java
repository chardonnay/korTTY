package com.sithtermfx.ui.split;

import com.sithtermfx.ui.SithTermFxWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Mirrors the keys typed in a pane into panes of other tabs and windows as well, beside a tab's own
 * broadcast mode: korTTY's multi-exec. A {@link TerminalSplitPane} asks its mirror ({@link
 * TerminalSplitPane#setInputMirror}) at every key typed in one of its panes, on the FX thread.
 *
 * <p>Only a member's keys are mirrored, and only into the other members. Each target still has to
 * pass the guard of the split pane that holds it (so a pane that is pacing a paste or that an AI
 * agent drives gets nothing, whichever tab the key comes from) and the key rule of the source's
 * split pane, and gets each navigation key encoded by its own split pane ({@link
 * TerminalSplitPane#encodeKeyFor}). Paste, snippets and input-method text are never mirrored.
 */
public interface InputMirror {

    /** Whether the keys typed in {@code widget} are mirrored, and whether it gets mirrored keys. */
    boolean isMember(@NotNull SithTermFxWidget widget);

    /**
     * The members besides {@code widget}, in a stable order; mirrored keys reach them in this order.
     */
    @NotNull List<SithTermFxWidget> otherMembers(@NotNull SithTermFxWidget widget);

    /**
     * The split pane that holds {@code widget}, whose guard and key encoding apply to it, or
     * {@code null} when the mirror does not know the pane; such a pane gets no mirrored keys.
     */
    @Nullable TerminalSplitPane ownerOf(@NotNull SithTermFxWidget widget);
}
