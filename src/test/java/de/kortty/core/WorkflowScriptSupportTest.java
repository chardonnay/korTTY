package de.kortty.core;

import de.kortty.core.WorkflowScriptSupport.HardeningOption;
import de.kortty.core.WorkflowScriptSupport.HeaderFacts;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.core.WorkflowScriptSupport.WorkflowContext;
import org.testng.annotations.Test;

import java.time.LocalDateTime;
import java.util.EnumSet;

import static com.google.common.truth.Truth.assertThat;

class WorkflowScriptSupportTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 6, 16, 14, 30, 0);

    private static HeaderFacts facts(ScriptLanguage lang) {
        return new HeaderFacts(
            WorkflowScriptSupport.defaultScriptName("Install and configure nginx", lang),
            "daniel", "root", "web-prod", WHEN, "Install and configure nginx", "OpenAI GPT");
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
    void userPromptEmbedsHeaderFactsAndLanguageNameAndContext() {
        WorkflowContext ctx = new WorkflowContext("apt-get install nginx", false, 2, 2);
        String user = WorkflowScriptSupport.buildUserPrompt(
            ScriptLanguage.PYTHON, facts(ScriptLanguage.PYTHON), ctx, HardeningOption.defaults(), "Keep it short");
        assertThat(user).contains("daniel");
        assertThat(user).contains("root@web-prod");
        assertThat(user).contains("2026-06-16 14:30:00");
        assertThat(user).contains("Install and configure nginx");
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
    void systemPromptWithCustomHeaderTellsModelNotToAddHeader() {
        String sys = WorkflowScriptSupport.buildSystemPrompt(ScriptLanguage.BASH, HardeningOption.defaults(), true);
        assertThat(sys).ignoringCase().contains("do not add a header");
        assertThat(sys).doesNotContain("Include a header comment block");
    }

    @Test
    void userPromptWithCustomHeaderOmitsHeaderFactsBlock() {
        WorkflowContext ctx = new WorkflowContext("apt-get install nginx", false, 1, 1);
        String user = WorkflowScriptSupport.buildUserPrompt(
            ScriptLanguage.BASH, facts(ScriptLanguage.BASH), ctx, HardeningOption.defaults(), null, true);
        assertThat(user).doesNotContain("use these EXACTLY in the header comment");
        assertThat(user).ignoringCase().contains("do not emit any header");
        assertThat(user).contains("apt-get install nginx"); // reproduction context still present
        // Without custom header the facts block is present (sanity contrast).
        String plain = WorkflowScriptSupport.buildUserPrompt(
            ScriptLanguage.BASH, facts(ScriptLanguage.BASH), ctx, HardeningOption.defaults(), null, false);
        assertThat(plain).contains("HEADER FACTS");
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
}
