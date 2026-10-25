package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * An OS badge the user added: a name and a PNG in the {@code os-badges} folder of korTTY's
 * configuration directory. Connections refer to it as {@code custom:<id>}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class CustomOsBadge {

    @XmlAttribute
    private String id;

    @XmlAttribute
    private String name;

    /** File name of the PNG inside the {@code os-badges} folder. */
    @XmlAttribute
    private String file;

    public CustomOsBadge() {
    }

    public CustomOsBadge(String id, String name, String file) {
        this.id = id;
        this.name = name;
        this.file = file;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }
}
