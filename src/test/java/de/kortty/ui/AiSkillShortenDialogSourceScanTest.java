package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The skill shortening: the dialog saves a skill only through the skill repository and only after the user confirmed the
 * proposal it shows, and the statistics offer it only for the hints that name a large or fast-growing skill.
 */
class AiSkillShortenDialogSourceScanTest {

    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/" + name + ".java")).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    void theDialogSavesOnlyThroughTheSkillRepositoryAfterTheUserConfirmed() throws IOException {
        String dialog = source("AiSkillShortenDialog");

        assertThat(dialog).contains("repository.commit(");
        assertThat(methodBody(dialog, "private void apply()")).contains("stillMatches(");
        assertThat(methodBody(dialog, "private void apply()")).contains("repository.commit(");
        for (String write : List.of("setAiSkills(", "AtomicFileWriter", "writeStoreAtomically", "Files.write",
            "Files.move", "MemoryConsent", "repository.update(", "getAiSkills().add", "new GlobalSettings(")) {
            assertWithMessage(write).that(dialog).doesNotContain(write);
        }
    }

    @Test
    void theProposalIsComputedOffTheFxThreadAndNeverBeforeTheUserAsksForIt() throws IOException {
        String dialog = source("AiSkillShortenDialog");

        assertThat(methodBody(dialog, "private void propose()")).contains("CompletableFuture.supplyAsync(");
        assertThat(methodBody(dialog, "private void propose()")).doesNotContain("AiSkillShortener.propose(");
        assertThat(methodBody(dialog, "private Sized compute(")).contains("AiSkillShortener.propose(");
    }

    @Test
    void theStatisticsOfferShorteningOnlyForLargeAndGrowingSkills() throws IOException {
        String statistics = source("AiSkillStatisticsDialog");

        assertThat(methodBody(statistics, "static boolean offersShortening(")).contains("OVER_1000_TOKENS");
        assertThat(methodBody(statistics, "static boolean offersShortening(")).contains("GREW_TWICE_IN_30_DAYS");
        assertThat(methodBody(statistics, "static boolean offersShortening(")).doesNotContain("UNUSED_90_DAYS");
        assertThat(statistics).contains("new AiSkillShortenDialog(");
    }

    @Test
    void theStatisticsOpenTheEditorThroughTheSkillsPane() throws IOException {
        assertThat(source("AiSkillsPane")).contains("this::revealAiSkill");
        assertThat(source("AiSkillsPane")).contains("void revealAiSkill(String skillId)");
    }

    @Test
    void theSaveMergesFromTheSkillAsTheProposalMadeItSoLaterChangesSurvive() throws IOException {
        String dialog = source("AiSkillShortenDialog");

        assertThat(methodBody(dialog, "private void apply()")).contains("AiSkillShortener.mergeBase(");
        assertThat(methodBody(dialog, "private void apply()")).contains("AiSkillShortener.savedSkills(");
        assertThat(methodBody(dialog, "private void apply()")).doesNotContain("new AiSkill(proposal.after())");
    }
}
