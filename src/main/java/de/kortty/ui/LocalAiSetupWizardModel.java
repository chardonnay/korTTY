package de.kortty.ui;

import de.kortty.ai.huggingface.HuggingFaceModelCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Testable navigation, role selection and installation state for the beginner setup dialog. */
final class LocalAiSetupWizardModel {

    enum Page {
        PRIVACY,
        HARDWARE,
        ROLES,
        LICENSE,
        INSTALL,
        FINISH
    }

    enum InstallationState {
        NOT_STARTED,
        RUNNING,
        FAILED,
        CANCELLED,
        SUCCEEDED
    }

    private final List<HuggingFaceModelCatalog.Recommendation> recommendations;
    private final EnumMap<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections =
        new EnumMap<>(HuggingFaceModelCatalog.Role.class);
    private int pageIndex;
    private InstallationState installationState = InstallationState.NOT_STARTED;

    LocalAiSetupWizardModel(long systemMemoryBytes, HuggingFaceModelCatalog.Role preferredRole) {
        this(HuggingFaceModelCatalog.candidatesForMemory(Math.max(0, systemMemoryBytes)), preferredRole);
    }

    LocalAiSetupWizardModel(
        List<HuggingFaceModelCatalog.Recommendation> recommendations,
        HuggingFaceModelCatalog.Role preferredRole
    ) {
        this.recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        for (HuggingFaceModelCatalog.Role role : HuggingFaceModelCatalog.Role.values()) {
            if (preferredRole == null || role == preferredRole) {
                preferredRecommendation(role).ifPresent(value -> selections.put(role, value));
            }
        }
    }

    List<HuggingFaceModelCatalog.Recommendation> recommendations() {
        return recommendations;
    }

    List<HuggingFaceModelCatalog.Recommendation> recommendationsFor(
        HuggingFaceModelCatalog.Role role
    ) {
        if (role == null) {
            return List.of();
        }
        return recommendations.stream()
            .filter(value -> value.roles().contains(role))
            .sorted(java.util.Comparator.comparingInt(
                HuggingFaceModelCatalog.Recommendation::preference).reversed())
            .toList();
    }

    HuggingFaceModelCatalog.Recommendation selectedRecommendation(
        HuggingFaceModelCatalog.Role role
    ) {
        return role != null ? selections.get(role) : null;
    }

    void selectRecommendation(
        HuggingFaceModelCatalog.Role role,
        HuggingFaceModelCatalog.Recommendation value
    ) {
        if (role == null) {
            throw new IllegalArgumentException("A local-AI role is required.");
        }
        if (value != null && (!recommendations.contains(value) || !value.roles().contains(role))) {
            throw new IllegalArgumentException("Recommendation is not offered for " + role + ".");
        }
        if (value == null) {
            selections.remove(role);
        } else {
            selections.put(role, value);
        }
        installationState = InstallationState.NOT_STARTED;
    }

    Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections() {
        return Collections.unmodifiableMap(new EnumMap<>(selections));
    }

    List<HuggingFaceModelCatalog.Recommendation> uniqueSelections() {
        LinkedHashSet<HuggingFaceModelCatalog.Recommendation> unique = new LinkedHashSet<>();
        for (HuggingFaceModelCatalog.Role role : HuggingFaceModelCatalog.Role.values()) {
            HuggingFaceModelCatalog.Recommendation selected = selections.get(role);
            if (selected != null) {
                unique.add(selected);
            }
        }
        return List.copyOf(unique);
    }

    List<HuggingFaceModelCatalog.Role> rolesFor(
        HuggingFaceModelCatalog.Recommendation recommendation
    ) {
        List<HuggingFaceModelCatalog.Role> roles = new ArrayList<>();
        for (HuggingFaceModelCatalog.Role role : HuggingFaceModelCatalog.Role.values()) {
            if (recommendation != null && recommendation.equals(selections.get(role))) {
                roles.add(role);
            }
        }
        return List.copyOf(roles);
    }

    boolean hasSelection() {
        return !selections.isEmpty();
    }

    Page page() {
        return Page.values()[pageIndex];
    }

    int pageIndex() {
        return pageIndex;
    }

    int stepNumber() {
        return pageIndex + 1;
    }

    int pageCount() {
        return Page.values().length;
    }

    InstallationState installationState() {
        return installationState;
    }

    boolean canGoBack() {
        if (installationState == InstallationState.RUNNING
            || installationState == InstallationState.SUCCEEDED) {
            return false;
        }
        return pageIndex > 0;
    }

    boolean canGoNext() {
        return switch (page()) {
            case PRIVACY, HARDWARE, LICENSE -> true;
            case ROLES -> hasSelection();
            case INSTALL, FINISH -> false;
        };
    }

    boolean canFinish() {
        return page() == Page.FINISH && installationState == InstallationState.SUCCEEDED;
    }

    boolean goBack() {
        if (!canGoBack()) {
            return false;
        }
        pageIndex--;
        if (page() != Page.INSTALL) {
            installationState = InstallationState.NOT_STARTED;
        }
        return true;
    }

    boolean goNext() {
        if (!canGoNext() || pageIndex + 1 >= pageCount()) {
            return false;
        }
        pageIndex++;
        return true;
    }

    void beginInstallation() {
        if (page() != Page.LICENSE || !hasSelection()) {
            throw new IllegalStateException("Installation can only start after selecting and reviewing a model.");
        }
        pageIndex = Page.INSTALL.ordinal();
        installationState = InstallationState.RUNNING;
    }

    void retryInstallation() {
        if (page() != Page.INSTALL
            || (installationState != InstallationState.FAILED
                && installationState != InstallationState.CANCELLED)) {
            throw new IllegalStateException("Only a failed or cancelled installation can be retried.");
        }
        installationState = InstallationState.RUNNING;
    }

    void failInstallation() {
        requireRunning();
        installationState = InstallationState.FAILED;
    }

    void cancelInstallation() {
        requireRunning();
        installationState = InstallationState.CANCELLED;
    }

    void completeInstallation() {
        requireRunning();
        installationState = InstallationState.SUCCEEDED;
        pageIndex = Page.FINISH.ordinal();
    }

    private java.util.Optional<HuggingFaceModelCatalog.Recommendation> preferredRecommendation(
        HuggingFaceModelCatalog.Role role
    ) {
        // The candidate list now carries alternatives per role, so the default must be picked by
        // preference instead of list position. Ties keep the earlier (catalog-ordered) entry.
        return recommendations.stream()
            .filter(value -> value.roles().contains(role))
            .max(java.util.Comparator.comparingInt(
                HuggingFaceModelCatalog.Recommendation::preference));
    }

    private void requireRunning() {
        if (installationState != InstallationState.RUNNING) {
            throw new IllegalStateException("No setup installation is running.");
        }
    }
}
