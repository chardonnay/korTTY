package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiChatDiagramSupportTest {

    @Test
    void detectsPlantUmlBlocks() {
        assertThat(AiChatDiagramSupport.isPlantUmlBlock("plantuml", "A -> B: hi")).isTrue();
        assertThat(AiChatDiagramSupport.isPlantUmlBlock("puml", "A -> B: hi")).isTrue();
        assertThat(AiChatDiagramSupport.isPlantUmlBlock("", "@startuml\nA -> B\n@enduml")).isTrue();
        assertThat(AiChatDiagramSupport.isPlantUmlBlock("", "A -> B")).isFalse();
        assertThat(AiChatDiagramSupport.isPlantUmlBlock("bash", "@startuml")).isFalse();
        assertThat(AiChatDiagramSupport.isPlantUmlBlock("plantuml", "")).isFalse();
    }

    @Test
    void normalizePlantUmlWrapsBareSources() {
        assertThat(AiChatDiagramSupport.normalizePlantUml("A -> B: hi"))
            .isEqualTo("@startuml\nA -> B: hi\n@enduml");
        assertThat(AiChatDiagramSupport.normalizePlantUml("@startuml\nA -> B\n@enduml"))
            .isEqualTo("@startuml\nA -> B\n@enduml");
        // Other @start dialects (mindmap, gantt, ...) are passed through untouched.
        assertThat(AiChatDiagramSupport.normalizePlantUml("@startmindmap\n* root\n@endmindmap"))
            .isEqualTo("@startmindmap\n* root\n@endmindmap");
    }

    @Test
    void normalizePlantUmlAppendsMissingEndMarker() {
        // Truncated AI output: @startuml without the closing @enduml.
        assertThat(AiChatDiagramSupport.normalizePlantUml("@startuml\nA -> B"))
            .isEqualTo("@startuml\nA -> B\n@enduml");
    }

    @Test
    void detectsMermaidBlocksByLanguageTag() {
        assertThat(AiChatDiagramSupport.isMermaidBlock("mermaid")).isTrue();
        assertThat(AiChatDiagramSupport.isMermaidBlock("Mermaid")).isTrue();
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
