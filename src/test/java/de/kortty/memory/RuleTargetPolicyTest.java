package de.kortty.memory;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.UserMemoryMode;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.function.Function;

import static com.google.common.truth.Truth.assertThat;

class RuleTargetPolicyTest {

    private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void skillCreationNeedsTheSettingAndThePolicy() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        MemoryService memory = memory(UserMemoryMode.EDITABLE);

        assertThat(RuleTargetPolicy.from(settings, EffectivePolicy.unrestricted(), memory).skillCreationAllowed())
            .isTrue();

        settings.setAiSkillAutoCreate(false);
        assertThat(RuleTargetPolicy.from(settings, EffectivePolicy.unrestricted(), memory).skillCreationAllowed())
            .isFalse();
    }

    @Test
    void aReadOnlyUserMemoryIsNotWritableButSkillsStillAre() throws Exception {
        RuleTargetPolicy view = RuleTargetPolicy.from(new GlobalSettings(), EffectivePolicy.unrestricted(),
            memory(UserMemoryMode.READ_ONLY));

        assertThat(view.memoryWritable()).isFalse();
        assertThat(view.skillCreationAllowed()).isTrue();
    }

    @Test
    void theBudgetIsTheSettingUnlessThePolicySetsALowerCap() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiMemoryMaxTokens(2000);

        assertThat(RuleTargetPolicy.from(settings, EffectivePolicy.unrestricted(), memory(UserMemoryMode.EDITABLE))
            .memoryBudgetTokens()).isEqualTo(2000);
    }

    @Test
    void theKillSwitchStopsSkillRulesToo() throws Exception {
        MemoryService memory = memory(UserMemoryMode.EDITABLE, name -> "off");

        RuleTargetPolicy view = RuleTargetPolicy.from(new GlobalSettings(), EffectivePolicy.unrestricted(), memory);

        assertThat(view.memoryWritable()).isFalse();
        assertThat(view.skillCreationAllowed()).isFalse();
    }

    private static MemoryService memory(UserMemoryMode mode) throws Exception {
        return memory(mode, name -> null);
    }

    private static MemoryService memory(UserMemoryMode mode, Function<String, String> environment) throws Exception {
        Path root = Files.createTempDirectory("kortty-rule-policy-");
        MemoryService memory = new MemoryService(new MemoryStore(root.resolve("memory"), CLOCK), environment);
        memory.setAiMemoryAllowedSupplier(() -> true);
        memory.setUserMemoryModeSupplier(() -> mode);
        return memory;
    }
}
