package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.RemoteNotificationText;
import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.function.BiFunction;
import org.testng.annotations.Test;

/**
 * A program's notification from the pane's output to what korTTY shows: {@code OSC 9} and
 * {@code OSC 777;notify} are taken out of real output by {@link ShellIntegrationTtyConnector}, never
 * reach the screen, and arrive cleaned ({@link RemoteNotificationText}); the desktop notification is
 * titled with korTTY and the tab's name, and the tab's tooltip names the notification in one short
 * line. The remote {@code korTTY-agent} sequence is never one of them (UX-01).
 */
class RemoteNotificationFlowTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";
    private static final String ST = ESC + "\\";

    @Test
    void notificationsAreTakenOutOfTheOutputAndArriveCleaned() throws IOException {
        Screen screen = new Screen(60, 4, 100);
        String output = "before" + ESC + "]9;Build done; 3 warnings" + BEL + "-"
            + ESC + "]777;notify;Claude Code" + ESC + "[31m;Waiting ‮for‬ you\r\nnow" + ST
            + ESC + "]9;4;1;50" + BEL + "after";

        List<RemoteNotificationText> shown = run(screen, output);

        assertThat(shown).containsExactly(RemoteNotificationText.of(null, "Build done; 3 warnings"),
            RemoteNotificationText.of("Claude Code[31m", "Waiting for you now")).inOrder();
        assertThat(shown.get(1).text()).isEqualTo("Claude Code[31m: Waiting for you now");
        assertWithMessage("neither the notifications nor the ConEmu progress report reach the screen")
            .that(screen.buffer.getLine(0).getText().strip()).isEqualTo("before-after");
    }

    @Test
    void aNotificationWithNothingVisibleIsDropped() throws IOException {
        assertThat(run(new Screen(40, 4, 100), ESC + "]9;" + ESC + "‮\u0007")).isEmpty();
        assertThat(run(new Screen(40, 4, 100), ESC + "]777;notify; ;‏" + BEL)).isEmpty();
    }

    @Test
    void theRemoteKorttyAgentSequenceIsNeverANotification() throws IOException {
        String agent = ESC + "]777;korTTY-agent;execute;"
            + Base64.getEncoder().encodeToString("/tmp".getBytes(StandardCharsets.UTF_8)) + ";"
            + Base64.getEncoder().encodeToString("(root=true) id".getBytes(StandardCharsets.UTF_8)) + BEL;
        List<ShellIntegrationEvent> events = new ArrayList<>();
        new Screen(40, 4, 100).run(new ShellIntegrationTtyConnector(ScriptedConnector.inChunksOf(agent + "x", 7),
            events::add));
        assertThat(events).isEmpty();
    }

    @Test
    void theDesktopNotificationIsTitledWithKorttyAndTheTab() {
        RemoteNotificationText notification = RemoteNotificationText.of("Claude Code", "Claude needs your permission");
        assertThat(TerminalAttentionNotifier.toastTitle("web-01")).isEqualTo("korTTY · web-01");
        assertWithMessage("the program's title goes into the text, below korTTY's own title")
            .that(notification.text()).isEqualTo("Claude Code: Claude needs your permission");
        assertWithMessage("a server-set tab name cannot spoof the title either")
            .that(TerminalAttentionNotifier.toastTitle("‮web-01\u0007")).isEqualTo("korTTY · web-01");
    }

    @Test
    void theTooltipNamesTheNotificationInOneShortLine() throws IOException {
        BiFunction<String, Object[], String> english = filling(bundle());
        RemoteNotificationText notification = RemoteNotificationText.of("Codex", "Approval needed");
        assertThat(TerminalAttentionNotifier.remoteTooltip(notification, english))
            .isEqualTo("A program in this tab sent a notification: Codex: Approval needed");

        RemoteNotificationText longOne = RemoteNotificationText.of("t".repeat(80), "b".repeat(200));
        String tooltip = TerminalAttentionNotifier.remoteTooltip(longOne, english);
        String prefix = "A program in this tab sent a notification: ";
        assertThat(tooltip).startsWith(prefix);
        assertThat(tooltip.substring(prefix.length())).hasLength(TerminalAttentionNotifier.MAX_REMOTE_TOOLTIP_CHARS);
        assertThat(tooltip).endsWith("…");
    }

    /** Runs {@code output} through the wrapper in small reads and returns the notifications it carried, cleaned. */
    private static List<RemoteNotificationText> run(Screen screen, String output) throws IOException {
        List<RemoteNotificationText> shown = new ArrayList<>();
        screen.run(new ShellIntegrationTtyConnector(ScriptedConnector.inChunksOf(output, 5), event -> {
            if (event instanceof ShellIntegrationEvent.RemoteNotification notification) {
                RemoteNotificationText text = RemoteNotificationText.of(notification);
                if (text != null) {
                    shown.add(text);
                }
            }
        }));
        return shown;
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
        try (InputStream in = RemoteNotificationFlowTest.class.getClassLoader()
                .getResourceAsStream("i18n/messages.properties")) {
            assertThat(in).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
