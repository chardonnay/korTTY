package de.kortty.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class TabPaneArrowNavigationTest {

    private static KeyEvent key(KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta);
    }

    @Test
    void ctrlTabAndCtrlPageDownStepForward() {
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.TAB, false, true, false, false))).isEqualTo(1);
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.PAGE_DOWN, false, true, false, false))).isEqualTo(1);
    }

    @Test
    void ctrlShiftTabAndCtrlPageUpStepBack() {
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.TAB, true, true, false, false))).isEqualTo(-1);
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.PAGE_UP, false, true, false, false))).isEqualTo(-1);
    }

    @Test
    void plainTabAndOtherModifiersKeepTheirMeaning() {
        // Tab alone moves the focus between fields; Cmd/Alt combinations belong to the platform.
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.TAB, false, false, false, false))).isEqualTo(0);
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.TAB, false, true, false, true))).isEqualTo(0);
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.TAB, false, true, true, false))).isEqualTo(0);
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.PAGE_DOWN, true, true, false, false))).isEqualTo(0);
        assertThat(TabPaneArrowNavigation.keyDirection(key(KeyCode.A, false, true, false, false))).isEqualTo(0);
    }
}
