package de.kortty.core;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalAnsiProcessorTest {

    private List<SessionJournalAnsiProcessor.EmittedLine> emitted;
    private SessionJournalAnsiProcessor processor;

    @BeforeMethod
    void setUp() {
        emitted = new ArrayList<>();
        processor = new SessionJournalAnsiProcessor(emitted::add);
    }

    private List<String> texts() {
        return emitted.stream().map(SessionJournalAnsiProcessor.EmittedLine::text).toList();
    }

    @Test
    void emitsPlainLines() {
        processor.accept("hello world\nsecond line\n");
        assertThat(texts()).containsExactly("hello world", "second line").inOrder();
    }

    @Test
    void stripsCsiSequences() {
        processor.accept("[31mred[0m text\n");
        assertThat(texts()).containsExactly("red text");
    }

    @Test
    void survivesEscapeSequenceSplitAcrossChunks() {
        processor.accept("start[");
        processor.accept("32;1mgreen");
        processor.accept("[0m\n");
        assertThat(texts()).containsExactly("startgreen");
    }

    @Test
    void stripsOscTitleSequences() {
        processor.accept("]0;window titleprompt$ \n");
        assertThat(texts()).containsExactly("prompt$");
    }

    @Test
    void stripsOscTerminatedByStringTerminator() {
        processor.accept("]8;;http://example.com\\link\n");
        assertThat(texts()).containsExactly("link");
    }

    @Test
    void carriageReturnOverwriteCollapsesProgressBars() {
        processor.accept("progress 10%\rprogress 50%\rprogress 100%\n");
        assertThat(texts()).containsExactly("progress 100%");
    }

    @Test
    void crlfIsASingleLineBreak() {
        processor.accept("line one\r\nline two\r\n");
        assertThat(texts()).containsExactly("line one", "line two").inOrder();
    }

    @Test
    void keepsUnicodeIntact() {
        processor.accept("größe: 10 → ✓ öäü 東京\n");
        assertThat(texts()).containsExactly("größe: 10 → ✓ öäü 東京");
    }

    @Test
    void backspaceDeletesLastCharacter() {
        processor.accept("abcd\b\bx\n");
        assertThat(texts()).containsExactly("abx");
    }

    @Test
    void collapsesBlankLineRuns() {
        processor.accept("a\n\n\n\nb\n");
        assertThat(texts()).containsExactly("a", "", "b").inOrder();
    }

    @Test
    void idleFlushEmitsPendingPromptAsPartial() {
        processor.accept("password: ");
        processor.flushIdle(0);
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).text()).isEqualTo("password:");
        assertThat(emitted.get(0).partial()).isTrue();
        // The buffer is kept: when the line completes, the full line is emitted again.
        processor.accept("\n");
        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(1).partial()).isFalse();
        assertThat(emitted.get(1).text()).isEqualTo("password:");
    }

    @Test
    void idleFlushDoesNotRepeatIdenticalPartial() {
        processor.accept("waiting");
        processor.flushIdle(0);
        processor.flushIdle(0);
        assertThat(emitted).hasSize(1);
    }

    @Test
    void pendingLineExposesUnemittedBuffer() {
        processor.accept("[sudo] password for daniel:");
        assertThat(processor.pendingLine()).isEqualTo("[sudo] password for daniel:");
    }

    @Test
    void flushRemainingEmitsFinalLine() {
        processor.accept("no newline at end");
        processor.flushRemaining();
        assertThat(texts()).containsExactly("no newline at end");
        assertThat(emitted.get(0).partial()).isFalse();
    }

    @Test
    void forceEmitsOversizedLines() {
        processor.accept("x".repeat(SessionJournalAnsiProcessor.MAX_LINE_CHARS + 5));
        assertThat(emitted).isNotEmpty();
        assertThat(emitted.get(0).text().length()).isEqualTo(SessionJournalAnsiProcessor.MAX_LINE_CHARS);
    }

    @Test
    void dropsControlCharactersButKeepsTabs() {
        processor.accept("ab\tc\n");
        assertThat(texts()).containsExactly("ab\tc");
    }
}
