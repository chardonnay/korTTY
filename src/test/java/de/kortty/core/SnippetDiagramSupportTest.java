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
    void fallbackRecognizesIndentedPerlConditionAsDecisionWithBothBranches() {
        String content = """
            sub process_file {
                my ($path) = @_;
                if (-f $path) {
                    print "Processing $path\n";
                } else {
                    warn "Missing $path\n";
                }
            }
            """;

        String mermaid = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(content, "perl");

        assertThat(mermaid).contains("decision_1{\"Main command succeeds?\"}");
        assertThat(mermaid).contains("decision_1 -->|yes| success_1");
        assertThat(mermaid).contains("decision_1 -->|no| failure_1");
        assertThat(SnippetDiagramSupport.validateMermaid(mermaid).valid()).isTrue();
    }

    @Test
    void fallbackRecognizesIndentedPythonConditionAsDecisionWithBothBranches() {
        String content = """
            def process_file(path):
                if path.exists():
                    print(f"Processing {path}")
                else:
                    print(f"Missing {path}")
            """;

        String mermaid = SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(content, "python");

        assertThat(mermaid).contains("decision_1{\"Main command succeeds?\"}");
        assertThat(mermaid).contains("decision_1 -->|yes| success_1");
        assertThat(mermaid).contains("decision_1 -->|no| failure_1");
        assertThat(SnippetDiagramSupport.validateMermaid(mermaid).valid()).isTrue();
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
    void validationRequiresEveryNodeOnAStartToStopPath() {
        String unreachable = """
            flowchart TD
                start_1(["Start"])
                work_1["Run"]
                orphan_1["Orphan"]
                stop_1(["Stop"])
                start_1 --> work_1
                work_1 --> stop_1
                orphan_1 --> stop_1
                class start_1,stop_1 setup
                class work_1,orphan_1 work
            """;
        String deadCycle = """
            flowchart TD
                start_1(["Start"])
                decision_1{"Continue?"}
                dead_1["Enter dead path"]
                dead_2["Stay in dead path"]
                stop_1(["Stop"])
                start_1 --> decision_1
                decision_1 -->|yes| stop_1
                decision_1 -->|no| dead_1
                dead_1 --> dead_2
                dead_2 --> dead_1
                class start_1,stop_1 setup
                class decision_1,dead_1,dead_2 work
            """;

        assertThat(SnippetDiagramSupport.validateMermaid(unreachable).valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(unreachable).message())
            .contains("reachable from start_1");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(deadCycle).message())
            .contains("path to stop_1");
    }

    @Test
    void validationRejectsBackwardTerminalsAndIncompleteDecisions() {
        String backwardStop = VALID_FLOWCHART + "\n    stop_1 --> work_1";
        String incomingStart = VALID_FLOWCHART + "\n    work_1 --> start_1";
        String incompleteDecision = VALID_FLOWCHART.replace(
            "    decision_1 -->|no| failure_1\n", "");

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(backwardStop).message())
            .contains("stop_1 must not have an outgoing edge");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(incomingStart).message())
            .contains("start_1 must not have an incoming edge");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(incompleteDecision).message())
            .contains("two distinctly labeled outgoing paths");
    }

    @Test
    void validationAcceptsConnectedLoopWithExplicitExit() {
        String loop = """
            flowchart TD
                start_1(["Start"])
                decision_1{"More items?"}
                work_1["Process next item"]
                stop_1(["Stop"])
                start_1 --> decision_1
                decision_1 -->|yes| work_1
                decision_1 -->|no| stop_1
                work_1 --> decision_1
                class start_1,stop_1 setup
                class decision_1,work_1 work
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(loop).valid()).isTrue();
    }

    @Test
    void persistedRestrictedDecisionWithoutLabelsRemainsRenderable() {
        String legacy = """
            flowchart TD
                start_1(["Start"])
                decision_1{"Ready?"}
                work_1["Run"]
                failure_1["Skip"]
                stop_1(["Stop"])
                start_1 --> decision_1
                decision_1 --> work_1
                decision_1 --> failure_1
                work_1 --> stop_1
                failure_1 --> stop_1
                class start_1,stop_1 setup
                class decision_1,work_1 work
                class failure_1 failure
            """;

        assertThat(SnippetDiagramSupport.validateMermaid(legacy).valid()).isTrue();
        assertThat(SnippetDiagramSupport.isRenderableMermaid(legacy)).isTrue();
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(legacy).message())
            .contains("two distinctly labeled outgoing paths");
    }

    @Test
    void generatedValidationAcceptsLocalizedDecisionEdgeLabels() {
        String localized = VALID_FLOWCHART
            .replace("|yes|", "|ja|")
            .replace("|no|", "|nein|");
        List<SnippetDiagramSupport.SourceCodeReference> references = List.of(
            new SnippetDiagramSupport.SourceCodeReference("setup_1", "Read configured values", 1, 1),
            new SnippetDiagramSupport.SourceCodeReference("work_1", "Run main snippet logic", 2, 2),
            new SnippetDiagramSupport.SourceCodeReference("decision_1", "Main command succeeds?", 3, 3),
            new SnippetDiagramSupport.SourceCodeReference("success_1", "Send success notification", 4, 4),
            new SnippetDiagramSupport.SourceCodeReference("failure_1", "Send failure notification", 5, 5));
        String content = "setup\nrun\ndecide\nsuccess\nfailure";

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(localized).valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            localized, content, references, "de").valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            VALID_FLOWCHART, content, references, "de").message())
            .contains("localized yes/no labels for language de");
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            localized.replace("|ja|", "|links|").replace("|nein|", "|rechts|"),
            content,
            references,
            "de").message()).contains("localized yes/no labels for language de");
    }

    @Test
    void generatedValidationRejectsOverDetailedStatementChain() {
        StringBuilder mermaid = new StringBuilder("flowchart TD\n    start_1([\"Start\"])\n");
        for (int index = 1; index <= 13; index++) {
            mermaid.append("    work_").append(index).append("[\"Step ").append(index).append("\"]\n");
        }
        mermaid.append("    stop_1([\"Stop\"])\n    start_1 --> work_1\n");
        for (int index = 1; index < 13; index++) {
            mermaid.append("    work_").append(index).append(" --> work_").append(index + 1).append('\n');
        }
        mermaid.append("    work_13 --> stop_1\n    class start_1,stop_1 setup\n");
        for (int index = 1; index <= 13; index++) {
            mermaid.append("    class work_").append(index).append(" work\n");
        }

        assertThat(SnippetDiagramSupport.validateMermaid(mermaid.toString()).valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(mermaid.toString()).message())
            .contains("at most 12 non-terminal nodes");
    }

    @Test
    void freshSnippetDiagramKeepsAnIncompleteSourceMappingAndReportsTheGaps() {
        String linear = """
            flowchart TD
                start_1(["Start"])
                work_1["Run"]
                stop_1(["Stop"])
                start_1 --> work_1
                work_1 --> stop_1
                class start_1,stop_1 setup
                class work_1 work
            """;
        List<SnippetDiagramSupport.SourceCodeReference> complete = List.of(
            new SnippetDiagramSupport.SourceCodeReference("work_1", "Run", 1, 1));
        List<SnippetDiagramSupport.SourceCodeReference> outOfBounds = List.of(
            new SnippetDiagramSupport.SourceCodeReference("work_1", "Run", 3, 3));

        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            linear, "run\n", complete, "en").valid()).isTrue();
        // A missing or unusable mapping no longer discards the whole diagram in favour of the
        // generic fallback: the affected node just loses its hover reference.
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            linear, "run\n", List.of(), "en").valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            linear, "run\n", outOfBounds, "en").valid()).isTrue();
        assertThat(SnippetDiagramSupport.reportSourceMapping(linear, "run\n", complete).complete()).isTrue();
        SnippetDiagramSupport.SourceMappingReport gaps =
            SnippetDiagramSupport.reportSourceMapping(linear, "run\n", outOfBounds);
        assertThat(gaps.expectedNodes()).isEqualTo(1);
        assertThat(gaps.mappedNodes()).isEqualTo(0);
        assertThat(gaps.unmappedNodeIds()).containsExactly("work_1");
    }

    @Test
    void chainedEdgesAndPlainCommentsAreAcceptedAsShorthand() {
        String chained = """
            flowchart TD
                %% phases
                start_1(["Start"])
                work_1["Read config"]
                check_1{"Config valid?"}
                work_2["Run"]
                fail_1["Abort"]
                stop_1(["Stop"])
                start_1 --> work_1 --> check_1
                check_1 -->|yes| work_2 --> stop_1
                check_1 -->|no| fail_1 --> stop_1
                class start_1,stop_1 setup
                class work_1,check_1,work_2 work
                class fail_1 failure
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(chained).valid()).isTrue();
        SnippetDiagramSupport.FlowchartStatistics statistics = SnippetDiagramSupport.flowchartStatistics(chained);
        assertThat(statistics.edges()).isEqualTo(6);
        assertThat(statistics.decisionNodes()).isEqualTo(1);
    }

    @Test
    void unsupportedSyntaxErrorsQuoteTheOffendingLine() {
        String subgraph = """
            flowchart TD
                start_1(["Start"])
                subgraph setup_phase [Setup]
                stop_1(["Stop"])
                start_1 --> stop_1
                class start_1,stop_1 setup
            """;

        assertThat(SnippetDiagramSupport.validateMermaid(subgraph).message())
            .isEqualTo("Unsupported Mermaid syntax on line 3: 'subgraph setup_phase [Setup]'.");
    }

    @Test
    void generatedNodeCapGrowsLinearlyWithTheSnippetLength() {
        assertThat(SnippetDiagramSupport.maxGeneratedNonterminalNodes("")).isEqualTo(12);
        assertThat(SnippetDiagramSupport.maxGeneratedNonterminalNodes((String) null)).isEqualTo(12);
        assertThat(SnippetDiagramSupport.maxGeneratedNonterminalNodes(200)).isEqualTo(12);
        assertThat(SnippetDiagramSupport.maxGeneratedNonterminalNodes(600)).isEqualTo(18);
        assertThat(SnippetDiagramSupport.maxGeneratedNonterminalNodes(1_000)).isEqualTo(24);
        assertThat(SnippetDiagramSupport.maxGeneratedNonterminalNodes(4_008)).isEqualTo(24);
        assertThat(SnippetDiagramSupport.maxGeneratedNonterminalNodes("a\nb\nc")).isEqualTo(12);
        assertThat(SnippetDiagramSupport.countLines("a\nb\nc")).isEqualTo(3);
        assertThat(SnippetDiagramSupport.countLines(null)).isEqualTo(0);
    }

    @Test
    void longSnippetsAcceptMoreNodesThanTheBaseCap() {
        String twenty = linearChain(20);
        String longSnippet = "line\n".repeat(1_200);

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(twenty).message())
            .contains("at most 12 non-terminal nodes for this snippet, but 20 were declared");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(twenty, 24).valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(twenty, longSnippet, List.of(), "en").valid())
            .isTrue();
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            linearChain(25), longSnippet, List.of(), "en").message())
            .contains("at most 24 non-terminal nodes for this snippet, but 25 were declared");

        SnippetDiagramSupport.FlowchartStatistics statistics =
            SnippetDiagramSupport.flowchartStatistics(linearChain(25));
        assertThat(statistics.nonterminalNodes()).isEqualTo(25);
        assertThat(statistics.decisionNodes()).isEqualTo(0);
        assertThat(statistics.edges()).isEqualTo(26);
        assertThat(statistics.toString()).isEqualTo("nodes=25 (decisions=0), edges=26");
    }

    private static String linearChain(int steps) {
        StringBuilder mermaid = new StringBuilder("flowchart TD\n    start_1([\"Start\"])\n");
        for (int index = 1; index <= steps; index++) {
            mermaid.append("    work_").append(index).append("[\"Step ").append(index).append("\"]\n");
        }
        mermaid.append("    stop_1([\"Stop\"])\n    start_1 --> work_1\n");
        for (int index = 1; index < steps; index++) {
            mermaid.append("    work_").append(index).append(" --> work_").append(index + 1).append('\n');
        }
        mermaid.append("    work_").append(steps).append(" --> stop_1\n    class start_1,stop_1 setup\n");
        for (int index = 1; index <= steps; index++) {
            mermaid.append("    class work_").append(index).append(" work\n");
        }
        return mermaid.toString();
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
