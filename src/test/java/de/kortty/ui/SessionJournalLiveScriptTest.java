package de.kortty.ui;

import de.kortty.core.SessionJournalLogEntry;
import org.testng.annotations.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalLiveScriptTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final OffsetDateTime BASE =
        OffsetDateTime.of(2026, 8, 13, 12, 0, 1, 0, ZoneOffset.UTC);

    private static SessionJournalLogEntry entry(long seq, SessionJournalLogEntry.Kind kind,
                                                String text, boolean redacted, String file) {
        return new SessionJournalLogEntry(seq, BASE, kind, text, redacted, false, file);
    }

    @Test
    void emptyBatchIsANoOp() {
        assertThat(SessionJournalLiveScript.appendLogCall(List.of(), UTC, "(hidden)", "Screenshot"))
            .isEmpty();
        assertThat(SessionJournalLiveScript.appendLogCall(null, UTC, "(hidden)", "Screenshot"))
            .isEmpty();
    }

    @Test
    void mapsKindsExactlyLikeThePage() {
        String script = SessionJournalLiveScript.appendLogCall(List.of(
            entry(1, SessionJournalLogEntry.Kind.OUT, "output line", false, null),
            entry(2, SessionJournalLogEntry.Kind.IN, "ls -la", false, null),
            entry(3, SessionJournalLogEntry.Kind.SEED, "seeded", false, null),
            entry(4, SessionJournalLogEntry.Kind.NOTE, "a note", false, null),
            entry(5, SessionJournalLogEntry.Kind.SCREENSHOT, "", false, "screenshots/shot-000005.png")),
            UTC, "(hidden)", "Screenshot");

        assertThat(script).startsWith("if(window.korttyAppendLog){window.korttyAppendLog([");
        assertThat(script).endsWith("]);}");
        assertThat(script).contains("{s:1,t:\"12:00:01\",k:\"o\",x:\"output line\"}");
        assertThat(script).contains("{s:2,t:\"12:00:01\",k:\"i\",x:\"ls -la\"}");
        assertThat(script).contains("{s:3,t:\"12:00:01\",k:\"o\",x:\"seeded\"}");
        assertThat(script).contains("{s:4,t:\"12:00:01\",k:\"n\",x:\"a note\"}");
        assertThat(script).contains("k:\"s\",x:\"Screenshot screenshots/shot-000005.png\"");
    }

    @Test
    void repeatCountIsEmittedOnlyWhenCoalesced() {
        String script = SessionJournalLiveScript.appendLogCall(List.of(
            new SessionJournalLogEntry(10, BASE, SessionJournalLogEntry.Kind.OUT,
                "retrying", false, false, null, 3),
            entry(11, SessionJournalLogEntry.Kind.OUT, "single", false, null)),
            UTC, "(hidden)", "Screenshot");
        assertThat(script).contains("{s:10,t:\"12:00:01\",k:\"o\",x:\"retrying\",r:3}");
        // The non-coalesced literal keeps its historical shape — no r key at repeat 1.
        assertThat(script).contains("{s:11,t:\"12:00:01\",k:\"o\",x:\"single\"}");
    }

    @Test
    void redactedInputShowsThePlaceholderNeverTheText() {
        String script = SessionJournalLiveScript.appendLogCall(List.of(
            entry(7, SessionJournalLogEntry.Kind.IN, "", true, null)),
            UTC, "(verborgene Eingabe)", "Screenshot");
        assertThat(script).contains("x:\"(verborgene Eingabe)\"");
    }

    @Test
    void escapesEverythingThatCouldBreakTheScript() {
        String nasty = "quote\" backslash\\ newline\n tab\t sep\u2028\u2029 emoji😀 </script>";
        String script = SessionJournalLiveScript.appendLogCall(List.of(
            entry(9, SessionJournalLogEntry.Kind.OUT, nasty, false, null)),
            UTC, "(hidden)", "Screenshot");
        // No raw specials may survive into the literal.
        assertThat(script).doesNotContain("\n");
        assertThat(script).doesNotContain("\u2028");
        assertThat(script).doesNotContain("\u2029");
        assertThat(script).doesNotContain("</script>");
        assertThat(script).contains("\\n");
    }
}
