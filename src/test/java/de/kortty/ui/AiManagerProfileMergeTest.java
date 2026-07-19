package de.kortty.ui;

import de.kortty.model.AiProfile;
import java.util.List;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiManagerProfileMergeTest {

    @Test
    void externalRagAssignmentsDoNotDiscardUnsavedProfileDrafts() {
        AiProfile draft = profile("existing", "Unsaved name");
        draft.setModel("unsaved-model");
        AiProfile unsavedNew = profile("new-draft", "New unsaved profile");
        AiProfile stored = profile("existing", "Persisted name");
        stored.setModel("persisted-model");
        stored.setRagStoreIds(List.of("knowledge"));
        AiProfile externallyCreated = profile("local-model", "Local model");

        List<AiProfile> merged = AiManagerDialog.mergeExternalProfiles(
            List.of(draft, unsavedNew), List.of(stored, externallyCreated));

        assertThat(merged).hasSize(3);
        AiProfile existing = merged.stream()
            .filter(profile -> "existing".equals(profile.getId())).findFirst().orElseThrow();
        assertThat(existing).isSameInstanceAs(draft);
        assertThat(existing.getName()).isEqualTo("Unsaved name");
        assertThat(existing.getModel()).isEqualTo("unsaved-model");
        assertThat(existing.getRagStoreIds()).containsExactly("knowledge");
        assertThat(merged.stream().map(AiProfile::getId).toList())
            .containsExactly("existing", "local-model", "new-draft").inOrder();
    }

    private static AiProfile profile(String id, String name) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        return profile;
    }
}
