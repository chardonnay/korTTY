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
    private static final AiFileAttachment ATTACHMENT =
        new AiFileAttachment("notes.txt", "/home/daniel/notes.txt", "line one\nline two");

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
            PICTURE_OPTIONS,
            ATTACHMENT);
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
    void withFileAttachmentKeepsEveryOtherComponent() {
        AiRequest original = fullyPopulated();

        AiRequest detached = original.withFileAttachment(null);

        assertThat(detached.fileAttachment()).isNull();
        assertThat(detached.hasFileAttachment()).isFalse();
        assertThat(detached.asciiArtOptions()).isSameInstanceAs(PICTURE_OPTIONS);
        assertThat(detached.withFileAttachment(ATTACHMENT)).isEqualTo(original);
        assertThat(original.hasFileAttachment()).isTrue();
    }

    @Test
    void blankAttachmentContentCountsAsNoAttachment() {
        AiRequest request = new AiRequest(AiAction.ASK, "ls", "box", "en")
            .withFileAttachment(new AiFileAttachment("empty.txt", null, "   "));

        assertThat(request.hasFileAttachment()).isFalse();
    }

    @Test
    void twelveArgumentConstructorLeavesAttachmentUnset() {
        AiRequest request = new AiRequest(
            AiAction.ASK, "text", "box", "en", null, null, true, AiPromptPreset.GENERIC,
            null, null, null, PICTURE_OPTIONS);

        assertThat(request.fileAttachment()).isNull();
        assertThat(request.asciiArtOptions()).isSameInstanceAs(PICTURE_OPTIONS);
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

    // ---- The stream listener survives every copy and every decorator ----

    private static final AiStreamListener LISTENER = (content, reasoning) -> { };

    @Test
    void everyWithMethodKeepsTheStreamListener() {
        AiRequest original = fullyPopulated().withStreamListener(LISTENER);

        assertThat(original.streamListener()).isSameInstanceAs(LISTENER);
        assertThat(original.withCodeTextLanguage(CodeTextLanguage.keep("de")).streamListener()).isSameInstanceAs(LISTENER);
        assertThat(original.withDiagramType(SnippetDiagramType.CLASS).streamListener()).isSameInstanceAs(LISTENER);
        assertThat(original.withAsciiArtOptions(null).streamListener()).isSameInstanceAs(LISTENER);
        assertThat(original.withPromptPreset(AiPromptPreset.GENERIC).streamListener()).isSameInstanceAs(LISTENER);
        assertThat(original.withRetrievedContext("ctx").streamListener()).isSameInstanceAs(LISTENER);
        assertThat(original.withFileAttachment(null).streamListener()).isSameInstanceAs(LISTENER);
        assertThat(original.withStreamListener(null).withStreamListener(LISTENER)).isEqualTo(original);
        assertThat(original.withStreamListener(null).streamListener()).isNull();
    }

    @Test
    void compatibilityConstructorsLeaveTheStreamListenerUnset() {
        assertThat(fullyPopulated().streamListener()).isNull();
        assertThat(new AiRequest(AiAction.ASK, "ls", "box", "en").streamListener()).isNull();
        // withStreamListener keeps every other component.
        assertThat(fullyPopulated().withStreamListener(LISTENER).withStreamListener(null)).isEqualTo(fullyPopulated());
    }

    @Test
    void decoratingServicesForwardTheStreamListener() throws Exception {
        AiRequest request = new AiRequest(AiAction.SUMMARIZE, "log lines", null, "en").withStreamListener(LISTENER);

        RecordingService presetDelegate = new RecordingService();
        new AiPromptPresetService(presetDelegate, AiPromptPreset.QWEN).execute(request);
        RecordingService ragDelegate = new RecordingService();
        new RagAugmentedAiService(ragDelegate, List.of("knowledge"), 8_000, AiRequestTest::retrieveStubContext)
            .execute(request);
        RecordingService loggingDelegate = new RecordingService();
        LoggingAiService.wrap(loggingDelegate, null, "m", null).execute(request);

        assertThat(presetDelegate.request.streamListener()).isSameInstanceAs(LISTENER);
        assertThat(ragDelegate.request.retrievedContext()).contains("<retrieved_context>");
        assertThat(ragDelegate.request.streamListener()).isSameInstanceAs(LISTENER);
        assertThat(loggingDelegate.request.streamListener()).isSameInstanceAs(LISTENER);
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
