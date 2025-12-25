package de.kortty.model;

import jakarta.xml.bind.annotation.*;

/**
 * Terminal display settings for a connection.
 * Can be specific to a connection or use global defaults.
 */
@XmlRootElement(name = "settings")
@XmlAccessorType(XmlAccessType.FIELD)
public class ConnectionSettings {
    
    @XmlElement
    private boolean useGlobalSettings = true;
    
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
    private String selectionColor = "#3399FF";
    
    @XmlElement
    private int terminalColumns = 80;
    
    @XmlElement
    private int terminalRows = 24;
    
    @XmlElement
    private int scrollbackLines = 10000;
    
    @XmlElement
    private boolean boldAsBright = true;
    
    @XmlElement
    private String encoding = "UTF-8";
    
    @XmlElement
    private boolean closeWithoutConfirmation = false;
    
    // ANSI Colors
    @XmlElement
    private String ansiBlack = "#000000";
    @XmlElement
    private String ansiRed = "#CD0000";
    @XmlElement
    private String ansiGreen = "#00CD00";
    @XmlElement
    private String ansiYellow = "#CDCD00";
    @XmlElement
    private String ansiBlue = "#0000EE";
    @XmlElement
    private String ansiMagenta = "#CD00CD";
    @XmlElement
    private String ansiCyan = "#00CDCD";
    @XmlElement
    private String ansiWhite = "#E5E5E5";
    
    // Bright ANSI Colors
    @XmlElement
    private String ansiBrightBlack = "#7F7F7F";
    @XmlElement
    private String ansiBrightRed = "#FF0000";
    @XmlElement
    private String ansiBrightGreen = "#00FF00";
    @XmlElement
    private String ansiBrightYellow = "#FFFF00";
    @XmlElement
    private String ansiBrightBlue = "#5C5CFF";
    @XmlElement
    private String ansiBrightMagenta = "#FF00FF";
    @XmlElement
    private String ansiBrightCyan = "#00FFFF";
    @XmlElement
    private String ansiBrightWhite = "#FFFFFF";
    
    public ConnectionSettings() {
    }
    
    public ConnectionSettings(ConnectionSettings other) {
        this.useGlobalSettings = other.useGlobalSettings;
        this.fontFamily = other.fontFamily;
        this.fontSize = other.fontSize;
        this.foregroundColor = other.foregroundColor;
        this.backgroundColor = other.backgroundColor;
        this.cursorColor = other.cursorColor;
        this.selectionColor = other.selectionColor;
        this.terminalColumns = other.terminalColumns;
        this.terminalRows = other.terminalRows;
        this.scrollbackLines = other.scrollbackLines;
        this.boldAsBright = other.boldAsBright;
        this.encoding = other.encoding;
        this.closeWithoutConfirmation = other.closeWithoutConfirmation;
        copyAnsiColors(other);
    }
    
    private void copyAnsiColors(ConnectionSettings other) {
        this.ansiBlack = other.ansiBlack;
        this.ansiRed = other.ansiRed;
        this.ansiGreen = other.ansiGreen;
        this.ansiYellow = other.ansiYellow;
        this.ansiBlue = other.ansiBlue;
        this.ansiMagenta = other.ansiMagenta;
        this.ansiCyan = other.ansiCyan;
        this.ansiWhite = other.ansiWhite;
        this.ansiBrightBlack = other.ansiBrightBlack;
        this.ansiBrightRed = other.ansiBrightRed;
        this.ansiBrightGreen = other.ansiBrightGreen;
        this.ansiBrightYellow = other.ansiBrightYellow;
        this.ansiBrightBlue = other.ansiBrightBlue;
        this.ansiBrightMagenta = other.ansiBrightMagenta;
        this.ansiBrightCyan = other.ansiBrightCyan;
        this.ansiBrightWhite = other.ansiBrightWhite;
    }
    
    // Getters and Setters
    
    public boolean isUseGlobalSettings() {
        return useGlobalSettings;
    }
    
    public void setUseGlobalSettings(boolean useGlobalSettings) {
        this.useGlobalSettings = useGlobalSettings;
    }
    
    public String getFontFamily() {
        return fontFamily;
    }
    
    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }
    
    public int getFontSize() {
        return fontSize;
    }
    
    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }
    
    public String getForegroundColor() {
        return foregroundColor;
    }
    
    public void setForegroundColor(String foregroundColor) {
        this.foregroundColor = foregroundColor;
    }
    
    public String getBackgroundColor() {
        return backgroundColor;
    }
    
    public void setBackgroundColor(String backgroundColor) {
        this.backgroundColor = backgroundColor;
    }
    
    public String getCursorColor() {
        return cursorColor;
    }
    
    public void setCursorColor(String cursorColor) {
        this.cursorColor = cursorColor;
    }
    
    public String getSelectionColor() {
        return selectionColor;
    }
    
    public void setSelectionColor(String selectionColor) {
        this.selectionColor = selectionColor;
    }
    
    public int getTerminalColumns() {
        return terminalColumns;
    }
    
    public void setTerminalColumns(int terminalColumns) {
        this.terminalColumns = terminalColumns;
    }
    
    public int getTerminalRows() {
        return terminalRows;
    }
    
    public void setTerminalRows(int terminalRows) {
        this.terminalRows = terminalRows;
    }
    
    public int getScrollbackLines() {
        return scrollbackLines;
    }
    
    public void setScrollbackLines(int scrollbackLines) {
        this.scrollbackLines = scrollbackLines;
    }
    
    public boolean isBoldAsBright() {
        return boldAsBright;
    }
    
    public void setBoldAsBright(boolean boldAsBright) {
        this.boldAsBright = boldAsBright;
    }
    
    public String getEncoding() {
        return encoding;
    }
    
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }
    
    public boolean isCloseWithoutConfirmation() {
        return closeWithoutConfirmation;
    }
    
    public void setCloseWithoutConfirmation(boolean closeWithoutConfirmation) {
        this.closeWithoutConfirmation = closeWithoutConfirmation;
    }
    
    public String getAnsiColor(int index, boolean bright) {
        if (bright) {
            return switch (index) {
                case 0 -> ansiBrightBlack;
                case 1 -> ansiBrightRed;
                case 2 -> ansiBrightGreen;
                case 3 -> ansiBrightYellow;
                case 4 -> ansiBrightBlue;
                case 5 -> ansiBrightMagenta;
                case 6 -> ansiBrightCyan;
                case 7 -> ansiBrightWhite;
                default -> ansiBrightWhite;
            };
        } else {
            return switch (index) {
                case 0 -> ansiBlack;
                case 1 -> ansiRed;
                case 2 -> ansiGreen;
                case 3 -> ansiYellow;
                case 4 -> ansiBlue;
                case 5 -> ansiMagenta;
                case 6 -> ansiCyan;
                case 7 -> ansiWhite;
                default -> ansiWhite;
            };
        }
    }
    
    // ANSI color getters and setters
    public String getAnsiBlack() { return ansiBlack; }
    public void setAnsiBlack(String ansiBlack) { this.ansiBlack = ansiBlack; }
    
    public String getAnsiRed() { return ansiRed; }
    public void setAnsiRed(String ansiRed) { this.ansiRed = ansiRed; }
    
    public String getAnsiGreen() { return ansiGreen; }
    public void setAnsiGreen(String ansiGreen) { this.ansiGreen = ansiGreen; }
    
    public String getAnsiYellow() { return ansiYellow; }
    public void setAnsiYellow(String ansiYellow) { this.ansiYellow = ansiYellow; }
    
    public String getAnsiBlue() { return ansiBlue; }
    public void setAnsiBlue(String ansiBlue) { this.ansiBlue = ansiBlue; }
    
    public String getAnsiMagenta() { return ansiMagenta; }
    public void setAnsiMagenta(String ansiMagenta) { this.ansiMagenta = ansiMagenta; }
    
    public String getAnsiCyan() { return ansiCyan; }
    public void setAnsiCyan(String ansiCyan) { this.ansiCyan = ansiCyan; }
    
    public String getAnsiWhite() { return ansiWhite; }
    public void setAnsiWhite(String ansiWhite) { this.ansiWhite = ansiWhite; }
    
    public String getAnsiBrightBlack() { return ansiBrightBlack; }
    public void setAnsiBrightBlack(String ansiBrightBlack) { this.ansiBrightBlack = ansiBrightBlack; }
    
    public String getAnsiBrightRed() { return ansiBrightRed; }
    public void setAnsiBrightRed(String ansiBrightRed) { this.ansiBrightRed = ansiBrightRed; }
    
    public String getAnsiBrightGreen() { return ansiBrightGreen; }
    public void setAnsiBrightGreen(String ansiBrightGreen) { this.ansiBrightGreen = ansiBrightGreen; }
    
    public String getAnsiBrightYellow() { return ansiBrightYellow; }
    public void setAnsiBrightYellow(String ansiBrightYellow) { this.ansiBrightYellow = ansiBrightYellow; }
    
    public String getAnsiBrightBlue() { return ansiBrightBlue; }
    public void setAnsiBrightBlue(String ansiBrightBlue) { this.ansiBrightBlue = ansiBrightBlue; }
    
    public String getAnsiBrightMagenta() { return ansiBrightMagenta; }
    public void setAnsiBrightMagenta(String ansiBrightMagenta) { this.ansiBrightMagenta = ansiBrightMagenta; }
    
    public String getAnsiBrightCyan() { return ansiBrightCyan; }
    public void setAnsiBrightCyan(String ansiBrightCyan) { this.ansiBrightCyan = ansiBrightCyan; }
    
    public String getAnsiBrightWhite() { return ansiBrightWhite; }
    public void setAnsiBrightWhite(String ansiBrightWhite) { this.ansiBrightWhite = ansiBrightWhite; }
}
