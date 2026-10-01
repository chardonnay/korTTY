package de.kortty.core.headless;

import de.kortty.core.TerminalRecordingScreenSnapshot;
import de.kortty.core.TerminalRecordingStyleRun;
import de.kortty.core.TerminalScreenRenderer;
import org.testng.annotations.Test;

import java.awt.image.BufferedImage;

import static com.google.common.truth.Truth.assertThat;

class HeadlessTerminalTest {

    @Test
    void interpretsCursorMovesAndClearsLikeATerminal() {
        try (HeadlessTerminal terminal = new HeadlessTerminal(40, 5)) {
            terminal.feed("first line\r\nsecond");
            terminal.feed("\u001b[2J\u001b[H");          // clear + home, split over two chunks
            terminal.feed("\u001b[3;5Hat row 3");      // absolute cursor move
            terminal.awaitProcessed(2_000);

            String[] lines = terminal.screenText().split("\n", -1);
            assertThat(lines[0].strip()).isEmpty();
            assertThat(lines[2]).startsWith("    at row 3");
            assertThat(terminal.screenText()).doesNotContain("first line");
            assertThat(terminal.changeCount()).isGreaterThan(0L);
        }
    }

    @Test
    void capturesColorsAsStyleRuns() {
        try (HeadlessTerminal terminal = new HeadlessTerminal(40, 3)) {
            terminal.feed("\u001b[31mred\u001b[0m plain");
            terminal.awaitProcessed(2_000);

            TerminalRecordingScreenSnapshot snapshot = terminal.snapshot();
            assertThat(snapshot.columns()).isEqualTo(40);
            assertThat(snapshot.rows()).isEqualTo(3);
            TerminalRecordingStyleRun red = snapshot.styleRuns().stream()
                .filter(run -> run.text().startsWith("red")).findFirst().orElseThrow();
            assertThat(red.foreground()).isEqualTo("#CD0000");
        }
    }

    @Test
    void rendersTheScreenIntoAnImage() throws Exception {
        try (HeadlessTerminal terminal = new HeadlessTerminal(20, 4)) {
            terminal.feed("\u001b[42m  \u001b[0m ok");
            terminal.awaitProcessed(2_000);

            BufferedImage image = TerminalScreenRenderer.render(terminal.snapshot(), true);
            byte[] png = TerminalScreenRenderer.renderPng(terminal.snapshot(), true);

            assertThat(image.getWidth()).isGreaterThan(100);
            assertThat(image.getHeight()).isGreaterThan(40);
            assertThat(png.length).isGreaterThan(100);
            // the green background run is drawn into the top-left cell area
            int rgb = image.getRGB(40, 34) & 0xFFFFFF;
            assertThat(rgb).isNotEqualTo(0x000000);
        }
    }
}
