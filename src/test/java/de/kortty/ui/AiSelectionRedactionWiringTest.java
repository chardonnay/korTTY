package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;

/**
 * Regression guard for masking terminal selections before they reach an AI profile. The masking
 * itself is unit-tested in {@code AiOutboundRedactionTest}; what can silently break is the wiring:
 * a send path that hands the raw selection or attachment to the request again.
 *
 * <p>The paths cannot run without a live {@code App}, JavaFX stage and terminal, so, like
 * {@link MainWindowAgentForeignSessionGateTest}, this test reads the source and pins the
 * structure: the selection is masked before the preview, the attachment before the request, the
 * Ask Agent tab gets only masked text, and the AI tab masks every follow-up again for the profile
 * it is switched to.</p>
 */
class AiSelectionRedactionWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path RESULT_TAB = Path.of("src/main/java/de/kortty/ui/AiResultTab.java");

    @Test
    void theSelectionIsMaskedBeforeThePreviewAndEverySend() throws IOException {
        String body = onlyBody(MAIN_WINDOW, "private void handleAiSelectionAction(");

        int mask = body.indexOf("AiOutboundRedaction.redactFor(effectiveProfile, selectedText, knownSecrets)");
        assertThat(mask).isAtLeast(0);
        assertThat(body.indexOf("confirmAiRequest(")).isGreaterThan(mask);
        assertThat(body).contains("confirmAiRequest(action, effectiveProfile, outboundText,");
        // Without a preview the masked text is sent as well, never the raw selection.
        assertThat(body).doesNotContain("new AiRequestDraft(selectedText");
        assertThat(count(body, "new AiRequestDraft(outboundText,")).isEqualTo(2);
    }

    @Test
    void theAttachmentIsMaskedBeforeTheRequestIsBuilt() throws IOException {
        String body = onlyBody(MAIN_WINDOW, "private void startAiSelectionRequest(");

        int mask = body.indexOf("AiOutboundRedaction.redactAttachmentFor(effectiveProfile, draft.fileAttachment(), knownSecrets)");
        assertThat(mask).isAtLeast(0);
        // The draft's attachment is read nowhere else, so the unmasked one cannot reach the request.
        assertThat(count(body, "draft.fileAttachment()")).isEqualTo(1);
        assertThat(body.indexOf("new AiRequest(")).isGreaterThan(mask);
        assertThat(body).contains("resultTab.setOutboundSecrets(knownSecrets);");
    }

    @Test
    void askAgentOpensItsTabWithMaskedTextOnly() throws IOException {
        List<String> overloads = bodies(MAIN_WINDOW, "private void openDirectAiAskTab(");
        assertThat(overloads).hasSize(2);
        String fromTerminal = overloads.stream().filter(b -> b.contains("resolveAiAttachmentCandidate(")).findFirst().orElseThrow();
        String opensTab = overloads.stream().filter(b -> b.contains("new AiResultTab(")).findFirst().orElseThrow();

        assertThat(fromTerminal).contains("AiOutboundRedaction.redactFor(profile, selectedText, knownSecrets)");
        assertThat(fromTerminal).contains("AiOutboundRedaction.redactAttachmentFor(profile, outcome.attachment(), knownSecrets)");
        // Both calls into the tab-opening overload pass the masked selection and attachment.
        assertThat(count(fromTerminal, "openDirectAiAskTab(")).isEqualTo(2);
        assertThat(count(fromTerminal, "openDirectAiAskTab(profile, prompt, maskedSelection.text(),")).isEqualTo(2);
        assertThat(fromTerminal).doesNotContain("connection, outcome.attachment()");
        assertThat(opensTab).contains("resultTab.setOutboundSecrets(knownSecrets);");
    }

    @Test
    void everyFollowUpOfTheAiTabIsMaskedForTheProfileItGoesTo() throws IOException {
        String followUp = onlyBody(RESULT_TAB, "private void sendFollowUp(");
        assertThat(followUp).contains("outboundContextFor(selectedProfile, plainTranscript.toString())");
        assertThat(followUp).contains("outbound.selectedText(),");
        assertThat(followUp).contains("outbound.conversation())");
        assertThat(followUp).contains(".withFileAttachment(outbound.attachment())");

        assertThat(onlyBody(RESULT_TAB, "private String generateSuggestedTitle(")).contains("AiOutboundRedaction.chatContextFor(");
        assertThat(onlyBody(RESULT_TAB, "private void generateAttachmentFlowchart(")).contains("AiOutboundRedaction.redactAttachmentFor(");

        // No request of the tab is built from the stored selection or attachment directly.
        String code = normalize(stripCommentsAndStrings(Files.readString(RESULT_TAB, StandardCharsets.UTF_8)));
        assertThat(Pattern.compile("new AiRequest\\( ?AiAction\\.\\w+, ?selectedText ?,").matcher(code).find()).isFalse();
        assertThat(code).doesNotContain(".withFileAttachment(fileAttachment)");
    }

    private static String onlyBody(Path file, String declarationPrefix) throws IOException {
        List<String> bodies = bodies(file, declarationPrefix);
        assertThat(bodies).hasSize(1);
        return bodies.get(0);
    }

    /** Method bodies with comments and string literals removed and whitespace collapsed. */
    private static List<String> bodies(Path file, String declarationPrefix) throws IOException {
        String source = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
        List<String> bodies = new ArrayList<>();
        Matcher matcher = Pattern.compile(Pattern.quote(declarationPrefix)).matcher(source);
        while (matcher.find()) {
            int open = bodyStart(source, matcher.end());
            if (open < 0) {
                continue;
            }
            int close = matchingBrace(source, open);
            assertThat(close).isGreaterThan(open);
            bodies.add(normalize(source.substring(open + 1, close)));
        }
        return bodies;
    }

    /** Index of the '{' that opens the body after the parameter list starting at {@code from}. */
    private static int bodyStart(String source, int from) {
        int depth = 1;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '{' && depth == 0) {
                return i;
            } else if (c == ';' && depth == 0) {
                return -1;
            }
        }
        return -1;
    }

    /** Works on stripped source, so no brace inside a string or comment is counted. */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String code) {
        return code.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    /** The source with comments removed and string and char literals emptied. */
    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? source.length() : end + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i = skipLiteral(source, i, c);
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int skipLiteral(String source, int start, char quote) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return i;
    }
}
