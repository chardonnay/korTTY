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

    private AiResponseSanitizer() {
    }

    public static String sanitizeForDisplay(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String withoutThink = THINK_BLOCK_PATTERN.matcher(content).replaceAll("");
        withoutThink = DANGLING_THINK_BLOCK_PATTERN.matcher(withoutThink).replaceAll("");
        withoutThink = ORPHAN_CLOSING_THINK_PREFIX_PATTERN.matcher(withoutThink).replaceFirst("");
        String normalized = withoutThink.replace("\r\n", "\n").replace('\r', '\n').trim();
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
