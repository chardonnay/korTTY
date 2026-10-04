package de.kortty.policy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionRestoreMode;
import de.kortty.paste.PasteWarningMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * The policy dimensions of the new terminal UX, batched into one {@link EffectivePolicy.TerminalPolicy}:
 * the {@code [rule.terminal] paste-warning} floor, the {@code [rule.security] allow-osc52-clipboard-write}
 * force-off, the {@code [rule.features] multi-exec} switch and the {@code [rule.terminal] session-restore}
 * lock — loader, resolution, lockdown and clamp.
 */
class TerminalUxPolicyTest {

    private Path tempDir;

    @AfterMethod
    void cleanup() throws IOException {
        if (tempDir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        tempDir = null;
    }

    private Path dir() throws IOException {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("kt-terminal-ux-policy");
        }
        return tempDir;
    }

    private PolicyLoadResult load(String rule) throws IOException {
        Path file = Files.createTempFile(dir(), "policy", ".toml");
        Files.writeString(file, "[meta]\nschema-version = 1\n\n[[rule]]\n" + rule);
        return PolicyLoader.load(file);
    }

    private static PolicyIdentity user(String name, String... groups) {
        return new PolicyIdentity() {
            @Override
            public String userName() {
                return name;
            }

            @Override
            public Set<String> osGroups() {
                return Set.of(groups);
            }
        };
    }

    private static EffectivePolicy resolve(PolicyRule... rules) {
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(), List.of(rules),
            List.of(), List.of(), List.of(), List.of());
        return EffectivePolicy.resolve(file, user("u", "ops"));
    }

    // ---- loader ---------------------------------------------------------------------------------

    @Test
    void parsesEveryNewKey() throws IOException {
        PolicyLoadResult result = load("""
            [rule.features]
            multi-exec = "deny"
            [rule.security]
            allow-osc52-clipboard-write = false
            [rule.terminal]
            paste-warning = "Always"
            session-restore = "off"
            session-restore-output = false
            """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        PolicyRule rule = result.file().rules().get(0);
        assertThat(rule.features().get(PolicyFeature.MULTI_EXEC)).isEqualTo(PolicyDecision.DENY);
        assertThat(rule.allowOsc52ClipboardWrite()).isFalse();
        assertThat(rule.pasteWarningFloor()).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(rule.sessionRestoreMode()).isEqualTo(SessionRestoreMode.OFF);
        assertThat(rule.sessionRestoreOutput()).isFalse();
    }

    @Test
    void anInvalidPasteWarningRejectsTheFile() throws IOException {
        PolicyLoadResult result = load("""
            [rule.terminal]
            paste-warning = "sometimes"
            """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0))
            .contains("paste-warning must be \"off\", \"unless-bracketed\" or \"always\"");
    }

    @Test
    void anInvalidSessionRestoreRejectsTheFile() throws IOException {
        PolicyLoadResult result = load("""
            [rule.terminal]
            session-restore = "always"
            """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0)).contains("session-restore must be \"off\", \"ask\" or \"auto\"");
    }

    @Test
    void aNonBooleanSessionRestoreOutputRejectsTheFile() throws IOException {
        PolicyLoadResult result = load("""
            [rule.terminal]
            session-restore-output = "off"
            """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0)).contains("session-restore-output must be true or false");
    }

    @Test
    void anInvalidMultiExecDecisionRejectsTheFile() throws IOException {
        PolicyLoadResult result = load("""
            [rule.features]
            multi-exec = "read-only"
            """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0)).contains("multi-exec must be \"allow\" or \"deny\"");
    }

    @Test
    void theNewKeysAreNotReportedAsUnknown() throws IOException {
        PolicyLoadResult result = load("""
            [rule.security]
            allow-osc52-clipboard-write = true
            [rule.terminal]
            load-into-snippet-editor = "allow"
            paste-warning = "unless-bracketed"
            session-restore = "ask"
            session-restore-output = true
            """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    // ---- resolution -----------------------------------------------------------------------------

    @Test
    void anUnrestrictedPolicySetsNothingAndLocksNothing() {
        EffectivePolicy policy = EffectivePolicy.unrestricted();
        assertThat(policy.pasteWarningFloor()).isNull();
        assertThat(policy.osc52ClipboardWriteAllowed()).isTrue();
        assertThat(policy.multiExecAllowed()).isTrue();
        assertThat(policy.sessionRestoreMode()).isNull();
        assertThat(policy.isManaged(ManagedSetting.PASTE_WARNING)).isFalse();
        assertThat(policy.isManaged(ManagedSetting.MULTI_EXEC)).isFalse();
        assertThat(policy.isManaged(ManagedSetting.SESSION_RESTORE)).isFalse();
        assertThat(policy.sessionRestoreOutput()).isNull();
        assertThat(policy.isManaged(ManagedSetting.SESSION_RESTORE_OUTPUT)).isFalse();
        assertThat(policy.isManaged(ManagedSetting.CLIPBOARD)).isFalse();
    }

    @Test
    void lockdownAsksForEveryPasteForbidsOsc52AndMultiExecAndRestoresNothing() {
        EffectivePolicy lockdown = EffectivePolicy.lockdown();
        assertThat(lockdown.pasteWarningFloor()).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(lockdown.osc52ClipboardWriteAllowed()).isFalse();
        assertThat(lockdown.multiExecAllowed()).isFalse();
        assertThat(lockdown.sessionRestoreMode()).isEqualTo(SessionRestoreMode.OFF);
        assertThat(lockdown.isManaged(ManagedSetting.PASTE_WARNING)).isTrue();
        assertThat(lockdown.isManaged(ManagedSetting.MULTI_EXEC)).isTrue();
        assertThat(lockdown.isManaged(ManagedSetting.SESSION_RESTORE)).isTrue();
        assertWithMessage("lockdown writes no terminal output to disk")
            .that(lockdown.sessionRestoreOutput()).isFalse();
        assertThat(lockdown.isManaged(ManagedSetting.SESSION_RESTORE_OUTPUT)).isTrue();
        assertThat(lockdown.isManaged(ManagedSetting.CLIPBOARD)).isTrue();
    }

    @Test
    void aPolicyThatMentionsNoneOfThemLeavesThemToTheUser() {
        EffectivePolicy policy = resolve(PolicyRule.builder().updatesEnabled(false).build());
        assertThat(policy.pasteWarningFloor()).isNull();
        assertThat(policy.osc52ClipboardWriteAllowed()).isTrue();
        assertThat(policy.multiExecAllowed()).isTrue();
        assertThat(policy.sessionRestoreMode()).isNull();
        assertThat(policy.isManaged(ManagedSetting.PASTE_WARNING)).isFalse();
        assertThat(policy.isManaged(ManagedSetting.SESSION_RESTORE)).isFalse();
        assertThat(policy.sessionRestoreOutput()).isNull();
        assertThat(policy.isManaged(ManagedSetting.SESSION_RESTORE_OUTPUT)).isFalse();
        assertThat(policy.isManaged(ManagedSetting.MULTI_EXEC)).isFalse();
    }

    @Test
    void sameTierRulesMergeToTheMostRestrictiveValue() {
        PolicyRule lax = PolicyRule.builder()
            .pasteWarningFloor(PasteWarningMode.OFF)
            .allowOsc52ClipboardWrite(true)
            .sessionRestoreMode(SessionRestoreMode.AUTO)
            .sessionRestoreOutput(true)
            .features(Map.of(PolicyFeature.MULTI_EXEC, PolicyDecision.ALLOW))
            .build();
        PolicyRule strict = PolicyRule.builder()
            .pasteWarningFloor(PasteWarningMode.UNLESS_BRACKETED)
            .allowOsc52ClipboardWrite(false)
            .sessionRestoreMode(SessionRestoreMode.ASK)
            .sessionRestoreOutput(false)
            .features(Map.of(PolicyFeature.MULTI_EXEC, PolicyDecision.DENY))
            .build();
        EffectivePolicy policy = resolve(lax, strict);
        assertThat(policy.pasteWarningFloor()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(policy.osc52ClipboardWriteAllowed()).isFalse();
        assertWithMessage("ask opens fewer connections by itself than auto")
            .that(policy.sessionRestoreMode()).isEqualTo(SessionRestoreMode.ASK);
        assertThat(policy.multiExecAllowed()).isFalse();
        assertWithMessage("off keeps terminal output off the disk")
            .that(policy.sessionRestoreOutput()).isFalse();
        assertThat(policy.isManaged(ManagedSetting.SESSION_RESTORE_OUTPUT)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.PASTE_WARNING)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.SESSION_RESTORE)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.MULTI_EXEC)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.CLIPBOARD)).isTrue();

        PolicyRule off = PolicyRule.builder().sessionRestoreMode(SessionRestoreMode.OFF).build();
        assertThat(resolve(strict, off).sessionRestoreMode()).isEqualTo(SessionRestoreMode.OFF);
    }

    @Test
    void aMoreSpecificTierRelaxesTheBaseline() {
        PolicyRule everyone = PolicyRule.builder()
            .pasteWarningFloor(PasteWarningMode.ALWAYS)
            .features(Map.of(PolicyFeature.MULTI_EXEC, PolicyDecision.DENY))
            .build();
        PolicyRule ops = PolicyRule.builder()
            .groups(Set.of("ops"))
            .pasteWarningFloor(PasteWarningMode.UNLESS_BRACKETED)
            .features(Map.of(PolicyFeature.MULTI_EXEC, PolicyDecision.ALLOW))
            .build();
        EffectivePolicy policy = resolve(everyone, ops);
        assertThat(policy.pasteWarningFloor()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(policy.multiExecAllowed()).isTrue();
    }

    @Test
    void multiExecIsNotChainedToAnyOtherFeature() {
        EffectivePolicy policy = resolve(PolicyRule.builder().features(Map.of(
            PolicyFeature.AI, PolicyDecision.DENY,
            PolicyFeature.TERMINAL_TRIGGERS, PolicyDecision.DENY,
            PolicyFeature.CONTROL_API, PolicyDecision.DENY)).build());
        assertThat(policy.multiExecAllowed()).isTrue();
        assertThat(PolicyFeature.fromTomlKey(" Multi-Exec ")).isEqualTo(PolicyFeature.MULTI_EXEC);
    }

    @Test
    void allowingOsc52LeavesTheClipboardModeAlone() {
        EffectivePolicy policy = resolve(PolicyRule.builder().allowOsc52ClipboardWrite(true).build());
        assertThat(policy.osc52ClipboardWriteAllowed()).isTrue();
        assertThat(policy.clipboardMode()).isEqualTo(ClipboardMode.SYSTEM);
    }

    // ---- clamp ----------------------------------------------------------------------------------

    @Test
    void theClampRaisesThePasteWarningToTheFloorButNeverLowersIt() {
        PolicyClamp clamp = new PolicyClamp(resolve(
            PolicyRule.builder().pasteWarningFloor(PasteWarningMode.UNLESS_BRACKETED).build()));

        GlobalSettings off = new GlobalSettings();
        off.setPasteWarningMode(PasteWarningMode.OFF);
        clamp.apply(off);
        assertThat(off.getPasteWarningMode()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);

        GlobalSettings always = new GlobalSettings();
        always.setPasteWarningMode(PasteWarningMode.ALWAYS);
        clamp.apply(always);
        assertWithMessage("asking more often than the floor demands is allowed")
            .that(always.getPasteWarningMode()).isEqualTo(PasteWarningMode.ALWAYS);
    }

    @Test
    void theClampSwitchesOsc52OffAndSetsTheSessionRestoreMode() {
        PolicyClamp clamp = new PolicyClamp(resolve(PolicyRule.builder()
            .allowOsc52ClipboardWrite(false)
            .sessionRestoreMode(SessionRestoreMode.AUTO)
            .build()));
        GlobalSettings settings = new GlobalSettings();
        settings.setOsc52ClipboardWriteEnabled(true);
        settings.setSessionRestoreMode(SessionRestoreMode.OFF);
        clamp.apply(settings);
        assertThat(settings.isOsc52ClipboardWriteEnabled()).isFalse();
        assertWithMessage("the policy sets the mode, whichever way")
            .that(settings.getSessionRestoreMode()).isEqualTo(SessionRestoreMode.AUTO);
    }

    @Test
    void theClampSetsTheSessionRestoreOutputSwitchWhicheverWay() {
        GlobalSettings on = new GlobalSettings();
        on.setSessionRestoreScrollback(true);
        new PolicyClamp(resolve(PolicyRule.builder().sessionRestoreOutput(false).build())).apply(on);
        assertThat(on.isSessionRestoreScrollback()).isFalse();

        GlobalSettings off = new GlobalSettings();
        new PolicyClamp(resolve(PolicyRule.builder().sessionRestoreOutput(true).build())).apply(off);
        assertThat(off.isSessionRestoreScrollback()).isTrue();
    }

    @Test
    void allowingOsc52DoesNotSwitchItOn() {
        PolicyClamp clamp = new PolicyClamp(resolve(PolicyRule.builder().allowOsc52ClipboardWrite(true).build()));
        GlobalSettings settings = new GlobalSettings();
        clamp.apply(settings);
        assertThat(settings.isOsc52ClipboardWriteEnabled()).isFalse();
        settings.setOsc52ClipboardWriteEnabled(true);
        clamp.apply(settings);
        assertThat(settings.isOsc52ClipboardWriteEnabled()).isTrue();
    }

    @Test
    void withoutAPolicyFileNothingIsClamped() {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteWarningMode(PasteWarningMode.OFF);
        settings.setOsc52ClipboardWriteEnabled(true);
        settings.setSessionRestoreMode(SessionRestoreMode.AUTO);
        settings.setSessionRestoreScrollback(true);
        new PolicyClamp(EffectivePolicy.unrestricted()).apply(settings);
        assertThat(settings.getPasteWarningMode()).isEqualTo(PasteWarningMode.OFF);
        assertThat(settings.isOsc52ClipboardWriteEnabled()).isTrue();
        assertThat(settings.getSessionRestoreMode()).isEqualTo(SessionRestoreMode.AUTO);
        assertThat(settings.isSessionRestoreScrollback()).isTrue();
    }

    @Test
    void lockdownClampsAllFourOnLoadAndSave() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(dir());
        manager.setPolicyClamp(new PolicyClamp(EffectivePolicy.lockdown()));
        manager.load();
        GlobalSettings settings = manager.getSettings();
        assertThat(settings.getPasteWarningMode()).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(settings.isOsc52ClipboardWriteEnabled()).isFalse();
        assertThat(settings.getSessionRestoreMode()).isEqualTo(SessionRestoreMode.OFF);
        assertThat(settings.isSessionRestoreScrollback()).isFalse();

        settings.setPasteWarningMode(PasteWarningMode.OFF);
        settings.setOsc52ClipboardWriteEnabled(true);
        settings.setSessionRestoreMode(SessionRestoreMode.AUTO);
        settings.setSessionRestoreScrollback(true);
        manager.save();
        assertThat(settings.getPasteWarningMode()).isEqualTo(PasteWarningMode.ALWAYS);
        assertThat(settings.isOsc52ClipboardWriteEnabled()).isFalse();
        assertThat(settings.getSessionRestoreMode()).isEqualTo(SessionRestoreMode.OFF);
        assertThat(settings.isSessionRestoreScrollback()).isFalse();
    }

    // ---- SessionRestoreMode helpers ---------------------------------------------------------------

    @Test
    void sessionRestoreModeParsesStrictlyAndMergesTowardsOff() {
        assertThat(SessionRestoreMode.parseId(" AUTO ")).isEqualTo(SessionRestoreMode.AUTO);
        assertThat(SessionRestoreMode.parseId("always")).isNull();
        assertThat(SessionRestoreMode.parseId(null)).isNull();
        assertThat(SessionRestoreMode.fromId("always")).isEqualTo(SessionRestoreMode.DEFAULT);
        assertThat(SessionRestoreMode.leastAutomatic(SessionRestoreMode.AUTO, SessionRestoreMode.ASK))
            .isEqualTo(SessionRestoreMode.ASK);
        assertThat(SessionRestoreMode.leastAutomatic(SessionRestoreMode.ASK, SessionRestoreMode.OFF))
            .isEqualTo(SessionRestoreMode.OFF);
        assertThat(SessionRestoreMode.leastAutomatic(null, SessionRestoreMode.AUTO)).isEqualTo(SessionRestoreMode.AUTO);
        assertThat(SessionRestoreMode.leastAutomatic(SessionRestoreMode.AUTO, null)).isEqualTo(SessionRestoreMode.AUTO);
    }
}
