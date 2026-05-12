package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SnippetEditorProfile;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SnippetEditorProfileSupportTest {

    @Test
    void builtInProfilesProvideTenIntellijStyleChoices() {
        assertThat(SnippetEditorProfileSupport.builtInProfiles()).hasSize(10);
        assertThat(SnippetEditorProfileSupport.builtInProfiles().stream()
            .map(SnippetEditorProfile::getName)
            .toList()).contains("Darcula");
    }

    @Test
    void resolveActiveProfileUsesSelectedCustomProfile() {
        GlobalSettings settings = new GlobalSettings();
        SnippetEditorProfile custom = SnippetEditorProfileSupport.fromCurrentSettings(
            "#111111",
            "#222222",
            "LINE",
            "#333333");
        custom.setId("custom-1");
        custom.setName("My profile");
        settings.setSnippetEditorProfiles(java.util.List.of(custom));
        settings.setSelectedSnippetEditorProfileId("custom-1");

        SnippetEditorProfile resolved = SnippetEditorProfileSupport.resolveActiveProfile(
            settings,
            SnippetEditorProfileSupport.fromCurrentSettings("#FFFFFF", "#000000", "BLOCK", "#FF0000"));

        assertThat(resolved.getName()).isEqualTo("My profile");
        assertThat(resolved.getBackgroundColor()).isEqualTo("#222222");
        assertThat(resolved.getCursorStyle()).isEqualTo("LINE");
    }

    @Test
    void resolveActiveProfilePrefersCustomProfileOverBuiltInWithSameId() {
        GlobalSettings settings = new GlobalSettings();
        SnippetEditorProfile custom = SnippetEditorProfileSupport.fromCurrentSettings(
            "#111111",
            "#222222",
            "LINE",
            "#333333");
        custom.setId("preset-darcula");
        custom.setName("Custom Darcula");
        settings.setSnippetEditorProfiles(java.util.List.of(custom));
        settings.setSelectedSnippetEditorProfileId("preset-darcula");

        SnippetEditorProfile resolved = SnippetEditorProfileSupport.resolveActiveProfile(
            settings,
            SnippetEditorProfileSupport.fromCurrentSettings("#FFFFFF", "#000000", "BLOCK", "#FF0000"));

        assertThat(resolved.getName()).isEqualTo("Custom Darcula");
        assertThat(resolved.getBackgroundColor()).isEqualTo("#222222");
    }

    @Test
    void normalizeRejectsInvalidColorsAndCursorStyle() {
        SnippetEditorProfile profile = new SnippetEditorProfile();
        profile.setId("bad");
        profile.setName("Bad");
        profile.setForegroundColor("red");
        profile.setBackgroundColor("#010203");
        profile.setCursorStyle("BLINK_BLOCK");
        profile.setCursorColor("#abcdef");

        SnippetEditorProfile normalized = SnippetEditorProfileSupport.normalize(profile);

        assertThat(normalized.getForegroundColor()).isEqualTo("#A9B7C6");
        assertThat(normalized.getBackgroundColor()).isEqualTo("#010203");
        assertThat(normalized.getCursorStyle()).isEqualTo("BLOCK");
        assertThat(normalized.getCursorColor()).isEqualTo("#ABCDEF");
    }
}
