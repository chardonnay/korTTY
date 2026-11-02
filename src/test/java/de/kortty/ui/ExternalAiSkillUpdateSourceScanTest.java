package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Wiring of the update dialog and the pane. The dialog offers the update only through {@code offeredFor}, which leaves
 * it out for a changed skill. The pane applies an update only to the live skill, and only while that still holds the
 * text the dialog diffed. The core refuses a changed skill as well (see ExternalAiSkillSupportTest and
 * AiSkillRepositoryTest).
 */
class ExternalAiSkillUpdateSourceScanTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    private static String source(String name) throws IOException {
        return Files.readString(UI.resolve(name + ".java"), StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    void theDialogBuildsItsButtonsFromTheDecisionsOfTheSkillsState() throws IOException {
        String dialog = source("ExternalAiSkillUpdateDialog");

        assertThat(dialog).contains("boolean locallyModified = ExternalAiSkillSupport.isLocallyModified(skill);");
        assertThat(dialog).contains("ExternalAiSkillUpdateDecision.offeredFor(locallyModified)");
        assertThat(dialog).doesNotContain("ButtonType apply");
        assertThat(occurrences(dialog, "ExternalAiSkillUpdateDecision.APPLY")).isEqualTo(1);
    }

    @Test
    void theDialogDoesNotAskToUpdateAChangedSkill() throws IOException {
        String dialog = source("ExternalAiSkillUpdateDialog");

        assertThat(dialog).contains("\"settings.aiSkills.external.update.modifiedTitle\"");
        assertThat(dialog).contains("\"settings.aiSkills.external.update.modifiedHeader\"");
    }

    @Test
    void thePaneAppliesAnUpdateOnlyToTheLiveSkillWhileItHoldsTheDiffedText() throws IOException {
        String pane = source("AiSkillsPane");

        assertThat(occurrences(pane, "ExternalAiSkillSupport.applyUpdate(")).isEqualTo(1);
        assertThat(pane).contains("AiSkill skill = ExternalAiSkillSupport.findById(aiSkills, selected.getId());");
        assertThat(pane).contains("if (!ExternalAiSkillSupport.holdsText(skill, shownContent, shownDescription)) {");
        assertThat(pane).contains("ExternalAiSkillSupport.applyUpdate(skill, latest, System.currentTimeMillis());");
        assertThat(pane).doesNotContain("applyUpdate(selected");
        assertThat(pane).doesNotContain("applyUpdate(shown");
        assertThat(pane).contains("case APPLY -> {");
    }

    @Test
    void theCopyIsImportedOnlyOnceAndTheSkipGoesThroughTheCore() throws IOException {
        String pane = source("AiSkillsPane");

        assertThat(occurrences(pane, "ExternalAiSkillSupport.copyAsLocalSkill(")).isEqualTo(1);
        assertThat(pane).contains("ExternalAiSkillSupport.findLocalByName(aiSkills, copy.getName())");
        assertThat(occurrences(pane, "ExternalAiSkillSupport.skipRevision(")).isEqualTo(1);
    }
}
