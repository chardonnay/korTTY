package de.kortty.core;

import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetModularizationSupport.ModuleFile;
import de.kortty.core.SnippetProjectAiSupport.ProjectContext;
import de.kortty.core.SnippetProjectAiSupport.ProjectFile;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class SnippetProjectAiSupportTest {

    private static final ProjectContext CONTEXT = new ProjectContext("ops/tools", List.of(
        new ProjectFile("run.sh", "bash", true, "#!/bin/bash\nsource lib/util.sh\n", "s1"),
        new ProjectFile("lib/util.sh", "bash", false, "util() { echo $1; }\n", "s2")));

    @Test
    void theContextListsEveryFileWithLineNumbers() {
        String rendered = CONTEXT.render();
        assertThat(rendered).contains("- run.sh (bash, executable)");
        assertThat(rendered).contains("- lib/util.sh (bash, not executable)");
        assertThat(rendered).contains("=== File: lib/util.sh ===");
        assertThat(rendered).contains("1 | util() { echo $1; }");
        assertThat(CONTEXT.estimatedTokens()).isGreaterThan(0);
        assertThat(CONTEXT.without(List.of("run.sh")).files()).hasSize(1);
    }

    @Test
    void projectFindingsCarryTheirFileInTheTitle() {
        String answer = """
            {"summary":"Two scripts.","dependencies":[],
             "improvements":[
              {"id":"SEC-1","file":"./lib/UTIL.sh","category":"security","severity":"high","title":"Quote $1","detail":"","recommendation":"Quote it","line":1},
              {"id":"DES-1","file":"","category":"design","severity":"low","title":"Shared logging","detail":"","recommendation":"x","line":1},
              {"id":"DES-2","file":"missing.sh","category":"design","severity":"low","title":"Ghost","detail":"","recommendation":"x","line":1}
             ]}""";
        SnippetAiResponseSupport.ScriptAnalysis tagged = SnippetProjectAiSupport.tagFiles(
            SnippetAiResponseSupport.parseScriptAnalysis(answer), SnippetProjectAiSupport.filesById(answer), CONTEXT);
        List<String> titles = tagged.improvements().stream().map(SnippetAiResponseSupport.ScriptImprovement::title).toList();
        assertThat(titles).containsExactly("[lib/util.sh] Quote $1", "Shared logging", "Ghost").inOrder();
        assertThat(SnippetProjectAiSupport.fileOf(tagged.improvements().get(0))).isEqualTo("lib/util.sh");

        List<SnippetAiResponseSupport.ScriptImprovement> forRun =
            SnippetProjectAiSupport.improvementsFor(tagged.improvements(), "run.sh");
        assertThat(forRun.stream().map(SnippetAiResponseSupport.ScriptImprovement::title).toList())
            .containsExactly("Shared logging", "Ghost");
        List<SnippetAiResponseSupport.ScriptImprovement> forUtil =
            SnippetProjectAiSupport.improvementsFor(tagged.improvements(), "lib/util.sh");
        assertThat(forUtil.getFirst().title()).isEqualTo("Quote $1");

        assertThat(SnippetProjectAiSupport.affectedFiles(CONTEXT, List.of(tagged.improvements().getFirst()), List.of()))
            .containsExactly("lib/util.sh");
        assertThat(SnippetProjectAiSupport.affectedFiles(CONTEXT, tagged.improvements(), List.of()))
            .containsExactly("run.sh", "lib/util.sh");
    }

    @Test
    void theRequestsUseTheirOwnActionsAndParseTheAnswers() throws Exception {
        List<AiRequest> requests = new ArrayList<>();
        AiService service = new StubService(request -> {
            requests.add(request);
            return switch (request.action()) {
                case ANALYZE_SNIPPET_PROJECT -> new AiExecutionResult(
                    "{\"summary\":\"s\",\"dependencies\":[],\"improvements\":[{\"id\":\"A\",\"file\":\"run.sh\","
                        + "\"category\":\"design\",\"severity\":\"low\",\"title\":\"T\",\"detail\":\"\","
                        + "\"recommendation\":\"r\",\"line\":1}]}", null);
                case PLAN_SNIPPET_MODULARIZATION -> new AiExecutionResult(
                    "{\"recommended\":true,\"rationale\":\"r\",\"files\":[{\"path\":\"main.sh\",\"entryPoint\":true},"
                        + "{\"path\":\"lib/a.sh\"}]}", null);
                default -> new AiExecutionResult("{\"fileLines\":[\"a() { :; }\",\"\"],\"summary\":\"ok\"}", null);
            };
        });
        SnippetAiResponseSupport.ScriptAnalysis analysis =
            SnippetProjectAiSupport.analyzeProject(service, null, CONTEXT, null, "en", null);
        assertThat(analysis.improvements().getFirst().title()).isEqualTo("[run.sh] T");

        ModularizationPlan plan = SnippetProjectAiSupport.planModularization(service, null,
            SnippetProjectAiSupport.scriptContext("tool.sh", "echo"), "bash", null, "en", null);
        assertThat(plan.isApplicable()).isTrue();

        String module = SnippetProjectAiSupport.generateModule(service, null, "ctx", "echo", plan,
            new ModuleFile("lib/a.sh", "", List.of(), false, false), Map.of(), "bash", null, "en", null, null);
        assertThat(module).isEqualTo("a() { :; }\n");

        assertThat(requests.stream().map(AiRequest::action).toList()).containsExactly(
            AiAction.ANALYZE_SNIPPET_PROJECT, AiAction.PLAN_SNIPPET_MODULARIZATION, AiAction.GENERATE_SNIPPET_MODULE)
            .inOrder();
        // The project and module prompts carry the sources once, in the context.
        String userPrompt = AiPromptBuilder.buildUserPrompt(requests.getFirst());
        assertThat(userPrompt).contains("=== File: run.sh ===");
        assertThat(userPrompt).doesNotContain("Script content for context only");
        assertThat(AiPromptBuilder.buildSystemPrompt(requests.get(2))).contains("fileLines");
    }

    @Test
    void folderKeysRoundTrip() {
        assertThat(SnippetProjectAiSupport.folderIdOfKey(SnippetProjectAiSupport.folderKey("abc"))).isEqualTo("abc");
        assertThat(SnippetProjectAiSupport.folderIdOfKey("abc")).isNull();
        assertThat(SnippetProjectAiSupport.folderIdOfKey("folder-")).isNull();
    }

    @Test
    void anAnalysisRecordKeepsItsModularizationPlanThroughJson() {
        ModularizationPlan plan = new ModularizationPlan(true, "split", List.of(
            new ModuleFile("main.py", "cli", List.of("main"), true, true),
            new ModuleFile("lib/x.py", "x", List.of(), false, false)));
        SnippetAnalysisRecord record = SnippetAnalysisTestData.simpleRecord("r1", "s1", 1).withModularization(plan);
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("s1").withNewCurrent(record, 5);
        SnippetAnalysisHistory read = SnippetAnalysisStore.fromJson(SnippetAnalysisStore.toJson(history));
        assertThat(read.current().modularization()).isEqualTo(plan);
        SnippetAnalysisHistory withoutPlan = SnippetAnalysisHistory.empty("s2")
            .withNewCurrent(SnippetAnalysisTestData.simpleRecord("r2", "s2", 1), 5);
        assertThat(SnippetAnalysisStore.fromJson(SnippetAnalysisStore.toJson(withoutPlan)).current().modularization())
            .isNull();
    }

    private record StubService(java.util.function.Function<AiRequest, AiExecutionResult> answer) implements AiService {
        @Override
        public AiExecutionResult execute(AiRequest request) {
            return answer.apply(request);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
