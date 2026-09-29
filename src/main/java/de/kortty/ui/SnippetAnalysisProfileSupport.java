package de.kortty.ui;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure logic behind choosing the AI profile before a Full code analysis starts: which profiles are
 * offered, which one is preselected (the remembered one, else the default), and whether a choice is
 * worth offering at all. No JavaFX, no settings access: callers pass the configured profiles in.
 */
final class SnippetAnalysisProfileSupport {

    private SnippetAnalysisProfileSupport() {
    }

    /** A profile the user can start an analysis with. */
    record Option(String id, String name, AiConnectionMode connectionMode, String model, boolean isDefault) {
    }

    /**
     * The selectable profiles in the given order; entries without an id are skipped (they cannot be
     * addressed). The profile whose id is {@code defaultProfileId} is marked as the default; when that
     * id is unknown the first profile is, matching how an unset default resolves at request time.
     */
    static List<Option> options(List<AiProfile> profiles, String defaultProfileId) {
        List<AiProfile> usable = new ArrayList<>();
        if (profiles != null) {
            for (AiProfile profile : profiles) {
                if (profile != null && profile.getId() != null && !profile.getId().isBlank()) {
                    usable.add(profile);
                }
            }
        }
        String effectiveDefault = null;
        for (AiProfile profile : usable) {
            if (profile.getId().equals(defaultProfileId)) {
                effectiveDefault = profile.getId();
                break;
            }
        }
        if (effectiveDefault == null && !usable.isEmpty()) {
            effectiveDefault = usable.get(0).getId();
        }
        List<Option> options = new ArrayList<>();
        for (AiProfile profile : usable) {
            String name = profile.getName() != null && !profile.getName().isBlank()
                ? profile.getName().trim()
                : profile.getId();
            options.add(new Option(profile.getId(), name, profile.getConnectionMode(), profile.getModel(),
                profile.getId().equals(effectiveDefault)));
        }
        return List.copyOf(options);
    }

    /**
     * The profile to preselect: the remembered one when it still exists among {@code options}, else the
     * default, else the first; {@code null} when there is nothing to choose from.
     */
    static String resolveSelection(List<Option> options, String rememberedProfileId) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (rememberedProfileId != null && !rememberedProfileId.isBlank()) {
            for (Option option : options) {
                if (option.id().equals(rememberedProfileId)) {
                    return option.id();
                }
            }
        }
        for (Option option : options) {
            if (option.isDefault()) {
                return option.id();
            }
        }
        return options.get(0).id();
    }

    /**
     * Whether the user is offered a choice: more than one profile and a host that can switch profiles.
     * With a single profile (or a fixed-profile host) the analysis starts immediately.
     */
    static boolean choiceOffered(List<Option> options, boolean profileSwitchingSupported) {
        return profileSwitchingSupported && options != null && options.size() > 1;
    }

    /** The option with this id, or {@code null}. */
    static Option find(List<Option> options, String profileId) {
        if (options == null || profileId == null) {
            return null;
        }
        for (Option option : options) {
            if (option.id().equals(profileId)) {
                return option;
            }
        }
        return null;
    }
}
