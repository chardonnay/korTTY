package de.kortty.policy;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class EffectivePolicyResolveTest {

    private static PolicyIdentity identity(String user, String... osGroups) {
        return new PolicyIdentity() {
            @Override
            public String userName() {
                return user;
            }

            @Override
            public Set<String> osGroups() {
                return Set.of(osGroups);
            }
        };
    }

    private static PolicyFile file(Map<String, Set<String>> groups, PolicyRule... rules) {
        return new PolicyFile(1, "ACME Corp", groups, List.of(rules),
            List.of(), List.of(), List.of(), List.of());
    }

    private static PolicyRule.SessionJournalRule screenshotAnalysisRule(Boolean value) {
        return new PolicyRule.SessionJournalRule(
            null, null, null, null, null, null, null, null, value, null, null, List.of());
    }

    private static PolicyRule.SessionJournalRule aiAskRule(Boolean value) {
        return new PolicyRule.SessionJournalRule(
            null, null, null, null, null, null, null, null, null, value, null, List.of());
    }

    private static PolicyRule.SessionJournalRule maxLogPartsRule(Integer value) {
        return new PolicyRule.SessionJournalRule(
            null, null, null, null, null, null, null, null, null, null, value, List.of());
    }

    @Test
    void maxLogPartsResolvesToTheTighterCap() {
        // Same-tier conflict: the lower cap wins — it is the more restrictive one.
        PolicyFile conflicting = file(Map.of(),
            PolicyRule.builder().sessionJournal(maxLogPartsRule(50)).build(),
            PolicyRule.builder().sessionJournal(maxLogPartsRule(30)).build());
        assertThat(EffectivePolicy.resolve(conflicting, identity("anyone"))
            .sessionJournalMaxLogParts()).isEqualTo(30);

        // No rule sets it: null, the connection's configured value applies alone.
        PolicyFile unset = file(Map.of(),
            PolicyRule.builder().sessionJournal(maxLogPartsRule(null)).build());
        assertThat(EffectivePolicy.resolve(unset, identity("anyone"))
            .sessionJournalMaxLogParts()).isNull();
    }

    @Test
    void aiScreenshotAnalysisResolvesRestrictivelyWithinATierAndBySpecificityAcrossTiers() {
        // Same-tier conflict: off wins — screenshots leaving the machine is the risk.
        PolicyFile conflicting = file(Map.of(),
            PolicyRule.builder().sessionJournal(screenshotAnalysisRule(true)).build(),
            PolicyRule.builder().sessionJournal(screenshotAnalysisRule(false)).build());
        assertThat(EffectivePolicy.resolve(conflicting, identity("anyone"))
            .sessionJournal().aiScreenshotAnalysis()).isFalse();

        // A more specific tier still overrides (GPO-style exception).
        PolicyFile tiered = file(Map.of(),
            PolicyRule.builder().sessionJournal(screenshotAnalysisRule(false)).build(),
            PolicyRule.builder().users(Set.of("eve")).sessionJournal(screenshotAnalysisRule(true)).build());
        assertThat(EffectivePolicy.resolve(tiered, identity("eve"))
            .sessionJournal().aiScreenshotAnalysis()).isTrue();
        assertThat(EffectivePolicy.resolve(tiered, identity("alice"))
            .sessionJournal().aiScreenshotAnalysis()).isFalse();

        // No rule sets it: the user decides.
        PolicyFile unset = file(Map.of(),
            PolicyRule.builder().sessionJournal(screenshotAnalysisRule(null)).build());
        assertThat(EffectivePolicy.resolve(unset, identity("anyone"))
            .sessionJournal().aiScreenshotAnalysis()).isNull();
    }

    @Test
    void aiAskResolvesRestrictivelyAndGatesTheAskCapability() {
        // Same-tier conflict: off wins — journal text leaving the machine is the risk.
        PolicyFile conflicting = file(Map.of(),
            PolicyRule.builder().sessionJournal(aiAskRule(true)).build(),
            PolicyRule.builder().sessionJournal(aiAskRule(false)).build());
        EffectivePolicy denied = EffectivePolicy.resolve(conflicting, identity("anyone"));
        assertThat(denied.sessionJournal().aiAsk()).isFalse();
        assertThat(denied.sessionJournalAiAskAllowed()).isFalse();

        // Unset: allowed as long as journal + AI features are allowed.
        PolicyFile unset = file(Map.of(),
            PolicyRule.builder().sessionJournal(aiAskRule(null)).build());
        EffectivePolicy open = EffectivePolicy.resolve(unset, identity("anyone"));
        assertThat(open.sessionJournal().aiAsk()).isNull();
        assertThat(open.sessionJournalAiAskAllowed()).isTrue();
    }

    @Test
    void ruleWithoutScopeAppliesToEveryUser() {
        PolicyFile file = file(Map.of(), PolicyRule.builder()
            .features(Map.of(PolicyFeature.AI_AGENT, PolicyDecision.DENY))
            .build());
        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("anyone"));
        assertThat(policy.aiAgentAllowed()).isFalse();
        assertThat(policy.aiChatAllowed()).isTrue();
        assertThat(policy.isManaged(ManagedSetting.AI_FEATURES)).isTrue();
    }

    @Test
    void userTierBeatsGroupTierBeatsAllTier() {
        PolicyRule allRule = PolicyRule.builder()
            .features(Map.of(PolicyFeature.AI_AGENT, PolicyDecision.DENY))
            .build();
        PolicyRule groupRule = PolicyRule.builder()
            .groups(Set.of("ops"))
            .features(Map.of(PolicyFeature.AI_AGENT, PolicyDecision.ALLOW))
            .build();
        PolicyRule userRule = PolicyRule.builder()
            .users(Set.of("eve"))
            .features(Map.of(PolicyFeature.AI_AGENT, PolicyDecision.DENY))
            .build();
        PolicyFile file = file(Map.of("ops", Set.of("carol", "eve")), allRule, groupRule, userRule);

        // alice: only the all-tier applies.
        assertThat(EffectivePolicy.resolve(file, identity("alice")).aiAgentAllowed()).isFalse();
        // carol: group tier relaxes the all-tier lockdown (GPO-style exception).
        assertThat(EffectivePolicy.resolve(file, identity("carol")).aiAgentAllowed()).isTrue();
        // eve: user tier wins over her group's allow.
        assertThat(EffectivePolicy.resolve(file, identity("eve")).aiAgentAllowed()).isFalse();
    }

    @Test
    void sameTierConflictResolvesToMostRestrictive() {
        PolicyRule devs = PolicyRule.builder()
            .groups(Set.of("devs"))
            .features(Map.of(PolicyFeature.TEAMWORK, PolicyDecision.ALLOW))
            .agentExecution(AgentExecutionMode.CONFIRM)
            .build();
        PolicyRule ops = PolicyRule.builder()
            .groups(Set.of("ops"))
            .features(Map.of(PolicyFeature.TEAMWORK, PolicyDecision.DENY))
            .agentExecution(AgentExecutionMode.READ_ONLY)
            .build();
        PolicyFile file = file(Map.of("devs", Set.of("bob"), "ops", Set.of("bob")), devs, ops);

        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("bob"));
        assertThat(policy.teamworkAllowed()).isFalse();
        assertThat(policy.agentExecution()).isEqualTo(AgentExecutionMode.READ_ONLY);
    }

    @Test
    void osGroupMembershipMatchesGroupRules() {
        PolicyRule rule = PolicyRule.builder()
            .groups(Set.of("acme\\entwickler"))
            .features(Map.of(PolicyFeature.AI, PolicyDecision.DENY))
            .build();
        PolicyFile file = file(Map.of(), rule);

        EffectivePolicy inGroup = EffectivePolicy.resolve(file, identity("dora", "acme\\entwickler"));
        assertThat(inGroup.aiAllowed()).isFalse();

        EffectivePolicy notInGroup = EffectivePolicy.resolve(file, identity("dora"));
        assertThat(notInGroup.aiAllowed()).isTrue();
    }

    @Test
    void aiMasterSwitchDisablesEverySubFeature() {
        PolicyFile file = file(Map.of(), PolicyRule.builder()
            .features(Map.of(PolicyFeature.AI, PolicyDecision.DENY))
            .build());
        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("u"));
        assertThat(policy.aiAllowed()).isFalse();
        assertThat(policy.aiAgentAllowed()).isFalse();
        assertThat(policy.aiChatAllowed()).isFalse();
        assertThat(policy.aiSwarmAllowed()).isFalse();
        assertThat(policy.aiPlanningAllowed()).isFalse();
    }

    @Test
    void serverRestrictionsIntersectWithinTheWinningTier() {
        PolicyRule first = PolicyRule.builder()
            .groups(Set.of("g1"))
            .servers(new ServerRestriction(ServerRestriction.Mode.ALLOW,
                List.of(ServerMatcher.parse("*.acme.com"))))
            .build();
        PolicyRule second = PolicyRule.builder()
            .groups(Set.of("g2"))
            .servers(new ServerRestriction(ServerRestriction.Mode.DENY,
                List.of(ServerMatcher.parse("vault.acme.com"))))
            .build();
        PolicyFile file = file(Map.of("g1", Set.of("u"), "g2", Set.of("u")), first, second);

        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("u"));
        assertThat(policy.isServerAllowed("web.acme.com", 22)).isTrue();
        // Denied by the second restriction even though the first allows it.
        assertThat(policy.isServerAllowed("vault.acme.com", 22)).isFalse();
        // Outside the first restriction's allow-list.
        assertThat(policy.isServerAllowed("other.example.org", 22)).isFalse();
        assertThat(policy.isManaged(ManagedSetting.SERVER_ACCESS)).isTrue();
    }

    @Test
    void moreSpecificTierReplacesServerRestrictionsEntirely() {
        PolicyRule allRule = PolicyRule.builder()
            .servers(new ServerRestriction(ServerRestriction.Mode.ALLOW,
                List.of(ServerMatcher.parse("intranet.acme.com"))))
            .build();
        PolicyRule opsRule = PolicyRule.builder()
            .groups(Set.of("ops"))
            .servers(new ServerRestriction(ServerRestriction.Mode.ALLOW,
                List.of(ServerMatcher.parse("*.acme.com"), ServerMatcher.parse("10.0.0.0/8"))))
            .build();
        PolicyFile file = file(Map.of("ops", Set.of("carol")), allRule, opsRule);

        assertThat(EffectivePolicy.resolve(file, identity("alice"))
            .isServerAllowed("db.acme.com", 22)).isFalse();
        assertThat(EffectivePolicy.resolve(file, identity("carol"))
            .isServerAllowed("db.acme.com", 22)).isTrue();
        assertThat(EffectivePolicy.resolve(file, identity("carol"))
            .isServerAllowed("10.1.2.3", 22)).isTrue();
    }

    @Test
    void securityFlagsResolveWithRestrictiveDefaultsPerDirection() {
        PolicyRule relaxed = PolicyRule.builder()
            .groups(Set.of("g"))
            .requireMasterPassword(false)
            .allowTelemetry(true)
            .build();
        PolicyRule strict = PolicyRule.builder()
            .groups(Set.of("g"))
            .requireMasterPassword(true)
            .allowTelemetry(false)
            .enforceHostKeyCheck(true)
            .allowTerminalRecording(false)
            .build();
        PolicyFile file = file(Map.of("g", Set.of("u")), relaxed, strict);

        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("u"));
        // Same tier: require-flags OR together, allow-flags AND together.
        assertThat(policy.requireMasterPassword()).isTrue();
        assertThat(policy.telemetryAllowed()).isFalse();
        assertThat(policy.enforceHostKeyCheck()).isTrue();
        assertThat(policy.terminalRecordingAllowed()).isFalse();
        assertThat(policy.isManaged(ManagedSetting.MASTER_PASSWORD)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.HOST_KEY_CHECK)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.TELEMETRY)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.TERMINAL_RECORDING)).isTrue();
    }

    @Test
    void clipboardModeResolvesWithInternalAsRestrictive() {
        PolicyRule system = PolicyRule.builder()
            .groups(Set.of("g"))
            .clipboardMode(ClipboardMode.SYSTEM)
            .build();
        PolicyRule internal = PolicyRule.builder()
            .groups(Set.of("g"))
            .clipboardMode(ClipboardMode.INTERNAL)
            .build();
        PolicyFile file = file(Map.of("g", Set.of("u")), system, internal);

        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("u"));
        assertThat(policy.clipboardMode()).isEqualTo(ClipboardMode.INTERNAL);
        assertThat(policy.isManaged(ManagedSetting.CLIPBOARD)).isTrue();

        // Unset -> system, unmanaged.
        EffectivePolicy unset = EffectivePolicy.resolve(file(Map.of()), identity("u"));
        assertThat(unset.clipboardMode()).isEqualTo(ClipboardMode.SYSTEM);
        assertThat(unset.isManaged(ManagedSetting.CLIPBOARD)).isFalse();
    }

    @Test
    void loggingResolvesPerFieldWithTighterCapsWinning() {
        PolicyRule first = PolicyRule.builder()
            .groups(Set.of("g"))
            .logging(new PolicyRule.LoggingRule("/var/log/kortty", 30, null, LogFormat.TEXT, 0, 512))
            .build();
        PolicyRule second = PolicyRule.builder()
            .groups(Set.of("g"))
            .logging(new PolicyRule.LoggingRule(null, 14, false, LogFormat.JSON, 7, 0))
            .build();
        PolicyFile file = file(Map.of("g", Set.of("u")), first, second);

        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("u"));
        assertThat(policy.logging().directory()).isEqualTo("/var/log/kortty");
        assertThat(policy.logging().retentionDays()).isEqualTo(14);
        assertThat(policy.logging().compress()).isFalse();
        assertThat(policy.logging().format()).isEqualTo(LogFormat.JSON);
        // A cap (7 files, 512 MB) beats "unlimited" (0).
        assertThat(policy.logging().rotationMaxFiles()).isEqualTo(7);
        assertThat(policy.logging().rotationTotalSizeMb()).isEqualTo(512);
        assertThat(policy.isManaged(ManagedSetting.LOGGING)).isTrue();

        EffectivePolicy unset = EffectivePolicy.resolve(file(Map.of()), identity("u"));
        assertThat(unset.logging().isEmpty()).isTrue();
        assertThat(unset.isManaged(ManagedSetting.LOGGING)).isFalse();
    }

    @Test
    void unknownUserGetsOnlyAllTierRules() {
        PolicyRule allRule = PolicyRule.builder().updatesEnabled(false).build();
        PolicyRule scoped = PolicyRule.builder()
            .users(Set.of("someoneelse"))
            .updatesEnabled(true)
            .build();
        PolicyFile file = file(Map.of(), allRule, scoped);

        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("stranger"));
        assertThat(policy.updatesEnabled()).isFalse();
        assertThat(policy.isManaged(ManagedSetting.UPDATES)).isTrue();
    }

    @Test
    void unsetKeysStayUnrestrictedAndUnmanaged() {
        PolicyFile file = file(Map.of(), PolicyRule.builder()
            .features(Map.of(PolicyFeature.PLUGINS, PolicyDecision.DENY))
            .build());
        EffectivePolicy policy = EffectivePolicy.resolve(file, identity("u"));
        assertThat(policy.pluginsAllowed()).isFalse();
        assertThat(policy.updatesEnabled()).isTrue();
        assertThat(policy.customScriptHeadersAllowed()).isTrue();
        assertThat(policy.aiProfileCreateAllowed()).isTrue();
        assertThat(policy.loadIntoSnippetEditor()).isEqualTo(LoadIntoEditorMode.ALLOW);
        assertThat(policy.isManaged(ManagedSetting.UPDATES)).isFalse();
        assertThat(policy.isManaged(ManagedSetting.SCRIPT_HEADERS)).isFalse();
        assertThat(policy.isServerAllowed("anywhere.example.org", 22)).isTrue();
    }

    @Test
    void unrestrictedAllowsEverything() {
        EffectivePolicy policy = EffectivePolicy.unrestricted();
        assertThat(policy.fromPolicyFile()).isFalse();
        assertThat(policy.aiAllowed()).isTrue();
        assertThat(policy.teamworkAllowed()).isTrue();
        assertThat(policy.pluginsAllowed()).isTrue();
        assertThat(policy.agentExecution()).isEqualTo(AgentExecutionMode.ALLOW);
        assertThat(policy.isServerAllowed("any.example.org", 22)).isTrue();
        assertThat(policy.requireMasterPassword()).isFalse();
        for (ManagedSetting setting : ManagedSetting.values()) {
            assertThat(policy.isManaged(setting)).isFalse();
        }
    }

    @Test
    void lockdownDeniesEverything() {
        EffectivePolicy policy = EffectivePolicy.lockdown();
        assertThat(policy.fromPolicyFile()).isTrue();
        assertThat(policy.isLockdown()).isTrue();
        assertThat(policy.aiAllowed()).isFalse();
        assertThat(policy.teamworkAllowed()).isFalse();
        assertThat(policy.pluginsAllowed()).isFalse();
        assertThat(policy.agentExecution()).isEqualTo(AgentExecutionMode.READ_ONLY);
        assertThat(policy.loadIntoSnippetEditor()).isEqualTo(LoadIntoEditorMode.DENY);
        assertThat(policy.isServerAllowed("any.example.org", 22)).isFalse();
        assertThat(policy.updatesEnabled()).isFalse();
        assertThat(policy.customScriptHeadersAllowed()).isFalse();
        assertThat(policy.requireMasterPassword()).isTrue();
        assertThat(policy.enforceHostKeyCheck()).isTrue();
        for (ManagedSetting setting : ManagedSetting.values()) {
            assertThat(policy.isManaged(setting)).isTrue();
        }
    }

    @Test
    void userNamedDirectlyAndViaGroupResolvesAtUserTier() {
        PolicyRule rule = PolicyRule.builder()
            .users(Set.of("eve"))
            .groups(Set.of("ops"))
            .features(Map.of(PolicyFeature.AI, PolicyDecision.DENY))
            .build();
        PolicyRule opsRelax = PolicyRule.builder()
            .groups(Set.of("ops"))
            .features(Map.of(PolicyFeature.AI, PolicyDecision.ALLOW))
            .build();
        PolicyFile file = file(Map.of("ops", Set.of("eve")), rule, opsRelax);

        // The mixed rule matches eve at USER tier and therefore beats the group-tier allow.
        assertThat(EffectivePolicy.resolve(file, identity("eve")).aiAllowed()).isFalse();
    }

    @Test
    void nullPolicyFileResolvesToUnrestricted() {
        assertThat(EffectivePolicy.resolve(null, identity("u")).fromPolicyFile()).isFalse();
    }
}
