package de.kortty.core;

import de.kortty.core.PdfReportKit.Fonts;
import de.kortty.core.PdfReportKit.OutlineNode;
import de.kortty.core.PdfReportKit.PageFlow;
import de.kortty.core.PdfReportKit.Row;
import de.kortty.core.PdfReportKit.SegmentChrome;
import de.kortty.core.PdfReportKit.TextRun;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.annotations.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

class PdfReportKitTest {

    private static final List<String> CATEGORIES = List.of("security", "optimization", "design", "dependencies");

    @Test
    void wrapParagraphKeepsBlankLinesAndRepeatsIndentationOnContinuations() throws Exception {
        try (PDDocument document = new PDDocument()) {
            Fonts fonts = PdfReportKit.loadFonts(document);
            String text = "First paragraph line.\n\n"
                + "    - an indented bullet whose text is long enough to wrap onto more than one line in a narrow column\n"
                + "\tTabbed step that also runs long enough to need a second line here";

            List<String> lines = PdfReportKit.wrapParagraph(text, fonts.sans(), 10f, 150f);

            assertThat(lines.get(0)).isEqualTo("First paragraph line.");
            assertThat(lines.get(1)).isEmpty();
            List<String> bullet = lines.subList(2, lines.size());
            assertThat(bullet.size()).isAtLeast(4);
            int tabbedStart = -1;
            for (int index = 0; index < bullet.size(); index++) {
                String line = bullet.get(index);
                assertWithMessage("line %s: '%s'", index, line).that(line).startsWith("    ");
                assertWithMessage("line %s: '%s'", index, line)
                    .that(PdfReportKit.textWidth(fonts.sans(), 10f, line)).isAtMost(150f);
                if (line.startsWith("    Tabbed")) {
                    tabbedStart = index;
                }
            }
            assertThat(bullet.getFirst()).startsWith("    - an indented");
            // The tab became four spaces and its continuation lines share the hanging indent.
            assertThat(tabbedStart).isGreaterThan(1);
            assertThat(bullet.get(tabbedStart + 1)).startsWith("    ");
            assertThat(bullet.get(tabbedStart + 1)).doesNotContain("Tabbed");

            // Empty and null input still yield one (empty) line so callers can measure a height.
            assertThat(PdfReportKit.wrapParagraph("", fonts.sans(), 10f, 150f)).containsExactly("");
            assertThat(PdfReportKit.wrapParagraph(null, fonts.sans(), 10f, 150f)).containsExactly("");
        }
    }

    @Test
    void wrapParagraphHardBreaksAnOverLongTokenThatOpensALine() throws Exception {
        try (PDDocument document = new PDDocument()) {
            Fonts fonts = PdfReportKit.loadFonts(document);
            String url = "https://example.invalid/" + "segment/".repeat(22) + "index.html";
            assertThat(url.length()).isAtLeast(200);
            String text = url + " is the endpoint\n    - " + url;

            List<String> lines = PdfReportKit.wrapParagraph(text, fonts.sans(), 10f, 150f);

            assertThat(lines.size()).isAtLeast(4);
            for (String line : lines) {
                assertWithMessage("'%s'", line).that(PdfReportKit.textWidth(fonts.sans(), 10f, line)).isAtMost(150f);
            }
            assertThat(String.join("", lines).replace(" ", "")).contains(url.replace(" ", ""));
            assertThat(lines.getFirst()).startsWith("https://example.invalid/");
        }
    }

    @Test
    void prepareTextUsesFallbackFontsForSymbolsInsteadOfQuestionMarks() throws Exception {
        try (PDDocument document = new PDDocument()) {
            Fonts fonts = PdfReportKit.loadFonts(document);
            String symbols = "→ ✓ ≥ ≤ ⚠ ⌘";

            String prepared = PdfReportKit.prepareText(fonts.sans(), symbols);
            assertThat(prepared).doesNotContain("?");
            // Arrows, check marks and the warning sign come from the symbol fonts; the math
            // operators no bundled face has are spelled out rather than lost.
            assertThat(prepared).isEqualTo("→ ✓ >= <= ⚠ ⌘");
            assertThat(PdfReportKit.prepareText(fonts.mono(), symbols)).doesNotContain("?");
            assertThat(PdfReportKit.prepareText(fonts.bold(), "Größe ✓ ≠ ⇒")).isEqualTo("Größe ✓ != =>");
            // A glyph nothing covers is still a '?', never an exception.
            assertThat(PdfReportKit.prepareText(fonts.sans(), "∀x")).isEqualTo("?x");

            List<TextRun> runs = PdfReportKit.splitFontRuns(fonts.sans(), prepared);
            assertThat(runs.size()).isAtLeast(3);
            boolean usedFallback = runs.stream().anyMatch(run -> run.font() != fonts.sans().primary());
            assertThat(usedFallback).isTrue();
            assertThat(PdfReportKit.fontForGlyph(fonts.sans(), "→")).isNotEqualTo(fonts.sans().primary());
            assertThat(PdfReportKit.fontForGlyph(fonts.sans(), "a")).isEqualTo(fonts.sans().primary());
            assertThat(PdfReportKit.fontForGlyph(fonts.sans(), "∀")).isNull();
            assertThat(String.join("", runs.stream().map(TextRun::text).toList())).isEqualTo(prepared);
            assertThat(PdfReportKit.textWidth(fonts.sans(), 10f, symbols)).isGreaterThan(0f);

            // Control characters vanish, whitespace and the emoji joiner are handled, not drawn as '?'.
            assertThat(PdfReportKit.prepareText(fonts.sans(), "a\u0007b\r\nc\td")).isEqualTo("ab\nc\td");
            assertThat(PdfReportKit.prepareText(fonts.sans(), "👍️")).doesNotContain("?");
        }
    }

    @Test
    void fitTruncatesWithAnEllipsisOnlyWhenNeeded() throws Exception {
        try (PDDocument document = new PDDocument()) {
            Fonts fonts = PdfReportKit.loadFonts(document);
            String title = "A rather long script name that will never fit into eighty points of width";

            String fitted = PdfReportKit.fit(title, fonts.bold(), 12f, 80f);
            assertThat(fitted).endsWith("…");
            assertThat(fitted.length()).isLessThan(title.length());
            assertThat(fitted).startsWith("A rather");
            assertThat(PdfReportKit.textWidth(fonts.bold(), 12f, fitted)).isAtMost(80f);

            assertThat(PdfReportKit.fit("short", fonts.bold(), 12f, 200f)).isEqualTo("short");
            // Even a hopelessly narrow box yields the ellipsis alone rather than an exception.
            assertThat(PdfReportKit.fit(title, fonts.bold(), 12f, 1f)).isEqualTo("…");
        }
    }

    @Test
    void wrapCodeAndBreakLongTokenAreCodePointSafe() throws Exception {
        try (PDDocument document = new PDDocument()) {
            Fonts fonts = PdfReportKit.loadFonts(document);
            String code = "if [ -z \"$1\" ]; then\n\techo usage\n\nfi";
            List<String> lines = PdfReportKit.wrapCode(code, fonts.mono(), 9f, 300f);
            assertThat(lines).containsExactly("if [ -z \"$1\" ]; then", "    echo usage", "", "fi").inOrder();

            String emoji = "👍".repeat(40);
            List<String> parts = PdfReportKit.breakLongToken(emoji, fonts.mono(), 9f, 60f);
            assertThat(parts.size()).isGreaterThan(1);
            for (String part : parts) {
                assertThat(part.codePointCount(0, part.length()) * 2).isEqualTo(part.length());
            }
            assertThat(String.join("", parts)).isEqualTo(emoji);
        }
    }

    @Test
    void fillSvgPathHandlesEveryCategoryGlyph() throws Exception {
        for (String category : CATEGORIES) {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    PdfReportKit.fillSvgPath(stream, AnalysisCategoryVisuals.iconPath(category), 100f, 700f, 14f,
                        Color.decode(AnalysisCategoryVisuals.printColorHex(category)));
                }
                String content = new String(page.getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
                assertWithMessage(category).that(content).contains("f*");
                assertWithMessage(category).that(content).contains(" m\n");
                assertWithMessage(category).that(content).contains(" l\n");
                assertWithMessage(category).that(content).contains("h\n");
                // The padlock and the gauge are drawn with arcs, which must become cubics.
                if ("security".equals(category) || "optimization".equals(category)) {
                    assertWithMessage(category).that(content).contains(" c\n");
                } else {
                    assertWithMessage(category).that(content).doesNotContain(" c\n");
                }
            }
        }
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                // Relative commands and a full circle drawn as two arcs.
                PdfReportKit.fillSvgPath(stream, "m2 2 h12 v12 h-12 z M8 4 a4 4 0 1 0 0.01 0 z", 10f, 10f, 16f, Color.BLACK);
                expectThrows(IllegalArgumentException.class,
                    () -> PdfReportKit.fillSvgPath(stream, "M1 1 C 2 2 3 3 4 4 Z", 10f, 10f, 16f, Color.BLACK));
                expectThrows(IllegalArgumentException.class,
                    () -> PdfReportKit.fillSvgPath(stream, "M1 1 L 2", 10f, 10f, 16f, Color.BLACK));
            }
        }
    }

    @Test
    void pageFlowKeepsBlocksTogetherAndSplitsOnlyWhatCannotFitAPage() throws Exception {
        try (PDDocument document = new PDDocument()) {
            List<String> drawn = new ArrayList<>();
            List<String> chrome = new ArrayList<>();
            SegmentChrome frame = new SegmentChrome() {
                @Override
                public float headerHeight(boolean continued) {
                    return continued ? 30f : 20f;
                }

                @Override
                public float footerHeight() {
                    return 10f;
                }

                @Override
                public void paint(PageFlow flow, float topY, float height, boolean continued) {
                    chrome.add(pageIndex(flow) + ":" + (continued ? "continued" : "first") + ":" + Math.round(height));
                }
            };

            try (PageFlow flow = new PageFlow(document, PDRectangle.A4, 72f, 62f, 48f)) {
                float pageHeight = flow.pageContentHeight();
                assertThat(pageHeight).isWithin(0.01f).of(PDRectangle.A4.getHeight() - 72f - 62f);
                assertThat(flow.contentWidth()).isWithin(0.01f).of(PDRectangle.A4.getWidth() - 96f);
                assertThat(flow.atTop()).isTrue();
                assertThat(flow.available()).isWithin(0.01f).of(pageHeight);

                // Block A: 5 rows of 100 fit on the first page as one piece.
                flow.drawKeepTogether(rows("A", 5, 100f, drawn), frame);
                assertThat(pageIndex(flow)).isEqualTo(0);
                assertThat(flow.available()).isWithin(0.01f).of(pageHeight - 530f);

                // Block B: 4 rows of 100 (430 with chrome) do not fit the ~178 left but fit an empty page.
                flow.drawKeepTogether(rows("B", 4, 100f, drawn), frame);
                assertThat(pageIndex(flow)).isEqualTo(1);
                assertThat(flow.atTop()).isFalse();

                // Block C: 12 rows of 100 exceed any page and are split row by row across pages.
                flow.drawKeepTogether(rows("C", 12, 100f, drawn), frame);
                assertThat(pageIndex(flow)).isAtLeast(3);

                // Block D without chrome: fits the remaining space directly.
                flow.drawKeepTogether(rows("D", 1, 20f, drawn), null);
            }

            // Every row is painted exactly once, in order, and never straddles a page boundary.
            List<String> expectedOrder = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                expectedOrder.add("A" + index);
            }
            for (int index = 0; index < 4; index++) {
                expectedOrder.add("B" + index);
            }
            for (int index = 0; index < 12; index++) {
                expectedOrder.add("C" + index);
            }
            expectedOrder.add("D0");
            assertThat(drawn.stream().map(entry -> entry.substring(0, entry.indexOf('@'))).toList())
                .isEqualTo(expectedOrder);
            assertThat(drawn.stream().filter(entry -> entry.startsWith("A")).map(PdfReportKitTest::pageOf).distinct().toList())
                .containsExactly(0);
            assertThat(drawn.stream().filter(entry -> entry.startsWith("B")).map(PdfReportKitTest::pageOf).distinct().toList())
                .containsExactly(1);
            List<Integer> cPages = drawn.stream().filter(entry -> entry.startsWith("C")).map(PdfReportKitTest::pageOf).toList();
            assertThat(cPages.getFirst()).isEqualTo(1);
            assertThat(cPages.getLast()).isAtLeast(3);
            for (int index = 1; index < cPages.size(); index++) {
                assertThat(cPages.get(index)).isAtLeast(cPages.get(index - 1));
            }

            // Chrome: A and B once each ("first"), C once as "first" and then "continued" per page.
            assertThat(chrome.get(0)).isEqualTo("0:first:530");
            assertThat(chrome.get(1)).isEqualTo("1:first:430");
            assertThat(chrome.get(2)).startsWith("1:first:");
            List<String> continued = chrome.subList(3, chrome.size());
            assertThat(continued).isNotEmpty();
            for (String entry : continued) {
                assertThat(entry).contains(":continued:");
            }
            assertThat(document.getNumberOfPages()).isEqualTo(cPages.getLast() + 1);
        }
    }

    @Test
    void pageFlowDrawsARowTallerThanAPageInsteadOfLoopingForever() throws Exception {
        try (PDDocument document = new PDDocument()) {
            List<String> drawn = new ArrayList<>();
            try (PageFlow flow = new PageFlow(document, PDRectangle.A4, 72f, 62f, 48f)) {
                flow.advance(100f);
                flow.drawKeepTogether(List.of(
                    new Row(1000f, (f, top) -> drawn.add("tall@" + pageIndex(f))),
                    new Row(50f, (f, top) -> drawn.add("after@" + pageIndex(f)))), null);
                assertThat(flow.ensureSpace(10f)).isFalse();
                assertThat(flow.ensureSpace(1000f)).isTrue();
                assertThat(flow.atTop()).isTrue();
            }
            assertThat(drawn).containsExactly("tall@1", "after@2").inOrder();
            assertThat(document.getNumberOfPages()).isEqualTo(4);
        }
    }

    @Test
    void generatedMultiPageReportOpensWithChromeOutlineMetadataAndLinks() throws Exception {
        Path file = Files.createTempFile("pdf-report-kit-", ".pdf");
        try {
            ExportBranding branding = new ExportBranding(true, "CONFIDENTIAL",
                ExportBranding.DEFAULT_WATERMARK_COLOR, true, "ACME internal", false);
            Instant exportedAt = Instant.parse("2026-09-28T10:30:00Z");
            PDPage firstPage;
            PDPage secondPage;
            try (PDDocument document = new PDDocument()) {
                Fonts fonts = PdfReportKit.loadFonts(document);
                try (PageFlow flow = new PageFlow(document, PDRectangle.A4, 72f, 62f, 48f)) {
                    firstPage = flow.page();
                    PdfReportKit.roundRect(flow.stream(), 48f, flow.cursor() - 60f, flow.contentWidth(), 60f, 10f,
                        new Color(0x00, 0x66, 0xCC), null, 0f);
                    PdfReportKit.drawText(flow.stream(), fonts.bold(), 20f, Color.WHITE, 66f, flow.cursor() - 36f,
                        "Cover Marker deploy_v2.sh → ✓");
                    flow.advance(80f);
                    float pillWidth = PdfReportKit.pill(flow.stream(), fonts.bold(), 8f, "CRITICAL",
                        Color.decode(AnalysisSeverity.CRITICAL.printColorHex()), Color.WHITE, 48f, flow.cursor());
                    assertThat(pillWidth).isGreaterThan(30f);
                    PdfReportKit.fillSvgPath(flow.stream(), AnalysisCategoryVisuals.iconPath("security"),
                        48f + pillWidth + 8f, flow.cursor() - 3f, 12f, Color.RED);
                    PdfReportKit.line(flow.stream(), 48f, flow.cursor() - 10f, 300f, flow.cursor() - 10f,
                        PdfReportKit.RULE, 0.7f);
                    PdfReportKit.strokeRect(flow.stream(), 48f, flow.cursor() - 40f, 100f, 20f, Color.GRAY, 0.5f);
                    PdfReportKit.fillRect(flow.stream(), 160f, flow.cursor() - 40f, 100f, 20f, Color.LIGHT_GRAY);
                    flow.advance(60f);

                    BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
                    List<BufferedImage> strips = PdfReportKit.sliceVertically(image, 120);
                    assertThat(strips).hasSize(3);
                    assertThat(strips.get(2).getHeight()).isEqualTo(60);
                    assertThat(PdfReportKit.sliceVertically(image, 0)).containsExactly(image);
                    PdfReportKit.drawImage(document, flow.stream(), strips.getFirst(), 48f, flow.cursor() - 90f, 120f, 36f);
                    flow.advance(100f);

                    flow.newPage(null);
                    secondPage = flow.page();
                    PdfReportKit.drawText(flow.stream(), fonts.sans(), 11f, Color.BLACK, 48f, flow.cursor(),
                        "Second Marker with symbols ≥ ✓");
                    flow.advance(20f);
                    flow.newPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                    assertThat(flow.contentWidth()).isWithin(0.01f).of(PDRectangle.A4.getHeight() - 96f);
                    PdfReportKit.drawText(flow.stream(), fonts.mono(), 11f, Color.BLACK, 48f, flow.cursor(),
                        "Landscape Marker");
                }
                PdfReportKit.addGoToLink(firstPage, new PDRectangle(48f, 400f, 200f, 12f), secondPage, 780f);
                PdfReportKit.applyPageChrome(document, fonts, branding,
                    new PdfReportKit.ChromeText("AI Code Analysis — deploy_v2.sh", "Before applying · 28.09.2026"),
                    true, "ai.result.export.pdf.pageNumber", 48f);
                PdfReportKit.applyOutline(document, "AI Code Analysis — deploy_v2.sh", List.of(
                    new OutlineNode("Overview", firstPage, 780f),
                    new OutlineNode("Security (2)", secondPage, 780f, List.of(
                        new OutlineNode("SEC-1 Unquoted variable", secondPage, 700f),
                        new OutlineNode("SEC-2 eval on input", secondPage, 600f)))));
                PdfReportKit.applyMetadata(document, new PdfReportKit.Metadata(
                    "AI Code Analysis — deploy_v2.sh", "Report before applying", "korTTY, code analysis, bash",
                    "korTTY 9.9.9", "korTTY", exportedAt, Locale.GERMAN));
                document.save(file.toFile());
            }

            try (PDDocument document = Loader.loadPDF(file.toFile())) {
                assertThat(document.getNumberOfPages()).isEqualTo(3);
                assertThat(document.getPage(2).getMediaBox().getWidth())
                    .isGreaterThan(document.getPage(2).getMediaBox().getHeight());

                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                assertThat(text).contains("Cover Marker deploy_v2.sh → ✓");
                assertThat(text).contains("Second Marker with symbols >= ✓");
                assertThat(text).contains("Landscape Marker");
                assertThat(text).contains("CRITICAL");
                assertThat(text).doesNotContain("?");
                assertThat(text).contains("ACME internal");
                assertThat(text).doesNotContain("Created with korTTY");
                assertThat(text).contains("Page 1 of 3");
                assertThat(text).contains("Page 3 of 3");
                // The diagonal watermark is on every page; a custom text carries no repository URL.
                assertThat(text.split("CONFIDENTIAL", -1).length - 1).isEqualTo(3);
                assertThat(text).doesNotContain(ExportBranding.REPOSITORY_URL);

                // The continuation header is skipped on the cover and present afterwards.
                stripper.setStartPage(1);
                stripper.setEndPage(1);
                assertThat(stripper.getText(document)).doesNotContain("Before applying · 28.09.2026");
                stripper.setStartPage(2);
                stripper.setEndPage(2);
                String second = stripper.getText(document);
                assertThat(second).contains("AI Code Analysis — deploy_v2.sh");
                assertThat(second).contains("Before applying · 28.09.2026");
                assertThat(second).contains("Page 2 of 3");

                PDOutlineItem root = (PDOutlineItem) document.getDocumentCatalog().getDocumentOutline().getFirstChild();
                assertThat(root.getTitle()).isEqualTo("AI Code Analysis — deploy_v2.sh");
                List<String> topLevel = new ArrayList<>();
                PDOutlineItem security = null;
                for (PDOutlineItem item : root.children()) {
                    topLevel.add(item.getTitle());
                    if (item.getTitle().startsWith("Security")) {
                        security = item;
                    }
                }
                assertThat(topLevel).containsExactly("Overview", "Security (2)").inOrder();
                assertThat(security).isNotNull();
                List<String> findings = new ArrayList<>();
                for (PDOutlineItem item : security.children()) {
                    findings.add(item.getTitle());
                }
                assertThat(findings).containsExactly("SEC-1 Unquoted variable", "SEC-2 eval on input").inOrder();
                assertThat(document.getDocumentCatalog().getPageMode()).isEqualTo(PageMode.USE_OUTLINES);

                assertThat(document.getDocumentInformation().getTitle()).isEqualTo("AI Code Analysis — deploy_v2.sh");
                assertThat(document.getDocumentInformation().getSubject()).isEqualTo("Report before applying");
                assertThat(document.getDocumentInformation().getKeywords()).contains("code analysis");
                assertThat(document.getDocumentInformation().getCreator()).isEqualTo("korTTY 9.9.9");
                assertThat(document.getDocumentInformation().getProducer()).isEqualTo("korTTY");
                assertThat(document.getDocumentInformation().getCreationDate().toInstant()).isEqualTo(exportedAt);
                assertThat(document.getDocumentCatalog().getLanguage()).isEqualTo("de");
                assertThat(document.getDocumentCatalog().getViewerPreferences().displayDocTitle()).isTrue();

                // The GoTo link on the cover survives, next to the footer's repository link.
                List<PDAnnotation> annotations = document.getPage(0).getAnnotations();
                long links = annotations.stream().filter(annotation -> annotation instanceof PDAnnotationLink).count();
                assertThat(links).isAtLeast(1);
                assertThat(annotations.stream().anyMatch(annotation -> annotation instanceof PDAnnotationLink link
                    && link.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo)).isTrue();
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static List<Row> rows(String prefix, int count, float height, List<String> drawn) {
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String name = prefix + index;
            rows.add(new Row(height, (flow, top) -> {
                assertThat(top - height).isAtLeast(flow.bottom() - 0.01f);
                drawn.add(name + "@" + pageIndex(flow));
            }));
        }
        return rows;
    }

    private static int pageIndex(PageFlow flow) {
        return flow.document().getPages().indexOf(flow.page());
    }

    private static int pageOf(String entry) {
        return Integer.parseInt(entry.substring(entry.indexOf('@') + 1));
    }
}
