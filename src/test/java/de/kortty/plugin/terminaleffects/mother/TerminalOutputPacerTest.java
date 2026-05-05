package de.kortty.plugin.terminaleffects.mother;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

public class TerminalOutputPacerTest {

    @Test
    void segmentKeepsAnsiControlSequenceImmediate() {
        var segments = TerminalOutputPacer.segment("\u001B[32mOK");

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).text()).isEqualTo("\u001B[32m");
        assertThat(segments.get(0).delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.NONE);
        assertThat(segments.get(1).text()).isEqualTo("O");
        assertThat(segments.get(1).delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.LINE_START);
        assertThat(segments.get(2).text()).isEqualTo("K");
        assertThat(segments.get(2).delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.CHARACTER);
    }

    @Test
    void segmentKeepsOscControlSequenceImmediate() {
        var segments = TerminalOutputPacer.segment("\u001B]0;title\u0007>");

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).text()).isEqualTo("\u001B]0;title\u0007");
        assertThat(segments.get(0).delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.NONE);
        assertThat(segments.get(1).text()).isEqualTo(">");
        assertThat(segments.get(1).delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.LINE_START);
    }

    @Test
    void segmentPacesLineBreakAndNextLineStart() {
        var segments = TerminalOutputPacer.segment("A\r\nB");

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).text()).isEqualTo("A");
        assertThat(segments.get(0).delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.LINE_START);
        assertThat(segments.get(1).text()).isEqualTo("\r\n");
        assertThat(segments.get(1).delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.LINE_BREAK);
        assertThat(segments.get(2).text()).isEqualTo("B");
        assertThat(segments.get(2).delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.LINE_START);
    }

    @Test
    void enqueueBypassesPacingForHighVolumeOutput() {
        TerminalOutputPacer pacer = new TerminalOutputPacer();
        String data = "x".repeat(5000);

        pacer.enqueue(data);
        var segment = pacer.poll();

        assertThat(segment.text()).isEqualTo(data);
        assertThat(segment.delayMode()).isEqualTo(TerminalOutputPacer.DelayMode.NONE);
    }
}
