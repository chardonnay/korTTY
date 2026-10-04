package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.DesktopNotifierBackend;
import de.kortty.core.swarm.SwarmModels.SwarmAgentState;
import de.kortty.core.swarm.SwarmModels.SwarmPhase;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels.Phase;
import de.kortty.shellintegration.TerminalNotificationPolicy;
import de.kortty.shellintegration.TerminalNotificationPolicy.AiRunEvent;
import de.kortty.shellintegration.TerminalNotificationPolicy.Decision;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * korTTY's own AI runs, the terminal agent and the AI swarm, notify on the desktop when they finish,
 * fail or wait for the user in a tab the user is not looking at (decision D5): the notification is
 * titled {@code korTTY · <tab>} and its body is a fixed text that never carries the prompt, a command
 * or output. The run's worker threads post through the JavaFX thread. Checked with a fake desktop
 * backend and a seen oracle that the test controls; no toolkit starts.
 */
class TerminalAttentionNotifierAiRunTest {

    /** A desktop backend that records what it was asked to show. */
    private static final class RecordingBackend implements DesktopNotifierBackend {
        final List<String[]> shown = new ArrayList<>();

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public void notify(String title, String body) {
            shown.add(new String[] {title, body});
        }
    }

    /** Runs every task at once on the calling thread, so a notification is delivered before notify returns. */
    private static final class InlineExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private long[] clock;
    private RecordingBackend backend;
    private GlobalSettings settings;
    private TerminalAttentionNotifier notifier;
    private BiFunction<String, Object[], String> english;
    private Properties bundle;

    @BeforeMethod
    void freshNotifier() throws IOException {
        clock = new long[] {1_000_000L};
        backend = new RecordingBackend();
        DesktopNotifier desktop = new DesktopNotifier(backend, new InlineExecutor());
        settings = new GlobalSettings();
        long[] time = clock;
        notifier = new TerminalAttentionNotifier(new TerminalNotificationPolicy(() -> time[0]), tab -> false,
            () -> settings, () -> desktop, () -> null, widget -> null, widget -> false, tab -> false);
        bundle = load("messages.properties");
        english = (key, args) -> {
            String value = bundle.getProperty(key);
            assertWithMessage("missing English text %s", key).that(value).isNotNull();
            return value;
        };
    }

    @Test
    void anUnseenTabGetsItsMarkAndANotificationWithAFixedText() {
        Object tab = new Object();
        List<String> marks = new ArrayList<>();
        Decision decision = notifier.onAiRun(tab, false, "prod-db", AiRunEvent.NEEDS_APPROVAL, marks::add, english);

        assertThat(decision).isEqualTo(new Decision(true, true));
        assertThat(backend.shown).hasSize(1);
        assertThat(backend.shown.getFirst()[0]).isEqualTo("korTTY · prod-db");
        assertThat(backend.shown.getFirst()[1]).isEqualTo("The AI run in this tab is waiting for your approval.");
        assertThat(marks).containsExactly("The AI run in this tab is waiting for your approval.");
    }

    @Test
    void theBodyNeverCarriesTheRunsPromptCommandOrOutput() {
        String secretCommand = "mysql -u root -phunter2 -e 'DROP DATABASE prod'";
        for (AiRunEvent event : AiRunEvent.values()) {
            String body = TerminalAttentionNotifier.aiRunText(event, english);
            assertWithMessage("%s is one fixed sentence without placeholders", event).that(body).doesNotContain("{");
            assertThat(body).doesNotContain("hunter2");
            assertThat(body).doesNotContain(secretCommand);
        }
        notifier.onAiRun(new Object(), false, "db", AiRunEvent.FAILED, null, english);
        notifier.onAiRun(new Object(), false, "db", AiRunEvent.NEEDS_PASSWORD, null, english);
        notifier.onAiRun(new Object(), false, "db", AiRunEvent.FINISHED, null, english);
        List<String> bodies = backend.shown.stream().map(shown -> shown[1]).toList();
        assertThat(bodies).containsExactly(
            "The AI run in this tab stopped without finishing.",
            "The AI run in this tab is waiting for a password.",
            "The AI run in this tab finished.").inOrder();
    }

    @Test
    void aSeenTabGetsNothing() {
        List<String> marks = new ArrayList<>();
        assertThat(notifier.onAiRun(new Object(), true, "db", AiRunEvent.FINISHED, marks::add, english))
            .isEqualTo(Decision.NONE);
        assertThat(backend.shown).isEmpty();
        assertThat(marks).isEmpty();
    }

    @Test
    void theSettingSwitchesTheNotificationOffButKeepsTheMark() {
        settings.setAiRunToastsEnabled(false);
        List<String> marks = new ArrayList<>();
        assertThat(notifier.onAiRun(new Object(), false, "db", AiRunEvent.NEEDS_APPROVAL, marks::add, english))
            .isEqualTo(new Decision(true, false));
        assertThat(backend.shown).isEmpty();
        assertThat(marks).hasSize(1);
    }

    @Test
    void repeatedApprovalsAreThrottledPerTab() {
        Object tab = new Object();
        notifier.onAiRun(tab, false, "swarm", AiRunEvent.NEEDS_APPROVAL, null, english);
        clock[0] += 3_000;
        notifier.onAiRun(tab, false, "swarm", AiRunEvent.NEEDS_APPROVAL, null, english);
        assertThat(backend.shown).hasSize(1);
        clock[0] += 7_000;
        notifier.onAiRun(tab, false, "swarm", AiRunEvent.NEEDS_APPROVAL, null, english);
        assertThat(backend.shown).hasSize(2);
    }

    @Test
    void theTitleIsCleanedOfControlCharactersAndFallsBackToTheAppName() {
        notifier.onAiRun(new Object(), false, "evil\u001B]0;x\u0007‮tab", AiRunEvent.FINISHED, null, english);
        notifier.onAiRun(new Object(), false, "  ", AiRunEvent.FINISHED, null, english);
        assertThat(backend.shown.get(0)[0]).startsWith("korTTY · ");
        assertThat(backend.shown.get(0)[0]).doesNotContain("\u001B");
        assertThat(backend.shown.get(0)[0]).doesNotContain("‮");
        assertThat(backend.shown.get(1)[0]).isEqualTo("korTTY");
    }

    @Test
    void anAgentRunsEndMapsToItsEvent() {
        assertThat(TerminalAttentionNotifier.agentEndEvent(Phase.DONE)).isEqualTo(AiRunEvent.FINISHED);
        assertThat(TerminalAttentionNotifier.agentEndEvent(Phase.FAILED)).isEqualTo(AiRunEvent.FAILED);
        assertThat(TerminalAttentionNotifier.agentEndEvent(Phase.BLOCKED)).isEqualTo(AiRunEvent.FAILED);
        assertWithMessage("the user cancelled it: they know").that(TerminalAttentionNotifier.agentEndEvent(Phase.CANCELLED))
            .isNull();
        assertThat(TerminalAttentionNotifier.agentEndEvent(Phase.RUNNING_COMMANDS)).isNull();
        assertThat(TerminalAttentionNotifier.agentEndEvent(null)).isNull();
    }

    @Test
    void aSwarmsEndMapsToItsEvent() {
        List<SwarmAgentState> someDone = List.of(SwarmAgentState.FAILED, SwarmAgentState.DONE);
        assertThat(TerminalAttentionNotifier.swarmEndEvent(false, SwarmPhase.DONE, true, someDone))
            .isEqualTo(AiRunEvent.FINISHED);
        assertThat(TerminalAttentionNotifier.swarmEndEvent(false, SwarmPhase.DONE, true, List.of()))
            .isEqualTo(AiRunEvent.FINISHED);
        assertWithMessage("no agent got done")
            .that(TerminalAttentionNotifier.swarmEndEvent(false, SwarmPhase.DONE, true,
                List.of(SwarmAgentState.FAILED, SwarmAgentState.SKIPPED)))
            .isEqualTo(AiRunEvent.FAILED);
        assertThat(TerminalAttentionNotifier.swarmEndEvent(false, SwarmPhase.DONE, false, someDone))
            .isEqualTo(AiRunEvent.FAILED);
        assertThat(TerminalAttentionNotifier.swarmEndEvent(false, SwarmPhase.FAILED, true, someDone))
            .isEqualTo(AiRunEvent.FAILED);
        assertWithMessage("cancelled by the user").that(
            TerminalAttentionNotifier.swarmEndEvent(false, SwarmPhase.CANCELLED, true, someDone)).isNull();
        assertWithMessage("restarted by the user").that(
            TerminalAttentionNotifier.swarmEndEvent(true, SwarmPhase.DONE, true, someDone)).isNull();
    }

    @Test
    void theRunsPostTheirEventsFromTheirWorkerThreads() throws IOException {
        String mainWindow = source("MainWindow.java");
        assertWithMessage("the agent notifies before it blocks on the approval")
            .that(mainWindow).contains("TerminalAttentionNotifier.postAiRun(terminalTab,\n"
                + "                            TerminalNotificationPolicy.AiRunEvent.NEEDS_APPROVAL);\n"
                + "                        return activityPanel.requestApproval(runId, approval);");
        assertThat(mainWindow).contains("TerminalAttentionNotifier.postAiRun(terminalTab,\n"
            + "                            TerminalNotificationPolicy.AiRunEvent.NEEDS_PASSWORD);\n"
            + "                        return activityPanel.requestPassword(runId, passwordRequest);");
        assertThat(mainWindow).contains("TerminalAttentionNotifier.agentEndEvent(state.phase());");
        assertThat(mainWindow).contains(
            "TerminalAttentionNotifier.postAiRun(terminalTab, TerminalNotificationPolicy.AiRunEvent.FAILED);");

        String swarm = source("SwarmAgentTab.java");
        assertThat(swarm).contains("TerminalAttentionNotifier.postAiRun(this, TerminalAttentionNotifier.swarmEndEvent(");
        assertThat(swarm).contains("TerminalAttentionNotifier.postAiRun(this,\n"
            + "            de.kortty.shellintegration.TerminalNotificationPolicy.AiRunEvent.NEEDS_APPROVAL);\n"
            + "        return SwarmApprovalDialogSupport.requestBlocking(");

        String notifierSource = source("TerminalAttentionNotifier.java");
        assertWithMessage("worker threads hop to the JavaFX thread before touching the notifier or the oracle")
            .that(notifierSource).contains("Platform.runLater(() -> shared().onAiRun(tab, event));");
    }

    @Test
    void theSettingsPageHasTheToggle() throws IOException {
        String dialog = source("SettingsDialog.java");
        assertThat(dialog).contains(
            "aiRunToastsCheck.setSelected(globalSettings == null || globalSettings.isAiRunToastsEnabled());");
        assertThat(dialog).contains("globalSettings.setAiRunToastsEnabled(aiRunToastsCheck.isSelected());");
        assertThat(dialog).contains("terminalGrid.add(aiRunToastsCheck, 0, terminalRow++, 2, 1);");
    }

    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui", name), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = TerminalAttentionNotifierAiRunTest.class.getResourceAsStream("/i18n/" + name)) {
            assertThat(in).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
