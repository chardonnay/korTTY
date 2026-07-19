package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiWorkload;
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

    @Test
    void workloadProfileUsesDedicatedCodingAndTextAssignments() {
        AiProfile fallback = profile("fallback", "Fallback");
        AiProfile text = profile("text", "Text");
        AiProfile coding = profile("coding", "Coding");
        List<AiProfile> profiles = List.of(fallback, text, coding);

        assertThat(AiProfileSelectionSupport.workloadProfile(
            profiles, AiWorkload.TEXT, "text", "coding", "fallback")).isSameInstanceAs(text);
        assertThat(AiProfileSelectionSupport.workloadProfile(
            profiles, AiWorkload.CODING, "text", "coding", "fallback")).isSameInstanceAs(coding);
    }

    @Test
    void workloadProfileFallsBackWhenRoleAssignmentIsMissing() {
        AiProfile fallback = profile("fallback", "Fallback");

        assertThat(AiProfileSelectionSupport.workloadProfile(
            List.of(fallback), AiWorkload.CODING, null, "deleted", "fallback"))
            .isSameInstanceAs(fallback);
    }

    @Test
    void actionProfileAppliesExplicitSecurityConnectionWorkloadDefaultOrder() {
        AiProfile fallback = profile("fallback", "Fallback");
        AiProfile text = profile("text", "Text");
        AiProfile coding = profile("coding", "Coding");
        AiProfile connection = profile("connection", "Connection");
        AiProfile security = profile("security", "Security");
        AiProfile explicit = profile("explicit", "Explicit");
        List<AiProfile> profiles = List.of(fallback, text, coding, connection, security, explicit);

        assertThat(AiProfileSelectionSupport.actionProfile(
            profiles, "explicit", true, "security", "connection", AiWorkload.CODING,
            "text", "coding", "fallback")).isSameInstanceAs(explicit);
        assertThat(AiProfileSelectionSupport.actionProfile(
            profiles, null, true, "security", "connection", AiWorkload.CODING,
            "text", "coding", "fallback")).isSameInstanceAs(security);
        assertThat(AiProfileSelectionSupport.actionProfile(
            profiles, null, false, "security", "connection", AiWorkload.CODING,
            "text", "coding", "fallback")).isSameInstanceAs(connection);
        assertThat(AiProfileSelectionSupport.actionProfile(
            profiles, null, false, "security", null, AiWorkload.CODING,
            "text", "coding", "fallback")).isSameInstanceAs(coding);
        assertThat(AiProfileSelectionSupport.actionProfile(
            List.of(fallback), "deleted", true, "deleted", "deleted", AiWorkload.TEXT,
            "deleted", "deleted", "fallback")).isSameInstanceAs(fallback);
    }

    private static AiProfile profile(String id, String name) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        return profile;
    }
}
