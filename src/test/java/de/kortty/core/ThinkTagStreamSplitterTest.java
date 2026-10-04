package de.kortty.core;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class ThinkTagStreamSplitterTest {

    /** Feeds growing snapshots built from {@code pieces} and records what the listener sees. */
    private static List<ThinkTagStreamSplitter.Split> feed(String... pieces) {
        List<ThinkTagStreamSplitter.Split> seen = new ArrayList<>();
        ThinkTagStreamSplitter splitter = new ThinkTagStreamSplitter(
            (content, reasoning) -> seen.add(new ThinkTagStreamSplitter.Split(content, reasoning)));
        StringBuilder snapshot = new StringBuilder();
        for (String piece : pieces) {
            snapshot.append(piece);
            splitter.onProgress(snapshot.toString(), "");
        }
        return seen;
    }

    @Test
    void thinkOpenerSplitAcrossChunksNeverShowsAsAnswer() {
        List<ThinkTagStreamSplitter.Split> seen =
            feed("<thi", "nk>checking ", "disk usage</thi", "nk>", "Use df -h.");

        for (ThinkTagStreamSplitter.Split split : seen) {
            assertThat(split.content()).doesNotContain("<");
            assertThat(split.content()).doesNotContain("checking");
        }
        assertThat(seen.get(0).content()).isEmpty();
        assertThat(seen.get(2).reasoning()).isEqualTo("checking disk usage");
        ThinkTagStreamSplitter.Split last = seen.get(seen.size() - 1);
        assertThat(last.content()).isEqualTo("Use df -h.");
        assertThat(last.reasoning()).isEqualTo("checking disk usage");
    }

    @Test
    void harmonyChannelsAreSplitIntoReasoningAndFinalAnswer() {
        List<ThinkTagStreamSplitter.Split> seen = feed(
            "<|channel|>analy",
            "sis<|message|>user wants uptime",
            "<|end|><|start|>assistant<|channel|>final<|message|>Run upt",
            "ime.<|ret",
            "urn|>");

        for (ThinkTagStreamSplitter.Split split : seen) {
            assertThat(split.content()).doesNotContain("user wants");
            assertThat(split.content()).doesNotContain("<|");
        }
        assertThat(seen.get(0).content()).isEmpty();
        assertThat(seen.get(1).reasoning()).isEqualTo("user wants uptime");
        assertThat(seen.get(3).content()).isEqualTo("Run uptime.");
        assertThat(seen.get(seen.size() - 1).content()).isEqualTo("Run uptime.");
    }

    @Test
    void midTextThinkTagIsAnswerTextNotReasoning() {
        List<ThinkTagStreamSplitter.Split> seen =
            feed("Wrap it in ", "<think>", " tags like <think>x</think>.");

        ThinkTagStreamSplitter.Split last = seen.get(seen.size() - 1);
        assertThat(last.content()).isEqualTo("Wrap it in <think> tags like <think>x</think>.");
        assertThat(last.reasoning()).isEmpty();
    }

    @Test
    void transportReasoningIsKeptAndMergedWithInlineThoughts() {
        ThinkTagStreamSplitter.Split split =
            ThinkTagStreamSplitter.split("<think>inline</think>answer", "from the field");

        assertThat(split.content()).isEqualTo("answer");
        assertThat(split.reasoning()).isEqualTo("from the field\n\ninline");
        assertThat(ThinkTagStreamSplitter.split("<html>", null).content()).isEqualTo("<html>");
        assertThat(ThinkTagStreamSplitter.split(null, null)).isEqualTo(new ThinkTagStreamSplitter.Split("", ""));
    }

    @Test
    void restartAndCompleteArePassedThroughAndWrapIsIdempotent() {
        List<String> events = new ArrayList<>();
        AiStreamListener inner = new AiStreamListener() {
            @Override
            public void onProgress(String contentSoFar, String reasoningSoFar) {
                events.add("progress");
            }

            @Override
            public void onRestart() {
                events.add("restart");
            }

            @Override
            public void onComplete() {
                events.add("complete");
            }
        };
        AiStreamListener wrapped = ThinkTagStreamSplitter.wrap(inner);

        wrapped.onRestart();
        wrapped.onComplete();

        assertThat(events).containsExactly("restart", "complete").inOrder();
        assertThat(ThinkTagStreamSplitter.wrap(wrapped)).isSameInstanceAs(wrapped);
        assertThat(ThinkTagStreamSplitter.wrap(null)).isNull();
    }
}
