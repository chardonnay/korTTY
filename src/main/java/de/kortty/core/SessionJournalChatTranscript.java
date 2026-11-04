package de.kortty.core;

import de.kortty.model.SavedAgentStep;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.SessionJournalChatMessage;
import de.kortty.model.SessionJournalChatSnapshot;
import de.kortty.ui.I18n;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The KI-Chat copy inside a journal's AGENT card: a collapsed {@code <details>} with the messages and,
 * under each assistant message, its agent steps. Everything the chat says is escaped. Markdown goes
 * through commonmark with raw HTML escaped and link destinations sanitised, so chat text cannot run
 * script in the journal page. The markup carries no class {@code summary}; the card's own collapse
 * script targets that class and must not pick the transcript up.
 */
final class SessionJournalChatTranscript {

    /** Chat text embedded per journal entry; past this the copy says that it is shortened. */
    static final int MAX_EMBEDDED_CHARS = 200_000;

    private static final DateTimeFormatter TIME_HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Parser PARSER = Parser.builder()
        .extensions(List.of(StrikethroughExtension.create())).build();
    private static final HtmlRenderer MARKDOWN = HtmlRenderer.builder()
        .escapeHtml(true)
        .sanitizeUrls(true)
        .extensions(List.of(StrikethroughExtension.create()))
        .attributeProviderFactory(context -> (node, tagName, attributes) -> {
            if (node instanceof Link) {
                // The same class as the page's other outside links, so the app opens them in the browser.
                attributes.put("class", "ext");
                attributes.put("rel", "noreferrer");
            }
        })
        .build();
    /** A Markdown image would load over the network from a journal page; its alt text stands in. */
    private static final Pattern IMAGE = Pattern.compile("<img [^>]*alt=\"([^\"]*)\"[^>]*>");

    private SessionJournalChatTranscript() {
    }

    /** The transcript block for an AGENT card; empty when the snapshot holds no messages. */
    static String render(SessionJournalChatSnapshot snapshot, ZoneId zone) {
        if (snapshot == null || snapshot.getMessages().isEmpty()) {
            return "";
        }
        List<SessionJournalChatMessage> messages = snapshot.getMessages();
        StringBuilder body = new StringBuilder();
        int used = 0;
        boolean shortened = false;
        for (SessionJournalChatMessage message : messages) {
            String content = message.getContent() != null ? message.getContent() : "";
            int stepChars = stepChars(message.getSteps());
            int room = MAX_EMBEDDED_CHARS - used;
            if (content.length() + stepChars > room) {
                // The rest of the chat is not embedded; the message that crosses the cap keeps what fits.
                shortened = true;
                if (room > 0) {
                    body.append(messageHtml(message, content.substring(0, Math.min(content.length(), room)),
                        List.of(), zone));
                }
                break;
            }
            used += content.length() + stepChars;
            body.append(messageHtml(message, content, message.getSteps(), zone));
        }
        String asOf = snapshot.getCapturedAt() != null
            ? snapshot.getCapturedAt().atZoneSameInstant(zone).format(DATE_TIME)
            : "?";

        StringBuilder out = new StringBuilder();
        out.append("<details class=\"chat-transcript\">\n<summary>")
            .append(SessionJournalHtmlRenderer.escapeHtml(I18n.get("journal.html.chat.summary", messages.size(), asOf)));
        if (snapshot.isFrozen()) {
            out.append(' ').append(SessionJournalHtmlRenderer.escapeHtml(
                I18n.get("journal.html.chat.final")));
        }
        out.append("</summary>\n<div class=\"chat-body\">\n").append(body).append("</div>\n");
        if (shortened) {
            out.append("<p class=\"chat-truncated\">").append(SessionJournalHtmlRenderer.escapeHtml(
                I18n.get("journal.html.chat.truncated"))).append("</p>\n");
        }
        out.append("</details>\n");
        return out.toString();
    }

    /** Markdown to HTML with raw HTML escaped and unsafe link destinations removed. */
    static String markdown(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return IMAGE.matcher(MARKDOWN.render(PARSER.parse(content))).replaceAll("<span class=\"chat-img\">$1</span>");
    }

    private static String messageHtml(SessionJournalChatMessage message, String content,
                                      List<SavedAgentStep> steps, ZoneId zone) {
        boolean user = SavedAiChatMessage.ROLE_USER.equals(message.getRole());
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"chat-msg ").append(user ? "user" : "assistant").append("\">\n");
        out.append("<div class=\"chat-msg-head\"><span class=\"chat-role\">")
            .append(SessionJournalHtmlRenderer.escapeHtml(roleLabel(message, user))).append("</span>");
        if (message.getCreatedAt() != null) {
            out.append(" · <time>")
                .append(SessionJournalHtmlRenderer.escapeHtml(message.getCreatedAt().atZoneSameInstant(zone).format(TIME_HM)))
                .append("</time>");
        }
        out.append("</div>\n");
        String html = markdown(content);
        if (!html.isEmpty()) {
            out.append("<div class=\"chat-text\">").append(html).append("</div>\n");
        }
        if (!steps.isEmpty()) {
            out.append(stepsHtml(steps));
        }
        out.append("</div>\n");
        return out.toString();
    }

    /** The chat's own label without its trailing colon, which the chat headings carry and the page does not need. */
    private static String roleLabel(SessionJournalChatMessage message, boolean user) {
        if (user) {
            return I18n.get("ai.result.user").replaceAll(":\\s*$", "");
        }
        String profile = message.getAiProfileName();
        return profile != null && !profile.isBlank() ? profile.strip() : I18n.get("ai.result.assistant");
    }

    private static String stepsHtml(List<SavedAgentStep> steps) {
        StringBuilder out = new StringBuilder();
        out.append("<details class=\"chat-steps\">\n<summary>")
            .append(SessionJournalHtmlRenderer.escapeHtml(I18n.get("ai.result.agentRun.steps", steps.size())))
            .append("</summary>\n<ol class=\"chat-step-list\">\n");
        for (SavedAgentStep step : steps) {
            out.append("<li class=\"chat-step\"><div class=\"chat-step-head\">");
            if (notBlank(step.getStatus())) {
                out.append("<span class=\"chat-step-status\">")
                    .append(SessionJournalHtmlRenderer.escapeHtml(step.getStatus())).append("</span> ");
            }
            out.append("<span class=\"chat-step-title\">")
                .append(SessionJournalHtmlRenderer.escapeHtml(nullSafe(step.getTitle()))).append("</span>");
            if (notBlank(step.getSummary())) {
                out.append(" <span class=\"chat-step-summary\">")
                    .append(SessionJournalHtmlRenderer.escapeHtml(step.getSummary().strip())).append("</span>");
            }
            if (step.getExitCode() != null) {
                out.append(" <span class=\"chat-step-exit\">").append(SessionJournalHtmlRenderer.escapeHtml(
                    I18n.get("ai.result.agentRun.exit", step.getExitCode()))).append("</span>");
            }
            out.append("</div>\n");
            if (notBlank(step.getCommand()) || notBlank(step.getOutput())) {
                StringBuilder block = new StringBuilder();
                if (notBlank(step.getCommand())) {
                    block.append("$ ").append(step.getCommand());
                }
                if (notBlank(step.getOutput())) {
                    if (block.length() > 0) {
                        block.append('\n');
                    }
                    block.append(step.getOutput());
                }
                out.append("<pre class=\"chat-step-output\">")
                    .append(SessionJournalHtmlRenderer.escapeHtml(block.toString())).append("</pre>\n");
            }
            if (step.isTruncated()) {
                out.append("<p class=\"chat-truncated\">").append(SessionJournalHtmlRenderer.escapeHtml(
                    I18n.get("ai.result.agentRun.truncated"))).append("</p>\n");
            }
            out.append("</li>\n");
        }
        out.append("</ol>\n</details>\n");
        return out.toString();
    }

    private static int stepChars(List<SavedAgentStep> steps) {
        int chars = 0;
        for (SavedAgentStep step : steps) {
            chars += length(step.getTitle()) + length(step.getSummary()) + length(step.getCommand())
                + length(step.getOutput());
        }
        return chars;
    }

    private static int length(String value) {
        return value != null ? value.length() : 0;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
