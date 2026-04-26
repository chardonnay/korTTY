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
    private String agentPanelBackgroundColor;

    @XmlElement
    private String agentPanelBorderColor;

    @XmlElement
    private String agentPanelTextColor;

    @XmlElement
    private String agentPanelMutedTextColor;

    @XmlElement
    private String agentPanelAccentColor;

    @XmlElement
    private String agentPanelErrorColor;

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

    public String getAgentPanelBackgroundColor() {
        return !isBlank(agentPanelBackgroundColor)
            ? agentPanelBackgroundColor
            : deriveAgentPanelColors().background();
    }

    public void setAgentPanelBackgroundColor(String agentPanelBackgroundColor) {
        this.agentPanelBackgroundColor = agentPanelBackgroundColor;
    }

    public String getAgentPanelBorderColor() {
        return !isBlank(agentPanelBorderColor)
            ? agentPanelBorderColor
            : deriveAgentPanelColors().border();
    }

    public void setAgentPanelBorderColor(String agentPanelBorderColor) {
        this.agentPanelBorderColor = agentPanelBorderColor;
    }

    public String getAgentPanelTextColor() {
        return !isBlank(agentPanelTextColor)
            ? agentPanelTextColor
            : deriveAgentPanelColors().text();
    }

    public void setAgentPanelTextColor(String agentPanelTextColor) {
        this.agentPanelTextColor = agentPanelTextColor;
    }

    public String getAgentPanelMutedTextColor() {
        return !isBlank(agentPanelMutedTextColor)
            ? agentPanelMutedTextColor
            : deriveAgentPanelColors().mutedText();
    }

    public void setAgentPanelMutedTextColor(String agentPanelMutedTextColor) {
        this.agentPanelMutedTextColor = agentPanelMutedTextColor;
    }

    public String getAgentPanelAccentColor() {
        return !isBlank(agentPanelAccentColor)
            ? agentPanelAccentColor
            : deriveAgentPanelColors().accent();
    }

    public void setAgentPanelAccentColor(String agentPanelAccentColor) {
        this.agentPanelAccentColor = agentPanelAccentColor;
    }

    public String getAgentPanelErrorColor() {
        return !isBlank(agentPanelErrorColor)
            ? agentPanelErrorColor
            : deriveAgentPanelColors().error();
    }

    public void setAgentPanelErrorColor(String agentPanelErrorColor) {
        this.agentPanelErrorColor = agentPanelErrorColor;
    }

    public boolean initializeAgentPanelColorsIfMissing() {
        AgentPanelColors colors = deriveAgentPanelColors();
        boolean changed = false;
        if (isBlank(agentPanelBackgroundColor)) {
            agentPanelBackgroundColor = colors.background();
            changed = true;
        }
        if (isBlank(agentPanelBorderColor)) {
            agentPanelBorderColor = colors.border();
            changed = true;
        }
        if (isBlank(agentPanelTextColor)) {
            agentPanelTextColor = colors.text();
            changed = true;
        }
        if (isBlank(agentPanelMutedTextColor)) {
            agentPanelMutedTextColor = colors.mutedText();
            changed = true;
        }
        if (isBlank(agentPanelAccentColor)) {
            agentPanelAccentColor = colors.accent();
            changed = true;
        }
        if (isBlank(agentPanelErrorColor)) {
            agentPanelErrorColor = colors.error();
            changed = true;
        }
        return changed;
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
        applyTo(target, false);
    }

    /**
     * Applies this theme's values to the given ConnectionSettings.
     * By default, only colors/cursor are applied; font can be enabled explicitly.
     */
    public void applyTo(ConnectionSettings target, boolean includeFont) {
        if (includeFont) {
            target.setFontFamily(getFontFamily());
            target.setFontSize(getFontSize());
        }
        target.setForegroundColor(getForegroundColor());
        target.setBackgroundColor(getBackgroundColor());
        target.setCursorColor(getCursorColor());
        // Theme.applyTo() does not change cursor style; effective cursor (shape/blink) is set from settings in TerminalView/applySettings.
    }

    /**
     * Creates ConnectionSettings from this theme.
     */
    public ConnectionSettings toConnectionSettings() {
        ConnectionSettings s = new ConnectionSettings();
        applyTo(s, true);
        return s;
    }

    private AgentPanelColors deriveAgentPanelColors() {
        Rgb background = parseHexColor(getBackgroundColor(), new Rgb(30, 30, 30));
        Rgb foreground = parseHexColor(getForegroundColor(), new Rgb(255, 255, 255));
        Rgb accent = deriveAccentColor(parseHexColor(getCursorColor(), new Rgb(24, 194, 110)), background);
        boolean dark = luminance(background) < 0.55;
        Rgb error = dark ? new Rgb(227, 106, 77) : new Rgb(180, 35, 24);
        return new AgentPanelColors(
            toHex(mix(background, accent, dark ? 0.18 : 0.08)),
            toHex(mix(background, accent, dark ? 0.42 : 0.30)),
            toHex(foreground),
            toHex(mix(foreground, background, dark ? 0.35 : 0.45)),
            toHex(accent),
            toHex(error));
    }

    private static Rgb deriveAccentColor(Rgb cursor, Rgb background) {
        if (saturation(cursor) >= 0.12 && contrastDistance(cursor, background) >= 55) {
            return cursor;
        }
        return luminance(background) < 0.55 ? new Rgb(24, 194, 110) : new Rgb(8, 127, 91);
    }

    private static Rgb parseHexColor(String color, Rgb fallback) {
        if (color == null) {
            return fallback;
        }
        String value = color.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6) {
            return fallback;
        }
        try {
            return new Rgb(
                Integer.parseInt(value.substring(0, 2), 16),
                Integer.parseInt(value.substring(2, 4), 16),
                Integer.parseInt(value.substring(4, 6), 16));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Rgb mix(Rgb left, Rgb right, double ratio) {
        double clampedRatio = Math.max(0.0, Math.min(1.0, ratio));
        double leftRatio = 1.0 - clampedRatio;
        return new Rgb(
            (int) Math.round(left.red() * leftRatio + right.red() * clampedRatio),
            (int) Math.round(left.green() * leftRatio + right.green() * clampedRatio),
            (int) Math.round(left.blue() * leftRatio + right.blue() * clampedRatio));
    }

    private static double luminance(Rgb color) {
        return (0.299 * color.red() + 0.587 * color.green() + 0.114 * color.blue()) / 255.0;
    }

    private static double saturation(Rgb color) {
        int max = Math.max(color.red(), Math.max(color.green(), color.blue()));
        int min = Math.min(color.red(), Math.min(color.green(), color.blue()));
        return max == 0 ? 0.0 : (double) (max - min) / max;
    }

    private static int contrastDistance(Rgb left, Rgb right) {
        return Math.abs(left.red() - right.red())
            + Math.abs(left.green() - right.green())
            + Math.abs(left.blue() - right.blue());
    }

    private static String toHex(Rgb color) {
        return String.format("#%02X%02X%02X", color.red(), color.green(), color.blue());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Rgb(int red, int green, int blue) {
    }

    private record AgentPanelColors(
        String background,
        String border,
        String text,
        String mutedText,
        String accent,
        String error) {
    }
}
