package de.kortty.core;

import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiProfileSelectionSupportTest {

    @Test
    void defaultProfileUsesConfiguredIdAfterDefaultChange() {
        AiProfile local = profile("legacy-default", "local");
        AiProfile minimax = profile("228fa3f3-e87c-47a4-90aa-da26e79ffc9f", "MiniMAX");
        List<AiProfile> profiles = List.of(local, minimax);

        AiProfile selected = AiProfileSelectionSupport.defaultProfile(
            profiles,
            "228fa3f3-e87c-47a4-90aa-da26e79ffc9f");

        assertThat(selected).isSameInstanceAs(minimax);
    }

    @Test
    void reorderByRequestedOrDefaultPlacesCurrentDefaultFirst() {
        AiProfile local = profile("legacy-default", "local");
        AiProfile minimax = profile("minimax-profile", "MiniMAX");
        List<AiProfile> profiles = List.of(local, minimax);

        List<AiProfile> reordered = AiProfileSelectionSupport.reorderByRequestedOrDefault(
            profiles,
            null,
            "minimax-profile");

        assertThat(reordered).containsExactly(minimax, local).inOrder();
    }

    @Test
    void requestedProfileLookupOverridesDefault() {
        AiProfile local = profile("legacy-default", "local");
        AiProfile minimax = profile("minimax-profile", "MiniMAX");
        List<AiProfile> profiles = List.of(local, minimax);

        List<AiProfile> reordered = AiProfileSelectionSupport.reorderByRequestedOrDefault(
            profiles,
            "local",
            "minimax-profile");

        assertThat(reordered).containsExactly(local, minimax).inOrder();
    }

    @Test
    void normalizeDefaultProfileIdFallsBackToPersistedProfileWhenPreferredIdIsStale() {
        AiProfile local = profile("legacy-default", "local");
        AiProfile minimax = profile("minimax-profile", "MiniMAX");

        String normalized = AiProfileSelectionSupport.normalizeDefaultProfileId(
            "deleted-profile",
            List.of(local, minimax));

        assertThat(normalized).isEqualTo("legacy-default");
    }

    private static AiProfile profile(String id, String name) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        return profile;
    }
}
