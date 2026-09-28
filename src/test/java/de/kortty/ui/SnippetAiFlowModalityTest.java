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

    /**
     * The windows around the flow: the editor that hosts the integrated analysis, the workspace that
     * embeds editors, the stand-alone diff window (no longer used by the editor, kept for other
     * hosts) and the result windows the editor opens beside itself.
     */
    private static final List<String> FLOW_WINDOWS = List.of(
        "SnippetEditDialog.java",
        "SnippetWorkspaceDialog.java",
        "SnippetAiDiffDialog.java",
        "SnippetSecurityReportDialog.java",
        "SnippetDescriptionDialog.java",
        "AlternativeSnippetSolutionsDialog.java",
        "SnippetAiReviewDialog.java",
        "SnippetEditorProfileDialog.java");

    /** The windows the editor opens beside itself; each must be shown without a nested event loop. */
    private static final List<String> EDITOR_CHILD_WINDOWS = List.of(
        "SnippetSecurityReportDialog",
        "SnippetDescriptionDialog",
        "AlternativeSnippetSolutionsDialog",
        "SnippetAiReviewDialog",
        "SnippetEditorProfileDialog");

    /**
     * The integrated Full-code-analysis flow: the analysis side panel, the progress and diff panes
     * inside the editor, and the controller that drives them.
     */
    private static final List<String> EMBEDDABLE_PANES = List.of(
        "SnippetAnalysisPanel.java",
        "SnippetAiDiffPane.java",
        "SnippetAiApplyProgressPane.java",
        "SnippetAnalysisController.java");

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
     * The integrated flow lives inside the editor. A pane (or the controller) that opened its own
     * window, raised an alert or blocked in {@code showAndWait} would bring back the satellite
     * windows and the modality problem above — inline banners and the in-editor review exist so
     * that it never has to.
     */
    @Test
    void integratedAnalysisFlowOpensNoWindows() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String name : EMBEDDABLE_PANES) {
            String source = code(UI_ROOT.resolve(name));
            for (String forbidden : List.of("new Stage(", "new Alert(", "showAndWait(", "initOwner(")) {
                if (source.contains(forbidden)) {
                    offenders.add(name + " uses " + forbidden);
                }
            }
        }
        assertWithMessage("The integrated analysis flow opens a window, an alert or blocks")
            .that(offenders).isEmpty();
    }

    /**
     * Every AI change the editor proposes (improve, migrate, assistant, security fix, AI format and
     * the line-width format preview) is reviewed in the editor area. A blocking diff window nested
     * event loops across editor tabs: they return in LIFO order, so accepting tab A's change applied
     * nothing until tab B's window closed.
     */
    @Test
    void theEditorReviewsEveryAiChangeInItsOwnArea() throws IOException {
        String source = code(UI_ROOT.resolve("SnippetEditDialog.java"));
        assertWithMessage("SnippetEditDialog opens the blocking diff window again")
            .that(source).doesNotContain("new SnippetAiDiffDialog(");
        // The helper itself plus improve, migrate, assistant, security fix, AI format, line width.
        assertWithMessage("the ad-hoc AI flows review through showAiChangeReview")
            .that(countOf(source, "showAiChangeReview(")).isAtLeast(7);
        assertWithMessage("Accept of an ad-hoc change must check the content it was computed from")
            .that(source).containsMatch(
                "if \\(decision == SnippetAiDiffPane\\.Decision\\.ACCEPT\\)\\s*\\{\\s*if \\(!contentUnchangedSince\\(");
    }

    /**
     * The result windows beside the editor are shown with {@code show()} and answer through a
     * callback — never {@code showAndWait()}, whose nested event loop is what tangled the tabs.
     */
    @Test
    void theEditorsResultWindowsNeverBlockInShowAndWait() throws IOException {
        String source = code(UI_ROOT.resolve("SnippetEditDialog.java"));
        List<String> offenders = new ArrayList<>();
        for (String window : EDITOR_CHILD_WINDOWS) {
            Matcher matcher = Pattern.compile("new " + window + "\\(").matcher(source);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                // showChildWindow(new X(...)) — wrapped in the same statement.
                int wrapper = source.lastIndexOf("showChildWindow(", matcher.start());
                if (wrapper >= 0 && wrapper > source.lastIndexOf(';', matcher.start())) {
                    continue;
                }
                int blocking = source.indexOf("showAndWait(", matcher.start());
                int nonBlocking = source.indexOf("showChildWindow(", matcher.start());
                if (nonBlocking < 0 || (blocking >= 0 && blocking < nonBlocking)) {
                    offenders.add(window + " at line " + lineOf(source, matcher.start()));
                }
            }
            if (!found) {
                offenders.add(window + " is no longer opened by the editor (update this test)");
            }
        }
        assertWithMessage("These editor windows block in showAndWait instead of show() + callback")
            .that(offenders).isEmpty();
    }

    /** The removed satellite windows stay removed. */
    @Test
    void theFlowHasNoSatelliteWindowsAnyMore() {
        for (String removed : List.of("SnippetCodeAnalysisDialog.java", "SnippetAiApplyProgressWindow.java",
                "WindowDockGroup.java")) {
            assertWithMessage(removed + " came back").that(Files.exists(UI_ROOT.resolve(removed))).isFalse();
        }
    }

    /** A question raised by the flow blocks nothing while the editor lives in a tab of the main window. */
    @Test
    void theFlowsQuestionsAreNonModalWhenHosted() throws IOException {
        String source = code(UI_ROOT.resolve("SnippetEditDialog.java"));
        assertWithMessage("aiFlowAlertModality() must exist and return NONE when hosted")
            .that(source).containsMatch(
                "private Modality aiFlowAlertModality\\(\\)\\s*\\{\\s*return isHostedInTab\\(\\)[^;]*Modality\\.NONE");
        assertWithMessage("the code-text language question uses aiFlowAlertModality()")
            .that(source).contains("initModality(aiFlowAlertModality())");
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

    private static int countOf(String source, String needle) {
        int count = 0;
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }
}
