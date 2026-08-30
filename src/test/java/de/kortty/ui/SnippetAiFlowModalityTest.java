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

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * korTTY is a terminal. Work in the Full-code-analysis flow runs on a background thread and can
 * take minutes, so the user has to be able to keep typing in their sessions while it does — and
 * while they read the result.
 *
 * <p>The one thing that breaks this is invisible: a JavaFX {@code Dialog} with an owner defaults to
 * {@code APPLICATION_MODAL}. Omitting {@code initModality} compiles, runs, looks right in a
 * screenshot, and freezes every terminal tab. That is precisely how the change-preview window came
 * to block the whole application. A missing line cannot be caught by reading the diff of the file
 * that is missing it, so it is asserted here instead.</p>
 */
class SnippetAiFlowModalityTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    /** Every window the staged apply flow puts on screen, plus the editor that hosts it. */
    private static final List<String> FLOW_WINDOWS = List.of(
        "SnippetEditDialog.java",
        "SnippetCodeAnalysisDialog.java",
        "SnippetAiDiffDialog.java",
        "SnippetAiApplyProgressWindow.java");

    private static final Pattern NON_MODAL = Pattern.compile(
        "initModality\\(\\s*(?:javafx\\.stage\\.)?Modality\\.NONE\\s*\\)");

    /** An {@code Alert} construction, so the following lines can be checked for a modality choice. */
    private static final Pattern ALERT = Pattern.compile("new Alert\\(");


    @Test
    void everyWindowOfTheApplyFlowIsExplicitlyNonModal() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String name : FLOW_WINDOWS) {
            String source = code(UI_ROOT.resolve(name));
            if (!NON_MODAL.matcher(source).find()) {
                offenders.add(name);
            }
        }
        assertWithMessage(
            "These windows never call initModality(Modality.NONE). JavaFX then makes them "
                + "APPLICATION_MODAL, which freezes every terminal session while the AI works")
            .that(offenders).isEmpty();
    }

    /**
     * An alert raised while the AI flow is on screen must say how far it blocks. The abort-recovery
     * prompt in particular waits for a decision that may never come quickly.
     */
    @Test
    void alertsInTheApplyFlowChooseTheirModality() throws IOException {
        String source = code(UI_ROOT.resolve("SnippetEditDialog.java"));

        List<Integer> unscoped = new ArrayList<>();
        Matcher matcher = ALERT.matcher(source);
        while (matcher.find()) {
            // Everything between constructing an alert and showing it is its setup; that is where a
            // modality choice has to appear, however many buttons are configured in between.
            int shown = source.indexOf(".showAndWait()", matcher.start());
            String setup = source.substring(
                matcher.start(), shown < 0 ? source.length() : shown);
            boolean partOfTheAiFlow = setup.contains("aiFlowAlertOwner");
            if (partOfTheAiFlow && !setup.contains("initModality")) {
                unscoped.add(lineOf(source, matcher.start()));
            }
        }
        assertWithMessage(
            "AI-flow alerts at these lines never set a modality, so they inherit "
                + "APPLICATION_MODAL and block the terminals while they wait for an answer")
            .that(unscoped).isEmpty();
    }

    /** The change preview must not store an owner that would put the block on the main window. */
    @Test
    void theApplyFlowNeverOwnsItsAlertsToTheMainWindow() throws IOException {
        String source = code(UI_ROOT.resolve("SnippetEditDialog.java"));
        assertWithMessage(
            "aiFlowAlertOwner is what keeps an AI-flow alert from blocking the terminals; "
                + "the flow's alerts are expected to route through it")
            .that(source).contains("private Window aiFlowAlertOwner(");
    }

    /**
     * The file's code with comments blanked out, line structure intact.
     *
     * <p>Without this the guard passes on a commented-out {@code initModality} — which is exactly
     * the shape a regression takes when someone disables the call "just to try something".</p>
     */
    private static String code(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(source.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (!inChar && c == '"' && !escaped(source, i)) {
                inString = !inString;
            } else if (!inString && c == '\'' && !escaped(source, i)) {
                inChar = !inChar;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean escaped(String source, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && source.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }
}
