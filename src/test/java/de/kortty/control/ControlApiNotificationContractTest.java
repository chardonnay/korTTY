package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.gson.JsonObject;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.codingagent.FakePaneAccess;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.DesktopNotifierBackend;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * What {@code notification.show} actually hands the desktop, pinned against §6.
 *
 * <p>The rest of the suite wires in a notifier that reports {@code supported:false} precisely so no
 * test can raise a real notification on a developer's machine — which also means no test in it ever
 * sees the three promises §6 makes about the payload: the {@code korTTY · } title prefix, the 80- and
 * 200-character caps, and the {@code {shown, supported}} result.
 *
 * <p>The prefix is the one that matters. It is the whole reason the verb is allowed to exist at all:
 * any client holding the token can raise a system notification, and without an attribution the user
 * cannot possibly tell "korTTY · Build green" from a notification purporting to come from a bank.
 * This class therefore wires in a <em>recording</em> backend that claims support and captures the
 * title and body instead of showing anything, so the payload can be read back and asserted.
 *
 * <p>The backend is asserted through a real server over a real socket, because the prefix is applied
 * on the far side of the dispatcher and the caps on the far side of the JSON boundary; a unit test of
 * {@code NotificationVerbs} could not reach either.
 */
public class ControlApiNotificationContractTest {

    /** §6: every API-raised notification is titled so it cannot be mistaken for another app's. */
    private static final String TITLE_PREFIX = "korTTY · ";

    /** §6: the largest title a caller may supply, before the prefix. */
    private static final int MAX_TITLE_CHARS = 80;

    /** §6: the largest body a caller may supply. */
    private static final int MAX_BODY_CHARS = 200;

    /** How long a test waits for the notifier's own thread to deliver one recording. */
    private static final long DELIVERY_BUDGET_SECONDS = 10L;

    private Path root;

    private CodingAgentRegistry agents;

    private ControlApiServer server;

    private EndpointDescriptor endpoint;

    private ScheduledExecutorService timer;

    private ExecutorService notifierExecutor;

    private DesktopNotifier notifier;

    /** What the backend was asked to show: the title and body of each notification. */
    private final BlockingQueue<String[]> shown = new ArrayBlockingQueue<>(16);

    @BeforeMethod
    void startAServerWithARecordingNotifier() throws IOException {
        root = ControlApiScenarioFixtures.newTempRoot();
        ControlApiScenarioFixtures.skipIfSocketPathTooLong(root.resolve(ControlDirectory.DIRECTORY_NAME));
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), System::currentTimeMillis);
        timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "kt-notification-contract-timer");
            thread.setDaemon(true);
            return thread;
        });
        notifierExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kt-notification-contract-notifier");
            thread.setDaemon(true);
            return thread;
        });
        notifier = new DesktopNotifier(new RecordingBackend(), notifierExecutor);

        ControlSurface surface = ControlApiScenarioFixtures.oneLocalShellPaneWithClaudeCode();
        CodingAgentActions actions =
            new CodingAgentActions(agents, new FakePaneAccess(), (verb, pane, detail) -> { });
        String instanceId = UUID.randomUUID().toString();
        MethodRegistry methods = ControlVerbs.build(surface, UiDispatcher.DIRECT, agents, actions,
            new ControlEventBus(timer, System::currentTimeMillis), (verb, pane, detail) -> { },
            notifier, System::currentTimeMillis, "3.4.1", instanceId);
        server = new ControlApiServer(root, ControlApiScenarioFixtures.nativeProbe(), methods,
            () -> true, System::currentTimeMillis, "3.4.1", instanceId);
        server.applyEnabledState();
        endpoint = server.endpoint().orElseThrow();
    }

    @AfterMethod(alwaysRun = true)
    void stopEverythingAndDeleteTheTempTree() {
        if (server != null) {
            server.close();
            server = null;
        }
        if (notifier != null) {
            notifier.close();
            notifier = null;
        }
        if (notifierExecutor != null) {
            notifierExecutor.shutdownNow();
        }
        if (timer != null) {
            timer.shutdownNow();
        }
        if (agents != null) {
            agents.clear();
        }
        ControlApiScenarioFixtures.deleteTree(root);
    }

    @Test(timeOut = 60_000)
    void everyNotificationTheApiRaisesIsTitledAsKorttysOwn() throws Exception {
        String[] delivered = show("Your bank needs your password", "Click here to confirm");
        assertWithMessage("§6 prefixes every API-raised title with '%s'. Without it any client"
                + " holding the token can put an unattributed system notification on the user's"
                + " screen, which is the forgery this prefix exists to prevent", TITLE_PREFIX)
            .that(delivered[0]).isEqualTo(TITLE_PREFIX + "Your bank needs your password");
        assertWithMessage("the body is the caller's, unchanged")
            .that(delivered[1]).isEqualTo("Click here to confirm");
    }

    @Test(timeOut = 60_000)
    void aTitleOverTheCapIsClampedBeforeItReachesTheDesktopAndKeepsThePrefix() throws Exception {
        String[] delivered = show("t".repeat(MAX_TITLE_CHARS * 3), "body");
        assertWithMessage("§6 caps the title at %s characters before the prefix, so a caller cannot"
                + " push the attribution off the visible part of the notification", MAX_TITLE_CHARS)
            .that(delivered[0]).isEqualTo(TITLE_PREFIX + "t".repeat(MAX_TITLE_CHARS));
    }

    @Test(timeOut = 60_000)
    void aBodyOverTheCapIsClampedBeforeItReachesTheDesktop() throws Exception {
        String[] delivered = show("Build", "b".repeat(MAX_BODY_CHARS * 3));
        assertWithMessage("§6 caps the body at %s characters, and the verb is what applies the cap:"
                + " a body that reaches the notifier uncapped comes back shortened with an ellipsis"
                + " instead of the %s characters the caller is promised",
                MAX_BODY_CHARS, MAX_BODY_CHARS)
            .that(delivered[1]).isEqualTo("b".repeat(MAX_BODY_CHARS));
    }

    @Test(timeOut = 60_000)
    void theResultSaysItWasShownAndWhetherThisPlatformSupportsNotifications() throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject frame = wire.call("notification.show",
                ControlApiScenarioFixtures.params("title", "Build", "body", "green"));
            assertWithMessage("notification.show must have succeeded but answered %s", frame)
                .that(frame.has("result")).isTrue();
            JsonObject result = frame.getAsJsonObject("result");
            assertWithMessage("§6 gives notification.show the result {shown, supported}")
                .that(result.keySet()).containsExactly("shown", "supported");
            assertThat(result.get("shown").getAsBoolean()).isTrue();
            assertWithMessage("this backend claims support, and 'supported' is what tells a client"
                    + " whether the user will actually have seen anything")
                .that(result.get("supported").getAsBoolean()).isTrue();
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Raises one notification over the wire and returns the title and body the backend received. */
    private String[] show(String title, String body) throws Exception {
        try (ControlApiScenarioFixtures.Wire wire = new ControlApiScenarioFixtures.Wire(endpoint)) {
            wire.authenticate(endpoint.token());
            JsonObject frame = wire.call("notification.show",
                ControlApiScenarioFixtures.params("title", title, "body", body));
            assertWithMessage("notification.show must have succeeded but answered %s", frame)
                .that(frame.has("result")).isTrue();
        }
        String[] delivered = shown.poll(DELIVERY_BUDGET_SECONDS, TimeUnit.SECONDS);
        assertWithMessage("the verb reported success, so the notifier must really have been asked to"
                + " show something")
            .that(delivered).isNotNull();
        return delivered;
    }

    /** A backend that claims support and records instead of showing anything. */
    private final class RecordingBackend implements DesktopNotifierBackend {

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public void notify(String title, String body) {
            shown.offer(new String[] {title, body});
        }
    }
}
