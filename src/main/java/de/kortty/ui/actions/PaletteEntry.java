package de.kortty.ui.actions;

import java.util.Objects;

/**
 * One row of the command palette: what it shows and what choosing it does.
 *
 * <p>{@code key} identifies the row across palette openings, so the list of recent choices can find
 * it again: {@code action:<id>}, and for the later kinds {@code tab:}, {@code conn:} and
 * {@code snippet:} with an id of their own. It never holds a query, a host name or a secret.
 * {@code enabled} is the state when the palette opened; {@code run} checks it again when it runs.
 * {@code disabledReason} says why a row that is not enabled cannot run. {@code checked} marks a
 * toggle that is on. Every text is {@link PaletteText#clean cleaned} here, so no source can put a
 * control or bidi character on screen.
 */
public record PaletteEntry(
        Kind kind,
        String key,
        String title,
        String detail,
        String shortcut,
        boolean enabled,
        String disabledReason,
        boolean checked,
        Runnable run) {

    /** The kinds of rows, in the order equally good matches are listed. */
    public enum Kind {
        ACTION('>'),
        TAB('#'),
        CONNECTION('@'),
        SNIPPET('$');

        private final char scopePrefix;

        Kind(char scopePrefix) {
            this.scopePrefix = scopePrefix;
        }

        /** The character that, typed first, limits the palette to this kind. */
        public char scopePrefix() {
            return scopePrefix;
        }

        /** The kind whose scope prefix is {@code character}, or {@code null}. */
        public static Kind ofScopePrefix(char character) {
            for (Kind kind : values()) {
                if (kind.scopePrefix == character) {
                    return kind;
                }
            }
            return null;
        }
    }

    public PaletteEntry {
        Objects.requireNonNull(kind, "kind");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A palette entry needs a key");
        }
        Objects.requireNonNull(run, "run");
        title = PaletteText.clean(title);
        detail = PaletteText.clean(detail);
        shortcut = PaletteText.clean(shortcut);
        disabledReason = PaletteText.clean(disabledReason);
    }
}
