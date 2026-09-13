package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import org.testng.annotations.Test;

/** The full gate truth table, including both null arguments. */
class ControlApiGateTest {

    /** A settings object that already carries the accessor the integration package adds. */
    static class FlaggedSettings extends GlobalSettings {

        private final boolean enabled;

        FlaggedSettings(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isControlApiEnabled() {
            return enabled;
        }
    }

    @Test
    void bothLegsMustSayYes() {
        assertThat(ControlApiGate.evaluate(Boolean.TRUE, Boolean.TRUE)).isTrue();
    }

    @Test
    void aDeniedPolicyWinsOverAnEnabledSetting() {
        assertThat(ControlApiGate.evaluate(Boolean.FALSE, Boolean.TRUE)).isFalse();
    }

    @Test
    void anAllowedPolicyDoesNotEnableTheApiOnItsOwn() {
        assertThat(ControlApiGate.evaluate(Boolean.TRUE, Boolean.FALSE)).isFalse();
        assertThat(ControlApiGate.evaluate(Boolean.FALSE, Boolean.FALSE)).isFalse();
    }

    @Test
    void anUnknownLegIsARefusal() {
        assertThat(ControlApiGate.evaluate(null, null)).isFalse();
        assertThat(ControlApiGate.evaluate(null, Boolean.TRUE)).isFalse();
        assertThat(ControlApiGate.evaluate(null, Boolean.FALSE)).isFalse();
        assertThat(ControlApiGate.evaluate(Boolean.TRUE, null)).isFalse();
        assertThat(ControlApiGate.evaluate(Boolean.FALSE, null)).isFalse();
    }

    @Test
    void bothNullsRefuse() {
        assertThat(ControlApiGate.shouldRun(null, null)).isFalse();
    }

    @Test
    void aNullSettingsObjectRefusesWhateverThePolicySays() {
        assertThat(ControlApiGate.shouldRun(null, EffectivePolicy.unrestricted())).isFalse();
        assertThat(ControlApiGate.shouldRun(null, EffectivePolicy.lockdown())).isFalse();
    }

    @Test
    void aNullPolicyRefusesWhateverTheSettingSays() {
        assertThat(ControlApiGate.shouldRun(new FlaggedSettings(true), null)).isFalse();
        assertThat(ControlApiGate.shouldRun(new FlaggedSettings(false), null)).isFalse();
        assertThat(ControlApiGate.shouldRun(new GlobalSettings(), null)).isFalse();
    }

    @Test
    void aSettingsObjectWithoutTheAccessorIsRefused() {
        assertThat(ControlApiGate.shouldRun(new GlobalSettings(), EffectivePolicy.unrestricted())).isFalse();
    }

    @Test
    void aLegIsReadOffTheRuntimeClass() {
        assertThat(ControlApiGate.flag(new FlaggedSettings(true), "isControlApiEnabled")).isTrue();
        assertThat(ControlApiGate.flag(new FlaggedSettings(false), "isControlApiEnabled")).isFalse();
    }

    @Test
    void aMissingOrUnreadableAccessorLeavesTheLegUnknown() {
        assertThat(ControlApiGate.flag(null, "isControlApiEnabled")).isNull();
        assertThat(ControlApiGate.flag(new GlobalSettings(), "isControlApiEnabled")).isNull();
        assertThat(ControlApiGate.flag(EffectivePolicy.unrestricted(), "controlApiAllowed")).isNull();
        assertThat(ControlApiGate.flag(new FlaggedSettings(true), "toString")).isNull();
    }

    @Test
    void anUnknownPolicyLegRefusesEvenWithTheSettingOn() {
        // Until the integration package adds EffectivePolicy.controlApiAllowed() the policy leg is
        // unknown, and an unknown leg is a refusal — the API cannot come up half-wired.
        assertThat(ControlApiGate.shouldRun(new FlaggedSettings(true), EffectivePolicy.unrestricted()))
            .isFalse();
    }
}
