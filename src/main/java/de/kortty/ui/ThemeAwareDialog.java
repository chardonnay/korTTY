package de.kortty.ui;

import javafx.scene.control.Dialog;

/**
 * Base dialog that automatically applies the active KorTTY theme.
 */
public class ThemeAwareDialog<R> extends Dialog<R> {

    public ThemeAwareDialog() {
        DialogThemeHelper.applyTheme(this);
    }
}
