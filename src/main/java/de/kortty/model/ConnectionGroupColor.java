package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;

/**
 * The tab color of one connection group, as stored in {@code global-settings.xml}: the group's path
 * ({@code Work/Production}) and the color as {@code #RRGGBB}. Groups have no color until the user
 * picks one in the Connection Manager; see {@link de.kortty.core.ConnectionGroupColors}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class ConnectionGroupColor {

    @XmlAttribute(required = true)
    private String path;

    @XmlAttribute(required = true)
    private String color;

    public ConnectionGroupColor() {
    }

    public ConnectionGroupColor(String path, String color) {
        this.path = path;
        this.color = color;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
