package de.kortty.core;

import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import java.awt.Color;

import static com.google.common.truth.Truth.assertThat;

class ExportBrandingTest {

    @Test
    void defaultsKeepTheWatermarkOffAndTheFooterOn() {
        ExportBranding branding = ExportBranding.defaults();
        assertThat(branding.watermarkEnabled()).isFalse();
        assertThat(branding.footerEnabled()).isTrue();
        assertThat(branding.footerUsesDefaultText()).isTrue();
        assertThat(branding.footerLine()).contains(ExportBranding.REPOSITORY_URL);
    }

    @Test
    void freshSettingsMatchTheDefaults() {
        ExportBranding branding = ExportBranding.fromSettings(new GlobalSettings());
        assertThat(branding.watermarkEnabled()).isFalse();
        assertThat(branding.footerEnabled()).isTrue();
        assertThat(branding.watermarkText()).isEqualTo(ExportBranding.DEFAULT_WATERMARK_TEXT);
        assertThat(branding.watermarkColor()).isEqualTo(ExportBranding.DEFAULT_WATERMARK_COLOR);
    }

    @Test
    void customTextIsUsedVerbatimWithoutTheRepositoryLink() {
        GlobalSettings settings = new GlobalSettings();
        settings.setExportFooterText("ACME Corp — internal");
        settings.setPdfWatermarkText("CONFIDENTIAL");
        settings.setPdfWatermarkEnabled(true);
        ExportBranding branding = ExportBranding.fromSettings(settings);

        assertThat(branding.footerUsesDefaultText()).isFalse();
        assertThat(branding.footerLine()).isEqualTo("ACME Corp — internal");
        assertThat(branding.footerLine()).doesNotContain(ExportBranding.REPOSITORY_URL);
        assertThat(branding.watermarkUsesDefaultText()).isFalse();
        assertThat(branding.watermarkText()).isEqualTo("CONFIDENTIAL");
    }

    @Test
    void blankCustomTextFallsBackToTheDefault() {
        GlobalSettings settings = new GlobalSettings();
        settings.setExportFooterText("   ");
        settings.setPdfWatermarkText("");
        ExportBranding branding = ExportBranding.fromSettings(settings);

        assertThat(branding.footerUsesDefaultText()).isTrue();
        assertThat(branding.watermarkText()).isEqualTo(ExportBranding.DEFAULT_WATERMARK_TEXT);
    }

    @Test
    void watermarkColourRoundTripsThroughSettings() {
        GlobalSettings settings = new GlobalSettings();
        settings.setPdfWatermarkColor("#ff8800");
        assertThat(ExportBranding.fromSettings(settings).watermarkColor())
            .isEqualTo(new Color(0xff, 0x88, 0x00));
        assertThat(ExportBranding.toHex(new Color(0xff, 0x88, 0x00))).isEqualTo("#ff8800");
        // An unreadable value must not break the export.
        settings.setPdfWatermarkColor("not-a-colour");
        assertThat(ExportBranding.fromSettings(settings).watermarkColor())
            .isEqualTo(ExportBranding.DEFAULT_WATERMARK_COLOR);
    }

    @Test
    void disabledFooterIsReportedAsDisabled() {
        GlobalSettings settings = new GlobalSettings();
        settings.setExportFooterEnabled(false);
        assertThat(ExportBranding.fromSettings(settings).footerEnabled()).isFalse();
    }
}
