package de.kortty.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes AI responses before they are shown in the UI.
 */
public final class AiResponseSanitizer {

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think\\b[^>]*>.*?</think\\s*>");
    private static final Pattern DANGLING_THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think\\b[^>]*>.*$");
    private static final Pattern ORPHAN_CLOSING_THINK_PREFIX_PATTERN = Pattern.compile("(?is)^.*?</think\\s*>\\s*");
    private static final Pattern EXCESSIVE_BLANK_LINES_PATTERN = Pattern.compile("\\n{3,}");

    // Extraction must not corrupt answers that merely mention think markers, so unlike the
    // display patterns above these are anchored to the start of the reply — the only place a
    // reasoning model emits its chain-of-thought — and accept only the bare <think> token
    // reasoning models actually produce (an attribute wildcard would swallow prose that opens
    // with a literal "<think ..." fragment).
    private static final Pattern LEADING_THINK_BLOCK_PATTERN =
        Pattern.compile("(?is)^\\s*<think\\s*>(.*?)</think\\s*>\\s*");
    private static final Pattern LEADING_DANGLING_THINK_PATTERN =
        Pattern.compile("(?is)^\\s*<think\\s*>(.*)$");
    private static final Pattern LEADING_ORPHAN_CLOSER_PATTERN =
        Pattern.compile("(?is)^(.*?)</think\\s*>\\s*");

    // gpt-oss and other harmony-format models emit their turn as channel segments —
    // <|channel|>analysis<|message|>…<|end|><|start|>assistant<|channel|>final<|message|>…<|return|>.
    // With the sidecar's --reasoning-format none the raw markers stay inline in the content, so the
    // analysis/commentary channels are reasoning and only the final channel is the answer.
    private static final Pattern HARMONY_CHANNEL_HEADER =
        Pattern.compile("(?is)<\\|channel\\|>\\s*([a-z_]+)\\s*(?:<\\|constrain\\|>[^<]*)?<\\|message\\|>");
    private static final Pattern HARMONY_CONTROL_TOKEN =
        Pattern.compile("(?is)<\\|(?:end|return|start|channel|call)\\|>");
    private static final Pattern HARMONY_ANY_TOKEN =
        Pattern.compile("(?is)<\\|[a-z_/]+\\|>");

    private AiResponseSanitizer() {
    }

    public static String sanitizeForDisplay(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String stripped;
        if (looksLikeHarmony(content)) {
            stripped = extractHarmonyReasoning(content).content();
        } else {
            stripped = THINK_BLOCK_PATTERN.matcher(content).replaceAll("");
            stripped = DANGLING_THINK_BLOCK_PATTERN.matcher(stripped).replaceAll("");
            stripped = ORPHAN_CLOSING_THINK_PREFIX_PATTERN.matcher(stripped).replaceFirst("");
        }
        String normalized = stripped.replace("\r\n", "\n").replace('\r', '\n').trim();
        return EXCESSIVE_BLANK_LINES_PATTERN.matcher(normalized).replaceAll("\n\n");
    }

    /** A response content split into the answer text and the inline chain-of-thought, if any. */
    public record InlineReasoning(String content, String reasoning) {

        /** True when the reply carried reasoning markers but no answer text outside them. */
        public boolean reasoningOnly() {
            return reasoning != null && content.isBlank();
        }
    }

    /**
     * Separates the leading inline {@code <think>} reasoning of a reply from the answer text:
     * complete blocks at the start, a dangling opening tag from a generation truncated inside its
     * reasoning, and — only when no opener was seen at all — an orphan closing tag from templates
     * that pre-consume the opener (DeepSeek-R1 style). Markers appearing later in the reply are
     * deliberately left untouched, because there they are usually literal answer text (a command
     * or JSON that mentions the tags); the orphan pass is additionally skipped for replies that
     * open like a structured payload. Returns the reasoning as {@code null} when no leading
     * reasoning markers were found.
     */
    public static InlineReasoning extractInlineReasoning(String content) {
        if (content == null || content.isEmpty()) {
            return new InlineReasoning("", null);
        }
        if (looksLikeHarmony(content)) {
            return extractHarmonyReasoning(content);
        }
        StringBuilder thoughts = new StringBuilder();
        int index = 0;
        boolean sawMarkers = false;
        Matcher block = LEADING_THINK_BLOCK_PATTERN.matcher(content);
        while (true) {
            block.region(index, content.length());
            if (!block.find()) {
                break;
            }
            appendThought(thoughts, block.group(1));
            index = block.end();
            sawMarkers = true;
        }
        Matcher dangling = LEADING_DANGLING_THINK_PATTERN.matcher(content);
        dangling.region(index, content.length());
        if (dangling.find()) {
            appendThought(thoughts, dangling.group(1));
            index = content.length();
            sawMarkers = true;
        }
        if (!sawMarkers && !startsLikeStructuredPayload(content)) {
            Matcher orphan = LEADING_ORPHAN_CLOSER_PATTERN.matcher(content);
            // A prefix that itself contains an opener is a literal mention, not leaked reasoning.
            if (orphan.find() && !orphan.group(1).toLowerCase(Locale.ROOT).contains("<think")) {
                appendThought(thoughts, orphan.group(1));
                index = orphan.end();
                sawMarkers = true;
            }
        }
        if (!sawMarkers) {
            return new InlineReasoning(content.trim(), null);
        }
        return new InlineReasoning(content.substring(index).trim(), thoughts.toString().trim());
    }

    /** Recognizes a gpt-oss harmony turn by its two defining markers, absent from normal prose. */
    private static boolean looksLikeHarmony(String content) {
        return content.contains("<|channel|>") && content.contains("<|message|>");
    }

    /**
     * Splits a harmony turn into the {@code final} channel (the answer) and every other channel
     * (analysis/commentary reasoning), stripping the control tokens. A turn truncated before its
     * final channel yields empty content plus the reasoning, so callers treat it as reasoning-only
     * and retry. A malformed turn falls back to stripping the tokens rather than dropping content.
     */
    private static InlineReasoning extractHarmonyReasoning(String content) {
        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Matcher header = HARMONY_CHANNEL_HEADER.matcher(content);
        boolean matchedAny = false;
        while (header.find()) {
            matchedAny = true;
            String channel = header.group(1).toLowerCase(Locale.ROOT);
            int bodyStart = header.end();
            Matcher control = HARMONY_CONTROL_TOKEN.matcher(content).region(bodyStart, content.length());
            int bodyEnd = control.find() ? control.start() : content.length();
            String body = content.substring(bodyStart, bodyEnd).trim();
            if ("final".equals(channel)) {
                if (!body.isEmpty()) {
                    if (answer.length() > 0) {
                        answer.append("\n\n");
                    }
                    answer.append(body);
                }
            } else {
                appendThought(reasoning, body);
            }
        }
        if (!matchedAny) {
            // The markers are present but not in the expected shape; never lose the content.
            return new InlineReasoning(HARMONY_ANY_TOKEN.matcher(content).replaceAll("").trim(), null);
        }
        String thoughts = reasoning.toString().trim();
        return new InlineReasoning(answer.toString().trim(), thoughts.isEmpty() ? null : thoughts);
    }

    /** Structured payloads (JSON, fenced code) can legitimately contain a bare closing marker. */
    private static boolean startsLikeStructuredPayload(String content) {
        String trimmed = content.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("```");
    }

    private static void appendThought(StringBuilder thoughts, String thought) {
        String trimmed = thought.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (thoughts.length() > 0) {
            thoughts.append("\n\n");
        }
        thoughts.append(trimmed);
    }
}
