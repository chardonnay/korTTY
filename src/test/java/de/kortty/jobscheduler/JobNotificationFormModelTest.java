package de.kortty.jobscheduler;

import de.kortty.policy.EffectivePolicy;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The JobScheduler window's "Notifications" section, headless; no JavaFX toolkit. */
class JobNotificationFormModelTest {

    private static WebhookTarget target(String id, String name, WebhookFormat format, boolean enabled) {
        WebhookTarget target = new WebhookTarget();
        target.setId(id);
        target.setName(name);
        target.setFormat(format);
        target.setEnabled(enabled);
        return target;
    }

    private static final List<WebhookTarget> TARGETS = List.of(
        target("t-ops", "ops channel", WebhookFormat.SLACK, true),
        target("t-teams", "Alerts", WebhookFormat.TEAMS, false));

    @Test
    void aJobWithoutConfigurationShowsTheDefaults() {
        JobNotificationFormModel model = JobNotificationFormModel.load(null, TARGETS);

        assertThat(model.triggers()).containsExactly(JobNotificationTrigger.FAILED, JobNotificationTrigger.BLOCKED);
        assertThat(model.isDesktop()).isTrue();
        assertThat(model.selectedTargetIds()).isEmpty();
        assertThat(model.warningKeys(EffectivePolicy.unrestricted())).isEmpty();
    }

    @Test
    void targetsAreListedByNameWithoutTheirUrl() {
        JobNotificationFormModel model = JobNotificationFormModel.load(null, TARGETS);

        assertThat(model.targets()).containsExactly(
            new JobNotificationFormModel.TargetOption("t-teams", "Alerts", WebhookFormat.TEAMS, false),
            new JobNotificationFormModel.TargetOption("t-ops", "ops channel", WebhookFormat.SLACK, true)).inOrder();
    }

    @Test
    void editsRoundTripIntoTheStoredConfiguration() {
        JobNotificationFormModel model = JobNotificationFormModel.load(JobNotificationConfig.defaults(), TARGETS);
        model.setTrigger(JobNotificationTrigger.BLOCKED, false);
        model.setTrigger(JobNotificationTrigger.RECOVERED, true);
        model.setDesktop(false);
        model.setTargetSelected("t-ops", true);
        model.setTargetSelected("unknown", true);

        JobNotificationConfig config = model.toConfig();

        assertThat(config.getTriggers()).containsExactly(JobNotificationTrigger.FAILED, JobNotificationTrigger.RECOVERED);
        assertThat(config.isDesktop()).isFalse();
        assertThat(config.getWebhookTargetIds()).containsExactly("t-ops");

        JobNotificationFormModel reloaded = JobNotificationFormModel.load(config, TARGETS);
        assertThat(reloaded.isTrigger(JobNotificationTrigger.RECOVERED)).isTrue();
        assertThat(reloaded.isTargetSelected("t-ops")).isTrue();
        assertThat(reloaded.isDesktop()).isFalse();
    }

    @Test
    void aDeletedTargetDisappearsFromTheSelection() {
        JobNotificationConfig config = JobNotificationConfig.defaults();
        config.setWebhookTargetIds(List.of("t-ops", "t-gone"));
        JobNotificationFormModel model = JobNotificationFormModel.load(config, TARGETS);
        assertThat(model.selectedTargetIds()).containsExactly("t-ops");

        model.setAvailableTargets(List.of(TARGETS.get(1)));

        assertThat(model.selectedTargetIds()).isEmpty();
        assertThat(model.toConfig().getWebhookTargetIds()).isEmpty();
    }

    @Test
    void warnsWhenTheJobCanNeverNotify() {
        JobNotificationFormModel model = JobNotificationFormModel.load(null, TARGETS);
        model.setDesktop(false);
        assertThat(model.warningKeys(null)).containsExactly(JobNotificationFormModel.WARNING_NO_CHANNEL);

        model.setTrigger(JobNotificationTrigger.FAILED, false);
        model.setTrigger(JobNotificationTrigger.BLOCKED, false);
        assertThat(model.warningKeys(null)).containsExactly(JobNotificationFormModel.WARNING_NO_TRIGGER);
    }

    @Test
    void warnsWhenThePolicyDeniesTheSelectedWebhooks() {
        JobNotificationFormModel model = JobNotificationFormModel.load(null, TARGETS);
        assertThat(model.warningKeys(EffectivePolicy.lockdown())).isEmpty();

        model.setTargetSelected("t-ops", true);

        assertThat(model.warningKeys(EffectivePolicy.lockdown()))
            .containsExactly(JobNotificationFormModel.WARNING_POLICY_DENIED);
        assertThat(model.warningKeys(EffectivePolicy.unrestricted())).isEmpty();
    }

    @Test
    void theTargetEditorKeepsAStoredUrlAndRejectsBadOnes() {
        assertThat(JobNotificationFormModel.validateTarget(" ", "https://example.org/hook", false))
            .hasValue(JobNotificationFormModel.ERROR_TARGET_NAME);
        assertThat(JobNotificationFormModel.validateTarget("‮\u0007", "https://example.org/hook", false))
            .hasValue(JobNotificationFormModel.ERROR_TARGET_NAME);
        assertThat(JobNotificationFormModel.validateTarget("Ops", "", true)).isEmpty();
        assertThat(JobNotificationFormModel.validateTarget("Ops", "", false))
            .hasValue(WebhookUrlValidator.Problem.EMPTY.i18nKey());
        assertThat(JobNotificationFormModel.validateTarget("Ops", "http://hooks.example.org/x", true))
            .hasValue(WebhookUrlValidator.Problem.INSECURE_HTTP.i18nKey());
        assertThat(JobNotificationFormModel.validateTarget("Ops", "https://user:pw@hooks.example.org/x", false))
            .hasValue(WebhookUrlValidator.Problem.USERINFO.i18nKey());
        assertThat(JobNotificationFormModel.validateTarget("Ops", "https://hooks.slack.com/services/T/B/x", false))
            .isEmpty();
        assertThat(JobNotificationFormModel.replacesUrl("  ")).isFalse();
        assertThat(JobNotificationFormModel.replacesUrl("https://x.example")).isTrue();
    }

    @Test
    void labelsAreSanitizedAndFallBackToTheId() {
        assertThat(JobNotificationFormModel.label(target("t1", "Ops‮\u001B[31m", WebhookFormat.SLACK, true)))
            .isEqualTo("Ops[31m");
        assertThat(JobNotificationFormModel.label(target("t1", null, WebhookFormat.SLACK, true))).isEqualTo("t1");
    }
}
