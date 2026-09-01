package de.kortty.ui;

import de.kortty.model.WindowGeometry;
import javafx.geometry.Rectangle2D;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class DialogGeometrySupportTest {

    private static final Rectangle2D LAPTOP = new Rectangle2D(0, 25, 1512, 920);
    /** A second monitor placed to the right of the laptop screen. */
    private static final Rectangle2D EXTERNAL = new Rectangle2D(1512, 0, 2560, 1440);

    private static WindowGeometry geometry(double x, double y, double width, double height) {
        return new WindowGeometry(x, y, width, height);
    }

    @Test
    void keepsAGeometryThatSitsOnTheOnlyScreen() {
        WindowGeometry stored = geometry(200, 120, 1000, 700);

        assertThat(DialogGeometrySupport.sanitize(stored, List.of(LAPTOP))).isSameInstanceAs(stored);
    }

    @Test
    void keepsAGeometryOnASecondScreenWhileThatScreenIsStillThere() {
        WindowGeometry stored = geometry(1800, 300, 1200, 800);

        assertThat(DialogGeometrySupport.sanitize(stored, List.of(LAPTOP, EXTERNAL)))
            .isSameInstanceAs(stored);
    }

    @Test
    void recoversAWindowLeftOnAMonitorThatIsNoLongerConnected() {
        WindowGeometry stored = geometry(1800, 300, 1200, 800);

        WindowGeometry fixed = DialogGeometrySupport.sanitize(stored, List.of(LAPTOP));

        assertThat(fixed).isNotNull();
        // The size the user chose survives; only the position is brought back into reach.
        assertThat(fixed.getWidth()).isEqualTo(1200);
        assertThat(fixed.getHeight()).isEqualTo(800);
        assertThat(fixed.getX()).isAtLeast(LAPTOP.getMinX());
        assertThat(fixed.getX() + fixed.getWidth()).isAtMost(LAPTOP.getMaxX());
        assertThat(fixed.getY()).isAtLeast(LAPTOP.getMinY());
    }

    @Test
    void shrinksAWindowLargerThanTheRemainingScreen() {
        WindowGeometry stored = geometry(2000, 100, 2400, 1300);

        WindowGeometry fixed = DialogGeometrySupport.sanitize(stored, List.of(LAPTOP));

        assertThat(fixed.getWidth()).isAtMost(LAPTOP.getWidth());
        assertThat(fixed.getHeight()).isAtMost(LAPTOP.getHeight());
    }

    @Test
    void recoversAWindowDraggedAlmostEntirelyOffTheLeftEdge() {
        // Only 40px of a 900px window would remain visible — not enough to grab.
        WindowGeometry fixed = DialogGeometrySupport.sanitize(geometry(-860, 200, 900, 600),
            List.of(LAPTOP));

        assertThat(fixed.getX()).isAtLeast(LAPTOP.getMinX());
    }

    @Test
    void recoversAWindowWhoseTitleBarIsBelowTheScreen() {
        WindowGeometry fixed = DialogGeometrySupport.sanitize(geometry(200, 1400, 900, 600),
            List.of(LAPTOP));

        assertThat(fixed.getY()).isLessThan(LAPTOP.getMaxY());
    }

    @Test
    void treatsAnAbsurdlySmallStoredSizeAsNothingWorthRestoring() {
        assertThat(DialogGeometrySupport.sanitize(geometry(100, 100, 10, 10), List.of(LAPTOP))).isNull();
        assertThat(DialogGeometrySupport.sanitize(geometry(100, 100, 900, 4), List.of(LAPTOP))).isNull();
        assertThat(DialogGeometrySupport.sanitize(null, List.of(LAPTOP))).isNull();
    }

    @Test
    void acceptsEveryPositionWhenNoScreensAreKnown() {
        // Headless tooling has no toolkit; a stored geometry must not be second-guessed there.
        WindowGeometry stored = geometry(9000, 9000, 800, 600);

        assertThat(DialogGeometrySupport.sanitize(stored, List.of())).isSameInstanceAs(stored);
        assertThat(DialogGeometrySupport.sanitize(stored, null)).isSameInstanceAs(stored);
    }

    @Test
    void usesTheDialogClassNameAsStableAutomaticPersistenceKey() {
        assertThat(DialogGeometrySupport.automaticKey(TerminalEffectPluginManagerDialog.class))
            .isEqualTo("de.kortty.ui.TerminalEffectPluginManagerDialog");
    }
}
