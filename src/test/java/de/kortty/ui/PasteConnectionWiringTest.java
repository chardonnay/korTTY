package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Where a connection's own paste protection is hooked in, which no headless test can build: the
 * connection editor stores the warning mode and the line delay on Save, its rows sit in the Terminal
 * behavior section outside the blocks that custom terminal settings and terminal effects switch off, and
 * Quick Connect and the Connection Manager import carry both values like every other per-connection
 * override. How a paste resolves them is checked in {@code PasteProtectionWiringTest}.
 */
class PasteConnectionWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void theConnectionEditorStoresBothValuesOnSave() throws IOException {
        String converter = region(source("ConnectionEditDialog.java"), "setResultConverter(dialogButton -> {",
            "pasteLineDelayCheck.isSelected(), pasteLineDelaySpinner.getValue()));");

        assertWithMessage("stored with the other fields when Save is pressed")
            .that(converter).contains("if (dialogButton == saveButtonType) {");
        assertThat(converter).contains(
            "connection.setPasteWarningMode(PasteConnectionSupport.storedMode(pasteWarningModeCombo.getValue()));");
        assertThat(converter).contains("connection.setPasteLineDelayMs(PasteConnectionSupport.storedLineDelay(");
    }

    @Test
    void theRowsSitInTheTerminalBehaviorSection() throws IOException {
        String dialog = source("ConnectionEditDialog.java");
        String grid = region(dialog, "private GridPane createTerminalBehaviorGrid() {", "\n    }\n");

        assertThat(grid).contains("grid.add(pasteWarningModeCombo, 1, row++);");
        assertThat(grid).contains("grid.add(pasteLineDelayBox, 1, row++);");
        assertThat(grid).contains("pasteLineDelaySpinner.disableProperty().bind(pasteLineDelayCheck.selectedProperty().not());");
        assertThat(grid).contains("PasteConnectionSupport.selectedMode(pasteModeChoices, connection.getPasteWarningMode())");
        assertWithMessage("a teamwork connection says that its warning can only ask more often")
            .that(grid).contains("if (connection.isTeamworkConnection()) {");
        String tab = region(dialog, "private Tab createSettingsTab() {", "\n    }\n");
        assertWithMessage("custom terminal settings disable settingsGrid; paste protection must not depend on them")
            .that(tab).doesNotContain("settingsGrid.add(pasteWarningModeCombo");
    }

    @Test
    void quickConnectAndTheImportCarryBothValues() throws IOException {
        String baseCopy = region(source("QuickConnectDialog.java"), "private ServerConnection baseCopyOf(", "\n    }\n");
        assertThat(baseCopy).contains("modified.setPasteWarningMode(selected.getPasteWarningMode());");
        assertThat(baseCopy).contains("modified.setPasteLineDelayMs(selected.getPasteLineDelayMs());");

        String manager = source("ConnectionManagerDialog.java");
        assertThat(manager).contains("imported.setPasteWarningMode(conn.getPasteWarningMode());");
        assertThat(manager).contains("imported.setPasteLineDelayMs(conn.getPasteLineDelayMs());");
    }

    private static String source(String file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(UI.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code startMarker} up to and including the next {@code endMarker}. */
    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }
}
