package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class SettingsSectionNavigationTest {

    @Test
    void previousClampsAtFirstSection() {
        assertThat(SettingsSectionNavigation.previous(0)).isEqualTo(0);
        assertThat(SettingsSectionNavigation.previous(1)).isEqualTo(0);
        assertThat(SettingsSectionNavigation.previous(5)).isEqualTo(4);
        // Defensive against an unexpected negative index.
        assertThat(SettingsSectionNavigation.previous(-3)).isEqualTo(0);
    }

    @Test
    void nextClampsAtLastSection() {
        assertThat(SettingsSectionNavigation.next(0, 18)).isEqualTo(1);
        assertThat(SettingsSectionNavigation.next(16, 18)).isEqualTo(17);
        assertThat(SettingsSectionNavigation.next(17, 18)).isEqualTo(17);
        // Defensive: empty / unknown section set.
        assertThat(SettingsSectionNavigation.next(0, 0)).isEqualTo(0);
    }

    @Test
    void boundaryFlagsDisableArrowsAtTheEnds() {
        assertThat(SettingsSectionNavigation.canGoPrevious(0)).isFalse();
        assertThat(SettingsSectionNavigation.canGoPrevious(1)).isTrue();
        assertThat(SettingsSectionNavigation.canGoNext(17, 18)).isFalse();
        assertThat(SettingsSectionNavigation.canGoNext(16, 18)).isTrue();
        // No sections → both arrows disabled.
        assertThat(SettingsSectionNavigation.canGoNext(0, 0)).isFalse();
        assertThat(SettingsSectionNavigation.canGoPrevious(0)).isFalse();
    }

    @Test
    void positionLabelShowsTitleAndOneBasedPosition() {
        assertThat(SettingsSectionNavigation.positionLabel("Appearance", 0, 18)).isEqualTo("Appearance  (1/18)");
        assertThat(SettingsSectionNavigation.positionLabel("AI", 16, 18)).isEqualTo("AI  (17/18)");
        // Blank title falls back to the bare position.
        assertThat(SettingsSectionNavigation.positionLabel("   ", 2, 18)).isEqualTo("3/18");
        // No usable section index → just the (trimmed) title, no crash.
        assertThat(SettingsSectionNavigation.positionLabel("X", -1, 0)).isEqualTo("X");
        assertThat(SettingsSectionNavigation.positionLabel(null, 0, 18)).isEqualTo("1/18");
        // Out-of-range index must not produce a nonsensical position like "9/3".
        assertThat(SettingsSectionNavigation.positionLabel("X", 8, 3)).isEqualTo("X");
        assertThat(SettingsSectionNavigation.positionLabel("X", 18, 18)).isEqualTo("X");
    }

    /**
     * TabPane's selection model sets the selected index before the selected item, and the label is
     * updated from the index listener: read from the selected item, it named the tab selected
     * before ("Font (12/21)" on the Keyboard tab).
     */
    @Test
    void theLabelNamesTheTabAtTheSelectedIndex() throws IOException {
        String dialog = Files.readString(Path.of("src/main/java/de/kortty/ui/SettingsDialog.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = dialog.indexOf("private static Region buildSectionNavigationContent(TabPane tabPane) {");
        assertThat(start).isAtLeast(0);
        String body = dialog.substring(start, dialog.indexOf("\n    }\n", start));

        assertThat(body).contains("Tab selected = index >= 0 && index < count ? tabPane.getTabs().get(index) : null;");
        assertWithMessage("the selected item lags behind the index").that(body)
            .doesNotContain("getSelectionModel().getSelectedItem()");
        assertThat(body).contains("tabPane.getSelectionModel().selectedIndexProperty().addListener(");
    }
}
