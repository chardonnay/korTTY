package de.kortty.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.jobscheduler.JobWebhookNotifier;
import de.kortty.jobscheduler.WebhookFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * {@code job_notification_sent}: the channel, the payload format, how it ended and the attempt
 * count; never a job or target name, a URL or a host.
 */
class JobNotificationTelemetryTest {

    @Test
    void theEventNameIsSnakeCase() {
        assertThat(TelemetryEvents.JOB_NOTIFICATION_SENT).isEqualTo("job_notification_sent");
    }

    @Test
    void thePropsAreOnlyIdsAndAClampedAttemptCount() {
        Map<String, Object> props = JobNotificationTelemetry.props(JobNotificationTelemetry.Channel.WEBHOOK,
            JobNotificationTelemetry.Format.TEAMS, JobNotificationTelemetry.Outcome.FAILED, 3);

        assertThat(props).containsExactly("channel", "webhook", "format", "teams", "outcome", "failed", "attempts", 3)
            .inOrder();
        for (Object value : props.values()) {
            assertWithMessage("props are enum ids or numbers only")
                .that(value instanceof String || value instanceof Integer).isTrue();
        }
        assertThat(JobNotificationTelemetry.props(null, null, null, 99))
            .containsExactly("channel", "desktop", "format", "none", "outcome", "ok", "attempts", 3).inOrder();
        assertThat(JobNotificationTelemetry.props(JobNotificationTelemetry.Channel.DESKTOP,
            JobNotificationTelemetry.Format.NONE, JobNotificationTelemetry.Outcome.BLOCKED, -1)).containsEntry("attempts", 0);
    }

    @Test
    void everyWebhookFormatMapsToAFixedId() {
        assertThat(JobWebhookNotifier.telemetryFormat(WebhookFormat.SLACK)).isEqualTo(JobNotificationTelemetry.Format.SLACK);
        assertThat(JobWebhookNotifier.telemetryFormat(WebhookFormat.TEAMS)).isEqualTo(JobNotificationTelemetry.Format.TEAMS);
        assertThat(JobWebhookNotifier.telemetryFormat(WebhookFormat.GENERIC_JSON))
            .isEqualTo(JobNotificationTelemetry.Format.GENERIC);
        assertThat(JobWebhookNotifier.telemetryFormat(null)).isEqualTo(JobNotificationTelemetry.Format.GENERIC);
    }

    @Test
    void theGuideListsTheEvent() throws IOException {
        String guide = Files.readString(Path.of("app-docs/site/docs/en/about/anonymous-data.md"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(guide).contains("`job_notification_sent`");
    }
}
