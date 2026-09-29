package de.kortty.core;

import java.util.Locale;

/**
 * Why an AI diagram could not be used, in the few categories a reader can act on. The rejection
 * reasons themselves are precise English sentences for the log; the interface shows the category,
 * localized, and keeps the sentence for the tooltip. The category also decides what the one
 * second request asks for: a syntax or structure slip is repaired ("fix only the syntax, keep the
 * structure"), while an oversized or unsafe diagram, or a prose answer without any diagram, is
 * requested again from scratch.
 */
public enum SnippetDiagramRejection {
    /** The answer carried no diagram at all (prose, no JSON, no mermaid value). */
    NO_DIAGRAM,
    /** A line Mermaid or korTTY's grammar cannot read. */
    SYNTAX,
    /** The flow itself is incomplete: no connections, no start, too few steps left. */
    STRUCTURE,
    /** More nodes, edges or bytes than a snippet diagram may have. */
    TOO_LARGE,
    /** Directives, callbacks, URLs, HTML or media the security screen refuses. */
    UNSAFE;

    /** Classifies a rejection reason as produced by the diagram validation and generation. */
    public static SnippetDiagramRejection classify(String reason) {
        String value = reason != null ? reason.toLowerCase(Locale.ROOT) : "";
        if (value.isBlank() || value.contains("no json object") || value.contains("no 'mermaid' value")
            || value.contains("json envelope") || value.contains("no usable diagram")) {
            return NO_DIAGRAM;
        }
        if (value.contains("not allowed") || value.contains("external or executable")
            || value.contains("nul character")) {
            return UNSAFE;
        }
        if (value.contains("exceeds") || value.contains("tolerated") || value.contains("at most")
            || value.contains("limit")) {
            return TOO_LARGE;
        }
        if (value.contains("syntax") || value.contains("parse") || value.contains("must start with")
            || value.contains("unsupported") || value.contains("expecting")) {
            return SYNTAX;
        }
        return STRUCTURE;
    }

    /** Whether the second request repairs the model's own diagram rather than starting from scratch. */
    public boolean repairable() {
        return this == SYNTAX || this == STRUCTURE;
    }
}
