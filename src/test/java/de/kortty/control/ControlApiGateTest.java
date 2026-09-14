package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import org.testng.annotations.Test;

/** The full gate truth table, including both null arguments, and the wire code each verdict raises. */
class ControlApiGateTest {

    /** Settings with the control API switched on; the default is off. */
    private static GlobalSettings enabledSettings() {
        GlobalSettings settings = new GlobalSettings();
        settings.setControlApiEnabled(true);
        return settings;
    }

    @Test
    void bothLegsMustSayYes() {
        assertThat(ControlApiGate.evaluate(Boolean.TRUE, Boolean.TRUE))
            .isEqualTo(ControlApiGate.Verdict.OPEN);
    }

    @Test
    void aDeniedPolicyWinsOverAnEnabledSetting() {
        assertWithMessage("an administrator's decision is what the client must be told about, because"
                + " it is the one refusal the user cannot lift")
            .that(ControlApiGate.evaluate(Boolean.FALSE, Boolean.TRUE))
            .isEqualTo(ControlApiGate.Verdict.BLOCKED_BY_POLICY);
    }

    @Test
    void aDeniedPolicyAlsoOutranksASettingThatIsOffBecauseTheClampTurnedItOff() {
        assertWithMessage("PolicyClamp forces the setting false whenever policy denies, so 'the"
                + " setting is off' is a consequence of the policy and not a user choice; reporting"
                + " the setting would send a managed client to a checkbox it cannot use")
            .that(ControlApiGate.evaluate(Boolean.FALSE, Boolean.FALSE))
            .isEqualTo(ControlApiGate.Verdict.BLOCKED_BY_POLICY);
    }

    @Test
    void anAllowedPolicyDoesNotEnableTheApiOnItsOwn() {
        assertThat(ControlApiGate.evaluate(Boolean.TRUE, Boolean.FALSE))
            .isEqualTo(ControlApiGate.Verdict.DISABLED_BY_SETTING);
    }

    @Test
    void anUnknownLegIsAClosedButRetryableGate() {
        assertThat(ControlApiGate.evaluate(null, null)).isEqualTo(ControlApiGate.Verdict.NOT_READY);
        assertThat(ControlApiGate.evaluate(null, Boolean.TRUE))
            .isEqualTo(ControlApiGate.Verdict.NOT_READY);
        assertThat(ControlApiGate.evaluate(Boolean.TRUE, null))
            .isEqualTo(ControlApiGate.Verdict.NOT_READY);
        for (ControlApiGate.Verdict verdict : ControlApiGate.Verdict.values()) {
            if (verdict != ControlApiGate.Verdict.OPEN) {
                assertWithMessage("%s must never open the listener", verdict)
                    .that(verdict.isOpen()).isFalse();
            }
        }
    }

    @Test
    void aKnownRefusalOnTheOtherLegIsReportedRatherThanCollapsedIntoNotReady() {
        assertWithMessage("a policy that denies is a definite answer whether or not the settings have"
                + " loaded, and telling the client to retry would be wrong")
            .that(ControlApiGate.evaluate(Boolean.FALSE, null))
            .isEqualTo(ControlApiGate.Verdict.BLOCKED_BY_POLICY);
        assertWithMessage("a setting that is off is equally definite before the policy resolves")
            .that(ControlApiGate.evaluate(null, Boolean.FALSE))
            .isEqualTo(ControlApiGate.Verdict.DISABLED_BY_SETTING);
    }

    @Test
    void everyVerdictCarriesTheWireCodeTheProtocolNamesForIt() {
        assertWithMessage("OPEN raises nothing; it is the one arm that serves")
            .that(ControlApiGate.Verdict.OPEN.errorCode()).isNull();
        assertThat(ControlApiGate.Verdict.OPEN.isOpen()).isTrue();
        assertThat(ControlApiGate.Verdict.DISABLED_BY_SETTING.errorCode())
            .isEqualTo(ControlErrorCode.CONTROL_API_DISABLED);
        assertThat(ControlApiGate.Verdict.BLOCKED_BY_POLICY.errorCode())
            .isEqualTo(ControlErrorCode.BLOCKED_BY_POLICY);
        assertThat(ControlApiGate.Verdict.NOT_READY.errorCode()).isEqualTo(ControlErrorCode.NOT_READY);
        assertWithMessage("§4 makes not_ready the one retryable member of this group, because the"
                + " identical request succeeds once korTTY has finished starting")
            .that(ControlErrorCode.NOT_READY.retryable()).isTrue();
    }

    @Test
    void noRefusalMessageNamesTheSettingThePolicyFileOrTheRule() {
        for (ControlApiGate.Verdict verdict : ControlApiGate.Verdict.values()) {
            String message = verdict.message();
            assertWithMessage("%s must carry a message a client can show", verdict)
                .that(message).isNotEmpty();
            assertWithMessage("%s names the cause, never the configuration that produced it", verdict)
                .that(message.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("kortty-policy");
        }
    }

    @Test
    void bothNullsAreNotReadyRatherThanADecision() {
        assertThat(ControlApiGate.verdict(null, null)).isEqualTo(ControlApiGate.Verdict.NOT_READY);
    }

    @Test
    void aNullSettingsObjectRefusesWhateverThePolicySays() {
        assertThat(ControlApiGate.verdict(null, EffectivePolicy.unrestricted()).isOpen()).isFalse();
        assertThat(ControlApiGate.verdict(null, EffectivePolicy.lockdown()).isOpen()).isFalse();
    }

    @Test
    void aNullPolicyRefusesWhateverTheSettingSays() {
        assertThat(ControlApiGate.verdict(enabledSettings(), null).isOpen()).isFalse();
        assertThat(ControlApiGate.verdict(new GlobalSettings(), null).isOpen()).isFalse();
    }

    @Test
    void freshSettingsKeepTheApiClosedBecauseTheSettingDefaultsToOff() {
        assertWithMessage("the control API must be opted into, never out of")
            .that(ControlApiGate.verdict(new GlobalSettings(), EffectivePolicy.unrestricted()))
            .isEqualTo(ControlApiGate.Verdict.DISABLED_BY_SETTING);
    }

    @Test
    void anUnrestrictedPolicyAndTheSettingOnOpensTheGate() {
        assertThat(ControlApiGate.verdict(enabledSettings(), EffectivePolicy.unrestricted()))
            .isEqualTo(ControlApiGate.Verdict.OPEN);
    }

    @Test
    void lockdownRefusesEvenWithTheSettingOnAndSaysItWasThePolicy() {
        assertWithMessage("lockdown() denies every PolicyFeature, CONTROL_API included")
            .that(ControlApiGate.verdict(enabledSettings(), EffectivePolicy.lockdown()))
            .isEqualTo(ControlApiGate.Verdict.BLOCKED_BY_POLICY);
    }
}
