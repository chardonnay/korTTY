package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.testng.annotations.Test;

class BackendSelectionTest {

    private static final Executor DIRECT = Runnable::run;
    private static final StageIconPresenter NO_ICONS = new StageIconPresenter() {
        @Override
        public void applyBadgedIcon(javafx.scene.image.Image icon) {
        }

        @Override
        public void restorePlainIcon() {
        }
    };

    private static PlatformProbe probe(String os, String jpackagePath, boolean flatpak, boolean headless) {
        return new PlatformProbe(os, jpackagePath, flatpak, headless);
    }

    @Test
    void macPackagedNonHeadlessGetsTheDockBadge() {
        AppBadgeBackend backend = AppBadgeBackends.select(
            probe("Mac OS X", "/Applications/korTTY.app/Contents/MacOS/korTTY", false, false),
            Optional.empty(), NO_ICONS, DIRECT);

        assertThat(backend).isInstanceOf(MacDockBadgeBackend.class);
    }

    @Test
    void macUnpackagedOrHeadlessIsUnsupported() {
        assertThat(AppBadgeBackends.select(probe("Mac OS X", null, false, false), Optional.empty(), NO_ICONS, DIRECT))
            .isInstanceOf(UnsupportedAppBadgeBackend.class);
        assertThat(AppBadgeBackends.select(
            probe("Mac OS X", "/Applications/korTTY.app/Contents/MacOS/korTTY", false, true),
            Optional.empty(), NO_ICONS, DIRECT))
            .isInstanceOf(UnsupportedAppBadgeBackend.class);
    }

    @Test
    void windowsGetsTheStageIconBadge() {
        AppBadgeBackend backend = AppBadgeBackends.select(
            probe("Windows 11", null, false, false), Optional.empty(), NO_ICONS, DIRECT);

        assertThat(backend).isInstanceOf(WindowsIconBadgeBackend.class);
        assertThat(backend.isSupported()).isTrue();
    }

    @Test
    void windowsWithoutAStageIconPortIsUnsupported() {
        assertThat(AppBadgeBackends.select(probe("Windows 11", null, false, false), Optional.empty(), null, DIRECT))
            .isInstanceOf(UnsupportedAppBadgeBackend.class);
    }

    @Test
    void linuxWithADesktopIdGetsTheLauncherEntry() {
        AppBadgeBackend backend = AppBadgeBackends.select(
            probe("Linux", "/opt/kortty/bin/korTTY", false, false),
            Optional.of("kortty-korTTY.desktop"), NO_ICONS, DIRECT);

        assertThat(backend).isInstanceOf(LinuxLauncherEntryBadgeBackend.class);
        assertThat(((LinuxLauncherEntryBadgeBackend) backend).desktopId()).isEqualTo("kortty-korTTY.desktop");
        assertThat(backend.isSupported()).isTrue();
    }

    @Test
    void linuxWithoutADesktopIdIsUnsupported() {
        assertThat(AppBadgeBackends.select(probe("Linux", null, false, false), Optional.empty(), NO_ICONS, DIRECT))
            .isInstanceOf(UnsupportedAppBadgeBackend.class);
    }

    @Test
    void unknownPlatformIsUnsupported() {
        assertThat(AppBadgeBackends.select(probe("SunOS", null, false, false), Optional.empty(), NO_ICONS, DIRECT))
            .isInstanceOf(UnsupportedAppBadgeBackend.class);
        assertThat(DesktopNotifierBackends.select(probe("SunOS", null, false, false), false, false))
            .isInstanceOf(UnsupportedNotifierBackend.class);
    }

    @Test
    void unsupportedBadgeBackendIsInert() throws Exception {
        AppBadgeBackend backend = new UnsupportedAppBadgeBackend();

        backend.showCount(3);
        backend.requestAttention();
        backend.close();

        assertThat(backend.isSupported()).isFalse();
    }

    @Test
    void macNotifierNeedsOsascriptButNotAPackagedApp() {
        assertThat(DesktopNotifierBackends.select(probe("Mac OS X", null, false, false), true, false))
            .isInstanceOf(MacOsascriptNotifierBackend.class);
        assertThat(DesktopNotifierBackends.select(
            probe("Mac OS X", "/Applications/korTTY.app/Contents/MacOS/korTTY", false, true), true, false))
            .isInstanceOf(MacOsascriptNotifierBackend.class);
        assertThat(DesktopNotifierBackends.select(probe("Mac OS X", null, false, false), false, false))
            .isInstanceOf(UnsupportedNotifierBackend.class);
    }

    @Test
    void windowsNotifierNeedsANonHeadlessAwt() {
        assertThat(DesktopNotifierBackends.select(probe("Windows 11", null, false, false), false, false))
            .isInstanceOf(WindowsTrayNotifierBackend.class);
        assertThat(DesktopNotifierBackends.select(probe("Windows 11", null, false, true), false, false))
            .isInstanceOf(UnsupportedNotifierBackend.class);
    }

    @Test
    void linuxNotifierNeedsNotifySendOrFlatpak() {
        assertThat(DesktopNotifierBackends.select(probe("Linux", null, false, false), false, true))
            .isInstanceOf(LinuxNotifySendNotifierBackend.class);
        assertThat(DesktopNotifierBackends.select(probe("Linux", null, true, false), false, false))
            .isInstanceOf(LinuxNotifySendNotifierBackend.class);
        assertThat(DesktopNotifierBackends.select(probe("Linux", null, false, false), false, false))
            .isInstanceOf(UnsupportedNotifierBackend.class);
    }

    @Test
    void selectorsAndServicesReferenceNoAwtClass() throws IOException {
        // Backends for other operating systems live in their own classes and are instantiated only in
        // the matching branch, so the selectors, services and fallbacks must not mention java.awt at all.
        for (Class<?> type : List.of(AppBadgeBackends.class, AppBadgeService.class, UnsupportedAppBadgeBackend.class,
                DesktopNotifierBackends.class, DesktopNotifier.class, UnsupportedNotifierBackend.class,
                LinuxLauncherEntryBadgeBackend.class, LinuxNotifySendNotifierBackend.class,
                MacOsascriptNotifierBackend.class, NotificationCommands.class, ExternalCommandRunner.class,
                LinuxDesktopId.class, LauncherEntryDBusConnection.class, PlatformProbe.class)) {
            assertThat(constantPool(type)).doesNotContain("java/awt");
        }
        assertThat(constantPool(MacDockBadgeBackend.class)).contains("java/awt/Taskbar");
        assertThat(constantPool(WindowsTrayNotifierBackend.class)).contains("java/awt/SystemTray");
    }

    /** Records every emitted update and how often a sender was opened and closed. */
    private static final class RecordingSender implements LinuxLauncherEntryBadgeBackend.LauncherEntrySender {
        final List<String> updates = new ArrayList<>();
        int opens;
        int closes;
        boolean failOnEmit;

        @Override
        public void emit(String objectPath, String applicationUri, int count, boolean urgent) throws IOException {
            if (failOnEmit) {
                throw new IOException("no session bus");
            }
            updates.add(objectPath + " " + applicationUri + " count=" + count + " urgent=" + urgent);
        }

        @Override
        public void close() {
            closes++;
        }

        LinuxLauncherEntryBadgeBackend.SenderFactory factory() {
            return () -> {
                opens++;
                return this;
            };
        }
    }

    @Test
    void launcherEntryBackendEmitsLatestCountThroughOneLongLivedSender() throws Exception {
        // The receivers drop the entry when the sender's bus name unregisters, so all updates of a
        // session must come from the very same connection.
        RecordingSender sender = new RecordingSender();
        List<Runnable> queued = new ArrayList<>();
        LinuxLauncherEntryBadgeBackend backend =
            new LinuxLauncherEntryBadgeBackend("kortty.desktop", sender.factory(), queued::add);

        backend.showCount(1);
        backend.showCount(2);
        backend.showCount(3);
        assertThat(queued).hasSize(1);

        queued.remove(0).run();
        assertThat(sender.updates).containsExactly(
            LinuxDesktopId.objectPath("kortty.desktop") + " application://kortty.desktop count=3 urgent=false");

        backend.requestAttention();
        queued.remove(0).run();
        backend.showCount(0);
        queued.remove(0).run();

        assertThat(sender.updates).hasSize(3);
        assertThat(sender.updates.get(1)).endsWith("count=3 urgent=true");
        assertThat(sender.updates.get(2)).endsWith("count=0 urgent=false");
        assertThat(sender.opens).isEqualTo(1);
        assertThat(sender.closes).isEqualTo(0);
        assertThat(backend.isSupported()).isTrue();
    }

    @Test
    void launcherEntryBackendDisablesItselfAfterAFailedEmit() throws Exception {
        RecordingSender sender = new RecordingSender();
        sender.failOnEmit = true;
        LinuxLauncherEntryBadgeBackend backend =
            new LinuxLauncherEntryBadgeBackend("kortty.desktop", sender.factory(), DIRECT);

        backend.showCount(2);
        assertThat(backend.isSupported()).isFalse();
        assertThat(sender.closes).isEqualTo(1);

        sender.failOnEmit = false;
        backend.showCount(3);
        backend.requestAttention();
        assertThat(sender.updates).isEmpty();
        assertThat(sender.opens).isEqualTo(1);
    }

    @Test
    void launcherEntryBackendDisablesItselfWhenNoSenderCanBeOpened() throws Exception {
        LinuxLauncherEntryBadgeBackend backend = new LinuxLauncherEntryBadgeBackend(
            "kortty.desktop",
            () -> {
                throw new IOException("no unix:path session bus address");
            },
            DIRECT);

        backend.showCount(2);

        assertThat(backend.isSupported()).isFalse();
    }

    @Test
    void launcherEntryBackendSurvivesARejectingExecutorAndCloseClearsAndDisconnects() throws Exception {
        RecordingSender sender = new RecordingSender();
        Executor rejecting = task -> {
            throw new java.util.concurrent.RejectedExecutionException("shut down");
        };
        LinuxLauncherEntryBadgeBackend rejected =
            new LinuxLauncherEntryBadgeBackend("kortty.desktop", sender.factory(), rejecting);
        rejected.showCount(1);
        assertThat(sender.updates).isEmpty();
        assertThat(rejected.isSupported()).isTrue();

        RecordingSender live = new RecordingSender();
        LinuxLauncherEntryBadgeBackend direct =
            new LinuxLauncherEntryBadgeBackend("kortty.desktop", live.factory(), DIRECT);
        direct.showCount(4);
        direct.close();

        assertThat(live.updates).hasSize(2);
        assertThat(live.updates.get(1)).endsWith("count=0 urgent=false");
        // Disconnecting removes the entry even where the clearing update was missed.
        assertThat(live.closes).isEqualTo(1);
    }

    @Test
    void sessionBusSocketPathPrefersTheUnixPathAddress() {
        assertThat(LauncherEntryDBusConnection.sessionBusSocketPath(
            Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus,guid=abc")))
            .hasValue("/run/user/1000/bus");
        assertThat(LauncherEntryDBusConnection.sessionBusSocketPath(
            Map.of("DBUS_SESSION_BUS_ADDRESS", "unix:path=/tmp/dbus%2dtest")))
            .hasValue("/tmp/dbus-test");
        // An abstract address cannot be expressed as a UnixDomainSocketAddress; the runtime dir wins.
        assertThat(LauncherEntryDBusConnection.sessionBusSocketPath(Map.of(
            "DBUS_SESSION_BUS_ADDRESS", "unix:abstract=/tmp/dbus-AbCd,guid=abc",
            "XDG_RUNTIME_DIR", "/run/user/1000")))
            .hasValue("/run/user/1000/bus");
        assertThat(LauncherEntryDBusConnection.sessionBusSocketPath(
            Map.of("XDG_RUNTIME_DIR", "/run/user/1000"))).hasValue("/run/user/1000/bus");
        assertThat(LauncherEntryDBusConnection.sessionBusSocketPath(Map.of())).isEmpty();
        assertThat(LauncherEntryDBusConnection.sessionBusSocketPath(null)).isEmpty();
    }

    @Test
    void updateSignalIsMarshalledAsTheLauncherEntryProtocolRequires() {
        byte[] message = LauncherEntryDBusConnection.updateSignal(
            7, "/com/canonical/unity/launcherentry/42", "application://kortty.desktop", 3, false);

        assertThat(message[0]).isEqualTo((byte) 'l');
        assertThat(message[1]).isEqualTo((byte) 4);
        assertThat(message[3]).isEqualTo((byte) 1);
        int bodyLength = message[4] & 0xff;
        int headerFieldsLength = message[12] & 0xff;
        // The header fields array is padded so the body starts on an eight-byte boundary.
        int bodyStart = 16 + headerFieldsLength + (headerFieldsLength % 8 == 0 ? 0 : 8 - headerFieldsLength % 8);
        assertThat(bodyStart % 8).isEqualTo(0);
        assertThat(message.length).isEqualTo(bodyStart + bodyLength);

        String wire = new String(message, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(wire).contains("com.canonical.Unity.LauncherEntry");
        assertThat(wire).contains("Update");
        assertThat(wire).contains("sa{sv}");
        assertThat(wire).contains("application://kortty.desktop");
        assertThat(wire).contains("count-visible");
        assertThat(wire).contains("urgent");

        // A zero count hides the counter, a positive one shows it.
        assertThat(countVisibleFlag(LauncherEntryDBusConnection.updateSignal(
            8, "/x", "application://kortty.desktop", 0, false))).isEqualTo(0);
        assertThat(countVisibleFlag(LauncherEntryDBusConnection.updateSignal(
            9, "/x", "application://kortty.desktop", 2, false))).isEqualTo(1);
    }

    /** The boolean that follows the {@code count-visible} key and its {@code b} variant signature. */
    private static int countVisibleFlag(byte[] message) {
        String wire = new String(message, java.nio.charset.StandardCharsets.ISO_8859_1);
        int key = wire.indexOf("count-visible");
        assertThat(key).isGreaterThan(0);
        // The key string and its NUL, then the variant signature (length byte, 'b', NUL); the
        // boolean itself is a uint32 and therefore padded to the next four-byte boundary.
        int offset = key + "count-visible".length() + 1 + 3;
        offset += offset % 4 == 0 ? 0 : 4 - offset % 4;
        return message[offset] & 0xff;
    }

    private static String constantPool(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
