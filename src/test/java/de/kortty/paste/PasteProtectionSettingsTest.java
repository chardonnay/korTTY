package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.GlobalSettings;
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
}
