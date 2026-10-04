package de.kortty.jobscheduler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.annotations.Test;

import java.time.Instant;

import static com.google.common.truth.Truth.assertThat;

class WebhookPayloadFormatterTest {

    private static final Instant FINISHED = Instant.parse("2026-10-04T08:00:00Z");
    private static final String TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String SUMMARY = "Backup failed for hunter2 on db1: token=" + TOKEN;

    private final WebhookPayloadFormatter formatter = new WebhookPayloadFormatter(JobNotificationDispatcherTest.I18N);

    private static JobRunEvent failedRun() {
        return new JobRunEvent("job-1", "Nightly backup", JobRunStatus.FAILED, null, 2, "scheduled", SUMMARY, FINISHED);
    }

    @Test
    void genericJsonGolden() {
        assertThat(formatter.format(WebhookFormat.GENERIC_JSON, failedRun(), false, null)).isEqualTo(
            "{\"schema\":\"kortty.job-run/1\",\"job\":{\"id\":\"job-1\",\"name\":\"Nightly backup\"},"
                + "\"status\":\"failed\",\"recovered\":false,\"exitCode\":2,\"trigger\":\"scheduled\","
                + "\"finishedAt\":\"2026-10-04T08:00:00Z\"}");
    }

    @Test
    void genericJsonOfARecoveryWithoutExitCode() {
        JobRunEvent recovered = new JobRunEvent("job-2", "Deploy", JobRunStatus.SUCCESS, JobRunStatus.BLOCKED,
            null, "manual", "ok", FINISHED);

        assertThat(formatter.format(WebhookFormat.GENERIC_JSON, recovered, false, null)).isEqualTo(
            "{\"schema\":\"kortty.job-run/1\",\"job\":{\"id\":\"job-2\",\"name\":\"Deploy\"},"
                + "\"status\":\"success\",\"recovered\":true,\"exitCode\":null,\"trigger\":\"manual\","
                + "\"finishedAt\":\"2026-10-04T08:00:00Z\"}");
    }

    @Test
    void slackGolden() {
        assertThat(formatter.format(WebhookFormat.SLACK, failedRun(), false, null)).isEqualTo(
            "{\"text\":\"korTTY · Job Nightly backup: Failed · Exit code 2 · Scheduled run\","
                + "\"blocks\":[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\","
                + "\"text\":\"*korTTY · Job Nightly backup*\\nFailed · Exit code 2 · Scheduled run\"}},"
                + "{\"type\":\"context\",\"elements\":[{\"type\":\"plain_text\",\"text\":\"2026-10-04T08:00:00Z\"}]}]}");
    }

    @Test
    void teamsGoldenIsAWorkflowsAdaptiveCard() {
        assertThat(formatter.format(WebhookFormat.TEAMS, failedRun(), false, null)).isEqualTo(
            "{\"type\":\"message\",\"attachments\":[{\"contentType\":\"application/vnd.microsoft.card.adaptive\","
                + "\"contentUrl\":null,\"content\":{\"$schema\":\"http://adaptivecards.io/schemas/adaptive-card.json\","
                + "\"type\":\"AdaptiveCard\",\"version\":\"1.4\",\"body\":["
                + "{\"type\":\"TextBlock\",\"text\":\"korTTY · Job Nightly backup\",\"wrap\":true,"
                + "\"weight\":\"Bolder\",\"size\":\"Medium\"},"
                + "{\"type\":\"TextBlock\",\"text\":\"Failed · Exit code 2 · Scheduled run\",\"wrap\":true},"
                + "{\"type\":\"TextBlock\",\"text\":\"2026-10-04T08:00:00Z\",\"wrap\":true,\"isSubtle\":true,"
                + "\"size\":\"Small\"}]}}]}");
    }

    @Test
    void noFormatCarriesTheSummaryWithoutOptIn() {
        for (WebhookFormat format : WebhookFormat.values()) {
            String json = formatter.format(format, failedRun(), false, null);
            assertThat(json).doesNotContain("Backup failed");
            assertThat(json).doesNotContain("db1");
            assertThat(json).doesNotContain(TOKEN);
        }
        WebhookTarget target = new WebhookTarget();
        target.setFormat(WebhookFormat.GENERIC_JSON);
        assertThat(formatter.format(target, failedRun(), null)).doesNotContain("summary");
    }

    @Test
    void optedInSummaryIsMaskedInEveryFormat() {
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();
        redactor.addSecret("hunter2");
        for (WebhookFormat format : WebhookFormat.values()) {
            String json = formatter.format(format, failedRun(), true, redactor);
            assertThat(json).contains("on db1");
            assertThat(json).doesNotContain(TOKEN);
            assertThat(json).doesNotContain("hunter2");
        }
        JsonObject generic = JsonParser.parseString(
            formatter.format(WebhookFormat.GENERIC_JSON, failedRun(), true, redactor)).getAsJsonObject();
        assertThat(generic.get("summary").getAsString()).isEqualTo("Backup failed for *** on db1: token=ghp_***");
        // Teams renders Markdown, so the mask's asterisks are escaped there.
        assertThat(formatter.format(WebhookFormat.TEAMS, failedRun(), true, redactor))
            .contains("Backup failed for \\\\*\\\\*\\\\* on db1: token=ghp\\\\_\\\\*\\\\*\\\\*");
    }

    @Test
    void tokenIsMaskedEvenWithoutRunSecrets() {
        String json = formatter.format(WebhookFormat.SLACK, failedRun(), true, null);

        assertThat(json).contains("ghp_***");
        assertThat(json).doesNotContain(TOKEN);
    }

    @Test
    void summaryIsCappedAndFlattened() {
        JobRunEvent event = new JobRunEvent("j", "J", JobRunStatus.FAILED, null, 1, "manual",
            "line one\nline two‮" + "x".repeat(5_000), FINISHED);

        String summary = JsonParser.parseString(formatter.format(WebhookFormat.GENERIC_JSON, event, true, null))
            .getAsJsonObject().get("summary").getAsString();

        assertThat(summary).startsWith("line one line two");
        assertThat(summary).doesNotContain("‮");
        assertThat(summary.length()).isAtMost(WebhookPayloadFormatter.MAX_SUMMARY_CHARS);
    }

    @Test
    void jobNameCannotMentionAChannelOrInjectALink() {
        JobRunEvent event = new JobRunEvent("j", "<!channel> [click](https://evil.example)", JobRunStatus.FAILED,
            null, 1, "manual", null, FINISHED);

        String slack = formatter.format(WebhookFormat.SLACK, event, false, null);
        assertThat(slack).doesNotContain("<!channel>");
        assertThat(slack).contains("&lt;!channel&gt;");

        JsonObject title = JsonParser.parseString(formatter.format(WebhookFormat.TEAMS, event, false, null))
            .getAsJsonObject().getAsJsonArray("attachments").get(0).getAsJsonObject()
            .getAsJsonObject("content").getAsJsonArray("body").get(0).getAsJsonObject();
        assertThat(title.get("text").getAsString()).contains("\\[click\\](https://evil.example)");
    }

    @Test
    void endpointConstantsMatchTheRegistry() {
        // external-apis.yaml matches these literals in this file.
        assertThat(WebhookPayloadFormatter.SLACK_WEBHOOK_PREFIX).isEqualTo("https://hooks.slack.com/services/");
        assertThat(WebhookPayloadFormatter.ADAPTIVE_CARD_VERSION).isEqualTo("1.4");
        assertThat(WebhookPayloadFormatter.GENERIC_SCHEMA).isEqualTo("kortty.job-run/1");
    }
}
