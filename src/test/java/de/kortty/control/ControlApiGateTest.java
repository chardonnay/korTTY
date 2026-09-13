package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import org.testng.annotations.Test;

/** The full gate truth table, including both null arguments. */
class ControlApiGateTest {

    /** Settings with the control API switched on; the default is off. */
    private static GlobalSettings enabledSettings() {
        GlobalSettings settings = new GlobalSettings();
        settings.setControlApiEnabled(true);
        return settings;
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
        assertThat(ControlApiGate.shouldRun(enabledSettings(), null)).isFalse();
        assertThat(ControlApiGate.shouldRun(new GlobalSettings(), null)).isFalse();
    }

    @Test
    void freshSettingsKeepTheApiClosedBecauseTheSettingDefaultsToOff() {
        assertWithMessage("the control API must be opted into, never out of")
            .that(ControlApiGate.shouldRun(new GlobalSettings(), EffectivePolicy.unrestricted()))
            .isFalse();
    }

    @Test
    void anUnrestrictedPolicyAndTheSettingOnOpensTheGate() {
        assertThat(ControlApiGate.shouldRun(enabledSettings(), EffectivePolicy.unrestricted())).isTrue();
    }

    @Test
    void lockdownRefusesEvenWithTheSettingOn() {
        assertWithMessage("lockdown() denies every PolicyFeature, CONTROL_API included")
            .that(ControlApiGate.shouldRun(enabledSettings(), EffectivePolicy.lockdown()))
            .isFalse();
    }
}
