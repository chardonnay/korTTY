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
import static org.testng.Assert.expectThrows;

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
            SnippetAiResponseSupport.parseScriptAnalysis(answer), SnippetProjectAiSupport.improvementFiles(answer), CONTEXT);
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
            SnippetProjectAiSupport.analyzeProject(service, null, CONTEXT, null, "en", null).analysis();
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
    void aFindingWithAKeyMissingItsQuoteKeepsTheWholeAnalysisAndItsFiles() throws Exception {
        String answer = "{\"summary\":\"Two scripts.\",\"dependencies\":[{\"id\":\"D1\",\"name\":\"bash\",\"kind\":\"program\"}],"
            + "\"improvements\":[{\"id\":\"SEC-1\",\"file\":\"lib/util.sh\",\"category\":\"security\",\"severity\":\"high\","
            + "\"title\":\"Quote $1\",detail\":\"Unquoted.\",\"recommendation\":\"Quote it\",\"line\":1},"
            + "{\"id\":\"DES-1\",\"file\":\"run.sh\",\"category\":\"design\",\"severity\":\"low\",\"title\":\"Shebang\","
            + "\"detail\":\"\",\"recommendation\":\"x\",\"line\":1}]}";
        SnippetAiResponseSupport.ScriptAnalysis analysis = SnippetProjectAiSupport.analyzeProject(
            new StubService(request -> new AiExecutionResult(answer, null)), null, CONTEXT, null, "en", null).analysis();

        assertThat(analysis.improvements().stream().map(SnippetAiResponseSupport.ScriptImprovement::title).toList())
            .containsExactly("[lib/util.sh] Quote $1", "[run.sh] Shebang").inOrder();
        assertThat(analysis.improvements().getFirst().detail()).isEqualTo("Unquoted.");

        IllegalStateException cutOff = expectThrows(IllegalStateException.class, () -> SnippetProjectAiSupport.analyzeProject(
            new StubService(request -> new AiExecutionResult("{\"summary\":\"Two", null)), null, CONTEXT, null, "en", null));
        assertThat(cutOff).hasMessageThat().contains("no usable analysis");
    }

    @Test
    void findingsKeepTheirOwnFileWhenTheModelNumbersThemPerFile() {
        String answer = """
            {"summary":"s","improvements":[
              {"id":"SEC-1","file":"run.sh","category":"security","severity":"high","title":"A","detail":"","recommendation":"r","line":1},
              {"id":"SEC-1","file":"lib/util.sh","category":"security","severity":"high","title":"B","detail":"","recommendation":"r","line":1}
            ]}""";
        SnippetAiResponseSupport.ScriptAnalysis tagged = SnippetProjectAiSupport.tagFiles(
            SnippetAiResponseSupport.parseScriptAnalysis(answer), SnippetProjectAiSupport.improvementFiles(answer), CONTEXT);
        assertThat(tagged.improvements().stream().map(SnippetAiResponseSupport.ScriptImprovement::title).toList())
            .containsExactly("[run.sh] A", "[lib/util.sh] B").inOrder();
    }

    @Test
    void theFileIsFoundUnderOtherKeysAndPathShapes() {
        String answer = """
            {"summary":"s","findings":[
              {"id":"A","filePath":"tools/lib/util.sh","category":"design","severity":"low","title":"folder in front","recommendation":"r"},
              {"id":"B","filename":"`run.sh:12`","category":"design","severity":"low","title":"line suffix","recommendation":"r"},
              {"id":"C","location":{"file":"util.sh","line":3},"category":"design","severity":"low","title":"nested, short name","recommendation":"r"},
              {"id":"D","file":["run.sh","lib/util.sh"],"category":"design","severity":"low","title":"several files","recommendation":"r"},
              {"id":"E","category":"design","severity":"low","title":"no file","recommendation":"r"}
            ]}""";
        SnippetAiResponseSupport.ScriptAnalysis tagged = SnippetProjectAiSupport.tagFiles(
            SnippetAiResponseSupport.parseScriptAnalysis(answer), SnippetProjectAiSupport.improvementFiles(answer), CONTEXT);
        assertThat(tagged.improvements().stream().map(SnippetProjectAiSupport::fileOf).toList())
            .containsExactly("lib/util.sh", "run.sh", "lib/util.sh", "", "").inOrder();
    }

    @Test
    void aShortNameThatSeveralFilesEndWithNamesNoFile() {
        ProjectContext twoUtils = new ProjectContext("p", List.of(
            new ProjectFile("a/util.sh", "bash", false, "x\n", "s1"),
            new ProjectFile("b/util.sh", "bash", false, "y\n", "s2")));
        assertThat(SnippetProjectAiSupport.normalizeKnownPath("util.sh", twoUtils)).isEmpty();
        assertThat(SnippetProjectAiSupport.normalizeKnownPath("B/UTIL.SH", twoUtils)).isEqualTo("b/util.sh");
        assertThat(SnippetProjectAiSupport.normalizeKnownPath("p/a/util.sh:4", twoUtils)).isEqualTo("a/util.sh");
    }

    @Test
    void theAnswerDescribesWhatEachFileDoes() throws Exception {
        String answer = """
            {"summary":"s","files":[{"path":"run.sh","purpose":"Entry point."},{"path":"./lib/util.sh","summary":"Helpers."},
              {"path":"ghost.sh","purpose":"Not in the project."}],
             "improvements":[{"id":"A","file":"run.sh","category":"design","severity":"low","title":"T","recommendation":"r"}]}""";
        SnippetProjectAiSupport.ProjectAnalysis analysis = SnippetProjectAiSupport.analyzeProject(
            new StubService(request -> new AiExecutionResult(answer, null)), null, CONTEXT, null, "en", null);
        assertThat(analysis.filePurposes()).containsExactly("run.sh", "Entry point.", "lib/util.sh", "Helpers.").inOrder();

        assertThat(SnippetProjectAiSupport.filePurposes(
            "{\"summary\":\"s\",\"files\":{\"lib/util.sh\":\"Helpers.\"},\"improvements\":[]}", CONTEXT))
            .containsExactly("lib/util.sh", "Helpers.");
        assertThat(SnippetProjectAiSupport.filePurposes("{\"summary\":\"s\",\"improvements\":[]}", CONTEXT)).isEmpty();
        assertThat(AiPromptBuilder.buildSystemPrompt(new AiRequest(AiAction.ANALYZE_SNIPPET_PROJECT, "ctx", null, "en",
            null, "ctx"))).contains("files lists every file");
    }

    @Test
    void anAnalysisRecordKeepsItsProjectFilesThroughJson() {
        List<SnippetAnalysisRecord.ProjectFileNote> files = List.of(
            new SnippetAnalysisRecord.ProjectFileNote("run.sh", "Entry point."),
            new SnippetAnalysisRecord.ProjectFileNote("lib/util.sh", null));
        SnippetAnalysisRecord record = SnippetAnalysisTestData.simpleRecord("r1", "s1", 1).withProjectFiles(files);
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty("s1").withNewCurrent(record, 5);
        String json = SnippetAnalysisStore.toJson(history);
        assertThat(json).contains("\"projectFiles\"");
        SnippetAnalysisRecord read = SnippetAnalysisStore.fromJson(json).current();
        assertThat(read.projectFiles()).containsExactly(
            new SnippetAnalysisRecord.ProjectFileNote("run.sh", "Entry point."),
            new SnippetAnalysisRecord.ProjectFileNote("lib/util.sh", "")).inOrder();
        // A snippet's analysis carries none, so its file stays as it was.
        assertThat(SnippetAnalysisStore.toJson(SnippetAnalysisHistory.empty("s2")
            .withNewCurrent(SnippetAnalysisTestData.simpleRecord("r2", "s2", 1), 5))).doesNotContain("projectFiles");
        assertThat(record.withProjectFiles(List.of()).projectFiles()).isNull();
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
