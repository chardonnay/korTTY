package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.ui.OscEmulatorHarness.Position;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * A mark is worth something only if it lands where the shell put it. The event handler runs on the
 * emulator thread when the event is delivered and reads the cursor there; that cursor must equal
 * the one of a fresh terminal that interpreted exactly the output before the mark, whatever the
 * chunking, at the last column and after the scrollback dropped lines.
 *
 * <p>This pins what {@link ShellIntegrationTtyConnector} relies on in SithTermFX: its data stream
 * asks the connector for more only when every char it was given has been interpreted
 * (refill-on-empty), and no escape sequence reads ahead. A SithTermFX that read ahead would fail
 * here on its version bump.
 */
class ShellIntegrationEmulatorOrderTest {

    private static final String ESC = "\u001B";
    private static final String A = ESC + "]133;A\u0007";
    private static final String B = ESC + "]133;B" + ESC + "\\";
    private static final String C = ESC + "]133;C\u009C";
    private static final String D0 = ESC + "]133;D;0\u0007";
    private static final Map<String, ShellIntegrationEvent> MARKS = Map.of(
        A, new ShellIntegrationEvent.PromptStart(),
        B, new ShellIntegrationEvent.CommandStart(),
        C, new ShellIntegrationEvent.OutputStart(),
        D0, new ShellIntegrationEvent.CommandFinished(0));

    @DataProvider
    Object[][] chunkSizes() {
        return new Object[][] {{1}, {2}, {3}, {5}, {8}, {13}, {1024}};
    }

    @Test(dataProvider = "chunkSizes")
    void aPromptCycleSeesEachMarkWhereTheShellPutIt(int chunkSize) throws IOException {
        assertMarksSeeTheirPosition(chunkSize, 20, 5, 50, List.of(
            "Last login: today\r\n", A, ESC + "[1;32muser@host" + ESC + "[0m:~$ ", B, "ls -la /var/log",
            "\r\n", C, "total 42\r\ndrwxr-xr-x  12 root root 4096 syslog\r\n", D0, A, "user@host:~$ ", B));
    }

    @Test(dataProvider = "chunkSizes")
    void marksAtTheLastColumnSeeTheWrapState(int chunkSize) throws IOException {
        assertMarksSeeTheirPosition(chunkSize, 20, 5, 50, List.of(
            "x".repeat(20), B, "after\r\n",
            "y".repeat(19), C, "z", D0, "\r\n",
            "w".repeat(21), A, "\r\n",
            "v".repeat(40), B));
    }

    @Test(dataProvider = "chunkSizes")
    void marksAfterScrollbackTrimsSeeTheTrimmedBuffer(int chunkSize) throws IOException {
        List<String> parts = new ArrayList<>();
        for (int line = 0; line < 40; line++) {
            if (line % 3 == 0) {
                parts.add(A);
                parts.add("$ ");
                parts.add(B);
                parts.add("cmd " + line + "\r\n");
                parts.add(C);
            }
            parts.add("output line " + line + "\r\n");
            if (line % 3 == 2) {
                parts.add(D0);
            }
        }
        assertMarksSeeTheirPosition(chunkSize, 20, 4, 5, parts);
    }

    @Test(dataProvider = "chunkSizes")
    void marksOnTheAlternateScreenSeeTheAlternateCursor(int chunkSize) throws IOException {
        assertMarksSeeTheirPosition(chunkSize, 20, 5, 50, List.of(
            "before\r\n", A, ESC + "[?1049h" + ESC + "[3;5H", B, "full screen", C,
            ESC + "[?1049l", D0, "after"));
    }

    private static void assertMarksSeeTheirPosition(int chunkSize, int columns, int rows, int maxHistory,
                                                    List<String> parts) throws IOException {
        StringBuilder output = new StringBuilder();
        List<Position> expected = new ArrayList<>();
        List<ShellIntegrationEvent> expectedEvents = new ArrayList<>();
        for (String part : parts) {
            ShellIntegrationEvent mark = MARKS.get(part);
            if (mark != null) {
                Screen reference = new Screen(columns, rows, maxHistory);
                reference.interpret(output.toString());
                expected.add(reference.cursor());
                expectedEvents.add(mark);
            }
            output.append(part);
        }

        Screen live = new Screen(columns, rows, maxHistory);
        List<Position> seen = new ArrayList<>();
        List<ShellIntegrationEvent> events = new ArrayList<>();
        live.run(new ShellIntegrationTtyConnector(
            ScriptedConnector.inChunksOf(output.toString(), chunkSize),
            event -> {
                events.add(event);
                seen.add(live.cursor());
            }));

        assertWithMessage("chunks of %s", chunkSize).that(events).isEqualTo(expectedEvents);
        assertWithMessage("chunks of %s: cursor (scrollback lines, x, y) at each mark", chunkSize)
            .that(seen).isEqualTo(expected);
    }
}
