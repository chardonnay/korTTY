package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiSvgContentSupportTest {

    private static final String SIMPLE_SVG =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 50\"><rect width=\"100\" height=\"50\"/></svg>";

    @Test
    void detectsSvgFencedBlocks() {
        assertThat(AiSvgContentSupport.isSvgContent("svg", SIMPLE_SVG)).isTrue();
        assertThat(AiSvgContentSupport.isSvgContent("xml", SIMPLE_SVG)).isTrue();
        assertThat(AiSvgContentSupport.isSvgContent("html", SIMPLE_SVG)).isTrue();
        assertThat(AiSvgContentSupport.isSvgContent("", SIMPLE_SVG)).isTrue();
        assertThat(AiSvgContentSupport.isSvgContent(null, SIMPLE_SVG)).isTrue();
    }

    @Test
    void detectsSvgBehindXmlPrologAndComments() {
        String withProlog = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"svg11.dtd\">\n"
            + "<!-- generated -->\n"
            + SIMPLE_SVG;
        assertThat(AiSvgContentSupport.isSvgContent("xml", withProlog)).isTrue();
    }

    @Test
    void rejectsNonSvgContentAndLanguages() {
        assertThat(AiSvgContentSupport.isSvgContent("bash", "echo <svg>")).isFalse();
        assertThat(AiSvgContentSupport.isSvgContent("xml", "<project><svg/></project>")).isFalse();
        assertThat(AiSvgContentSupport.isSvgContent("svg", "")).isFalse();
        assertThat(AiSvgContentSupport.isSvgContent("svg", null)).isFalse();
        // Mentioning svg in text must not trigger image rendering.
        assertThat(AiSvgContentSupport.isSvgContent("", "Use an <svg> element for icons.")).isFalse();
    }

    @Test
    void sanitizeStripsScriptsEventHandlersAndJavascriptLinks() {
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\">"
            + "<script>alert(2)</script>"
            + "<a xlink:href=\"javascript:alert(3)\"><text onclick='alert(4)'>x</text></a>"
            + "<rect width=\"10\" height=\"10\"/></svg>";

        String sanitized = AiSvgContentSupport.sanitizeSvg(hostile);

        assertThat(sanitized).doesNotContain("<script");
        assertThat(sanitized).doesNotContain("onload");
        assertThat(sanitized).doesNotContain("onclick");
        assertThat(sanitized).doesNotContain("javascript:");
        assertThat(sanitized).contains("<rect width=\"10\" height=\"10\"/>");
    }

    @Test
    void sanitizeStripsExternalResourceReferencesButKeepsFragments() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">"
            + "<defs><linearGradient id=\"g\"/></defs>"
            + "<image href=\"http://evil.example/leak.png\" width=\"5\" height=\"5\"/>"
            + "<image href=relative.png width=\"5\" height=\"5\"/>"
            + "<use xlink:href=\"#g\"/>"
            + "<rect fill=\"url(#g)\" style=\"background:url(http://evil.example/x.png)\" width=\"10\" height=\"10\"/>"
            + "</svg>";

        String sanitized = AiSvgContentSupport.sanitizeSvg(svg);

        assertThat(sanitized).doesNotContain("http://evil.example");
        assertThat(sanitized).doesNotContain("relative.png");
        // Same-document fragment references (gradients, <use>) must survive.
        assertThat(sanitized).contains("xlink:href=\"#g\"");
        assertThat(sanitized).contains("fill=\"url(#g)\"");
    }

    @Test
    void sanitizeStripsFetchSinksFromSmuggledMarkup() {
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"5\" height=\"5\"/>"
            + "<foreignObject><iframe src=\"file:///etc/passwd\"></iframe>"
            + "<img srcset=\"https://evil.example/a.png 1x\" src=https://evil.example/b.png>"
            + "<object data=\"https://evil.example/o.swf\"></object>"
            + "<video poster='https://evil.example/p.jpg'></video>"
            + "<style>@import url(https://evil.example/f.css); @import \"https://evil.example/g.css\";</style>"
            + "</foreignObject></svg>"
            + "<img src=\"https://evil.example/appended.png\">";

        String sanitized = AiSvgContentSupport.sanitizeSvg(hostile);

        assertThat(sanitized).doesNotContain("evil.example");
        assertThat(sanitized).doesNotContain("file:///");
        assertThat(sanitized).doesNotContain("@import");
        assertThat(sanitized).contains("<rect width=\"5\" height=\"5\"/>");
    }

    @Test
    void buildSvgHtmlEmbedsTheDocumentWithScalingStyles() {
        String html = AiSvgContentSupport.buildSvgHtml(SIMPLE_SVG);

        assertThat(html).contains(SIMPLE_SVG);
        assertThat(html).contains("default-src 'none'");
        assertThat(html).contains("connect-src 'none'");
        assertThat(html).contains("max-width:100%");
        assertThat(html).contains("background:#ffffff");
    }

    @Test
    void estimatesDisplayHeightFromHeightAttributeThenViewBox() {
        String withHeight = "<svg xmlns=\"a\" width=\"300\" height=\"200\"><rect/></svg>";
        assertThat(AiSvgContentSupport.estimateDisplayHeight(withHeight, 120, 520, 320)).isEqualTo(200.0);

        assertThat(AiSvgContentSupport.estimateDisplayHeight(SIMPLE_SVG, 120, 520, 320)).isEqualTo(120.0);

        String huge = "<svg height=\"4000\"><rect/></svg>";
        assertThat(AiSvgContentSupport.estimateDisplayHeight(huge, 120, 520, 320)).isEqualTo(520.0);

        String noSize = "<svg xmlns=\"a\"><rect/></svg>";
        assertThat(AiSvgContentSupport.estimateDisplayHeight(noSize, 120, 520, 320)).isEqualTo(320.0);
    }
}
