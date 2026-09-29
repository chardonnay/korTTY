package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.SnippetDiagramType;
import org.testng.annotations.Test;

/**
 * The local syntax repair of generated flowcharts: what it fixes, and — as important — that it
 * never turns unsafe input into accepted input. The repair runs before the unchanged security
 * screen and grammar; unsafe syntax is refused as a whole, never quietly cut out.
 */
public class SnippetDiagramRepairTest {

    @Test
    void theHeaderVariantsModelsWriteBecomeFlowchartTd() {
        assertThat(SnippetDiagramSupport.repairGeneratedFlowchart("graph TD\na[\"A\"] --> b[\"B\"]").source())
            .startsWith("flowchart TD\n");
        assertThat(SnippetDiagramSupport.repairGeneratedFlowchart("flowchart LR\na[\"A\"] --> b[\"B\"]").source())
            .startsWith("flowchart TD\n");
        // Without a flowchart header nothing is touched, so validation names the real problem.
        assertThat(SnippetDiagramSupport.repairGeneratedFlowchart("sequenceDiagram\na ->> b: hi").source())
            .isEqualTo("sequenceDiagram\na ->> b: hi");
    }

    @Test
    void labelsBrokenAcrossLinesAreJoinedAndOpenShapesClosed() {
        SnippetDiagramSupport.FlowchartRepair repair = SnippetDiagramSupport.repairGeneratedFlowchart(
            "flowchart TD\nw[\"Lade 60 Min\"\n\"ergebnis?\"]\nx{\"Bereit?\"\ny[\"Ausgabe\"\n\"..\"]\nz[Ohne Ende\n");

        assertThat(repair.source()).contains("w[\"Lade 60 Min ergebnis?\"]");
        assertThat(repair.source()).contains("x{\"Bereit?\"}");
        assertThat(repair.source()).contains("y[\"Ausgabe\"]");
        assertThat(repair.source()).contains("z[\"Ohne Ende\"]");
        assertThat(repair.droppedLines()).isEmpty();
    }

    @Test
    void roundShapesTrailingSpacesAndStrayBackslashesAreRead() {
        String repaired = SnippetDiagramSupport.repairGeneratedFlowchart(
            "flowchart TD\nstart_1((\"Start\"))\nsetup(\"Setup\" )\nwork[\"Zwei?\"\\]\nstart_1 --> setup --> work").source();

        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(repaired).valid()).isTrue();
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(repaired);
        assertThat(canonical).contains("setup[\"Setup\"]");
        assertThat(canonical).contains("work[\"Zwei?\"]");
        assertThat(canonical).contains("start_1([\"Start\"])");
    }

    @Test
    void unreadableLinesAreLeftOutAndReported() {
        SnippetDiagramSupport.FlowchartRepair repair = SnippetDiagramSupport.repairGeneratedFlowchart(
            "flowchart TD\na[\"A\"] --> b[\"B\"]\nwork_hist 60 --> work_hist 24\nsubgraph phase\nend\nstop_1\n\"\"\n");

        assertThat(repair.source()).isEqualTo("flowchart TD\na[\"A\"] --> b[\"B\"]");
        assertThat(repair.droppedLines()).containsExactly(
            "'work_hist 60 --> work_hist 24'", "'subgraph phase'", "'end'", "'stop_1'").inOrder();
    }

    @Test
    void htmlLineBreaksInLabelsBecomeSpaces() {
        String repaired = SnippetDiagramSupport.repairGeneratedFlowchart(
            "flowchart TD\na[\"Load<br>60 min\"] --> b[\"Print<br/>table\"]").source();

        assertThat(repaired).contains("a[\"Load 60 min\"]");
        assertThat(SnippetDiagramSupport.validateGeneratedMermaid(repaired).valid()).isTrue();
    }

    @Test
    void unsafeSyntaxIsRefusedNotCutOut() {
        String[] unsafe = {
            "flowchart TD\na[\"A\"] --> b[\"B\"]\nclick a callback \"steal\"",
            "flowchart TD\na[\"A\"] --> b[\"B\"]\nclick a href \"https://example.com\"",
            "flowchart TD\na[\"<script>alert(1)</script>\"] --> b[\"B\"]",
            "flowchart TD\na[\"<img src=x onerror=alert(1)>\"] --> b[\"B\"]",
            "%%{init: {\"securityLevel\": \"loose\"}}%%\nflowchart TD\na[\"A\"] --> b[\"B\"]",
            "flowchart TD\n%%{init: {\"theme\": \"dark\"}}%%\na[\"A\"] --> b[\"B\"]",
            "flowchart TD\na[\"A\"] --> b[\"B\"]\nstyle a fill:url(https://example.com/x.png)",
            "flowchart TD\na[\"A\"] --> b[\"B\"]\nclassDef evil fill:url(javascript:alert(1))\nclass a evil",
            "flowchart TD\na[\"javascript: alert(1)\"] --> b[\"B\"]",
            "flowchart TD\na@{ img: \"https://example.com/x.png\" } --> b[\"B\"]",
            "flowchart TD\na[\"A\"] --> b[\"B\"]\nlinkStyle 0 stroke:url(#x)",
        };
        for (String source : unsafe) {
            SnippetAiResponseSupport.MermaidDiagram diagram = parse(source);
            com.google.common.truth.Truth.assertWithMessage(source).that(diagram.isUsable()).isFalse();
        }
    }

    @Test
    void presentationStatementsAloneAreStillDroppedNotRefused() {
        // classDef/style/linkStyle the contract forbids but models add to "define" the classes are
        // stripped before the screen, as before — only their url()/script payloads are refused.
        SnippetAiResponseSupport.MermaidDiagram diagram = parse(
            "flowchart TD\nstart_1([\"Start\"]) --> a[\"A\"] --> stop_1([\"Stop\"])\nclassDef work fill:#fff\nstyle a fill:#f00");

        assertThat(diagram.isUsable()).isTrue();
        assertThat(diagram.mermaid()).doesNotContain("classDef");
        assertThat(diagram.mermaid()).doesNotContain("style");
    }

    @Test
    void veryLongLabelsAreCutInTheCanonicalForm() {
        String longLabel = "x".repeat(2_000);
        String canonical = SnippetDiagramSupport.canonicalizeGeneratedFlowchart(
            "flowchart TD\nstart_1 --> a[\"" + longLabel + "\"] -->|" + "y".repeat(500) + "| stop_1");

        assertThat(canonical).doesNotContain("x".repeat(SnippetDiagramSupport.MAX_CANONICAL_LABEL_CHARS + 1));
        assertThat(canonical).contains("…");
        assertThat(canonical).doesNotContain("y".repeat(SnippetDiagramSupport.MAX_CANONICAL_EDGE_LABEL_CHARS + 1));
    }

    @Test
    void edgeLabelsAreReducedToPlainText() {
        assertThat(SnippetDiagramSupport.sanitizeEdgeLabel("retry [x] (3) {y} #z; a&b `c`"))
            .isEqualTo("retry x 3 y z a b c");
    }

    @Test
    void oversizedSourcesAreStillRefused() {
        StringBuilder huge = new StringBuilder("flowchart TD\n");
        while (huge.length() < SnippetDiagramSupport.MAX_MERMAID_SOURCE_BYTES + 10) {
            huge.append("a[\"").append("z".repeat(100)).append("\"] --> b\n");
        }
        assertThat(parse(huge.toString()).isUsable()).isFalse();
    }

    @Test
    void codeReferencesToUnknownNodesAreDroppedNotFatal() {
        String answer = """
            {"title": "t", "mermaid": "flowchart TD\\nstart_1 --> a[\\"Load\\"] --> stop_1",
             "codeReferences": [
               {"nodeId": "a", "label": "Load the values", "startLine": 1, "endLine": 2},
               {"nodeId": "ghost", "label": "Ghost", "startLine": 1, "endLine": 1}]}
            """;

        SnippetAiResponseSupport.MermaidDiagram diagram =
            SnippetAiResponseSupport.parseMermaidDiagram(SnippetDiagramType.LOGICAL_STRUCTURE, answer, "one\ntwo\n");

        assertThat(diagram.isUsable()).isTrue();
        assertThat(diagram.codeReferences()).hasSize(1);
        assertThat(diagram.codeReferences().get(0).nodeId()).isEqualTo("a");
        // The node's own label stands; the reworded label of the mapping is not needed.
        assertThat(diagram.codeReferences().get(0).label()).isEqualTo("Load");
    }

    private static SnippetAiResponseSupport.MermaidDiagram parse(String mermaid) {
        com.google.gson.JsonObject answer = new com.google.gson.JsonObject();
        answer.addProperty("title", "t");
        answer.addProperty("mermaid", mermaid);
        return SnippetAiResponseSupport.parseMermaidDiagram(SnippetDiagramType.LOGICAL_STRUCTURE, answer.toString(), "x\n");
    }
}
