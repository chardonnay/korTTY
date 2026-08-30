package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.core.ScriptLanguageMixSupport.HostFormat;
import de.kortty.core.ScriptLanguageMixSupport.LanguageMix;
import de.kortty.core.ScriptLanguageMixSupport.MigrationMode;
import de.kortty.core.SnippetAiWorkflowSupport.MigrationPlan;
import de.kortty.core.SnippetAiWorkflowSupport.MigrationRejectedException;
import de.kortty.core.SnippetAiWorkflowSupport.MigrationRejection;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SnippetLanguageMigrationTest {

    private static final String MIXED_BASH = """
        #!/usr/bin/env bash
        set -euo pipefail
        perl <<'PERL'
        use strict;
        print "hello\\n";
        PERL
        awk '{ print $1 }' data.txt
        echo done
        """;

    private static final String AZURE_MIXED = """
        trigger:
          - main
        pool:
          vmImage: ubuntu-latest
        steps:
          - bash: |
              set -eu
              make build
            displayName: Build
          - pwsh: |
              Write-Host "publish"
            displayName: Publish
        """;

    // ---------------------------------------------------------------- MigrationPlan

    @Test
    void aWholeScriptPlanIsDerivedForAMixedPlainScript() {
        MigrationPlan plan = plan(MIXED_BASH, "bash", ScriptLanguage.PERL, null);
        assertThat(plan.modes()).containsExactly(MigrationMode.WHOLE_SCRIPT);
        assertThat(plan.isNoOp()).isFalse();
        assertThat(plan.changesHostFormat()).isFalse();
    }

    @Test
    void aHostDocumentGetsStepsOnlyNeverWholeScript() {
        MigrationPlan plan = plan(AZURE_MIXED, "yaml", ScriptLanguage.POWERSHELL, null);
        assertThat(plan.modes()).containsExactly(MigrationMode.EMBEDDED_STEPS_ONLY);
        assertThat(plan.modes()).doesNotContain(MigrationMode.WHOLE_SCRIPT);
    }

    @Test
    void namingTheCurrentPlatformIsNotAConversion() {
        MigrationPlan plan = plan(AZURE_MIXED, "yaml", null, HostFormat.AZURE_PIPELINES);
        assertThat(plan.changesHostFormat()).isFalse();
        assertThat(plan.isNoOp()).isTrue();
    }

    @Test
    void aPlatformChoiceAndALanguageChoiceCombine() {
        MigrationPlan plan = plan(AZURE_MIXED, "yaml", ScriptLanguage.BASH, HostFormat.GITHUB_ACTIONS);
        assertThat(plan.modes())
            .containsExactly(MigrationMode.HOST_FORMAT_CONVERSION, MigrationMode.EMBEDDED_STEPS_ONLY);
    }

    @Test
    void anEmptyOrderIsRejectedBeforeItCostsAModelCall() {
        String uniform = "#!/usr/bin/env bash\nset -eu\necho one\necho two\necho three\n";
        MigrationPlan plan = plan(uniform, "bash", ScriptLanguage.BASH, null);
        assertThat(plan.isNoOp()).isTrue();
        CapturingService service = new CapturingService("{}");
        expectThrows(IllegalArgumentException.class, () -> SnippetAiWorkflowSupport.migrateSnippetLanguage(
            service, null, uniform, "bash", plan, "conn", "de", null));
        assertThat(service.calls).isEqualTo(0);
    }

    @Test
    void stepsThatAlreadyAllSpeakTheTargetAreLeftAlone() {
        String uniform = """
            trigger:
              - main
            pool:
              vmImage: ubuntu-latest
            steps:
              - bash: make build
              - bash: make test
            """;
        assertThat(plan(uniform, "yaml", ScriptLanguage.BASH, null).isNoOp()).isTrue();
    }

    // ---------------------------------------------------------------- prompt context

    @Test
    void theWholeScriptContextNamesTargetShebangAndTheDetectedForeignRanges() throws Exception {
        CapturingService service = new CapturingService(
            migrationResponse("#!/usr/bin/env perl\nuse strict;\nprint \"hello\\n\";\nprint \"done\\n\";\n", "ok"));
        SnippetAiWorkflowSupport.migrateSnippetLanguage(
            service, null, MIXED_BASH, "bash", plan(MIXED_BASH, "bash", ScriptLanguage.PERL, null),
            "conn", "de", null);

        String context = service.lastRequest.conversationContext();
        assertThat(service.lastRequest.action()).isEqualTo(AiAction.MIGRATE_SNIPPET_LANGUAGE);
        assertThat(context).contains("MIGRATION SCOPE: the complete script.");
        assertThat(context).contains("#!/usr/bin/env perl");
        assertThat(context).contains("perl");
        assertThat(context).contains("awk");
        assertThat(context).contains("Line-numbered snippet:");
    }

    @Test
    void theStepsOnlyContextDemandsAByteIdenticalScaffoldAndListsTheStepRanges() throws Exception {
        CapturingService service = new CapturingService(migrationResponse(AZURE_MIXED, "ok"));
        MigrationPlan plan = plan(AZURE_MIXED, "yaml", ScriptLanguage.POWERSHELL, null);
        try {
            SnippetAiWorkflowSupport.migrateSnippetLanguage(
                service, null, AZURE_MIXED, "yaml", plan, "conn", "de", null);
        } catch (MigrationRejectedException ignored) {
            // The echoed input is not a valid unification; only the prompt matters here.
        }
        String context = service.lastRequest.conversationContext();
        assertThat(context).contains("only the script-step bodies");
        assertThat(context).contains("Azure DevOps Pipeline");
        assertThat(context).contains("character for character");
        assertThat(context).contains("Step bodies to rewrite:");
        assertThat(context).contains("- lines 7-8: bash");
    }

    @Test
    void theConversionContextForbidsInventingAnEquivalent() throws Exception {
        String jenkinsfile = """
            pipeline {
              agent any
              stages {
                stage('Build') {
                  steps {
                    sh 'make build'
                  }
                }
              }
            }
            """;
        CapturingService service = new CapturingService(migrationResponse(
            "trigger:\n  - main\npool:\n  vmImage: ubuntu-latest\nsteps:\n  - bash: make build\n", "ok"));
        MigrationPlan plan = plan(jenkinsfile, "groovy", null, HostFormat.AZURE_PIPELINES);
        SnippetAiWorkflowSupport.migrateSnippetLanguage(
            service, null, jenkinsfile, "groovy", plan, "conn", "de", null);

        String context = service.lastRequest.conversationContext();
        assertThat(context).contains("convert this Jenkinsfile (declarative) into a valid Azure DevOps Pipeline");
        assertThat(context).contains("Do not invent a construct.");
        assertThat(context).contains("approvals");
    }

    // ---------------------------------------------------------------- result guards

    @Test
    void anEmptyReplyIsRefused() {
        CapturingService service = new CapturingService("{\"summary\":\"done\"}");
        MigrationRejectedException failure = expectThrows(MigrationRejectedException.class,
            () -> SnippetAiWorkflowSupport.migrateSnippetLanguage(service, null, MIXED_BASH, "bash",
                plan(MIXED_BASH, "bash", ScriptLanguage.PERL, null), "conn", "de", null));
        assertThat(failure.reason()).isEqualTo(MigrationRejection.NO_USABLE_SCRIPT);
    }

    @Test
    void aCollapsedWholeScriptResultIsRefused() {
        CapturingService service = new CapturingService(migrationResponse("print \"hello\";\n", "ok"));
        MigrationRejectedException failure = expectThrows(MigrationRejectedException.class,
            () -> SnippetAiWorkflowSupport.migrateSnippetLanguage(service, null, MIXED_BASH, "bash",
                plan(MIXED_BASH, "bash", ScriptLanguage.PERL, null), "conn", "de", null));
        assertThat(failure.reason()).isEqualTo(MigrationRejection.DEGENERATE);
    }

    @Test
    void aStepsOnlyResultThatRewritesTheScaffoldIsRefused() {
        String tampered = """
            trigger:
              - main
            pool:
              vmImage: windows-latest
            steps:
              - pwsh: |
                  Write-Host "build"
                displayName: Build
              - pwsh: |
                  Write-Host "publish"
                displayName: Publish
            """;
        CapturingService service = new CapturingService(migrationResponse(tampered, "unified"));
        MigrationRejectedException failure = expectThrows(MigrationRejectedException.class,
            () -> SnippetAiWorkflowSupport.migrateSnippetLanguage(service, null, AZURE_MIXED, "yaml",
                plan(AZURE_MIXED, "yaml", ScriptLanguage.POWERSHELL, null), "conn", "de", null));
        assertThat(failure.reason()).isEqualTo(MigrationRejection.SCAFFOLD_CHANGED);
    }

    @Test
    void aStepsOnlyResultThatOnlyChangesStepBodiesIsAccepted() throws Exception {
        String unified = """
            trigger:
              - main
            pool:
              vmImage: ubuntu-latest
            steps:
              - pwsh: |
                  Write-Host "build"
                displayName: Build
              - pwsh: |
                  Write-Host "publish"
                displayName: Publish
            """;
        CapturingService service = new CapturingService(migrationResponse(unified, "unified"));
        SnippetAiResponseSupport.LanguageMigration migration = SnippetAiWorkflowSupport.migrateSnippetLanguage(
            service, null, AZURE_MIXED, "yaml",
            plan(AZURE_MIXED, "yaml", ScriptLanguage.POWERSHELL, null), "conn", "de", null);
        assertThat(migration.replacement()).isEqualTo(unified);
    }

    @Test
    void aConversionThatDidNotReachTheTargetPlatformIsRefused() {
        String stillJenkins = """
            pipeline {
              agent any
              stages {
                stage('Build') {
                  steps {
                    sh 'make build'
                  }
                }
              }
            }
            """;
        CapturingService service = new CapturingService(migrationResponse(stillJenkins, "converted"));
        MigrationRejectedException failure = expectThrows(MigrationRejectedException.class,
            () -> SnippetAiWorkflowSupport.migrateSnippetLanguage(service, null, stillJenkins, "groovy",
                plan(stillJenkins, "groovy", null, HostFormat.AZURE_PIPELINES), "conn", "de", null));
        assertThat(failure.reason()).isEqualTo(MigrationRejection.TARGET_FORMAT_NOT_REACHED);
    }

    // ---------------------------------------------------------------- staged apply

    @Test
    void inTheStagedApplyTheMigrationRunsBeforeEveryImprovementStage() throws Exception {
        String original = MIXED_BASH;
        String migrated = """
            #!/usr/bin/env perl
            use strict;
            use warnings;
            print "hello\n";
            my @rows = split /\n/, `cat data.txt`;
            print "$_\n" for @rows;
            print "done\n";
            """;
        String afterImprovement = migrated + "# selected analysis item\n";
        SequencedService service = new SequencedService(
            migrationResponse(migrated, "Migrated to Perl."),
            applyResponse(afterImprovement, "Applied analysis item."));
        List<SnippetAiWorkflowSupport.ImprovementApplyProgress> progress = new ArrayList<>();

        SnippetAiResponseSupport.SnippetSecurityFix fix = SnippetAiWorkflowSupport.applySnippetImprovements(
            service, null, original, "bash", "conn", "de",
            List.of(new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "medium", "Quote value", "Unsafe expansion", "Quote it", 3)),
            List.of(), null, null, null,
            progress::add, null, null,
            plan(original, "bash", ScriptLanguage.PERL, null));

        assertThat(service.requests).hasSize(2);
        assertThat(service.requests.get(0).action()).isEqualTo(AiAction.MIGRATE_SNIPPET_LANGUAGE);
        assertThat(service.requests.get(1).action()).isEqualTo(AiAction.APPLY_SNIPPET_IMPROVEMENTS);
        // The decisive assertion: the improvement stage sees the migrated script, not the original.
        assertThat(service.requests.get(1).selectedText()).isEqualTo(migrated);
        assertThat(fix.replacement()).isEqualTo(afterImprovement);

        List<SnippetAiWorkflowSupport.ImprovementApplyPhase> runningPhases = progress.stream()
            .filter(item -> item.state() == SnippetAiWorkflowSupport.ImprovementApplyProgressState.RUNNING)
            .map(SnippetAiWorkflowSupport.ImprovementApplyProgress::phase)
            .toList();
        assertThat(runningPhases).containsExactly(
            SnippetAiWorkflowSupport.ImprovementApplyPhase.MIGRATION,
            SnippetAiWorkflowSupport.ImprovementApplyPhase.ANALYSIS_ITEMS).inOrder();
    }

    @Test
    void theMigrationAddsExactlyOneStageToTheVisiblePlan() {
        List<SnippetAiResponseSupport.ScriptImprovement> improvements = List.of(
            new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "medium", "Quote value", "Unsafe expansion", "Quote it", 3));
        int withoutMigration = SnippetAiWorkflowSupport
            .planSnippetImprovements(improvements, List.of(), null, null).size();
        List<SnippetAiWorkflowSupport.ImprovementApplyProgress> withMigration = SnippetAiWorkflowSupport
            .planSnippetImprovements(improvements, List.of(), null, null,
                plan(MIXED_BASH, "bash", ScriptLanguage.PERL, null));

        assertThat(withMigration).hasSize(withoutMigration + 1);
        assertThat(withMigration.get(0).phase())
            .isEqualTo(SnippetAiWorkflowSupport.ImprovementApplyPhase.MIGRATION);
        assertThat(withMigration.get(0).stage()).isEqualTo(1);
        assertThat(withMigration.get(0).detail()).isEqualTo("Perl");
        for (SnippetAiWorkflowSupport.ImprovementApplyProgress stage : withMigration) {
            assertThat(stage.totalStages()).isEqualTo(withoutMigration + 1);
        }
    }

    @Test
    void aNoOpPlanAddsNoStage() {
        assertThat(SnippetAiWorkflowSupport.planSnippetImprovements(List.of(), List.of(), null, null, null))
            .hasSize(SnippetAiWorkflowSupport.planSnippetImprovements(List.of(), List.of(), null, null).size());
    }

    // ---------------------------------------------------------------- response parsing

    @Test
    void notesAreReadFromEitherOfTheAcceptedFieldNames() {
        assertThat(SnippetAiResponseSupport.parseLanguageMigration(
            "{\"replacementLines\":[\"echo hi\"],\"summary\":\"s\",\"notes\":[\"a\",\"b\"]}").notes())
            .containsExactly("a", "b").inOrder();
        assertThat(SnippetAiResponseSupport.parseLanguageMigration(
            "{\"replacementLines\":[\"echo hi\"],\"summary\":\"s\",\"limitations\":[\"c\"]}").notes())
            .containsExactly("c");
        assertThat(SnippetAiResponseSupport.parseLanguageMigration(
            "{\"replacementLines\":[\"echo hi\"],\"summary\":\"s\",\"notes\":[]}").notes())
            .isEmpty();
    }

    @Test
    void aRewriteOfNearlyEveryLineIsNotDegenerate() {
        String perl = """
            #!/usr/bin/env perl
            use strict;
            use warnings;
            print "hello\\n";
            my @rows = split /\\n/, `cat data.txt`;
            print "$_\\n" for @rows;
            print "done\\n";
            """;
        assertThat(SnippetAiResponseSupport.isDegenerateMigration(MIXED_BASH, perl)).isFalse();
        assertThat(SnippetAiResponseSupport.isDegenerateMigration(MIXED_BASH, "")).isTrue();
        assertThat(SnippetAiResponseSupport.isDegenerateMigration(MIXED_BASH, "print 1;\n")).isTrue();
    }

    @Test
    void scaffoldPreservedIgnoresTheStepVerbButNotTheStructure() {
        String verbSwapped = AZURE_MIXED.replace("- bash: |", "- pwsh: |");
        assertThat(ScriptLanguageMixSupport.scaffoldPreserved(
            HostFormat.AZURE_PIPELINES, AZURE_MIXED, verbSwapped)).isTrue();
        String renamed = AZURE_MIXED.replace("displayName: Build", "displayName: Compile");
        assertThat(ScriptLanguageMixSupport.scaffoldPreserved(
            HostFormat.AZURE_PIPELINES, AZURE_MIXED, renamed)).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private static MigrationPlan plan(String content, String language,
                                      ScriptLanguage target, HostFormat targetHost) {
        LanguageMix mix = ScriptLanguageMixSupport.detect(language, content);
        return new MigrationPlan(mix, target, targetHost);
    }

    private static String migrationResponse(String script, String summary, String... notes) {
        JsonArray lines = new JsonArray();
        for (String line : script.split("\n", -1)) {
            lines.add(line);
        }
        JsonArray noteArray = new JsonArray();
        for (String note : notes) {
            noteArray.add(note);
        }
        JsonObject response = new JsonObject();
        response.add("replacementLines", lines);
        response.addProperty("summary", summary);
        response.add("notes", noteArray);
        return response.toString();
    }

    private static String applyResponse(String script, String summary) {
        JsonArray lines = new JsonArray();
        for (String line : script.split("\n", -1)) {
            lines.add(line);
        }
        JsonObject response = new JsonObject();
        response.add("replacementLines", lines);
        response.addProperty("summary", summary);
        response.add("changes", new JsonArray());
        response.add("implementedRequirements", new JsonArray());
        return response.toString();
    }

    /** Returns one deterministic response per staged request, in order. */
    private static final class SequencedService implements AiService {
        private final Queue<String> responses;
        private final List<AiRequest> requests = new ArrayList<>();

        private SequencedService(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            requests.add(request);
            return new AiExecutionResult(responses.remove(), null, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    private static final class CapturingService implements AiService {
        private final String response;
        private AiRequest lastRequest;
        private int calls;

        private CapturingService(String response) {
            this.response = response;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            this.lastRequest = request;
            this.calls++;
            return new AiExecutionResult(response, null, null);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
