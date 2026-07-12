package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiChatRenderPageSupportTest {

    @Test
    void mathPageEmbedsTexAndDisablesAutoTypesetting() {
        String html = AiChatRenderPageSupport.buildMathHtml("\\frac{a}{b}");

        assertThat(html).contains("tex-svg.js");
        assertThat(html).contains("tex2svgPromise");
        assertThat(html).contains("\\\\frac{a}{b}");
        assertThat(html).contains("startup:{typeset:false}");
    }

    @Test
    void scriptTerminatorInsidePayloadCannotBreakOutOfTheScriptElement() {
        String literal = AiChatRenderPageSupport.toJsStringLiteral("x</script><script>alert(1)</script>");

        // Gson's HTML-safe mode escapes angle brackets as </>, so a literal
        // </script> can never appear in the embedded payload.
        assertThat(literal).doesNotContain("</script>");
        assertThat(literal).doesNotContain("<script>");
        assertThat(literal).contains("\\u003c/script\\u003e");
    }

    @Test
    void nullSourceBecomesEmptyLiteral() {
        assertThat(AiChatRenderPageSupport.toJsStringLiteral(null)).isEqualTo("\"\"");
    }
}
