package com.sithtermfx.ui.split;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Closing a pane used to move the split pane's focused widget to the first remaining pane even when
 * the closed pane was not the focused one, for example when a background pane's session ended or
 * the Control API closed it. Keyboard focus stays where it was in that case, so Edit &gt; Find,
 * Copy/Paste and the AI actions went to the first pane while the typing went to the focused one,
 * and the split pane disagreed with {@code TerminalView.getFocusedWidget()}.
 *
 * <p>Toolkit-free: {@code paneFocusedAfterClose} is pure, so plain objects stand in for the panes.
 */
public class TerminalSplitPaneCloseFocusTest {

    private final Object first = new Object();
    private final Object second = new Object();
    private final Object third = new Object();

    @Test
    public void closingAnotherPaneKeepsTheFocusedPane() {
        assertThat(TerminalSplitPane.paneFocusedAfterClose(third, second, List.of(first, third)))
            .isSameInstanceAs(third);
    }

    @Test
    public void closingTheFocusedPaneFallsBackToTheFirstRemainingPane() {
        assertThat(TerminalSplitPane.paneFocusedAfterClose(second, second, List.of(first, third)))
            .isSameInstanceAs(first);
    }

    @Test
    public void aFocusedPaneThatIsNoLongerInTheTreeFallsBackToTheFirstRemainingPane() {
        Object stale = new Object();
        assertThat(TerminalSplitPane.paneFocusedAfterClose(stale, second, List.of(first, third)))
            .isSameInstanceAs(first);
        assertThat(TerminalSplitPane.paneFocusedAfterClose(null, second, List.of(first, third)))
            .isSameInstanceAs(first);
    }

    @Test
    public void closingTheLastPaneLeavesNoFocusedPane() {
        assertThat(TerminalSplitPane.paneFocusedAfterClose(first, first, List.of())).isNull();
    }

    @Test
    public void survivalIsDecidedByIdentityNotEquality() {
        Object equalToFocused = new EqualToEverything();
        Object focused = new EqualToEverything();
        assertThat(TerminalSplitPane.paneFocusedAfterClose(focused, second, List.of(equalToFocused, first)))
            .isSameInstanceAs(equalToFocused);
    }

    /** Equal to every object, so only an identity check can tell two instances apart. */
    private static final class EqualToEverything {
        @Override
        public boolean equals(Object other) {
            return other != null;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }
}
