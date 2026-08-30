package de.kortty.model;

/**
 * The diagram families korTTY can generate for a snippet. The {@code id} is the persisted
 * {@link SnippetDiagram#getType()} value and must stay stable across releases.
 */
public enum SnippetDiagramType {
    LOGICAL_STRUCTURE(SnippetDiagram.TYPE_LOGICAL_STRUCTURE),
    SEQUENCE(SnippetDiagram.TYPE_SEQUENCE),
    STATE(SnippetDiagram.TYPE_STATE),
    CLASS(SnippetDiagram.TYPE_CLASS),
    ER(SnippetDiagram.TYPE_ER);

    private final String id;

    SnippetDiagramType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Resolves a persisted type id; {@code null} for unknown ids (e.g. from a newer app version). */
    public static SnippetDiagramType fromId(String id) {
        if (id != null) {
            String value = id.trim();
            for (SnippetDiagramType type : values()) {
                if (type.id.equalsIgnoreCase(value)) {
                    return type;
                }
            }
        }
        return null;
    }

    /** Resolves a persisted type id, falling back to {@link #LOGICAL_STRUCTURE} for unknown ids. */
    public static SnippetDiagramType fromIdOrDefault(String id) {
        SnippetDiagramType type = fromId(id);
        return type != null ? type : LOGICAL_STRUCTURE;
    }
}
