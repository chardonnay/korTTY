package com.techsenger.jeditermfx.ui.settings;

/**
 * Interface for settings providers that support dynamic font size changes at runtime.
 * When implemented, the terminal can resize its font without reconnecting the session.
 */
public interface MutableFontSizeProvider {

    /**
     * Gets the current font size in points.
     */
    float getFontSize();

    /**
     * Sets the font size in points. Value is clamped to min/max from UserSettingsProvider.
     * Triggers font reinitialization in the terminal.
     */
    void setFontSize(float size);

    /**
     * Adds a listener that is notified when font size changes.
     */
    void addFontSizeListener(Runnable listener);

    /**
     * Removes a previously added listener.
     */
    void removeFontSizeListener(Runnable listener);

    /**
     * Increases font size by the given delta. Respects max font size from UserSettingsProvider.
     */
    default void increaseFontSize(float delta) {
        setFontSize(getFontSize() + delta);
    }

    /**
     * Decreases font size by the given delta. Respects min font size from UserSettingsProvider.
     */
    default void decreaseFontSize(float delta) {
        setFontSize(getFontSize() - delta);
    }

    /**
     * Resets font size to default (14pt).
     */
    default void resetFontSize() {
        setFontSize(14);
    }
}
