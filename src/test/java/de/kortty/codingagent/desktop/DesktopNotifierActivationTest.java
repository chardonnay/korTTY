package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * The click path of {@link DesktopNotifier}: the action reaches the backend, runs once and only
 * through the activation dispatcher, never after close; and the enterprise-policy gate that keeps
 * every notification off while {@code desktop-notifications = "deny"}.
 */
class DesktopNotifierActivationTest {

    /** A backend that reports clicks and keeps the click action of every notification. */
    static final class ClickableBackend implements DesktopNotifierBackend {
        final List<String> titles = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        int plainCalls;

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public boolean supportsActivation() {
            return true;
        }

        @Override
        public void notify(String title, String body) {
            plainCalls++;
            titles.add(title);
        }

        @Override
        public void notify(String title, String body, Runnable onActivate) {
            titles.add(title);
            actions.add(onActivate);
        }
    }

    /** Records what was dispatched and runs it later, as {@code Platform.runLater} would. */
    static final class QueueDispatcher implements Executor {
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            List<Runnable> work = new ArrayList<>(queued);
            queued.clear();
            work.forEach(Runnable::run);
        }
    }

    @Test
    void theClickActionRunsOnceAndOnlyThroughTheDispatcher() {
        ClickableBackend backend = new ClickableBackend();
        QueueDispatcher fx = new QueueDispatcher();
        DesktopNotifier notifier = new DesktopNotifier(backend, new DesktopNotifierTest.DirectExecutorService(), fx);
        AtomicInteger clicks = new AtomicInteger();

        notifier.notify("claude needs a decision", "tab › Pane 1", clicks::incrementAndGet);

        assertThat(backend.actions).hasSize(1);
        Runnable click = backend.actions.get(0);
        click.run();
        assertThat(clicks.get()).isEqualTo(0);
        assertThat(fx.queued).hasSize(1);

        click.run();
        fx.runAll();
        assertThat(clicks.get()).isEqualTo(1);
        assertThat(fx.queued).isEmpty();
    }

    @Test
    void aNotificationWithoutActionUsesThePlainCall() {
        ClickableBackend backend = new ClickableBackend();
        DesktopNotifier notifier = new DesktopNotifier(backend, new DesktopNotifierTest.DirectExecutorService());

        notifier.notify("t", "b");

        assertThat(backend.plainCalls).isEqualTo(1);
        assertThat(backend.actions).isEmpty();
        assertThat(notifier.supportsActivation()).isTrue();
    }

    @Test
    void aBackendWithoutClicksShowsThePlainNotification() {
        DesktopNotifierTest.FakeNotifierBackend backend = new DesktopNotifierTest.FakeNotifierBackend();
        DesktopNotifier notifier = new DesktopNotifier(backend, new DesktopNotifierTest.DirectExecutorService());

        notifier.notify("t", "b", () -> { });

        assertThat(backend.shown).hasSize(1);
        assertThat(notifier.supportsActivation()).isFalse();
    }

    @Test
    void noClickActionRunsAfterClose() {
        ClickableBackend backend = new ClickableBackend();
        QueueDispatcher fx = new QueueDispatcher();
        DesktopNotifier notifier = new DesktopNotifier(backend, new DesktopNotifierTest.DirectExecutorService(), fx);
        AtomicBoolean clicked = new AtomicBoolean();
        notifier.notify("t", "b", () -> clicked.set(true));

        notifier.close();
        backend.actions.get(0).run();

        assertThat(fx.queued).isEmpty();
        assertThat(clicked.get()).isFalse();
    }

    @Test
    void aFailingActionOrARejectingDispatcherNeverReachesTheBackend() {
        ClickableBackend backend = new ClickableBackend();
        DesktopNotifier rejecting = new DesktopNotifier(backend, new DesktopNotifierTest.DirectExecutorService(),
            command -> {
                throw new RejectedExecutionException("toolkit gone");
            });
        rejecting.notify("t", "b", () -> { });
        backend.actions.get(0).run();

        DesktopNotifier direct = new DesktopNotifier(backend, new DesktopNotifierTest.DirectExecutorService(),
            Runnable::run);
        direct.notify("t", "b", () -> {
            throw new IllegalStateException("pane gone");
        });
        backend.actions.get(1).run();

        assertThat(backend.actions).hasSize(2);
    }

    @Test
    void aDenyingPolicyKeepsEveryNotificationOffAndFollowsAReload() {
        ClickableBackend backend = new ClickableBackend();
        AtomicBoolean allowed = new AtomicBoolean(false);
        DesktopNotifier notifier = new DesktopNotifier(backend, new DesktopNotifierTest.DirectExecutorService(),
            Runnable::run, allowed::get);

        notifier.notify("t1", "b1");
        notifier.notify("t2", "b2", () -> { });
        assertThat(backend.titles).isEmpty();
        assertThat(notifier.isSupported()).isFalse();
        assertThat(notifier.supportsActivation()).isFalse();
        assertThat(notifier.isAllowedByPolicy()).isFalse();

        allowed.set(true);
        notifier.notify("t3", "b3");
        assertThat(backend.titles).containsExactly("t3");
        assertThat(notifier.isSupported()).isTrue();
    }

    @Test
    void aFailingPolicyGateCountsAsDenied() {
        ClickableBackend backend = new ClickableBackend();
        DesktopNotifier notifier = new DesktopNotifier(backend, new DesktopNotifierTest.DirectExecutorService(),
            Runnable::run, () -> {
                throw new IllegalStateException("policy not loaded");
            });

        notifier.notify("t", "b");

        assertThat(backend.titles).isEmpty();
        assertThat(notifier.isAllowedByPolicy()).isFalse();
    }
}
