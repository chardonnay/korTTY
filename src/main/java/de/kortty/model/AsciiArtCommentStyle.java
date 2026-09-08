package de.kortty.model;

/**
 * How the ASCII Art tool wraps a copied picture so it can be pasted into source code as a comment.
 *
 * <p>Line styles put their marker in front of every picture line; block styles surround the whole
 * picture with an opener and a closer. The {@code languages} text is deliberately English and
 * untranslated: language names are proper nouns and the dialog's list would otherwise vary per
 * locale for no benefit. Persisted by name in the global settings, so the constants must stay stable.
 */
public enum AsciiArtCommentStyle {
    /** The picture is copied as-is. The dialog shows a translated "none" entry for it. */
    NONE("", "", false),
    HASH("#", "Bash, Perl, Python, Ruby, YAML, TOML, PowerShell", false),
    DOUBLE_SLASH("//", "Java, C, C++, JavaScript, TypeScript, Go, Rust, Kotlin, Swift, PHP", false),
    DOUBLE_DASH("--", "SQL, Lua, Haskell, Ada", false),
    SEMICOLON(";", "Lisp, Clojure, INI, Assembler", false),
    PERCENT("%", "LaTeX, MATLAB, Erlang", false),
    APOSTROPHE("'", "VB, VBA", false),
    REM("REM", "Batch", false),
    /** {@code /*} … {@code *} per line … {@code *&#47;}; a {@code *&#47;} inside the picture is broken up. */
    C_BLOCK("/*", "C block comment", true),
    /** {@code <!--} … {@code -->}; double hyphens inside the picture are broken up. */
    XML_BLOCK("<!--", "XML, HTML, SVG, Markdown", true),
    /** A JSON array with one string literal per picture line. */
    JSON_STRINGS("[", "JSON", true);

    /** Separates marker and language list in {@link #label()}; wide enough to read as two columns. */
    private static final String LABEL_SEPARATOR = "  -  ";

    private final String marker;
    private final String languages;
    private final boolean block;

    AsciiArtCommentStyle(String marker, String languages, boolean block) {
        this.marker = marker;
        this.languages = languages;
        this.block = block;
    }

    /** The comment marker, or the opener for block styles; empty for {@link #NONE}. */
    public String marker() {
        return marker;
    }

    /** English, comma-separated names of the languages that use this marker; empty for {@link #NONE}. */
    public String languages() {
        return languages;
    }

    /** {@code true} when the style surrounds the picture instead of prefixing each line. */
    public boolean isBlock() {
        return block;
    }

    /**
     * The menu text, marker first so the eye can scan the column of markers. Empty for
     * {@link #NONE}, whose entry the dialog renders from a translated resource instead.
     */
    public String label() {
        if (this == NONE) {
            return "";
        }
        return marker + LABEL_SEPARATOR + languages;
    }

}
