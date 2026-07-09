package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.Snippet;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared resolution of the user's "Script-Header" snippets (the fixed, non-deletable "Script-Header"
 * category managed by {@code SnippetManager}) into ready-to-inject text: built-in variables (date,
 * creator, …) and custom variables are substituted, exactly as the workflow-script generator does.
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
        String text = snippetManager.resolveBuiltInVariables(header.getContent()).text();
        Map<String, String> vars = new HashMap<>();
        if (app.getSnippetVariableManager() != null) {
            app.getSnippetVariableManager().getAll().forEach(variable -> {
                if (variable.getName() != null) {
                    vars.put(variable.getName(), variable.getValue() != null ? variable.getValue() : "");
                }
            });
        }
        return snippetManager.replaceCustomVariables(text, vars);
    }
}
