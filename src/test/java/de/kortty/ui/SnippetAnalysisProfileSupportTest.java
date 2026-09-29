package de.kortty.ui;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The profile choice before a Full code analysis: offered options, preselection, fallback, submenu. */
class SnippetAnalysisProfileSupportTest {

    private static AiProfile profile(String id, String name, String model) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        profile.setModel(model);
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        return profile;
    }

    private static List<SnippetAnalysisProfileSupport.Option> twoProfiles() {
        return SnippetAnalysisProfileSupport.options(
            List.of(profile("a", "Alpha", "m1"), profile("b", "Beta", "m2")), "b");
    }

    @Test
    void optionsMarkTheConfiguredDefaultAndKeepTheGivenOrder() {
        List<SnippetAnalysisProfileSupport.Option> options = twoProfiles();
        assertThat(options.stream().map(SnippetAnalysisProfileSupport.Option::id).toList())
            .containsExactly("a", "b").inOrder();
        assertThat(options.get(0).isDefault()).isFalse();
        assertThat(options.get(1).isDefault()).isTrue();
        assertThat(options.get(1).model()).isEqualTo("m2");
        assertThat(options.get(1).connectionMode()).isEqualTo(AiConnectionMode.HTTP_API);
    }

    @Test
    void anUnknownDefaultFallsBackToTheFirstProfileAndBlankIdsAreSkipped() {
        List<AiProfile> profiles = new ArrayList<>();
        profiles.add(null);
        profiles.add(profile(" ", "Nameless id", "x"));
        profiles.add(profile("a", "", "m1"));
        profiles.add(profile("b", "Beta", "m2"));
        List<SnippetAnalysisProfileSupport.Option> options = SnippetAnalysisProfileSupport.options(profiles, "gone");
        assertThat(options).hasSize(2);
        assertThat(options.get(0).isDefault()).isTrue();
        // A profile without a name is listed by its id.
        assertThat(options.get(0).name()).isEqualTo("a");
        assertThat(SnippetAnalysisProfileSupport.options(null, null)).isEmpty();
    }

    @Test
    void theRememberedProfileWinsWhileItExistsElseTheDefaultApplies() {
        List<SnippetAnalysisProfileSupport.Option> options = twoProfiles();
        assertThat(SnippetAnalysisProfileSupport.resolveSelection(options, "a")).isEqualTo("a");
        // Removed profile: back to the default.
        assertThat(SnippetAnalysisProfileSupport.resolveSelection(options, "deleted")).isEqualTo("b");
        assertThat(SnippetAnalysisProfileSupport.resolveSelection(options, null)).isEqualTo("b");
        assertThat(SnippetAnalysisProfileSupport.resolveSelection(options, "  ")).isEqualTo("b");
        assertThat(SnippetAnalysisProfileSupport.resolveSelection(List.of(), "a")).isNull();
    }

    @Test
    void aChoiceIsOnlyOfferedForSeveralProfilesAndASwitchingHost() {
        assertThat(SnippetAnalysisProfileSupport.choiceOffered(twoProfiles(), true)).isTrue();
        assertThat(SnippetAnalysisProfileSupport.choiceOffered(twoProfiles(), false)).isFalse();
        assertThat(SnippetAnalysisProfileSupport.choiceOffered(
            SnippetAnalysisProfileSupport.options(List.of(profile("a", "Alpha", "m")), "a"), true)).isFalse();
        assertThat(SnippetAnalysisProfileSupport.choiceOffered(List.of(), true)).isFalse();
    }

    @Test
    void theSubmenuListsEveryProfileAndFindLooksThemUpById() {
        List<SnippetAnalysisProfileSupport.Option> options = twoProfiles();
        assertThat(options.stream().map(SnippetAnalysisProfileSupport.Option::name).toList())
            .containsExactly("Alpha", "Beta").inOrder();
        assertThat(SnippetAnalysisProfileSupport.find(options, "b").name()).isEqualTo("Beta");
        assertThat(SnippetAnalysisProfileSupport.find(options, "zzz")).isNull();
        assertThat(SnippetAnalysisProfileSupport.find(options, null)).isNull();
    }

    @Test
    void theAnalysisDefaultIsTheCodingRoleProfileWhenItExistsElseTheDefaultProfile() {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiProfiles(new ArrayList<>(List.of(profile("a", "Alpha", "m1"), profile("b", "Beta", "m2"))));
        settings.setDefaultAiProfileId("a");
        assertThat(SnippetAnalysisController.analysisDefaultProfileId(settings)).isEqualTo("a");
        settings.setCodingAiProfileId("b");
        assertThat(SnippetAnalysisController.analysisDefaultProfileId(settings)).isEqualTo("b");
        settings.setCodingAiProfileId("missing");
        assertThat(SnippetAnalysisController.analysisDefaultProfileId(settings)).isEqualTo("a");
        assertThat(SnippetAnalysisController.analysisDefaultProfileId(null)).isNull();
    }
}
