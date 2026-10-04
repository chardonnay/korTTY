package de.kortty.ui;

import de.kortty.shellintegration.PaneOutputClock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Only panes that received output since their file was written are written again: a quiet pane
 * costs nothing, however long its history is.
 */
class SessionScrollbackRecorderTest {

    private SessionScrollbackRecorder<String> recorder;
    private Map<String, Long> output;

    @BeforeMethod
    void setUp() {
        AtomicInteger names = new AtomicInteger();
        recorder = new SessionScrollbackRecorder<>(() -> "ref-" + names.incrementAndGet());
        output = new HashMap<>();
    }

    private List<SessionScrollbackRecorder.Job<String>> changed(String... open) {
        return recorder.changedPanes(List.of(open), pane -> output.getOrDefault(pane, PaneOutputClock.NEVER),
            pane -> () -> List.of(pane + " rows"));
    }

    @Test
    void existingRefNeverNamesANewFile() {
        assertWithMessage("a locked vault names no new file").that(recorder.existingRef("a")).isNull();
        assertThat(recorder.hasRef("a")).isFalse();
        String ref = recorder.refOf("a", 10L);
        assertWithMessage("a pane keeps its file while the vault is locked").that(recorder.existingRef("a"))
            .isEqualTo(ref);
    }

    @Test
    void aPaneWithoutOutputGetsNoFile() {
        assertThat(recorder.refOf("quiet", PaneOutputClock.NEVER)).isNull();
        assertThat(recorder.hasRef("quiet")).isFalse();
    }

    @Test
    void aPaneKeepsItsFileName() {
        String first = recorder.refOf("a", 10L);

        assertThat(first).isEqualTo("ref-1");
        assertThat(recorder.refOf("a", 20L)).isEqualTo(first);
        assertThat(recorder.refOf("b", 5L)).isEqualTo("ref-2");
    }

    @Test
    void onlyPanesWithNewOutputAreWrittenAgain() {
        output.put("a", 10L);
        output.put("b", 10L);
        recorder.refOf("a", 10L);
        recorder.refOf("b", 10L);

        List<SessionScrollbackRecorder.Job<String>> first = changed("a", "b");
        assertThat(first).hasSize(2);
        first.forEach(recorder::written);

        assertWithMessage("nothing arrived since the write").that(changed("a", "b")).isEmpty();

        output.put("b", 11L);
        List<SessionScrollbackRecorder.Job<String>> second = changed("a", "b");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).pane()).isEqualTo("b");
        assertThat(second.get(0).rows().get()).containsExactly("b rows");
    }

    @Test
    void outputDuringTheWriteKeepsThePaneChanged() {
        output.put("a", 10L);
        recorder.refOf("a", 10L);
        SessionScrollbackRecorder.Job<String> job = changed("a").get(0);

        output.put("a", 12L);
        recorder.written(job);

        assertThat(changed("a")).hasSize(1);
    }

    @Test
    void aClosedPaneIsForgotten() {
        output.put("a", 10L);
        recorder.refOf("a", 10L);

        assertThat(changed()).isEmpty();
        assertThat(recorder.hasRef("a")).isFalse();
        assertThat(recorder.refs()).isEmpty();
    }

    @Test
    void afterAPurgeEveryPaneIsWrittenAgain() {
        output.put("a", 10L);
        recorder.refOf("a", 10L);
        changed("a").forEach(recorder::written);

        recorder.filesPurged();

        assertThat(changed("a")).hasSize(1);
    }

    @Test
    void clearForgetsEveryPane() {
        recorder.refOf("a", 10L);
        recorder.clear();

        assertThat(recorder.refs()).isEmpty();
    }
}
