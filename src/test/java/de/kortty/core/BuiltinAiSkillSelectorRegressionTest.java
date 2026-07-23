package de.kortty.core;

import de.kortty.model.AiSkill;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;


/**
 * Quality gate for the bundled skill contents against the real relevance scorer: generic
 * meta-queries and known substring collisions must select nothing, while topic queries must
 * select their skill. Guards future edits to skills, tags, STOP_WORDS and scoring alike.
 */
class BuiltinAiSkillSelectorRegressionTest {

    private static final List<String> META_QUERIES = List.of(
        "security best practices",
        "best practices",
        "error handling",
        "add comments to my code",
        "make this more robust",
        "secure coding standards",
        "what should I avoid");

    private static final List<String> COLLISION_CANARIES = List.of(
        "how do I properly configure nginx",
        "evaluate this output",
        "trust this host key fingerprint",
        "visual studio code settings",
        "ansi escape codes in the terminal");

    /** Representative ambient vocabulary of the Unix agent base prompt — must fire nothing. */
    private static final String UNIX_AGENT_AMBIENT =
        "You are a terminal automation agent. Run shell commands, read files in the working "
            + "directory, summarize results and status. Use cat, head, sed, awk and sudo carefully, "
            + "write yaml, json or ini contents via a here-document, add comments to scripts, "
            + "and format the task summary as markdown.";

    private static final Map<String, String> POSITIVE_QUERIES = Map.ofEntries(
        Map.entry("builtin.shell.bash", "write a bash script with pipefail to rotate files"),
        Map.entry("builtin.shell.ksh", "convert this ksh93 function to a portable form"),
        Map.entry("builtin.shell.zsh", "my zshrc alias breaks completion"),
        Map.entry("builtin.shell.csh", "fix this legacy tcsh login file"),
        Map.entry("builtin.shell.sh", "#!/bin/sh portability for busybox"),
        Map.entry("builtin.shell.powershell", "write a powershell function to list services"),
        Map.entry("builtin.lang.python", "write a python parser with pytest coverage"),
        Map.entry("builtin.lang.c", "fix the malloc failure in this c99 module"),
        Map.entry("builtin.lang.cpp", "refactor this cpp class to use raii"),
        Map.entry("builtin.lang.java", "review this java code and add javadoc"),
        Map.entry("builtin.lang.csharp", "write a csharp worker for dotnet"),
        Map.entry("builtin.lang.javascript", "debug this javascript promise in node.js"),
        Map.entry("builtin.lang.visual-basic", "convert this vba macro to vb.net"),
        Map.entry("builtin.lang.sql", "optimize this sql join with an index"),
        Map.entry("builtin.lang.r", "plot the results with ggplot and dplyr"),
        Map.entry("builtin.lang.rust", "why does my rust code fail the borrow checker"),
        Map.entry("builtin.lang.go", "write a golang worker with goroutine pools"),
        Map.entry("builtin.lang.php", "harden this php login against injection"),
        Map.entry("builtin.lang.swift", "build a swiftui view in xcode"),
        Map.entry("builtin.lang.assembler", "optimize this x86 loop in nasm"),
        Map.entry("builtin.lang.macro-assembler", "write an hlasm macro with local labels"),
        Map.entry("builtin.lang.ruby", "package this ruby library with bundler"),
        Map.entry("builtin.lang.perl", "review this perl code for common pitfalls"),
        Map.entry("builtin.lang.lua", "improve this lua code for luajit"),
        Map.entry("builtin.lang.groovy", "write a groovy task for gradle"),
        Map.entry("builtin.lang.typescript", "fix the strict tsconfig errors in my typescript app"),
        Map.entry("builtin.lang.kotlin", "convert this class to idiomatic kotlin"),
        Map.entry("builtin.lang.dart", "add a flutter widget in dart"),
        Map.entry("builtin.devops.puppet", "write a puppet manifest with hiera data"),
        Map.entry("builtin.devops.ansible", "write an ansible playbook to install nginx"),
        Map.entry("builtin.devops.azure-pipelines", "create an azure-pipelines stage for the build"),
        Map.entry("builtin.devops.jenkins-declarative", "write a declarative jenkinsfile with stages"),
        Map.entry("builtin.devops.jenkins-scripted", "convert my scripted jenkins job"),
        Map.entry("builtin.observability.filebeat", "configure filebeat.yml multiline for stack traces"),
        Map.entry("builtin.observability.logstash", "my logstash grok pattern fails to parse"),
        Map.entry("builtin.markup.html", "add alt attributes to this html page"),
        Map.entry("builtin.markup.xml", "validate this xml file against its xsd"),
        Map.entry("builtin.markup.yaml", "fix the indentation in my config.yml"),
        Map.entry("builtin.markup.json", "why does package.json fail to parse"));

    private static List<AiSkill> catalogSkills() {
        return BuiltinAiSkillCatalog.load().entries().stream()
            .map(AiSkillMarkdownCodec.BundledAiSkill::skill)
            .map(AiSkill::new)
            .toList();
    }

    private static AiSkillRelevanceSelector selector() {
        return new AiSkillRelevanceSelector(true, true, catalogSkills());
    }

    private static List<String> selectedIds(String question) {
        return selector()
            .selectChatSkillsLocal(new AiRequest(AiAction.ASK, null, null, "en", question))
            .stream()
            .map(AiSkill::getBuiltinId)
            .toList();
    }

    @Test
    void genericMetaQueriesSelectNothing() {
        for (String query : META_QUERIES) {
            assertThat(selectedIds(query)).isEmpty();
        }
    }

    @Test
    void collisionCanariesSelectNothing() {
        for (String query : COLLISION_CANARIES) {
            assertThat(selectedIds(query)).isEmpty();
        }
    }

    @Test
    void unixAgentAmbientVocabularySelectsNothing() {
        List<String> selected = selector()
            .selectAgentSkillsLocal(UNIX_AGENT_AMBIENT, "please continue with the task")
            .stream()
            .map(AiSkill::getBuiltinId)
            .toList();
        assertThat(selected).isEmpty();
    }

    @Test
    void windowsAgentAmbientVocabularySelectsOnlyPowerShell() {
        String windowsAmbient = UNIX_AGENT_AMBIENT
            + " On Windows hosts run PowerShell or cmd commands instead.";
        List<String> selected = selector()
            .selectAgentSkillsLocal(windowsAmbient, "please continue with the task")
            .stream()
            .map(AiSkill::getBuiltinId)
            .toList();
        // Auto-firing the PowerShell skill on Windows agent runs is expected behavior.
        assertThat(selected).containsExactly("builtin.shell.powershell");
    }

    @Test
    void topicQueriesSelectTheirSkill() {
        for (Map.Entry<String, String> entry : POSITIVE_QUERIES.entrySet()) {
            List<String> selected = selectedIds(entry.getValue());
            assertThat(selected).contains(entry.getKey());
            // Cross-fire budget: at most one additional skill may ride along on a topic query.
            assertThat(selected.size()).isAtMost(2);
        }
    }
}
