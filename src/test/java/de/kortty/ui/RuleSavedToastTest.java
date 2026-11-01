package de.kortty.ui;

import de.kortty.memory.SavedRule;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;

class RuleSavedToastTest {

    @Test
    void everyUndoOutcomeHasAMessageInTheEnglishBundle() throws IOException {
        Properties english = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/i18n/messages.properties"));
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            english.load(reader);
        }

        for (SavedRule.UndoOutcome outcome : SavedRule.UndoOutcome.values()) {
            String key = RuleSavedToast.undoMessageKey(outcome);
            assertThat(key).startsWith("ai.learn.undo.");
            assertThat(english.getProperty(key)).isNotNull();
        }
        assertThat(english.getProperty(RuleSavedToast.undoMessageKey(null))).isNotNull();
        assertThat(english.getProperty("ai.learn.undo.button")).isNotNull();
    }

    @Test
    void aRuleEditedSinceItWasSavedReadsAsKeptAndNotAsNotFound() {
        assertThat(RuleSavedToast.undoMessageKey(SavedRule.UndoOutcome.RULE_CHANGED_KEPT))
            .isEqualTo("ai.learn.undo.ruleChanged");
        assertThat(RuleSavedToast.undoMessageKey(SavedRule.UndoOutcome.RULE_CHANGED_KEPT))
            .isNotEqualTo(RuleSavedToast.undoMessageKey(SavedRule.UndoOutcome.NOT_FOUND));
    }

    @Test
    void aRemovedRuleAndAKeptSkillReadDifferently() {
        assertThat(RuleSavedToast.undoMessageKey(SavedRule.UndoOutcome.UNDONE))
            .isNotEqualTo(RuleSavedToast.undoMessageKey(SavedRule.UndoOutcome.RULE_REMOVED_SKILL_KEPT));
        assertThat(RuleSavedToast.undoMessageKey(SavedRule.UndoOutcome.EXPIRED))
            .isEqualTo("ai.learn.undo.expired");
        assertThat(RuleSavedToast.undoMessageKey(null)).isEqualTo("ai.learn.undo.failed");
    }
}
