package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.testng.annotations.Test;

class DesktopNotifierTest {

    /** Runs submitted work inline and records shutdown. */
    static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("shut down");
            }
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
            return shutdown;
        }
    }

    static final class FakeNotifierBackend implements DesktopNotifierBackend {
        final List<String[]> shown = new ArrayList<>();
        boolean supported = true;
        boolean throwOnNotify;
        int calls;
        int closeCalls;

        @Override
        public boolean isSupported() {
            return supported;
        }

        @Override
        public void notify(String title, String body) throws IOException {
            calls++;
            if (throwOnNotify) {
                throw new IOException("notification failed");
            }
            shown.add(new String[] {title, body});
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    @Test
    void bodyIsTruncatedBeforeTheBackendSeesIt() {
        FakeNotifierBackend backend = new FakeNotifierBackend();
        DesktopNotifier notifier = new DesktopNotifier(backend, new DirectExecutorService());
        String body = "b".repeat(DesktopNotifier.MAX_BODY_CHARS + 50);

        notifier.notify("claude needs a decision", body);

        assertThat(backend.shown).hasSize(1);
        assertThat(backend.shown.get(0)[0]).isEqualTo("claude needs a decision");
        assertThat(backend.shown.get(0)[1]).hasLength(DesktopNotifier.MAX_BODY_CHARS);
        assertThat(backend.shown.get(0)[1]).endsWith("…");
        assertThat(notifier.isSupported()).isTrue();
    }

    @Test
    void nullTitleAndBodyBecomeEmptyStrings() {
        FakeNotifierBackend backend = new FakeNotifierBackend();
        DesktopNotifier notifier = new DesktopNotifier(backend, new DirectExecutorService());

        notifier.notify(null, null);

        assertThat(backend.shown.get(0)[0]).isEmpty();
        assertThat(backend.shown.get(0)[1]).isEmpty();
    }

    @Test
    void unsupportedBackendIsNeverCalled() {
        FakeNotifierBackend backend = new FakeNotifierBackend();
        backend.supported = false;
        DesktopNotifier notifier = new DesktopNotifier(backend, new DirectExecutorService());

        notifier.notify("t1", "b1");
        notifier.notify("t2", "b2");

        assertThat(notifier.isSupported()).isFalse();
        assertThat(backend.calls).isEqualTo(0);
    }

    @Test
    void backendFailuresDisableTheNotifierAfterTwoInARow() {
        FakeNotifierBackend backend = new FakeNotifierBackend();
        backend.throwOnNotify = true;
        DesktopNotifier notifier = new DesktopNotifier(backend, new DirectExecutorService());

        notifier.notify("t1", "b1");
        assertThat(notifier.isSupported()).isTrue();
        notifier.notify("t2", "b2");
        assertThat(notifier.isSupported()).isFalse();
        notifier.notify("t3", "b3");

        assertThat(backend.calls).isEqualTo(2);
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        FakeNotifierBackend backend = new FakeNotifierBackend();
        DesktopNotifier notifier = new DesktopNotifier(backend, new DirectExecutorService());

        backend.throwOnNotify = true;
        notifier.notify("t1", "b1");
        backend.throwOnNotify = false;
        notifier.notify("t2", "b2");
        backend.throwOnNotify = true;
        notifier.notify("t3", "b3");

        assertThat(notifier.isSupported()).isTrue();
        assertThat(backend.calls).isEqualTo(3);
        assertThat(backend.shown).hasSize(1);
    }

    @Test
    void closeShutsTheExecutorDownAndDropsLaterNotifications() {
        FakeNotifierBackend backend = new FakeNotifierBackend();
        DirectExecutorService executor = new DirectExecutorService();
        DesktopNotifier notifier = new DesktopNotifier(backend, executor);

        notifier.close();
        notifier.close();
        notifier.notify("t", "b");

        assertThat(executor.isShutdown()).isTrue();
        assertThat(backend.closeCalls).isEqualTo(1);
        assertThat(backend.calls).isEqualTo(0);
        assertThat(notifier.isSupported()).isFalse();
    }

    @Test(timeOut = 30_000)
    void defaultExecutorIsANamedDaemonThread() throws Exception {
        var executor = DesktopNotifier.defaultExecutor();
        try {
            Thread thread = executor.submit(Thread::currentThread).get(10, TimeUnit.SECONDS);
            assertThat(thread.getName()).isEqualTo("kortty-desktop-notifier");
            assertThat(thread.isDaemon()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void osascriptBackendReportsANonZeroExit() {
        List<List<String>> commands = new ArrayList<>();
        Function<List<String>, ExternalCommandRunner.Result> failing = argv -> {
            commands.add(argv);
            return new ExternalCommandRunner.Result(1, "syntax error", false);
        };
        MacOsascriptNotifierBackend backend = new MacOsascriptNotifierBackend(failing);

        assertThat(backend.isSupported()).isTrue();
        IOException failure = null;
        try {
            backend.notify("title", "body");
        } catch (IOException e) {
            failure = e;
        }

        assertThat(failure).isNotNull();
        assertThat(failure).hasMessageThat().contains("syntax error");
        assertThat(commands.get(0).get(0)).isEqualTo("/usr/bin/osascript");
    }

    @Test
    void notifySendBackendDisablesItselfOnlyInsideFlatpak() throws Exception {
        Function<List<String>, ExternalCommandRunner.Result> failing =
            argv -> new ExternalCommandRunner.Result(1, "", false);

        LinuxNotifySendNotifierBackend host = new LinuxNotifySendNotifierBackend("utilities-terminal", false, failing);
        try {
            host.notify("t", "b");
        } catch (IOException expected) {
            // First failure outside Flatpak keeps the backend supported.
        }
        assertThat(host.isSupported()).isTrue();

        LinuxNotifySendNotifierBackend flatpak = new LinuxNotifySendNotifierBackend("id", true, failing);
        try {
            flatpak.notify("t", "b");
        } catch (IOException expected) {
            // Expected: the host spawn failed.
        }
        assertThat(flatpak.isSupported()).isFalse();

        List<List<String>> commands = new ArrayList<>();
        LinuxNotifySendNotifierBackend ok = new LinuxNotifySendNotifierBackend(null, false, argv -> {
            commands.add(argv);
            return new ExternalCommandRunner.Result(0, "", false);
        });
        ok.notify("title", "body");
        assertThat(commands.get(0)).containsExactly(
            "notify-send", "--app-name=korTTY", "--icon=utilities-terminal", "--urgency=normal",
            "--expire-time=8000", "--", "title", "body").inOrder();
    }
}
