package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * Color and cursor profile for the snippet editor.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SnippetEditorProfile {

    @XmlElement
    private String id;

    @XmlElement
    private String name;

    @XmlElement
    private boolean builtIn;

    @XmlElement
    private String foregroundColor;

    @XmlElement
    private String backgroundColor;

    @XmlElement
    private String cursorStyle;

    @XmlElement
    private String cursorColor;

    @XmlElement
    private String commentColor;

    @XmlElement
    private String stringColor;

    @XmlElement
    private String numberColor;

    @XmlElement
    private String booleanColor;

    @XmlElement
    private String keyColor;

    @XmlElement
    private String keywordColor;

    @XmlElement
    private String sectionColor;

    @XmlElement
    private String variableColor;

    @XmlElement
    private String braceColor;

    public SnippetEditorProfile() {
    }

    public SnippetEditorProfile(SnippetEditorProfile source) {
        if (source == null) {
            return;
        }
        this.id = source.id;
        this.name = source.name;
        this.builtIn = source.builtIn;
        this.foregroundColor = source.foregroundColor;
        this.backgroundColor = source.backgroundColor;
        this.cursorStyle = source.cursorStyle;
        this.cursorColor = source.cursorColor;
        this.commentColor = source.commentColor;
        this.stringColor = source.stringColor;
        this.numberColor = source.numberColor;
        this.booleanColor = source.booleanColor;
        this.keyColor = source.keyColor;
        this.keywordColor = source.keywordColor;
        this.sectionColor = source.sectionColor;
        this.variableColor = source.variableColor;
        this.braceColor = source.braceColor;
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

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
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

    public String getCursorStyle() {
        return cursorStyle;
    }

    public void setCursorStyle(String cursorStyle) {
        this.cursorStyle = cursorStyle;
    }

    public String getCursorColor() {
        return cursorColor;
    }

    public void setCursorColor(String cursorColor) {
        this.cursorColor = cursorColor;
    }

    public String getCommentColor() {
        return commentColor;
    }

    public void setCommentColor(String commentColor) {
        this.commentColor = commentColor;
    }

    public String getStringColor() {
        return stringColor;
    }

    public void setStringColor(String stringColor) {
        this.stringColor = stringColor;
    }

    public String getNumberColor() {
        return numberColor;
    }

    public void setNumberColor(String numberColor) {
        this.numberColor = numberColor;
    }

    public String getBooleanColor() {
        return booleanColor;
    }

    public void setBooleanColor(String booleanColor) {
        this.booleanColor = booleanColor;
    }

    public String getKeyColor() {
        return keyColor;
    }

    public void setKeyColor(String keyColor) {
        this.keyColor = keyColor;
    }

    public String getKeywordColor() {
        return keywordColor;
    }

    public void setKeywordColor(String keywordColor) {
        this.keywordColor = keywordColor;
    }

    public String getSectionColor() {
        return sectionColor;
    }

    public void setSectionColor(String sectionColor) {
        this.sectionColor = sectionColor;
    }

    public String getVariableColor() {
        return variableColor;
    }

    public void setVariableColor(String variableColor) {
        this.variableColor = variableColor;
    }

    public String getBraceColor() {
        return braceColor;
    }

    public void setBraceColor(String braceColor) {
        this.braceColor = braceColor;
    }

    @Override
    public String toString() {
        return name != null && !name.isBlank() ? name : "Snippet editor profile";
    }
}
