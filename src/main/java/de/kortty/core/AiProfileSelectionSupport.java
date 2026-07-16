package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiWorkload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves AI profile selections without depending on JavaFX state.
 */
public final class AiProfileSelectionSupport {

    private AiProfileSelectionSupport() {
    }

    public static AiProfile defaultProfile(List<AiProfile> profiles, String defaultProfileId) {
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        AiProfile configuredDefault = findById(profiles, defaultProfileId);
        return configuredDefault != null ? configuredDefault : profiles.getFirst();
    }

    /**
     * Resolves the configured role profile and falls back to the normal default. Explicit and
     * connection-specific selections are handled by callers before invoking this method.
     */
    public static AiProfile workloadProfile(
        List<AiProfile> profiles,
        AiWorkload workload,
        String textProfileId,
        String codingProfileId,
        String defaultProfileId) {

        String roleProfileId = workload == AiWorkload.CODING ? codingProfileId : textProfileId;
        AiProfile roleProfile = findById(profiles, roleProfileId);
        return roleProfile != null ? roleProfile : defaultProfile(profiles, defaultProfileId);
    }

    /**
     * Applies the complete per-action routing order used by interactive snippet tools: explicit
     * selection, dedicated security profile, connection profile, workload role, then default.
     */
    public static AiProfile actionProfile(
        List<AiProfile> profiles,
        String explicitProfileId,
        boolean securityAction,
        String securityProfileId,
        String connectionProfileId,
        AiWorkload workload,
        String textProfileId,
        String codingProfileId,
        String defaultProfileId) {

        AiProfile selected = findById(profiles, explicitProfileId);
        if (selected != null) {
            return selected;
        }
        if (securityAction) {
            selected = findById(profiles, securityProfileId);
            if (selected != null) {
                return selected;
            }
        }
        selected = findById(profiles, connectionProfileId);
        if (selected != null) {
            return selected;
        }
        return workloadProfile(
            profiles,
            workload != null ? workload : AiWorkload.TEXT,
            textProfileId,
            codingProfileId,
            defaultProfileId);
    }

    public static List<AiProfile> reorderByRequestedOrDefault(
        List<AiProfile> profiles,
        String requestedProfileLookup,
        String defaultProfileId) {

        if (profiles == null || profiles.isEmpty()) {
            return profiles != null ? profiles : List.of();
        }
        AiProfile preferred = trimToNull(requestedProfileLookup) != null
            ? findByLookup(profiles, requestedProfileLookup)
            : defaultProfile(profiles, defaultProfileId);
        if (preferred == null) {
            return profiles;
        }
        List<AiProfile> reordered = new ArrayList<>();
        reordered.add(preferred);
        String preferredId = trimToNull(preferred.getId());
        for (AiProfile profile : profiles) {
            if (profile == null || profile == preferred) {
                continue;
            }
            String profileId = trimToNull(profile.getId());
            if (preferredId != null && preferredId.equals(profileId)) {
                continue;
            }
            reordered.add(profile);
        }
        return reordered;
    }

    public static String normalizeDefaultProfileId(String preferredProfileId, List<AiProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        AiProfile preferred = findById(profiles, preferredProfileId);
        if (preferred != null) {
            return trimToNull(preferred.getId());
        }
        for (AiProfile profile : profiles) {
            String profileId = profile != null ? trimToNull(profile.getId()) : null;
            if (profileId != null) {
                return profileId;
            }
        }
        return null;
    }

    public static AiProfile findById(List<AiProfile> profiles, String profileId) {
        String normalizedProfileId = trimToNull(profileId);
        if (profiles == null || normalizedProfileId == null) {
            return null;
        }
        for (AiProfile profile : profiles) {
            String candidateId = profile != null ? trimToNull(profile.getId()) : null;
            if (normalizedProfileId.equals(candidateId)) {
                return profile;
            }
        }
        return null;
    }

    public static AiProfile findByLookup(List<AiProfile> profiles, String lookup) {
        String normalizedLookup = trimToNull(lookup);
        if (profiles == null || normalizedLookup == null) {
            return null;
        }
        String lowerLookup = normalizedLookup.toLowerCase(Locale.ROOT);
        for (AiProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            String profileId = trimToNull(profile.getId());
            if (profileId != null && profileId.equalsIgnoreCase(normalizedLookup)) {
                return profile;
            }
            String profileName = trimToNull(profile.getName());
            if (profileName != null && profileName.toLowerCase(Locale.ROOT).equals(lowerLookup)) {
                return profile;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
