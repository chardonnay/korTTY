package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.GlobalSettings;
import de.kortty.shellintegration.CommandStatus;
import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.shellintegration.TerminalNotificationPolicy.Toggles;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BiFunction;
import org.testng.annotations.Test;

/**
 * The long-command notification from the shell's marks to its text: a {@code D} mark after a
 * {@code C} mark hands the command's status, with its exit status and its runtime from {@code C} to
 * {@code D}, out of {@link PaneCommandMarks#record}; nothing else does. The text built from it says
 * how the command ended and how long it ran, and never what the command was.
 */
class CommandFinishedNotificationTest {

    private static final String ESC = "\u001B";
    private static final String A = ESC + "]133;A\u0007";
    private static final String B = ESC + "]133;B\u0007";
    private static final String C = ESC + "]133;C\u0007";
    private static final long SECOND = 1_000_000_000L;

    private static String finished(String status) {
        return ESC + "]133;D" + status + "\u0007";
    }

    @Test
    void aFinishedCommandReportsItsExitStatusAndItsRuntimeFromCToD() throws IOException {
        // A at 0 s, B at 1 s (typing), C at 4 s (Enter), D at 99 s.
        List<CommandStatus> reported = run(new Screen(40, 6, 100),
            A + "$ " + B + "make all\r\n" + C + "building\r\n" + finished(";2") + A + "$ " + B,
            0, SECOND, 4 * SECOND, 99 * SECOND, 100 * SECOND, 101 * SECOND);

        assertThat(reported).hasSize(1);
        CommandStatus status = reported.getFirst();
        assertThat(status.kind()).isEqualTo(CommandStatus.Kind.FAILED);
        assertThat(status.exitStatus()).isEqualTo(2);
        assertWithMessage("from the submitted command (C), not from the prompt or the typing")
            .that(status.runtime()).isEqualTo(Duration.ofSeconds(95));
    }

    @Test
    void aStatuslessEndIsReportedWithoutAnExitStatus() throws IOException {
        List<CommandStatus> reported = run(new Screen(40, 6, 100),
            A + "$ " + B + "sleep 40\r\n" + C + finished(""), 0, 0, 0, 40 * SECOND);
        assertThat(reported).hasSize(1);
        assertThat(reported.getFirst().kind()).isEqualTo(CommandStatus.Kind.NO_STATUS);
        assertThat(reported.getFirst().exitStatus()).isNull();
        assertThat(reported.getFirst().runtime()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void onlyADThatEndsASubmittedCommandIsReported() throws IOException {
        assertWithMessage("an empty command line: the shell sends D without C")
            .that(run(new Screen(40, 6, 100), A + "$ " + B + "\r\n" + finished(";0") + A + "$ " + B))
            .isEmpty();
        assertWithMessage("a second D for the same command")
            .that(run(new Screen(40, 6, 100), A + "$ " + B + "true\r\n" + C + finished(";0") + finished(";0")))
            .hasSize(1);
        assertWithMessage("a D before any prompt").that(run(new Screen(40, 6, 100), finished(";0"))).isEmpty();
    }

    @Test
    void aDOnTheAlternateScreenIsNotReported() throws IOException {
        // A full-screen program prints a fake D: no mark is recorded, so nothing finished.
        assertThat(run(new Screen(40, 6, 100),
            A + "$ " + B + "vim\r\n" + C + ESC + "[?1049h" + finished(";0") + ESC + "[?1049l")).isEmpty();
    }

    @Test
    void theTextNamesTheEndAndTheRuntimeButNeverTheCommand() throws IOException {
        String secret = "export TOKEN=hunter2";
        List<CommandStatus> reported = run(new Screen(60, 6, 100),
            A + "$ " + B + secret + "; ./deploy.sh\r\n" + C + "deploying\r\n" + finished(";0"),
            0, 0, 0, 134 * SECOND);
        CommandStatus status = reported.getFirst();
        Properties english = bundle();
        String runtime = TimestampGutterFormats.forLocale(Locale.ENGLISH, english::getProperty)
            .verboseRuntime(status.runtime());

        String text = TerminalAttentionNotifier.commandFinishedText(status.exitStatus(), runtime, filling(english));

        assertThat(text).isEqualTo("Command finished (exit 0) after 2 min 14 sec.");
        assertThat(text).doesNotContain("hunter2");
        assertThat(text).doesNotContain("deploy");
    }

    @Test
    void eachEndHasItsOwnWording() throws IOException {
        BiFunction<String, Object[], String> english = filling(bundle());
        assertThat(TerminalAttentionNotifier.commandFinishedText(0, "31 sec", english))
            .isEqualTo("Command finished (exit 0) after 31 sec.");
        assertThat(TerminalAttentionNotifier.commandFinishedText(130, "1 h 2 min 3 sec", english))
            .isEqualTo("Command failed (exit 130) after 1 h 2 min 3 sec.");
        assertThat(TerminalAttentionNotifier.commandFinishedText(null, "45 sec", english))
            .isEqualTo("Command finished after 45 sec.");
    }

    @Test
    void theSettingsReachThePolicy() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalBellNotificationsEnabled(true);
        settings.setCodingAgentNotificationsEnabled(false);
        settings.setCommandFinishedNotificationsEnabled(false);
        settings.setCommandFinishedNotificationSeconds(90);
        settings.setRemoteTerminalNotificationsEnabled(false);
        settings.setAiRunToastsEnabled(false);
        assertThat(TerminalAttentionNotifier.toggles(settings))
            .isEqualTo(new Toggles(true, false, false, 90, false, false));
        assertWithMessage("unreadable settings mean a fresh installation's: bell toasts off, long commands on at 30 s, "
                + "programs' notifications on, AI runs on (decision D5)")
            .that(TerminalAttentionNotifier.toggles(null)).isEqualTo(new Toggles(false, true, true, 30, true, true));
    }

    /**
     * Runs {@code output} through the wrapper into a pane's marks and returns what
     * {@link PaneCommandMarks#record} reported; the n-th mark arrives at {@code nanos[n]} (later ones
     * at the last of them).
     */
    private static List<CommandStatus> run(Screen screen, String output, long... nanos) throws IOException {
        PaneCommandMarks marks = new PaneCommandMarks(screen.buffer);
        screen.buffer.addModelListener(marks::observe);
        List<CommandStatus> reported = new ArrayList<>();
        int[] next = {0};
        screen.run(new ShellIntegrationTtyConnector(ScriptedConnector.inChunksOf(output, 5), event -> {
            if (!PaneCommandMarks.isMark(event)) {
                return;
            }
            long at = nanos.length == 0 ? 0L : nanos[Math.min(next[0]++, nanos.length - 1)];
            CommandStatus status = marks.record(event, screen.terminal, at);
            if (status != null) {
                assertWithMessage("only a D reports").that(event).isInstanceOf(ShellIntegrationEvent.CommandFinished.class);
                reported.add(status);
            }
        }));
        return reported;
    }

    /** The translations as {@code I18n.get(key, args)} gives them: plain replacement of {@code {n}}. */
    private static BiFunction<String, Object[], String> filling(Properties bundle) {
        return (key, args) -> {
            String text = bundle.getProperty(key, key);
            for (int i = 0; i < args.length; i++) {
                text = text.replace("{" + i + "}", String.valueOf(args[i]));
            }
            return text;
        };
    }

    private static Properties bundle() throws IOException {
        try (InputStream in = CommandFinishedNotificationTest.class.getClassLoader()
                .getResourceAsStream("i18n/messages.properties")) {
            assertThat(in).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
