package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The connection's memory dialog asks before unsaved Markdown is discarded, on the close button and on the window's close
 * request, as the AI manager does.
 */
class AiMemoryDialogCloseWiringSourceScanTest {

    @Test
    void theCloseRequestConsultsTheMemoryPaneBeforeTheDialogCloses() throws IOException {
        String dialog = Files.readString(Path.of("src/main/java/de/kortty/ui/AiMemoryDialog.java"));
        int start = dialog.indexOf("setOnCloseRequest(");
        int end = dialog.indexOf("setOnHidden(", start);

        assertThat(start).isAtLeast(0);
        assertThat(dialog.substring(start, end)).contains("pane.confirmClose()");
        assertThat(dialog.substring(start, end)).contains("event.consume()");
    }
}
