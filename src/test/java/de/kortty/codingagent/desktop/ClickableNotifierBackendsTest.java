package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.testng.annotations.Test;

/**
 * The pure parts of the clickable backends: the Linux D-Bus backend's fallback and retry, the
 * {@code notify-send --action} path, and the macOS routing and click bookkeeping.
 */
class ClickableNotifierBackendsTest {

    /** A notification server that records what it was asked and can fail on demand. */
    static final class FakeServer implements LinuxDBusNotifierBackend.NotificationServer {
        final List<String> titles = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        boolean open = true;
        boolean fail;
        boolean actionsCapability = true;
        int closes;
        String desktopEntry;

        @Override
        public int notify(String appName, String icon, String title, String body, String desktopEntry,
                          int expireMillis, Runnable onActivate) throws IOException {
            if (fail) {
                throw new IOException("bus gone");
            }
            this.desktopEntry = desktopEntry;
            titles.add(title);
            actions.add(onActivate);
            return titles.size();
        }

        @Override
        public boolean supportsActions() {
            return actionsCapability;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
            closes++;
        }
    }

    private static final class CountingFactory implements LinuxDBusNotifierBackend.ServerFactory {
        final List<FakeServer> opened = new ArrayList<>();
        boolean fail;

        @Override
        public LinuxDBusNotifierBackend.NotificationServer open() throws IOException {
            if (fail) {
                throw new IOException("no notification server");
            }
            FakeServer server = new FakeServer();
            opened.add(server);
            return server;
        }
    }

    @Test
    void theBusConnectionIsOpenedOnceAndCarriesTheClickAction() throws Exception {
        CountingFactory factory = new CountingFactory();
        DesktopNotifierTest.FakeNotifierBackend fallback = new DesktopNotifierTest.FakeNotifierBackend();
        LinuxDBusNotifierBackend backend = new LinuxDBusNotifierBackend("icon", "kortty-korTTY", factory, fallback,
            () -> 0L);
        Runnable click = () -> { };

        backend.notify("t1", "b1", click);
        backend.notify("t2", "b2");

        assertThat(factory.opened).hasSize(1);
        FakeServer server = factory.opened.get(0);
        assertThat(server.titles).containsExactly("t1", "t2").inOrder();
        assertThat(server.actions.get(0)).isSameInstanceAs(click);
        assertThat(server.actions.get(1)).isNull();
        assertThat(server.desktopEntry).isEqualTo("kortty-korTTY");
        assertThat(fallback.calls).isEqualTo(0);
        assertThat(backend.supportsActivation()).isTrue();

        server.actionsCapability = false;
        assertThat(backend.supportsActivation()).isFalse();
    }

    @Test
    void withoutABusTheFallbackShowsTheNotificationUntilTheRetryPauseIsOver() throws Exception {
        CountingFactory factory = new CountingFactory();
        factory.fail = true;
        DesktopNotifierTest.FakeNotifierBackend fallback = new DesktopNotifierTest.FakeNotifierBackend();
        AtomicLong now = new AtomicLong(1_000L);
        LinuxDBusNotifierBackend backend = new LinuxDBusNotifierBackend("icon", null, factory, fallback, now::get);

        backend.notify("t1", "b1");
        factory.fail = false;
        backend.notify("t2", "b2");
        assertThat(fallback.shown).hasSize(2);
        assertThat(factory.opened).isEmpty();

        now.addAndGet(LinuxDBusNotifierBackend.RETRY_MILLIS);
        backend.notify("t3", "b3");
        assertThat(factory.opened).hasSize(1);
        assertThat(factory.opened.get(0).titles).containsExactly("t3");
    }

    @Test
    void aBrokenConnectionIsDroppedAndTheNotificationStillShown() throws Exception {
        CountingFactory factory = new CountingFactory();
        DesktopNotifierTest.FakeNotifierBackend fallback = new DesktopNotifierTest.FakeNotifierBackend();
        AtomicLong now = new AtomicLong();
        LinuxDBusNotifierBackend backend = new LinuxDBusNotifierBackend("icon", null, factory, fallback, now::get);
        backend.notify("t1", "b1");
        factory.opened.get(0).fail = true;

        backend.notify("t2", "b2");

        assertThat(factory.opened.get(0).closes).isEqualTo(1);
        assertThat(fallback.shown).hasSize(1);
        assertThat(fallback.shown.get(0)[0]).isEqualTo("t2");

        now.addAndGet(LinuxDBusNotifierBackend.RETRY_MILLIS);
        backend.notify("t3", "b3");
        assertThat(factory.opened).hasSize(2);
    }

    @Test
    void withNeitherBusNorNotifySendTheFailureReachesTheNotifier() {
        CountingFactory factory = new CountingFactory();
        factory.fail = true;
        LinuxDBusNotifierBackend backend = new LinuxDBusNotifierBackend("icon", null, factory,
            new UnsupportedNotifierBackend(), () -> 0L);

        assertThrows(IOException.class, () -> backend.notify("t", "b"));
    }

    @Test
    void closeClosesTheConnectionAndTheFallback() throws Exception {
        CountingFactory factory = new CountingFactory();
        DesktopNotifierTest.FakeNotifierBackend fallback = new DesktopNotifierTest.FakeNotifierBackend();
        LinuxDBusNotifierBackend backend = new LinuxDBusNotifierBackend("icon", null, factory, fallback, () -> 0L);
        backend.notify("t", "b");

        backend.close();

        assertThat(factory.opened.get(0).closes).isEqualTo(1);
        assertThat(fallback.closeCalls).isEqualTo(1);
        assertThat(backend.isSupported()).isFalse();
    }

    /** Records the started {@code notify-send --action} commands and lets the test report a line. */
    private static final class FakeWatcher implements LinuxNotifySendNotifierBackend.ActionWatcher {
        final List<List<String>> started = new ArrayList<>();
        final List<Consumer<String>> lines = new ArrayList<>();
        final List<IntConsumer> exits = new ArrayList<>();
        boolean failToStart;

        @Override
        public Process start(List<String> argv, Consumer<String> onLine, IntConsumer onExit) {
            if (failToStart) {
                onExit.accept(-1);
                return null;
            }
            started.add(argv);
            lines.add(onLine);
            exits.add(onExit);
            try {
                // Any short-lived process stands in for notify-send.
                return new ProcessBuilder(isWindows() ? List.of("cmd", "/c", "exit") : List.of("true")).start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        }
    }

    private static Function<List<String>, ExternalCommandRunner.Result> runner(List<List<String>> runs, String help) {
        return argv -> {
            runs.add(argv);
            if (argv.contains("--help")) {
                return new ExternalCommandRunner.Result(0, help, false);
            }
            return new ExternalCommandRunner.Result(0, "", false);
        };
    }

    @Test
    void aNotifySendThatKnowsActionsWaitsForTheClick() throws Exception {
        List<List<String>> runs = new ArrayList<>();
        FakeWatcher watcher = new FakeWatcher();
        LinuxNotifySendNotifierBackend backend = new LinuxNotifySendNotifierBackend("icon", false,
            runner(runs, "  -A, --action=[NAME=]Text...   Specifies the actions to display"), watcher);
        AtomicInteger clicks = new AtomicInteger();

        backend.notify("t1", "b1", clicks::incrementAndGet);
        backend.notify("t2", "b2", clicks::incrementAndGet);

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0)).contains("--help");
        assertThat(watcher.started).hasSize(2);
        assertThat(watcher.started.get(0)).contains("--action=default=Open");
        assertThat(watcher.started.get(0).indexOf("--action=default=Open"))
            .isLessThan(watcher.started.get(0).indexOf("--"));

        watcher.lines.get(0).accept("closed");
        watcher.lines.get(0).accept("default\n");
        assertThat(clicks.get()).isEqualTo(1);
        assertThat(backend.supportsActivation()).isTrue();

        backend.notify("t3", "b3");
        assertThat(runs).hasSize(2);
        assertThat(runs.get(1)).doesNotContain("--action=default=Open");
    }

    @Test
    void anOlderNotifySendShowsThePlainNotification() throws Exception {
        List<List<String>> runs = new ArrayList<>();
        FakeWatcher watcher = new FakeWatcher();
        LinuxNotifySendNotifierBackend backend = new LinuxNotifySendNotifierBackend("icon", false,
            runner(runs, "Usage: notify-send [OPTION...] <SUMMARY> [BODY]"), watcher);

        backend.notify("t1", "b1", () -> { });
        backend.notify("t2", "b2", () -> { });

        assertThat(watcher.started).isEmpty();
        assertThat(runs).hasSize(3);
        assertThat(backend.supportsActivation()).isFalse();
    }

    @Test
    void aWatcherThatCannotStartFailsTheNotificationAndDisablesFlatpak() {
        List<List<String>> runs = new ArrayList<>();
        FakeWatcher watcher = new FakeWatcher();
        watcher.failToStart = true;
        LinuxNotifySendNotifierBackend backend = new LinuxNotifySendNotifierBackend("icon", true,
            runner(runs, "--action"), watcher);

        assertThrows(IOException.class, () -> backend.notify("t", "b", () -> { }));
        assertThat(backend.isSupported()).isFalse();
    }

    @Test
    void notifySendHelpAndActionArgvAreHostAware() {
        assertThat(NotificationCommands.notifySendHelp("/usr/bin/notify-send", java.util.Map.of()))
            .containsExactly("/usr/bin/notify-send", "--help").inOrder();
        assertThat(NotificationCommands.notifySendHelp(null, java.util.Map.of("FLATPAK_ID", "x")).get(0))
            .isEqualTo("flatpak-spawn");
        assertThat(NotificationCommands.notifySendSupportsActions("  -A, --action=")).isTrue();
        assertThat(NotificationCommands.notifySendSupportsActions("Usage: notify-send")).isFalse();
        assertThat(NotificationCommands.notifySendSupportsActions(null)).isFalse();
        assertThat(NotificationCommands.notifySend("notify-send", "korTTY", "icon", "-t", "b", java.util.Map.of(),
            false)).doesNotContain("--action=default=Open");
    }

    @Test
    void macRoutesByThePermissionTheUserGave() {
        assertThat(MacUserNotificationsBackend.route(true, MacUserNotificationsBackend.Authorization.GRANTED))
            .isEqualTo(MacUserNotificationsBackend.Route.NATIVE);
        assertThat(MacUserNotificationsBackend.route(true, MacUserNotificationsBackend.Authorization.PENDING))
            .isEqualTo(MacUserNotificationsBackend.Route.FALLBACK);
        assertThat(MacUserNotificationsBackend.route(true, MacUserNotificationsBackend.Authorization.DENIED))
            .isEqualTo(MacUserNotificationsBackend.Route.NONE);
        for (MacUserNotificationsBackend.Authorization any : MacUserNotificationsBackend.Authorization.values()) {
            assertThat(MacUserNotificationsBackend.route(false, any))
                .isEqualTo(MacUserNotificationsBackend.Route.FALLBACK);
        }
    }

    @Test
    void macRunsTheClickedNotificationsActionOnceAndIgnoresDismissal() {
        MacUserNotificationsBackend backend = new MacUserNotificationsBackend(new UnsupportedNotifierBackend());
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        backend.remember("kortty-1", first::incrementAndGet);
        backend.remember("kortty-2", second::incrementAndGet);

        backend.activated("kortty-1", "com.apple.UNNotificationDefaultActionIdentifier");
        backend.activated("kortty-1", "com.apple.UNNotificationDefaultActionIdentifier");
        backend.activated("kortty-2", "com.apple.UNNotificationDismissActionIdentifier");
        backend.activated(null, null);
        backend.activated("unknown", null);

        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(0);
        assertThat(backend.trackedCount()).isEqualTo(1);
    }

    @Test
    void macForgetsTheOldestActionsBeyondTheLimit() {
        MacUserNotificationsBackend backend = new MacUserNotificationsBackend(new UnsupportedNotifierBackend());
        AtomicInteger oldest = new AtomicInteger();
        backend.remember("kortty-0", oldest::incrementAndGet);
        for (int i = 1; i <= MacUserNotificationsBackend.MAX_TRACKED_NOTIFICATIONS; i++) {
            backend.remember("kortty-" + i, () -> { });
        }

        backend.activated("kortty-0", null);

        assertThat(backend.trackedCount()).isEqualTo(MacUserNotificationsBackend.MAX_TRACKED_NOTIFICATIONS);
        assertThat(oldest.get()).isEqualTo(0);
    }
}
