package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class MermaidRenderServiceTest {

    @Test
    void bundledResourcesAreAvailable() {
        assertThat(MermaidRenderService.isBundledAvailable()).isTrue();
    }

    @Test
    void rejectsUntrustedNetworkAndConfigurationFeatures() {
        assertThat(MermaidRenderService.validateSource("---\nconfig:\n  theme: dark\n---\nflowchart TD\nA-->B"))
            .contains("frontmatter");
        assertThat(MermaidRenderService.validateSource("%%{init: {theme: 'dark'}}%%\nflowchart TD\nA-->B"))
            .contains("directives");
        assertThat(MermaidRenderService.validateSource("flowchart TD\nA-->B\nclick A https://example.com"))
            .contains("external resources");
        assertThat(MermaidRenderService.validateSource("flowchart TD; A-->B; click A callback"))
            .contains("callbacks");
        assertThat(MermaidRenderService.validateSource("flowchart TD\nA@{img: 'https://example.com/a.png'}"))
            .contains("external resources");
        assertThat(MermaidRenderService.validateSource("flowchart TD\nA@{icon: 'logos:github'}"))
            .contains("image and icon");
        assertThat(MermaidRenderService.validateSource("flowchart TD\nA[\"Read\"]-->B[\"Open file:/tmp/key\"]"))
            .contains("external resources");
        assertThat(MermaidRenderService.validateSource("flowchart TD\nA-->B\nclassDef note fill:#eee"))
            .isNull();
    }

    @Test
    void acceptsSafeSupportedDiagramSources() {
        assertThat(MermaidRenderService.validateSource("flowchart TD\nstart([Start])-->work[\"Run\"]"))
            .isNull();
        assertThat(MermaidRenderService.validateSource("sequenceDiagram\nAlice->>Bob: Hello"))
            .isNull();
        assertThat(MermaidRenderService.validateSource("mindmap\n  root((korTTY))\n    Mermaid"))
            .isNull();
        assertThat(MermaidRenderService.validateSource(
            "sequenceDiagram\nA->>B: Open file: config.yml")).isNull();
        assertThat(MermaidRenderService.validateSource(
            "flowchart TD\nA[\"Image: build artifact\"]")).isNull();
    }

    @Test
    void sourceLimitCountsOriginalUtf8BeforeWhitespaceNormalization() {
        String padded = " ".repeat(33 * 1024) + "flowchart TD\nA-->B";
        assertThat(MermaidRenderService.validateSource(padded)).contains("32 KiB");
        assertThat(MermaidRenderService.validateSource("  flowchart TD\nA-->B  ")).isNull();
    }

    @Test
    void enforcesEdgeLimitAcrossSupportedDiagramFamilies() {
        assertThat(MermaidRenderService.validateSource(repeatedEdges(
            "sequenceDiagram", "Alice->>Bob: message", 300))).isNull();
        assertThat(MermaidRenderService.validateSource(repeatedEdges(
            "sequenceDiagram", "Alice->>Bob: message", 301))).contains("300-edge");
        assertThat(MermaidRenderService.validateSource(repeatedEdges(
            "classDiagram", "First --> Second", 301))).contains("300-edge");
        assertThat(MermaidRenderService.validateSource(repeatedEdges(
            "erDiagram", "USER ||--o{ SESSION : opens", 301))).contains("300-edge");

        StringBuilder mindmap = new StringBuilder("mindmap\n  root((korTTY))\n");
        for (int index = 0; index < 301; index++) {
            mindmap.append("    node_").append(index).append('\n');
        }
        assertThat(MermaidRenderService.validateSource(mindmap.toString())).contains("300-edge");
    }

    @Test
    void generatedDiagramsAreLimitedToTheRestrictedSnippetFlowchartDialect() {
        MermaidRenderService.RenderResult result = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.generatedFlow(
                "sequenceDiagram\nAlice->>Bob: Hello",
                MermaidRenderService.Theme.LIGHT,
                "#FFFFFF",
                false))
            .join();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("flowchart TD");
    }

    @Test
    void generatedRequestsValidateAgainstTheirDeclaredFamily() {
        MermaidRenderService.RenderResult crossType = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.generated(
                "sequenceDiagram\nparticipant a\nparticipant b\na ->> b: hi",
                de.kortty.model.SnippetDiagramType.STATE,
                MermaidRenderService.Theme.LIGHT,
                "#FFFFFF",
                false))
            .join();
        assertThat(crossType.success()).isFalse();
        assertThat(crossType.message()).contains("stateDiagram-v2");

        MermaidRenderService.RenderResult forbidden = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.generated(
                "sequenceDiagram\nparticipant a\na ->> a: see https://example.com",
                de.kortty.model.SnippetDiagramType.SEQUENCE,
                MermaidRenderService.Theme.LIGHT,
                "#FFFFFF",
                false))
            .join();
        assertThat(forbidden.success()).isFalse();

        MermaidRenderService.RenderResult unsupportedStatement = MermaidRenderService.render(
            MermaidRenderService.RenderRequest.generated(
                "sequenceDiagram\nparticipant a\nparticipant b\na ->> b: hi\nautonumber",
                de.kortty.model.SnippetDiagramType.SEQUENCE,
                MermaidRenderService.Theme.LIGHT,
                "#FFFFFF",
                false))
            .join();
        assertThat(unsupportedStatement.success()).isFalse();
        assertThat(unsupportedStatement.message()).contains("Unsupported sequence");
    }

    @Test
    void renderResultDefensivelyCopiesPng() {
        byte[] png = {1, 2, 3};
        MermaidRenderService.RenderResult result = new MermaidRenderService.RenderResult(
            true, "<svg/>", png, 10, 10, java.util.List.of(), "");
        png[0] = 9;
        assertThat(result.png()).isEqualTo(new byte[] {1, 2, 3});
        byte[] returned = result.png();
        returned[1] = 9;
        assertThat(result.png()).isEqualTo(new byte[] {1, 2, 3});
    }

    private static String repeatedEdges(String header, String edge, int count) {
        StringBuilder source = new StringBuilder(header).append('\n');
        for (int index = 0; index < count; index++) {
            source.append(edge).append('\n');
        }
        return source.toString();
    }
}
