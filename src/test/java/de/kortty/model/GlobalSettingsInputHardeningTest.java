package de.kortty.model;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class GlobalSettingsInputHardeningTest {

    @Test
    void inputHardeningIsDisabledByDefault() {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.isSnippetInputHardeningEnabled()).isFalse();
        // Sub-option CSV: null = never saved, the UI resolves that to "all pre-ticked".
        assertThat(settings.getSnippetInputHardeningOptions()).isNull();
    }

    @Test
    void enabledFlagRoundTripsAndNullMeansOff() {
        GlobalSettings settings = new GlobalSettings();
        settings.setSnippetInputHardeningEnabled(true);
        assertThat(settings.isSnippetInputHardeningEnabled()).isTrue();
        settings.setSnippetInputHardeningEnabled(null);
        assertThat(settings.isSnippetInputHardeningEnabled()).isFalse();
    }

    @Test
    void maxFileSizeDefaultsToTenMbWhenUnset() {
        assertThat(new GlobalSettings().getSnippetInputHardeningMaxFileSizeMb()).isEqualTo(10);
    }

    @Test
    void maxFileSizeSetterClampsIntoTheAllowedRange() {
        GlobalSettings settings = new GlobalSettings();
        settings.setSnippetInputHardeningMaxFileSizeMb(0);
        assertThat(settings.getSnippetInputHardeningMaxFileSizeMb()).isEqualTo(0);
        settings.setSnippetInputHardeningMaxFileSizeMb(-1);
        assertThat(settings.getSnippetInputHardeningMaxFileSizeMb()).isEqualTo(0);
        settings.setSnippetInputHardeningMaxFileSizeMb(4096);
        assertThat(settings.getSnippetInputHardeningMaxFileSizeMb()).isEqualTo(1024);
        settings.setSnippetInputHardeningMaxFileSizeMb(25);
        assertThat(settings.getSnippetInputHardeningMaxFileSizeMb()).isEqualTo(25);
    }

    @Test
    void maxFileSizeSetterNullRestoresTheDefault() {
        GlobalSettings settings = new GlobalSettings();
        settings.setSnippetInputHardeningMaxFileSizeMb(500);
        settings.setSnippetInputHardeningMaxFileSizeMb(null);
        assertThat(settings.getSnippetInputHardeningMaxFileSizeMb()).isEqualTo(10);
    }

    @Test
    void optionsCsvRoundTripsIncludingEmptyAndNull() {
        GlobalSettings settings = new GlobalSettings();
        settings.setSnippetInputHardeningOptions("PARAM_VALIDATION,FILE_CHECKS");
        assertThat(settings.getSnippetInputHardeningOptions()).isEqualTo("PARAM_VALIDATION,FILE_CHECKS");
        // "" is a saved "clear" and must survive as-is (distinct from null = never saved).
        settings.setSnippetInputHardeningOptions("");
        assertThat(settings.getSnippetInputHardeningOptions()).isEmpty();
        settings.setSnippetInputHardeningOptions(null);
        assertThat(settings.getSnippetInputHardeningOptions()).isNull();
    }
}
