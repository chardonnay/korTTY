package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;

class AsciiArtSupportTest {

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

    // ---- Reply sanitizing ----

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
        assertThat(AsciiArtSupport.extractAsciiArt("```\nab\u0007c   \n```")).isEqualTo("abc");
    }

    @Test
    void blankEdgeLinesAreTrimmedButInnerBlankLinesSurvive() {
        assertThat(AsciiArtSupport.extractAsciiArt("```\n\n\n#\n\n#\n\n\n```")).isEqualTo("#\n\n#");
    }

    @Test
    void windowsLineEndingsAreNormalised() {
        assertThat(AsciiArtSupport.extractAsciiArt("```\r\n/\\\r\n\\/\r\n```")).isEqualTo("/\\\n\\/");
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

        assertThat(AsciiArtSupport.generateAsciiArt(failIfCalled, "   ", null, "de", 0, null)).isNull();
    }

    @Test
    void generationSendsTheActionAndReturnsTheSanitizedPicture() throws Exception {
        AiRequest[] seen = new AiRequest[1];
        AiService service = new AiService() {
            @Override
            public AiExecutionResult execute(AiRequest request) {
                seen[0] = request;
                return new AiExecutionResult("```\n /\\\n/__\\\n```", null);
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };

        String art = AsciiArtSupport.generateAsciiArt(service, "  Haus  ", "prod-server", "de", 1, null);

        assertThat(art).isEqualTo(" /\\\n/__\\");
        assertThat(seen[0].action()).isEqualTo(AiAction.GENERATE_ASCII_ART);
        assertThat(seen[0].selectedText()).isEqualTo("Haus");
        assertThat(seen[0].responseLanguageCode()).isEqualTo("de");
        assertThat(seen[0].userPrompt()).contains("attempt 2");
        assertThat(seen[0].includeAiSkills()).isFalse();
    }

    @Test
    void missingServiceIsReportedInsteadOfSilentlyDoingNothing() {
        try {
            AsciiArtSupport.generateAsciiArt(null, "Haus", null, "de", 0, null);
            throw new AssertionError("expected an IllegalStateException");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(IllegalStateException.class);
        }
    }
}
