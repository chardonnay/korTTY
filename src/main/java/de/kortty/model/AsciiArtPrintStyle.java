package de.kortty.model;

/**
 * How the ASCII Art tool turns a copied picture into print statements, one per picture line, so the
 * program prints the picture at run time.
 *
 * <p>Each style names the language and the statement it emits; the escaping rules live in
 * {@code de.kortty.core.AsciiArtCopySupport} because they are the part that needs unit tests.
 * Persisted by name in the global settings, so the constants must stay stable.
 */
public enum AsciiArtPrintStyle {
    /** The picture is copied as-is. The dialog shows a translated "none" entry for it. */
    NONE("", ""),
    /** The recommended shell style: a quoted heredoc needs no escaping at all. */
    BASH_HEREDOC("Bash", "heredoc"),
    /** Fine for pictures; only a line that is exactly an echo option such as {@code -n} at gap 0 would vanish. */
    BASH_ECHO("Bash", "echo"),
    /** {@code printf} instead of {@code echo}: dash's {@code echo} interprets backslashes. */
    SH_PRINTF("POSIX sh", "printf"),
    PERL("Perl", "print"),
    PYTHON("Python", "print()"),
    RUBY("Ruby", "puts"),
    PHP("PHP", "echo"),
    JAVASCRIPT("JavaScript", "console.log()"),
    JAVA("Java", "System.out.println()"),
    C("C", "puts()"),
    CSHARP("C#", "Console.WriteLine()"),
    GO("Go", "fmt.Println()"),
    RUST("Rust", "println!()"),
    KOTLIN("Kotlin", "println()"),
    SWIFT("Swift", "print()"),
    LUA("Lua", "print()"),
    POWERSHELL("PowerShell", "Write-Output"),
    /** The most fragile target: {@code cmd.exe} has no quoting, only caret escapes. */
    BATCH("Batch", "echo");

    private static final String LABEL_SEPARATOR = " - ";

    private final String languageName;
    private final String statement;

    AsciiArtPrintStyle(String languageName, String statement) {
        this.languageName = languageName;
        this.statement = statement;
    }

    /** The English language name; empty for {@link #NONE}. */
    public String languageName() {
        return languageName;
    }

    /** The statement or construct the style emits, e.g. {@code echo} or {@code print()}; empty for {@link #NONE}. */
    public String statement() {
        return statement;
    }

    /**
     * The menu text, e.g. {@code "Python - print()"}. Empty for {@link #NONE}, whose entry the dialog
     * renders from a translated resource instead.
     */
    public String label() {
        if (this == NONE) {
            return "";
        }
        return languageName + LABEL_SEPARATOR + statement;
    }

}
