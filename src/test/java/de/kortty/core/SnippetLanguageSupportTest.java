package de.kortty.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression test ensuring {@link SnippetLanguageSupport#KNOWN_LANGUAGES} stays in sync
 * with {@link SnippetLanguageSupport#normalizeSnippetLanguage(String)}.
 */
class SnippetLanguageSupportTest {

    /**
     * Verifies that every normalized language token returned by {@link SnippetLanguageSupport#normalizeSnippetLanguage}
     * (except the passthrough default branch) is present in the {@link SnippetLanguageSupport#KNOWN_LANGUAGES} allowlist.
     * This ensures future language additions fail CI if they are not reflected in the allowlist.
     */
    @Test
    void normalizedLanguagesMustBeInKnownLanguages() throws Exception {
        // Access KNOWN_LANGUAGES via reflection (it's private)
        Field knownLanguagesField = SnippetLanguageSupport.class.getDeclaredField("KNOWN_LANGUAGES");
        knownLanguagesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> knownLanguages = (Set<String>) knownLanguagesField.get(null);

        // Collect all normalized output values from normalizeSnippetLanguage by testing known inputs
        Set<String> normalizedOutputs = new HashSet<>();

        // Test all known input patterns from the switch statement
        String[][] testInputs = {
            {"sh", "shell", "zsh", "bash"},
            {"py", "python", "python3"},
            {"pl", "perl"},
            {"rb", "ruby"},
            {"js", "javascript", "node", "nodejs"},
            {"ps", "ps1", "pwsh", "powershell"},
            {"groovy"},
            {"java"},
            {"json"},
            {"yaml", "yml"},
            {"xml"},
            {"markdown", "md"},
            {"asciidoctor", "asciidoc", "adoc"},
            {"sql"},
            {"dockerfile"},
            {"properties", "ini"},
            {"html"},
            {"plain", "text", "txt"}
        };

        for (String[] inputs : testInputs) {
            for (String input : inputs) {
                String normalized = SnippetLanguageSupport.normalizeSnippetLanguage(input);
                normalizedOutputs.add(normalized);
            }
        }

        // Also test null and empty inputs (should map to "plain")
        normalizedOutputs.add(SnippetLanguageSupport.normalizeSnippetLanguage(null));
        normalizedOutputs.add(SnippetLanguageSupport.normalizeSnippetLanguage(""));
        normalizedOutputs.add(SnippetLanguageSupport.normalizeSnippetLanguage("   "));

        // Verify all normalized outputs are in KNOWN_LANGUAGES
        Set<String> missing = new HashSet<>();
        for (String normalized : normalizedOutputs) {
            if (!knownLanguages.contains(normalized)) {
                missing.add(normalized);
            }
        }

        if (!missing.isEmpty()) {
            fail("The following normalized language tokens are missing from KNOWN_LANGUAGES: " + missing
                + ". Please add them to the KNOWN_LANGUAGES set in SnippetLanguageSupport.");
        }

        // Additional check: verify telemetryLanguageToken uses KNOWN_LANGUAGES correctly
        for (String known : knownLanguages) {
            String token = SnippetLanguageSupport.telemetryLanguageToken(known, "");
            assertTrue(knownLanguages.contains(token) || "other".equals(token),
                "telemetryLanguageToken must return a known language or 'other', but got: " + token);
        }
    }
}
