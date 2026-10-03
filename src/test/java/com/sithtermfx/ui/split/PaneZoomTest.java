package com.sithtermfx.ui.split;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The zoom of a split pane on a pure layout model: a tree of split controls with their items and
 * divider positions, and the split pane's children as the host. Zooming swaps the pane for a
 * placeholder and makes it the host's only child; showing the panes again puts every node back and
 * every divider where it was, also after the split controls reset them meanwhile, as a JavaFX
 * {@code SplitPane} does when its first item is replaced and when a split cell is built. No JavaFX
 * toolkit is started.
 */
public class PaneZoomTest {

    /** A node; two nodes with the same name are equal but never the same pane. */
    private static final class Node {
        final String name;

        Node(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Node node && node.name.equals(name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A split control: its two items and its divider position. */
    private static final class Split {
        final Node node;
        final List<Node> items = new ArrayList<>();
        double[] dividers = {0.5};

        Split(String name, Node first, Node second) {
            node = new Node(name);
            items.add(first);
            items.add(second);
        }
    }

    private static final PaneZoom.Dividers<Split> DIVIDERS = new PaneZoom.Dividers<>() {
        @Override
        public double[] positions(Split split) {
            return split.dividers;
        }

        @Override
        public void setPositions(Split split, double[] positions) {
            split.dividers = positions;
        }
    };

    /**
     * A | B
     *   | --
     *   | C
     */
    private final Node a = new Node("A");
    private final Node b = new Node("B");
    private final Node c = new Node("C");
    private final Node placeholder = new Node("placeholder");

    private Split inner;
    private Split root;
    private List<Node> host;

    private void build() {
        inner = new Split("inner", b, c);
        root = new Split("root", a, inner.node);
        root.dividers = new double[] {0.3};
        inner.dividers = new double[] {0.7};
        host = new ArrayList<>(List.of(root.node));
    }

    @Test
    public void zoomingSwapsThePaneForThePlaceholderAndMakesItTheHostsOnlyChild() {
        build();

        PaneZoom<Node, Split> zoom = PaneZoom.zoom(b, inner.items, host, placeholder, List.of(root, inner), DIVIDERS);

        assertThat(zoom).isNotNull();
        assertThat(zoom.pane()).isSameInstanceAs(b);
        assertThat(host).containsExactly(b);
        assertWithMessage("the pane's place is kept, so nothing is rebuilt")
            .that(inner.items).containsExactly(placeholder, c).inOrder();
        assertThat(root.items).containsExactly(a, inner.node).inOrder();
    }

    @Test
    public void showingThePanesAgainPutsEveryNodeAndEveryDividerBack() {
        build();
        PaneZoom<Node, Split> zoom = PaneZoom.zoom(b, inner.items, host, placeholder, List.of(root, inner), DIVIDERS);
        // Replacing a SplitPane's first item resets its divider, and a layout in between may move both.
        inner.dividers = new double[] {0.5};
        root.dividers = new double[] {0.5};

        zoom.restore();

        assertThat(host).containsExactly(root.node);
        assertThat(inner.items).containsExactly(b, c).inOrder();
        assertThat(root.items).containsExactly(a, inner.node).inOrder();
        assertThat(root.dividers).usingExactEquality().containsExactly(0.3);
        assertThat(inner.dividers).usingExactEquality().containsExactly(0.7);
    }

    @Test
    public void theDividersCanBeWrittenAgainAfterALaterReset() {
        build();
        PaneZoom<Node, Split> zoom = PaneZoom.zoom(a, root.items, host, placeholder, List.of(root, inner), DIVIDERS);
        zoom.restore();
        // A layout pass after the split controls return to the scene, or a split cell's own reset.
        root.dividers = new double[] {0.5};
        inner.dividers = new double[] {0.5};

        zoom.reapplyDividers();

        assertThat(root.dividers).usingExactEquality().containsExactly(0.3);
        assertThat(inner.dividers).usingExactEquality().containsExactly(0.7);
    }

    @Test
    public void theSavedPositionsAreCopies() {
        build();
        PaneZoom<Node, Split> zoom = PaneZoom.zoom(c, inner.items, host, placeholder, List.of(root, inner), DIVIDERS);
        // A control that changes the array it handed out, or the one it was given, changes no saved value.
        inner.dividers[0] = 0.1;
        zoom.restore();
        inner.dividers[0] = 0.2;

        zoom.reapplyDividers();

        assertThat(inner.dividers).usingExactEquality().containsExactly(0.7);
        assertThat(inner.items).containsExactly(b, c).inOrder();
    }

    @Test
    public void whileZoomedTheSavedPositionsAreTheLayoutToReturnTo() {
        build();
        PaneZoom<Node, Split> zoom = PaneZoom.zoom(b, inner.items, host, placeholder, List.of(root, inner), DIVIDERS);
        inner.dividers = new double[] {0.5};

        assertWithMessage("a project saved while zoomed stores the divider the tab returns to")
            .that(zoom.savedPositions(inner)).usingExactEquality().containsExactly(0.7);
        assertThat(zoom.savedPositions(root)).usingExactEquality().containsExactly(0.3);
        assertThat(zoom.savedPositions(new Split("other", a, c))).isNull();
        zoom.savedPositions(inner)[0] = 0.1;
        assertThat(zoom.savedPositions(inner)).usingExactEquality().containsExactly(0.7);
    }

    @Test
    public void aPaneThatIsNotInTheItemsIsNotZoomedAndNothingChanges() {
        build();

        PaneZoom<Node, Split> zoom = PaneZoom.zoom(a, inner.items, host, placeholder, List.of(root, inner), DIVIDERS);

        assertWithMessage("a tab's only pane is the root and has nothing to zoom out of").that(zoom).isNull();
        assertThat(host).containsExactly(root.node);
        assertThat(inner.items).containsExactly(b, c).inOrder();
    }

    @Test
    public void thePaneIsFoundByReferenceNotByEquality() {
        build();
        Node lookalike = new Node("B");
        inner.items.set(1, lookalike);

        PaneZoom<Node, Split> zoom = PaneZoom.zoom(lookalike, inner.items, host, placeholder, List.of(inner), DIVIDERS);

        assertThat(zoom).isNotNull();
        assertThat(inner.items.get(0)).isSameInstanceAs(b);
        assertThat(inner.items.get(1)).isSameInstanceAs(placeholder);
        zoom.restore();
        assertThat(inner.items.get(1)).isSameInstanceAs(lookalike);
        assertThat(host).containsExactly(root.node);
    }

    @Test
    public void whatTheHostGotWhileZoomedStaysAboveTheTree() {
        build();
        PaneZoom<Node, Split> zoom = PaneZoom.zoom(b, inner.items, host, placeholder, List.of(root, inner), DIVIDERS);
        Node overlay = new Node("overlay");
        host.add(overlay);

        zoom.restore();

        assertThat(host).containsExactly(root.node, overlay).inOrder();
        assertThat(host.get(0)).isSameInstanceAs(root.node);
    }

    @Test
    public void zoomingTwiceInARowRestoresTheFirstLayout() {
        build();
        PaneZoom<Node, Split> first = PaneZoom.zoom(b, inner.items, host, placeholder, List.of(root, inner), DIVIDERS);
        first.restore();
        root.dividers = new double[] {0.6};

        PaneZoom<Node, Split> second = PaneZoom.zoom(a, root.items, host, new Node("placeholder 2"),
            List.of(root, inner), DIVIDERS);
        root.dividers = new double[] {0.5};
        second.restore();

        assertWithMessage("a divider moved between two zooms keeps its new place")
            .that(root.dividers).usingExactEquality().containsExactly(0.6);
        assertThat(root.items).containsExactly(a, inner.node).inOrder();
        assertThat(inner.items).containsExactly(b, c).inOrder();
        assertThat(host).containsExactly(root.node);
    }
}
