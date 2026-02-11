package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * Terminal theme (color profile) with font, colors, cursor style.
 * Themes are managed globally and can be selected per connection.
 */
@XmlRootElement(name = "theme")
@XmlAccessorType(XmlAccessType.FIELD)
public class Theme {

    @XmlAttribute
    private String id;

    @XmlElement
    private String name;

    @XmlElement
    private String fontFamily = "Monospaced";

    @XmlElement
    private int fontSize = 14;

    @XmlElement
    private String foregroundColor = "#FFFFFF";

    @XmlElement
    private String backgroundColor = "#1E1E1E";

    @XmlElement
    private String cursorColor = "#FFFFFF";

    @XmlElement
    private String cursorStyle = "BLINK_BLOCK";

    @XmlElement
    private String backgroundImagePath;

    @XmlElement
    private boolean builtIn;

    public Theme() {
    }

    public Theme(String id, String name, boolean builtIn) {
        this.id = id;
        this.name = name;
        this.builtIn = builtIn;
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

    public String getFontFamily() {
        return fontFamily != null ? fontFamily : "Monospaced";
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    public int getFontSize() {
        return fontSize > 0 ? fontSize : 14;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }

    public String getForegroundColor() {
        return foregroundColor != null ? foregroundColor : "#FFFFFF";
    }

    public void setForegroundColor(String foregroundColor) {
        this.foregroundColor = foregroundColor;
    }

    public String getBackgroundColor() {
        return backgroundColor != null ? backgroundColor : "#1E1E1E";
    }

    public void setBackgroundColor(String backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public String getCursorColor() {
        return cursorColor != null ? cursorColor : "#FFFFFF";
    }

    public void setCursorColor(String cursorColor) {
        this.cursorColor = cursorColor;
    }

    public String getCursorStyle() {
        return cursorStyle != null ? cursorStyle : "BLINK_BLOCK";
    }

    public void setCursorStyle(String cursorStyle) {
        this.cursorStyle = cursorStyle;
    }

    public String getBackgroundImagePath() {
        return backgroundImagePath;
    }

    public void setBackgroundImagePath(String backgroundImagePath) {
        this.backgroundImagePath = backgroundImagePath;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }

    /**
     * Applies this theme's values to the given ConnectionSettings.
     */
    public void applyTo(ConnectionSettings target) {
        target.setFontFamily(getFontFamily());
        target.setFontSize(getFontSize());
        target.setForegroundColor(getForegroundColor());
        target.setBackgroundColor(getBackgroundColor());
        target.setCursorColor(getCursorColor());
        target.setCursorStyle(getCursorStyle());
    }

    /**
     * Creates ConnectionSettings from this theme.
     */
    public ConnectionSettings toConnectionSettings() {
        ConnectionSettings s = new ConnectionSettings();
        applyTo(s);
        return s;
    }
}
