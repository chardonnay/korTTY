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
    void headerNeverRepeatsAConnectionTheTitleAlreadyNames() {
        SessionJournalMeta meta = document.getMeta();
        meta.setTitle("daniel@10.211.55.5 — 2026-08-03 10:09");
        meta.setConnectionName("daniel@10.211.55.5");
        meta.setUsername("daniel");
        meta.setHost("10.211.55.5");
        meta.setPort(22);
        String html = renderer.render(document, List.of());
        // The endpoint appears once — in the h1 — and the subtitle line is dropped entirely.
        assertThat(html).doesNotContain("class=\"conn\"");
        assertThat(html).contains("<h1>daniel@10.211.55.5 — 2026-08-03 10:09</h1>");
    }

    @Test
    void liveBadgeSurvivesADroppedSubtitle() {
        SessionJournalMeta meta = document.getMeta();
        meta.setTitle("daniel@10.211.55.5 — 2026-08-03 10:09");
        meta.setConnectionName("daniel@10.211.55.5");
        meta.setUsername("daniel");
        meta.setHost("10.211.55.5");
        meta.setEndedAt(null);
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
    void contextMenuOffersEveryCopyAction() {
        document.getEntries().add(summaryEntry());
        String html = renderer.render(document, sampleLog());
        assertThat(html).contains("id=\"ctxMenu\"");
        assertThat(html).contains("id=\"ctxSelection\"");
        assertThat(html).contains("id=\"ctxSummary\"");
        assertThat(html).contains("id=\"ctxEntry\"");
        assertThat(html).contains("id=\"ctxScreenshot\"");
        assertThat(html).contains("id=\"ctxPath\"");
        assertThat(html).contains("id=\"ctxLog\"");
        assertThat(html).contains("id=\"toast\"");
        // Copying goes through the app bridge first and degrades to the clipboard API/execCommand.
        assertThat(html).contains("korttyJournal");
        assertThat(html).contains("execCommand");
    }

    @Test
    void fontSizeControlsScaleThePageAndPersist() {
        String html = renderer.render(document, List.of());
        assertThat(html).contains("id=\"fontSmaller\"");
        assertThat(html).contains("id=\"fontLarger\"");
        assertThat(html).contains("id=\"fontReset\"");
        assertThat(html).contains("--font-scale:1.0");
        assertThat(html).contains("font-size:calc(15px * var(--font-scale))");
        assertThat(html).contains("kortty-journal-font-scale");
        assertThat(html).contains("fontScaleChanged");
    }

    @Test
    void bakedFontScaleComesFromTheSuppliedSetting() {
        renderer.setFontScaleSupplier(() -> 140);
        assertThat(renderer.render(document, List.of())).contains("--font-scale:1.4");
        // Out-of-range values are clamped rather than trusted.
        renderer.setFontScaleSupplier(() -> 5000);
        assertThat(renderer.render(document, List.of())).contains("--font-scale:2.5");
    }

    @Test
    void sizesAreViewportRelative() {
        String html = renderer.render(document, List.of());
        assertThat(html).contains("max-width:min(560px,100%)");   // screenshot thumbnails
        assertThat(html).contains("max-height:min(340px,34vh)");  // excerpt panels
        assertThat(html).contains("max-width:min(1200px,94vw)");  // timeline column
        assertThat(html).contains("height:clamp(200px,44vh,60vh)"); // log panel
    }

    @Test
    void journalWideSearchBarIsPresentButHidden() {
        document.getEntries().add(summaryEntry());
        String html = renderer.render(document, sampleLog());
        assertThat(html).contains("id=\"searchToggle\"");
        assertThat(html).contains("id=\"searchBar\" class=\"search-bar\" hidden");
        assertThat(html).contains("id=\"journalSearch\"");
        assertThat(html).contains("id=\"journalMatchCount\"");
        assertThat(html).contains("id=\"journalPrev\"");
        assertThat(html).contains("id=\"journalNext\"");
        assertThat(html).contains("id=\"journalSearchClose\"");
        // Highlighting wraps text nodes so the cards keep their listeners.
        assertThat(html).contains("createTreeWalker");
        assertThat(html).contains("mark.gs");
    }

    @Test
    void everyEntryCarriesCopyButtons() {
        SessionJournalEntry shot = new SessionJournalEntry();
        shot.setKind(SessionJournalEntryKind.SCREENSHOT);
        shot.setScreenshotFile("screenshots/shot-000004.png");
        shot.setCreatedAt(OffsetDateTime.of(2026, 8, 3, 14, 21, 0, 0, ZoneOffset.ofHours(2)));
        document.getEntries().add(summaryEntry());
        document.getEntries().add(shot);
        String html = renderer.render(document, sampleLog());

        assertThat(html).contains("class=\"card-actions\"");
        assertThat(html).contains("data-copy=\"text\"");
        assertThat(html).contains("data-copy=\"image\"");
        // The screenshot entry has no text of its own, so it only offers the image button.
        assertThat(countOccurrences(html, "data-copy=\"text\"")).isEqualTo(1);
        assertThat(countOccurrences(html, "data-copy=\"image\"")).isEqualTo(1);
        // The log panel can be copied too.
        assertThat(html).contains("id=\"copyLog\"");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
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
        // Regression guard: without this rule the hidden lightbox overlay covers the whole
        // page (display:flex beats the UA [hidden] rule) and blocks every click.
        assertThat(html).contains(".lightbox[hidden]{display:none}");
    }
}
