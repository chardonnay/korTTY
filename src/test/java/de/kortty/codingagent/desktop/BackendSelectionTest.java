package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
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
                LinuxDesktopId.class, PlatformProbe.class)) {
            assertThat(constantPool(type)).doesNotContain("java/awt");
        }
        assertThat(constantPool(MacDockBadgeBackend.class)).contains("java/awt/Taskbar");
        assertThat(constantPool(WindowsTrayNotifierBackend.class)).contains("java/awt/SystemTray");
    }

    @Test
    void launcherEntryBackendEmitsLatestCountOnTheBackgroundExecutor() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        List<Runnable> queued = new ArrayList<>();
        Function<List<String>, ExternalCommandRunner.Result> runner = argv -> {
            commands.add(argv);
            return new ExternalCommandRunner.Result(0, "", false);
        };
        LinuxLauncherEntryBadgeBackend backend =
            new LinuxLauncherEntryBadgeBackend("kortty.desktop", false, runner, queued::add);

        backend.showCount(1);
        backend.showCount(2);
        backend.showCount(3);
        assertThat(queued).hasSize(1);

        queued.remove(0).run();
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).get(0)).isEqualTo("gdbus");
        assertThat(commands.get(0).get(8)).isEqualTo("{'count': <int64 3>, 'count-visible': <true>, 'urgent': <false>}");

        backend.requestAttention();
        queued.remove(0).run();
        assertThat(commands.get(1).get(8)).isEqualTo("{'count': <int64 3>, 'count-visible': <true>, 'urgent': <true>}");

        backend.showCount(0);
        queued.remove(0).run();
        assertThat(commands.get(2).get(8)).isEqualTo("{'count': <int64 0>, 'count-visible': <false>, 'urgent': <false>}");
        assertThat(backend.isSupported()).isTrue();
    }

    @Test
    void launcherEntryBackendDisablesItselfAfterAFailedEmit() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        Function<List<String>, ExternalCommandRunner.Result> runner = argv -> {
            commands.add(argv);
            return new ExternalCommandRunner.Result(1, "no session bus", false);
        };
        LinuxLauncherEntryBadgeBackend backend =
            new LinuxLauncherEntryBadgeBackend("kortty.desktop", false, runner, DIRECT);

        backend.showCount(2);
        assertThat(backend.isSupported()).isFalse();

        backend.showCount(3);
        backend.requestAttention();
        backend.close();
        assertThat(commands).hasSize(1);
    }

    @Test
    void launcherEntryBackendSurvivesARejectingExecutorAndCloseClears() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        Function<List<String>, ExternalCommandRunner.Result> runner = argv -> {
            commands.add(argv);
            return new ExternalCommandRunner.Result(0, "", false);
        };
        Executor rejecting = task -> {
            throw new java.util.concurrent.RejectedExecutionException("shut down");
        };
        LinuxLauncherEntryBadgeBackend rejected =
            new LinuxLauncherEntryBadgeBackend("kortty.desktop", false, runner, rejecting);
        rejected.showCount(1);
        assertThat(commands).isEmpty();
        assertThat(rejected.isSupported()).isTrue();

        LinuxLauncherEntryBadgeBackend direct =
            new LinuxLauncherEntryBadgeBackend("kortty.desktop", false, runner, DIRECT);
        direct.showCount(4);
        direct.close();
        assertThat(commands).hasSize(2);
        assertThat(commands.get(1).get(8)).isEqualTo("{'count': <int64 0>, 'count-visible': <false>, 'urgent': <false>}");
    }

    private static String constantPool(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
