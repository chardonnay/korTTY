package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalHtmlRendererTest {

    private SessionJournalHtmlRenderer renderer;
    private SessionJournalDocument document;

    @BeforeMethod
    void setUp() {
        renderer = new SessionJournalHtmlRenderer(new SessionJournalService());
        document = new SessionJournalDocument();
        SessionJournalMeta meta = document.getMeta();
        meta.setTitle("web01 <maintenance> & \"deploy\"");
        meta.setUsername("daniel");
        meta.setHost("192.168.1.50");
        meta.setPort(22);
        meta.setConnectionName("Production Web01");
        meta.setDescription("Nginx outage debugging");
        meta.setStartedAt(OffsetDateTime.of(2026, 8, 3, 14, 15, 2, 0, ZoneOffset.ofHours(2)));
        meta.setEndedAt(OffsetDateTime.of(2026, 8, 3, 15, 2, 44, 0, ZoneOffset.ofHours(2)));
        meta.setCommandCount(12);
        meta.setErrorCount(1);
        meta.setAppLanguageCode("de");
    }

    private SessionJournalEntry summaryEntry() {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
        entry.setTitle("Checked <nginx> status");
        entry.setText("Service is running & healthy.");
        entry.setMarker(SessionJournalMarker.IMPORTANT);
        entry.setCreatedAt(OffsetDateTime.of(2026, 8, 3, 14, 30, 0, 0, ZoneOffset.ofHours(2)));
        entry.setLogStartSeq(1L);
        entry.setLogEndSeq(4L);
        entry.setInputExcerpt(List.of("systemctl status nginx"));
        entry.setOutputExcerpt(List.of("Active: active (running)"));
        return entry;
    }

    private List<SessionJournalLogEntry> sampleLog() {
        OffsetDateTime base = OffsetDateTime.of(2026, 8, 3, 14, 15, 3, 0, ZoneOffset.ofHours(2));
        return List.of(
            new SessionJournalLogEntry(1, base, SessionJournalLogEntry.Kind.IN,
                "systemctl status nginx", false, false, null),
            new SessionJournalLogEntry(2, base.plusSeconds(1), SessionJournalLogEntry.Kind.OUT,
                "Active: active (running) </script> <b>bold</b>", false, false, null),
            new SessionJournalLogEntry(3, base.plusSeconds(2), SessionJournalLogEntry.Kind.IN,
                "", true, false, null),
            new SessionJournalLogEntry(4, base.plusSeconds(3), SessionJournalLogEntry.Kind.SCREENSHOT,
                "", false, false, "screenshots/shot-000004.png"));
    }

    @Test
    void rendersSelfContainedPageWithHeaderAndTimeline() {
        document.getEntries().add(summaryEntry());
        String html = renderer.render(document, sampleLog());

        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("web01 &lt;maintenance&gt; &amp; &quot;deploy&quot;");
        assertThat(html).contains("daniel@192.168.1.50:22");
        assertThat(html).contains("Nginx outage debugging");
        assertThat(html).contains("Checked &lt;nginx&gt; status");
        assertThat(html).contains("badge-important");
        assertThat(html).contains("data-from=\"1\" data-to=\"4\"");
        assertThat(html).contains("id=\"entry-" + document.getEntries().get(0).getId() + "\"");
        // No external resources: no http(s) URLs outside of text content.
        assertThat(html).doesNotContain("<link ");
        assertThat(html).doesNotContain("src=\"http");
    }

    @Test
    void embedsLogDataAsEscapedJsArray() {
        String html = renderer.render(document, sampleLog());
        assertThat(html).contains("const LOG=[");
        // A literal </script> inside terminal text must not terminate the script element —
        // the JS string escaper turns "<" into \u003c, so the raw sequence never appears.
        assertThat(html).doesNotContain("</script> <b>");
        assertThat(html).contains("\\u003c/script\\u003e");
        assertThat(html).contains("k:\"i\"");
        assertThat(html).contains("(hidden input)");
    }

    @Test
    void referencesScreenshotsRelatively() {
        SessionJournalEntry shot = new SessionJournalEntry();
        shot.setKind(SessionJournalEntryKind.SCREENSHOT);
        shot.setScreenshotFile("screenshots/shot-000004.png");
        shot.setCreatedAt(OffsetDateTime.of(2026, 8, 3, 14, 21, 0, 0, ZoneOffset.ofHours(2)));
        document.getEntries().add(shot);
        String html = renderer.render(document, sampleLog());
        assertThat(html).contains("src=\"screenshots/shot-000004.png\"");
        assertThat(html).contains("class=\"thumb\"");
    }

    @Test
    void liveJournalShowsLiveBadge() {
        document.getMeta().setEndedAt(null);
        String html = renderer.render(document, List.of());
        assertThat(html).contains("live-badge");
    }

    @Test
    void rawStateGetsTagAndSessionSummaryGetsFinalStyling() {
        SessionJournalEntry raw = summaryEntry();
        raw.setState(SessionJournalEntry.State.RAW);
        SessionJournalEntry finalEntry = summaryEntry();
        finalEntry.setKind(SessionJournalEntryKind.SESSION_SUMMARY);
        finalEntry.setCreatedAt(OffsetDateTime.of(2026, 8, 3, 15, 0, 0, 0, ZoneOffset.ofHours(2)));
        document.getEntries().add(raw);
        document.getEntries().add(finalEntry);
        String html = renderer.render(document, List.of());
        assertThat(html).contains("state-tag");
        assertThat(html).contains("entry final");
    }

    @Test
    void panelAndSearchScaffoldingIsPresent() {
        String html = renderer.render(document, sampleLog());
        assertThat(html).contains("id=\"logPanel\"");
        assertThat(html).contains("id=\"logSearch\"");
        assertThat(html).contains("id=\"matchCount\"");
        assertThat(html).contains("id=\"prevMatch\"");
        assertThat(html).contains("id=\"nextMatch\"");
        assertThat(html).contains("prefers-color-scheme");
        assertThat(html).contains("@media print");
    }
}
