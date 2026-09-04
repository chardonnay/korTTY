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
            """).valid()).isTrue();  // terminals are synthesized
        // A node the model forgot to class keeps the diagram: the class only colors it, and the
        // canonical form makes the neutral default explicit.
        String unclassed = """
            flowchart TD
                start_1(["Start"])
                work_1["Run"]
                stop_1(["Stop"])
                start_1 --> work_1
                work_1 --> stop_1
                class start_1,stop_1 setup
            """;
        assertThat(SnippetDiagramSupport.validateMermaid(unclassed).valid()).isTrue();
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(unclassed)).contains("class work_1 work");
        // An id that only appears in an edge becomes a node labelled with the id, as in Mermaid,
        // and a second edge out of an action is dropped with whatever only it reached.
        String fanOut = """
            flowchart TD
                start_1(["Start"])
                work_1["Run"]
                work_1 --> missing_1
                work_1 --> stop_1
                stop_1(["Stop"])
                start_1 --> work_1
                class start_1,stop_1 setup
                class work_1 work
            """;
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(fanOut).valid()).isTrue();
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(fanOut)).contains("missing_1 --> stop_1");
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
        // A node nothing leads to is pruned with its edges instead of costing the diagram.
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(unreachable).valid()).isTrue();
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(unreachable)).doesNotContain("orphan_1");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(deadCycle).message())
            .contains("path to stop_1");
    }

    @Test
    void validationRejectsBackwardTerminalsAndIncompleteDecisions() {
        String backwardStop = VALID_FLOWCHART + "\n    stop_1 --> work_1";
        String incomingStart = VALID_FLOWCHART.replace("    success_1 --> stop_1\n", "    success_1 --> start_1\n");
        // Two branches with the same label are read as one binary question per branch.
        String sameLabels = VALID_FLOWCHART.replace(
            "    decision_1 -->|no| failure_1\n", "    decision_1 -->|yes| failure_1\n");

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(backwardStop).message())
            .contains("stop_1 must not have an outgoing edge");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(incomingStart).message())
            .contains("start_1 must not have an incoming edge");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(sameLabels).valid()).isTrue();
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(sameLabels))
            .contains("decision_1{\"Main command succeeds — yes?\"}");
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
        // Two bare branches read as yes then no, in the order they were drawn.
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(legacy).valid()).isTrue();
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(legacy)).contains("decision_1 -->|yes| work_1");
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(legacy)).contains("decision_1 -->|no| failure_1");
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
        // English outcomes under a German interface are translated, not rejected …
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            VALID_FLOWCHART, content, references, "de").valid()).isTrue();
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(VALID_FLOWCHART, "de"))
            .contains("decision_1 -->|nein| failure_1");
        // … and branch names that are no outcomes become one binary question per branch.
        String named = localized.replace("|ja|", "|links|").replace("|nein|", "|rechts|");
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(named, content, references, "de").valid()).isTrue();
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(named, "de"))
            .contains("decision_1{\"Main command succeeds — links?\"}");
    }

    @Test
    void generatedValidationRejectsOverDetailedStatementChain() {
        StringBuilder mermaid = new StringBuilder("flowchart TD\n    start_1([\"Start\"])\n");
        for (int index = 1; index <= 25; index++) {
            mermaid.append("    work_").append(index).append("[\"Step ").append(index).append("\"]\n");
        }
        mermaid.append("    stop_1([\"Stop\"])\n    start_1 --> work_1\n");
        for (int index = 1; index < 25; index++) {
            mermaid.append("    work_").append(index).append(" --> work_").append(index + 1).append('\n');
        }
        mermaid.append("    work_25 --> stop_1\n    class start_1,stop_1 setup\n");
        for (int index = 1; index <= 25; index++) {
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
    void inlineDeclarationsDashLabelsAndInlineClassesAreParsedAndCanonicalized() {
        // The exact shapes MiniMax-M3 wrote for a 130-line script.
        String modelStyle = """
            flowchart TD
            start_1(["Start"]) --> n1["Print header"]:::setup
            n1 --> n2{"Loadavg readable?"}
            class n2 work
            n2 -- no --> n3["Warn and use zeros"]:::failure
            n2 -- yes --> n4["Query mpstat"]
            n3 --> n4
            n4 --> stop_1(["Stop"])
            n4 --> stop_1
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(modelStyle).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(modelStyle);
        assertThat(canonical).isEqualTo("""
            flowchart TD
                start_1(["Start"])
                n1["Print header"]
                n2{"Loadavg readable?"}
                n3["Warn and use zeros"]
                n4["Query mpstat"]
                stop_1(["Stop"])
                start_1 --> n1
                n1 --> n2
                n2 -->|no| n3
                n2 -->|yes| n4
                n3 --> n4
                n4 --> stop_1
                class start_1,n1,stop_1 setup
                class n2,n4 work
                class n3 failure""".stripTrailing());
        SnippetDiagramSupport.FlowchartStatistics statistics = SnippetDiagramSupport.flowchartStatistics(modelStyle);
        assertThat(statistics.nonterminalNodes()).isEqualTo(4);
        assertThat(statistics.decisionNodes()).isEqualTo(1);
    }

    @Test
    void unquotedLabelsAndTheStadiumBracketSlipAreNormalized() {
        String shorthand = """
            flowchart TD
                start_1([Start]) --> gather_now[Read current load and CPU]
                gather_now --> ask{Historical logs available?}
                ask -->|yes| both(Print rows)
                ask -->|no| both
                both --> stop_1(["Stop")]
                class start_1,stop_1 setup
                class gather_now,ask,both work
            """;

        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("start_1([Start]) --> n[Read load]"))
            .isEqualTo("start_1([\"Start\"]) --> n[\"Read load\"]");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("stop_1([\"Stop\")]"))
            .isEqualTo("stop_1([\"Stop\"])");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("n[\"Keep [v2] as is\"]"))
            .isEqualTo("n[\"Keep [v2] as is\"]");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("check{\"Only?\"} -->|yes] --> check_config"))
            .isEqualTo("check{\"Only?\"} -->|yes| check_config");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid("""
            flowchart TD
            start_1(["Start"])-->collect-current["Collect"]-->stop_1(["Stop"])
            class start_1,stop_1 setup
            """).valid()).isTrue();
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("hist_60 -->|no] hist_60_skip"))
            .isEqualTo("hist_60 -->|no| hist_60_skip");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("c{\"Ready?\" -->|yes| p"))
            .isEqualTo("c{\"Ready?\"} -->|yes| p");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("a[\"Do it\" --> b([\"End\" --> c"))
            .isEqualTo("a[\"Do it\"] --> b([\"End\"]) --> c");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("check --> yes|go"))
            .isEqualTo("check -->|yes| go");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("check --> nein --> stop_1"))
            .isEqualTo("check -->|nein| stop_1");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("start_1([Start]) --> c[\"Collect\"] --> d{Ok?}"))
            .isEqualTo("start_1([\"Start\"]) --> c[\"Collect\"] --> d{\"Ok?\"}");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(
            shorthand.replace("class gather_now,ask,both work", "class setup work success failure")).valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(shorthand).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(shorthand);
        assertThat(canonical).contains("gather_now[\"Read current load and CPU\"]");
        assertThat(canonical).contains("ask{\"Historical logs available?\"}");
        assertThat(canonical).contains("both[\"Print rows\"]");
        assertThat(canonical).contains("stop_1([\"Stop\"])");
        // A decision whose branches converge at once is accepted, not rejected.
        assertThat(canonical).contains("ask -->|yes| both");
        assertThat(canonical).contains("ask -->|no| both");
    }

    @Test
    void strayTerminalsBecomeActionsAndDeadEndsContinueToStop() {
        // MiniMax-M3 ended in its own terminal and left stop_1 dangling.
        String strayEnd = """
            flowchart TD
            start_1([Start]) --> init_const[Initialize constants]
            init_const --> print_table[Print formatted rows]
            print_table --> report_done([Report complete])
            stop_1([Stop])
            class start_1,stop_1 setup
            class report_done success
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(strayEnd).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(strayEnd);
        assertThat(canonical).contains("report_done[\"Report complete\"]");
        assertThat(canonical).contains("report_done --> stop_1");
        assertThat(canonical).contains("class report_done success");
    }

    @Test
    void exoticShapesReadAsActionsAndIdenticalRedeclarationsAreIgnored() {
        String exotic = """
            flowchart TD
            start_1(["Start"])
            a[/Print current row/]
            b[[Subroutine]]
            c[(Store)]
            d((Loop))
            e{{Prepare}}
            f>Flag]
            g[/"Quoted slant"/]
            a[/Print current row/]
            stop_1(["Stop"])
            start_1 --> a --> b --> c --> d --> e --> f --> g --> stop_1
            class start_1,stop_1 setup
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(exotic).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(exotic);
        for (String expected : List.of("a[\"Print current row\"]", "b[\"Subroutine\"]", "c[\"Store\"]",
            "d[\"Loop\"]", "e[\"Prepare\"]", "f[\"Flag\"]", "g[\"Quoted slant\"]")) {
            assertThat(canonical).contains(expected);
        }
        assertThat(SnippetDiagramSupport.flowchartStatistics(exotic).nonterminalNodes()).isEqualTo(7);
    }

    @Test
    void aSingleBareDecisionBranchGetsTheComplementaryLabelAndUndeclaredIdsBecomeNodes() {
        String sloppy = """
            flowchart TD
            start_1(["Start"]) --> check{"Logs available?"}
            check --> read_60 --> emit_rows["Print rows"]
            check -- no --> emit_rows
            emit_rows --> cpu_unavail["Mark CPU unavailable"] --> row_now
            stop_1(["Stop"])
            class start_1,stop_1 setup
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(sloppy).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(sloppy);
        assertThat(canonical).contains("check -->|yes| read_60");
        assertThat(canonical).contains("check -->|no| emit_rows");
        assertThat(canonical).contains("read_60[\"read_60\"]");
        assertThat(canonical).contains("row_now[\"row_now\"]");
        assertThat(canonical).contains("row_now --> stop_1");
        // A German pair completes in German for a German interface — and is translated to the
        // response language otherwise.
        String german = sloppy.replace("-- no -->", "-- nein -->");
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(german, "de")).contains("check -->|ja| read_60");
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(german, "en")).contains("check -->|yes| read_60");
    }

    @Test
    void nodesNothingLeadsToArePrunedInsteadOfRejectingTheDiagram() {
        // A mistyped id that got its own declaration beside the node the flow really uses.
        String mistyped = """
            flowchart TD
            start_1([Start]) --> h60{"History available?"}
            h60 -->|yes| row_60
            h60 -->|no| row_60
            h60_decided["Row for last 60 minutes"] --> finish
            row_60 --> finish
            finish["Print footer"] --> stop_1
            stop_1([Stop])
            class start_1,stop_1 setup
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(mistyped).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(mistyped);
        assertThat(canonical).doesNotContain("h60_decided");
        assertThat(canonical).contains("row_60 --> finish");
    }

    @Test
    void labelsMayMentionFileOrDataWordsAndStableIdsAreTerminalsByContract() {
        // "File::Glob" matched the URL screen's file: prefix; the stable ids appeared only in edges.
        String getsslStyle = """
            flowchart TD
            start_1 --> setup_env["Initialize: use strict, POSIX, File::Glob; read data: defaults"]
            setup_env --> stop_1
            class start_1,stop_1 setup
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(getsslStyle).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(getsslStyle);
        assertThat(canonical).contains("start_1([\"Start\"])");
        assertThat(canonical).contains("stop_1([\"Stop\"])");
        assertThat(canonical).contains("File::Glob");
        // A real resource reference is still refused wherever it appears, labels included.
        assertThat(SnippetDiagramSupport.validateMermaid(
            getsslStyle + "click setup_env href \"https://example.com\"\n").valid()).isFalse();
        assertThat(SnippetDiagramSupport.validateMermaid(
            getsslStyle.replace("File::Glob", "https://example.com/pixel.png")).valid()).isFalse();
        assertThat(SnippetDiagramSupport.validateMermaid(
            getsslStyle.replace("File::Glob", "data:image/png;base64,AAAA")).valid()).isFalse();
    }

    @Test
    void parallelBranchesFromAnActionAreReducedToTheFirstPath() {
        String parallel = """
            flowchart TD
            start_1(["Start"]) --> load_now["Read load"]
            start_1 --> cpu_now["Read CPU"]
            load_now --> print_now["Print row"]
            cpu_now --> print_now
            print_now --> stop_1(["Stop"])
            class start_1,stop_1 setup
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(parallel).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(parallel);
        assertThat(canonical).contains("start_1 --> load_now");
        assertThat(canonical).doesNotContain("cpu_now");
        assertThat(SnippetDiagramSupport.flowchartStatistics(canonical).edges()).isEqualTo(3);
        // The branch that reaches most of the diagram is the one that stands for the path.
        String longerSecond = """
            flowchart TD
            start_1(["Start"]) --> short["Short"]
            start_1 --> a["A"]
            a --> b["B"]
            b --> c["C"]
            short --> stop_1(["Stop"])
            c --> stop_1
            class start_1,stop_1 setup
            """;
        String kept = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(longerSecond);
        assertThat(kept).contains("start_1 --> a");
        assertThat(kept).doesNotContain("short");
        assertThat(SnippetDiagramSupport.normalizeShapeShorthand("x --> check{\"\"Ready?\"\"}"))
            .isEqualTo("x --> check{\"Ready?\"}");
    }

    @Test
    void missingStableTerminalsAreSynthesizedAroundTheFlow() {
        // The model ended in its own "End" terminal and never declared stop_1 (or start_1).
        String own = """
            flowchart TD
            print_header["Print header"] --> print_footer["Print footer"]
            print_footer --> main_call(["End"])
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(own).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(own);
        assertThat(canonical).contains("start_1 --> print_header");
        assertThat(canonical).contains("main_call[\"End\"]");
        assertThat(canonical).contains("main_call --> stop_1");
    }

    @Test
    void multiWayDecisionsBecomeAChainOfBinaryOnesAndForeignPairsAreTranslated() {
        String threeWay = """
            flowchart TD
            start_1(["Start"]) --> special{"Special action requested?"}
            special -->|upgrade| upgrade_check["Check upgrade"]
            special -->|revoke| revoke["Revoke certificate"]
            special -->|other| side_action["Run side action"]
            upgrade_check --> stop_1(["Stop"])
            revoke --> stop_1
            side_action --> stop_1
            class start_1,stop_1 setup
            """;

        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(threeWay, "x\n", List.of(), "de").valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(threeWay, "de");
        assertThat(canonical).contains("special{\"Special action requested — upgrade?\"}");
        assertThat(canonical).contains("special_2{\"Special action requested — revoke?\"}");
        assertThat(canonical).contains("special -->|ja| upgrade_check");
        assertThat(canonical).contains("special -->|nein| special_2");
        assertThat(canonical).contains("special_2 -->|ja| revoke");
        assertThat(canonical).contains("special_2 -->|nein| side_action");
        assertThat(canonical).doesNotContain("special_3");

        String english = VALID_FLOWCHART;
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(english, "x\n", List.of(), "de").valid()).isTrue();
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(english, "de")).contains("decision_1 -->|ja| success_1");
        assertThat(SnippetDiagramSupport.canonicalizeGeneratedFlowchart(english, "de")).contains("decision_1 -->|nein| failure_1");
    }

    @Test
    void aDiamondWithASingleExitIsAnAction() {
        String oneExit = """
            flowchart TD
            start_1(["Start"]) --> send{"Send registration"}
            send -->|ok| done["Report success"]
            done --> stop_1(["Stop"])
            class start_1,stop_1 setup
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(oneExit).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(oneExit);
        assertThat(canonical).contains("send[\"Send registration\"]");
        assertThat(canonical).contains("send --> done");
    }

    @Test
    void missingClassesDefaultAndClassesForUnknownIdsAreIgnored() {
        String sloppy = """
            flowchart TD
            class print_header,stop_1 setup
            start_1(["Start"]) --> init_main["Initialize"]
            init_main --> stop_1(["Stop"])
            """;

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(sloppy).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(sloppy);
        assertThat(canonical).contains("class start_1,stop_1 setup");
        assertThat(canonical).contains("class init_main work");
        assertThat(canonical).doesNotContain("print_header");
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
    void presentationStatementsAreStrippedOnlyFromFreshAnswersNotFromPersistedDiagrams() {
        String decorated = """
            flowchart TD
                classDef setup fill:#ffffff,stroke:#000000;
                start_1(["Start"])
                stop_1(["Stop"])
                start_1 --> stop_1
                linkStyle 0 stroke:#ff0000
                class start_1,stop_1 setup
            """;

        String stripped = SnippetDiagramSupport.stripPresentationStatements(decorated);

        assertThat(stripped).doesNotContain("classDef");
        assertThat(stripped).doesNotContain("linkStyle");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(stripped).valid()).isTrue();
        // The security screen itself is unchanged: a saved diagram carrying styling is still refused.
        assertThat(SnippetDiagramSupport.validateMermaid(decorated).message())
            .contains("directives, callbacks and custom styles");
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
        String twenty = linearChain(25);
        String longSnippet = "line\n".repeat(1_200);

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(twenty).message())
            .contains("at most 12 non-terminal nodes for this snippet (24 tolerated), but 25 were declared");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(twenty, 24).valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(linearChain(20)).valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(twenty, longSnippet, List.of(), "en").valid())
            .isTrue();
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            linearChain(30), longSnippet, List.of(), "en").valid()).isTrue();
        assertThat(SnippetDiagramSupport.validateMermaidForSnippet(
            linearChain(49), longSnippet, List.of(), "en").message())
            .contains("at most 24 non-terminal nodes for this snippet (48 tolerated), but 49 were declared");

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
