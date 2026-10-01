package de.kortty.core;

import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetModularizationSupport.ModuleFile;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class SnippetModularizationSupportTest {

    @Test
    void parsesAPlanAndKeepsExactlyOneExecutableEntryPoint() {
        ModularizationPlan plan = SnippetModularizationSupport.parsePlan("""
            Sure! ```json
            { "recommended": true, "rationale": "Two concerns.",
              "files": [
                { "path": "lib/net", "purpose": "HTTP", "symbols": ["fetch"], "executable": false, "entryPoint": false },
                { "path": "./main.py", "purpose": "CLI", "symbols": ["main"], "executable": false, "entryPoint": true },
                { "path": "../../etc/evil.py", "purpose": "x", "entryPoint": true },
                { "path": "lib/net.py", "purpose": "duplicate" }
              ] }
            ```""", ".py");
        assertThat(plan.isApplicable()).isTrue();
        assertThat(plan.files().stream().map(ModuleFile::path).toList())
            .containsExactly("lib/net.py", "main.py", "etc/evil.py").inOrder();
        assertThat(plan.entryPoint().path()).isEqualTo("main.py");
        assertThat(plan.entryPoint().executable()).isTrue();
        assertThat(plan.files().stream().filter(ModuleFile::entryPoint).count()).isEqualTo(1);
        assertThat(plan.modulesFirst().getLast().path()).isEqualTo("main.py");
    }

    @Test
    void aDeclinedSplitIsUsableButNotApplicable() {
        ModularizationPlan plan = SnippetModularizationSupport.parsePlan(
            "{\"recommended\": false, \"rationale\": \"Too short to split.\", \"files\": []}", ".sh");
        assertThat(plan.isUsable()).isTrue();
        assertThat(plan.isApplicable()).isFalse();
        assertThat(plan.rationale()).isEqualTo("Too short to split.");
    }

    @Test
    void readsGeneratedFileLines() {
        assertThat(SnippetModularizationSupport.parseGeneratedFile(
            "{\"fileLines\": [\"#!/bin/bash\", \"source \\\"$(dirname \\\"$0\\\")/lib/x.sh\\\"\", \"\"], \"summary\": \"ok\"}"))
            .isEqualTo("#!/bin/bash\nsource \"$(dirname \"$0\")/lib/x.sh\"\n");
        assertThat(SnippetModularizationSupport.parseGeneratedFile("{\"fileLines\": []}")).isNull();
    }

    @Test
    void reportsEmptyFilesAndModulesNobodyLoads() {
        ModularizationPlan plan = new ModularizationPlan(true, "", List.of(
            new ModuleFile("main.sh", "", List.of(), true, true),
            new ModuleFile("lib/net.sh", "", List.of(), false, false),
            new ModuleFile("lib/log.sh", "", List.of(), false, false)));
        List<String> problems = SnippetModularizationSupport.problems(plan, Map.of(
            "main.sh", "source \"$DIR/lib/net.sh\"\n",
            "lib/net.sh", "net() { :; }\n",
            "lib/log.sh", ""));
        assertThat(problems).hasSize(2);
        assertThat(String.join("\n", problems)).contains("lib/log.sh");
    }

    @Test
    void normalizesPaths() {
        assertThat(SnippetModularizationSupport.normalizePath("\\lib\\my util", ".sh")).isEqualTo("lib/my-util.sh");
        assertThat(SnippetModularizationSupport.normalizePath("pkg/__init__.py", ".py")).isEqualTo("pkg/__init__.py");
        assertThat(SnippetModularizationSupport.normalizePath("..", ".py")).isEmpty();
    }
}
