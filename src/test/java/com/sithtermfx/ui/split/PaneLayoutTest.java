package com.sithtermfx.ui.split;

import javafx.geometry.Orientation;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The picture of a split pane's tree that saving and restoring a split layout work with. Plain
 * strings stand in for the panes; no JavaFX toolkit is started.
 */
public class PaneLayoutTest {

    /** a | (b / c): a on the left, b above c on the right. */
    private static PaneLayout<String> threePanes() {
        return PaneLayout.split(Orientation.HORIZONTAL, 0.3,
            PaneLayout.leaf("a"),
            PaneLayout.split(Orientation.VERTICAL, 0.6, PaneLayout.leaf("b"), PaneLayout.leaf("c")));
    }

    @Test
    public void thePanesComeFromLeftToRightAndTopToBottom() {
        // The order TerminalSplitPane.getAllWidgets() lists them in.
        PaneLayout<String> layout = PaneLayout.split(Orientation.HORIZONTAL, 0.5,
            PaneLayout.split(Orientation.VERTICAL, 0.5, PaneLayout.leaf("a"), PaneLayout.leaf("b")),
            PaneLayout.leaf("c"));

        assertThat(layout.panes()).containsExactly("a", "b", "c").inOrder();
        assertThat(layout.paneCount()).isEqualTo(3);
        assertThat(PaneLayout.leaf("x").panes()).containsExactly("x");
        assertThat(PaneLayout.leaf("x").paneCount()).isEqualTo(1);
    }

    @Test
    public void aLeafIsOnePaneAndABranchTwoChildren() {
        assertThat(PaneLayout.leaf("a").isLeaf()).isTrue();
        assertThat(threePanes().isLeaf()).isFalse();
        assertThat(threePanes().orientation()).isEqualTo(Orientation.HORIZONTAL);
        assertThat(threePanes().divider()).isEqualTo(0.3);

        assertThrows(IllegalArgumentException.class,
            () -> new PaneLayout<>("a", Orientation.HORIZONTAL, 0, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new PaneLayout<String>(null, Orientation.HORIZONTAL, 0.5, PaneLayout.leaf("a"), null));
        assertThrows(IllegalArgumentException.class,
            () -> new PaneLayout<>(null, null, 0.5, PaneLayout.leaf("a"), PaneLayout.leaf("b")));
        assertThrows(NullPointerException.class, () -> PaneLayout.leaf(null));
    }

    @Test
    public void mappingKeepsTheTreeAndTheDividers() {
        PaneLayout<Integer> numbered = threePanes().map(pane -> List.of("a", "b", "c").indexOf(pane));

        assertThat(numbered.panes()).containsExactly(0, 1, 2).inOrder();
        assertThat(numbered.divider()).isEqualTo(0.3);
        assertThat(numbered.second().orientation()).isEqualTo(Orientation.VERTICAL);
        assertThat(numbered.second().divider()).isEqualTo(0.6);
        assertThrows(NullPointerException.class, () -> threePanes().map(pane -> null));
    }

    @Test
    public void theShapeIsThePanesAndTheOrientationsButNotTheDividers() {
        PaneLayout<String> moved = PaneLayout.split(Orientation.HORIZONTAL, 0.8,
            PaneLayout.leaf("a"),
            PaneLayout.split(Orientation.VERTICAL, 0.1, PaneLayout.leaf("b"), PaneLayout.leaf("c")));
        assertThat(threePanes().sameShapeAs(moved)).isTrue();

        PaneLayout<String> turned = PaneLayout.split(Orientation.HORIZONTAL, 0.3,
            PaneLayout.leaf("a"),
            PaneLayout.split(Orientation.HORIZONTAL, 0.6, PaneLayout.leaf("b"), PaneLayout.leaf("c")));
        assertThat(threePanes().sameShapeAs(turned)).isFalse();

        // The same panes in the same order, grouped differently: (a | b) | c is another tree.
        PaneLayout<String> regrouped = PaneLayout.split(Orientation.HORIZONTAL, 0.3,
            PaneLayout.split(Orientation.VERTICAL, 0.6, PaneLayout.leaf("a"), PaneLayout.leaf("b")),
            PaneLayout.leaf("c"));
        assertThat(threePanes().panes()).isEqualTo(regrouped.panes());
        assertThat(threePanes().sameShapeAs(regrouped)).isFalse();

        PaneLayout<String> otherPane = PaneLayout.split(Orientation.HORIZONTAL, 0.3,
            PaneLayout.leaf("a"),
            PaneLayout.split(Orientation.VERTICAL, 0.6, PaneLayout.leaf("b"), PaneLayout.leaf("d")));
        assertThat(threePanes().sameShapeAs(otherPane)).isFalse();
        assertThat(threePanes().sameShapeAs(null)).isFalse();
        assertThat(PaneLayout.leaf("a").sameShapeAs(threePanes())).isFalse();
    }
}
