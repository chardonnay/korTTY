package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Helpers for the ASCII Art tool: preview zoom, the AI "draw this subject" request, and turning a
 * model reply into a picture that is safe to show in a monospace preview.
 *
 * <p>Deliberately free of JavaFX so the clamping and sanitizing rules stay unit-testable. The
 * system and user prompts themselves live in {@link AiPromptBuilder} with every other action's
 * prompt text; only the per-retry variation instruction is built here, because it travels as the
 * request's {@code userPrompt} the same way a dialog's instruction field would.
 */
public final class AsciiArtSupport {

    /** Preview font size bounds in px. The dialog's zoom buttons step inside this range. */
    public static final double MIN_PREVIEW_FONT_SIZE = 6.0;
    public static final double MAX_PREVIEW_FONT_SIZE = 40.0;
    public static final double DEFAULT_PREVIEW_FONT_SIZE = 12.0;

    /** One zoom click. */
    private static final double PREVIEW_FONT_SIZE_STEP = 1.0;

    /** Upper bound on the subject text sent to the model, so a pasted wall of text cannot become the prompt. */
    private static final int MAX_SUBJECT_LENGTH = 200;

    /**
     * Treatments rotated through on each regeneration. The AI layer exposes no temperature or seed,
     * so a visibly different variant has to be asked for in words.
     */
    private static final List<String> VARIATION_HINTS = List.of(
        "Draw the subject from a different angle than the most obvious one.",
        "Draw a small, minimal version that uses as few characters as possible.",
        "Draw a large, richly detailed version that fills the available width.",
        "Use a bold, blocky line style built mainly from '#', '=' and '|' characters.",
        "Use a fine, sketchy line style built mainly from '/', '\\', '|', '_' and '.' characters.",
        "Place the subject in a small scene with some surrounding context.",
        "Draw a stylised, decorative version rather than a realistic one.",
        "Change the proportions: make the subject noticeably wider or taller than a standard depiction.");

    private AsciiArtSupport() {
    }

    // ---- Preview zoom ----

    /** Clamps {@code size} into the supported preview range; NaN and 0 fall back to the default. */
    public static double clampPreviewFontSize(double size) {
        if (Double.isNaN(size) || size <= 0) {
            return DEFAULT_PREVIEW_FONT_SIZE;
        }
        return Math.min(MAX_PREVIEW_FONT_SIZE, Math.max(MIN_PREVIEW_FONT_SIZE, size));
    }

    /** Moves the preview font size by {@code steps} zoom clicks (negative shrinks), staying in range. */
    public static double stepPreviewFontSize(double current, int steps) {
        return clampPreviewFontSize(clampPreviewFontSize(current) + steps * PREVIEW_FONT_SIZE_STEP);
    }

    /** The inline style for a preview area at {@code fontSize}. */
    public static String previewStyle(double fontSize) {
        return String.format(Locale.ROOT,
            "-fx-font-family: monospace; -fx-font-size: %.1fpx;", clampPreviewFontSize(fontSize));
    }

    /** The zoom level as a percentage of the default size, for the label between the zoom buttons. */
    public static int zoomPercent(double fontSize) {
        return (int) Math.round(clampPreviewFontSize(fontSize) / DEFAULT_PREVIEW_FONT_SIZE * 100.0);
    }

    // ---- AI generation ----

    /**
     * The extra instruction for regeneration {@code attempt} (0-based). Returns {@code null} for the
     * first attempt so the model is not steered away from its best default depiction.
     */
    public static String variationInstructions(int attempt) {
        if (attempt <= 0) {
            return null;
        }
        String hint = VARIATION_HINTS.get((attempt - 1) % VARIATION_HINTS.size());
        return "This is attempt " + (attempt + 1) + " for the same subject. "
            + "The picture must look clearly different from the previous attempts: " + hint;
    }

    /**
     * Asks {@code aiService} to draw {@code subject} and returns the sanitized picture, or {@code null}
     * when the subject is blank or the model returned nothing usable.
     *
     * <p>Blocking — call it from a background thread. AI skills are switched off for this request:
     * a user skill about, say, shell scripting only adds noise to a drawing task.
     */
    public static String generateAsciiArt(
            AiService aiService,
            String subject,
            String connectionDisplayName,
            String responseLanguageCode,
            int attempt,
            BiConsumer<AiRequest, AiExecutionResult> usageRecorder) throws Exception {

        if (aiService == null) {
            throw new IllegalStateException("No AI service is available for ASCII art generation.");
        }
        String trimmedSubject = subject != null ? subject.trim() : "";
        if (trimmedSubject.isEmpty()) {
            return null;
        }
        if (trimmedSubject.length() > MAX_SUBJECT_LENGTH) {
            trimmedSubject = trimmedSubject.substring(0, MAX_SUBJECT_LENGTH).trim();
        }
        AiRequest request = new AiRequest(
            AiAction.GENERATE_ASCII_ART,
            trimmedSubject,
            connectionDisplayName,
            responseLanguageCode,
            variationInstructions(attempt),
            null,
            false);
        AiExecutionResult result = aiService.execute(request);
        if (result != null && usageRecorder != null) {
            usageRecorder.accept(request, result);
        }
        return extractAsciiArt(result != null ? result.content() : null);
    }

    /**
     * Pulls the picture out of a model reply: drops reasoning blocks, prefers the first fenced code
     * block, expands tabs (they break monospace alignment), removes control characters, and trims
     * blank edge lines. Returns {@code null} when nothing usable is left.
     */
    public static String extractAsciiArt(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) {
            return null;
        }
        String text = stripThinkBlocks(rawReply).replace("\r\n", "\n").replace('\r', '\n');
        String fenced = firstFencedBlock(text);
        String body = fenced != null ? fenced : text;
        body = trimBlankEdgeLines(stripControlCharacters(body.replace("\t", "    ")));
        return body.isBlank() ? null : body;
    }

    /** Removes {@code <think>…</think>} reasoning some local models inline into the content. */
    private static String stripThinkBlocks(String text) {
        return text.replaceAll("(?is)<think>.*?</think>", "");
    }

    /**
     * The body of the first fenced code block, or {@code null} when the reply is not fenced. Anything
     * on the opening fence line after the backticks is a language tag and is dropped with it.
     */
    private static String firstFencedBlock(String text) {
        int open = text.indexOf("```");
        if (open < 0) {
            return null;
        }
        int bodyStart = text.indexOf('\n', open);
        if (bodyStart < 0) {
            return null;
        }
        int close = text.indexOf("```", bodyStart + 1);
        return close >= 0 ? text.substring(bodyStart + 1, close) : text.substring(bodyStart + 1);
    }

    /** Keeps newlines and printable characters; drops the control characters that would corrupt the preview. */
    private static String stripControlCharacters(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c >= 0x20 && c != 0x7F)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Strips trailing spaces per line and removes leading and trailing blank lines. */
    private static String trimBlankEdgeLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, lines.get(i).stripTrailing());
        }
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }
}
