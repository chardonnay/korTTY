package de.kortty.ui;

import de.kortty.ui.PaneNavigator.PaneDirection;
import de.kortty.ui.PaneNavigator.Rect;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Where Cmd+Option / Ctrl+Alt with an arrow key and View → Panes move the focus: the neighbour on
 * that side, preferring one that overlaps the focused pane across the direction, then the smaller
 * gap, then the nearest centre; nothing at the edge or with one pane. Next and previous wrap around.
 * Bounds are built by hand (with a 4-pixel divider between panes, as a SplitPane draws one), so no
 * JavaFX toolkit is needed.
 */
class PaneNavigatorTest {

    private static final double DIVIDER = 4;

    /**
     * <pre>
     * +----+----+
     * | A  | B  |
     * +----+----+
     * | C  | D  |
     * +----+----+
     * </pre>
     */
    private static Map<String, Rect> grid() {
        Map<String, Rect> panes = new LinkedHashMap<>();
        panes.put("A", new Rect(0, 0, 400, 300));
        panes.put("B", new Rect(400 + DIVIDER, 0, 400, 300));
        panes.put("C", new Rect(0, 300 + DIVIDER, 400, 300));
        panes.put("D", new Rect(400 + DIVIDER, 300 + DIVIDER, 400, 300));
        return panes;
    }

    /**
     * An L shape: one tall pane on the left, two stacked on the right.
     * <pre>
     * +----+----+
     * |    | B  |
     * | A  +----+
     * |    | C  |
     * +----+----+
     * </pre>
     */
    private static Map<String, Rect> lShape() {
        Map<String, Rect> panes = new LinkedHashMap<>();
        panes.put("A", new Rect(0, 0, 400, 604));
        panes.put("B", new Rect(400 + DIVIDER, 0, 400, 300));
        panes.put("C", new Rect(400 + DIVIDER, 300 + DIVIDER, 400, 300));
        return panes;
    }

    /**
     * One wide pane on top of three narrow ones.
     * <pre>
     * +--------------+
     * |      A       |
     * +----+----+----+
     * | B  | C  | D  |
     * +----+----+----+
     * </pre>
     */
    private static Map<String, Rect> uneven() {
        Map<String, Rect> panes = new LinkedHashMap<>();
        panes.put("A", new Rect(0, 0, 900, 300));
        panes.put("B", new Rect(0, 300 + DIVIDER, 296, 300));
        panes.put("C", new Rect(300, 300 + DIVIDER, 296, 300));
        panes.put("D", new Rect(600, 300 + DIVIDER, 300, 300));
        return panes;
    }

    private static Optional<String> neighbor(Map<String, Rect> panes, String from, PaneDirection direction) {
        return PaneNavigator.neighbor(from, List.copyOf(panes.keySet()), panes::get, direction);
    }

    @Test
    void aTwoByTwoGridMovesToTheAdjacentPaneOnEverySide() {
        Map<String, Rect> grid = grid();

        assertThat(neighbor(grid, "A", PaneDirection.RIGHT)).hasValue("B");
        assertThat(neighbor(grid, "A", PaneDirection.DOWN)).hasValue("C");
        assertThat(neighbor(grid, "D", PaneDirection.LEFT)).hasValue("C");
        assertThat(neighbor(grid, "D", PaneDirection.UP)).hasValue("B");
        assertWithMessage("B only touches C's corner, A lies right above it")
            .that(neighbor(grid, "C", PaneDirection.UP)).hasValue("A");
        assertThat(neighbor(grid, "B", PaneDirection.DOWN)).hasValue("D");
    }

    @Test
    void thereIsNoNeighbourAtTheEdge() {
        Map<String, Rect> grid = grid();

        assertThat(neighbor(grid, "A", PaneDirection.LEFT)).isEmpty();
        assertThat(neighbor(grid, "A", PaneDirection.UP)).isEmpty();
        assertThat(neighbor(grid, "D", PaneDirection.RIGHT)).isEmpty();
        assertThat(neighbor(grid, "D", PaneDirection.DOWN)).isEmpty();
    }

    @Test
    void aSinglePaneHasNoNeighbour() {
        Map<String, Rect> single = Map.of("A", new Rect(0, 0, 800, 600));

        for (PaneDirection direction : PaneDirection.values()) {
            assertThat(neighbor(single, "A", direction)).isEmpty();
        }
        assertThat(PaneNavigator.next(List.of("A"), "A", true)).isEmpty();
        assertThat(PaneNavigator.next(List.of("A"), "A", false)).isEmpty();
    }

    @Test
    void anLShapeLeavesTheTallPaneForTheNearerOfTwoEqualCandidatesAndComesBack() {
        Map<String, Rect> lShape = lShape();

        // B and C both overlap A and lie at the same gap: their centres are equally far from A's,
        // so the order of the panes decides, and B comes first.
        assertThat(neighbor(lShape, "A", PaneDirection.RIGHT)).hasValue("B");
        assertThat(neighbor(lShape, "B", PaneDirection.LEFT)).hasValue("A");
        assertThat(neighbor(lShape, "C", PaneDirection.LEFT)).hasValue("A");
        assertThat(neighbor(lShape, "B", PaneDirection.DOWN)).hasValue("C");
        assertThat(neighbor(lShape, "C", PaneDirection.UP)).hasValue("B");
        assertThat(neighbor(lShape, "A", PaneDirection.DOWN)).isEmpty();
    }

    @Test
    void aWidePaneMovesDownToThePaneBelowItsCentre() {
        Map<String, Rect> uneven = uneven();

        assertWithMessage("all three overlap A at the same gap: C's centre is nearest to A's")
            .that(neighbor(uneven, "A", PaneDirection.DOWN)).hasValue("C");
        assertThat(neighbor(uneven, "B", PaneDirection.UP)).hasValue("A");
        assertThat(neighbor(uneven, "D", PaneDirection.UP)).hasValue("A");
        assertThat(neighbor(uneven, "B", PaneDirection.RIGHT)).hasValue("C");
        assertThat(neighbor(uneven, "D", PaneDirection.LEFT)).hasValue("C");
    }

    @Test
    void anOverlappingPaneBeatsANearerOneThatOnlyTouchesACorner() {
        Map<String, Rect> panes = new LinkedHashMap<>();
        panes.put("O", new Rect(0, 0, 100, 100));
        // Right next to O but entirely below its bottom edge: it only shares a corner.
        panes.put("corner", new Rect(100, 100, 100, 100));
        // Further away, but beside O.
        panes.put("beside", new Rect(300, 20, 100, 100));

        assertThat(neighbor(panes, "O", PaneDirection.RIGHT)).hasValue("beside");
    }

    @Test
    void amongOverlappingPanesTheSmallerGapWinsThenTheNearerCentre() {
        Map<String, Rect> panes = new LinkedHashMap<>();
        panes.put("O", new Rect(0, 0, 100, 100));
        panes.put("far", new Rect(300, 0, 100, 100));
        panes.put("near", new Rect(104, 60, 100, 100));
        assertThat(neighbor(panes, "O", PaneDirection.RIGHT)).hasValue("near");

        panes.put("level", new Rect(104, 10, 100, 100));
        assertWithMessage("same gap: the centre nearer O's centre wins")
            .that(neighbor(panes, "O", PaneDirection.RIGHT)).hasValue("level");
    }

    @Test
    void aPaneThatReachesBackPastTheEdgeIsNotBesideIt() {
        Map<String, Rect> panes = new LinkedHashMap<>();
        panes.put("O", new Rect(0, 0, 100, 100));
        panes.put("overlapping", new Rect(50, 0, 100, 100));

        assertThat(neighbor(panes, "O", PaneDirection.RIGHT)).isEmpty();
        // Fractional bounds that meet within a pixel still count as beside it.
        panes.put("touching", new Rect(99.5, 0, 100, 100));
        assertThat(neighbor(panes, "O", PaneDirection.RIGHT)).hasValue("touching");
    }

    @Test
    void panesWithoutBoundsAreSkippedAndAnOriginWithoutBoundsGoesNowhere() {
        Map<String, Rect> grid = grid();
        grid.put("B", null);

        assertWithMessage("B is not laid out, D is the next pane on the right")
            .that(neighbor(grid, "A", PaneDirection.RIGHT)).hasValue("D");
        assertThat(PaneNavigator.neighbor("X", List.of("X", "A"), grid::get, PaneDirection.RIGHT)).isEmpty();
        assertThat(PaneNavigator.neighbor(null, List.of("A", "B"), grid::get, PaneDirection.RIGHT)).isEmpty();
    }

    @Test
    void panesAreComparedByIdentity() {
        String first = new String("pane");
        String second = new String("pane");
        Map<String, Rect> bounds = new java.util.IdentityHashMap<>();
        bounds.put(first, new Rect(0, 0, 100, 100));
        bounds.put(second, new Rect(104, 0, 100, 100));

        assertThat(PaneNavigator.neighbor(first, List.of(first, second), bounds::get, PaneDirection.RIGHT).get())
            .isSameInstanceAs(second);
        assertThat(PaneNavigator.next(List.of(first, second), second, true).get()).isSameInstanceAs(first);
    }

    @Test
    void nextAndPreviousWrapAround() {
        List<String> panes = List.of("A", "B", "C");

        assertThat(PaneNavigator.next(panes, "A", true)).hasValue("B");
        assertThat(PaneNavigator.next(panes, "C", true)).hasValue("A");
        assertThat(PaneNavigator.next(panes, "A", false)).hasValue("C");
        assertThat(PaneNavigator.next(panes, "B", false)).hasValue("A");
    }

    @Test
    void nextFromAPaneThatIsGoneStartsAtTheFirstOrTheLast() {
        List<String> panes = List.of("A", "B", "C");

        assertThat(PaneNavigator.next(panes, "gone", true)).hasValue("A");
        assertThat(PaneNavigator.next(panes, null, false)).hasValue("C");
    }
}
