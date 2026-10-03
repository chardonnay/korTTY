package de.kortty.core.headless;

import de.kortty.core.TerminalRecordingScreenSnapshot;
import de.kortty.core.TerminalRecordingStyleRun;
import de.kortty.core.TerminalScreenRenderer;
import org.testng.annotations.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

class HeadlessTerminalTest {

    @Test
    void interpretsCursorMovesAndClearsLikeATerminal() {
        try (HeadlessTerminal terminal = new HeadlessTerminal(40, 5)) {
            terminal.feed("first line\r\nsecond");
            terminal.feed("\u001b[2J\u001b[H");          // clear + home, split over two chunks
            terminal.feed("\u001b[3;5Hat row 3");      // absolute cursor move
            assertThat(terminal.awaitProcessed(2_000)).isTrue();

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
            assertThat(terminal.awaitProcessed(2_000)).isTrue();

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
            assertThat(terminal.awaitProcessed(2_000)).isTrue();

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

    @Test
    void awaitProcessedWaitsForAnEmulatorThatIsSlowToInterpretTheLastChunk() {
        // the emulator has taken the chunk but is descheduled before interpreting it, as on a loaded CI runner
        Runnable slowEmulator = () -> sleep(150);
        try (HeadlessTerminal terminal = new HeadlessTerminal(40, 3, null, slowEmulator)) {
            terminal.feed("\u001b[31mred\u001b[0m plain");
            assertThat(terminal.awaitProcessed(5_000)).isTrue();

            assertThat(terminal.screenText()).contains("red plain");
            assertThat(terminal.snapshot().styleRuns().stream()
                .anyMatch(run -> run.text().startsWith("red") && "#CD0000".equals(run.foreground()))).isTrue();
        }
    }

    @Test
    void awaitProcessedReportsWhenTheEmulatorDidNotCatchUpInTime() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Runnable stalledEmulator = () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try (HeadlessTerminal terminal = new HeadlessTerminal(40, 3, null, stalledEmulator)) {
            try {
                terminal.feed("stalled");
                assertThat(terminal.awaitProcessed(50)).isFalse();
                assertThat(terminal.screenText()).doesNotContain("stalled");
            } finally {
                release.countDown();
            }
            assertThat(terminal.awaitProcessed(2_000)).isTrue();
            assertThat(terminal.screenText()).contains("stalled");
        }
    }

    @Test
    void awaitProcessedShowsTheLastOfManyChunksImmediately() {
        try (HeadlessTerminal terminal = new HeadlessTerminal(40, 5)) {
            for (int round = 0; round < 300; round++) {
                terminal.feed("\u001b[2J\u001b[H");
                for (int line = 0; line < 40; line++) {
                    terminal.feed("\u001b[3" + (line % 8) + "mline " + line + "\u001b");   // sequence split over two chunks
                    terminal.feed("[0m\r\n");
                }
                String marker = "done-" + round;
                terminal.feed("\u001b[1;32m" + marker + "\u001b[0m");
                assertThat(terminal.awaitProcessed(5_000)).isTrue();

                assertThat(terminal.screenText()).contains(marker);
            }
        }
    }

    @Test
    void awaitProcessedReturnsAtOnceWhenNothingWasFed() {
        try (HeadlessTerminal terminal = new HeadlessTerminal(40, 3)) {
            assertThat(terminal.awaitProcessed(0)).isTrue();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
