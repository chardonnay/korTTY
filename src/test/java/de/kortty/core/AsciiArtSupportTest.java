package de.kortty.core;

import de.kortty.core.AsciiArtPictureRenderer.RejectReason;
import de.kortty.core.AsciiArtPictureRenderer.RenderResult;
import de.kortty.core.AsciiArtPictureRenderer.Stats;
import de.kortty.core.AsciiArtSupport.AsciiArtResult;
import de.kortty.core.AsciiArtSupport.Source;
import de.kortty.core.AsciiArtSupport.Stage;
import de.kortty.model.AsciiArtPictureSize;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AsciiArtSupportTest {

    private static final String SVG_ANSWER =
        "```svg\n<svg viewBox=\"0 0 100 100\"><rect x=\"10\" y=\"10\" width=\"80\" height=\"80\"/></svg>\n```";
    private static final String PICTURE = "####\n####";

    // ---- Preview zoom ----

    @Test
    void clampKeepsSizesInsideTheSupportedRange() {
        assertThat(AsciiArtSupport.clampPreviewFontSize(18.0)).isEqualTo(18.0);
        assertThat(AsciiArtSupport.clampPreviewFontSize(2.0)).isEqualTo(AsciiArtSupport.MIN_PREVIEW_FONT_SIZE);
        assertThat(AsciiArtSupport.clampPreviewFontSize(500.0)).isEqualTo(AsciiArtSupport.MAX_PREVIEW_FONT_SIZE);
    }

    @Test
    void clampFallsBackToTheDefaultForUnusableValues() {
        assertThat(AsciiArtSupport.clampPreviewFontSize(0.0)).isEqualTo(AsciiArtSupport.DEFAULT_PREVIEW_FONT_SIZE);
        assertThat(AsciiArtSupport.clampPreviewFontSize(-4.0)).isEqualTo(AsciiArtSupport.DEFAULT_PREVIEW_FONT_SIZE);
        assertThat(AsciiArtSupport.clampPreviewFontSize(Double.NaN)).isEqualTo(AsciiArtSupport.DEFAULT_PREVIEW_FONT_SIZE);
    }

    @Test
    void steppingZoomsInAndOutAndStopsAtTheBounds() {
        assertThat(AsciiArtSupport.stepPreviewFontSize(12.0, 1)).isEqualTo(13.0);
        assertThat(AsciiArtSupport.stepPreviewFontSize(12.0, -1)).isEqualTo(11.0);
        assertThat(AsciiArtSupport.stepPreviewFontSize(12.0, 4)).isEqualTo(16.0);
        assertThat(AsciiArtSupport.stepPreviewFontSize(AsciiArtSupport.MAX_PREVIEW_FONT_SIZE, 5))
            .isEqualTo(AsciiArtSupport.MAX_PREVIEW_FONT_SIZE);
        assertThat(AsciiArtSupport.stepPreviewFontSize(AsciiArtSupport.MIN_PREVIEW_FONT_SIZE, -5))
            .isEqualTo(AsciiArtSupport.MIN_PREVIEW_FONT_SIZE);
    }

    @Test
    void previewStyleAndZoomPercentReflectTheFontSize() {
        assertThat(AsciiArtSupport.previewStyle(14.0)).contains("14.0px");
        assertThat(AsciiArtSupport.previewStyle(14.0)).contains("monospace");
        assertThat(AsciiArtSupport.zoomPercent(AsciiArtSupport.DEFAULT_PREVIEW_FONT_SIZE)).isEqualTo(100);
        assertThat(AsciiArtSupport.zoomPercent(24.0)).isEqualTo(200);
    }

    // ---- Retry variation ----

    @Test
    void firstAttemptGetsNoVariationInstruction() {
        assertThat(AsciiArtSupport.variationInstructions(0)).isNull();
        assertThat(AsciiArtSupport.variationInstructions(-1)).isNull();
    }

    @Test
    void everyRetryAsksForADifferentTreatment() {
        String first = AsciiArtSupport.variationInstructions(1);
        String second = AsciiArtSupport.variationInstructions(2);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isNotEqualTo(second);
        assertThat(first).contains("attempt 2");
        assertThat(second).contains("attempt 3");
    }

    @Test
    void variationHintsWrapAroundButStayDistinctPerAttempt() {
        // The hint list is rotated, so a later attempt reuses a hint while still naming its own attempt number.
        String ninth = AsciiArtSupport.variationInstructions(9);
        assertThat(ninth).isNotEqualTo(AsciiArtSupport.variationInstructions(1));
        assertThat(ninth).contains("attempt 10");
    }

    @Test
    void variationHintsDescribeCompositionNotCharacters() {
        // The model draws shapes now, so a hint about '#' or '|' characters would be meaningless.
        for (int attempt = 1; attempt <= 8; attempt++) {
            String hint = AsciiArtSupport.variationInstructions(attempt);
            assertThat(hint).doesNotContain("'#'");
            assertThat(hint).doesNotContain("'|'");
            assertThat(hint).doesNotContain("characters");
        }
        assertThat(AsciiArtSupport.variationInstructions(1)).contains("viewpoint");
    }

    // ---- Legacy reply sanitizing ----

    @Test
    void emptyRepliesYieldNoPicture() {
        assertThat(AsciiArtSupport.extractAsciiArt(null)).isNull();
        assertThat(AsciiArtSupport.extractAsciiArt("")).isNull();
        assertThat(AsciiArtSupport.extractAsciiArt("   \n  ")).isNull();
        assertThat(AsciiArtSupport.extractAsciiArt("```\n   \n```")).isNull();
    }

    @Test
    void theFencedBlockIsPreferredOverSurroundingProse() {
        String reply = "Here you go:\n```\n /\\\n/__\\\n```\nEnjoy!";

        assertThat(AsciiArtSupport.extractAsciiArt(reply)).isEqualTo(" /\\\n/__\\");
    }

    @Test
    void aLanguageTagOnTheFenceIsDropped() {
        assertThat(AsciiArtSupport.extractAsciiArt("```text\n#####\n```")).isEqualTo("#####");
    }

    @Test
    void anUnfencedReplyIsUsedAsIs() {
        assertThat(AsciiArtSupport.extractAsciiArt("\n\n###\n#_#\n\n")).isEqualTo("###\n#_#");
    }

    @Test
    void reasoningBlocksAreRemovedBeforeExtraction() {
        String reply = "<think>Let me plan the roof first.</think>\n```\n/\\\n```";

        assertThat(AsciiArtSupport.extractAsciiArt(reply)).isEqualTo("/\\");
    }

    @Test
    void tabsBecomeSpacesSoMonospaceAlignmentSurvives() {
        assertThat(AsciiArtSupport.extractAsciiArt("```\na\tb\n```")).isEqualTo("a    b");
    }

    @Test
    void controlCharactersAndTrailingWhitespaceAreStripped() {
        assertThat(AsciiArtSupport.extractAsciiArt("```\nabc   \n```")).isEqualTo("abc");
    }

    @Test
    void blankEdgeLinesAreTrimmedButInnerBlankLinesSurvive() {
        assertThat(AsciiArtSupport.extractAsciiArt("```\n\n\n#\n\n#\n\n\n```")).isEqualTo("#\n\n#");
    }

    @Test
    void windowsLineEndingsAreNormalised() {
        assertThat(AsciiArtSupport.extractAsciiArt("```\r\n/\\\r\n\\/\r\n```")).isEqualTo("/\\\n\\/");
    }

    @Test
    void theFallbackPictureIsCroppedToTheGrid() {
        StringBuilder wide = new StringBuilder();
        for (int row = 0; row < 35; row++) {
            wide.append("#".repeat(70)).append('\n');
        }

        String fitted = AsciiArtSupport.fitToGrid(wide.toString(), AsciiArtPictureSize.SMALL);

        assertThat(fitted).isNotNull();
        List<String> lines = fitted.lines().toList();
        assertThat(lines).hasSize(20);
        assertThat(lines.get(0)).hasLength(40);
        assertThat(AsciiArtSupport.fitToGrid("   \n  ", AsciiArtPictureSize.SMALL)).isNull();
    }

    // ---- Generation guards ----

    @Test
    void aBlankSubjectIsNotSentToTheModel() throws Exception {
        AiService failIfCalled = new AiService() {
            @Override
            public AiExecutionResult execute(AiRequest request) {
                throw new AssertionError("the model must not be called for a blank subject");
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };

        assertThat(AsciiArtSupport.generateAsciiArt(failIfCalled, "   ", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, null, null, failingRenderer())).isNull();
    }

    @Test
    void missingServiceIsReportedInsteadOfSilentlyDoingNothing() {
        try {
            AsciiArtSupport.generateAsciiArt(null, "Haus", null, "de", 0, AsciiArtPictureSize.MEDIUM, null, null,
                failingRenderer());
            throw new AssertionError("expected an IllegalStateException");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(IllegalStateException.class);
        }
    }

    // ---- The SVG pipeline ----

    @Test
    void aGoodDrawingNeedsExactlyOneRequest() throws Exception {
        RecordingService service = new RecordingService(List.of(new AiExecutionResult(SVG_ANSWER, null)));
        List<Stage> stages = new ArrayList<>();
        List<AiRequest> recorded = new ArrayList<>();

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "  Haus  ", "prod-server", "de", 1,
            AsciiArtPictureSize.MEDIUM, (request, reply) -> recorded.add(request), stages::add,
            scriptedRenderer(accepted(PICTURE)));

        assertThat(result.isUsable()).isTrue();
        assertThat(result.picture()).isEqualTo(PICTURE);
        assertThat(result.source()).isEqualTo(Source.SVG);
        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        assertThat(result.columns()).isEqualTo(60);
        assertThat(result.rows()).isEqualTo(30);
        assertThat(stages).containsExactly(Stage.REQUESTING, Stage.CONVERTING).inOrder();
        assertThat(recorded).hasSize(1);

        AiRequest sent = service.requests.get(0);
        assertThat(sent.action()).isEqualTo(AiAction.GENERATE_ASCII_ART);
        assertThat(sent.selectedText()).isEqualTo("Haus");
        assertThat(sent.connectionDisplayName()).isEqualTo("prod-server");
        assertThat(sent.responseLanguageCode()).isEqualTo("de");
        assertThat(sent.userPrompt()).contains("attempt 2");
        assertThat(sent.includeAiSkills()).isFalse();
        assertThat(sent.asciiArtOptions().mode()).isEqualTo(AsciiArtRequestOptions.Mode.SVG);
        assertThat(sent.asciiArtOptions().size()).isEqualTo(AsciiArtPictureSize.MEDIUM);
        assertThat(sent.asciiArtOptions().repairFeedback()).isNull();
    }

    @Test
    void aTruncatedButDrawableAnswerIsShownAndFlaggedWithoutARetry() throws Exception {
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult("<svg viewBox=\"0 0 100 100\"><circle cx=\"50\" cy=\"50\" r=\"30\"/>", null, null, true)));

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Ball", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, null, null, scriptedRenderer(accepted(PICTURE)));

        assertThat(result.isUsable()).isTrue();
        assertThat(result.truncated()).isTrue();
        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.source()).isEqualTo(Source.SVG);
    }

    @Test
    void anAnswerWithoutADrawingGetsExactlyOneRepairRequestNamingTheDefect() throws Exception {
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult("Sorry, I cannot draw.", null),
            new AiExecutionResult(SVG_ANSWER, null)));
        List<Stage> stages = new ArrayList<>();
        List<AiRequest> recorded = new ArrayList<>();

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.LARGE, (request, reply) -> recorded.add(request), stages::add,
            scriptedRenderer(hard(RejectReason.NO_SVG), accepted(PICTURE)));

        assertThat(result.isUsable()).isTrue();
        assertThat(result.source()).isEqualTo(Source.SVG_REPAIR);
        assertThat(result.requests()).isEqualTo(2);
        assertThat(service.requests).hasSize(2);
        AiRequest repair = service.requests.get(1);
        assertThat(repair.asciiArtOptions().mode()).isEqualTo(AsciiArtRequestOptions.Mode.SVG);
        assertThat(repair.asciiArtOptions().repairFeedback()).isEqualTo(RejectReason.NO_SVG.repairHint());
        assertThat(repair.selectedText()).isEqualTo("Haus");
        assertThat(stages).containsExactly(Stage.REQUESTING, Stage.CONVERTING, Stage.REPAIRING, Stage.CONVERTING).inOrder();
        // Usage is booked for both requests, with the request that was actually sent.
        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(1)).isSameInstanceAs(repair);
    }

    @Test
    void theRendererRejectionReasonReachesTheRepairRequest() throws Exception {
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult(SVG_ANSWER, null),
            new AiExecutionResult(SVG_ANSWER, null)));

        AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0, AsciiArtPictureSize.MEDIUM, null, null,
            scriptedRenderer(hard(RejectReason.NO_INK), accepted(PICTURE)));

        assertThat(service.requests.get(1).asciiArtOptions().repairFeedback())
            .isEqualTo(RejectReason.NO_INK.repairHint());
    }

    @Test
    void aCutOffRepairCandidateAsksForAShorterDrawing() throws Exception {
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult("<svg viewBox=\"0 0 100 100\"><rect", null, null, true),
            new AiExecutionResult(SVG_ANSWER, null)));

        AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0, AsciiArtPictureSize.MEDIUM, null, null,
            scriptedRenderer(hard(RejectReason.BAD_PATH), accepted(PICTURE)));

        String feedback = service.requests.get(1).asciiArtOptions().repairFeedback();
        assertThat(feedback).startsWith(RejectReason.BAD_PATH.repairHint());
        assertThat(feedback).contains("cut off");
    }

    @Test
    void theLegacyTypedFallbackRunsOnlyAfterTheRepairAlsoFails() throws Exception {
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult("no picture", null),
            new AiExecutionResult("still no picture", null),
            new AiExecutionResult("```\n /\\\n/__\\\n```", null)));
        List<Stage> stages = new ArrayList<>();

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, null, stages::add,
            scriptedRenderer(hard(RejectReason.NO_SVG), hard(RejectReason.NO_SVG)));

        assertThat(result.isUsable()).isTrue();
        assertThat(result.picture()).isEqualTo(" /\\\n/__\\");
        assertThat(result.source()).isEqualTo(Source.LEGACY_ASCII);
        assertThat(result.requests()).isEqualTo(3);
        AiRequest legacy = service.requests.get(2);
        assertThat(legacy.asciiArtOptions().mode()).isEqualTo(AsciiArtRequestOptions.Mode.ASCII);
        assertThat(legacy.asciiArtOptions().repairFeedback()).isNull();
        assertThat(legacy.asciiArtOptions().size()).isEqualTo(AsciiArtPictureSize.MEDIUM);
        assertThat(stages).contains(Stage.FALLBACK);
    }

    @Test
    void threeUnusableAnswersYieldNoPictureButKeepTheReason() throws Exception {
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult("no", null),
            new AiExecutionResult("no", null),
            new AiExecutionResult("", null)));

        List<AiRequest> recorded = new ArrayList<>();

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, (request, reply) -> recorded.add(request), null,
            scriptedRenderer(hard(RejectReason.NO_SVG), hard(RejectReason.NO_SHAPES)));

        assertThat(result).isNotNull();
        assertThat(result.isUsable()).isFalse();
        assertThat(result.rejection()).isEqualTo(RejectReason.NO_SHAPES);
        assertThat(result.requests()).isEqualTo(3);
        // Every request that was sent is booked, the fallback included.
        assertThat(recorded).hasSize(3);
    }

    @Test
    void aSoftlyRejectedDrawingIsKeptInsteadOfFallingBack() throws Exception {
        // The first drawing is too small but drawable; the repair returns prose. The small drawing
        // is still the best thing the user can get without a third request of the typed kind.
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult(SVG_ANSWER, null),
            new AiExecutionResult("prose", null)));

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, null, null,
            scriptedRenderer(soft(RejectReason.TOO_SMALL, "##", 0.3), hard(RejectReason.NO_SVG)));

        assertThat(result.isUsable()).isTrue();
        assertThat(result.picture()).isEqualTo("##");
        assertThat(result.source()).isEqualTo(Source.SVG);
        assertThat(result.requests()).isEqualTo(2);
    }

    @Test
    void theRepairCandidateWinsWhenItFillsMoreOfTheCanvas() throws Exception {
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult(SVG_ANSWER, null),
            new AiExecutionResult(SVG_ANSWER, null)));

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, null, null,
            scriptedRenderer(soft(RejectReason.TOO_SMALL, "small", 0.2), soft(RejectReason.TOO_SMALL, "bigger", 0.4)));

        assertThat(result.picture()).isEqualTo("bigger");
        assertThat(result.source()).isEqualTo(Source.SVG_REPAIR);
        assertThat(result.requests()).isEqualTo(2);
    }

    @Test
    void anAnswerCutOffBeforeAnyShapeStopsImmediately() throws Exception {
        // The completion budget went to hidden reasoning; a second request would repeat that exactly.
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult("", null, "thinking...", true)));

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, null, null, scriptedRenderer(hard(RejectReason.NO_SVG)));

        assertThat(result.isUsable()).isFalse();
        assertThat(result.rejection()).isEqualTo(RejectReason.TRUNCATED_EMPTY);
        assertThat(result.truncated()).isTrue();
        assertThat(result.requests()).isEqualTo(1);
    }

    @Test
    void aCutStreamWithoutADrawingStillGetsTheRepairRound() throws Exception {
        // An interruption is transient, unlike the completion limit: the repair may well succeed.
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult("", null, null, true, true),
            new AiExecutionResult(SVG_ANSWER, null)));

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, null, null, scriptedRenderer(hard(RejectReason.NO_SVG), accepted(PICTURE)));

        assertThat(result.isUsable()).isTrue();
        assertThat(result.source()).isEqualTo(Source.SVG_REPAIR);
        assertThat(result.requests()).isEqualTo(2);
        assertThat(service.requests.get(1).asciiArtOptions().repairFeedback()).doesNotContain("cut off");
    }

    @Test
    void aRepairAnswerCutOffBeforeAnyShapeStopsWithoutTheFallback() throws Exception {
        RecordingService service = new RecordingService(List.of(
            new AiExecutionResult("prose", null),
            new AiExecutionResult("", null, "thinking...", true)));

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, null, null, scriptedRenderer(hard(RejectReason.NO_SVG), hard(RejectReason.NO_SVG)));

        assertThat(result.isUsable()).isFalse();
        assertThat(result.rejection()).isEqualTo(RejectReason.TRUNCATED_EMPTY);
        assertThat(result.requests()).isEqualTo(2);
    }

    @Test
    void anEmptyResponseExceptionCountsAsAnAnswerWithoutADrawing() throws Exception {
        AiService service = new AiService() {
            int calls;

            @Override
            public AiExecutionResult execute(AiRequest request) throws Exception {
                calls++;
                if (calls == 1) {
                    throw new OpenAiCompatibleAiService.EmptyResponseException(
                        new AiExecutionResult("", null, "hmm", false));
                }
                return new AiExecutionResult(SVG_ANSWER, null);
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };
        List<AiRequest> recorded = new ArrayList<>();

        AsciiArtResult result = AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0,
            AsciiArtPictureSize.MEDIUM, (request, reply) -> recorded.add(request), null,
            scriptedRenderer(hard(RejectReason.NO_SVG), accepted(PICTURE)));

        assertThat(result.isUsable()).isTrue();
        assertThat(result.source()).isEqualTo(Source.SVG_REPAIR);
        assertThat(recorded).hasSize(2);
    }

    @Test
    void anInterruptedWorkerStopsBeforeTheNextRequest() {
        List<AiRequest> seen = new ArrayList<>();
        AiService service = new AiService() {
            @Override
            public AiExecutionResult execute(AiRequest request) {
                seen.add(request);
                Thread.currentThread().interrupt();
                return new AiExecutionResult("prose", null);
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };
        try {
            AsciiArtSupport.generateAsciiArt(service, "Haus", null, "de", 0, AsciiArtPictureSize.MEDIUM,
                null, null, scriptedRenderer(hard(RejectReason.NO_SVG)));
            throw new AssertionError("expected an InterruptedException");
        } catch (InterruptedException expected) {
            assertThat(Thread.interrupted()).isTrue(); // clears the flag for the next test
        } catch (Exception e) {
            throw new AssertionError("unexpected " + e, e);
        }
        assertThat(seen).hasSize(1);
    }

    // ---- Helpers ----

    private static RenderResult accepted(String text) {
        return new RenderResult(text, null, false, stats(1.0, 5), List.of());
    }

    private static RenderResult hard(RejectReason reason) {
        return new RenderResult(null, reason, false, stats(0.0, 0), List.of());
    }

    private static RenderResult soft(RejectReason reason, String text, double coverage) {
        return new RenderResult(text, reason, true, stats(coverage, 3), List.of());
    }

    private static Stats stats(double coverage, int shapes) {
        return new Stats(shapes, 0, 10, 0.1, coverage, 4, 2, false, false, false, false);
    }

    /** A renderer that answers the scripted results in order and fails on any further call. */
    private static AsciiArtSupport.SvgPictureRenderer scriptedRenderer(RenderResult... results) {
        List<RenderResult> queue = new ArrayList<>(List.of(results));
        return (reply, columns, rows) -> {
            if (queue.isEmpty()) {
                throw new AssertionError("the renderer was called more often than scripted");
            }
            return queue.remove(0);
        };
    }

    private static AsciiArtSupport.SvgPictureRenderer failingRenderer() {
        return (reply, columns, rows) -> {
            throw new AssertionError("the renderer must not be called");
        };
    }

    /** Answers scripted results in order and remembers every request it saw. */
    private static final class RecordingService implements AiService {
        final List<AiRequest> requests = new ArrayList<>();
        private final List<AiExecutionResult> answers;

        RecordingService(List<AiExecutionResult> answers) {
            this.answers = new ArrayList<>(answers);
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            requests.add(request);
            if (answers.isEmpty()) {
                throw new AssertionError("the model was called more often than scripted");
            }
            return answers.remove(0);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
