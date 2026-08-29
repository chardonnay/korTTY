package de.kortty.core;

/**
 * Which natural language the prose inside returned code must be written in, and whether existing
 * prose may be rewritten into it.
 *
 * <p>These are genuinely two different instructions. "Write German" applied to an English script
 * translates every comment in it; "write German because this script is already German" does not.
 * korTTY used to send only the first, so applying a Full-code-analysis result to an English script
 * from a German interface silently translated the whole file's comments and messages.</p>
 *
 * <p>{@link #keep} is the default. {@link #translateInto} is the opt-in the user reaches for when
 * they actually want the script's prose converted.</p>
 */
public record CodeTextLanguage(String languageCode, boolean preserveExisting) {

    /** The snippet already writes this language: keep its prose, do not translate it. */
    public static CodeTextLanguage keep(String languageCode) {
        return new CodeTextLanguage(languageCode, true);
    }

    /** Deliberately convert the snippet's prose into this language. */
    public static CodeTextLanguage translateInto(String languageCode) {
        return new CodeTextLanguage(languageCode, false);
    }

    public boolean isUsable() {
        return languageCode != null && !languageCode.isBlank();
    }
}
