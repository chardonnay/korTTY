package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;

/** A persisted window geometry addressed by a stable application-internal key. */
@XmlAccessorType(XmlAccessType.FIELD)
public class NamedWindowGeometry {

    @XmlAttribute(name = "key")
    private String key;

    @XmlElement
    private WindowGeometry geometry;

    public NamedWindowGeometry() {
    }

    public NamedWindowGeometry(String key, WindowGeometry geometry) {
        this.key = key;
        this.geometry = geometry;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public WindowGeometry getGeometry() {
        return geometry;
    }

    public void setGeometry(WindowGeometry geometry) {
        this.geometry = geometry;
    }
}
