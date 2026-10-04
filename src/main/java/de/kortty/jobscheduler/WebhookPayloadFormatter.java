package de.kortty.jobscheduler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.kortty.core.DisplayTextSanitizer;
import de.kortty.core.SecretTokenPatterns;
import org.jetbrains.annotations.Nullable;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Builds the JSON body a webhook target receives for a finished job run.
 *
 * <ul>
 *   <li>{@link WebhookFormat#SLACK}: a Slack incoming-webhook message, a plain {@code text}
 *       fallback plus Block Kit {@code blocks}.</li>
 *   <li>{@link WebhookFormat#TEAMS}: a Teams Workflows message carrying one Adaptive Card
 *       ({@value #ADAPTIVE_CARD_VERSION}); the retired Office 365 connector card is not used.</li>
 *   <li>{@link WebhookFormat#GENERIC_JSON}: korTTY's own document, schema {@value #GENERIC_SCHEMA},
 *       with machine-readable values for any receiver.</li>
 * </ul>
 *
 * <p>A payload never carries the run's stdout, stderr or detail. The free-text summary is added
 * only when the target opted in, and then only after the run's own secrets
 * ({@link JobSchedulerSecretRedactor}) and every well-known token format
 * ({@link SecretTokenPatterns}) were masked. The job name and summary are cleaned of control and
 * bidi characters, and escaped for the receiver's markup so a job name cannot ping a channel or
 * inject a link.
 */
public final class WebhookPayloadFormatter {

    /**
     * The URL prefix of a Slack incoming webhook; registered in {@code external-apis.yaml}, so the
     * registry check finds the API this formatter speaks.
     */
    public static final String SLACK_WEBHOOK_PREFIX = "https://hooks.slack.com/services/";

    /** The JSON schema the Teams Adaptive Card declares. */
    public static final String ADAPTIVE_CARD_SCHEMA = "http://adaptivecards.io/schemas/adaptive-card.json";

    /**
     * The Adaptive Card version sent to Teams Workflows; registered in {@code external-apis.yaml}.
     * 1.4 is the newest version Teams renders on every client.
     */
    public static final String ADAPTIVE_CARD_VERSION = "1.4";

    /** The attachment content type of an Adaptive Card in a Teams Workflows message. */
    public static final String ADAPTIVE_CARD_CONTENT_TYPE = "application/vnd.microsoft.card.adaptive";

    /** The {@code schema} value of the generic JSON document; bump it on an incompatible change. */
    public static final String GENERIC_SCHEMA = "kortty.job-run/1";

    /** At most this many characters of a (masked) summary are sent. */
    static final int MAX_SUMMARY_CHARS = 1_000;

    private static final String SEPARATOR = " · ";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final BiFunction<String, Object[], String> i18n;

    /** A formatter with the application's translated status texts. */
    public WebhookPayloadFormatter() {
        this(WebhookPayloadFormatter::translate);
    }

    WebhookPayloadFormatter(BiFunction<String, Object[], String> i18n) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
    }

    /**
     * The request body for {@code target}'s format.
     *
     * @param redactor the run's own secrets to mask in the summary; may be {@code null}
     */
    public String format(WebhookTarget target, JobRunEvent event, @Nullable JobSchedulerSecretRedactor redactor) {
        Objects.requireNonNull(target, "target");
        return format(target.getFormat(), event, target.isIncludeSummary(), redactor);
    }

    /**
     * The request body for {@code format}.
     *
     * @param includeSummary whether the masked summary is added (the target's opt-in)
     * @param redactor       the run's own secrets to mask in the summary; may be {@code null}
     */
    public String format(WebhookFormat format, JobRunEvent event, boolean includeSummary,
            @Nullable JobSchedulerSecretRedactor redactor) {
        Objects.requireNonNull(event, "event");
        String summary = includeSummary ? maskedSummary(event.summary(), redactor) : null;
        JsonObject payload = switch (format != null ? format : WebhookFormat.GENERIC_JSON) {
            case SLACK -> slack(event, summary);
            case TEAMS -> teams(event, summary);
            case GENERIC_JSON -> generic(event, summary);
        };
        return GSON.toJson(payload);
    }

    /**
     * The summary with the run's secrets and every known token format masked, without control or
     * bidi characters (line breaks become spaces), at most {@link #MAX_SUMMARY_CHARS} characters;
     * {@code null} when nothing visible is left.
     */
    static @Nullable String maskedSummary(@Nullable String summary, @Nullable JobSchedulerSecretRedactor redactor) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String masked = redactor != null ? redactor.redact(summary) : summary;
        if (masked == null) {
            return null;
        }
        masked = SecretTokenPatterns.redact(masked).text();
        String clean = DisplayTextSanitizer.sanitize(masked, MAX_SUMMARY_CHARS);
        return clean.isEmpty() ? null : clean;
    }

    private JsonObject slack(JobRunEvent event, @Nullable String summary) {
        String title = JobNotificationDispatcher.title(event.jobName(), i18n);
        String body = JobNotificationDispatcher.body(event, i18n);
        JsonObject payload = new JsonObject();
        payload.addProperty("text", escapeSlack(title + ": " + body));
        JsonArray blocks = new JsonArray();
        blocks.add(slackSection("mrkdwn", "*" + escapeSlack(title) + "*\n" + escapeSlack(body)));
        if (summary != null) {
            // plain_text is never parsed for mentions or links.
            blocks.add(slackSection("plain_text", summary));
        }
        JsonObject context = new JsonObject();
        context.addProperty("type", "context");
        JsonArray elements = new JsonArray();
        JsonObject time = new JsonObject();
        time.addProperty("type", "plain_text");
        time.addProperty("text", finishedAt(event));
        elements.add(time);
        context.add("elements", elements);
        blocks.add(context);
        payload.add("blocks", blocks);
        return payload;
    }

    private static JsonObject slackSection(String textType, String text) {
        JsonObject section = new JsonObject();
        section.addProperty("type", "section");
        JsonObject content = new JsonObject();
        content.addProperty("type", textType);
        content.addProperty("text", text);
        section.add("text", content);
        return section;
    }

    private JsonObject teams(JobRunEvent event, @Nullable String summary) {
        JsonArray body = new JsonArray();
        JsonObject title = textBlock(escapeMarkdown(JobNotificationDispatcher.title(event.jobName(), i18n)));
        title.addProperty("weight", "Bolder");
        title.addProperty("size", "Medium");
        body.add(title);
        body.add(textBlock(escapeMarkdown(JobNotificationDispatcher.body(event, i18n))));
        if (summary != null) {
            body.add(textBlock(escapeMarkdown(summary)));
        }
        JsonObject time = textBlock(finishedAt(event));
        time.addProperty("isSubtle", true);
        time.addProperty("size", "Small");
        body.add(time);

        JsonObject card = new JsonObject();
        card.addProperty("$schema", ADAPTIVE_CARD_SCHEMA);
        card.addProperty("type", "AdaptiveCard");
        card.addProperty("version", ADAPTIVE_CARD_VERSION);
        card.add("body", body);

        JsonObject attachment = new JsonObject();
        attachment.addProperty("contentType", ADAPTIVE_CARD_CONTENT_TYPE);
        attachment.add("contentUrl", JsonNull.INSTANCE);
        attachment.add("content", card);
        JsonArray attachments = new JsonArray();
        attachments.add(attachment);

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "message");
        payload.add("attachments", attachments);
        return payload;
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "TextBlock");
        block.addProperty("text", text);
        block.addProperty("wrap", true);
        return block;
    }

    private static JsonObject generic(JobRunEvent event, @Nullable String summary) {
        JsonObject job = new JsonObject();
        job.addProperty("id", event.jobId());
        job.addProperty("name", DisplayTextSanitizer.sanitize(event.jobName(), JobNotificationDispatcher.MAX_JOB_NAME_CHARS));

        JsonObject payload = new JsonObject();
        payload.addProperty("schema", GENERIC_SCHEMA);
        payload.add("job", job);
        payload.addProperty("status", event.status().name().toLowerCase(Locale.ROOT));
        payload.addProperty("recovered", event.recovered());
        payload.addProperty("exitCode", event.exitCode());
        payload.addProperty("trigger", "scheduled".equals(event.trigger()) ? "scheduled" : "manual");
        payload.addProperty("finishedAt", finishedAt(event));
        if (summary != null) {
            payload.addProperty("summary", summary);
        }
        return payload;
    }

    private static String finishedAt(JobRunEvent event) {
        return DateTimeFormatter.ISO_INSTANT.format(event.finishedAt());
    }

    /** Slack's three control characters; everything else in mrkdwn is harmless without them. */
    static String escapeSlack(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Backslash-escapes what starts emphasis, code or a link in the Markdown subset an Adaptive Card
     * TextBlock renders, and a list marker at the start of the (single-line) text.
     */
    static String escapeMarkdown(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("\\*_[]`".indexOf(c) >= 0 || (i == 0 && (c == '-' || c == '+' || c == '#' || c == '>'))) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static String translate(String key, Object[] args) {
        return args.length == 0 ? de.kortty.ui.I18n.get(key) : de.kortty.ui.I18n.get(key, args);
    }
}
