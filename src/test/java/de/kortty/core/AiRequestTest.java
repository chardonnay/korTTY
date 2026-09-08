package de.kortty.core;

import de.kortty.model.AiPromptPreset;
import de.kortty.model.AiWorkload;
import de.kortty.model.AsciiArtPictureSize;
import de.kortty.model.SnippetDiagramType;
import de.kortty.rag.RagContextBuilder;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiRequestTest {

    private static final AsciiArtRequestOptions PICTURE_OPTIONS =
        AsciiArtRequestOptions.svg(AsciiArtPictureSize.XL).withRepairFeedback("Too small.");

    /** A request with every component set to a distinct, non-default value. */
    private static AiRequest fullyPopulated() {
        return new AiRequest(
            AiAction.GENERATE_ASCII_ART,
            "lighthouse",
            "sea-box",
            "de",
            "variation hint",
            "conversation",
            false,
            AiPromptPreset.QWEN,
            "<retrieved_context/>",
            CodeTextLanguage.keep("fr"),
            SnippetDiagramType.STATE,
            PICTURE_OPTIONS);
    }

    // ---- with… methods keep every other component ----

    @Test
    void withAsciiArtOptionsKeepsEveryOtherComponent() {
        AiRequest original = fullyPopulated();
        AsciiArtRequestOptions replacement = AsciiArtRequestOptions.ascii(AsciiArtPictureSize.SMALL);

        AiRequest changed = original.withAsciiArtOptions(replacement);

        assertThat(changed.asciiArtOptions()).isSameInstanceAs(replacement);
        assertThat(changed.withAsciiArtOptions(PICTURE_OPTIONS)).isEqualTo(original);
    }

    @Test
    void withPromptPresetKeepsEveryOtherComponent() {
        AiRequest original = fullyPopulated();

        AiRequest changed = original.withPromptPreset(AiPromptPreset.GENERIC);

        assertThat(changed.promptPreset()).isEqualTo(AiPromptPreset.GENERIC);
        assertThat(changed.withPromptPreset(AiPromptPreset.QWEN)).isEqualTo(original);
    }

    @Test
    void withRetrievedContextKeepsEveryOtherComponent() {
        AiRequest original = fullyPopulated();

        AiRequest changed = original.withRetrievedContext("other context");

        assertThat(changed.retrievedContext()).isEqualTo("other context");
        assertThat(changed.withRetrievedContext("<retrieved_context/>")).isEqualTo(original);
    }

    @Test
    void withDiagramTypeKeepsEveryOtherComponent() {
        AiRequest original = fullyPopulated();

        AiRequest changed = original.withDiagramType(SnippetDiagramType.ER);

        assertThat(changed.diagramType()).isEqualTo(SnippetDiagramType.ER);
        assertThat(changed.withDiagramType(SnippetDiagramType.STATE)).isEqualTo(original);
    }

    @Test
    void withCodeTextLanguageKeepsEveryOtherComponent() {
        AiRequest original = fullyPopulated();
        CodeTextLanguage replacement = CodeTextLanguage.keep("it");

        AiRequest changed = original.withCodeTextLanguage(replacement);

        assertThat(changed.codeTextLanguage()).isSameInstanceAs(replacement);
        assertThat(changed.withCodeTextLanguage(CodeTextLanguage.keep("fr"))).isEqualTo(original);
    }

    @Test
    void shorterConstructorsLeaveTheActionSpecificComponentsUnset() {
        AiRequest request = new AiRequest(AiAction.GENERATE_ASCII_ART, "cat", null, "en");

        assertThat(request.diagramType()).isNull();
        assertThat(request.asciiArtOptions()).isNull();
        assertThat(request.promptPreset()).isEqualTo(AiPromptPreset.GENERIC);
    }

    // ---- The decorating services must not lose action-specific components ----

    @Test
    void promptPresetServiceKeepsDiagramTypeAndAsciiArtOptions() throws Exception {
        RecordingService delegate = new RecordingService();
        AiPromptPresetService service = new AiPromptPresetService(delegate, AiPromptPreset.QWEN);
        AiRequest request = new AiRequest(AiAction.GENERATE_ASCII_ART, "cat", null, "en")
            .withDiagramType(SnippetDiagramType.SEQUENCE)
            .withAsciiArtOptions(PICTURE_OPTIONS);

        service.execute(request);

        // Before the with… methods existed the wrapper rebuilt the request with a shorter
        // constructor, and a sequence diagram silently fell back to a flowchart.
        assertThat(delegate.request.promptPreset()).isEqualTo(AiPromptPreset.QWEN);
        assertThat(delegate.request.diagramType()).isEqualTo(SnippetDiagramType.SEQUENCE);
        assertThat(delegate.request.asciiArtOptions()).isSameInstanceAs(PICTURE_OPTIONS);
        assertThat(delegate.request.selectedText()).isEqualTo("cat");
    }

    @Test
    void ragAugmentedServiceKeepsDiagramTypeAndAsciiArtOptionsWhenItRetrieves() throws Exception {
        RecordingService delegate = new RecordingService();
        RagAugmentedAiService service = new RagAugmentedAiService(
            delegate, List.of("knowledge"), 8_000, AiRequestTest::retrieveStubContext);
        // A chat action so that retrieval actually runs and the request is rebuilt.
        AiRequest request = new AiRequest(AiAction.SUMMARIZE, "log lines", null, "en")
            .withDiagramType(SnippetDiagramType.CLASS)
            .withAsciiArtOptions(PICTURE_OPTIONS);

        service.execute(request);

        assertThat(delegate.request.retrievedContext()).contains("<retrieved_context>");
        assertThat(delegate.request.diagramType()).isEqualTo(SnippetDiagramType.CLASS);
        assertThat(delegate.request.asciiArtOptions()).isSameInstanceAs(PICTURE_OPTIONS);
        assertThat(delegate.request.selectedText()).isEqualTo("log lines");
    }

    private static RagContextBuilder.RagContext retrieveStubContext(
        List<String> storeIds,
        String query,
        int modelContextTokens,
        AiWorkload workload,
        boolean autonomousOnly,
        de.kortty.rag.CancellationToken cancellation) {

        return new RagContextBuilder.RagContext(
            "<retrieved_context>\n[R1] local knowledge\n</retrieved_context>", List.of(), 4, false);
    }

    private static final class RecordingService implements AiService {
        private AiRequest request;

        @Override
        public AiExecutionResult execute(AiRequest request) {
            this.request = request;
            return new AiExecutionResult("ok", null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
