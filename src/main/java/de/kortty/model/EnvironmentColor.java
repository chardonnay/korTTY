package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * The tab color of one credential environment, as stored in {@code environments.xml}: the
 * environment's id (a built-in one such as {@code PRODUCTION}, or a custom one) and the color as
 * {@code #RRGGBB}. Environments have no color until the user picks one.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class EnvironmentColor {

    @XmlAttribute(required = true)
    private String id;

    @XmlAttribute(required = true)
    private String color;

    public EnvironmentColor() {
    }

    public EnvironmentColor(String id, String color) {
        this.id = id;
        this.color = color;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
