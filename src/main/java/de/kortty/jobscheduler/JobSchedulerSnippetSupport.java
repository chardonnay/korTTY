package de.kortty.jobscheduler;

import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetOneLiner;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.Snippet;

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

    /**
     * Resolves the snippet like every other use (see {@code SnippetPlaceholderResolver}), but a
     * scheduled run cannot ask for a value: a declared variable without a stored value blocks, and so
     * does an undeclared simple {@code ${name}} — most likely a korTTY variable that was never stored,
     * which the shell would otherwise expand to an empty string. Shell forms such as {@code ${1:-x}},
     * the standard environment names and escaped {@code $${name}} pass through.
     */
    private String resolveSnippetText(Snippet snippet) throws JobBlockedException {
        String content = snippet.getContent();
        List<String> undeclared = snippetManager.undeclaredSimpleNames(content, variableManager);
        if (!undeclared.isEmpty()) {
            String name = undeclared.getFirst();
            throw new JobBlockedException("Snippet variable is not declared: ${" + name + "}. Declare it with a value in "
                + "the Variable Manager, or write $${" + name + "} to pass it to the shell unchanged.");
        }
        for (String variable : snippetManager.declaredVariables(content, variableManager)) {
            if (variableManager.getValue(variable) == null) {
                throw new JobBlockedException("Snippet variable has no stored value: ${" + variable + "}");
            }
        }
        String text = snippetManager.resolve(content, variableManager, Map.of()).text();
        if (text == null || text.isBlank()) {
            throw new JobBlockedException("Snippet script is empty: " + safeSnippetName(snippet));
        }
        return text;
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
