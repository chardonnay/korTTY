package de.kortty.ui;

import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.FocusOracle;
import de.kortty.codingagent.PaneLocation;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.DesktopNotifierBackend;
import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static com.google.common.truth.Truth.assertThat;

/** The FX-free parts of the bridge: notification text, the title badge text and the seen rule. */
public class CodingAgentUiBridgeTest {

    /** Renders "key[arg0|arg1...]" so a test can see both the key and the arguments that were passed. */
    private static final BiFunction<String, Object[], String> KEYS = (key, args) -> {
        StringBuilder text = new StringBuilder(key);
        if (args != null && args.length > 0) {
            text.append('[');
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    text.append('|');
                }
                text.append(args[i]);
            }
            text.append(']');
        }
        return text.toString();
    };

    private static final BiFunction<String, Object[], String> ENGLISH = (key, args) -> switch (key) {
        case "codingAgent.notify.blocked.title" -> args[0] + " needs a decision";
        case "codingAgent.notify.done.title" -> args[0] + " finished";
        case "codingAgent.notify.body" -> args[0] + " › " + args[1];
        case "codingAgent.title.badge" -> "(" + args[0] + ") " + args[1];
        default -> key;
    };

    private static CodingAgentEntry entry(CodingAgentState state, String alias, String evidence) {
        DetectionResult detection = DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "rule", evidence);
        return new CodingAgentEntry(new PaneRef("tab-1", "pane-1"), CodingAgentKind.CLAUDE_CODE, state, detection,
            null, 1_000L, 1_000L, alias, state == CodingAgentState.DONE);
    }

    @Test
    public void blockedNotificationCarriesAgentNameLocationCwdAndEvidence() {
        PaneLocation location = new PaneLocation(0, 1, "api", 1, 2, "/home/me/proj/api");
        CodingAgentUiBridge.Notification notification = CodingAgentUiBridge.buildNotification(
            entry(CodingAgentState.BLOCKED, null, "Do you want to proceed?\nsecond line"), CodingAgentState.BLOCKED,
            location, ENGLISH);

        assertThat(notification.title()).isEqualTo("Claude Code needs a decision");
        assertThat(notification.body()).isEqualTo("api › Pane 2 › /home/me/proj/api\nDo you want to proceed?");
    }

    @Test
    public void doneNotificationUsesTheAliasAndSkipsMissingParts() {
        CodingAgentUiBridge.Notification notification = CodingAgentUiBridge.buildNotification(
            entry(CodingAgentState.DONE, "refactor bot", null), CodingAgentState.DONE,
            new PaneLocation(0, 1, "shell", 0, 1, null), KEYS);

        assertThat(notification.title()).isEqualTo("codingAgent.notify.done.title[refactor bot]");
        assertThat(notification.body()).isEqualTo("shell");

        CodingAgentUiBridge.Notification unlocated = CodingAgentUiBridge.buildNotification(
            entry(CodingAgentState.DONE, null, "  All done  "), CodingAgentState.DONE, null, KEYS);
        assertThat(unlocated.body()).isEqualTo("All done");
    }

    @Test
    public void notificationSinkDeliversThroughTheNotifier() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        DesktopNotifierBackend backend = new DesktopNotifierBackend() {
            @Override
            public boolean isSupported() {
                return true;
            }

            @Override
            public void notify(String notificationTitle, String notificationBody) {
                title.set(notificationTitle);
                body.set(notificationBody);
                delivered.countDown();
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DesktopNotifier notifier = new DesktopNotifier(backend, executor);
        CodingAgentRegistry registry = CodingAgentRegistry.forTests(FocusOracle.NEVER, () -> 5_000L);
        CodingAgentUiBridge bridge = new CodingAgentUiBridge(registry, null, null, notifier, List::of,
            GlobalSettings::new, ENGLISH);
        try {
            bridge.notificationSink().notify(entry(CodingAgentState.BLOCKED, null, "(y/n)"), CodingAgentState.BLOCKED,
                false);
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            // No window is open, so the pane cannot be located: the body is the evidence alone.
            assertThat(title.get()).isEqualTo("Claude Code needs a decision");
            assertThat(body.get()).isEqualTo("(y/n)");
        } finally {
            notifier.close();
        }
    }

    @Test
    public void titleBadgeShowsTheCountAndRestoresThePlainNameAtZero() {
        assertThat(CodingAgentUiBridge.titleFor(2, "KorTTY", ENGLISH)).isEqualTo("(2) KorTTY");
        assertThat(CodingAgentUiBridge.titleFor(0, "KorTTY", ENGLISH)).isEqualTo("KorTTY");
        assertThat(CodingAgentUiBridge.titleFor(-3, "KorTTY", ENGLISH)).isEqualTo("KorTTY");
        assertThat(CodingAgentUiBridge.titleFor(1, "KorTTY", KEYS)).isEqualTo("codingAgent.title.badge[1|KorTTY]");
    }

    @Test
    public void seenRequiresForegroundWindowSelectedTabAndFocusedWidget() {
        assertThat(CodingAgentUiBridge.seenFor(true, true, true)).isTrue();
        assertThat(CodingAgentUiBridge.seenFor(false, true, true)).isFalse();
        assertThat(CodingAgentUiBridge.seenFor(true, false, true)).isFalse();
        assertThat(CodingAgentUiBridge.seenFor(true, true, false)).isFalse();
        assertThat(CodingAgentUiBridge.seenFor(false, false, false)).isFalse();
    }

    @Test
    public void portsAnswerSafelyWithoutAnyWindow() {
        CodingAgentRegistry registry = CodingAgentRegistry.forTests(FocusOracle.NEVER, () -> 5_000L);
        CodingAgentUiBridge bridge = new CodingAgentUiBridge(registry, null, null, null, List::of,
            GlobalSettings::new, ENGLISH);
        PaneRef pane = new PaneRef("tab-1", "pane-1");

        assertThat(bridge.isSeen(pane)).isFalse();
        assertThat(bridge.isAnyWindowFocused()).isFalse();
        assertThat(bridge.locate(pane)).isEmpty();
        assertThat(bridge.connectorFor(pane)).isEmpty();
        assertThat(bridge.isBracketedPasteEnabled(pane)).isFalse();
        assertThat(bridge.wouldHostShortcutIntercept("agent do it")).isFalse();
        assertThat(bridge.hostShortcutCommandName()).isEqualTo("agent");
        assertThat(bridge.focus(pane)).isFalse();
        assertThat(bridge.currentPane()).isEmpty();
        assertThat(bridge.isTickTimerRunning()).isFalse();
    }
}
