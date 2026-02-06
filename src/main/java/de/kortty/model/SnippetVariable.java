package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * Represents a stored custom snippet variable and its value.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SnippetVariable {

    @XmlElement
    private String name;

    @XmlElement
    private String value;

    public SnippetVariable() {}

    public SnippetVariable(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
