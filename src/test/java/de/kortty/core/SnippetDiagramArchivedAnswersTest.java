package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import org.testng.annotations.Test;

/**
 * Regression tests built from real answers of small local models that korTTY used to reject for
 * the local fallback diagram (archived under {@code ~/.kortty/logs/ai-answers}). The flowchart
 * dialect used to demand a single path — one outgoing edge per action, no loops, no edge out of
 * stop_1 — and threw a diagram away when its repairs would drop most of it; Mermaid renders all of
 * these. Each answer is replayed through the real generation path with a stub provider.
 */
public class SnippetDiagramArchivedAnswersTest {

    /** The archived answers describe a 130-line script; the references point into it. */
    private static final String SNIPPET = "echo line\n".repeat(130);

    @Test
    void nemotronFanOutWithLoopsIsAcceptedAsDrawn() throws Exception {
        // "repairs would drop 9 of 14 nodes and 14 of 21 edges": fan-out from setup and work,
        // two check → print → work loops, and `failure` declared twice with different labels.
        Result result = generate("nemotron-fan-out-and-loops.json");

        assertThat(result.diagram().isUsable()).isTrue();
        assertThat(result.requests()).isEqualTo(1);
        SnippetDiagramSupport.FlowchartStatistics stats = SnippetDiagramSupport.flowchartStatistics(result.diagram().mermaid());
        assertThat(stats.nonterminalNodes()).isEqualTo(12);
        assertThat(stats.decisionNodes()).isEqualTo(2);
        assertThat(stats.edges()).isEqualTo(21);
        String mermaid = result.diagram().mermaid();
        // The loops and the fan-out stay; the yes/no checks become decisions with German labels.
        assertThat(mermaid).contains("print_current --> work");
        assertThat(mermaid).contains("setup --> get_load_average");
        assertThat(mermaid).contains("check_load_success -->|ja| print_current");
        assertThat(mermaid).contains("check_load_success -->|nein| failure");
        // The first of the two `failure` labels stands.
        assertThat(mermaid).contains("failure[\"Failure: Fehler beim Laden\"]");
        // The reference whose label the model reworded still maps by node id.
        assertThat(result.diagram().codeReferences().stream().map(SnippetDiagramSupport.SourceCodeReference::nodeId))
            .contains("failure");
        assertThat(result.diagram().codeReferences()).hasSize(12);
    }

    @Test
    void nemotronChainPastStopIsReadAsTheDecisionsOtherBranch() throws Exception {
        // "stop_1 must not have an outgoing edge": `decision -->|yes| success --> stop_1 -->|no| failure`.
        Result result = generate("nemotron-chain-past-stop.json");

        assertThat(result.diagram().isUsable()).isTrue();
        assertThat(result.requests()).isEqualTo(1);
        String mermaid = result.diagram().mermaid();
        assertThat(mermaid).contains("decision -->|ja| success");
        assertThat(mermaid).contains("decision -->|nein| failure");
        assertThat(mermaid).contains("success --> work");
        assertThat(mermaid).doesNotContain("stop_1 -->");
        SnippetDiagramSupport.FlowchartStatistics stats = SnippetDiagramSupport.flowchartStatistics(mermaid);
        assertThat(stats.nonterminalNodes()).isEqualTo(5);
        assertThat(stats.edges()).isEqualTo(8);
    }

    @Test
    void nemotronRedeclaredDecisionsKeepTheirBranches() throws Exception {
        // "repairs would drop 3 of 6 nodes and 7 of 11 edges": `work` declared as a box and then
        // four times as a diamond; its branches to stop_1, success and failure all stay.
        Result result = generate("nemotron-redeclared-decisions.json");

        assertThat(result.diagram().isUsable()).isTrue();
        assertThat(result.requests()).isEqualTo(1);
        String mermaid = result.diagram().mermaid();
        assertThat(mermaid).contains("work[\"Work: Hauptprogramm ausführen\"]");
        // An action's yes/no branches are written in the diagram's language too.
        assertThat(mermaid).contains("work -->|ja| success");
        assertThat(mermaid).contains("work -->|nein| failure");
        assertThat(mermaid).contains("work --> stop_1");
        SnippetDiagramSupport.FlowchartStatistics stats = SnippetDiagramSupport.flowchartStatistics(mermaid);
        assertThat(stats.nonterminalNodes()).isEqualTo(4);
        assertThat(stats.edges()).isEqualTo(7);
    }

    @Test
    void gptOssUnclosedMultiLineLabelsAreJoinedAndClosed() throws Exception {
        // "Unsupported Mermaid syntax on line 7: 'work_hist_60["Lade 60 Min"'": a label broken
        // across lines, circles for the terminals, an id with a space in an edge line.
        Result result = generate("gpt-oss-unclosed-multiline-label.json");

        assertThat(result.diagram().isUsable()).isTrue();
        assertThat(result.requests()).isEqualTo(1);
        String mermaid = result.diagram().mermaid();
        assertThat(mermaid).contains("work_hist_60[\"Lade 60 Min ergebnis?\"]");
        assertThat(mermaid).contains("work_cpu_now --> work_hist_60");
        SnippetDiagramSupport.FlowchartStatistics stats = SnippetDiagramSupport.flowchartStatistics(mermaid);
        // The three nodes the model declared but never connected cannot be placed.
        assertThat(stats.nonterminalNodes()).isEqualTo(4);
        assertThat(stats.edges()).isEqualTo(5);
    }

    @Test
    void gptOssNodesWithoutAnyEdgeGetOneRepairRoundThenFallBack() throws Exception {
        // Seven steps, not one connection between them: there is no flow to show. The model is
        // asked once more with the reason and its own diagram; a second unusable answer is final.
        Result result = generate("gpt-oss-nodes-without-edges.json");

        assertThat(result.diagram().isUsable()).isFalse();
        assertThat(result.diagram().rejectionReason()).contains("no connections");
        assertThat(result.requests()).isEqualTo(2);
        String repairPrompt = result.lastRequest().userPrompt();
        assertThat(repairPrompt).contains("Your previous diagram answer was rejected");
        assertThat(repairPrompt).contains("no connections");
        assertThat(repairPrompt).contains("setup_init");
    }

    @Test
    void gptOssGarbledAnswerGetsOneRepairRoundThenFallBack() throws Exception {
        Result result = generate("gpt-oss-garbled.json");

        assertThat(result.diagram().isUsable()).isFalse();
        assertThat(result.requests()).isEqualTo(2);
    }

    @Test
    void aRepairRoundThatReturnsAFixedDiagramIsAccepted() throws Exception {
        String fixed = """
            {"title": "Serverauslastung", "mermaid": "flowchart TD\\nstart_1([\\"Start\\"]) --> setup_init[\\"Initialisierung\\"]\\nsetup_init --> work_now[\\"Sofortwerte anzeigen\\"] --> stop_1([\\"Stop\\"])"}
            """;
        StubAiService service = new StubAiService(read("gpt-oss-nodes-without-edges.json"), fixed);

        SnippetAiResponseSupport.MermaidDiagram diagram = SnippetAiWorkflowSupport.generateSnippetMermaid(
            service, null, de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE, SNIPPET, "bash", null, "de", "");

        assertThat(diagram.isUsable()).isTrue();
        assertThat(service.requests).hasSize(2);
        assertThat(diagram.mermaid()).contains("setup_init --> work_now");
    }

    @Test
    void aDiagramMermaidCannotParseGetsTheParsersErrorInTheRepairRound() throws Exception {
        String answer = read("nemotron-chain-past-stop.json");
        StubAiService service = new StubAiService(answer, answer);
        List<String> checked = new ArrayList<>();
        SnippetAiWorkflowSupport.DiagramSyntaxGate gate = (type, mermaid) -> {
            checked.add(mermaid);
            return checked.size() == 1 ? "Parse error on line 3: Expecting 'SQE'" : null;
        };

        SnippetAiResponseSupport.MermaidDiagram diagram = SnippetAiWorkflowSupport.generateSnippetMermaid(
            service, null, de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE, SNIPPET, "bash", null, "de", "",
            gate);

        assertThat(diagram.isUsable()).isTrue();
        assertThat(service.requests).hasSize(2);
        assertThat(service.requests.get(1).userPrompt()).contains("Parse error on line 3: Expecting 'SQE'");
        assertThat(checked).hasSize(2);
    }

    @Test
    void theRepairRoundKeepsTheUsersOwnInstructions() {
        String instructions = SnippetAiWorkflowSupport.diagramRepairInstructions(
            "Focus on the error handling.", "Unsupported syntax", "flowchart TD\nx[\"a\"");

        assertThat(instructions).startsWith("Focus on the error handling.");
        assertThat(instructions).contains("Unsupported syntax");
        assertThat(instructions).contains("keep the same nodes".replace("keep", "Keep"));
        assertThat(instructions).contains("x[\"a\"");
    }

    private static Result generate(String resource) throws Exception {
        String answer = read(resource);
        StubAiService service = new StubAiService(answer, answer);
        SnippetAiResponseSupport.MermaidDiagram diagram = SnippetAiWorkflowSupport.generateSnippetMermaid(
            service, null, de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE, SNIPPET, "bash", null, "de", "");
        return new Result(diagram, service.requests.size(),
            service.requests.isEmpty() ? null : service.requests.get(service.requests.size() - 1));
    }

    public static String read(String resource) throws IOException {
        try (InputStream stream = SnippetDiagramArchivedAnswersTest.class.getResourceAsStream(
            "/snippet-diagram-answers/" + resource)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Result(SnippetAiResponseSupport.MermaidDiagram diagram, int requests, AiRequest lastRequest) {
    }

    private static final class StubAiService implements AiService {
        private final Queue<String> answers;
        private final List<AiRequest> requests = new ArrayList<>();

        private StubAiService(String... answers) {
            this.answers = new ArrayDeque<>(Arrays.asList(answers));
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            requests.add(request);
            return new AiExecutionResult(answers.isEmpty() ? "" : answers.remove(), null, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
