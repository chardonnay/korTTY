package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.ConnectionSource;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

class PasteProtectionSettingsTest {

    @Test
    void theDefaultsWarnUnlessBracketedAndAbove5KiB() {
        assertThat(PasteProtectionSettings.DEFAULTS.mode()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(PasteProtectionSettings.DEFAULTS.largeWarningKiB()).isEqualTo(5);
        assertThat(PasteProtectionSettings.DEFAULTS.largeWarningBytes()).isEqualTo(5 * 1024L);
        assertThat(PasteProtectionSettings.DEFAULTS.largeWarningEnabled()).isTrue();
        assertThat(PasteProtectionSettings.DEFAULTS.asksForAnything()).isTrue();
    }

    @Test
    void disabledNeverAsks() {
        assertThat(PasteProtectionSettings.DISABLED.mode()).isEqualTo(PasteWarningMode.OFF);
        assertThat(PasteProtectionSettings.DISABLED.largeWarningKiB()).isEqualTo(0);
        assertThat(PasteProtectionSettings.DISABLED.largeWarningBytes()).isEqualTo(0L);
        assertThat(PasteProtectionSettings.DISABLED.largeWarningEnabled()).isFalse();
        assertThat(PasteProtectionSettings.DISABLED.asksForAnything()).isFalse();
    }

    @Test
    void eitherCheckAloneStillAsks() {
        assertThat(new PasteProtectionSettings(PasteWarningMode.OFF, 1).asksForAnything()).isTrue();
        assertThat(new PasteProtectionSettings(PasteWarningMode.UNLESS_BRACKETED, 0).asksForAnything()).isTrue();
        assertThat(new PasteProtectionSettings(PasteWarningMode.ALWAYS, 0).asksForAnything()).isTrue();
    }

    @Test
    void theThresholdIsClampedToItsRange() {
        assertThat(new PasteProtectionSettings(PasteWarningMode.ALWAYS, -1).largeWarningKiB()).isEqualTo(0);
        assertThat(new PasteProtectionSettings(PasteWarningMode.ALWAYS, Integer.MIN_VALUE).largeWarningKiB())
            .isEqualTo(0);
        assertThat(new PasteProtectionSettings(PasteWarningMode.ALWAYS, 10_240).largeWarningKiB()).isEqualTo(10_240);
        assertThat(new PasteProtectionSettings(PasteWarningMode.ALWAYS, 10_241).largeWarningKiB()).isEqualTo(10_240);
        assertThat(new PasteProtectionSettings(PasteWarningMode.ALWAYS, Integer.MAX_VALUE).largeWarningBytes())
            .isEqualTo(10_240 * 1024L);
        assertThat(PasteProtectionSettings.clampLargeWarningKiB(64)).isEqualTo(64);
        assertThat(PasteProtectionSettings.MAX_LARGE_WARNING_KIB).isEqualTo(10_240);
    }

    @Test
    void aMissingModeMeansTheDefaultMode() {
        assertThat(new PasteProtectionSettings(null, 5)).isEqualTo(PasteProtectionSettings.DEFAULTS);
    }

    @Test
    void freshOrMissingGlobalSettingsYieldTheDefaults() {
        assertThat(PasteProtectionSettings.from(new GlobalSettings())).isEqualTo(PasteProtectionSettings.DEFAULTS);
        assertThat(PasteProtectionSettings.from(null)).isEqualTo(PasteProtectionSettings.DEFAULTS);
    }

    @Test
    void theGlobalSettingsChooseTheModeAndTheThreshold() {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteWarningMode(PasteWarningMode.ALWAYS);
        settings.setPasteLargeWarningKiB(64);
        assertThat(PasteProtectionSettings.from(settings))
            .isEqualTo(new PasteProtectionSettings(PasteWarningMode.ALWAYS, 64));

        settings.setPasteWarningMode(PasteWarningMode.OFF);
        settings.setPasteLargeWarningKiB(0);
        assertThat(PasteProtectionSettings.from(settings)).isEqualTo(PasteProtectionSettings.DISABLED);
    }

    private static GlobalSettings global(PasteWarningMode mode, int largeKiB, int lineDelayMs) {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteWarningMode(mode);
        settings.setPasteLargeWarningKiB(largeKiB);
        settings.setPasteLineDelayMs(lineDelayMs);
        return settings;
    }

    private static ServerConnection connection(PasteWarningMode mode, Integer lineDelayMs) {
        ServerConnection connection = new ServerConnection("prod", "db.example.com", 22, "root");
        connection.setPasteWarningMode(mode);
        connection.setPasteLineDelayMs(lineDelayMs);
        return connection;
    }

    private static ServerConnection teamwork(PasteWarningMode mode, Integer lineDelayMs) {
        ServerConnection connection = connection(mode, lineDelayMs);
        connection.setConnectionSource(ConnectionSource.TEAMWORK);
        return connection;
    }

    @Test
    void aConnectionWithoutItsOwnModeFollowsTheGlobalSettings() {
        GlobalSettings settings = global(PasteWarningMode.ALWAYS, 64, 0);

        assertThat(PasteProtectionSettings.resolve(settings, null)).isEqualTo(PasteProtectionSettings.from(settings));
        assertThat(PasteProtectionSettings.resolve(settings, connection(null, null)))
            .isEqualTo(new PasteProtectionSettings(PasteWarningMode.ALWAYS, 64));
        assertThat(PasteProtectionSettings.connectionWarningMode(PasteWarningMode.ALWAYS, connection(null, null)))
            .isNull();
        assertThat(PasteProtectionSettings.resolve(null, null)).isEqualTo(PasteProtectionSettings.DEFAULTS);
    }

    @Test
    void aProductionConnectionAsksForEveryMultiLinePasteWhileTheDefaultsStayGlobal() {
        GlobalSettings settings = global(PasteWarningMode.UNLESS_BRACKETED, 5, 0);

        PasteProtectionSettings resolved = PasteProtectionSettings.resolve(settings,
            connection(PasteWarningMode.ALWAYS, null));

        assertThat(resolved).isEqualTo(new PasteProtectionSettings(PasteWarningMode.ALWAYS, 5));
        assertThat(PasteProtectionSettings.from(settings).mode()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(new PasteDecision(resolved).reasons("echo one\necho two", true))
            .containsExactly(PasteReason.MULTI_LINE);
    }

    @Test
    void aConnectionOfYourOwnAppliesItsModeInBothDirections() {
        GlobalSettings strict = global(PasteWarningMode.ALWAYS, 5, 0);

        assertThat(PasteProtectionSettings.resolve(strict, connection(PasteWarningMode.OFF, null)))
            .isEqualTo(new PasteProtectionSettings(PasteWarningMode.OFF, 5));
        assertThat(PasteProtectionSettings.connectionWarningMode(PasteWarningMode.ALWAYS,
            connection(PasteWarningMode.OFF, null))).isEqualTo(PasteWarningMode.OFF);
        assertThat(PasteProtectionSettings.connectionWarningMode(PasteWarningMode.ALWAYS,
            connection(PasteWarningMode.ALWAYS, null))).isEqualTo(PasteWarningMode.ALWAYS);
    }

    @Test
    void aTeamworkConnectionCanOnlyMakeTheWarningStricter() {
        GlobalSettings unlessBracketed = global(PasteWarningMode.UNLESS_BRACKETED, 5, 0);

        assertThat(PasteProtectionSettings.resolve(unlessBracketed, teamwork(PasteWarningMode.ALWAYS, null)).mode())
            .isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(PasteProtectionSettings.resolve(unlessBracketed, teamwork(PasteWarningMode.OFF, null)).mode())
            .isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(PasteProtectionSettings.connectionWarningMode(PasteWarningMode.UNLESS_BRACKETED,
            teamwork(PasteWarningMode.OFF, null))).isNull();
        assertThat(PasteProtectionSettings.connectionWarningMode(PasteWarningMode.UNLESS_BRACKETED,
            teamwork(PasteWarningMode.UNLESS_BRACKETED, null))).isNull();
        assertThat(PasteProtectionSettings.connectionWarningMode(null, teamwork(PasteWarningMode.ALWAYS, null)))
            .isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(PasteProtectionSettings.connectionWarningMode(PasteWarningMode.OFF,
            teamwork(PasteWarningMode.UNLESS_BRACKETED, null))).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
    }

    @Test
    void theSizeCheckAlwaysComesFromTheGlobalSettings() {
        GlobalSettings settings = global(PasteWarningMode.UNLESS_BRACKETED, 0, 0);

        assertThat(PasteProtectionSettings.resolve(settings, connection(PasteWarningMode.ALWAYS, null))
            .largeWarningKiB()).isEqualTo(0);
        assertThat(PasteProtectionSettings.resolve(global(PasteWarningMode.OFF, 64, 0),
            connection(PasteWarningMode.OFF, null))).isEqualTo(new PasteProtectionSettings(PasteWarningMode.OFF, 64));
    }

    @Test
    void withoutGlobalSettingsTheConnectionsModeStillApplies() {
        assertThat(PasteProtectionSettings.resolve(null, connection(PasteWarningMode.ALWAYS, null)))
            .isEqualTo(new PasteProtectionSettings(PasteWarningMode.ALWAYS,
                PasteProtectionSettings.DEFAULT_LARGE_WARNING_KIB));
    }

    @Test
    void theLineDelayIsTheConnectionsOwnElseTheGlobalOne() {
        GlobalSettings paced = global(PasteWarningMode.UNLESS_BRACKETED, 5, 200);

        assertThat(PasteProtectionSettings.resolveLineDelayMs(paced, null)).isEqualTo(200);
        assertThat(PasteProtectionSettings.resolveLineDelayMs(paced, connection(null, null))).isEqualTo(200);
        assertThat(PasteProtectionSettings.resolveLineDelayMs(paced, connection(null, 50))).isEqualTo(50);
        assertThat(PasteProtectionSettings.resolveLineDelayMs(paced, connection(null, 0)))
            .isEqualTo(0);
        assertThat(PasteProtectionSettings.resolveLineDelayMs(global(PasteWarningMode.OFF, 5, 0),
            connection(null, 120))).isEqualTo(120);
        assertThat(PasteProtectionSettings.resolveLineDelayMs(null, null)).isEqualTo(0);
        assertThat(PasteProtectionSettings.resolveLineDelayMs(null, connection(null, 80))).isEqualTo(80);
    }

    @Test
    void aTeamworkConnectionsLineDelayAppliesBecauseItOnlySlowsAPasteDown() {
        assertThat(PasteProtectionSettings.resolveLineDelayMs(global(PasteWarningMode.OFF, 5, 0),
            teamwork(null, 150))).isEqualTo(150);
        assertThat(PasteProtectionSettings.resolveLineDelayMs(global(PasteWarningMode.OFF, 5, 300),
            teamwork(null, 0))).isEqualTo(0);
    }

    @Test
    void thePolicyFloorRaisesEveryLevelBelowIt() {
        GlobalSettings off = global(PasteWarningMode.OFF, 64, 0);
        assertThat(PasteProtectionSettings.resolve(off, null, PasteWarningMode.UNLESS_BRACKETED))
            .isEqualTo(new PasteProtectionSettings(PasteWarningMode.UNLESS_BRACKETED, 64));
        // A connection of your own cannot go below the floor either, in either direction.
        assertThat(PasteProtectionSettings.resolve(global(PasteWarningMode.ALWAYS, 5, 0),
            connection(PasteWarningMode.OFF, null), PasteWarningMode.ALWAYS).mode()).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(PasteProtectionSettings.resolve(off, connection(PasteWarningMode.OFF, null),
            PasteWarningMode.UNLESS_BRACKETED).mode()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        // Nor a teamwork connection, nor missing settings.
        assertThat(PasteProtectionSettings.resolve(off, teamwork(PasteWarningMode.OFF, null),
            PasteWarningMode.ALWAYS).mode()).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(PasteProtectionSettings.resolve(null, null, PasteWarningMode.ALWAYS))
            .isEqualTo(new PasteProtectionSettings(PasteWarningMode.ALWAYS, PasteProtectionSettings.DEFAULT_LARGE_WARNING_KIB));
    }

    @Test
    void thePolicyFloorNeverLowersAStricterChoiceAndNullMeansNoFloor() {
        GlobalSettings always = global(PasteWarningMode.ALWAYS, 64, 0);
        assertThat(PasteProtectionSettings.resolve(always, null, PasteWarningMode.OFF))
            .isEqualTo(PasteProtectionSettings.resolve(always, null));
        assertThat(PasteProtectionSettings.resolve(global(PasteWarningMode.OFF, 64, 0),
            connection(PasteWarningMode.ALWAYS, null), PasteWarningMode.UNLESS_BRACKETED).mode())
            .isEqualTo(PasteWarningMode.ALWAYS);
        GlobalSettings off = global(PasteWarningMode.OFF, 0, 0);
        assertThat(PasteProtectionSettings.resolve(off, connection(PasteWarningMode.OFF, null), null))
            .isEqualTo(PasteProtectionSettings.resolve(off, connection(PasteWarningMode.OFF, null)));
    }
}
