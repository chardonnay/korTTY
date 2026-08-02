package de.kortty.core;

import java.util.Locale;

/**
 * Heuristics for recognizing password prompts in terminal text. There is no reliable client-side
 * echo-off signal for a remote PTY (termios stays on the server), so features that must avoid
 * capturing secrets — the session journal above all — rely on recognizing the prompt itself in
 * the server output and suppressing the following input line.
 */
public final class PasswordPromptDetector {

    /** Keywords matched case-insensitively; includes common localized prompt words. */
    private static final String[] KEYWORDS = {
        "password", "passcode", "passphrase", "pin", "vault",
        "passwort", "kennwort",            // de
        "contraseña", "contrasena",        // es
        "mot de passe",                    // fr
        "wachtwoord",                      // nl
        "lozinka",                         // hr
        "senha"                            // pt/it-adjacent
    };

    /** Prompt lines are short; anything longer is ordinary output that merely ends with a colon. */
    private static final int MAX_PROMPT_LINE_CHARS = 160;

    private PasswordPromptDetector() {
    }

    /**
     * Broad containment check, suitable for classifying keyboard-interactive auth prompts where
     * the surrounding dialog already limits false positives.
     */
    public static boolean isPasswordPrompt(String promptText) {
        if (promptText == null) {
            return false;
        }
        String lower = promptText.toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prompt-shaped check for free-flowing terminal output: a short line ending with a colon that
     * mentions a password keyword. This keeps ordinary output such as {@code cat passwords.txt}
     * listings from tripping the suppression, while deliberately erring toward suppression —
     * a false positive only costs one redacted input line, a false negative leaks a secret.
     */
    public static boolean isPasswordPromptLine(String line) {
        if (line == null) {
            return false;
        }
        String stripped = line.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_PROMPT_LINE_CHARS || !stripped.endsWith(":")) {
            return false;
        }
        return isPasswordPrompt(stripped);
    }
}
