package de.kortty.jobscheduler;

import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetOneLiner;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.Snippet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JobSchedulerSnippetSupport {

    private final SnippetManager snippetManager;
    private final SnippetVariableManager variableManager;

    public JobSchedulerSnippetSupport(SnippetManager snippetManager, SnippetVariableManager variableManager) {
        this.snippetManager = snippetManager;
        this.variableManager = variableManager;
    }

    public BuiltSnippetScript build(JobAction action) throws JobBlockedException {
        if (action == null) {
            throw new JobBlockedException("Snippet action is required.");
        }
        if (snippetManager == null) {
            throw new JobBlockedException("SnippetManager is unavailable.");
        }
        String snippetId = requireNonBlank(action.getSnippetId(), "Snippet script is required.");
        Snippet snippet = snippetManager.findById(snippetId)
            .orElseThrow(() -> new JobBlockedException("Snippet script was not found: " + snippetId));
        String resolved = resolveSnippetText(snippet);
        List<String> arguments = action.getSnippetArguments().stream()
            .filter(argument -> argument != null && !argument.isBlank())
            .toList();

        SnippetOneLiner.OneLinerResult oneLiner = SnippetOneLiner.toEmbedded(
            resolved,
            snippet.getLanguage(),
            arguments);
        if (!oneLiner.isOk()) {
            throw new JobBlockedException(
                "Snippet script cannot be converted for scheduler execution. Supported languages: bash, shell, python, perl, ruby.");
        }
        return new BuiltSnippetScript(
            oneLiner.line(),
            "Snippet script: " + safeSnippetName(snippet) + "\nArguments: " + arguments.size());
    }

    private String resolveSnippetText(Snippet snippet) throws JobBlockedException {
        SnippetManager.ResolvedSnippet resolved = snippetManager.resolveBuiltInVariables(snippet.getContent());
        String text = resolved.text();
        if (text == null || text.isBlank()) {
            throw new JobBlockedException("Snippet script is empty: " + safeSnippetName(snippet));
        }
        List<String> customVariables = snippetManager.findCustomVariables(text);
        if (customVariables.isEmpty()) {
            return text;
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String variable : customVariables) {
            String value = variableManager != null ? variableManager.getValue(variable) : null;
            if (value == null) {
                throw new JobBlockedException("Snippet variable has no stored value: ${" + variable + "}");
            }
            values.put(variable, value);
        }
        return snippetManager.replaceCustomVariables(text, values);
    }

    private String requireNonBlank(String value, String message) throws JobBlockedException {
        if (value == null || value.isBlank()) {
            throw new JobBlockedException(message);
        }
        return value.trim();
    }

    private String safeSnippetName(Snippet snippet) {
        return snippet.getName() != null && !snippet.getName().isBlank()
            ? snippet.getName()
            : snippet.getId();
    }

    public record BuiltSnippetScript(String command, String detail) {
    }
}
