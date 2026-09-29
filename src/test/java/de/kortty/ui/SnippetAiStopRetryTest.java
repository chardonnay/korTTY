package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

/** Retry repeats exactly the stopped request, and refuses when the text it worked on changed. */
public class SnippetAiStopRetryTest {

    @Test
    public void selectionRetryStaysAvailableOnlyWhileTheRangeHoldsTheSameText() {
        String content = "#!/bin/bash\nname=$1\necho \"$name\"\n";
        int start = content.indexOf("name=$1");
        int end = start + "name=$1".length();

        assertThat(SnippetEditDialog.selectionStillMatches(content, start, end, "name=$1")).isTrue();
        // Edited elsewhere: the range still holds the same text.
        assertThat(SnippetEditDialog.selectionStillMatches(content + "# more\n", start, end, "name=$1")).isTrue();
        // The selected text itself changed, or moved.
        assertThat(SnippetEditDialog.selectionStillMatches(content.replace("name=$1", "name=$2"), start, end,
            "name=$1")).isFalse();
        assertThat(SnippetEditDialog.selectionStillMatches("# x\n" + content, start, end, "name=$1")).isFalse();
        // The snippet got shorter than the range.
        assertThat(SnippetEditDialog.selectionStillMatches("#!/bin", start, end, "name=$1")).isFalse();
        // A caret (empty range) only needs to still exist.
        assertThat(SnippetEditDialog.selectionStillMatches(content, 5, 5, "")).isTrue();
        assertThat(SnippetEditDialog.selectionStillMatches(null, 0, 0, null)).isTrue();
    }

    @Test
    public void diagramScopeIsCutLikeTheOriginalSelection() {
        String content = "line1\nline2\nline3\nline4\n";

        assertThat(SnippetEditDialog.diagramScopeText(content, 2, 3)).isEqualTo("line2\nline3");
        assertThat(SnippetEditDialog.diagramScopeText(content, 4, 4)).isEqualTo("line4");
        assertThat(SnippetEditDialog.diagramScopeText(content, 0, 2)).isEmpty();
        assertThat(SnippetEditDialog.diagramScopeText(content, 3, 2)).isEmpty();
        assertThat(SnippetEditDialog.diagramScopeText("line2\nline3", 2, 3)).isEqualTo("line3");
    }

    @Test
    public void retryRunsTheCapturedActionAndReportsWhyItIsBlocked() {
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<String> blocked = new AtomicReference<>();
        SnippetEditDialog.AiRetry retry = new SnippetEditDialog.AiRetry(runs::incrementAndGet, blocked::get);

        assertThat(retry.blockedReason()).isNull();
        retry.action().run();
        assertThat(runs.get()).isEqualTo(1);
        blocked.set("changed");
        assertThat(retry.blockedReason()).isEqualTo("changed");
        assertThat(new SnippetEditDialog.AiRetry(() -> { }, null).blockedReason()).isNull();
    }

    @Test
    public void elapsedTimeReadsLikeAClock() {
        assertThat(AiStopRetrySupport.formatElapsed(0)).isEqualTo("0:00");
        assertThat(AiStopRetrySupport.formatElapsed(7_900)).isEqualTo("0:07");
        assertThat(AiStopRetrySupport.formatElapsed(750_000)).isEqualTo("12:30");
        assertThat(AiStopRetrySupport.formatElapsed(3_723_000)).isEqualTo("1:02:03");
        assertThat(AiStopRetrySupport.formatElapsed(-5)).isEqualTo("0:00");
    }
}
