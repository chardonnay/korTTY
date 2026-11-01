package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/** The close request of the AI manager asks before unsaved Markdown of the Memory tab is discarded, and keeps a refused skill edit. */
class AiMemoryCloseWiringSourceScanTest {

    @Test
    void theCloseRequestConsultsTheMemoryPaneBeforeTheDialogCloses() throws IOException {
        String dialog = Files.readString(Path.of("src/main/java/de/kortty/ui/AiManagerDialog.java"));
        int start = dialog.indexOf("setOnCloseRequest(");
        int end = dialog.indexOf("setOnHidden(", start);

        assertThat(start).isAtLeast(0);
        assertThat(dialog.substring(start, end)).contains("aiMemoryPane.confirmClose()");
    }

    @Test
    void theCloseRequestKeepsTheDialogOpenForARefusedSkillEdit() throws IOException {
        String dialog = Files.readString(Path.of("src/main/java/de/kortty/ui/AiManagerDialog.java"));
        int start = dialog.indexOf("setOnCloseRequest(");
        int end = dialog.indexOf("setOnHidden(", start);

        // The quiet save on closing would replace a refused skill with its stored version, so the check comes first.
        assertThat(dialog.substring(start, end)).contains("aiSkillsPane.confirmClose()");
        assertThat(dialog.substring(start, end).indexOf("aiSkillsPane.confirmClose()"))
            .isGreaterThan(dialog.substring(start, end).indexOf("aiMemoryPane.confirmClose()"));
    }
}
