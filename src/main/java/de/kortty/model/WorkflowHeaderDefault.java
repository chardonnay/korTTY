package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * Maps a workflow-script language (by {@code ScriptLanguage.name()}) to the id of the
 * "Script-Header" snippet used as its default header when the user picks "Default".
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class WorkflowHeaderDefault {

    @XmlElement
    private String language;

    @XmlElement
    private String headerSnippetId;

    public WorkflowHeaderDefault() {
    }

    public WorkflowHeaderDefault(String language, String headerSnippetId) {
        this.language = language;
        this.headerSnippetId = headerSnippetId;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getHeaderSnippetId() {
        return headerSnippetId;
    }

    public void setHeaderSnippetId(String headerSnippetId) {
        this.headerSnippetId = headerSnippetId;
    }
}
