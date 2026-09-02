package de.kortty.ui;

import de.kortty.model.WindowGeometry;
import javafx.geometry.Rectangle2D;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class MainWindowGeometrySupportTest {

    private static final Rectangle2D SCREEN = new Rectangle2D(0, 25, 1512, 920);

    @Test
    void reappliesNormalGeometryAfterShowingAUnifiedMacWindow() {
        WindowGeometry stored = new WindowGeometry(140, 90, 1100, 760);

        MainWindowGeometrySupport.RestorePlan plan =
            MainWindowGeometrySupport.plan(stored, List.of(SCREEN), true);

        assertThat(plan.geometry().getX()).isEqualTo(140);
        assertThat(plan.geometry().getY()).isEqualTo(90);
        assertThat(plan.reapplyAfterShow()).isTrue();
    }

    @Test
    void doesNotScheduleASecondRestoreForRegularWindowChrome() {
        WindowGeometry stored = new WindowGeometry(140, 90, 1100, 760);

        MainWindowGeometrySupport.RestorePlan plan =
            MainWindowGeometrySupport.plan(stored, List.of(SCREEN), false);

        assertThat(plan.reapplyAfterShow()).isFalse();
    }

    @Test
    void leavesMaximizedWindowsToTheNativeWindowManagerAfterShow() {
        WindowGeometry stored = new WindowGeometry(140, 90, 1100, 760);
        stored.setMaximized(true);

        MainWindowGeometrySupport.RestorePlan plan =
            MainWindowGeometrySupport.plan(stored, List.of(SCREEN), true);

        assertThat(plan.geometry().isMaximized()).isTrue();
        assertThat(plan.reapplyAfterShow()).isFalse();
    }

    @Test
    void recentersGeometryFromADisconnectedMonitorBeforeRestoringIt() {
        WindowGeometry stored = new WindowGeometry(2200, 200, 1100, 760);

        MainWindowGeometrySupport.RestorePlan plan =
            MainWindowGeometrySupport.plan(stored, List.of(SCREEN), true);

        assertThat(plan.geometry().getX()).isAtLeast(SCREEN.getMinX());
        assertThat(plan.geometry().getX() + plan.geometry().getWidth()).isAtMost(SCREEN.getMaxX());
        assertThat(plan.geometry().getY()).isAtLeast(SCREEN.getMinY());
    }

    @Test
    void snapshotsPersistedGeometrySoLaterSettingsChangesCannotAlterTheRestore() {
        WindowGeometry stored = new WindowGeometry(140, 90, 1100, 760);

        MainWindowGeometrySupport.RestorePlan plan =
            MainWindowGeometrySupport.plan(stored, List.of(SCREEN), true);
        stored.setY(500);

        assertThat(plan.geometry().getY()).isEqualTo(90);
    }
}
