package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * The AI memory and skill dialogs: their Cancel and Close buttons carry the translated labels (JavaFX's bare
 * {@code ButtonType.CANCEL} and {@code ButtonType.CLOSE} stay English in the German UI), and the wrapped texts and the
 * rule toast's undo button keep the sizes the smoke run verified.
 */
class AiMemoryDialogLabelsSourceScanTest {

    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/" + name + ".java")).replace("\r\n", "\n");
    }

    @Test
    void cancelAndCloseButtonsUseTheTranslatedLabels() throws IOException {
        for (String dialog : List.of("RuleProposalDialog", "ExternalAiSkillUpdateDialog", "AiMemoryHistoryDialog",
            "AiMemoryCleanupDialog", "AiSkillStatisticsDialog")) {
            String text = source(dialog);
            for (String bare : List.of("add(ButtonType.CANCEL)", "add(ButtonType.CLOSE)")) {
                assertWithMessage(dialog + " must not add " + bare).that(text).doesNotContain(bare);
            }
            assertWithMessage(dialog + " uses a translated Cancel or Close label")
                .that(text.contains("I18n.get(\"dialog.cancel\")") || text.contains("I18n.get(\"dialog.close\")"))
                .isTrue();
        }
    }

    @Test
    void triageOptionTitlesDoNotTreatUnderscoresAsMnemonics() throws IOException {
        assertWithMessage("SolutionChoicePane option radios")
            .that(source("SolutionChoicePane")).contains("radio.setMnemonicParsing(false);");
    }

    @Test
    void ruleToastUndoButtonKeepsItsTextWidth() throws IOException {
        assertWithMessage("RuleSavedToast undo button")
            .that(source("RuleSavedToast")).contains("undo.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);");
    }

    @Test
    void externalMemoryPathsCanBreakAfterSeparators() throws IOException {
        assertWithMessage("ExternalMemoryAddDialog path label")
            .that(source("ExternalMemoryAddDialog")).contains("u200B");
    }
}
