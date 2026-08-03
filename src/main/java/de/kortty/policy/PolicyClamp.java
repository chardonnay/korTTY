package de.kortty.policy;

import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TeamworkSourceConfig;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * The list references temporarily swapped out for a marshal, so {@link #afterSave} can restore
     * the live lists without any in-place mutation of the settings a concurrent reader may hold.
     */
    public record MarshalScope(GlobalSettings settings,
                               List<AiProfile> savedAiProfiles,
                               List<TeamworkSourceConfig> savedTeamworkSources) {
        static final MarshalScope NONE = new MarshalScope(null, null, null);
    }

    /** Applies every forced scalar value and (re)injects policy-managed objects. */
    public void apply(GlobalSettings settings) {
        if (!policy.fromPolicyFile()) {
            return;
        }
        applyScalars(settings);
        applyManagedAiProfiles(settings);
        applyManagedTeamworkSources(settings);
    }

    /** Forces every policy-controlled scalar value; never touches the managed-object lists. */
    private void applyScalars(GlobalSettings settings) {
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
            // A forced master password rules out the insecure auto-unlock path.
            settings.setSkipMasterPasswordPrompt(false);
        }
        if (policy.enforceHostKeyCheck()) {
            settings.setHostKeyCheckDisabledForAllConnections(false);
            settings.setHostKeyCheckDisabledGroups(java.util.List.of());
        }
        if (policy.logging().directory() != null) {
            settings.setLogDirectoryPath(policy.logging().directory());
        }
        if (policy.logging().retentionDays() != null) {
            settings.setLogRetentionDays(policy.logging().retentionDays());
        }
        PolicyRule.SessionJournalRule sessionJournal = policy.sessionJournal();
        if (sessionJournal.logFormat() != null) {
            de.kortty.model.SessionJournalLogFormat format =
                de.kortty.model.SessionJournalLogFormat.fromKey(sessionJournal.logFormat());
            if (format != null) {
                settings.setSessionJournalLogFormat(format);
            }
        }
        if (sessionJournal.aiMaxLines() != null) {
            settings.setSessionJournalAiMaxLines(sessionJournal.aiMaxLines());
        }
        if (sessionJournal.storagePath() != null) {
            settings.setSessionJournalStoragePath(sessionJournal.storagePath());
        }
        if (Boolean.TRUE.equals(sessionJournal.aiTitle())) {
            settings.setSessionJournalAiTitleEnabled(true);
        }
    }

    /**
     * Prepares {@code settings} for marshaling: re-forces the scalar clamps (defense in depth) and
     * swaps the managed-object lists for filtered copies that exclude policy-managed entries, so
     * they never reach the user XML. Crucially the live lists are <b>not mutated in place</b> — a
     * concurrent reader iterating {@code getAiProfiles()} on another thread can never see a
     * {@link java.util.ConcurrentModificationException}. Always pair with
     * {@link #afterSave(MarshalScope)} in a {@code finally} block.
     */
    public MarshalScope beforeSave(GlobalSettings settings) {
        if (!policy.fromPolicyFile()) {
            return MarshalScope.NONE;
        }
        applyScalars(settings);
        List<AiProfile> savedProfiles = settings.exchangeAiProfilesForMarshal(
            withoutManaged(settings.getAiProfiles(),
                profile -> profile != null && profile.isPolicyManaged()));
        List<TeamworkSourceConfig> savedSources = settings.exchangeTeamworkSourcesForMarshal(
            withoutManaged(settings.getTeamworkSources(),
                source -> source != null && source.isPolicyManaged()));
        return new MarshalScope(settings, savedProfiles, savedSources);
    }

    /** Restores the live lists swapped out by {@link #beforeSave(GlobalSettings)}. */
    public void afterSave(MarshalScope scope) {
        if (scope == null || scope.settings() == null) {
            return;
        }
        scope.settings().exchangeAiProfilesForMarshal(scope.savedAiProfiles());
        scope.settings().exchangeTeamworkSourcesForMarshal(scope.savedTeamworkSources());
    }

    // ---- managed objects: rebuilt from the policy on every load ----

    private void applyManagedAiProfiles(GlobalSettings settings) {
        List<AiProfile> profiles = settings.getAiProfiles();
        profiles.removeIf(profile -> profile != null
            && (profile.isPolicyManaged()
                || (profile.getId() != null
                    && profile.getId().startsWith(PolicyFile.AI_PROFILE_ID_PREFIX))));
        for (PolicyFile.AiProfileDef def : policy.aiProfiles()) {
            profiles.add(PolicyAiProfileSupport.toAiProfile(def));
        }
    }

    private void applyManagedTeamworkSources(GlobalSettings settings) {
        List<TeamworkSourceConfig> sources = settings.getTeamworkSources();
        sources.removeIf(source -> source != null
            && (source.isPolicyManaged()
                || (source.getId() != null && source.getId().startsWith("policy-teamwork-"))));
        int index = 0;
        for (PolicyFile.TeamworkSourceDef def : policy.teamworkSources()) {
            sources.add(PolicyAiProfileSupport.toTeamworkSource(def, ++index));
        }
    }

    /** A new list with the managed entries removed; the source list is only read, never mutated. */
    private static <T> List<T> withoutManaged(List<T> source, java.util.function.Predicate<T> managed) {
        List<T> filtered = new ArrayList<>(source.size());
        for (T element : source) {
            if (!managed.test(element)) {
                filtered.add(element);
            }
        }
        return filtered;
    }
}
