package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/** The close request of the AI manager asks before unsaved Markdown of the Memory tab is discarded. */
class AiMemoryCloseWiringSourceScanTest {

    @Test
    void theCloseRequestConsultsTheMemoryPaneBeforeTheDialogCloses() throws IOException {
        String dialog = Files.readString(Path.of("src/main/java/de/kortty/ui/AiManagerDialog.java"));
        int start = dialog.indexOf("setOnCloseRequest(");
        int end = dialog.indexOf("setOnHidden(", start);

        assertThat(start).isAtLeast(0);
        assertThat(dialog.substring(start, end)).contains("aiMemoryPane.confirmClose()");
    }
}
