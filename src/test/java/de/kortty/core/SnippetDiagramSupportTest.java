package de.kortty.core;

import de.kortty.model.SnippetDiagram;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetDiagramSupportTest {

    private static final String VALID_FLOWCHART = """
        flowchart TD
            start_1(["Start"])
            setup_1["Read configured values"]
            work_1["Run main snippet logic"]
            decision_1{"Main command succeeds?"}
            success_1["Send success notification"]
            failure_1["Send failure notification"]
            stop_1(["Stop"])
            start_1 --> setup_1
            setup_1 --> work_1
            work_1 --> decision_1
            decision_1 -->|yes| success_1
            decision_1 -->|no| failure_1
            success_1 --> stop_1
            failure_1 --> stop_1
            class start_1,stop_1,setup_1 setup
            class work_1,decision_1 work
            class success_1 success
            class failure_1 failure
        """;

    @Test
    void contentHashMarksDiagramStaleAfterCodeChange() {
        SnippetDiagram diagram = new SnippetDiagram();
        diagram.setSourceContentSha256(SnippetDiagramSupport.contentHash("echo old"));

        assertThat(SnippetDiagramSupport.isStale(diagram, "echo old")).isFalse();
        assertThat(SnippetDiagramSupport.isStale(diagram, "echo new")).isTrue();
    }

    @Test
    void normalizeMermaidRemovesOnlyExplicitMermaidFence() {
        assertThat(SnippetDiagramSupport.normalizeMermaid("""
            ```mermaid
            flowchart TD
                work_1["Run"]
                class work_1 work
            ```
            """)).startsWith("flowchart TD");
        assertThat(SnippetDiagramSupport.normalizeMermaid("```plantuml\nflowchart TD\n```"))
            .startsWith("```plantuml");
    }

    @Test
    void fallbackUsesStableIdsQuotedLabelsAndSemanticClasses() {
        String content = """
            BACKUP_DIR="/backup"
            tar -czf "$BACKUP_FILE" "${SOURCE_DIRS[@]}"
            if [ $? -eq 0 ]; then
              echo ok | mail -s Success admin@example.com
            else
              echo failed | mail -s Failed admin@example.com
            fi
            """;

        String first = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(content, "bash");
        String second = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(content, "bash");

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("flowchart TD");
        assertThat(first).contains("start_1([\"Start\"])");
        assertThat(first).contains("stop_1([\"Stop\"])");
        assertThat(first).contains("setup_1[\"Read configured values\"]");
        assertThat(first).contains("decision_1{\"Main command succeeds?\"}");
        assertThat(first).contains("decision_1 -->|yes| success_1");
        assertThat(first).contains("class work_1 work");
        assertThat(first).contains("class failure_1 failure");
        assertThat(SnippetDiagramSupport.validateMermaid(first).valid()).isTrue();
    }

    @Test
    void localFallbackBuildsNodeIdBasedCodeReferences() {
        String content = """
            BACKUP_DIR="/backup"
            tar -czf "$BACKUP_FILE" "${SOURCE_DIRS[@]}"
            if [ $? -eq 0 ]; then
              echo ok | mail -s Success admin@example.com
            else
              echo failed | mail -s Failed admin@example.com
            fi
            """;
        String mermaid = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(content, "bash");

        List<SnippetDiagramSupport.CodeReference> references =
            SnippetDiagramSupport.buildCodeReferences(mermaid, content);

        assertThat(findReference(references, "setup_1").startLine()).isEqualTo(1);
        assertThat(findReference(references, "work_1").startLine()).isEqualTo(2);
        assertThat(findReference(references, "decision_1").startLine()).isEqualTo(3);
        assertThat(findReference(references, "success_1").startLine()).isEqualTo(4);
        assertThat(findReference(references, "failure_1").startLine()).isEqualTo(6);
        assertThat(references.stream().map(SnippetDiagramSupport.CodeReference::nodeId).toList())
            .containsNoneOf("start_1", "stop_1");
    }

    @Test
    void validatedCodeReferencesRequireMatchingNodeIdLabelAndLineRange() {
        List<SnippetDiagramSupport.CodeReference> references =
            SnippetDiagramSupport.buildValidatedCodeReferences(VALID_FLOWCHART, """
                CONFIG=/etc/tool.conf
                tool --config "$CONFIG"
                """, List.of(
                new SnippetDiagramSupport.SourceCodeReference("setup_1", "Read configured values", 1, 1),
                new SnippetDiagramSupport.SourceCodeReference("work_1", "Run main snippet logic", 2, 2),
                new SnippetDiagramSupport.SourceCodeReference("missing", "Run main snippet logic", 1, 1),
                new SnippetDiagramSupport.SourceCodeReference("work_1", "Wrong label", 1, 1),
                new SnippetDiagramSupport.SourceCodeReference("decision_1", "Main command succeeds?", 4, 4)));

        assertThat(references).hasSize(2);
        assertThat(references.get(0).nodeId()).isEqualTo("setup_1");
        assertThat(references.get(0).excerpt()).contains("1 | CONFIG=/etc/tool.conf");
        assertThat(references.get(1).nodeId()).isEqualTo("work_1");
    }

    @Test
    void expandedReferencesKeepAiRangesAndAddLocallyMatchedNodes() {
        List<SnippetDiagramSupport.CodeReference> references =
            SnippetDiagramSupport.buildExpandedCodeReferences("""
                flowchart TD
                    start_1(["Start"])
                    setup_1["Read configured values"]
                    scan_1["Scan directory for files"]
                    sort_1["Sort files by selected criterion"]
                    stop_1(["Stop"])
                    start_1 --> setup_1
                    setup_1 --> scan_1
                    scan_1 --> sort_1
                    sort_1 --> stop_1
                    class start_1,stop_1,setup_1 setup
                    class scan_1,sort_1 work
                """, """
                SOURCE_DIR="/tmp"
                find "$SOURCE_DIR" -type f -name '*.log'
                sort "$file_list"
                """, List.of(
                new SnippetDiagramSupport.SourceCodeReference("setup_1", "Read configured values", 1, 1)));

        assertThat(references).hasSize(3);
        assertThat(findReference(references, "setup_1").startLine()).isEqualTo(1);
        assertThat(findReference(references, "scan_1").startLine()).isEqualTo(2);
        assertThat(findReference(references, "sort_1").startLine()).isEqualTo(3);
    }

    @DataProvider
    Object[][] forbiddenMermaidSources() {
        return new Object[][] {
            {"---\nconfig:\n  theme: dark\n---\n" + VALID_FLOWCHART},
            {VALID_FLOWCHART + "\n%%{init: {'theme':'dark'}}%%"},
            {VALID_FLOWCHART + "\nclick work_1 https://example.com"},
            {VALID_FLOWCHART.replace("Run main snippet logic", "https://example.com/pixel.png")},
            {VALID_FLOWCHART + "\nstyle work_1 fill:#fff"},
            {VALID_FLOWCHART + "\nclassDef danger fill:red"},
            {VALID_FLOWCHART.replace("work_1[\"Run main snippet logic\"]", "work_1@{ img: 'https://example.com/a.png' }")},
            {VALID_FLOWCHART.replace("Run main snippet logic", "<b>Run</b>")}
        };
    }

    @Test(dataProvider = "forbiddenMermaidSources")
    void validationRejectsNetworkInteractionMediaDirectivesAndCustomStyles(String source) {
        assertThat(SnippetDiagramSupport.validateMermaid(source).valid()).isFalse();
    }

    @Test
    void validationEnforcesSourceAndEdgeLimits() {
        String oversized = "flowchart TD\n    work_1[\"" + "x".repeat(33 * 1024)
            + "\"]\n    class work_1 work";
        assertThat(SnippetDiagramSupport.validateMermaid(oversized).message()).contains("32 KiB");

        StringBuilder tooManyEdges = new StringBuilder("flowchart TD\n");
        for (int index = 0; index <= SnippetDiagramSupport.MAX_MERMAID_EDGES + 1; index++) {
            tooManyEdges.append("n").append(index).append("[\"Node ").append(index).append("\"]\n");
        }
        for (int index = 0; index <= SnippetDiagramSupport.MAX_MERMAID_EDGES; index++) {
            tooManyEdges.append("n").append(index).append(" --> n").append(index + 1).append('\n');
        }
        tooManyEdges.append("class ");
        for (int index = 0; index <= SnippetDiagramSupport.MAX_MERMAID_EDGES + 1; index++) {
            if (index > 0) tooManyEdges.append(',');
            tooManyEdges.append('n').append(index);
        }
        tooManyEdges.append(" work");

        assertThat(SnippetDiagramSupport.validateMermaid(tooManyEdges.toString()).message())
            .contains("300-edge");
    }

    @Test
    void validationRequiresRestrictedFlowchartAndSemanticClassForEveryNode() {
        assertThat(SnippetDiagramSupport.validateMermaid("sequenceDiagram\nAlice->>Bob: Hi").valid()).isFalse();
        assertThat(SnippetDiagramSupport.validateMermaid("""
            flowchart TD
                work_1["Run"]
                class work_1 work
            """).message()).contains("start_1 and stop_1");
        assertThat(SnippetDiagramSupport.validateMermaid("""
            flowchart TD
                start_1(["Start"])
                work_1["Run"]
                stop_1(["Stop"])
                start_1 --> work_1
                work_1 --> stop_1
                class start_1,stop_1 setup
            """).message())
            .contains("Every Mermaid node");
        assertThat(SnippetDiagramSupport.validateMermaid("""
            flowchart TD
                start_1(["Start"])
                work_1["Run"]
                work_1 --> missing_1
                work_1 --> stop_1
                stop_1(["Stop"])
                start_1 --> work_1
                class start_1,stop_1 setup
                class work_1 work
            """).message()).contains("declared node ids");
    }

    @Test
    void diagramColorModeAndHexColorRemainStableViewerHelpers() {
        assertThat(SnippetDiagramSupport.normalizeHexColor("#f4f8ff", "#FFFFFF"))
            .isEqualTo("#F4F8FF");
        assertThat(SnippetDiagramSupport.DiagramColorMode.fromKey("dark"))
            .isEqualTo(SnippetDiagramSupport.DiagramColorMode.DARK);
        assertThat(SnippetDiagramSupport.DiagramColorMode.fromKey("bogus"))
            .isEqualTo(SnippetDiagramSupport.DiagramColorMode.AUTO);
        assertThat(SnippetDiagramSupport.DiagramColorMode.DARK.isDarkActive()).isTrue();
        assertThat(SnippetDiagramSupport.DiagramColorMode.LIGHT.isDarkActive()).isFalse();
    }

    private SnippetDiagramSupport.CodeReference findReference(
        List<SnippetDiagramSupport.CodeReference> references, String nodeId) {

        return references.stream()
            .filter(reference -> nodeId.equals(reference.nodeId()))
            .findFirst()
            .orElseThrow();
    }
}
