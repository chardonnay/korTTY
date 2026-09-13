package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.DesktopNotifierBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The two things the assembly point owes the security model: every API-driven action leaves one
 * audit line under its <em>wire</em> verb, and the first pane write of a run announces itself.
 *
 * <p>The {@code agent.*} verbs are the interesting half. They run through a second
 * {@code CodingAgentActions} with the Stage-2 vocabulary — {@code prompt}, {@code sendKeys} — and
 * used to have a sink of their own that only logged, so {@code agent.prompt} could type a prompt and
 * {@code agent.send_keys} could answer the permission dialog it raised without the user ever seeing
 * the "a local program is controlling this window" notification.
 *
 * <p>Any thread; nothing here needs a toolkit or a socket.
 */
class ControlApiWiringTest {

    private static final PaneRef PANE =
        new PaneRef(ControlIds.terminalViewId("t1"), ControlIds.widgetPaneIdFromPaneId("p1a2b"));

    private List<String> notifications;

    private CountDownLatch delivered;

    private ExecutorService notifierThread;

    private DesktopNotifier notifier;

    @BeforeMethod
    void setUp() {
        notifications = new ArrayList<>();
        delivered = new CountDownLatch(1);
        notifierThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kt-test-notifier");
            thread.setDaemon(true);
            return thread;
        });
        notifier = new DesktopNotifier(new DesktopNotifierBackend() {
            @Override
            public boolean isSupported() {
                return true;
            }

            @Override
            public void notify(String title, String body) {
                synchronized (notifications) {
                    notifications.add(title);
                }
                delivered.countDown();
            }
        }, notifierThread);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() {
        if (notifier != null) {
            notifier.close();
            notifier = null;
        }
        if (notifierThread != null) {
            notifierThread.shutdownNow();
            notifierThread = null;
        }
    }

    /** The titles the backend actually received, once the notifier's own thread has run. */
    private List<String> shown() throws InterruptedException {
        delivered.await(5L, TimeUnit.SECONDS);
        synchronized (notifications) {
            return List.copyOf(notifications);
        }
    }

    @Test(timeOut = 30_000)
    void anAgentPromptRaisesTheTakeoverNotificationExactlyOncePerRun() throws Exception {
        List<String> audit = new ArrayList<>();
        ControlAuditSink sink = ControlApiWiring.auditSink(notifier);
        CodingAgentActions.AuditSink agentSink = ControlApiWiring.agentAuditSink(
            (verb, paneId, detail) -> {
                audit.add(verb + " " + paneId);
                sink.record(verb, paneId, detail);
            });

        agentSink.record("prompt", PANE, "bytes=9 lines=1 bracketed=false");
        agentSink.record("sendKeys", PANE, "bytes=2");

        assertThat(audit).containsExactly("agent.prompt p1a2b", "agent.send_keys p1a2b").inOrder();
        assertThat(shown()).hasSize(1);
    }

    @Test(timeOut = 30_000)
    void aReadOnlyAgentVerbIsAuditedButAnnouncesNothing() throws Exception {
        ControlAuditSink sink = ControlApiWiring.auditSink(notifier);
        CodingAgentActions.AuditSink agentSink = ControlApiWiring.agentAuditSink(sink);

        agentSink.record("explain", PANE, "chars=120");
        agentSink.record("rename", PANE, "alias=backend");

        assertThat(delivered.await(200L, TimeUnit.MILLISECONDS)).isFalse();
        synchronized (notifications) {
            assertThat(notifications).isEmpty();
        }
    }

    /** Why: a sink that throws would turn a successful action into a JSON-RPC error. */
    @Test(timeOut = 30_000)
    void anAgentSinkNeverThrowsWhateverTheSinkBehindItDoes() {
        CodingAgentActions.AuditSink agentSink = ControlApiWiring.agentAuditSink((verb, paneId, detail) -> {
            throw new IllegalStateException("the sink is broken");
        });

        agentSink.record("prompt", PANE, "bytes=9");
        agentSink.record("prompt", null, null);
    }
}
