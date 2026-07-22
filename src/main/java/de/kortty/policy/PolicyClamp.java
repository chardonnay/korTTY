package de.kortty.policy;

import de.kortty.model.GlobalSettings;

/**
 * Forces policy-managed values onto a {@link GlobalSettings} instance. Called by
 * {@link de.kortty.core.GlobalSettingsManager} after every load (including
 * {@code reloadIfChanged()}, so hand-edits to {@code global-settings.xml} are re-clamped) and again
 * before every save (defense in depth — a locked control can never persist a diverging value).
 *
 * <p>Only settings that already exist in {@code GlobalSettings} are clamped here; policy dimensions
 * without a settings field (per-AI-subfeature switches, server access, load-into-editor mode, …)
 * are enforced at their feature gates via {@link PolicyManager#effective()}.
 */
public final class PolicyClamp {

    private final EffectivePolicy policy;

    public PolicyClamp(EffectivePolicy policy) {
        this.policy = policy;
    }

    /** Applies every forced scalar value. Managed-object injection lives in dedicated appliers. */
    public void apply(GlobalSettings settings) {
        if (!policy.fromPolicyFile()) {
            return;
        }
        if (!policy.aiAllowed()) {
            settings.setAiFeaturesEnabled(false);
        }
        if (policy.agentExecution() == AgentExecutionMode.READ_ONLY || !policy.aiAgentAllowed()) {
            settings.setTerminalAgentExecutionEnabled(false);
        }
        if (policy.agentExecution() == AgentExecutionMode.CONFIRM) {
            settings.setTerminalAgentConfirmMutatingCommandSets(true);
        }
        if (!policy.updatesEnabled()) {
            settings.setUpdateChecksEnabled(false);
        }
        if (!policy.telemetryAllowed()) {
            settings.setTelemetryEnabled(false);
        }
        if (!policy.terminalRecordingAllowed()) {
            settings.setTerminalRecordingEnabled(false);
        }
        if (policy.requireMasterPassword()) {
            settings.setRequireMasterPasswordOnStartup(true);
        }
        if (policy.enforceHostKeyCheck()) {
            settings.setHostKeyCheckDisabledForAllConnections(false);
            settings.setHostKeyCheckDisabledGroups(java.util.List.of());
        }
        applyManagedAiProfiles(settings);
        applyManagedTeamworkSources(settings);
    }

    /**
     * Removes policy-managed objects before the settings are marshaled to the user XML and
     * re-applies scalar clamps. Call {@link #afterSave(GlobalSettings)} once marshaling is done.
     */
    public void beforeSave(GlobalSettings settings) {
        if (!policy.fromPolicyFile()) {
            return;
        }
        apply(settings);
        stripManagedAiProfiles(settings);
        stripManagedTeamworkSources(settings);
    }

    /** Restores the managed objects stripped by {@link #beforeSave(GlobalSettings)}. */
    public void afterSave(GlobalSettings settings) {
        if (!policy.fromPolicyFile()) {
            return;
        }
        applyManagedAiProfiles(settings);
        applyManagedTeamworkSources(settings);
    }

    // Managed-object injection is added with the AI-profile / teamwork phases; the hooks exist so
    // GlobalSettingsManager never needs to change again.

    private void applyManagedAiProfiles(GlobalSettings settings) {
        // Implemented in the AI-profile phase.
    }

    private void stripManagedAiProfiles(GlobalSettings settings) {
        // Implemented in the AI-profile phase.
    }

    private void applyManagedTeamworkSources(GlobalSettings settings) {
        // Implemented in the teamwork phase.
    }

    private void stripManagedTeamworkSources(GlobalSettings settings) {
        // Implemented in the teamwork phase.
    }
}
