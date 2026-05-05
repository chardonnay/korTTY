package de.kortty.jobscheduler;

import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerSnippetSupportTest {

    @Test
    void buildsSnippetScriptCommandWithStoredVariablesAndArguments() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-snippet-support");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            SnippetVariableManager variableManager = new SnippetVariableManager(dir);
            Snippet snippet = new Snippet("Check disk", "echo ${target} \"$1\"", "bash");
            snippet.setId("snippet-1");
            snippetManager.addSnippet(snippet);
            variableManager.addOrUpdate("target", "/srv");

            JobAction action = new JobAction();
            action.setType(JobActionType.SNIPPET_SCRIPT);
            action.setSnippetId("snippet-1");
            action.setSnippetArguments(List.of("alpha beta", "--force"));

            JobSchedulerSnippetSupport.BuiltSnippetScript built =
                new JobSchedulerSnippetSupport(snippetManager, variableManager).build(action);

            assertThat(built.command()).contains("base64 -d");
            assertThat(built.command()).contains("bash -s -- 'alpha beta' '--force'");
            assertThat(built.detail()).contains("Snippet script: Check disk");
            assertThat(built.detail()).contains("Arguments: 2");
        } finally {
            Files.deleteIfExists(dir.resolve("snippets.xml"));
            Files.deleteIfExists(dir.resolve("snippet-variables.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void blocksSnippetScriptWhenStoredVariableIsMissing() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-snippet-missing-variable");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            Snippet snippet = new Snippet("Needs variable", "echo ${target}", "bash");
            snippet.setId("snippet-1");
            snippetManager.addSnippet(snippet);
            JobAction action = new JobAction();
            action.setType(JobActionType.SNIPPET_SCRIPT);
            action.setSnippetId("snippet-1");

            try {
                new JobSchedulerSnippetSupport(snippetManager, new SnippetVariableManager(dir)).build(action);
                throw new AssertionError("Expected missing snippet variable to block the job.");
            } catch (JobBlockedException expected) {
                assertThat(expected.getMessage()).contains("Snippet variable has no stored value");
            }
        } finally {
            Files.deleteIfExists(dir.resolve("snippets.xml"));
            Files.deleteIfExists(dir.resolve("snippet-variables.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void blocksUnsupportedSnippetLanguage() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-snippet-language");
        try {
            SnippetManager snippetManager = new SnippetManager(dir);
            Snippet snippet = new Snippet("Java snippet", "System.out.println(\"no\");", "java");
            snippet.setId("snippet-1");
            snippetManager.addSnippet(snippet);
            JobAction action = new JobAction();
            action.setType(JobActionType.SNIPPET_SCRIPT);
            action.setSnippetId("snippet-1");

            try {
                new JobSchedulerSnippetSupport(snippetManager, new SnippetVariableManager(dir)).build(action);
                throw new AssertionError("Expected unsupported snippet language to block the job.");
            } catch (JobBlockedException expected) {
                assertThat(expected.getMessage()).contains("Supported languages");
            }
        } finally {
            Files.deleteIfExists(dir.resolve("snippets.xml"));
            Files.deleteIfExists(dir.resolve("snippet-variables.xml"));
            Files.deleteIfExists(dir);
        }
    }
}
