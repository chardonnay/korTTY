package de.kortty.update;

import de.kortty.core.GlobalSettingsManager;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class UpdateCheckServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final PlatformProfile WINDOWS_X64 =
        new PlatformProfile(OperatingSystem.WINDOWS, "amd64", null, Set.of());

    @Test
    void manualCheckReportsNoUpdateWhenCurrentVersionIsLatest() throws Exception {
        Path dir = Files.createTempDirectory("kortty-update-check-current");
        try {
            GlobalSettingsManager settingsManager = new GlobalSettingsManager(dir);
            UpdateCheckService service = service(
                settingsManager,
                release("v2.2.0"),
                Clock.fixed(Instant.parse("2026-05-20T08:00:00Z"), BERLIN),
                new ArrayList<>());

            UpdateCheckResult result = service.checkManually();

            assertThat(result.status()).isEqualTo(UpdateCheckResult.Status.NO_UPDATE);
            assertThat(settingsManager.getSettings().getLastSuccessfulUpdateCheckMillis()).isGreaterThan(0L);
        } finally {
            deleteSettingsDirectory(dir);
        }
    }

    @Test
    void automaticCheckStaysSilentWhenCurrentVersionIsLatest() throws Exception {
        Path dir = Files.createTempDirectory("kortty-update-check-auto-current");
        try {
            GlobalSettingsManager settingsManager = new GlobalSettingsManager(dir);
            List<AvailableUpdate> notifications = new ArrayList<>();
            UpdateCheckService service = service(
                settingsManager,
                release("v2.2.0"),
                Clock.fixed(Instant.parse("2026-05-20T08:00:00Z"), BERLIN),
                notifications);

            service.runAutomaticCheck(UpdateCheckRunType.AUTOMATIC_STARTUP);

            assertThat(notifications).isEmpty();
        } finally {
            deleteSettingsDirectory(dir);
        }
    }

    @Test
    void ignoredVersionSuppressesAutomaticCheckButNotManualCheck() throws Exception {
        Path dir = Files.createTempDirectory("kortty-update-check-ignored");
        try {
            GlobalSettingsManager settingsManager = new GlobalSettingsManager(dir);
            settingsManager.getSettings().setIgnoredUpdateVersion("v2.3.0");
            UpdateCheckService service = service(
                settingsManager,
                release("v2.3.0"),
                Clock.fixed(Instant.parse("2026-05-20T08:00:00Z"), BERLIN),
                new ArrayList<>());

            assertThat(service.checkForUpdate(UpdateCheckRunType.AUTOMATIC_PERIODIC).status())
                .isEqualTo(UpdateCheckResult.Status.NO_UPDATE);
            assertThat(service.checkManually().status()).isEqualTo(UpdateCheckResult.Status.UPDATE_AVAILABLE);
        } finally {
            deleteSettingsDirectory(dir);
        }
    }

    @Test
    void snoozeUntilTomorrowUsesLocalCalendarDay() throws Exception {
        Path dir = Files.createTempDirectory("kortty-update-check-snooze");
        try {
            GlobalSettingsManager settingsManager = new GlobalSettingsManager(dir);
            settingsManager.getSettings().setSnoozedUpdateVersion("v2.3.0");
            settingsManager.getSettings().setUpdateSnoozedUntilLocalDate("2026-05-21");
            settingsManager.getSettings().setLastAutomaticUpdatePromptVersion("v2.3.0");
            settingsManager.getSettings().setLastAutomaticUpdatePromptLocalDate("2026-05-20");

            UpdateCheckService sameDay = service(
                settingsManager,
                release("v2.3.0"),
                Clock.fixed(Instant.parse("2026-05-20T20:30:00Z"), BERLIN),
                new ArrayList<>());
            assertThat(sameDay.checkForUpdate(UpdateCheckRunType.AUTOMATIC_STARTUP).status())
                .isEqualTo(UpdateCheckResult.Status.NO_UPDATE);

            UpdateCheckService nextDayStartup = service(
                settingsManager,
                release("v2.3.0"),
                Clock.fixed(Instant.parse("2026-05-20T22:30:00Z"), BERLIN),
                new ArrayList<>());
            assertThat(nextDayStartup.checkForUpdate(UpdateCheckRunType.AUTOMATIC_STARTUP).status())
                .isEqualTo(UpdateCheckResult.Status.UPDATE_AVAILABLE);
        } finally {
            deleteSettingsDirectory(dir);
        }
    }

    @Test
    void periodicAutomaticPromptForSameVersionIsThrottledForOneWeek() throws Exception {
        Path dir = Files.createTempDirectory("kortty-update-check-weekly");
        try {
            GlobalSettingsManager settingsManager = new GlobalSettingsManager(dir);
            settingsManager.getSettings().setLastAutomaticUpdatePromptVersion("v2.3.0");
            settingsManager.getSettings().setLastAutomaticUpdatePromptLocalDate("2026-05-14");

            UpdateCheckService beforeWeek = service(
                settingsManager,
                release("v2.3.0"),
                Clock.fixed(Instant.parse("2026-05-20T08:00:00Z"), BERLIN),
                new ArrayList<>());
            assertThat(beforeWeek.checkForUpdate(UpdateCheckRunType.AUTOMATIC_PERIODIC).status())
                .isEqualTo(UpdateCheckResult.Status.NO_UPDATE);

            UpdateCheckService afterWeek = service(
                settingsManager,
                release("v2.3.0"),
                Clock.fixed(Instant.parse("2026-05-21T08:00:00Z"), BERLIN),
                new ArrayList<>());
            assertThat(afterWeek.checkForUpdate(UpdateCheckRunType.AUTOMATIC_PERIODIC).status())
                .isEqualTo(UpdateCheckResult.Status.UPDATE_AVAILABLE);
        } finally {
            deleteSettingsDirectory(dir);
        }
    }

    private static UpdateCheckService service(
        GlobalSettingsManager settingsManager,
        UpdateRelease release,
        Clock clock,
        List<AvailableUpdate> notifications
    ) {
        return new UpdateCheckService(
            settingsManager,
            () -> release,
            new UpdateAssetSelector(),
            () -> WINDOWS_X64,
            () -> "2.2.0",
            clock,
            notifications::add);
    }

    private static UpdateRelease release(String tagName) {
        return new UpdateRelease(
            tagName,
            "korTTY " + tagName,
            URI.create("https://example.test/releases/" + tagName),
            Instant.parse("2026-05-20T10:00:00Z"),
            false,
            false,
            List.of(new UpdateAsset(
                "korTTY-Windows-" + tagName.substring(1) + "-x86_64.msi",
                URI.create("https://example.test/download/" + tagName),
                1,
                "sha256:0000000000000000000000000000000000000000000000000000000000000000")));
    }

    private static void deleteSettingsDirectory(Path dir) throws Exception {
        Files.deleteIfExists(dir.resolve("global-settings.xml"));
        Files.deleteIfExists(dir);
    }
}
