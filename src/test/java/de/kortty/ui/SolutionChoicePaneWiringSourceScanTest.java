package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The solution card: only Proceed saves a typed rule, a rule with no place keeps the card open and says why, and the steps of the
 * selected option are shown because they become the binding plan.
 */
class SolutionChoicePaneWiringSourceScanTest {

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/SolutionChoicePane.java"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    void runningTheRequestAsAskedDoesNotSaveTheTypedRule() throws IOException {
        String source = source();
        assertThat(source).contains("asRequestedButton.setOnAction(event -> complete(SolutionChoiceRules.asRequestedChoice(ideaText())));");
        assertThat(source).doesNotContain("choose(SolutionChoiceRules.asRequestedChoice");
        // The hint names the button that saves the rule, so the user knows which answer keeps it.
        assertThat(source).contains("ai.triage.rule.hintProceed");
    }

    @Test
    void aRuleWithNoPlaceKeepsTheCardOpenWithTheReason() throws IOException {
        String choose = methodBody(source(), "private void choose(TerminalAgentModels.SolutionChoice choice)");
        assertThat(choose).contains("targets.isEmpty() || target == null");
        assertThat(choose).contains("showMessage(I18n.get(RuleSaveException.Reason.NO_TARGET.messageKey()), REFUSAL_STYLE);");
        // The card returns right after the reason, before any save or completion.
        int noTarget = choose.indexOf("NO_TARGET");
        assertThat(choose.indexOf("return;", noTarget)).isAtLeast(0);
        assertThat(choose.indexOf("return;", noTarget)).isLessThan(choose.indexOf("saving = true;"));
    }

    @Test
    void theRuleSectionStaysVisibleWhileItHasText() throws IOException {
        assertThat(methodBody(source(), "private void refreshRule()")).contains("boolean showRule = !targets.isEmpty() || !text.isBlank();");
    }

    @Test
    void theStepsOfTheSelectedOptionAreShown() throws IOException {
        assertThat(methodBody(source(), "private VBox optionCard(")).contains("more.setExpanded(radio.isSelected());");
    }

    @Test
    void proceedNamesTheMissingIdeaInItsTooltip() throws IOException {
        assertThat(methodBody(source(), "private void refreshButtons()")).contains("proceedButton.setTooltip(ideaMissing");
    }
}
