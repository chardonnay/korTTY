package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted Mermaid diagram generated for a snippet.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SnippetDiagram {

    public static final String TYPE_LOGICAL_STRUCTURE = "logical-structure";
    public static final String TYPE_SEQUENCE = "sequence";
    public static final String TYPE_STATE = "state";
    public static final String TYPE_CLASS = "class";
    public static final String TYPE_ER = "er";

    @XmlElement(required = true)
    private String id;

    @XmlElement
    private String title;

    @XmlElement
    private String type = TYPE_LOGICAL_STRUCTURE;

    @XmlElement
    private String mermaidSource;

    /**
     * Read-only JAXB migration field. {@link de.kortty.core.SnippetManager} clears it immediately after
     * unmarshalling and discards a diagram when this is its only source, so it is never written again.
    */
    @XmlElement(name = "plantUmlSource")
    private String legacyDiagramSource;

    @XmlElement
    private String sourceContentSha256;

    @XmlElement
    private String customInstructions;

    /**
     * 1-based line range of the snippet selection this diagram was generated from, or 0/0 for a
     * diagram covering the whole snippet. Optional in XML so older files load unchanged.
     */
    @XmlElement
    private int scopeStartLine;

    @XmlElement
    private int scopeEndLine;

    @XmlElementWrapper(name = "codeReferences")
    @XmlElement(name = "codeReference")
    private List<CodeReference> codeReferences = new ArrayList<>();

    @XmlElement
    private long createdAt;

    @XmlElement
    private long updatedAt;

    public SnippetDiagram() {
        this.id = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public SnippetDiagram(SnippetDiagram source) {
        this.id = source != null && source.id != null ? source.id : java.util.UUID.randomUUID().toString();
        this.title = source != null ? source.title : null;
        this.type = source != null && source.type != null ? source.type : TYPE_LOGICAL_STRUCTURE;
        this.mermaidSource = source != null ? source.mermaidSource : null;
        this.sourceContentSha256 = source != null ? source.sourceContentSha256 : null;
        this.customInstructions = source != null ? source.customInstructions : null;
        this.scopeStartLine = source != null ? source.scopeStartLine : 0;
        this.scopeEndLine = source != null ? source.scopeEndLine : 0;
        this.codeReferences = source != null && source.codeReferences != null
            ? copyCodeReferences(source.codeReferences)
            : new ArrayList<>();
        this.createdAt = source != null ? source.createdAt : System.currentTimeMillis();
        this.updatedAt = source != null ? source.updatedAt : this.createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id : java.util.UUID.randomUUID().toString();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type != null && !type.isBlank() ? type : TYPE_LOGICAL_STRUCTURE;
    }

    public String getMermaidSource() {
        return mermaidSource;
    }

    public void setMermaidSource(String mermaidSource) {
        this.mermaidSource = mermaidSource;
    }

    public void discardLegacySource() {
        legacyDiagramSource = null;
    }

    public String getSourceContentSha256() {
        return sourceContentSha256;
    }

    public void setSourceContentSha256(String sourceContentSha256) {
        this.sourceContentSha256 = sourceContentSha256;
    }

    public String getCustomInstructions() {
        return customInstructions;
    }

    public void setCustomInstructions(String customInstructions) {
        this.customInstructions = customInstructions;
    }

    public int getScopeStartLine() {
        return scopeStartLine;
    }

    public void setScopeStartLine(int scopeStartLine) {
        this.scopeStartLine = Math.max(0, scopeStartLine);
    }

    public int getScopeEndLine() {
        return scopeEndLine;
    }

    public void setScopeEndLine(int scopeEndLine) {
        this.scopeEndLine = Math.max(0, scopeEndLine);
    }

    /** True when this diagram was generated from a selected part of the snippet. */
    public boolean hasScope() {
        return scopeStartLine > 0 && scopeEndLine >= scopeStartLine;
    }

    public List<CodeReference> getCodeReferences() {
        if (codeReferences == null) {
            codeReferences = new ArrayList<>();
        }
        return codeReferences;
    }

    public void setCodeReferences(List<CodeReference> codeReferences) {
        this.codeReferences = codeReferences != null ? copyCodeReferences(codeReferences) : new ArrayList<>();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return title != null && !title.isBlank() ? title : "Snippet diagram";
    }

    private static List<CodeReference> copyCodeReferences(List<CodeReference> source) {
        List<CodeReference> copy = new ArrayList<>();
        if (source != null) {
            for (CodeReference reference : source) {
                if (reference != null) {
                    copy.add(new CodeReference(reference));
                }
            }
        }
        return copy;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class CodeReference {
        @XmlElement
        private String nodeId;

        @XmlElement
        private String label;

        @XmlElement
        private int startLine;

        @XmlElement
        private int endLine;

        public CodeReference() {
        }

        public CodeReference(String nodeId, String label, int startLine, int endLine) {
            this.nodeId = nodeId;
            this.label = label;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        public CodeReference(CodeReference source) {
            this.nodeId = source != null ? source.nodeId : null;
            this.label = source != null ? source.label : null;
            this.startLine = source != null ? source.startLine : 0;
            this.endLine = source != null ? source.endLine : 0;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public int getStartLine() {
            return startLine;
        }

        public void setStartLine(int startLine) {
            this.startLine = startLine;
        }

        public int getEndLine() {
            return endLine;
        }

        public void setEndLine(int endLine) {
            this.endLine = endLine;
        }
    }
}
