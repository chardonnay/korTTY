package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiChatDiagramSupportTest {

    @Test
    void detectsMermaidBlocksByLanguageTag() {
        assertThat(AiChatDiagramSupport.isMermaidBlock("mermaid")).isTrue();
        assertThat(AiChatDiagramSupport.isMermaidBlock("Mermaid")).isTrue();
        assertThat(AiChatDiagramSupport.isMermaidBlock("plantuml")).isFalse();
        assertThat(AiChatDiagramSupport.isMermaidBlock("puml")).isFalse();
        assertThat(AiChatDiagramSupport.isMermaidBlock("")).isFalse();
        assertThat(AiChatDiagramSupport.isMermaidBlock(null)).isFalse();
    }

    @Test
    void detectsLatexMathBlocksButNotFullDocuments() {
        assertThat(AiChatDiagramSupport.isLatexMathBlock("latex", "\\frac{a}{b}")).isTrue();
        assertThat(AiChatDiagramSupport.isLatexMathBlock("math", "E = mc^2")).isTrue();
        assertThat(AiChatDiagramSupport.isLatexMathBlock("tex", "x^2")).isTrue();
        assertThat(AiChatDiagramSupport.isLatexMathBlock("latex",
            "\\documentclass{article}\\begin{document}x\\end{document}")).isFalse();
        assertThat(AiChatDiagramSupport.isLatexMathBlock("bash", "x^2")).isFalse();
        assertThat(AiChatDiagramSupport.isLatexMathBlock("latex", "")).isFalse();
    }

    @Test
    void normalizeLatexMathStripsDisplayFrames() {
        assertThat(AiChatDiagramSupport.normalizeLatexMath("$$x^2$$")).isEqualTo("x^2");
        assertThat(AiChatDiagramSupport.normalizeLatexMath("\\[x^2\\]")).isEqualTo("x^2");
        assertThat(AiChatDiagramSupport.normalizeLatexMath("x^2")).isEqualTo("x^2");
    }

    @Test
    void splitsDisplayMathOutOfText() {
        var segments = AiChatDiagramSupport.splitTextWithDisplayMath(
            "The energy is\n$$E = mc^2$$\nas shown above.");

        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).text()).isEqualTo("The energy is");
        assertThat(segments.get(1).math()).isEqualTo("E = mc^2");
        assertThat(segments.get(2).text()).isEqualTo("as shown above.");
    }

    @Test
    void keepsTextWithoutMathUntouched() {
        var segments = AiChatDiagramSupport.splitTextWithDisplayMath("Costs 5 dollars, no math.");

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text()).isEqualTo("Costs 5 dollars, no math.");
        assertThat(segments.get(0).math()).isNull();
    }

    @Test
    void ignoresEmptyDisplayMathFrames() {
        var segments = AiChatDiagramSupport.splitTextWithDisplayMath("Before $$ $$ after.");

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).math()).isNull();
    }
}
