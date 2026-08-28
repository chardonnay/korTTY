package de.kortty.ui;

import javafx.geometry.Rectangle2D;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * The dock arithmetic, exercised without a display. Two fake screens stand in for the real ones, so
 * "there is no room on the right" is a number here rather than a machine someone has to reproduce.
 */
class WindowDockGroupTest {

    /** A laptop screen with a menu bar, and a wide external one to its right. */
    private static final Rectangle2D LAPTOP = new Rectangle2D(0, 25, 1512, 920);
    private static final Rectangle2D WIDE = new Rectangle2D(0, 0, 3440, 1440);

    private static final double SATELLITE = 360;
    private static final double MIN_HEIGHT = 420;

    @Test
    void docksOnTheRequestedSideWhenItFits() {
        Rectangle2D anchor = new Rectangle2D(500, 100, 800, 700);

        Rectangle2D right = WindowDockGroup.dockBounds(
            anchor, WIDE, WindowDockGroup.Side.RIGHT, SATELLITE, MIN_HEIGHT, false);
        assertThat(right.getMinX()).isEqualTo(anchor.getMaxX() + WindowDockGroup.GAP);

        Rectangle2D left = WindowDockGroup.dockBounds(
            anchor, WIDE, WindowDockGroup.Side.LEFT, SATELLITE, MIN_HEIGHT, false);
        assertThat(left.getMaxX()).isEqualTo(anchor.getMinX() - WindowDockGroup.GAP);
    }

    @Test
    void matchesTheAnchorHeightButNeverGoesBelowItsOwnMinimum() {
        Rectangle2D tall = new Rectangle2D(500, 100, 800, 700);
        assertThat(WindowDockGroup.dockBounds(
            tall, WIDE, WindowDockGroup.Side.RIGHT, SATELLITE, MIN_HEIGHT, false).getHeight())
            .isEqualTo(700);

        Rectangle2D squat = new Rectangle2D(500, 100, 800, 200);
        assertThat(WindowDockGroup.dockBounds(
            squat, WIDE, WindowDockGroup.Side.RIGHT, SATELLITE, MIN_HEIGHT, false).getHeight())
            .isEqualTo(MIN_HEIGHT);
    }

    @Test
    void flipsToTheFreeSideWhenTheRequestedOneHasNoRoom() {
        // Hard against the right edge of the laptop screen: nothing fits to its right.
        Rectangle2D anchor = new Rectangle2D(700, 25, 812, 900);

        Rectangle2D placed = WindowDockGroup.dockBounds(
            anchor, LAPTOP, WindowDockGroup.Side.RIGHT, SATELLITE, MIN_HEIGHT, false);

        assertThat(placed.getMaxX()).isEqualTo(anchor.getMinX() - WindowDockGroup.GAP);
    }

    @Test
    void doesNotFlipOntoAnOccupiedSide() {
        Rectangle2D anchor = new Rectangle2D(700, 25, 812, 900);

        Rectangle2D placed = WindowDockGroup.dockBounds(
            anchor, LAPTOP, WindowDockGroup.Side.RIGHT, SATELLITE, MIN_HEIGHT, true);

        // The left is taken, so it stays on its own side, flush to the screen edge.
        assertThat(placed.getMaxX()).isEqualTo(LAPTOP.getMaxX());
        assertThat(placed.getMinX()).isGreaterThan(anchor.getMinX());
    }

    @Test
    void staysOnScreenEvenWhenTheAnchorFillsIt() {
        Rectangle2D anchor = new Rectangle2D(0, 25, 1512, 920);

        for (WindowDockGroup.Side side : WindowDockGroup.Side.values()) {
            Rectangle2D placed = WindowDockGroup.dockBounds(
                anchor, LAPTOP, side, SATELLITE, MIN_HEIGHT, true);
            assertThat(placed.getMinX()).isAtLeast(LAPTOP.getMinX());
            assertThat(placed.getMaxX()).isAtMost(LAPTOP.getMaxX());
            assertThat(placed.getMinY()).isAtLeast(LAPTOP.getMinY());
            assertThat(placed.getMaxY()).isAtMost(LAPTOP.getMaxY());
        }
    }

    @Test
    void keepsTheSatelliteBelowTheMenuBarAndAboveTheScreenEdge() {
        // An anchor dragged so far down that matching its y would push the satellite off screen.
        Rectangle2D low = new Rectangle2D(200, 800, 800, 700);

        Rectangle2D placed = WindowDockGroup.dockBounds(
            low, LAPTOP, WindowDockGroup.Side.RIGHT, SATELLITE, MIN_HEIGHT, false);

        assertThat(placed.getMaxY()).isAtMost(LAPTOP.getMaxY());
        assertThat(placed.getMinY()).isAtLeast(LAPTOP.getMinY());
    }

    @Test
    void reportsWhetherTheTrioFitsBesideEachOther() {
        assertThat(WindowDockGroup.fitsBeside(WIDE, 360, 1160, 360)).isTrue();
        assertThat(WindowDockGroup.fitsBeside(LAPTOP, 360, 1160, 360)).isFalse();
        // One satellite only: no gap is charged for the missing side.
        assertThat(WindowDockGroup.fitsBeside(LAPTOP, 0, 1144, 360)).isTrue();
    }

    @Test
    void tilingFillsTheScreenWithoutOverlapWhenThereIsRoom() {
        WindowDockGroup.Tiling tiling = WindowDockGroup.tiling(WIDE, 720, 360, 1160, 640);

        assertThat(tiling.leftX()).isEqualTo(WIDE.getMinX());
        assertThat(tiling.anchorX()).isEqualTo(720 + WindowDockGroup.GAP);
        assertThat(tiling.anchorWidth()).isEqualTo(1160);
        assertThat(tiling.rightX()).isEqualTo(tiling.anchorX() + 1160 + WindowDockGroup.GAP);
        assertThat(tiling.rightX() + 360).isAtMost(WIDE.getMaxX());
    }

    @Test
    void tilingNarrowsTheAnchorSoBothSatellitesFit() {
        // 1900 - 400 - 360 - 2 gaps leaves 1124: less than the anchor wanted, more than it needs.
        Rectangle2D screen = new Rectangle2D(0, 0, 1900, 1000);

        WindowDockGroup.Tiling tiling = WindowDockGroup.tiling(screen, 400, 360, 1160, 640);

        assertThat(tiling.anchorWidth()).isEqualTo(1124);
        assertThat(tiling.anchorWidth()).isLessThan(1160);
        assertThat(tiling.rightX() + 360).isAtMost(screen.getMaxX());
    }

    @Test
    void tilingNeverSqueezesTheAnchorBelowItsMinimum() {
        // On a laptop screen all three cannot fit: the anchor keeps its minimum width and the right
        // satellite overlaps it rather than being pushed out of reach.
        WindowDockGroup.Tiling tiling = WindowDockGroup.tiling(LAPTOP, 720, 360, 1160, 640);

        assertThat(tiling.anchorWidth()).isEqualTo(640);
        assertThat(LAPTOP.getWidth() - 720 - 360 - 2 * WindowDockGroup.GAP).isLessThan(640);
        assertThat(tiling.rightX() + 360).isAtMost(LAPTOP.getMaxX());
    }

    @Test
    void tilingPlacesEverythingOnTheAnchorsOwnScreen() {
        // Second monitor: the layout must be expressed in that screen's coordinates, not the origin.
        Rectangle2D external = new Rectangle2D(1512, 0, 2560, 1440);

        WindowDockGroup.Tiling tiling = WindowDockGroup.tiling(external, 720, 360, 1160, 640);

        assertThat(tiling.leftX()).isEqualTo(1512);
        assertThat(tiling.anchorX()).isAtLeast(external.getMinX());
        assertThat(tiling.rightX() + 360).isAtMost(external.getMaxX());
    }

    @Test
    void slidesTheAnchorJustFarEnoughToOpenUpBothSides() {
        // Screen-wide room is not room here: an anchor near the left edge of a wide display has
        // nowhere to put a left dock, even though all three fit comfortably.
        double anchorWidth = 1000;
        assertThat(WindowDockGroup.fitsBeside(WIDE, 620, anchorWidth, 360)).isTrue();

        double x = WindowDockGroup.anchorXFor(WIDE, 400, anchorWidth, 620, 360);

        assertThat(x).isEqualTo(620 + WindowDockGroup.GAP);
        assertThat(x - WindowDockGroup.GAP - 620).isAtLeast(WIDE.getMinX());
    }

    @Test
    void leavesTheAnchorAloneWhenBothSidesAlreadyFit() {
        assertThat(WindowDockGroup.anchorXFor(WIDE, 1500, 1000, 620, 360)).isEqualTo(1500);
    }

    @Test
    void slidesTheAnchorBackWhenTheRightDockWouldRunOffScreen() {
        double anchorWidth = 1000;
        double x = WindowDockGroup.anchorXFor(WIDE, 3000, anchorWidth, 620, 360);

        assertThat(x + anchorWidth + WindowDockGroup.GAP + 360).isAtMost(WIDE.getMaxX());
        assertThat(x).isLessThan(3000);
    }

    @Test
    void anchorSlidingRespectsTheScreenTheWindowIsActuallyOn() {
        Rectangle2D external = new Rectangle2D(1512, 0, 2560, 1440);

        double x = WindowDockGroup.anchorXFor(external, 1512, 1000, 620, 360);

        assertThat(x).isEqualTo(1512 + 620 + WindowDockGroup.GAP);
    }
}
