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

    @Test
    void aMaximizedWindowFromADisconnectedMonitorOpensMaximized() {
        WindowGeometry stored = new WindowGeometry(2200, 200, 1100, 760);
        stored.setMaximized(true);

        MainWindowGeometrySupport.RestorePlan plan =
            MainWindowGeometrySupport.plan(stored, List.of(SCREEN), false);

        assertThat(plan.geometry().getX()).isLessThan(SCREEN.getMaxX());
        assertThat(plan.geometry().isMaximized()).isTrue();
    }

    @Test
    void aProjectSavesTheNormalBoundsOfAMaximizedWindow() {
        WindowGeometry normal = new WindowGeometry(140, 90, 1100, 760);

        WindowGeometry saved = MainWindowGeometrySupport.capture(0, 25, 1512, 920, true, false, false, normal);

        assertThat(saved.getX()).isEqualTo(140);
        assertThat(saved.getWidth()).isEqualTo(1100);
        assertThat(saved.isMaximized()).isTrue();
        assertThat(saved).isNotSameInstanceAs(normal);
    }

    @Test
    void aProjectSavesTheCurrentBoundsOfANormalWindowAndNeverFullscreen() {
        WindowGeometry stale = new WindowGeometry(10, 10, 640, 480);

        WindowGeometry normalWindow = MainWindowGeometrySupport.capture(200, 120, 1000, 700, false, false, false, stale);
        WindowGeometry fullScreen = MainWindowGeometrySupport.capture(0, 0, 1512, 982, false, true, false, stale);
        WindowGeometry unknownNormal = MainWindowGeometrySupport.capture(0, 25, 1512, 920, true, false, false, null);

        assertThat(normalWindow.getX()).isEqualTo(200);
        assertThat(normalWindow.getWidth()).isEqualTo(1000);
        assertThat(normalWindow.isMaximized()).isFalse();
        assertThat(fullScreen.getWidth()).isEqualTo(640);
        assertThat(fullScreen.isFullScreen()).isFalse();
        assertThat(unknownNormal.getWidth()).isEqualTo(1512);
        assertThat(unknownNormal.isMaximized()).isTrue();
    }
}
