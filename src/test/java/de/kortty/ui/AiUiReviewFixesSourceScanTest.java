package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The AI manager's review fixes: a press acknowledges a korTTY skill even when it is already selected, the memory rows do
 * not ask for the unwrapped text width, the statistics rows grow with their wrapped text, both AI combos of the connection
 * editor share one width policy, and the scrolling AI manager tabs keep the focused control in view.
 */
class AiUiReviewFixesSourceScanTest {

    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/" + name + ".java")).replace("\r\n", "\n");
    }

    @Test
    void aPressAcknowledgesTheKorttySkillEvenWhenItIsAlreadySelected() throws IOException {
        String pane = source("AiSkillsPane");

        // A click on the selected row changes no selection, so the selection listener alone would leave the badge.
        assertThat(pane).contains("addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED");
        assertThat(pane).contains("acknowledgeKorttyChanges(getItem())");
    }

    @Test
    void memoryRowsDoNotRequestTheUnwrappedTextWidth() throws IOException {
        assertThat(source("AiMemoryPane")).contains("protected double computePrefWidth(double height)");
    }

    @Test
    void statisticsCellsMeasureTheirWrappedTextAtTheColumnWidth() throws IOException {
        String statistics = source("AiSkillStatisticsDialog");

        assertThat(statistics).contains("protected double computePrefHeight(double width)");
        assertThat(statistics).contains("getTableColumn().getWidth()");
    }

    @Test
    void theAiProfileAndDesignCombosShareOneWidthPolicy() throws IOException {
        String dialog = source("ConnectionEditDialog");

        assertThat(dialog).contains("aiProfileCombo.setMaxWidth(Double.MAX_VALUE)");
        assertThat(dialog).contains("aiAgentDesignCombo.setMaxWidth(Double.MAX_VALUE)");
    }

    @Test
    void theScrollingAiManagerTabsRevealTheFocusedControl() throws IOException {
        String manager = source("AiManagerDialog");
        String call = "AiManagerSections.fillingScroller(";
        int scrollers = 0;
        for (int at = manager.indexOf(call); at >= 0; at = manager.indexOf(call, at + call.length())) {
            scrollers++;
        }

        // The skills and memory tabs scroll through the shared scroller, which reveals the focused control.
        assertWithMessage("scrolling tabs built by the shared scroller").that(scrollers).isEqualTo(2);
        String sections = source("AiManagerSections");
        int scroller = sections.indexOf("static ScrollPane fillingScroller(Region content)");
        assertThat(scroller).isAtLeast(0);
        assertThat(sections.substring(scroller, sections.indexOf("\n    }\n", scroller)))
            .contains("revealFocusedControl(scroller)");
        assertThat(sections).contains("scene.focusOwnerProperty()");
    }
}
