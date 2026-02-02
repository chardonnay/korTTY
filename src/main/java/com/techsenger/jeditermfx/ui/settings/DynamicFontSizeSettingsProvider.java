package com.techsenger.jeditermfx.ui.settings;

import javafx.beans.property.SimpleFloatProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.scene.text.Font;

import java.util.concurrent.CopyOnWriteArrayList;

import static com.techsenger.jeditermfx.core.util.Platform.isMacOS;
import static com.techsenger.jeditermfx.core.util.Platform.isWindows;

/**
 * Settings provider that supports dynamic font size changes at runtime.
 * Font size can be adjusted via Ctrl+Plus/Ctrl+Minus without reconnecting the terminal session.
 */
public class DynamicFontSizeSettingsProvider extends DefaultSettingsProvider implements MutableFontSizeProvider {

    private static final Logger logger = LoggerFactory.getLogger(DynamicFontSizeSettingsProvider.class);
    private static final float DEFAULT_FONT_SIZE = 14f;

    private final SimpleFloatProperty fontSizeProperty = new SimpleFloatProperty(DEFAULT_FONT_SIZE);
    private final CopyOnWriteArrayList<Runnable> fontSizeListeners = new CopyOnWriteArrayList<>();

    public DynamicFontSizeSettingsProvider() {
        this(DEFAULT_FONT_SIZE);
    }

    public DynamicFontSizeSettingsProvider(float initialFontSize) {
        setFontSize(initialFontSize);
    }

    @Override
    public float getFontSize() {
        return fontSizeProperty.get();
    }

    @Override
    public void setFontSize(float size) {
        float clamped = clampFontSize(size);
        if (fontSizeProperty.get() != clamped) {
            fontSizeProperty.set(clamped);
            notifyFontSizeListeners();
        }
    }

    @Override
    public void addFontSizeListener(Runnable listener) {
        if (listener != null) {
            fontSizeListeners.add(listener);
        }
    }

    @Override
    public void removeFontSizeListener(Runnable listener) {
        fontSizeListeners.remove(listener);
    }

    @Override
    public Font getTerminalFont() {
        String fontName;
        if (isWindows()) {
            fontName = "Consolas";
        } else if (isMacOS()) {
            fontName = "Menlo";
        } else {
            fontName = "Monospaced";
        }
        return Font.font(fontName, getTerminalFontSize());
    }

    @Override
    public float getTerminalFontSize() {
        return fontSizeProperty.get();
    }

    public boolean supportsDynamicFontSize() {
        return true;
    }

    /**
     * Increases font size by the given delta (e.g. 2). Respects max font size.
     */
    public void increaseFontSize(float delta) {
        setFontSize(getFontSize() + delta);
    }

    /**
     * Decreases font size by the given delta (e.g. 2). Respects min font size.
     */
    public void decreaseFontSize(float delta) {
        setFontSize(getFontSize() - delta);
    }

    /**
     * Resets font size to default (14pt).
     */
    public void resetFontSize() {
        setFontSize(DEFAULT_FONT_SIZE);
    }

    private float clampFontSize(float size) {
        // Upstream DefaultSettingsProvider doesn't expose min/max, so use safe defaults here.
        float min = 6f;
        float max = 48f;
        return Math.max(min, Math.min(max, size));
    }

    private void notifyFontSizeListeners() {
        for (Runnable listener : fontSizeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.warn("Font size listener failed", e);
            }
        }
    }
}
