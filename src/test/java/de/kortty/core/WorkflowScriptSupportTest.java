package de.kortty.core;

import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.core.WorkflowScriptSupport.HeaderFacts;
import de.kortty.core.WorkflowScriptSupport.InputHardeningConfig;
import de.kortty.core.WorkflowScriptSupport.InputHardeningOption;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.core.WorkflowScriptSupport.WorkflowContext;
import org.testng.annotations.Test;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class WorkflowScriptSupportTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 6, 16, 14, 30, 0);

    private static HeaderFacts facts(ScriptLanguage lang) {
        return new HeaderFacts(
            WorkflowScriptSupport.defaultScriptName("Install and configure nginx", lang),
            "daniel", "root", "web-prod", WHEN, "Install and configure nginx", "OpenAI GPT");
    }

    // ---------------------------------------------------------------- rule labels

    @Test
    void everyEmittedHardeningRuleResolvesToItsOwnOptionLabel() {
        for (HardeningOption option : HardeningOption.values()) {
            for (boolean declarative : new boolean[] {false, true}) {
                String rules = WorkflowScriptSupport.hardeningRulesText(EnumSet.of(option), declarative);
                for (String bullet : bullets(rules)) {
                    assertThat(WorkflowScriptSupport.ruleLabelKey(bullet))
                        .isEqualTo("ai.workflow.option." + option.name());
                }
            }
        }
    }

    @Test
    void everyEmittedInputHardeningRuleResolvesToALabelInEveryCombination() {
        InputHardeningOption[] options = InputHardeningOption.values();
        for (int mask = 1; mask < (1 << options.length); mask++) {
            EnumSet<InputHardeningOption> combination = EnumSet.noneOf(InputHardeningOption.class);
            for (int bit = 0; bit < options.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    combination.add(options[bit]);
                }
            }
            InputHardeningConfig config = new InputHardeningConfig(
                combination, InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
            List<String> emitted = bullets(
                WorkflowScriptSupport.inputHardeningRulesText(config, ScriptLanguage.BASH));
            assertThat(emitted).isNotEmpty();
            for (String bullet : emitted) {
                assertThat(WorkflowScriptSupport.ruleLabelKey(bullet)).isNotNull();
            }
        }
    }

    @Test
    void aRuleKeepsItsLabelWhenAnotherOptionRewordsIt() {
        // The security-logging rule gains an "and every forced bypass" clause inside its opening
        // words as soon as the force override is also selected; it must still be that option's rule.
        InputHardeningConfig withForce = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.SECURITY_LOGGING, InputHardeningOption.FORCE_OVERRIDE),
            InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        String reworded = bullets(WorkflowScriptSupport.inputHardeningRulesText(withForce, ScriptLanguage.BASH))
            .stream().filter(rule -> rule.startsWith("Report every violation")).findFirst().orElseThrow();
        assertThat(reworded).contains("forced bypass");
        assertThat(WorkflowScriptSupport.ruleLabelKey(reworded))
            .isEqualTo("ai.inputHardening.option.SECURITY_LOGGING");
    }

    @Test
    void aChangedFileSizeLimitDoesNotLoseTheRuleLabel() {
        InputHardeningConfig custom = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.FILE_SIZE_LIMIT), 3_145_728L);
        String rule = bullets(WorkflowScriptSupport.inputHardeningRulesText(custom, ScriptLanguage.BASH))
            .stream().filter(line -> line.startsWith("Define a variable")).findFirst().orElseThrow();
        assertThat(rule).contains("3145728");
        assertThat(WorkflowScriptSupport.ruleLabelKey(rule))
            .isEqualTo("ai.inputHardening.option.FILE_SIZE_LIMIT");
    }

    @Test
    void theSharedGuardRulesAreNotAttributedToOneSubOption() {
        InputHardeningConfig onlyForce = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.FORCE_OVERRIDE),
            InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        List<String> emitted = bullets(
            WorkflowScriptSupport.inputHardeningRulesText(onlyForce, ScriptLanguage.BASH));
        // The bullet that opens the guard block is emitted for every selection, so it belongs to the
        // guard rather than to the one sub-option that happened to be ticked here.
        assertThat(WorkflowScriptSupport.ruleLabelKey(emitted.get(0)))
            .isEqualTo(WorkflowScriptSupport.INPUT_HARDENING_GUARD_LABEL_KEY);
        // The sub-option's own rule keeps its own label.
        String forceRule = emitted.stream()
            .filter(rule -> rule.startsWith("Force override:"))
            .findFirst()
            .orElseThrow();
        assertThat(WorkflowScriptSupport.ruleLabelKey(forceRule))
            .isEqualTo("ai.inputHardening.option.FORCE_OVERRIDE");
        // Everything the guard always writes — including the closing language-idiom rule — stays generic.
        assertThat(WorkflowScriptSupport.ruleLabelKey(emitted.get(emitted.size() - 1)))
            .isEqualTo(WorkflowScriptSupport.INPUT_HARDENING_GUARD_LABEL_KEY);
    }

    @Test
    void textThatIsNotARuleHasNoLabel() {
        assertThat(WorkflowScriptSupport.ruleLabelKey(null)).isNull();
        assertThat(WorkflowScriptSupport.ruleLabelKey("  ")).isNull();
        // An analysis finding's own title must pass through untouched.
        assertThat(WorkflowScriptSupport.ruleLabelKey("Variable wird nicht gequotet")).isNull();
    }

    private static List<String> bullets(String rulesText) {
        return rulesText.lines()
            .map(String::strip)
            .filter(line -> line.startsWith("- ") && line.length() > 2)
            .map(line -> line.substring(2).strip())
            .toList();
    }

    // ---------------------------------------------------------------- ScriptLanguage

    @Test
    void fromIdMapsAliasesAndDefaultsToBash() {
        assertThat(ScriptLanguage.fromId("py")).isEqualTo(ScriptLanguage.PYTHON);
        assertThat(ScriptLanguage.fromId("pwsh")).isEqualTo(ScriptLanguage.POWERSHELL);
        assertThat(ScriptLanguage.fromId("ansible-playbook")).isEqualTo(ScriptLanguage.ANSIBLE);
        assertThat(ScriptLanguage.fromId("yml")).isEqualTo(ScriptLanguage.ANSIBLE);
        assertThat(ScriptLanguage.fromId(null)).isEqualTo(ScriptLanguage.BASH);
        assertThat(ScriptLanguage.fromId("nonsense")).isEqualTo(ScriptLanguage.BASH);
    }

    @Test
    void ansibleIsDeclarativeWithoutShebangAndYamlSnippetLanguage() {
        assertThat(ScriptLanguage.ANSIBLE.isDeclarative()).isTrue();
        assertThat(ScriptLanguage.ANSIBLE.shebang()).isNull();
        assertThat(ScriptLanguage.ANSIBLE.fileExtension()).isEqualTo(".yml");
        assertThat(ScriptLanguage.ANSIBLE.snippetLanguage()).isEqualTo("yaml");

        assertThat(ScriptLanguage.POWERSHELL.isDeclarative()).isFalse();
        assertThat(ScriptLanguage.POWERSHELL.fileExtension()).isEqualTo(".ps1");
        assertThat(ScriptLanguage.POWERSHELL.snippetLanguage()).isEqualTo("powershell");
        assertThat(ScriptLanguage.BASH.shebang()).isEqualTo("#!/usr/bin/env bash");
    }

    // ---------------------------------------------------------------- defaults / options

    @Test
    void defaultsContainAllOptionsIncludingTheFourOptIns() {
        EnumSet<HardeningOption> defaults = HardeningOption.defaults();
        assertThat(defaults).containsAtLeast(
            HardeningOption.STRICT_MODE,
            HardeningOption.PRECONDITION_CHECKS,
            HardeningOption.IDEMPOTENCY,
            HardeningOption.SAFE_MODE,
            HardeningOption.HELP_USAGE);
        assertThat(defaults).hasSize(HardeningOption.values().length);
    }

    @Test
    void everyHardeningOptionContributesExactlyOneIndependentRule() {
        for (boolean declarative : new boolean[] {false, true}) {
            String allRules = WorkflowScriptSupport.hardeningRulesText(HardeningOption.defaults(), declarative);
            for (HardeningOption option : HardeningOption.values()) {
                String onlyThisRule = WorkflowScriptSupport.hardeningRulesText(EnumSet.of(option), declarative);
                assertThat(onlyThisRule).isNotEmpty();
                assertThat(onlyThisRule.lines().count()).isEqualTo(1L);
                assertThat(allRules).contains(onlyThisRule);

                EnumSet<HardeningOption> withoutOption = HardeningOption.defaults();
                withoutOption.remove(option);
                assertThat(WorkflowScriptSupport.hardeningRulesText(withoutOption, declarative))
                    .doesNotContain(onlyThisRule);
            }
        }
    }

    // ---------------------------------------------------------------- languageIdioms

    @Test
    void languageIdiomsCarryTheStrictModeTokensPerLanguage() {
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.BASH)).contains("set -euo pipefail");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.PYTHON)).contains("try/except");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.PYTHON)).contains("sys.exit");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.PERL)).contains("use strict; use warnings;");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.RUBY)).contains("begin/rescue");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.POWERSHELL)).contains("Set-StrictMode -Version Latest");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.POWERSHELL)).contains("$ErrorActionPreference");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.ANSIBLE)).contains("block/rescue/always");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.ANSIBLE)).contains("assert");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.WINDOWS_CMD)).contains("@echo off");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.WINDOWS_CMD)).contains("errorlevel");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.APPLESCRIPT)).contains("on error");
        assertThat(WorkflowScriptSupport.languageIdioms(ScriptLanguage.APPLESCRIPT)).contains("do shell script");
    }

    @Test
    void clearedHardeningLeavesOnlyOneNeutralLanguageValidityIdiomPerLanguage() {
        EnumSet<HardeningOption> none = EnumSet.noneOf(HardeningOption.class);
        for (ScriptLanguage language : ScriptLanguage.values()) {
            String neutral = WorkflowScriptSupport.languageIdioms(language, none);
            assertThat(neutral).isNotEmpty();
            assertThat(neutral.lines().count()).isEqualTo(1L);
            assertThat(WorkflowScriptSupport.languageIdioms(language)).contains(neutral);
        }
    }

    @Test
    void strictModeHasALanguageSpecificAbortBehaviourInEveryTargetLanguage() {
        EnumSet<HardeningOption> none = EnumSet.noneOf(HardeningOption.class);
        for (ScriptLanguage language : ScriptLanguage.values()) {
            String neutral = WorkflowScriptSupport.languageIdioms(language, none);
            String strict = WorkflowScriptSupport.languageIdioms(
                language, EnumSet.of(HardeningOption.STRICT_MODE));
            assertThat(strict).isNotEqualTo(neutral);
            assertThat(strict.lines().count()).isGreaterThan(neutral.lines().count());
        }
    }

    // ---------------------------------------------------------------- Windows-CMD / AppleScript

    @Test
    void newLanguagesMapAndExposeExpectedTraits() {
        assertThat(ScriptLanguage.fromId("cmd")).isEqualTo(ScriptLanguage.WINDOWS_CMD);
        assertThat(ScriptLanguage.fromId("bat")).isEqualTo(ScriptLanguage.WINDOWS_CMD);
        assertThat(ScriptLanguage.fromId("windows-cmd")).isEqualTo(ScriptLanguage.WINDOWS_CMD);
        assertThat(ScriptLanguage.fromId("applescript")).isEqualTo(ScriptLanguage.APPLESCRIPT);
        assertThat(ScriptLanguage.fromId("osascript")).isEqualTo(ScriptLanguage.APPLESCRIPT);

        assertThat(ScriptLanguage.WINDOWS_CMD.fileExtension()).isEqualTo(".cmd");
        assertThat(ScriptLanguage.WINDOWS_CMD.shebang()).isNull();
        assertThat(ScriptLanguage.WINDOWS_CMD.leadLine()).isEqualTo("@echo off");
        assertThat(ScriptLanguage.WINDOWS_CMD.commentPrefix()).isEqualTo("REM");

        assertThat(ScriptLanguage.APPLESCRIPT.fileExtension()).isEqualTo(".applescript");
        assertThat(ScriptLanguage.APPLESCRIPT.shebang()).isEqualTo("#!/usr/bin/osascript");
        assertThat(ScriptLanguage.APPLESCRIPT.commentPrefix()).isEqualTo("--");
    }

    @Test
    void windowsCmdHeaderGoesAfterEchoOffWithRemComments() {
        String script = "@echo off\necho hello\n";
        String out = WorkflowScriptSupport.ensureHeaderInjected(script, ScriptLanguage.WINDOWS_CMD, facts(ScriptLanguage.WINDOWS_CMD));
        assertThat(out).startsWith("@echo off\n");
        assertThat(out).contains("REM Script:");
        assertThat(out).contains("REM Author:");
        // header sits between @echo off and the body, not echoed to the console
        assertThat(out.indexOf("REM Script:")).isGreaterThan(out.indexOf("@echo off"));
        assertThat(out.indexOf("REM Script:")).isLessThan(out.indexOf("echo hello"));
    }

    @Test
    void windowsCmdHeaderPrependsEchoOffWhenMissing() {
        String out = WorkflowScriptSupport.ensureHeaderInjected("dir\n", ScriptLanguage.WINDOWS_CMD, facts(ScriptLanguage.WINDOWS_CMD));
        assertThat(out).startsWith("@echo off\n");
        assertThat(out).contains("REM Script:");
    }

    @Test
    void appleScriptHeaderUsesDashCommentsAfterShebang() {
        String script = "#!/usr/bin/osascript\nlog \"hi\"\n";
        String out = WorkflowScriptSupport.ensureHeaderInjected(script, ScriptLanguage.APPLESCRIPT, facts(ScriptLanguage.APPLESCRIPT));
        assertThat(out).startsWith("#!/usr/bin/osascript\n");
        assertThat(out).contains("-- Script:");
        assertThat(out).contains("-- Author:");
    }

    @Test
    void systemPromptForWindowsCmdDemandsEchoOffNotShebang() {
        String sys = WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.WINDOWS_CMD, HardeningOption.defaults());
        assertThat(sys).contains("@echo off");
        assertThat(sys).doesNotContain("#!/usr/bin/env");
        assertThat(sys).contains("errorlevel");
    }

    // ---------------------------------------------------------------- system / user prompts

    @Test
    void systemPromptForbidsFencesAndDemandsShebangForScripts() {
        String sys = WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.BASH, HardeningOption.defaults());
        assertThat(sys).contains("No prose");
        assertThat(sys).contains("```");           // mentions the fence it must NOT emit
        assertThat(sys).contains("#!/usr/bin/env bash");
        assertThat(sys).contains("set -euo pipefail");
        // opt-in option rules present
        assertThat(sys).contains("--dry-run");
        assertThat(sys).contains("--help");
    }

    @Test
    void systemPromptForAnsibleDemandsYamlStartAndAnsibleOptionMapping() {
        String sys = WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.ANSIBLE, HardeningOption.defaults());
        assertThat(sys).contains("'---'");
        assertThat(sys).contains("ansible-playbook");
        assertThat(sys).doesNotContain("#!/usr/bin/env");
        assertThat(sys).contains("--check");          // SAFE_MODE mapped to Ansible check mode
        assertThat(sys).contains("idempotent");       // IDEMPOTENCY mapped
    }

    @Test
    void clearedHardeningDoesNotLeakLanguageSpecificHardeningIntoTheSystemPrompt() {
        EnumSet<HardeningOption> none = EnumSet.noneOf(HardeningOption.class);

        String bash = WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.BASH, none);
        assertThat(bash).doesNotContain("ADDITIONAL REQUIREMENTS:");
        assertThat(bash).doesNotContain("Robust error handling");
        assertThat(bash).doesNotContain("set -euo pipefail");
        assertThat(bash).doesNotContain("ERR trap");
        assertThat(bash).doesNotContain("command -v <cmd>");
        assertThat(bash).doesNotContain("meaningful non-zero exit codes");

        String ansible = WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.ANSIBLE, none);
        assertThat(ansible).doesNotContain("ADDITIONAL REQUIREMENTS:");
        assertThat(ansible).doesNotContain("block/rescue/always");
        assertThat(ansible).doesNotContain("assert and failed_when");
        assertThat(ansible).doesNotContain("idempotent modules");
        assertThat(ansible).doesNotContain("any_errors_fatal");
        assertThat(ansible).doesNotContain("vars: section");

        String swarm = WorkflowScriptSupport.buildSwarmSystemPrompt(
            ScriptLanguage.BASH, none, null, WorkflowScriptSupport.HeaderMode.AUTO);
        assertThat(swarm).contains("MULTI-HOST ORCHESTRATION:");
        assertThat(swarm).doesNotContain("set -euo pipefail");
    }

    @Test
    void partialHardeningPromptContainsOnlySelectedOptionRulesAndIdioms() {
        String strictOnly = WorkflowScriptSupport.buildSystemPrompt(
            ScriptLanguage.BASH, EnumSet.of(HardeningOption.STRICT_MODE));

        assertThat(strictOnly).contains("Enable the language's strict / abort-on-error mode");
        assertThat(strictOnly).contains("set -euo pipefail");
        assertThat(strictOnly).doesNotContain("ERR trap");
        assertThat(strictOnly).doesNotContain("--verbose/-v");
        assertThat(strictOnly).doesNotContain("meaningful non-zero exit codes");
        assertThat(strictOnly).doesNotContain("command -v <cmd>");
        assertThat(strictOnly).doesNotContain("--dry-run");
        assertThat(strictOnly).doesNotContain("--help/usage");
    }

    @Test
    void userPromptEmbedsRequestLanguageAndContextButNotMetadata() {
        WorkflowContext ctx = new WorkflowContext("apt-get install nginx", false, 2, 2);
        String user = WorkflowScriptSupport.buildUserPrompt(
            ScriptLanguage.PYTHON, facts(ScriptLanguage.PYTHON), ctx, HardeningOption.defaults(), "Keep it short");
        // Metadata is injected deterministically by the app, so it must NOT be fed to the model.
        assertThat(user).doesNotContain("daniel");
        assertThat(user).doesNotContain("root@web-prod");
        assertThat(user).doesNotContain("2026-06-16 14:30:00");
        assertThat(user).doesNotContain("HEADER FACTS");
        // The originating request is still supplied as context for the functional description.
        assertThat(user).contains("Install and configure nginx");
        assertThat(user).ignoringCase().contains("description");
        assertThat(user).contains("Python");                 // language name steers skill selection
        assertThat(user).contains("Keep it short");          // extra instructions
        assertThat(user).contains("apt-get install nginx");  // reproduction context
    }

    @Test
    void userPromptNotesTruncationWhenContextTruncated() {
        WorkflowContext ctx = new WorkflowContext("partial", true, 3, 9);
        String user = WorkflowScriptSupport.buildUserPrompt(
            ScriptLanguage.BASH, facts(ScriptLanguage.BASH), ctx, HardeningOption.defaults(), null);
        assertThat(user).contains("3");
        assertThat(user).contains("9");
        assertThat(user).ignoringCase().contains("infer");
    }

    // ---------------------------------------------------------------- stripCodeFences

    @Test
    void stripsLanguageTaggedFence() {
        String fenced = "```bash\n#!/usr/bin/env bash\necho hi\n```";
        assertThat(WorkflowScriptSupport.stripCodeFences(fenced)).isEqualTo("#!/usr/bin/env bash\necho hi");
    }

    @Test
    void stripsPlainFence() {
        String fenced = "```\nplaybook\n```";
        assertThat(WorkflowScriptSupport.stripCodeFences(fenced)).isEqualTo("playbook");
    }

    @Test
    void leavesUnfencedContentUntouchedAndPreservesInnerBackticks() {
        String script = "#!/usr/bin/env bash\nVAR=$(echo `date`)";
        assertThat(WorkflowScriptSupport.stripCodeFences(script)).isEqualTo(script);
        assertThat(WorkflowScriptSupport.stripCodeFences(null)).isEmpty();
    }

    @Test
    void stripsClosingFenceAndTrailingProse() {
        String fenced = "```bash\n#!/usr/bin/env bash\necho hi\n```\nThis script installs nginx.";
        String out = WorkflowScriptSupport.stripCodeFences(fenced);
        assertThat(out).isEqualTo("#!/usr/bin/env bash\necho hi");
        assertThat(out).doesNotContain("```");
        assertThat(out).doesNotContain("installs nginx");
    }

    @Test
    void normalizesCrlfLineEndings() {
        String fenced = "```bash\r\nline1\r\nline2\r\n```";
        String out = WorkflowScriptSupport.stripCodeFences(fenced);
        assertThat(out).isEqualTo("line1\nline2");
        assertThat(out).doesNotContain("\r");
    }

    // ---------------------------------------------------------------- ensureHeaderInjected

    @Test
    void injectsHeaderAfterShebangWhenMissing() {
        String script = "#!/usr/bin/env bash\necho hello";
        String out = WorkflowScriptSupport.ensureHeaderInjected(script, ScriptLanguage.BASH, facts(ScriptLanguage.BASH));
        String[] lines = out.split("\n", 3);
        assertThat(lines[0]).isEqualTo("#!/usr/bin/env bash");   // shebang stays on line 1
        assertThat(out).contains("# Author:");
        assertThat(out).contains("daniel");
        assertThat(out).contains("2026-06-16 14:30:00");
        assertThat(out).contains("echo hello");
    }

    @Test
    void injectsHeaderAfterYamlMarkerForAnsible() {
        String playbook = "---\n- hosts: all";
        String out = WorkflowScriptSupport.ensureHeaderInjected(playbook, ScriptLanguage.ANSIBLE, facts(ScriptLanguage.ANSIBLE));
        assertThat(out.split("\n", 2)[0]).isEqualTo("---");
        assertThat(out).contains("# Author:");
        assertThat(out).contains("- hosts: all");
    }

    @Test
    void prependsShebangAndHeaderWhenModelOmittedThem() {
        String script = "echo hi";
        String out = WorkflowScriptSupport.ensureHeaderInjected(script, ScriptLanguage.BASH, facts(ScriptLanguage.BASH));
        assertThat(out.split("\n", 2)[0]).isEqualTo("#!/usr/bin/env bash");
        assertThat(out).contains("# Script:");
    }

    @Test
    void injectsHeaderWhenCreatorAndDateOnlyAppearInCommandsNotComments() {
        // Regression: creator + date occurring in ordinary command text (paths, filenames) must NOT
        // be mistaken for an existing header — the header block must still be injected.
        String script = "#!/usr/bin/env bash\ncp /home/daniel/data /tmp/backup-2026-06-16.tar";
        String out = WorkflowScriptSupport.ensureHeaderInjected(script, ScriptLanguage.BASH, facts(ScriptLanguage.BASH));
        assertThat(out).contains("# Author:");
        assertThat(out).contains("# Script:");
    }

    @Test
    void injectHeaderOverridePrependsAfterShebang() {
        String script = "#!/usr/bin/env bash\necho hi";
        String out = WorkflowScriptSupport.injectHeaderOverride(script, ScriptLanguage.BASH, "# my header\n# line 2");
        String[] lines = out.split("\n", 4);
        assertThat(lines[0]).isEqualTo("#!/usr/bin/env bash");
        assertThat(lines[1]).isEqualTo("# my header");
        assertThat(lines[2]).isEqualTo("# line 2");
        assertThat(out).contains("echo hi");
    }

    @Test
    void injectHeaderOverrideAfterYamlMarkerForAnsible() {
        String out = WorkflowScriptSupport.injectHeaderOverride("---\n- hosts: all", ScriptLanguage.ANSIBLE, "# hdr");
        assertThat(out.split("\n", 2)[0]).isEqualTo("---");
        assertThat(out).contains("# hdr");
        assertThat(out).contains("- hosts: all");
    }

    @Test
    void injectHeaderOverrideBlankLeavesScriptUnchanged() {
        String script = "#!/usr/bin/env bash\necho hi";
        assertThat(WorkflowScriptSupport.injectHeaderOverride(script, ScriptLanguage.BASH, "")).isEqualTo(script);
        assertThat(WorkflowScriptSupport.injectHeaderOverride(script, ScriptLanguage.BASH, "   ")).isEqualTo(script);
    }

    @Test
    void systemPromptForbidsMetadataHeaderInEveryMode() {
        for (WorkflowScriptSupport.HeaderMode mode : WorkflowScriptSupport.HeaderMode.values()) {
            String sys = WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.BASH, HardeningOption.defaults(), mode);
            assertThat(sys).ignoringCase().contains("do not emit any metadata header");
            assertThat(sys).doesNotContain("Include a header comment block");
        }
    }

    @Test
    void systemPromptRequestsDescriptionExceptForNoneMode() {
        String auto = WorkflowScriptSupport.buildSystemPrompt(
            ScriptLanguage.BASH, HardeningOption.defaults(), WorkflowScriptSupport.HeaderMode.AUTO);
        String custom = WorkflowScriptSupport.buildSystemPrompt(
            ScriptLanguage.BASH, HardeningOption.defaults(), WorkflowScriptSupport.HeaderMode.CUSTOM);
        String none = WorkflowScriptSupport.buildSystemPrompt(
            ScriptLanguage.BASH, HardeningOption.defaults(), WorkflowScriptSupport.HeaderMode.NONE);
        assertThat(auto).contains("concise description block");
        assertThat(custom).contains("concise description block");
        assertThat(none).doesNotContain("concise description block");
    }

    @Test
    void userPromptNeverEmitsHeaderFactsBlockAndRequestsDescription() {
        WorkflowContext ctx = new WorkflowContext("apt-get install nginx", false, 1, 1);
        String custom = WorkflowScriptSupport.buildUserPrompt(
            ScriptLanguage.BASH, facts(ScriptLanguage.BASH), ctx, HardeningOption.defaults(), null,
            WorkflowScriptSupport.HeaderMode.CUSTOM);
        assertThat(custom).doesNotContain("HEADER FACTS");
        assertThat(custom).ignoringCase().contains("do not emit any metadata header");
        assertThat(custom).ignoringCase().contains("description");
        assertThat(custom).contains("apt-get install nginx"); // reproduction context still present
        // NONE omits the description request; AUTO/CUSTOM include it. Neither emits HEADER FACTS.
        String none = WorkflowScriptSupport.buildUserPrompt(
            ScriptLanguage.BASH, facts(ScriptLanguage.BASH), ctx, HardeningOption.defaults(), null,
            WorkflowScriptSupport.HeaderMode.NONE);
        assertThat(none).doesNotContain("HEADER FACTS");
        assertThat(none).doesNotContain("Begin with a short functional description");
    }

    @Test
    void leavesExistingHeaderUntouched() {
        String script = "#!/usr/bin/env bash\n# Author: daniel\n# Created: 2026-06-16 14:30:00\necho hi";
        String out = WorkflowScriptSupport.ensureHeaderInjected(script, ScriptLanguage.BASH, facts(ScriptLanguage.BASH));
        // No second header block injected (only one "# Author:" occurrence).
        assertThat(out.split("# Author:", -1).length - 1).isEqualTo(1);
    }

    // ---------------------------------------------------------------- defaultScriptName

    @Test
    void matchOperatingSystemMapsDistrosToConfiguredSystemEntries() {
        java.util.List<String> list = java.util.List.of("Windows", "MacOS", "Linux");
        assertThat(WorkflowScriptSupport.matchOperatingSystem("Fedora Linux 44 (Workstation Edition)", list)).isEqualTo("Linux");
        assertThat(WorkflowScriptSupport.matchOperatingSystem("Ubuntu 22.04", list)).isEqualTo("Linux");
        assertThat(WorkflowScriptSupport.matchOperatingSystem("Red Hat Enterprise Linux 9", list)).isEqualTo("Linux");
        assertThat(WorkflowScriptSupport.matchOperatingSystem("openSUSE Leap 15", list)).isEqualTo("Linux");
        assertThat(WorkflowScriptSupport.matchOperatingSystem("Darwin", list)).isEqualTo("MacOS");
        assertThat(WorkflowScriptSupport.matchOperatingSystem("Windows 11 Pro", list)).isEqualTo("Windows");
        // Returns the actual list entry verbatim (preserves casing).
        assertThat(WorkflowScriptSupport.matchOperatingSystem("ubuntu", java.util.List.of("linux"))).isEqualTo("linux");
        // Only names present in the System list are used.
        assertThat(WorkflowScriptSupport.matchOperatingSystem("Ubuntu", java.util.List.of("Windows", "MacOS"))).isNull();
        assertThat(WorkflowScriptSupport.matchOperatingSystem("Plan 9", list)).isNull();
        assertThat(WorkflowScriptSupport.matchOperatingSystem(null, list)).isNull();
        assertThat(WorkflowScriptSupport.matchOperatingSystem("Linux", null)).isNull();
    }

    @Test
    void defaultScriptNameSlugifiesWithUnderscoresAndAddsExtension() {
        assertThat(WorkflowScriptSupport.defaultScriptName("Install & configure nginx!", ScriptLanguage.BASH))
            .isEqualTo("install_configure_nginx.sh");
        assertThat(WorkflowScriptSupport.defaultScriptName("  ", ScriptLanguage.PYTHON))
            .isEqualTo("workflow_script.py");
        assertThat(WorkflowScriptSupport.defaultScriptName("Deploy app", ScriptLanguage.ANSIBLE))
            .isEqualTo("deploy_app.yml");
        assertThat(WorkflowScriptSupport.defaultScriptName("Steuerung", ScriptLanguage.PERL))
            .isEqualTo("steuerung.pl");
        assertThat(WorkflowScriptSupport.defaultScriptName("Backup job", ScriptLanguage.POWERSHELL))
            .isEqualTo("backup_job.ps1");
        assertThat(WorkflowScriptSupport.defaultScriptName("List files", ScriptLanguage.RUBY))
            .isEqualTo("list_files.rb");
        assertThat(WorkflowScriptSupport.defaultScriptName("a-b-c", ScriptLanguage.BASH))
            .doesNotContain("-");
    }

    @Test
    void defaultScriptNameStaysShortForLongPrompts() {
        String name = WorkflowScriptSupport.defaultScriptName(
            "show the most failures in file /var/log/messages and summarize them", ScriptLanguage.PERL);

        // Filler words ("show", "the", "in") are dropped; only the first few meaningful words remain.
        assertThat(name).isEqualTo("most_failures_file.pl");

        String stem = WorkflowScriptSupport.buildShortScriptStem(
            "show the most failures in file /var/log/messages and summarize them");
        assertThat(stem.length()).isAtMost(28);
        assertThat(stem.split("_")).hasLength(3);
    }

    @Test
    void buildShortScriptStemDropsFillerWordsAndCaps() {
        // German polite/article filler words are dropped too.
        assertThat(WorkflowScriptSupport.buildShortScriptStem("Bitte zeige mir die Festplattenbelegung"))
            .isEqualTo("festplattenbelegung");
        // All-filler prompt falls back to the raw words rather than an empty stem.
        assertThat(WorkflowScriptSupport.buildShortScriptStem("show me the")).isNotEmpty();
        // A single very long word is hard-capped.
        assertThat(WorkflowScriptSupport.buildShortScriptStem("supercalifragilisticexpialidocious_extra_long").length())
            .isAtMost(28);
        // Blank prompt falls back to the default stem.
        assertThat(WorkflowScriptSupport.buildShortScriptStem("   ")).isEqualTo("workflow_script");
    }

    @Test
    void hardeningOptionsSerializeAndParseRoundTrip() {
        EnumSet<HardeningOption> chosen = EnumSet.of(
            HardeningOption.STRICT_MODE, HardeningOption.SAFE_MODE, HardeningOption.HELP_USAGE);

        String csv = HardeningOption.serializeOptions(chosen);
        assertThat(HardeningOption.parseOptions(csv)).isEqualTo(chosen);

        // null means "never saved" -> all options; an empty string means a saved "clear" -> none.
        assertThat(HardeningOption.parseOptions(null)).isEqualTo(HardeningOption.defaults());
        assertThat(HardeningOption.parseOptions("")).isEmpty();
        assertThat(HardeningOption.serializeOptions(EnumSet.noneOf(HardeningOption.class))).isEmpty();
        // Unknown / stale tokens are ignored, valid ones kept.
        assertThat(HardeningOption.parseOptions("STRICT_MODE, BOGUS ,IDEMPOTENCY"))
            .isEqualTo(EnumSet.of(HardeningOption.STRICT_MODE, HardeningOption.IDEMPOTENCY));
    }

    // ---------------------------------------------------------------- input hardening

    private static InputHardeningConfig allInputHardening() {
        return new InputHardeningConfig(InputHardeningOption.defaults(),
            InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
    }

    @Test
    void inputHardeningDefaultsContainAllSubOptions() {
        assertThat(InputHardeningOption.defaults()).hasSize(InputHardeningOption.values().length);
    }

    @Test
    void inputHardeningOptionsSerializeAndParseRoundTrip() {
        EnumSet<InputHardeningOption> chosen = EnumSet.of(
            InputHardeningOption.PARAM_VALIDATION, InputHardeningOption.FILE_CHECKS);

        String csv = InputHardeningOption.serializeOptions(chosen);
        assertThat(InputHardeningOption.parseOptions(csv)).isEqualTo(chosen);

        // null means "never saved" -> all options; an empty string means a saved "clear" -> none.
        assertThat(InputHardeningOption.parseOptions(null)).isEqualTo(InputHardeningOption.defaults());
        assertThat(InputHardeningOption.parseOptions("")).isEmpty();
        assertThat(InputHardeningOption.serializeOptions(EnumSet.noneOf(InputHardeningOption.class))).isEmpty();
        // Unknown / stale tokens are ignored, valid ones kept.
        assertThat(InputHardeningOption.parseOptions("PARAM_VALIDATION, BOGUS ,FILE_CHECKS"))
            .isEqualTo(EnumSet.of(InputHardeningOption.PARAM_VALIDATION, InputHardeningOption.FILE_CHECKS));
    }

    @Test
    void inputHardeningConfigPreservesUnlimitedZeroAndCopiesDefensively() {
        EnumSet<InputHardeningOption> mutable = EnumSet.of(InputHardeningOption.PARAM_VALIDATION);
        InputHardeningConfig config = new InputHardeningConfig(mutable, -1);
        InputHardeningConfig unlimited = new InputHardeningConfig(mutable, 0);
        mutable.add(InputHardeningOption.FORCE_OVERRIDE);

        assertThat(config.options()).containsExactly(InputHardeningOption.PARAM_VALIDATION);
        assertThat(config.maxFileSizeBytes()).isEqualTo(InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        assertThat(unlimited.maxFileSizeBytes()).isEqualTo(0);
        assertThat(config.isEnabled()).isTrue();
        assertThat(InputHardeningConfig.disabled().isEnabled()).isFalse();
    }

    @Test
    void inputHardeningRulesCarryTheCoreContract() {
        String rules = WorkflowScriptSupport.inputHardeningRulesText(allInputHardening(), ScriptLanguage.BASH);

        assertThat(rules).contains("INPUT HARDENING guard block");
        assertThat(rules).contains("built-ins / standard library wherever possible");
        assertThat(rules).contains("MAX_FILE_SIZE=10485760");
        assertThat(rules).doesNotContain("KORTTY_MAX_FILE_SIZE");
        assertThat(rules).contains("(bytes; 10 MB)");
        assertThat(rules).contains("before any operation reads even one byte");
        assertThat(rules).contains("Never calculate the size by reading or streaming the file");
        assertThat(rules).contains("FORCE");
        assertThat(rules).contains("--force");
        assertThat(rules).contains("stderr");
        assertThat(rules).contains("64 for parameter violations");
        assertThat(rules).contains("65 for a file that fails the format or size checks");
        assertThat(rules).contains("66 for a missing or unreadable input file");
        assertThat(rules).contains("SECURITY:");
        assertThat(rules).contains("exact expected parameter count");
        assertThat(rules).contains("never more than 4096");
        assertThat(rules).contains("file --mime-type");
        assertThat(rules.indexOf("verify the file exists")).isLessThan(rules.indexOf("MAX_FILE_SIZE="));
        assertThat(rules.indexOf("MAX_FILE_SIZE=")).isLessThan(rules.indexOf("scan the first 512"));
    }

    @Test
    void inputHardeningRulesEmbedTheConfiguredMaxFileSize() {
        InputHardeningConfig fiveMb = new InputHardeningConfig(InputHardeningOption.defaults(), 5_242_880L);
        String rules = WorkflowScriptSupport.inputHardeningRulesText(fiveMb, ScriptLanguage.BASH);

        assertThat(rules).contains("MAX_FILE_SIZE=5242880");
        assertThat(rules).doesNotContain("10485760");
    }

    @Test
    void inputHardeningRulesTreatZeroFileSizeLimitAsUnlimited() {
        InputHardeningConfig unlimited = new InputHardeningConfig(InputHardeningOption.defaults(), 0);
        String rules = WorkflowScriptSupport.inputHardeningRulesText(unlimited, ScriptLanguage.BASH);

        assertThat(rules).contains("MAX_FILE_SIZE=0");
        assertThat(rules).contains("(bytes; unlimited)");
        assertThat(rules).contains("treat the size as unlimited and skip the size check entirely");
        assertThat(rules).contains("When MAX_FILE_SIZE is greater than 0");
    }

    @Test
    void inputHardeningRulesAreLanguageAware() {
        InputHardeningConfig config = allInputHardening();

        String bash = WorkflowScriptSupport.inputHardeningRulesText(config, ScriptLanguage.BASH);
        assertThat(bash).contains("GNU stat -c %s");
        assertThat(bash).contains("BSD/macOS stat -f %z");
        assertThat(bash).contains("for example with wc -c");
        assertThat(bash).doesNotContain("wc -c <");
        String python = WorkflowScriptSupport.inputHardeningRulesText(config, ScriptLanguage.PYTHON);
        assertThat(python).contains("os.path.getsize");
        assertThat(python).contains("sys.argv");
        String perl = WorkflowScriptSupport.inputHardeningRulesText(config, ScriptLanguage.PERL);
        assertThat(perl).contains("taint mode (-T");
        assertThat(perl).contains("untaint");
        assertThat(WorkflowScriptSupport.inputHardeningRulesText(config, ScriptLanguage.RUBY))
            .contains("File.binread");
        // Unknown snippet languages get the generic implementation bullet, no bash idioms.
        String generic = WorkflowScriptSupport.inputHardeningRulesText(config, null);
        assertThat(generic).contains("native argument-count and string-validation facilities");
        assertThat(generic).doesNotContain("GNU stat");
    }

    @Test
    void inputHardeningSubOptionsGateTheirRules() {
        InputHardeningConfig paramOnly = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.PARAM_VALIDATION), InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        String rules = WorkflowScriptSupport.inputHardeningRulesText(paramOnly, ScriptLanguage.BASH);

        assertThat(rules).contains("character allowlist");
        assertThat(rules).doesNotContain("MAX_FILE_SIZE");
        assertThat(rules).doesNotContain("FORCE");
        assertThat(rules).doesNotContain("--force");
        assertThat(rules).doesNotContain("SECURITY:");
        assertThat(rules).doesNotContain("file --mime-type");
        assertThat(rules).doesNotContain("set -u");
        // A param-only guard must not name file-related exit codes it will never use.
        assertThat(rules).doesNotContain("65 for a file");
        assertThat(rules).doesNotContain("66 for a missing");
        assertThat(WorkflowScriptSupport.inputHardeningRulesText(InputHardeningConfig.disabled(),
            ScriptLanguage.BASH)).isEmpty();
        assertThat(WorkflowScriptSupport.inputHardeningRulesText(null, ScriptLanguage.BASH)).isEmpty();

        String genericRules = WorkflowScriptSupport.inputHardeningRulesText(paramOnly, null);
        assertThat(genericRules).contains("argument-count and string-validation facilities");
        assertThat(genericRules).doesNotContain("file tests");
        assertThat(genericRules).doesNotContain("file-size queries");
        assertThat(genericRules).doesNotContain("environment-variable access");
    }

    @Test
    void inputHardeningFileExitCodesAndHelpersMatchOnlyTheSelectedChecks() {
        InputHardeningConfig fileChecksOnly = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.FILE_CHECKS), InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        String formatRules = WorkflowScriptSupport.inputHardeningRulesText(fileChecksOnly, ScriptLanguage.BASH);
        assertThat(formatRules).contains("65 for a file that fails the format check");
        assertThat(formatRules).contains("66 for a missing or unreadable input file");
        assertThat(formatRules).contains("'file' command");
        assertThat(formatRules).doesNotContain("MAX_FILE_SIZE");
        assertThat(formatRules).doesNotContain("size checks");

        InputHardeningConfig sizeOnly = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.FILE_SIZE_LIMIT), InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        String sizeRules = WorkflowScriptSupport.inputHardeningRulesText(sizeOnly, ScriptLanguage.BASH);
        assertThat(sizeRules).contains("65 for a file that exceeds the size limit");
        assertThat(sizeRules).contains("MAX_FILE_SIZE=10485760");
        assertThat(sizeRules).doesNotContain("format check");
        assertThat(sizeRules).doesNotContain("'file' command");
        assertThat(sizeRules).doesNotContain("66 for a missing or unreadable input file");
    }

    @Test
    void inputHardeningSecurityLoggingNamesForcedBypassesOnlyWhenOverrideIsSelected() {
        InputHardeningConfig loggingOnly = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.SECURITY_LOGGING), InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        String loggingRules = WorkflowScriptSupport.inputHardeningRulesText(loggingOnly, ScriptLanguage.PYTHON);
        assertThat(loggingRules).contains("SECURITY:");
        assertThat(loggingRules).doesNotContain("forced bypass");
        assertThat(loggingRules).doesNotContain("FORCE");
        assertThat(loggingRules).doesNotContain("--force");

        InputHardeningConfig loggingAndOverride = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.SECURITY_LOGGING, InputHardeningOption.FORCE_OVERRIDE),
            InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        String combinedRules = WorkflowScriptSupport.inputHardeningRulesText(
            loggingAndOverride, ScriptLanguage.PYTHON);
        assertThat(combinedRules).contains("every forced bypass");
        assertThat(combinedRules).contains("FORCE");
        assertThat(combinedRules).contains("--force");
    }

    @Test
    void inputHardeningLanguageIdiomsAreGatedBySubOptionsToo() {
        // The per-language implementation bullet must not teach the mechanics of deselected
        // sub-options — most importantly the FORCE bypass, which weakens the guard.
        InputHardeningConfig paramOnly = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.PARAM_VALIDATION), InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        for (ScriptLanguage lang : new ScriptLanguage[] {
            ScriptLanguage.BASH, ScriptLanguage.PYTHON, ScriptLanguage.PERL, ScriptLanguage.RUBY}) {
            String rules = WorkflowScriptSupport.inputHardeningRulesText(paramOnly, lang);
            assertThat(rules).doesNotContain("FORCE");
            assertThat(rules).doesNotContain("--force");
            assertThat(rules).doesNotContain("binread");
            assertThat(rules).doesNotContain("getsize");
            assertThat(rules).doesNotContain("wc -c");
            assertThat(rules).doesNotContain("512");
        }
        // A config without any language-mappable sub-option still gets a coherent generic lead.
        InputHardeningConfig loggingOnly = new InputHardeningConfig(
            EnumSet.of(InputHardeningOption.SECURITY_LOGGING), InputHardeningConfig.DEFAULT_MAX_FILE_SIZE_BYTES);
        String logging = WorkflowScriptSupport.inputHardeningRulesText(loggingOnly, ScriptLanguage.PYTHON);
        assertThat(logging).contains("Python standard library only.");
        assertThat(logging).doesNotContain("FORCE");
        assertThat(logging).doesNotContain("--force");
        assertThat(logging).doesNotContain("64 for parameter violations");
    }

    @Test
    void inputHardeningRulesAreEmptyForAnsible() {
        assertThat(WorkflowScriptSupport.inputHardeningRulesText(allInputHardening(), ScriptLanguage.ANSIBLE))
            .isEmpty();
    }

    @Test
    void snippetLanguageSupportRejectsOnlyDeclarativeYamlAndAnsible() {
        assertThat(WorkflowScriptSupport.supportsInputHardeningForSnippet("bash")).isTrue();
        assertThat(WorkflowScriptSupport.supportsInputHardeningForSnippet("python")).isTrue();
        assertThat(WorkflowScriptSupport.supportsInputHardeningForSnippet("javascript")).isTrue();
        assertThat(WorkflowScriptSupport.supportsInputHardeningForSnippet(null)).isTrue();
        assertThat(WorkflowScriptSupport.supportsInputHardeningForSnippet("yaml")).isFalse();
        assertThat(WorkflowScriptSupport.supportsInputHardeningForSnippet(" YML ")).isFalse();
        assertThat(WorkflowScriptSupport.supportsInputHardeningForSnippet("ansible-playbook")).isFalse();
    }

    @Test
    void systemPromptIncludesInputHardeningSectionOnlyWhenEnabled() {
        String withGuard = WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.BASH, HardeningOption.defaults(),
            WorkflowScriptSupport.HeaderMode.AUTO, allInputHardening());
        assertThat(withGuard).contains("INPUT HARDENING:");
        assertThat(withGuard).contains("MAX_FILE_SIZE=10485760");

        String without = WorkflowScriptSupport.buildSystemPrompt(
            ScriptLanguage.BASH, HardeningOption.defaults(), WorkflowScriptSupport.HeaderMode.AUTO);
        assertThat(without).doesNotContain("INPUT HARDENING:");
        // A disabled config must be byte-identical to the legacy overload's output.
        assertThat(WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.BASH, HardeningOption.defaults(),
            WorkflowScriptSupport.HeaderMode.AUTO, InputHardeningConfig.disabled())).isEqualTo(without);

        String swarm = WorkflowScriptSupport.buildSwarmSystemPrompt(ScriptLanguage.BASH, HardeningOption.defaults(),
            null, WorkflowScriptSupport.HeaderMode.AUTO, allInputHardening());
        assertThat(swarm).contains("INPUT HARDENING:");
        assertThat(swarm).contains("MULTI-HOST ORCHESTRATION:");
    }
}
