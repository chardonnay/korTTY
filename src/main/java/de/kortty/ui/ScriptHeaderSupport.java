package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetVariableManager;
import de.kortty.model.Snippet;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared resolution of the user's "Script-Header" snippets (the fixed, non-deletable "Script-Header"
 * category managed by {@code SnippetManager}) into ready-to-inject text: built-in variables (date,
 * creator, …) and declared variables are substituted with the same rules as every other snippet use
 * ({@code SnippetPlaceholderResolver}); undeclared {@code ${...}} stay as written.
 * Extracted so more than one dialog can offer "add a script header" without duplicating the substitution.
 */
final class ScriptHeaderSupport {

    private ScriptHeaderSupport() {
    }

    /**
     * Resolves a Script-Header snippet's content with built-in + custom variables substituted, or
     * {@code null} when the id is blank, the snippet is gone, or the app is not fully initialised.
     */
    static String substitutedHeaderById(String snippetId) {
        if (snippetId == null || snippetId.isBlank()) {
            return null;
        }
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getSnippetManager() == null) {
            return null;
        }
        var snippetManager = app.getSnippetManager();
        Snippet header = snippetManager.findById(snippetId).orElse(null);
        if (header == null || header.getContent() == null) {
            return null;
        }
        // A header is inserted without asking, so a declared variable without a value becomes empty.
        SnippetVariableManager variables = app.getSnippetVariableManager();
        Map<String, String> vars = new HashMap<>();
        if (variables != null) {
            variables.getAll().forEach(variable -> {
                if (variable.getName() != null) {
                    vars.put(variable.getName(), variable.getValue() != null ? variable.getValue() : "");
                }
            });
        }
        return snippetManager.resolve(header.getContent(), variables, vars).text();
    }
}
