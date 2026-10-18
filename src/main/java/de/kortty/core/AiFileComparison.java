package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the AI gets when asked about two compared files ({@link AiAction#COMPARE_FILES}) or asked to
 * write a third one from them ({@link AiAction#WRITE_COMPARED_FILE}): both files in full with line
 * numbers, so an answer can point at lines, then their unified diff, so it does not have to find
 * the changes itself. The whole block is the request's selected text, which the prompt fences as
 * data to analyze, never as instructions.
 */
public final class AiFileComparison {

    /**
     * The new file out of a {@link AiAction#WRITE_COMPARED_FILE} reply: what the AI says it changed
     * (Markdown, may be empty) and the file itself.
     */
    public record NewFile(String explanation, String content) {
    }

    private AiFileComparison() {
    }

    /**
     * The material for the request.
     *
     * @param leftLabel  where the left file is ("This computer: /etc/app.conf")
     * @param rightLabel where the right file is
     * @param unifiedDiff {@link TextDiff} output from left to right; empty when they are the same
     */
    public static String material(String leftLabel, String leftText, String rightLabel, String rightText,
                                  String unifiedDiff) {
        StringBuilder text = new StringBuilder();
        text.append("LEFT FILE: ").append(leftLabel).append('\n');
        appendNumbered(text, leftText);
        text.append("\nRIGHT FILE: ").append(rightLabel).append('\n');
        appendNumbered(text, rightText);
        text.append("\nUNIFIED DIFF (left to right):\n");
        text.append(unifiedDiff == null || unifiedDiff.isEmpty() ? "(the files are the same)\n" : unifiedDiff);
        return text.toString();
    }

    /** The request: the user's question about the material, answered in {@code languageCode}. */
    public static AiRequest request(String material, String question, String connectionName, String languageCode) {
        return new AiRequest(AiAction.COMPARE_FILES, material, connectionName, languageCode, question);
    }

    /** The request for a new file written from the material as {@code instruction} describes it. */
    public static AiRequest newFileRequest(String material, String instruction, String connectionName,
                                           String languageCode) {
        return new AiRequest(AiAction.WRITE_COMPARED_FILE, material, connectionName, languageCode, instruction);
    }

    /**
     * Picks the new file out of a reply: the longest fenced code block, the text around it being the
     * explanation. Empty when the reply holds no complete block: one without its closing fence was
     * most likely cut off and must not be saved as the file.
     */
    public static Optional<NewFile> newFile(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        String answer = AiResponseSanitizer.extractInlineReasoning(reply).content();
        List<String> lines = TextDiff.lines(answer.replace("\r\n", "\n"));
        int bestStart = -1;
        int bestEnd = -1;
        int bestLength = -1;
        for (int i = 0; i < lines.size(); i++) {
            String fence = openingFence(lines.get(i));
            if (fence == null) {
                continue;
            }
            int close = -1;
            for (int j = i + 1; j < lines.size(); j++) {
                if (closes(lines.get(j), fence)) {
                    close = j;
                    break;
                }
            }
            if (close < 0) {
                // A block that never closes was cut off; a smaller complete one before it is
                // not the file either.
                return Optional.empty();
            }
            int length = 0;
            for (int j = i + 1; j < close; j++) {
                length += lines.get(j).length() + 1;
            }
            if (length > bestLength) {
                bestStart = i;
                bestEnd = close;
                bestLength = length;
            }
            i = close;
        }
        if (bestStart < 0) {
            return Optional.empty();
        }
        StringBuilder content = new StringBuilder();
        for (int j = bestStart + 1; j < bestEnd; j++) {
            content.append(lines.get(j)).append('\n');
        }
        if (content.toString().isBlank()) {
            return Optional.empty();
        }
        List<String> rest = new ArrayList<>(lines.subList(0, bestStart));
        rest.addAll(lines.subList(bestEnd + 1, lines.size()));
        return Optional.of(new NewFile(String.join("\n", rest).strip(), content.toString()));
    }

    /**
     * {@code content} with the line breaks of {@code model}, the file it replaces: CRLF when the
     * model uses them, and a final line break exactly when the model ends with one.
     */
    public static String withLineEndingsOf(String content, String model) {
        String text = content == null ? "" : content.replace("\r\n", "\n");
        if (model == null || model.isEmpty()) {
            return text;
        }
        if (text.endsWith("\n") && !model.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        } else if (!text.isEmpty() && !text.endsWith("\n") && model.endsWith("\n")) {
            text = text + "\n";
        }
        return model.contains("\r\n") ? text.replace("\n", "\r\n") : text;
    }

    /** The fence a line opens ("```" or "~~~" and longer, up to three spaces in), or null. */
    private static String openingFence(String line) {
        String trimmed = stripIndent(line);
        if (trimmed == null || trimmed.length() < 3) {
            return null;
        }
        char mark = trimmed.charAt(0);
        if (mark != '`' && mark != '~') {
            return null;
        }
        int length = 0;
        while (length < trimmed.length() && trimmed.charAt(length) == mark) {
            length++;
        }
        if (length < 3 || (mark == '`' && trimmed.indexOf('`', length) >= 0)) {
            return null;
        }
        return trimmed.substring(0, length);
    }

    private static boolean closes(String line, String fence) {
        String trimmed = stripIndent(line);
        if (trimmed == null) {
            return false;
        }
        trimmed = trimmed.strip();
        if (trimmed.length() < fence.length()) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != fence.charAt(0)) {
                return false;
            }
        }
        return true;
    }

    /** The line without up to three leading spaces; null when it is indented further (code, not a fence). */
    private static String stripIndent(String line) {
        int spaces = 0;
        while (spaces < line.length() && spaces < 4 && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces > 3 ? null : line.substring(spaces);
    }

    private static void appendNumbered(StringBuilder text, String content) {
        int number = 1;
        for (String line : TextDiff.lines(content)) {
            text.append(String.format("%5d| ", number++)).append(line).append('\n');
        }
        if (number == 1) {
            text.append("(empty file)\n");
        }
    }
}
