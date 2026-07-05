package de.kortty.plugin.terminaleffects.pack;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class PackOutputPacerTest {

    @Test
    void segmentPacesPrintableCharactersAndPunctuation() {
        List<PackOutputPacer.OutputSegment> segments = PackOutputPacer.segment("hi.");

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).text()).isEqualTo("h");
        assertThat(segments.get(0).delayMode()).isEqualTo(PackOutputPacer.DelayMode.CHARACTER);
        assertThat(segments.get(1).delayMode()).isEqualTo(PackOutputPacer.DelayMode.CHARACTER);
        assertThat(segments.get(2).text()).isEqualTo(".");
        assertThat(segments.get(2).delayMode()).isEqualTo(PackOutputPacer.DelayMode.PUNCTUATION);
    }

    @Test
    void segmentTreatsLineBreaksAsCarriageReturnPause() {
        List<PackOutputPacer.OutputSegment> segments = PackOutputPacer.segment("a\r\nb\n");

        assertThat(segments).hasSize(4);
        assertThat(segments.get(1).text()).isEqualTo("\r\n");
        assertThat(segments.get(1).delayMode()).isEqualTo(PackOutputPacer.DelayMode.LINE_BREAK);
        assertThat(segments.get(3).text()).isEqualTo("\n");
        assertThat(segments.get(3).delayMode()).isEqualTo(PackOutputPacer.DelayMode.LINE_BREAK);
    }

    @Test
    void segmentPassesEscapeSequencesThroughImmediately() {
        List<PackOutputPacer.OutputSegment> segments = PackOutputPacer.segment("\u001B[32mA");

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).text()).isEqualTo("\u001B[32m");
        assertThat(segments.get(0).delayMode()).isEqualTo(PackOutputPacer.DelayMode.NONE);
        assertThat(segments.get(1).delayMode()).isEqualTo(PackOutputPacer.DelayMode.CHARACTER);
    }

    @Test
    void segmentPassesOscSequencesThroughImmediately() {
        List<PackOutputPacer.OutputSegment> segments = PackOutputPacer.segment("\u001B]0;title\u0007x");

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).delayMode()).isEqualTo(PackOutputPacer.DelayMode.NONE);
        assertThat(segments.get(1).text()).isEqualTo("x");
    }

    @Test
    void enqueueBypassesPacingForHighVolumeChunks() {
        PackOutputPacer pacer = new PackOutputPacer();
        String bulk = "x".repeat(PackOutputPacer.HIGH_VOLUME_THRESHOLD);

        List<PackOutputPacer.OutputSegment> segments = pacer.enqueue(bulk);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).delayMode()).isEqualTo(PackOutputPacer.DelayMode.NONE);
        assertThat(segments.get(0).text()).hasLength(PackOutputPacer.HIGH_VOLUME_THRESHOLD);
    }

    @Test
    void enqueueCollapsesPendingSegmentsWhenBacklogGrowsTooLarge() {
        PackOutputPacer pacer = new PackOutputPacer();
        pacer.enqueue("abc");
        String almostBulk = "y".repeat(PackOutputPacer.MAX_PENDING_CHARS);

        List<PackOutputPacer.OutputSegment> segments = pacer.enqueue(almostBulk);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).delayMode()).isEqualTo(PackOutputPacer.DelayMode.NONE);
        assertThat(segments.get(0).text()).startsWith("abc");
        assertThat(pacer.poll().text()).hasLength(3 + PackOutputPacer.MAX_PENDING_CHARS);
        assertThat(pacer.hasPending()).isFalse();
    }

    @Test
    void pollDrainsSegmentsInOrder() {
        PackOutputPacer pacer = new PackOutputPacer();
        pacer.enqueue("ab");

        assertThat(pacer.hasPending()).isTrue();
        assertThat(pacer.poll().text()).isEqualTo("a");
        assertThat(pacer.poll().text()).isEqualTo("b");
        assertThat(pacer.poll()).isNull();
        assertThat(pacer.hasPending()).isFalse();
    }
}
